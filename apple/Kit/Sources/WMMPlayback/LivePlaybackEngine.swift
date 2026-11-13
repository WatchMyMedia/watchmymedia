import AVFoundation
import Foundation
import Network
import Observation
import WMMModel

/// Each controller is pinned to one sign-in. A replacement waits for the previous
/// controller's stop before tuning, including a start reply that arrives late.
@MainActor
public final class LivePlaybackEngine: LivePlaybackControllerFactory {
    private let repository: any LiveTVRepository
    private let profile: any ProfileProvider
    private let network = LiveNetworkPath()
    private var current: RealLivePlaybackController?
    public init(repository: any LiveTVRepository, profile: any ProfileProvider = DeviceProfileProvider()) {
        self.repository = repository; self.profile = profile
    }
    public func make() -> any LivePlaybackController {
        let previous = current
        previous?.release()
        let next = RealLivePlaybackController(repository: repository, media: AVLiveMediaPlayer(), clock: SystemPlaybackClock(),
                                              cellular: { [network] in network.cellular }, profile: { [profile] in profile.current() },
                                              beforeStart: { try await previous?.ensureStopped() })
        current = next
        return next
    }
}

/// A Live TV heartbeat asked for: what it says is read when it goes out.
private struct LiveBeat {}

@MainActor
private final class LiveNetworkPath {
    // An unclassified route uses the cellular cap until NWPathMonitor answers.
    var cellular = true
    private let monitor = NWPathMonitor()
    init() {
        monitor.pathUpdateHandler = { [weak self] path in
            let cellular = path.usesInterfaceType(.cellular)
            Task { @MainActor in self?.cellular = cellular }
        }
        monitor.start(queue: DispatchQueue(label: "media.watchmy.live-network"))
    }
    deinit { monitor.cancel() }
}

/// Independent of file playback: all server calls are live-session operations.
@MainActor @Observable
public final class RealLivePlaybackController: LivePlaybackController {
    public private(set) var state = LivePlayerState()
    public var player: AVPlayer? { released ? nil : media.player }
    @ObservationIgnored private let repository: any LiveTVRepository
    @ObservationIgnored private let contextID: String?
    @ObservationIgnored private let gateway: Result<any LiveSessionGateway, Error>
    @ObservationIgnored private let media: any LiveMediaPlayer
    @ObservationIgnored private let clock: any PlaybackClock
    @ObservationIgnored private let cellular: @MainActor () -> Bool
    @ObservationIgnored private let profile: @MainActor () -> PlaybackProfile
    @ObservationIgnored private var beforeStart: (@MainActor () async throws -> Void)?
    @ObservationIgnored private var generation = 0
    @ObservationIgnored private var desired: LivePlayRequest?
    @ObservationIgnored private var lastRequest: LivePlayRequest?
    @ObservationIgnored private var activeSession: LiveSession?
    /// A Start whose HTTP response was lost may own a tuner even without an ID.
    @ObservationIgnored private var uncertainStart: (request: LivePlayRequest, id: String, profile: PlaybackProfile)?
    /// No player reads or control requests continue after failed cleanup. The
    /// shipped server's 60-second lease, plus a five-second grace, bounds ownership.
    @ObservationIgnored private var uncertainUntil: Int?
    @ObservationIgnored private let tokens: TokenKeeper
    @ObservationIgnored private var worker: Task<Void, Never>?
    /// One heartbeat out at a time, and the newest one asked for meanwhile waiting.
    @ObservationIgnored private var beats: HeartbeatPump<LiveBeat>!
    @ObservationIgnored private let ticker: Ticker
    @ObservationIgnored private var released = false
    @ObservationIgnored private var loaded = false
    @ObservationIgnored private var wantsPlay = true
    @ObservationIgnored private var pausedAt: Int?
    @ObservationIgnored private var pauseBudget = 300_000
    @ObservationIgnored private var lastInput = 0
    @ObservationIgnored private var inputVersion = 0
    @ObservationIgnored private var acknowledgedInput = 0
    @ObservationIgnored private var lastBeat = 0
    @ObservationIgnored private var lastSuccessfulBeat = 0
    @ObservationIgnored private var lastNativePlaying = false
    @ObservationIgnored private var loadedURL: URL?
    @ObservationIgnored private var startedProfile: PlaybackProfile?
    @ObservationIgnored private var pendingOutput: (generation: Int, previousURL: URL)?
    /// Automatic's output switches: those handled, and those to report.
    @ObservationIgnored private var switches = SwitchGenerations<Int>()
    /// A new token is being fetched after the stream refused the last one.
    @ObservationIgnored private var renewingToken = false
    /// Tokens renewed for a refused stream, and when the last was: as many
    /// as the file player's recoveries, counted afresh after a minute of playing.
    @ObservationIgnored private var tokenRenewals = 0
    @ObservationIgnored private var renewedAt = 0

    public init(repository: any LiveTVRepository, media: any LiveMediaPlayer, clock: any PlaybackClock, cellular: @escaping @MainActor () -> Bool = { false },
                profile: @escaping @MainActor () -> PlaybackProfile = { PlaybackProfile(name: "Apple Live TV", containers: ["mpegts"], videoCodecs: ["h264"], audioCodecs: ["aac"], maxHeight: 1080) },
                beforeStart: @escaping @MainActor () async throws -> Void = {}) {
        self.repository = repository; self.contextID = repository.contextID
        self.gateway = Result { try repository.makeSessionGateway() }
        self.media = media; self.clock = clock; self.cellular = cellular; self.profile = profile; self.beforeStart = beforeStart
        tokens = TokenKeeper(clock: clock)
        ticker = Ticker(clock: clock, everyMs: 500)
        beats = HeartbeatPump(owner: self, coalesce: { beat, waiting, _ in waiting = [beat] },
                              deliver: { [weak self] _ in await self?.beat() })
    }

    public func start(_ request: LivePlayRequest) {
        guard !released, identityMatches else { return }
        var request = request
        if request.quality == .auto && repository.capabilities?.automatic != true { request.quality = .p1080 }
        request.quality = request.quality.cappedForCellular(cellular())
        generation += 1; desired = request; lastRequest = request
        loaded = false; wantsPlay = true; pausedAt = nil; pauseBudget = 300_000
        loadedURL = nil; switches = SwitchGenerations(); pendingOutput = nil
        renewingToken = false; tokenRenewals = 0
        noteUserInput()
        media.stop()
        state = LivePlayerState(); state.channel = request.channel; state.quality = request.quality
        state.status = .tuning
        updateNetworkState()
        scheduleTick()
        runSwitches()
    }

    public func play() {
        guard !released else { return }
        noteUserInput()
        guard activeSession != nil, state.status != .stopped, state.status != .error else {
            if let request = lastRequest { start(request) }; return
        }
        if let pausedAt, clock.elapsedMs - pausedAt >= state.bufferSeconds * 1000 || positionHasExpired {
            media.goLive(); state.message = "The pause passed the live buffer. Resumed at live TV."
        }
        pausedAt = nil; wantsPlay = true; media.play(); lastNativePlaying = true
        state.status = loaded ? .playing : .tuning
        sendHeartbeat(force: true)
    }
    public func pause() {
        guard activeSession != nil, !released else { return }
        noteUserInput(); wantsPlay = false
        if pendingOutput != nil { rejectOutput() }
        media.pause(); lastNativePlaying = false
        if pausedAt == nil { pausedAt = clock.elapsedMs; pauseBudget = 300_000 }
        state.status = .paused
        sendHeartbeat(force: true)
    }
    public func togglePlayPause() { wantsPlay ? pause() : play() }
    public func goLive() {
        guard activeSession != nil, !released else { play(); return }
        noteUserInput(); media.goLive(); media.play(); wantsPlay = true; pausedAt = nil; lastNativePlaying = true
        state.isAtLiveEdge = true; state.status = .playing; state.message = "Returned to live TV."
        sendHeartbeat(force: true)
    }
    public func selectQuality(_ quality: LiveQuality) {
        noteUserInput()
        guard quality != .auto || repository.capabilities?.automatic == true else { return }
        guard var request = lastRequest else { return }
        request.quality = quality.cappedForCellular(cellular())
        guard request.quality != state.quality else { return }
        start(request)
    }
    public func noteUserInput() {
        lastInput = clock.elapsedMs; inputVersion += 1
        state.askStillWatching = false; state.stillWatchingSeconds = 60
    }
    public func answerStillWatching(keepWatching: Bool) {
        if keepWatching { noteUserInput(); sendHeartbeat(force: true) }
        else { finish(reason: "inactive") }
    }
    public func stop() {
        generation += 1; desired = nil; loaded = false
        loadedURL = nil; pendingOutput = nil; switches = SwitchGenerations(handled: switches.handled)
        ticker.stop(); media.stop()
        state.status = .stopped; state.askStillWatching = false
        runSwitches()
    }
    public func release() {
        guard !released else { return }
        stop(); released = true
    }
    public func waitUntilStopped() async { await worker?.value }
    func ensureStopped() async throws {
        await worker?.value
        retireExpiredOwnership()
        if activeSession != nil || uncertainStart != nil {
            // A stop failure leaves ownership uncertain. Do not let another controller
            // acquire a tuner until another explicit Stop succeeds or the lease expires.
            runSwitches()
            await worker?.value
            if activeSession != nil || uncertainStart != nil { throw APIError(status: nil, message: "Could not release the previous channel. Check the connection and try again, or wait one minute for its lease to expire.") }
        }
    }

    private var identityMatches: Bool { repository.contextID == contextID && contextID != nil }
    private var positionHasExpired: Bool {
        guard let range = media.seekable else { return false }
        // A native live position can run ahead of the most recently published
        // seekable end. Only falling behind the rolling start proves expiry.
        return media.position < range.lowerBound
    }

    private func runSwitches() {
        guard worker == nil else { return }
        worker = Task { [self] in
            var processed = -1
            while processed != generation {
                let version = generation
                processed = version
                do {
                    try await beforeStart?()
                    beforeStart = nil
                    await beats.drained()
                    retireExpiredOwnership()
                    let bound = try gateway.get()
                    if let uncertain = uncertainStart {
                        // Reconcile a lost Start using its original idempotency key.
                        // Even a new desired channel cannot bypass this ownership barrier.
                        do {
                            activeSession = try await bound.start(channelID: uncertain.request.channel.id, quality: uncertain.request.quality, requestID: uncertain.id, profile: uncertain.profile)
                            uncertainStart = nil; uncertainUntil = nil
                        } catch {
                            // This attempt may itself have allocated on a previously
                            // disconnected request, so its final failure starts the lease wait.
                            uncertainUntil = clock.elapsedMs + 65_000
                            throw error
                        }
                    }
                    if let session = activeSession {
                        // Never allocate a second tuner before the old upstream closes.
                        do { try await bound.stop(sessionID: session.id) }
                        catch {
                            // Failed Stops do not renew a lease. Preserve the first
                            // deadline so a revoked sign-in cannot block its replacement forever.
                            if uncertainUntil == nil { uncertainUntil = clock.elapsedMs + 65_000 }
                            throw error
                        }
                        activeSession = nil; uncertainUntil = nil
                    }
                    guard version == generation else { continue }
                    guard let request = desired, !released, identityMatches else { continue }
                    // A channel change keeps a token with time left: they last six hours
                    // (PLAN-APPLE-REVIEW.md §4.2.2). This controller is pinned to one sign-in.
                    let fetched: MediaToken? = tokens.valid ? nil : try await bound.mediaToken()
                    guard version == generation, !released, identityMatches else { continue }
                    let selectedProfile = liveProfile()
                    let session = try await createSession(bound, request: request, requestID: UUID().uuidString, profile: selectedProfile)
                    activeSession = session
                    guard version == generation, !released, identityMatches else {
                        // The reply belongs to the abandoned request. Loop through Stop
                        // even when the screen/account disappeared while Start was running.
                        if version == generation { generation += 1; desired = nil }
                        continue
                    }
                    if let fetched { tokens.keep(fetched) }
                    startedProfile = selectedProfile
                    lastBeat = clock.elapsedMs; lastSuccessfulBeat = clock.elapsedMs
                    apply(session)
                } catch {
                    if version == generation, !released {
                        desired = nil; state.status = .error; state.error = error.displayMessage
                        media.stop()
                    }
                    // A failed stop keeps the session identity for Retry, which must stop
                    // it again before it is allowed to acquire another tuner.
                }
            }
            worker = nil
        }
    }

    private func liveProfile() -> PlaybackProfile {
        var result = profile()
        if cellular() {
            result.maxHeight = min(result.maxHeight ?? 720, 720)
            result.maxBitrate = min(result.maxBitrate ?? 4_000_000, 4_000_000)
        }
        return result
    }

    private func createSession(_ bound: any LiveSessionGateway, request: LivePlayRequest, requestID: String, profile: PlaybackProfile) async throws -> LiveSession {
        do { return try await bound.start(channelID: request.channel.id, quality: request.quality, requestID: requestID, profile: profile) }
        catch {
            // A lost HTTP response may still have allocated the tuner. Reusing the
            // server's idempotency key retrieves that session, including for cleanup
            // after navigation away, without allocating a second tuner.
            guard (error as? APIError)?.status == nil && error is APIError || error is URLError else { throw error }
            uncertainStart = (request, requestID, profile)
            do {
                let recovered = try await bound.start(channelID: request.channel.id, quality: request.quality, requestID: requestID, profile: profile)
                uncertainStart = nil; uncertainUntil = nil
                return recovered
            } catch {
                uncertainUntil = clock.elapsedMs + 65_000
                throw error
            }
        }
    }

    private func retireExpiredOwnership() {
        if let deadline = uncertainUntil, clock.elapsedMs >= deadline {
            activeSession = nil; uncertainStart = nil; uncertainUntil = nil
        }
    }

    private func apply(_ session: LiveSession) {
        activeSession = session
        state.bufferSeconds = session.bufferSeconds
        state.takeover = session.takeover
        state.actualQuality = session.actualQuality
        state.actualOutput = session.actualOutput
        state.switchStatus = session.switchStatus
        if session.state.terminal || session.state == .stopping {
            finish(reason: session.reason ?? "stopped"); return
        }
        guard session.state == .playing else { state.status = .tuning; return }
        if !loaded, let url = session.streamURL, let token = tokens.token {
            media.load(url: token.apply(to: url), title: session.channel.title, play: wantsPlay)
            loaded = true; loadedURL = url; lastNativePlaying = wantsPlay
        }
        if session.automatic && wantsPlay { prepareOutput(session) }
        if !wantsPlay {
            // The server's remaining allowance can shorten, never extend, this pause.
            pauseBudget = min(pauseBudget, session.pauseRemainingSeconds * 1000 + (pausedAt.map { clock.elapsedMs - $0 } ?? 0))
        }
        state.status = wantsPlay ? (media.buffering ? .buffering : .playing) : .paused
    }

    private func prepareOutput(_ session: LiveSession) {
        guard let directive = session.directive, pendingOutput == nil,
              switches.take(directive.generation, playing: session.outputGeneration) else { return }
        guard let next = directive.url, let previous = loadedURL ?? session.streamURL, let token = tokens.token else {
            switches.fail(directive.generation)
            sendHeartbeat(force: true)
            return
        }
        pendingOutput = (directive.generation, previous)
        loadedURL = next
        media.load(url: token.apply(to: next), title: session.channel.title, play: wantsPlay)
    }

    private func rejectOutput() {
        guard let pending = pendingOutput else { return }
        pendingOutput = nil
        switches.fail(pending.generation)
        loadedURL = pending.previousURL
        state.message = "Keeping the current Live TV quality after a switch failed."
        guard let session = activeSession, let token = tokens.token else { finish(reason: "signal_lost", failure: true); return }
        media.load(url: token.apply(to: pending.previousURL), title: session.channel.title, play: wantsPlay)
        sendHeartbeat(force: true)
    }

    private func scheduleTick() {
        ticker.restart { [weak self] _ in
            // Nothing wanted any more (a failed start, an abandoned one): the beat ends.
            guard let self, !self.released, self.desired != nil else { self?.ticker.stop(); return }
            self.tick()
        }
    }
    private func tick() {
        guard identityMatches else { finish(reason: "access_revoked"); return }
        if let startedProfile, activeSession != nil, state.status != .tuning, startedProfile != liveProfile(),
           let request = lastRequest {
            start(request); return
        }
        updateNetworkState()
        if pendingOutput != nil {
            if media.failed { rejectOutput() }
            else if media.firstFrameRendered, let pending = pendingOutput {
                pendingOutput = nil
                switches.accept(pending.generation)
                state.message = nil
                sendHeartbeat(force: true)
            }
        }
        let idle = clock.elapsedMs - lastInput
        if idle >= 4 * 3_600_000 + 60_000 { finish(reason: "inactive"); return }
        state.askStillWatching = idle >= 4 * 3_600_000
        state.stillWatchingSeconds = max(0, (4 * 3_600_000 + 60_000 - idle + 999) / 1000)
        if loaded, media.ready {
            // AVKit and system controls act on AVPlayer directly (tvOS/PiP/AirPlay).
            if pendingOutput == nil && media.playing != lastNativePlaying {
                if media.playing { play() } else { pause() }
            }
            if let range = media.seekable { state.isAtLiveEdge = range.upperBound - media.position <= 8 }
            if wantsPlay { state.status = media.playing && !media.buffering ? .playing : .buffering }
        }
        if let pausedAt {
            state.pauseRemainingSeconds = max(0, (pauseBudget - (clock.elapsedMs - pausedAt) + 999) / 1000)
            if state.pauseRemainingSeconds == 0 { finish(reason: "pause_expired"); return }
        }
        if tokenRenewals > 0, media.playing, !media.buffering, clock.elapsedMs - renewedAt >= recoveryResetMs { tokenRenewals = 0 }
        if media.failed && !renewingToken {
            if media.failure?.refusedToken == true, tokenRenewals < maxRecoveries { renewToken() }
            else { finish(reason: "signal_lost", failure: true); return }
        }
        sendHeartbeat(force: false)
    }
    /// The stream refused the media token (a 401 or 403 for a playlist or a
    /// segment): a new token and the same stream again, as the file player
    /// does, rather than "signal lost". The tuner's session is kept.
    private func renewToken() {
        guard let session = activeSession, let url = loadedURL ?? session.streamURL else {
            finish(reason: "signal_lost", failure: true); return
        }
        renewingToken = true; tokenRenewals += 1; renewedAt = clock.elapsedMs
        let version = generation
        Task { [self] in
            defer { if version == generation { renewingToken = false } }
            do {
                let fresh = try await gateway.get().mediaToken()
                guard version == generation, activeSession?.id == session.id, !released else { return }
                tokens.keep(fresh)
                media.load(url: fresh.apply(to: url), title: session.channel.title, play: wantsPlay)
                lastNativePlaying = wantsPlay
                state.message = "Reconnected at live TV."
            } catch {
                guard version == generation, !released else { return }
                finish(reason: "signal_lost", failure: true)
            }
        }
    }

    private func updateNetworkState() {
        state.cellular = cellular()
        state.qualities = (repository.capabilities?.automatic == true ? [.auto] : [])
            + LiveQuality.fixed.filter { !state.cellular || $0 != .p1080 }
    }
    private func sendHeartbeat(force: Bool) {
        // One already out: a forced one waits for it, taking the place of any waiting.
        if force, beats.busy { beats.submit(LiveBeat()); return }
        guard !beats.busy, worker == nil, let session = activeSession, desired != nil, !released else { return }
        // While the channel tunes this asks every second. From API level 28 a
        // status request with `waitMs` answers the moment it plays; that needs
        // the regenerated client (`getLiveTVSession`), so it waits for it.
        let interval = session.state == .starting ? 1_000 : 10_000
        guard force || clock.elapsedMs - lastBeat >= interval else { return }
        beats.submit(LiveBeat())
    }

    /// Sends a heartbeat with what the player measures now, and acts on the reply.
    private func beat() async {
        guard worker == nil, let session = activeSession, desired != nil, !released else { return }
        lastBeat = clock.elapsedMs
        let version = generation, input = inputVersion, paused = !wantsPlay
        let accepted = switches.accepted, failed = switches.failed
        let transfer = session.automatic ? media.transfer : nil
        let measurement: LiveMeasurement? = session.automatic ? .init(
            state: paused ? .paused : media.ready && media.playing && !media.buffering ? .playing : .buffering,
            bufferedMs: media.bufferedMs, throughputBps: transfer?.bps, throughputAgeMs: transfer?.ageMs,
            stalls: media.stalls, switchGeneration: accepted, switchFailedGeneration: failed) : nil
        do {
            let bound = try gateway.get()
            let updated = try await bound.heartbeat(sessionID: session.id, paused: paused, interacted: input != acknowledgedInput, measurement: measurement)
            guard version == generation, activeSession?.id == session.id, !released else { return }
            lastSuccessfulBeat = clock.elapsedMs; acknowledgedInput = input
            switches.acknowledge(accepted: accepted)
            switches.acknowledge(failed: failed)
            apply(updated)
            guard desired != nil else { return }
            if !tokens.valid {
                let fresh = try await bound.mediaToken()
                guard version == generation, activeSession?.id == session.id, !released else { return }
                tokens.keep(fresh)
                if let url = loadedURL ?? updated.streamURL {
                    media.load(url: fresh.apply(to: url), title: updated.channel.title, play: wantsPlay)
                    state.message = "Reconnected at live TV."
                }
            }
        } catch {
            guard version == generation, !released else { return }
            if error is SignedOutError || (error as? APIError)?.status == 403 || (error as? LiveTVError)?.reason == "access_revoked" {
                finish(reason: "access_revoked")
            } else if (error as? APIError)?.status == 404 {
                finish(reason: "lease_expired")
            } else if clock.elapsedMs - lastSuccessfulBeat >= 50_000 {
                finish(reason: "lease_expired", failure: true)
            }
        }
    }
    private func finish(reason: String, failure: Bool = false) {
        stop()
        state.status = failure ? .error : .stopped
        state.stopReason = reason
        state.message = LiveTVError.message(reason)
        if failure { state.error = state.message }
    }
}
