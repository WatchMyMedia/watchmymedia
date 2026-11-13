import Foundation
import WMMAPI
import WMMModel

/// The player's server calls over the current server's ``ServerAPI``
/// (android/core/data/.../RealPlaybackGateway.kt). URLs come back absolute
/// and without a token; the player adds ``mediaToken()``'s `access_token`
/// itself (PLAN-CLIENTS.md §2.2). The profile goes as the server's level
/// takes it (``PlaybackProfile/forAPILevel(_:)``). Failures are ``APIError``
/// or ``SignedOutError``.
public struct APIPlaybackGateway: PlaybackGateway {
    private let api: @MainActor @Sendable () throws -> ServerAPI
    private let apiLevel: @MainActor @Sendable () -> Int?

    /// - Parameters:
    ///   - api: the current server's API, read on every call (`DataServices.api()`).
    ///   - apiLevel: that server's API level as last refreshed (`DataServices.apiLevel`); nil when not known.
    public init(api: @escaping @MainActor @Sendable () throws -> ServerAPI, apiLevel: @escaping @MainActor @Sendable () -> Int?) {
        self.api = api
        self.apiLevel = apiLevel
    }

    @MainActor public func bound() -> any PlaybackGateway {
        let captured = Result { try api() }
        let level = apiLevel()
        return APIPlaybackGateway(api: { try captured.get() }, apiLevel: { level })
    }

    public func decide(_ request: DecisionRequest) async throws -> PlaybackDecision {
        let api = try await api()
        let level = await apiLevel()
        if request.recordingID != nil && !ServerFeatures(apiLevel: level).liveRecordings { throw LiveTVError(reason: "unavailable") }
        let body = Components.Schemas.Request(
            audioIndex: request.audioIndex.map(Int64.init),
            autoRecovery: ServerFeatures(apiLevel: level).autoRecovery && request.quality == .auto && request.autoRecovery ? true : nil,
            burnIn: request.burnIn ? true : nil,
            client: PlaybackMapping.client(request.client),
            playId: request.playID,
            playlist: request.vod ? .vod : nil,
            profile: PlaybackMapping.profile(request.profile.forAPILevel(level).capped(by: request.quality)),
            quality: request.quality.rawValue,
            startMs: request.startMs.map { Int64(max($0, 0)) },
            subtitleId: request.subtitleID,
            subtitleMode: ServerFeatures(apiLevel: level).subtitlePreferences ? .init(rawValue: request.subtitleMode.rawValue) : nil,
            switchGeneration: ServerFeatures(apiLevel: level).autoRecovery ? request.switchGeneration : nil
        )
        let decision = try await api.call { client in
            if let recordingID = request.recordingID {
                return try await client.startLiveTVRecordingPlayback(path: .init(id: recordingID), body: .json(body)).ok.body.json
            }
            return try await client.startPlayback(path: .init(id: Int64(request.fileID)), body: .json(body)).ok.body.json
        }
        return try PlaybackMapping.decision(decision, on: api, apiLevel: level)
    }

    /// Every decision needs its token fetched beside it until the generated
    /// client has `Decision.mediaToken` (API level 28). Then this answers
    /// `!ServerFeatures(apiLevel: apiLevel()).tokenWithDecision`, and
    /// ``PlaybackMapping/decision(_:base:apiLevel:)`` maps the field.
    @MainActor public func needsTokenBeside(_ request: DecisionRequest) -> Bool { true }

    public func reportProgress(_ report: ProgressReport) async throws -> ProgressReply {
        let recovery = ServerFeatures(apiLevel: await apiLevel()).autoRecovery
        let body = Components.Schemas.ProgressInputBody(
            bufferedMs: Int64(max(report.bufferedMs, 0)),
            durationMs: Int64(max(report.durationMs, 0)),
            playId: report.playID.flatMap(PlaybackMapping.validPlayID),
            positionMs: Int64(max(report.positionMs, 0)),
            stallMs: report.stallMs.map { Int64(max($0, 0)) },
            stalls: report.stalls.map { Int64(max($0, 0)) },
            startupMs: report.startupMs.flatMap { $0 > 0 ? Int64($0) : nil },
            state: PlaybackMapping.state(report.state),
            switchFailedGeneration: recovery ? report.switchFailedGeneration : nil,
            switchGeneration: recovery ? report.switchGeneration : nil,
            throughputAgeMs: recovery ? report.throughputAgeMs.map { Int64(max($0, 0)) } : nil,
            throughputBps: report.throughputBps.flatMap { $0 > 0 ? Int64($0) : nil }
        )
        let reply = try await api().call { client in
            if let recordingID = report.recordingID {
                return try await client.reportLiveTVRecordingProgress(path: .init(id: recordingID), body: .json(body)).ok.body.json
            }
            return try await client.reportProgress(path: .init(id: Int64(report.fileID)), body: .json(body)).ok.body.json
        }
        let stop = reply.directive.flatMap { $0.action == .stop ? $0 : nil }
        let lower = reply.directive.flatMap { $0.action == .quality ? $0 : nil }
        return ProgressReply(
            completed: reply.completed,
            stopMessage: stop.map { PlaybackMapping.nonBlank($0.message) ?? "Playback was stopped by the server's admin." },
            newPlayID: PlaybackMapping.nonBlank(reply.playId).flatMap { $0 == report.playID ? nil : $0 },
            lowerTo: lower?.quality.flatMap { StreamQuality(rawValue: $0.rawValue) }.flatMap { $0 == .auto ? nil : $0 },
            lowerMessage: lower.flatMap { PlaybackMapping.nonBlank($0.message) },
            switchGeneration: lower?.generation
        )
    }

    /// Scoped to the server that issued it: the player puts it on that server's addresses only.
    public func mediaToken() async throws -> MediaToken {
        let api = try await api()
        let t = try await api.call { try await $0.createMediaToken().ok.body.json }
        return t.model(server: api.baseURL)
    }

    public func stopSession(_ sessionID: String) async throws {
        do {
            _ = try await api().call { try await $0.stopPlayback(path: .init(session: sessionID)) }
        } catch let error as APIError where error.status == 404 {
            // Already gone: it timed out, or another stop got there first.
        }
    }

    /// Servers older than the plans call answer with no plans.
    public func plans(profile: PlaybackProfile, quality: StreamQuality, fileIDs: [Int]) async throws -> [PlaybackPlan] {
        var seen = Set<Int>()
        let ids = fileIDs.filter { seen.insert($0).inserted }.prefix(PlaybackMapping.maxPlans)
        guard !ids.isEmpty else { return [] }
        let level = await apiLevel()
        let body = Components.Schemas.PlanInputBody(fileIds: ids.map(Int64.init), profile: PlaybackMapping.profile(profile.forAPILevel(level).capped(by: quality)))
        do {
            let list = try await api().call { try await $0.planPlayback(body: .json(body)).ok.body.json }
            return list.items.compactMap(PlaybackMapping.plan)
        } catch let error as APIError where PlaybackMapping.unknownRoute(error) {
            return []
        }
    }

    /// Servers without the search answer as if they had no provider.
    public func searchSubtitles(fileID: Int) async throws -> OnlineSubtitles? {
        do {
            let search = try await api().call { try await $0.searchSubtitles(path: .init(id: Int64(fileID))).ok.body.json }
            return PlaybackMapping.online(search)
        } catch let error as APIError where PlaybackMapping.unknownRoute(error) {
            return nil
        }
    }

    public func downloadSubtitle(fileID: Int, resultID: String) async throws -> String {
        try await api().call {
            try await $0.downloadSubtitle(path: .init(id: Int64(fileID)), body: .json(.init(resultId: resultID))).ok.body.json.id
        }
    }

    public func automaticSubtitle(fileID: Int, playID: String) async throws -> DecisionSubtitle? {
        guard ServerFeatures(apiLevel: await apiLevel()).subtitlePreferences else { return nil }
        let api = try await api()
        let result = try await api.call {
            try await $0.automaticSubtitle(path: .init(id: Int64(fileID)), body: .json(.init(playId: playID))).ok.body.json
        }
        if let error = PlaybackMapping.nonBlank(result.error) { throw APIError(status: nil, message: error) }
        guard let track = result.track, track.format == .webvtt, !track.burnInOnly else { return nil }
        return DecisionSubtitle(option: PlaybackMapping.subtitle(track), format: track.format.rawValue,
                                url: PlaybackMapping.nonBlank(track.url).map { PlaybackMapping.absolute($0, base: api.baseURL) })
    }

    public func cancelAutomaticSubtitle(fileID: Int, playID: String) async throws {
        guard ServerFeatures(apiLevel: await apiLevel()).subtitlePreferences else { return }
        _ = try await api().call {
            try await $0.automaticSubtitle(path: .init(id: Int64(fileID)), body: .json(.init(cancel: true, playId: playID))).ok.body.json
        }
    }

    public func subtitleText(_ url: URL) async throws -> String {
        let data = try await api().data(url)
        return String(decoding: data, as: UTF8.self)
    }
}

/// The generated client's playback types as the model's (android/core/data/.../Mapping.kt).
enum PlaybackMapping {
    /// The server takes at most 100 files a call; a movie has a handful.
    static let maxPlans = 100

    static func unknownRoute(_ error: APIError) -> Bool { error.status == 404 || error.status == 405 }

    /// Nil for nil, "" and whitespace, newlines too (``Mapping/nonBlank(_:)``).
    static func nonBlank(_ text: String?) -> String? { Mapping.nonBlank(text) }

    /// The server refuses any other play ID (a pattern in the API).
    static func validPlayID(_ id: String) -> String? {
        id.count == 16 && id.allSatisfy { ("0"..."9").contains($0) || ("a"..."f").contains($0) } ? id : nil
    }

    static func client(_ c: ClientInfo) -> Components.Schemas.Client { c.api }

    static func profile(_ p: PlaybackProfile) -> Components.Schemas.Profile {
        .init(
            audioCodecs: p.audioCodecs,
            containers: p.containers,
            hdr: p.hdr,
            hdrTypes: p.hdrTypes,
            maxAudioChannels: p.maxAudioChannels.map(Int64.init),
            maxBitrate: p.maxBitrate.map(Int64.init),
            maxHeight: p.maxHeight.map(Int64.init),
            maxWidth: p.maxWidth.map(Int64.init),
            name: p.name,
            selectsAudioTrack: p.selectsAudioTrack,
            subtitleFormats: p.subtitleFormats,
            videoCodecs: p.videoCodecs
        )
    }

    static func state(_ s: PlayState) -> Components.Schemas.ProgressInputBody.StatePayload {
        switch s {
        case .playing: .playing
        case .paused: .paused
        case .buffering: .buffering
        case .stopped: .stopped
        case .closed: .closed
        }
    }

    /// The decision as the model has it; its seek thumbnails only from a
    /// server at ``trickplayAPILevel`` or later (nil: not known).
    static func decision(_ d: Components.Schemas.Decision, on api: ServerAPI, apiLevel: Int? = trickplayAPILevel) throws -> PlaybackDecision {
        try decision(d, base: api.baseURL, apiLevel: apiLevel)
    }

    /// The decision with its relative URLs made absolute on the server at
    /// `base`. A download's is read with `apiLevel` 0: the copy plays from the
    /// device, and its seek thumbnails stay on the server. The media token
    /// goes on these addresses, so a stream elsewhere is refused, as Live
    /// TV's is, and a subtitle or seek thumbnails elsewhere are left out.
    ///
    /// From ``decisionTokenAPILevel`` the decision carries a media token:
    /// once the generated client has the field, map it here as
    /// `mediaToken: d.mediaToken.map { $0.model(server: base) }` (and
    /// ``APIPlaybackGateway/needsTokenBeside(_:)`` answers by level).
    static func decision(_ d: Components.Schemas.Decision, base: URL, apiLevel: Int? = trickplayAPILevel) throws -> PlaybackDecision {
        guard Components.Schemas.Decision.ModePayload.allCases.contains(d.mode),
              Components.Schemas.Decision._ProtocolPayload.allCases.contains(d._protocol),
              d.playlist.map({ Components.Schemas.Decision.PlaylistPayload.allCases.contains($0) }) ?? true else {
            throw APIError(status: nil, message: "This stream needs a newer version of WatchMyMedia.")
        }
        let stream = absolute(d.url, base: base)
        guard ServerAPI.owns(base, stream) else { throw APIError(status: nil, message: "The server sent a stream outside this server.") }
        let proto: StreamProtocol = d._protocol == .hls ? .hls : .file
        return PlaybackDecision(
            mode: mode(d.mode.rawValue),
            reasons: d.reasons,
            protocol: proto,
            // A file plays from its start, so its time is file time, as with a VOD playlist.
            vod: proto == .file || d.playlist == .vod,
            url: stream,
            mimeType: nonBlank(d.mimeType),
            playID: d.playId,
            sessionID: nonBlank(d.sessionId),
            durationMs: Int(d.durationMs),
            startMs: Int(d.startMs),
            resumeMs: Int(d.resumeMs),
            audio: d.audioTracks.map(audio),
            selectedAudio: d.audio.index >= 0 ? Int(d.audio.index) : nil,
            subtitles: d.subtitles.filter { Components.Schemas.SubtitleTrack.FormatPayload.allCases.contains($0.format) }.map { s in
                DecisionSubtitle(option: subtitle(s), format: s.format.rawValue, url: nonBlank(s.url).map { absolute($0, base: base) }.flatMap { ServerAPI.owns(base, $0) ? $0 : nil })
            },
            selectedSubtitleID: d.subtitleId,
            burnInSubtitleID: d.burnIn?.id,
            segments: d.segments.compactMap(segment),
            creditsMs: Int(d.creditsMs),
            watchedAtMs: Int(d.watchedAtMs),
            upNextMs: Int(d.upNextMs),
            next: d.nextEpisode.map(upNext),
            video: video(d.video),
            autoStep: d.auto.flatMap { StreamQuality(rawValue: $0.step.rawValue) },
            trickplay: ServerFeatures(apiLevel: apiLevel).trickplay ? d.trickplay.flatMap { trickplay($0, base: base) }.flatMap { t in
                URL(string: t.urlTemplate.replacingOccurrences(of: "{sheet}", with: "0")).map { ServerAPI.owns(base, $0) } == true ? t : nil
            } : nil,
            growing: ServerFeatures(apiLevel: apiLevel).liveRecordings ? d.growing.flatMap { value in
                guard let start = value.startedAt, let end = value.endsAt, end > start else { return nil }
                return .init(startedAt: start, endsAt: end)
            } : nil,
            copiedVideoCodec: copiedVideoCodec(d.video)
        )
    }

    /// The profile's key for a copied video, as the server classifies it
    /// (`classify` in server/internal/playback/decide.go): Dolby Vision kept
    /// is its profile's dovi key, 10-bit HEVC hevc10, the rest its codec.
    static func copiedVideoCodec(_ v: Components.Schemas.VideoPlan) -> String? {
        guard v.copy, let codec = nonBlank(v.codec)?.lowercased() else { return nil }
        if v.hdr == "dolbyvision", let profile = v.dvProfile, profile > 0 { return "dovi\(profile)" }
        if codec == "hevc" && (v.bitDepth ?? 8) > 8 { return "hevc10" }
        return codec
    }

    /// A decision's seek thumbnails with the sheet URL made absolute; nil
    /// for a manifest the app cannot draw from.
    static func trickplay(_ t: Components.Schemas.Trickplay, base: URL) -> Trickplay? {
        // The URL holds "{sheet}", which URL(string:) would refuse: join it by hand.
        let template = t.url.hasPrefix("http://") || t.url.hasPrefix("https://")
            ? t.url : base.absoluteString + (t.url.hasPrefix("/") ? t.url : "/" + t.url)
        return Trickplay(
            intervalMs: Int(t.intervalMs), width: Int(t.width), height: Int(t.height), columns: Int(t.columns), rows: Int(t.rows),
            count: Int(t.count), sheets: Int(t.sheets), urlTemplate: template
        )
    }

    static func plan(_ p: Components.Schemas.PlaybackPlan) -> PlaybackPlan? {
        guard Components.Schemas.PlaybackPlan.ModePayload.allCases.contains(p.mode),
              Components.Schemas.PlaybackPlan._ProtocolPayload.allCases.contains(p._protocol) else { return nil }
        return PlaybackPlan(
            fileID: Int(p.fileId),
            mode: mode(p.mode.rawValue),
            video: video(p.video),
            videoCodec: p.video.copy ? nil : codecLabel(p.video.codec),
            audioCodec: p.audio.copy ? nil : nonBlank(p.audio.codec).map(codecName),
            audioChannels: p.audio.copy ? nil : p.audio.channels.map(Int.init).flatMap { $0 > 0 ? $0 : nil },
            reasons: p.reasons
        )
    }

    /// A search's results, or nil when the server has no online provider.
    static func online(_ s: Components.Schemas.SubtitleSearch) -> OnlineSubtitles? {
        guard s.available else { return nil }
        return OnlineSubtitles(
            results: s.results.map {
                OnlineSubtitle(
                    id: $0.id, language: nonBlank($0.language), label: onlineLanguageLabel($0.language), release: $0.release,
                    hashMatch: $0.hashMatch, hearingImpaired: $0.hearingImpaired, forced: $0.forced, aiTranslated: $0.aiTranslated,
                    downloads: Int($0.downloads), trackID: nonBlank($0.trackId)
                )
            },
            attribution: nonBlank(s.attribution) ?? openSubtitlesCredit,
            canDownload: s.canDownload,
            remaining: s.quota.remaining >= 0 ? Int(s.quota.remaining) : nil,
            resetAt: s.quota.resetAt,
            error: nonBlank(s.error)
        )
    }

    static func mode(_ value: String) -> PlaybackMode {
        switch value {
        case "direct": .direct
        case "remux": .remux
        case "audio": .audioTranscode
        default: .transcode
        }
    }

    /// "/api/files/1/stream" on the server at `base`; absolute URLs are left alone.
    static func absolute(_ url: String, base: URL) -> URL {
        if url.hasPrefix("http://") || url.hasPrefix("https://"), let absolute = URL(string: url) { return absolute }
        return URL(string: base.absoluteString + (url.hasPrefix("/") ? url : "/" + url)) ?? base
    }

    static func audio(_ t: Components.Schemas.AudioTrack) -> AudioOption {
        AudioOption(
            index: Int(t.index),
            label: audioTrackLabel(language: t.language, codec: t.codec, channels: Int(t.channels), title: t.title, isDefault: t._default),
            language: nonBlank(t.language),
            isDefault: t._default,
            codec: nonBlank(t.codec),
            channels: t.channels > 0 ? Int(t.channels) : nil,
            title: nonBlank(t.title)
        )
    }

    static func subtitle(_ t: Components.Schemas.SubtitleTrack) -> SubtitleOption {
        SubtitleOption(
            id: t.id,
            label: subtitleTrackLabel(language: t.language, title: t.title, forced: t.forced),
            language: nonBlank(t.language),
            forced: t.forced,
            burnInOnly: t.burnInOnly,
            format: subtitleFormatName(t.codec),
            source: source(t.source?.rawValue, id: t.id, external: t.external),
            release: nonBlank(t.release),
            hearingImpaired: t.hearingImpaired ?? false
        )
    }

    /// The track's source, or from its ID when an older server says none: "d" is a download, "x" a file next to the video.
    private static func source(_ value: String?, id: String, external: Bool) -> SubtitleSource {
        switch value {
        case "opensubtitles": return .online
        case "sidecar": return .sidecar
        case "embedded": return .embedded
        default:
            if id.hasPrefix("d") { return .online }
            return external || id.hasPrefix("x") ? .sidecar : .embedded
        }
    }

    private static func segment(_ s: Components.Schemas.Segment) -> Segment? {
        SegmentKind(rawValue: s.kind.rawValue).map { Segment(kind: $0, startMs: Int(s.startMs), endMs: s.endMs.map(Int.init), final: s.final) }
    }

    private static func upNext(_ n: Components.Schemas.UpNext) -> UpNext {
        UpNext(
            fileID: Int(n.fileId),
            episodeID: Int(n.episodeId),
            showID: Int(n.showId),
            showTitle: n.showTitle,
            season: Int(n.seasonNumber),
            episode: Int(n.episodeNumber),
            title: n.title,
            stillPath: nonBlank(n.stillPath),
            runtimeMs: n.runtimeMin.flatMap { $0 > 0 ? Int($0) * 60_000 : nil },
            seasonFinale: n.seasonFinale ?? false
        )
    }

    /// The video sent; HDR mapped to SDR is SDR.
    private static func video(_ v: Components.Schemas.VideoPlan) -> StreamVideo? {
        guard let w = v.width, w > 0, let h = v.height, h > 0 else { return nil }
        return StreamVideo(width: Int(w), height: Int(h), hdr: v.toneMap ? nil : hdrLabel(v.hdr))
    }
}
