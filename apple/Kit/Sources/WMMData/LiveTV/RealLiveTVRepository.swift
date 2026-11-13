import Foundation
import Observation
import WMMAPI
import WMMModel

@MainActor @Observable
final class RealLiveTVRepository: LiveTVRepository {
    private let store: LocalStore
    private let events: ServerEvents?
    private var loadedRevision: Int?
    private var loadedCapabilities: LiveTVCapabilities?
    private var loadedChannels: [LiveChannel] = []

    init(store: LocalStore, events: ServerEvents? = nil) { self.store = store; self.events = events }
    var contextID: String? {
        guard let current = store.current, store.user != nil else { return nil }
        return "\(current.id):\(store.revision)"
    }
    var capabilities: LiveTVCapabilities? { loadedRevision == store.revision ? loadedCapabilities : nil }
    var channels: [LiveChannel] { loadedRevision == store.revision ? loadedChannels : [] }

    func refresh() async throws {
        let revision = store.revision
        guard let server = store.current?.server, server.apiLevel >= liveTVAPILevel, store.user != nil else {
            loadedRevision = revision; loadedCapabilities = .init(); loadedChannels = []
            return
        }
        let c = try await store.call { try await $0.getLiveTVCapabilities().ok.body.json }
        let capabilities = LiveTVCapabilities(enabled: c.enabled, allowed: c.allowed, playback: c.playback,
                                               bufferSeconds: c.bufferSeconds.map(Int.init) ?? 60,
                                               pauseSeconds: c.pauseSeconds.map(Int.init) ?? 300,
                                               guide: c.guide && server.apiLevel >= liveGuideAPILevel,
                                               extras: server.apiLevel >= liveExtrasAPILevel,
                                               recording: server.apiLevel >= liveRecordingAPILevel && c.recording == true,
                                               canRecord: server.apiLevel >= liveRecordingAPILevel && c.canRecord == true,
                                               automatic: server.apiLevel >= liveAutomaticAPILevel && c.automatic == true)
        // Commit access revocation immediately; a failing channel refresh must not preserve an allowed tab.
        guard revision == store.revision else { throw CancellationError() }
        loadedRevision = revision; loadedCapabilities = capabilities
        guard capabilities.available else { loadedChannels = []; return }
        do {
            let reply = try await store.call { try await $0.listLiveTVChannels().ok.body.json }
            guard revision == store.revision else { throw CancellationError() }
            let base = server.baseURL
            loadedChannels = reply.items.map { LiveTVMapping.channel($0, base: capabilities.guide ? base : nil) }
        } catch {
            if revision == store.revision { loadedChannels = [] }
            throw error
        }
    }

    func guide(start: Date, hours: Int) async throws -> LiveGuide {
        let revision = store.revision
        guard let server = store.current?.server, server.apiLevel >= liveGuideAPILevel, capabilities?.listings == true else {
            throw LiveTVError(reason: "unavailable")
        }
        let reply = try await store.call {
            try await $0.getLiveTVGuide(query: .init(start: start, hours: Int64(min(max(hours, 1), 6)))).ok.body.json
        }
        // A reply for the previous sign-in or server never reaches the screens.
        guard revision == store.revision else { throw CancellationError() }
        return LiveTVMapping.guide(reply, base: server.baseURL)
    }

    // MARK: Favourites, search and reminders (API level 15)

    /// The server in use when it keeps favourites and reminders, with the revision a reply must still match.
    private func extrasServer() throws -> (server: Server, revision: Int) {
        guard let server = store.current?.server, server.apiLevel >= liveExtrasAPILevel, capabilities?.extrasAvailable == true else {
            throw LiveTVError(reason: "unavailable")
        }
        return (server, store.revision)
    }

    func setFavourite(_ favourite: Bool, channelID: String) async throws {
        let (_, revision) = try extrasServer()
        if favourite {
            _ = try await store.call { try await $0.addLiveTVFavourite(path: .init(id: channelID)).noContent }
        } else {
            _ = try await store.call { try await $0.removeLiveTVFavourite(path: .init(id: channelID)).noContent }
        }
        guard revision == store.revision else { throw CancellationError() }
        if let i = loadedChannels.firstIndex(where: { $0.id == channelID }) { loadedChannels[i].favourite = favourite }
    }

    func searchListings(_ query: String, limit: Int) async throws -> [LiveSearchHit] {
        let (server, revision) = try extrasServer()
        let q = String(query.trimmingCharacters(in: .whitespacesAndNewlines).prefix(100))
        guard q.count >= liveSearchMinimumLength else { return [] }
        let reply = try await store.call {
            try await $0.searchLiveTVGuide(query: .init(q: q, limit: Int64(min(max(limit, 1), 50)))).ok.body.json
        }
        guard revision == store.revision else { throw CancellationError() }
        return reply.items.map { hit in
            LiveSearchHit(channel: LiveTVMapping.channel(hit.channel, base: server.baseURL),
                          programme: LiveTVMapping.programme(hit.programme, base: server.baseURL))
        }.filter { $0.programme.end > $0.programme.start }
    }

    func reminders() async throws -> LiveReminders {
        let (_, revision) = try extrasServer()
        let reply = try await store.call { try await $0.listLiveTVReminders().ok.body.json }
        guard revision == store.revision else { throw CancellationError() }
        return LiveReminders(items: reply.items.map(LiveTVMapping.reminder), leadMinutes: Int(reply.leadMinutes))
    }

    func remind(programmeID: String) async throws -> LiveReminder {
        let (_, revision) = try extrasServer()
        let reply = try await store.call { try await $0.setLiveTVReminder(body: .json(.init(programmeId: programmeID))).ok.body.json }
        guard revision == store.revision else { throw CancellationError() }
        return LiveTVMapping.reminder(reply)
    }

    func removeReminder(id: String) async throws {
        _ = try extrasServer()
        do { _ = try await store.call { try await $0.deleteLiveTVReminder(path: .init(id: id)).noContent } }
        catch let error as APIError where error.status == 404 { /* Already gone. */ }
    }

    func setReminderLead(minutes: Int) async throws {
        _ = try extrasServer()
        guard LiveReminderLead.choices.contains(minutes) else { throw LiveTVError(reason: "unavailable") }
        _ = try await store.call { try await $0.updatePreferences(body: .json(.init(liveTVReminderMinutes: Int64(minutes)))).ok.body.json }
    }

    func changes() -> AsyncStream<Void> {
        events?.changes(.liveTV) ?? AsyncStream { $0.finish() }
    }

    func liveChanges() -> AsyncStream<LiveTVChange> {
        events?.liveChanges() ?? AsyncStream { $0.finish() }
    }

    func makeSessionGateway() throws -> any LiveSessionGateway {
        guard (store.current?.server.apiLevel ?? 0) >= liveTVAPILevel else { throw LiveTVError(reason: "unavailable") }
        let session = try store.signedIn()
        return APILiveSessionGateway(api: session.api, clientName: store.client.name,
                                     apiLevel: store.current?.server.apiLevel ?? 0, automaticAvailable: capabilities?.automatic == true)
    }
}

/// Its ServerAPI already captured the original sign-in token; cleanup keeps that identity after a switch.
public struct APILiveSessionGateway: LiveSessionGateway {
    private let api: ServerAPI
    private let clientName: String
    private let apiLevel: Int
    private let automaticAvailable: Bool
    public init(api: ServerAPI, clientName: String, apiLevel: Int = liveTVAPILevel, automaticAvailable: Bool = false) {
        self.api = api; self.clientName = clientName; self.apiLevel = apiLevel; self.automaticAvailable = automaticAvailable
    }

    public func start(channelID: String, quality: LiveQuality, requestID: String) async throws -> LiveSession {
        try await start(channelID: channelID, quality: quality, requestID: requestID, profile: nil)
    }
    public func start(channelID: String, quality: LiveQuality, requestID: String, profile: PlaybackProfile?) async throws -> LiveSession {
        let automatic = quality == .auto && apiLevel >= liveAutomaticAPILevel && automaticAvailable && profile != nil
        let selected: LiveQuality = quality == .auto && !automatic ? .p1080 : quality
        return try await liveCall {
            let response = try await api.call {
                try await $0.startLiveTV(body: .json(.init(autoRecovery: automatic ? true : nil,
                                                          channelId: channelID, clientName: String(clientName.prefix(128)),
                                                          profile: apiLevel >= liveProfileAPILevel ? profile?.liveProfile : nil,
                                                          quality: .init(rawValue: selected.rawValue), requestId: requestID))).ok.body.json
            }
            do { return try LiveTVMapping.session(response, on: api) }
            catch {
                // Even an unusable reply may already own a tuner. Release it with the captured sign-in.
                try? await stop(sessionID: response.id)
                throw error
            }
        }
    }
    public func status(sessionID: String) async throws -> LiveSession {
        return try await liveCall {
            let response = try await api.call { try await $0.getLiveTVSession(path: .init(id: sessionID)).ok.body.json }
            return try LiveTVMapping.session(response, on: api)
        }
    }
    public func heartbeat(sessionID: String, paused: Bool, interacted: Bool) async throws -> LiveSession {
        try await heartbeat(sessionID: sessionID, paused: paused, interacted: interacted, measurement: nil)
    }
    public func heartbeat(sessionID: String, paused: Bool, interacted: Bool, measurement: LiveMeasurement?) async throws -> LiveSession {
        try await liveCall {
            let response = try await api.call {
                let m = apiLevel >= liveAutomaticAPILevel ? measurement : nil
                return try await $0.heartbeatLiveTV(path: .init(id: sessionID), body: .json(.init(
                    bufferedMs: m?.bufferedMs.map(Int64.init), interacted: interacted, paused: paused,
                    stalls: m?.stalls.map(Int64.init), state: m?.state.apiState,
                    switchFailedGeneration: m?.switchFailedGeneration.map(Int64.init),
                    switchGeneration: m?.switchGeneration.map(Int64.init),
                    throughputAgeMs: m?.throughputAgeMs.map(Int64.init), throughputBps: m?.throughputBps.map(Int64.init)))).ok.body.json
            }
            return try LiveTVMapping.session(response, on: api)
        }
    }
    public func stop(sessionID: String) async throws {
        do { _ = try await api.call { try await $0.stopLiveTV(path: .init(id: sessionID)) } }
        catch let error as APIError where error.status == 404 { /* Already released. */ }
    }
    public func mediaToken() async throws -> MediaToken {
        let token = try await api.call { try await $0.createMediaToken().ok.body.json }
        return MediaToken(token: token.token, expiresAt: token.expiresAt, param: PlaybackMapping.nonBlank(token.param) ?? "access_token")
    }

    private func liveCall<T>(_ operation: () async throws -> T) async throws -> T {
        do { return try await operation() }
        catch let error as APIError {
            if error.status == 403 { throw LiveTVError(reason: "access_revoked") }
            let reason = error.message.lowercased().trimmingCharacters(in: .punctuationCharacters.union(.whitespacesAndNewlines))
            if ["tuners_busy", "server_busy", "no_signal", "tuner_unavailable"].contains(reason) { throw LiveTVError(reason: reason) }
            throw error
        }
    }
}

private extension PlaybackProfile {
    var liveProfile: Components.Schemas.LiveTVLiveProfile {
        .init(audioCodecs: audioCodecs.filter { ["aac", "ac3", "eac3"].contains($0) },
              hdrTypes: hdrTypes ?? [], interlaced: false,
              maxAudioChannels: maxAudioChannels.map(Int64.init), maxBitrate: maxBitrate.map(Int64.init),
              maxHeight: maxHeight.map(Int64.init), segmentFormats: ["mpegts", "fmp4"],
              videoCodecs: videoCodecs.filter { ["h264", "hevc", "hevc10"].contains($0) })
    }
}

private extension LivePlaybackState {
    var apiState: Components.Schemas.LiveTVHeartbeatInputBody.StatePayload {
        switch self { case .playing: .playing; case .paused: .paused; case .buffering: .buffering }
    }
}

enum LiveTVMapping {
    /// With `base`, the channel keeps its logo and now and next; without, it is a plain channel as before listings.
    static func channel(_ channel: Components.Schemas.LiveTVChannel, base: URL? = nil) -> LiveChannel {
        .init(id: channel.id, number: channel.number, name: channel.name, available: channel.available,
              reason: PlaybackMapping.nonBlank(channel.reason),
              logoURL: base.flatMap { picture(channel.logoUrl, base: $0) },
              now: base.flatMap { b in channel.now.map { programme($0, base: b) } },
              next: base.flatMap { b in channel.next.map { programme($0, base: b) } },
              favourite: channel.favourite ?? false)
    }

    static func reminder(_ r: Components.Schemas.LiveTVReminder) -> LiveReminder {
        .init(id: r.id, channelID: PlaybackMapping.nonBlank(r.channelId), number: r.number, channelName: PlaybackMapping.nonBlank(r.channelName),
              programmeID: r.programmeId, start: r.start, end: r.end, title: r.title, episodeTitle: PlaybackMapping.nonBlank(r.episodeTitle),
              season: r.season.map(Int.init), episode: r.episode.map(Int.init), state: .init(r.state))
    }

    static func guide(_ guide: Components.Schemas.LiveTVGuide, base: URL) -> LiveGuide {
        let state = LiveGuideState(enabled: guide.state.enabled, stale: guide.state.stale,
                                   updatedAt: guide.state.updatedAt, listedThrough: guide.state.listedThrough)
        return LiveGuide(start: guide.start, end: guide.end, state: state, channels: guide.channels.map { c in
            let channel = LiveChannel(id: c.id, number: c.number, name: c.name, available: c.available,
                                      reason: PlaybackMapping.nonBlank(c.reason), logoURL: picture(c.logoUrl, base: base),
                                      now: c.now.map { programme($0, base: base) }, next: c.next.map { programme($0, base: base) },
                                      favourite: c.favourite ?? false)
            return LiveGuideChannel(channel: channel, listings: c.listings,
                                    programmes: c.programmes.map { programme($0, base: base) }.filter { $0.end > $0.start })
        })
    }

    static func programme(_ p: Components.Schemas.LiveTVProgramme, base: URL) -> LiveProgramme {
        .init(id: p.id, start: p.start, end: p.end, title: p.title, episodeTitle: PlaybackMapping.nonBlank(p.episodeTitle),
              season: p.season.map(Int.init), episode: p.episode.map(Int.init), episodeLabel: PlaybackMapping.nonBlank(p.episodeLabel),
              airDate: PlaybackMapping.nonBlank(p.airDate), kind: p.kind.flatMap(LiveProgramme.Kind.init(rawValue:)),
              isNew: p.new ?? false, live: p.live ?? false, isRepeat: p._repeat ?? false, hd: p.hd ?? false,
              categories: (p.categories ?? []).compactMap { PlaybackMapping.nonBlank($0) }, rating: PlaybackMapping.nonBlank(p.rating),
              description: PlaybackMapping.nonBlank(p.description), descriptionFromTMDB: p.descriptionSource == "tmdb",
              pictureURL: picture(p.pictureUrl, base: base), pictureFromTMDB: p.pictureSource == "tmdb",
              stillURL: picture(p.stillUrl, base: base), posterURL: picture(p.posterUrl, base: base), reminder: p.reminder ?? false,
              recording: p.recording.map { .init(id: $0.id, state: .init(rawValue: $0.state) ?? .unknown, series: $0.series, canCancel: $0.canCancel ?? false,
                                                 reason: PlaybackMapping.nonBlank($0.reason)) },
              seriesKey: PlaybackMapping.nonBlank(p.seriesKey))
    }

    /// A picture path on this server. Anything elsewhere is dropped, so viewers never contact the listings provider.
    static func picture(_ path: String?, base: URL) -> URL? {
        guard let path = PlaybackMapping.nonBlank(path) else { return nil }
        if path.hasPrefix("https://") || path.hasPrefix("http://") {
            guard let url = URL(string: path), ServerAPI.owns(base, url) else { return nil }
            return url
        }
        guard path.hasPrefix("/"), !path.hasPrefix("//") else { return nil }
        return URL(string: base.absoluteString.trimmingCharacters(in: ["/"]) + path)
    }
    static func session(_ session: Components.Schemas.LiveTVSessionView, on api: ServerAPI) throws -> LiveSession {
        let state = LiveSessionState(rawValue: session.state) ?? .unknown
        let url: URL?
        if session.url.isEmpty { url = nil }
        else {
            let candidate = session.url.hasPrefix("https://") || session.url.hasPrefix("http://")
                ? URL(string: session.url) : URL(string: api.url(session.url).absoluteString)?.standardized
            guard let candidate, ServerAPI.owns(api.baseURL, candidate) else {
                throw APIError(status: nil, message: "The server sent a live stream outside this server.")
            }
            url = candidate
        }
        let directive = session.directive.map { planned -> LiveOutputDirective in
            let candidate = planned.url.hasPrefix("https://") || planned.url.hasPrefix("http://")
                ? URL(string: planned.url) : URL(string: api.url(planned.url).absoluteString)?.standardized
            return LiveOutputDirective(generation: Int(planned.generation),
                                       url: candidate.flatMap { ServerAPI.owns(api.baseURL, $0) ? $0 : nil }, reason: planned.reason)
        }
        return .init(id: session.id, channel: channel(session.channel), state: state, reason: PlaybackMapping.nonBlank(session.reason),
                     quality: LiveQuality(rawValue: session.quality) ?? .p1080, streamURL: url, paused: session.paused,
                     pauseRemainingSeconds: Int(session.pauseRemainingSeconds), bufferSeconds: Int(session.bufferSeconds),
                     takeover: session.takeoverAt.flatMap { at in
                         guard let id = session.takeoverRecordingId, let title = session.takeoverTitle else { return nil }
                         return .init(recordingID: id, title: title, at: at, canCancel: session.takeoverCanCancel ?? false)
                     }, automatic: session.automatic == true, actualQuality: session.actualQuality.flatMap(LiveQuality.init(rawValue:)), actualOutput: session.actualQuality,
                     outputGeneration: Int(session.outputGeneration ?? 0), directive: directive,
                     switchStatus: PlaybackMapping.nonBlank(session.switchStatus),
                     mode: PlaybackMapping.nonBlank(session.mode), modeReason: PlaybackMapping.nonBlank(session.modeReason))
    }
}

extension RealLiveTVRepository {
    private func recordingServer() throws -> (server: Server, revision: Int) {
        guard let server = store.current?.server, server.apiLevel >= liveRecordingAPILevel,
              capabilities?.recordingsAvailable == true else { throw LiveTVError(reason: "unavailable") }
        return (server, store.revision)
    }
    func recordings(section: LiveRecordingSection, cursor: String?) async throws -> LiveRecordingPage {
        let (server, revision) = try recordingServer()
        let reply = try await store.call {
            try await $0.listLiveTVRecordings(query: .init(section: .init(rawValue: section.rawValue), cursor: cursor)).ok.body.json
        }
        guard revision == store.revision else { throw CancellationError() }
        return .init(items: reply.items.map { LiveTVMapping.recording($0, base: server.baseURL) },
                     rules: (reply.rules ?? []).map(LiveTVMapping.recordingRule), nextCursor: PlaybackMapping.nonBlank(reply.nextCursor),
                     tuners: reply.tuners.map(Int.init), freeBytes: reply.freeBytes)
    }
    func record(programmeID: String, options: LiveRecordingOptions) async throws -> LiveRecording {
        let (server, revision) = try recordingServer()
        let result = try await store.call {
            try await $0.createLiveTVRecording(body: .json(.init(anyChannel: options.anyChannel, keep: Int64(options.keep), newOnly: options.newOnly,
                                                               padAfter: options.useServerPadding ? nil : Int64(options.padAfter), padBefore: options.useServerPadding ? nil : Int64(options.padBefore),
                                                               programmeId: programmeID, scope: options.series ? .series : .once))).ok.body.json
        }
        guard revision == store.revision else { throw CancellationError() }
        events?.notify(.liveTV)
        return LiveTVMapping.recording(result, base: server.baseURL)
    }
    func recordManual(_ request: LiveManualRecording) async throws -> LiveRecording {
        let (server, revision) = try recordingServer()
        if let message = request.validationMessage { throw APIError(status: 422, message: message) }
        let result = try await store.call {
            try await $0.createManualLiveTVRecording(body: .json(.init(channelId: request.channelID, end: request.end, name: request.name,
                                                                     _repeat: .init(rawValue: request.repeatKind.rawValue)!, start: request.start, zone: request.zone))).ok.body.json
        }
        guard revision == store.revision else { throw CancellationError() }
        events?.notify(.liveTV)
        return LiveTVMapping.recording(result, base: server.baseURL)
    }
    func editRecording(id: String, padBefore: Int?, padAfter: Int?, prefer: Bool) async throws -> LiveRecording {
        let (server, revision) = try recordingServer()
        let result = try await store.call {
            try await $0.updateLiveTVRecording(path: .init(id: id), body: .json(.init(padAfter: padAfter.map(Int64.init), padBefore: padBefore.map(Int64.init), prefer: prefer))).ok.body.json
        }
        guard revision == store.revision else { throw CancellationError() }
        events?.notify(.liveTV)
        return LiveTVMapping.recording(result, base: server.baseURL)
    }
    func preferRecording(id: String, over: String?) async throws -> LiveRecording {
        let (server, revision) = try recordingServer()
        let result = try await store.call {
            try await $0.updateLiveTVRecording(path: .init(id: id), body: .json(.init(over: over, prefer: true))).ok.body.json
        }
        guard revision == store.revision else { throw CancellationError() }
        events?.notify(.liveTV)
        return LiveTVMapping.recording(result, base: server.baseURL)
    }
    func cancelRecording(id: String) async throws {
        let (_, revision) = try recordingServer()
        do { _ = try await store.call { try await $0.cancelLiveTVRecording(path: .init(id: id)).noContent } }
        catch let error as APIError where error.status == 404 { /* Already gone. */ }
        guard revision == store.revision else { throw CancellationError() }
        events?.notify(.liveTV)
    }
    func editRecordingRule(id: String, options: LiveRecordingOptions) async throws -> LiveRecordingRule {
        let (_, revision) = try recordingServer()
        let result = try await store.call {
            try await $0.updateLiveTVRecordingRule(path: .init(id: id), body: .json(.init(anyChannel: options.anyChannel, keep: Int64(options.keep), newOnly: options.newOnly,
                                                                                      padAfter: Int64(options.padAfter), padBefore: Int64(options.padBefore)))).ok.body.json
        }
        guard revision == store.revision else { throw CancellationError() }
        events?.notify(.liveTV)
        return LiveTVMapping.recordingRule(result)
    }
    func deleteRecordingFile(id: String, recordAgain: Bool) async throws {
        let (_, revision) = try recordingServer()
        _ = try await store.call { try await $0.deleteLiveTVRecordingFile(path: .init(id: id), query: .init(recordAgain: recordAgain)).noContent }
        guard revision == store.revision else { throw CancellationError() }
        events?.notify(.liveTV); events?.notify(.library)
    }
    func removeRecordingRule(id: String) async throws {
        let (_, revision) = try recordingServer()
        do { _ = try await store.call { try await $0.deleteLiveTVRecordingRule(path: .init(id: id)).noContent } }
        catch let error as APIError where error.status == 404 { /* Already gone. */ }
        guard revision == store.revision else { throw CancellationError() }
        events?.notify(.liveTV)
    }
}

extension LiveTVMapping {
    static func recording(_ r: Components.Schemas.LiveTVRecording, base: URL) -> LiveRecording {
        .init(id: r.id, ruleID: PlaybackMapping.nonBlank(r.ruleId), userID: r.userId.map(Int.init), userName: r.userName,
              channelID: r.channelId, sourceID: r.sourceId, number: r.number, channelName: r.channelName,
              programmeID: PlaybackMapping.nonBlank(r.programmeId), title: r.title, episodeTitle: PlaybackMapping.nonBlank(r.episodeTitle),
              season: r.season.map(Int.init), episode: r.episode.map(Int.init), start: r.start, end: r.end,
              startedAt: r.startedAt, endedAt: r.endedAt, state: .init(rawValue: r.state) ?? .unknown, reason: PlaybackMapping.nonBlank(r.reason),
              series: r.series, padBefore: Int(r.padBefore), padAfter: Int(r.padAfter), bytes: r.bytes,
              gaps: r.gaps.map { .init(start: $0.start, end: $0.end, reason: $0.reason) }, mediaFileID: r.mediaFileId.map(Int.init),
              canCancel: r.canCancel, canDelete: r.canDelete ?? false, pictureURL: picture(r.pictureUrl, base: base),
              conflictsWith: (r.conflictsWith ?? []).map {
                  .init(id: $0.id, title: $0.title, number: $0.number, channelName: $0.channelName, start: $0.start, end: $0.end,
                        userName: $0.userName, running: $0.running)
              })
    }
    static func recordingRule(_ r: Components.Schemas.LiveTVRecordingRule) -> LiveRecordingRule {
        .init(id: r.id, kind: r.kind, title: r.title, userName: r.userName, channelID: PlaybackMapping.nonBlank(r.channelId),
              number: PlaybackMapping.nonBlank(r.number), seriesKey: PlaybackMapping.nonBlank(r.seriesKey), newOnly: r.newOnly,
              anyChannel: r.anyChannel, keep: Int(r.keep), padBefore: Int(r.padBefore), padAfter: Int(r.padAfter),
              repeatKind: r._repeat, zone: PlaybackMapping.nonBlank(r.zone), canCancel: r.canCancel)
    }
}
