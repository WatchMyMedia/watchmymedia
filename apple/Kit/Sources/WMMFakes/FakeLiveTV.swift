import AVFoundation
import Foundation
import Observation
import WMMModel

@MainActor @Observable
public final class FakeLiveTVRepository: LiveTVRepository {
    nonisolated public static let sampleChannels: [LiveChannel] = [
        .init(id: "live-1", number: "2.1", name: "Cedar One"),
        .init(id: "live-2", number: "5.1", name: "Northline"),
        .init(id: "live-3", number: "5.2", name: "Northline Extra"),
        .init(id: "live-4", number: "8.1", name: "Coast Public"),
        .init(id: "live-5", number: "12.1", name: "Valley News"),
    ]
    private let servers: FakeServerRepository?
    private var loadedContext: String?
    private var loaded = false
    public var offered = LiveTVCapabilities(enabled: true, allowed: true, playback: true)
    public var availableChannels = sampleChannels
    public var failure: String?
    /// The boards' listings; nil for channels without listings, as before G5.
    public var listings: FakeLiveGuide? {
        didSet { listingsChanged() }
    }
    public private(set) var guideRequests: [(start: Date, hours: Int)] = []
    public var guideFailure: String?
    /// The person's starred channels, by id: the extras boards' five unless `-fakesLiveNoFavourites`.
    public private(set) var favouriteIDs: Set<String> = []
    /// The person's reminders, soonest first.
    public var reminderItems: [LiveReminder] = []
    public var leadMinutes = LiveReminderLead.standard
    public private(set) var searchRequests: [String] = []
    /// Makes the next favourite, reminder or search call fail with this message.
    public var recordingItems: [LiveRecording] = []
    public var recordingRules: [LiveRecordingRule] = []
    public var recordingFailure: String?
    public var extrasFailure: String?
    /// The clock search and reminders go by; tests set the boards' moment.
    public var now: @MainActor () -> Date = { Date() }
    private var listeners: [UUID: AsyncStream<Void>.Continuation] = [:]
    private var typedListeners: [UUID: AsyncStream<LiveTVChange>.Continuation] = [:]
    /// Calls made, for tests that count requests.
    public private(set) var refreshCount = 0
    public private(set) var remindersCount = 0
    /// Awaited inside each reminders call after it is counted, so a test can
    /// hold one open while a second caller joins it.
    public var remindersHold: (@MainActor () async -> Void)?
    public var contextID: String? {
        guard let servers else { return "fake-live" }
        guard servers.currentUser != nil, let current = servers.current else { return nil }
        return "\(current.id):\(servers.accountRevision)"
    }
    public var capabilities: LiveTVCapabilities? { loaded && loadedContext == contextID ? offeredForServer : nil }
    public var channels: [LiveChannel] { capabilities?.available == true ? availableChannels.map(decorated) : [] }
    private var offeredForServer: LiveTVCapabilities {
        if let servers, !ServerFeatures(apiLevel: servers.current?.server.apiLevel).liveTV || servers.currentUser == nil { return .init() }
        var offered = offered
        if let servers, !ServerFeatures(apiLevel: servers.current?.server.apiLevel).liveGuide { offered.guide = false }
        if let servers, !ServerFeatures(apiLevel: servers.current?.server.apiLevel).livePersonal { offered.extras = false }
        if let servers, !ServerFeatures(apiLevel: servers.current?.server.apiLevel).liveRecordings { offered.recording = false; offered.canRecord = false }
        if let servers, !ServerFeatures(apiLevel: servers.current?.server.apiLevel).liveAutomatic { offered.automatic = false }
        return offered
    }
    public init(servers: FakeServerRepository? = nil, guide: FakeLiveGuide? = nil) {
        self.servers = servers
        self.listings = guide
        listingsChanged()
        if let guide, guide.extras {
            if guide.favourites { favouriteIDs = ["ch-2.1", "ch-4.1", "ch-4.3", "ch-7.1", "ch-20.1"] }
            reminderItems = guide.reminders()
        }
    }
    private func listingsChanged() {
        offered.guide = listings != nil
        offered.extras = listings?.extras ?? false
        availableChannels = listings?.channels(at: .now) ?? Self.sampleChannels
    }
    private func decorated(_ channel: LiveChannel) -> LiveChannel {
        var channel = channel
        channel.favourite = favouriteIDs.contains(channel.id)
        let now = reminded(channel.now?.id), next = reminded(channel.next?.id)
        channel.now?.reminder = now
        channel.next?.reminder = next
        let nowID = channel.now?.id, nextID = channel.next?.id
        channel.now?.recording = nowID.flatMap { id in recordingItems.first { $0.programmeID == id && $0.state != .cancelled }?.mark }
        channel.next?.recording = nextID.flatMap { id in recordingItems.first { $0.programmeID == id && $0.state != .cancelled }?.mark }
        return channel
    }
    private func reminded(_ programmeID: String?) -> Bool {
        reminderItems.contains { $0.programmeID == programmeID && $0.state != .gone }
    }
    private func decorated(_ guide: LiveGuide) -> LiveGuide {
        var guide = guide
        guide.channels = guide.channels.map { row in
            var row = row
            row.channel = decorated(row.channel)
            row.programmes = row.programmes.map { var p = $0; p.reminder = reminded(p.id); p.recording = recordingItems.first { $0.programmeID == p.id && $0.state != .cancelled }?.mark; return p }
            return row
        }
        return guide
    }
    public func refresh() async throws {
        refreshCount += 1
        let context = contextID
        await FakeLatency.wait()
        try Task.checkCancellation()
        guard context == contextID else { throw CancellationError() }
        if let failure { throw LiveTVError(reason: failure) }
        if let listings { availableChannels = listings.channels(at: .now) }
        loadedContext = context; loaded = true
    }
    public func guide(start: Date, hours: Int) async throws -> LiveGuide {
        let context = contextID
        guideRequests.append((start, hours))
        await FakeLatency.wait()
        try Task.checkCancellation()
        guard context == contextID else { throw CancellationError() }
        guard let listings, capabilities?.listings == true else { throw LiveTVError(reason: "unavailable") }
        if let guideFailure { throw APIError(status: 500, message: guideFailure) }
        return decorated(listings.guide(start: start, hours: hours))
    }

    // MARK: Favourites, search and reminders

    private func extras() async throws -> FakeLiveGuide {
        let context = contextID
        await FakeLatency.wait()
        try Task.checkCancellation()
        guard context == contextID else { throw CancellationError() }
        guard let listings, capabilities?.extrasAvailable == true else { throw LiveTVError(reason: "unavailable") }
        if let extrasFailure { self.extrasFailure = nil; throw APIError(status: 500, message: extrasFailure) }
        return listings
    }

    public func setFavourite(_ favourite: Bool, channelID: String) async throws {
        _ = try await extras()
        guard availableChannels.contains(where: { $0.id == channelID }) else { throw APIError(status: 404, message: "channel not found") }
        if favourite { favouriteIDs.insert(channelID) } else { favouriteIDs.remove(channelID) }
    }

    /// Every word of `query` in the title or episode title, ignoring case and accents, on now and later.
    public func searchListings(_ query: String, limit: Int) async throws -> [LiveSearchHit] {
        searchRequests.append(query)
        let listings = try await extras()
        let words = query.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: nil).split(separator: " ").map(String.init)
        guard query.trimmingCharacters(in: .whitespaces).count >= liveSearchMinimumLength else { return [] }
        let now = self.now()
        let order = Dictionary(availableChannels.enumerated().map { ($1.id, $0) }, uniquingKeysWith: { a, _ in a })
        return listings.programmes(from: now)
            .filter { $0.programme.end > now }
            .filter { hit in
                let text = [hit.programme.title, hit.programme.episodeTitle ?? ""].joined(separator: " ")
                    .folding(options: [.caseInsensitive, .diacriticInsensitive], locale: nil)
                return words.allSatisfy { text.contains($0) }
            }
            .sorted { ($0.programme.start, order[$0.channel.id] ?? 0) < ($1.programme.start, order[$1.channel.id] ?? 0) }
            .prefix(min(max(limit, 1), 50))
            .map { hit in
                var p = hit.programme
                p.reminder = reminded(p.id)
                return LiveSearchHit(channel: decorated(hit.channel), programme: p)
            }
    }

    public func reminders() async throws -> LiveReminders {
        remindersCount += 1
        if let remindersHold { await remindersHold() }
        _ = try await extras()
        let now = self.now()
        reminderItems.removeAll { $0.end <= now }
        return LiveReminders(items: reminderItems.sorted { $0.start < $1.start }, leadMinutes: leadMinutes)
    }

    public func remind(programmeID: String) async throws -> LiveReminder {
        let listings = try await extras()
        if let existing = reminderItems.first(where: { $0.programmeID == programmeID }) { return existing }
        guard let found = listings.programmes(from: now().addingTimeInterval(-6 * 3600)).first(where: { $0.programme.id == programmeID }) else {
            throw APIError(status: 404, message: "That programme is not in the listings.")
        }
        guard found.programme.start > now() else { throw APIError(status: 409, message: "That programme has already started.") }
        let reminder = LiveReminder(programme: found.programme, channel: found.channel, id: "rem-\(programmeID)")
        reminderItems.append(reminder)
        return reminder
    }

    public func removeReminder(id: String) async throws {
        _ = try await extras()
        reminderItems.removeAll { $0.id == id }
    }

    public func setReminderLead(minutes: Int) async throws {
        _ = try await extras()
        guard LiveReminderLead.choices.contains(minutes) else { throw APIError(status: 422, message: "Choose 0, 1, 5, 10 or 15 minutes.") }
        leadMinutes = minutes
    }

    public func changes() -> AsyncStream<Void> {
        AsyncStream(bufferingPolicy: .bufferingNewest(1)) { continuation in
            let id = UUID()
            listeners[id] = continuation
            continuation.onTermination = { [weak self] _ in Task { @MainActor in self?.listeners[id] = nil } }
        }
    }

    public func liveChanges() -> AsyncStream<LiveTVChange> {
        AsyncStream { continuation in
            let id = UUID()
            typedListeners[id] = continuation
            continuation.onTermination = { [weak self] _ in Task { @MainActor in self?.typedListeners[id] = nil } }
        }
    }

    /// Who listens for typed changes now, so a test can wait for a subscription before an event.
    public var liveListenerCount: Int { typedListeners.count }

    /// As if another device changed a favourite or reminder (the server's
    /// private events): `part` says which event; `.all` is a reconnect's.
    public func changedElsewhere(_ part: LiveTVChange = .all, _ change: (FakeLiveTVRepository) -> Void = { _ in }) {
        change(self)
        for listener in listeners.values { listener.yield() }
        for listener in typedListeners.values { listener.yield(part) }
    }

    /// Stars or unstars a channel as another device would, without an event.
    public func setFavouriteElsewhere(_ favourite: Bool, channelID: String) {
        if favourite { favouriteIDs.insert(channelID) } else { favouriteIDs.remove(channelID) }
    }
    public func makeSessionGateway() throws -> any LiveSessionGateway {
        guard contextID != nil, offeredForServer.available else { throw LiveTVError(reason: "access_revoked") }
        return FakeLiveSessionGateway(channels: availableChannels)
    }
}

public actor FakeLiveSessionGateway: LiveSessionGateway {
    private let channels: [LiveChannel]
    private var sessions: [String: LiveSession] = [:]
    public init(channels: [LiveChannel] = FakeLiveTVRepository.sampleChannels) { self.channels = channels }
    public func start(channelID: String, quality: LiveQuality, requestID: String) async throws -> LiveSession {
        if let existing = sessions[requestID] { return existing }
        guard let channel = channels.first(where: { $0.id == channelID }) else { throw LiveTVError(reason: "unavailable") }
        let session = LiveSession(id: requestID, channel: channel, state: .playing, quality: quality)
        sessions[requestID] = session
        return session
    }
    public func status(sessionID: String) async throws -> LiveSession {
        guard let session = sessions[sessionID] else { throw APIError(status: 404, message: "Live session not found.") }
        return session
    }
    public func heartbeat(sessionID: String, paused: Bool, interacted: Bool) async throws -> LiveSession {
        var session = try await status(sessionID: sessionID)
        session.paused = paused
        sessions[sessionID] = session
        return session
    }
    public func stop(sessionID: String) async throws {
        sessions[sessionID]?.state = .stopped
        sessions[sessionID]?.reason = "stopped"
    }
    public func mediaToken() async throws -> MediaToken { .init(token: "fake-live-token", expiresAt: .now.addingTimeInterval(3600)) }
}

@MainActor
public final class FakeLivePlaybackFactory: LivePlaybackControllerFactory {
    private let automatic: Bool
    public init(automatic: Bool = false) { self.automatic = automatic }
    public func make() -> any LivePlaybackController { FakeLivePlaybackController(automatic: automatic) }
}

/// No media or network: renders the same live states for previews, accessibility and navigation tests.
@MainActor @Observable
public final class FakeLivePlaybackController: LivePlaybackController {
    public private(set) var state = LivePlayerState()
    public var player: AVPlayer? { nil }
    private let automatic: Bool
    public init(automatic: Bool = false) { self.automatic = automatic }
    public func start(_ request: LivePlayRequest) {
        state = .init(); state.channel = request.channel; state.quality = automatic ? .auto : request.quality; state.status = .playing
        if automatic { state.qualities = [.auto] + LiveQuality.fixed; state.actualQuality = .p1080; state.actualOutput = "original" }
    }
    public func play() { state.status = .playing }
    public func pause() { state.status = .paused; state.isAtLiveEdge = false }
    public func togglePlayPause() { state.status == .paused ? play() : pause() }
    public func goLive() { state.isAtLiveEdge = true; state.status = .playing; state.message = "Back at live TV." }
    public func selectQuality(_ quality: LiveQuality) {
        guard state.qualities.contains(quality) else { return }
        state.quality = quality
        if quality == .auto { state.actualQuality = .p1080; state.actualOutput = "original" }
        else { state.actualQuality = nil; state.actualOutput = nil }
        goLive()
    }
    /// Preview a 50 → 5 → 50 Mbps link without media or a server.
    public func setAutomaticStep(_ quality: LiveQuality?) {
        guard automatic, state.quality == .auto else { return }
        state.actualQuality = quality
        state.actualOutput = quality?.rawValue ?? "original"
    }
    public func noteUserInput() { state.askStillWatching = false }
    public func answerStillWatching(keepWatching: Bool) { state.askStillWatching = false; if !keepWatching { stop() } }
    public func stop() { state.status = .stopped }
    public func release() { stop() }
    public func showStillWatching() { state.askStillWatching = true }
    public func showStopped(reason: String) {
        state.status = .stopped; state.stopReason = reason; state.message = LiveTVError.message(reason)
        if reason == "recording", state.takeover == nil { showTakeover() }
    }
    /// `-liveTakeover`: a recording needs this tuner in 2 minutes; whoever set it may cancel.
    public func showTakeover(canCancel: Bool = true) {
        state.takeover = .init(recordingID: "dvr-upcoming", title: "Station 9", at: .now.addingTimeInterval(120), canCancel: canCancel)
    }
    public func showError(reason: String) { state.status = .error; state.error = LiveTVError.message(reason) }
}
