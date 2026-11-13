import AVFoundation
import Foundation
import Observation
import os
import WMMModel

private let log = Logger(subsystem: "media.watchmy.client", category: "Playback")

/// The player behaviour of PLAN-CLIENTS.md §2.3 around a ``MediaPlayer``, as
/// android/core/playback/.../RealPlaybackController.kt does it: asks the
/// server how to play, maps player time to file time, reports heartbeats,
/// runs Skip, Next Episode and Still watching, draws the selected text
/// subtitle's cues, keeps Now Playing current with the play's picture, and
/// moves the stream to the server's LAN address when AirPlay needs one.
/// At Automatic quality the server picks the step and may change it through a
/// heartbeat's reply; the heartbeats carry what the player measures
/// (PLAN-AUTO-QUALITY.md). ``PlaybackLookups`` names a file the request left
/// untitled, fills in the next episode's runtime and loads the picture.
///
/// Everything runs on the main actor, so there is no locking. Heartbeats and
/// session stops go out in order through one queue, which outlives
/// ``release()``, so the last "stopped" report is sent even as the screen goes.
/// Timers run on the injected ``PlaybackClock``.
///
/// ``PlaybackEngine`` builds these with the real pieces; tests pass fakes.
@MainActor
@Observable
public final class RealPlaybackController: PlaybackController {
    public private(set) var state = PlayerState()
    public let events: AsyncStream<PlayerEvent>
    public private(set) var player: AVPlayer?

    @ObservationIgnored private let continuation: AsyncStream<PlayerEvent>.Continuation
    @ObservationIgnored private let gateway: any PlaybackGateway
    @ObservationIgnored private let preferences: any PreferencesRepository
    @ObservationIgnored private let client: @MainActor () -> ClientInfo
    @ObservationIgnored private let profile: any ProfileProvider
    @ObservationIgnored private let makePlayer: @MainActor () -> any MediaPlayer
    @ObservationIgnored private let stillWatching: StillWatching
    @ObservationIgnored private let system: (any PlaybackSystem)?
    @ObservationIgnored private let lookups: any PlaybackLookups
    @ObservationIgnored private let servers: (any ServerRepository)?
    @ObservationIgnored private let clock: any PlaybackClock

    @ObservationIgnored private var media: (any MediaPlayer)?
    @ObservationIgnored private var session: (any SystemSession)?
    @ObservationIgnored private var viewing: Viewing?
    @ObservationIgnored private var generation = 0
    /// The viewer's pick; ``asked`` is what this server is asked for.
    @ObservationIgnored private var quality: StreamQuality
    @ObservationIgnored private var token: MediaToken?
    @ObservationIgnored private var tokenRefreshAt = 0
    @ObservationIgnored private var backgrounded = false
    @ObservationIgnored private var preferencesLoaded = false
    @ObservationIgnored private var preferencesReady = false
    @ObservationIgnored private var released = false
    @ObservationIgnored private var pausedByInterruption = false

    @ObservationIgnored private var ticker: TimerHandle?
    @ObservationIgnored private var lastTickAt = 0
    @ObservationIgnored private var loadTask: Task<Void, Never>?

    @ObservationIgnored private var outbox: [Outgoing] = []
    @ObservationIgnored private var delivering = false
    /// A message is out, waiting for the server.
    @ObservationIgnored private var sending = false
    @ObservationIgnored private var endHold: (@MainActor () -> Void)?
    @ObservationIgnored private var inFlight: [Int: Task<Void, Never>] = [:]
    @ObservationIgnored private var taskCount = 0

    /// Subtitle tracks' cues by URL (without the token), and the fetches out or failed.
    @ObservationIgnored private var cueTracks: [URL: CueTrack] = [:]
    @ObservationIgnored private var cueFetches: Set<URL> = []

    /// Now Playing's picture: the one chosen for the play, and its bytes once loaded.
    @ObservationIgnored private var artworkImage: ArtworkImage?
    @ObservationIgnored private var artwork: NowPlayingArtwork?

    /// AirPlay: whether the video is on another screen, the server's LAN
    /// address once found for it, and whether the stream loaded comes from there.
    @ObservationIgnored private var external = false
    @ObservationIgnored private var lookingForLocal = false
    @ObservationIgnored private var localRoute: LocalRoute?
    @ObservationIgnored private var playingLocally = false

    enum Outgoing {
        case report(ProgressReport, generation: Int)
        case endSession(String)
    }

    /// - Parameters:
    ///   - client: this device as the server sees it, read at every request (the name is editable).
    ///   - makePlayer: the player, made at the first ``start(_:)``.
    ///   - stillWatching: one for the app: the counters live for the app session.
    ///   - system: Now Playing, remote commands and the audio session; nil for none.
    ///   - servers: finds the server's LAN address when AirPlay starts, and
    ///     says whether it can do Automatic quality; nil never looks, and
    ///     plays a saved Automatic as Original.
    public init(
        gateway: any PlaybackGateway,
        preferences: any PreferencesRepository,
        client: @escaping @MainActor () -> ClientInfo,
        profile: any ProfileProvider,
        makePlayer: @escaping @MainActor () -> any MediaPlayer,
        stillWatching: StillWatching,
        system: (any PlaybackSystem)? = nil,
        lookups: any PlaybackLookups = NoLookups(),
        servers: (any ServerRepository)? = nil,
        clock: any PlaybackClock
    ) {
        self.gateway = gateway.bound()
        self.preferences = preferences
        self.client = client
        self.profile = profile
        self.makePlayer = makePlayer
        self.stillWatching = stillWatching
        self.system = system
        self.lookups = lookups
        self.servers = servers
        self.clock = clock
        quality = preferences.quality
        (events, continuation) = AsyncStream.makeStream(of: PlayerEvent.self)
    }

    // MARK: - Commands

    public func start(_ request: PlayRequest) {
        guard !released else { return }
        endViewing(.stopped)
        quality = request.qualityOverride ?? preferences.quality
        stillWatching.notePlay()
        stillWatching.noteInput()
        begin(request)
    }

    public func play() {
        stillWatching.noteInput()
        backgrounded = false
        guard let v = active() else { return }
        if v.error != nil && !v.loading {
            // Play after an error tries again from where it stopped.
            v.error = nil
            reload(v, at: position(v))
            return
        }
        media?.play()
    }

    public func pause() {
        stillWatching.noteInput()
        guard active() != nil else { return }
        media?.pause()
    }

    public func togglePlayPause() {
        if let p = media, p.playWhenReady, p.phase != .ended { pause() } else { play() }
    }

    public func seek(toMs positionMs: Int) {
        stillWatching.noteInput()
        guard let v = active(), let d = v.decision else { return }
        let target = min(max(positionMs, 0), duration(v, d))
        noteSeek(v, d, to: target)
        seekWithin(v, target)
    }

    public func seek(byMs deltaMs: Int) { seek(toMs: state.positionMs + deltaMs) }

    public func noteSeek(playerMs: Int) {
        stillWatching.noteInput()
        guard let v = active(), let d = v.decision, !v.loading else { return }
        noteSeek(v, d, to: min(max(v.streamClock.filePosition(playerMs), 0), duration(v, d)))
        refresh(0)
    }

    /// The viewer's seek to `target`: back before the credits offers the Next
    /// Episode card again, and a segment it lands in is never skipped by itself.
    private func noteSeek(_ v: Viewing, _ d: PlaybackDecision, to target: Int) {
        v.measure.excuse(at: clock.elapsedMs)
        if d.upNextMs > 0 && target < d.upNextMs { v.creditsDismissed = false }
        for s in d.segments where target >= s.startMs && target < s.end(or: duration(v, d)) { v.seekedInto.insert(s) }
    }

    public func skip() {
        stillWatching.noteInput()
        guard let v = active(), let segment = state.skip?.segment else { return }
        skipSegment(v, segment)
    }

    public func playNext() {
        stillWatching.noteInput()
        guard let v = active() else { return }
        // The controls' button before the card leaves this episode where it was; from the card it is done.
        goNext(v, finished: v.watched || state.upNext != nil || media?.phase == .ended)
    }

    public func watchCredits() {
        stillWatching.noteInput()
        guard let v = active() else { return }
        v.creditsDismissed = true
        v.countdownMs = nil
        refresh(0)
    }

    public func answerStillWatching(keepWatching: Bool) {
        guard let v = active(), v.askStill else { return }
        v.askStill = false
        if keepWatching {
            stillWatching.noteInput()
            goNext(v, finished: true)
        } else {
            exit(v)
        }
    }

    public func selectAudio(_ index: Int) {
        stillWatching.noteInput()
        guard let v = active(), let d = v.decision, index != v.selectedAudio,
              let ordinal = d.audio.firstIndex(where: { $0.index == index })
        else { return }
        v.selectedAudio = index
        v.audioManuallyChosen = true
        // Direct play switches the file's own track when this device plays it; otherwise the server sends it.
        if d.protocol == .file && !v.loading && media?.selectAudio(ordinal: ordinal, of: d.audio.count) == true {
            refresh(0)
            return
        }
        reload(v, at: position(v))
    }

    public func selectSubtitle(_ id: String?) {
        stillWatching.noteInput()
        guard let v = active(), let d = v.decision else { return }
        let track = id.flatMap { s in d.subtitles.first { $0.option.id == s } }
        if id != nil && track == nil { return }
        // Even Off while no track is selected is a manual choice: a delayed
        // automatic download must not turn it back on.
        manualSubtitleChoice(v)
        if id == v.selectedSubtitle { return }
        v.selectedSubtitle = id
        if let track, isImage(track) {
            // Image subtitles are burned in by the server, which restarts the stream here.
            reload(v, at: position(v), subtitleID: id, burnIn: true)
        } else if d.burnInSubtitleID != nil {
            // Leaving a burned-in subtitle needs a stream without it.
            reload(v, at: position(v), subtitleID: id, burnIn: false)
        } else {
            // Text subtitles are drawn here: the new track's cues replace the old.
            refresh(0)
        }
    }

    public func downloadSubtitle(_ resultID: String) {
        stillWatching.noteInput()
        guard let v = active(), let d = v.decision, let online = v.online,
              let result = online.results.first(where: { $0.id == resultID })
        else { return }
        // Downloaded for this file before: it is one of the decision's tracks already.
        if let id = result.trackID, d.subtitles.contains(where: { $0.option.id == id }) {
            selectSubtitle(id)
            return
        }
        guard online.downloading == nil else { return }
        manualSubtitleChoice(v)
        let revision = v.subtitleRevision
        v.online?.downloading = resultID
        v.online?.error = nil
        refresh(0)
        launch { [self] in
            do {
                let trackID = try await gateway.downloadSubtitle(fileID: v.request.fileID, resultID: resultID)
                guard viewing === v, !v.stopped else { return }
                if var o = v.online {
                    o.downloading = nil
                    o.results = o.results.map { r in
                        var copy = r
                        if r.id == resultID { copy.trackID = trackID }
                        return copy
                    }
                    o.remaining = o.remaining.map { max($0 - 1, 0) }
                    v.online = o
                }
                // The new track comes in the next decision.
                guard v.subtitleRevision == revision else { refresh(0); return }
                v.selectedSubtitle = trackID
                reload(v, at: position(v), subtitleID: trackID, burnIn: false)
            } catch is CancellationError {
            } catch {
                log.warning("Subtitle download failed: \(error.localizedDescription, privacy: .public)")
                guard viewing === v else { return }
                let quotaSpent = (error as? APIError)?.status == 429
                v.online?.downloading = nil
                v.online?.error = error.displayMessage.isEmpty ? "Could not download the subtitle." : error.displayMessage
                if quotaSpent { v.online?.remaining = 0 }
                refresh(0)
            }
        }
    }

    /// A pick in the player becomes this device's default, and ends
    /// Automatic for the play, or starts it.
    public func selectQuality(_ quality: StreamQuality) {
        stillWatching.noteInput()
        guard quality != asked else { return }
        self.quality = quality
        preferences.setQuality(quality)
        state.quality = asked
        guard let v = active(), v.decision != nil else { return }
        v.qualityNote = nil
        reload(v, at: position(v))
    }

    public func noteUserInput() {
        stillWatching.noteInput()
    }

    public func stop() {
        endViewing(.stopped)
    }

    /// The app went to the background (the screen calls this from the scene
    /// phase, but not in Picture in Picture or over AirPlay, where playing goes
    /// on): pauses, reports "closed", and sends nothing more until the app is
    /// back or the viewer plays again; the OS is asked for time to send it.
    public func close() {
        guard !backgrounded else { return }
        backgrounded = true
        guard let v = active(), v.decision != nil else { return }
        media?.pause()
        report(v, .closed, position(v), 0)
        if endHold == nil, delivering, let system { endHold = system.holdForDelivery() }
    }

    public func release() {
        guard !released else { return }
        endViewing(.stopped)
        released = true
        viewing = nil
        stopTicker()
        loadTask?.cancel()
        session?.close()
        session = nil
        player = nil
        media?.setListener(nil)
        media?.release()
        media = nil
        continuation.finish()
        // The queue drains (the last report goes out) on its own task.
    }

    // MARK: - Viewing lifecycle

    /// One file being watched: from ``start(_:)`` (or the next episode starting) until it stops.
    @MainActor
    final class Viewing {
        struct PendingSwitch {
            var previous: PlaybackDecision
            var atMs: Int
            var generation: Int64
        }
        var request: PlayRequest
        let generation: Int
        var decision: PlaybackDecision?
        /// The decision's next episode, with what the lookups added.
        var next: UpNext?
        var streamClock = StreamClock.zero
        var playID: String?
        var selectedAudio: Int?
        /// The viewer (or a handover) chose the audio: no preference moves it.
        var audioManuallyChosen = false
        /// This file's audio options didn't line up with the decision's
        /// tracks: ask without `selectsAudioTrack`, and the server remuxes.
        var audioUnselectable = false
        /// An offline play chose its audio from the loaded preferences.
        var offlineAudioChosen = false
        /// Video codecs this device failed to decode when sent as they are:
        /// left out of the profile for the rest of the play, so the server
        /// converts the video (PLAN-APPLE-REVIEW.md §3.1.2). Asked once a play.
        var refusedVideoCodecs = Set<String>()
        /// The decoder failure that asked again; its message stands if that fails too.
        var codecFailure: PlayerFailure?
        var selectedSubtitle: String?
        var subtitleManuallyChosen = false
        var subtitleRevision = 0
        var attemptedAutomaticSubtitle = false
        var automaticSubtitleInFlight = false
        /// Subtitles found online, once the search has answered.
        var online: OnlineSubtitles?
        var searchedOnline = false
        /// A decision is being fetched.
        var loading = false
        /// While loading: the file position it will start at (the scrubber shows it meanwhile).
        var pendingPositionMs: Int?
        var everReady = false
        var seekedInto = Set<Segment>()
        var creditsDismissed = false
        var askStill = false
        var countdownMs: Int?
        var fillSegment: Segment?
        var fillMs = 0
        /// Reached watchedAtMs, or the server said so.
        var watched = false
        /// Moved on (next episode, or a skip to the end): reports give the end as the position.
        var finished = false
        var stopped = false
        var exited = false
        var adminStopped = false
        var error: String?
        var lastReported: PlayState?
        /// When the decision came, on the monotonic clock: a growing recording grows from there.
        var decidedAtMs = 0
        /// A growing recording's length once its stream ended (an event playlist with its end tag).
        var grownMs: Int?
        var lastBeatAt = 0
        var recoveries = 0
        var recoveredAt = 0
        /// The latest decision's start and stalls.
        var measure: StreamMeasure
        /// What Automatic last said over the video, and until when.
        var qualityNote: String?
        var qualityNoteUntil = 0
        /// Said once that the lowest step stalls too.
        var floorNoted = false
        var handledGeneration: Int64 = 0
        var pendingSwitch: PendingSwitch?
        var awaitingFrameGeneration: Int64?
        var acceptedGeneration: Int64?
        var failedGeneration: Int64?
        var stepMessage: String?

        init(request: PlayRequest, generation: Int, at now: Int) {
            self.request = request
            self.generation = generation
            measure = StreamMeasure(requestedAt: now)
        }
    }

    private func active() -> Viewing? {
        guard let v = viewing, !v.stopped, !v.adminStopped else { return nil }
        return v
    }

    private func begin(_ request: PlayRequest) {
        generation += 1
        let v = Viewing(request: request, generation: generation, at: clock.elapsedMs)
        // A play handed over from another device keeps its tracks.
        v.selectedAudio = request.audioIndex
        v.audioManuallyChosen = request.audioIndex != nil
        v.selectedSubtitle = request.subtitleID
        v.subtitleManuallyChosen = request.subtitleID != nil || request.subtitleMode == .off
        viewing = v
        ensurePlayer()
        if !preferencesLoaded {
            preferencesLoaded = true
            launch { [self] in
                _ = await attempt("load preferences") { try await preferences.refresh() }
                preferencesReady = true
                // First-frame playback never waits for preferences, but a
                // delayed load must still get its one automatic attempt.
                refresh(0)
            }
        }
        // Every play gets a fresh media token (PLAN-CLIENTS.md §2.2).
        token = nil
        cueTracks = [:]
        cueFetches = []
        var s = PlayerState()
        s.title = request.title
        s.subtitle = request.subtitle
        s.backdropPath = request.backdropPath
        s.stillPath = request.stillPath
        s.posterPath = request.posterPath
        s.positionMs = request.startMs ?? 0
        s.quality = asked
        s.qualities = StreamQuality.offered(apiLevel: apiLevel)
        state = s
        showArtwork(for: request)
        startTicker()
        v.loading = true
        loadTask?.cancel()
        loadTask = launch { [weak self] in
            guard let self else { return }
            // A bare play link: name the file while the server decides, and
            // start without waiting for the name (PLAN-APPLE-REVIEW.md §4.2.6).
            async let named = request.title.isEmpty ? attempt("name the file") { try await self.lookups.name(request) } ?? nil : nil
            do {
                var d = try await decide(v, startMs: request.startMs, playID: nil)
                if overtaken(v, d) { return }
                var start = request.startMs ?? 0
                // No explicit start: pick up where the viewer left off. A VOD
                // playlist (or the file) just starts there; an event playlist
                // is restarted there, as the web does.
                if request.startMs == nil && d.resumeMs > resumeMinMs {
                    start = d.resumeMs
                    if StreamClock.of(d).event {
                        let first = d
                        do {
                            d = try await decide(v, startMs: start, playID: d.playID)
                        } catch {
                            endUnused(first, of: v)
                            throw error
                        }
                        if overtaken(v, d) {
                            endUnused(first, of: v)
                            return
                        }
                        endReplacedSession(first, d)
                    }
                }
                apply(v, d, requestedStartMs: start)
            } catch is CancellationError {
                return
            } catch {
                fail(v, error)
                return
            }
            if let named = await named { rename(v, named) }
        }
    }

    private func rename(_ v: Viewing, _ named: PlayRequest) {
        guard viewing === v else { return }
        v.request.title = named.title
        v.request.subtitle = named.subtitle
        v.request.backdropPath = v.request.backdropPath ?? named.backdropPath
        v.request.stillPath = v.request.stillPath ?? named.stillPath
        v.request.posterPath = v.request.posterPath ?? named.posterPath
        state.title = v.request.title
        state.subtitle = v.request.subtitle
        state.backdropPath = v.request.backdropPath
        state.stillPath = v.request.stillPath
        state.posterPath = v.request.posterPath
        media?.retitle(v.request.title, subtitle: v.request.subtitle)
        showArtwork(for: v.request)
        refresh(0)
    }

    /// Re-requests the decision for the same play at `atMs`: track and quality changes, event seeks, a new token, recoveries.
    private func reload(_ v: Viewing, at atMs: Int, newToken: Bool = false) {
        reload(v, at: atMs, subtitleID: v.selectedSubtitle, burnIn: burnsIn(v, v.selectedSubtitle), newToken: newToken)
    }

    /// The stream playing goes on until the new one loads, which matters most
    /// when Automatic steps down while it still plays, and the new one starts
    /// where it got to. A seek elsewhere, or a stream that stopped, starts at `atMs`.
    private func reload(_ v: Viewing, at atMs: Int, subtitleID: String?, burnIn: Bool, newToken: Bool = false, qualitySwitch: ProgressReply? = nil) {
        let here = v.pendingPositionMs == nil && playsOn(v) && atMs == position(v)
        if qualitySwitch == nil {
            if let pending = v.pendingSwitch, v.awaitingFrameGeneration != nil {
                restorePrevious(v, pending, showFailureNote: false)
            } else if let previous = v.pendingSwitch?.previous, let current = v.decision {
                endReplacedSession(previous, current)
            }
            v.pendingSwitch = nil
            v.awaitingFrameGeneration = nil
            v.acceptedGeneration = nil
        }
        v.loading = true
        v.pendingPositionMs = here ? nil : atMs
        v.measure = StreamMeasure(requestedAt: clock.elapsedMs)
        if newToken { token = nil }
        loadTask?.cancel()
        loadTask = launch { [weak self] in
            guard let self else { return }
            do {
                let d = try await decide(v, startMs: atMs, playID: v.playID, subtitleID: subtitleID, burnIn: burnIn, switchGeneration: qualitySwitch?.switchGeneration)
                if overtaken(v, d, switchGeneration: qualitySwitch?.switchGeneration) { return }
                // Playing or paused as the viewer has it now: the picker pauses while open
                // and resumes on closing, which is usually while the decision is out.
                apply(v, d, requestedStartMs: atMs, playWhenReady: media?.playWhenReady ?? true, qualitySwitch: qualitySwitch)
            } catch is CancellationError {
                // Overtaken before the server answered: the switch it was for didn't happen.
                if let generation = qualitySwitch?.switchGeneration, viewing === v, !v.stopped { v.failedGeneration = generation }
            } catch {
                if let generation = qualitySwitch?.switchGeneration, viewing === v, v.decision != nil {
                    if let pending = v.pendingSwitch, pending.generation == generation {
                        restorePrevious(v, pending)
                    } else {
                        v.loading = false
                        v.pendingPositionMs = nil
                        v.failedGeneration = generation
                        v.stepMessage = nil
                        showNote(v, "Still playing at \(v.decision?.autoStep?.shortLabel ?? "Original"). Quality will retry later.")
                        refresh(0)
                    }
                } else { fail(v, error) }
            }
        }
        refresh(0)
    }

    private func decide(_ v: Viewing, startMs: Int?, playID: String?) async throws -> PlaybackDecision {
        try await decide(v, startMs: startMs, playID: playID, subtitleID: v.selectedSubtitle, burnIn: burnsIn(v, v.selectedSubtitle))
    }

    private func decide(_ v: Viewing, startMs: Int?, playID: String?, subtitleID: String?, burnIn: Bool, switchGeneration: Int64? = nil) async throws -> PlaybackDecision {
        // Automatic sends the device's own profile: the server applies the step's caps.
        var device = profile.current().capped(by: asked)
        if v.audioUnselectable { device.selectsAudioTrack = nil }
        device.videoCodecs.removeAll(where: v.refusedVideoCodecs.contains)
        let request = DecisionRequest(
            fileID: v.request.fileID,
            profile: device,
            client: client(),
            startMs: startMs,
            audioIndex: v.selectedAudio,
            subtitleID: subtitleID,
            subtitleMode: v.subtitleManuallyChosen && subtitleID == nil ? .off : .auto,
            burnIn: burnIn,
            quality: asked,
            playID: playID,
            vod: v.request.recordingID == nil,
            recordingID: v.request.recordingID,
            autoRecovery: asked == .auto && (apiLevel ?? 0) >= 24 && v.request.recordingID == nil,
            switchGeneration: switchGeneration
        )
        // The token is asked for at the same time as the decision, not after
        // it (PLAN-APPLE-REVIEW.md §4.2.1); a valid one is kept, and one the
        // decision brings is taken instead.
        let beside = !tokenValid && gateway.needsTokenBeside(request)
        let gateway = gateway
        async let fetched: MediaToken? = beside ? gateway.mediaToken() : nil
        let decision = try await gateway.decide(request)
        // A download plays from the device and needs no media token (PLAN-DOWNLOADS.md §10.4).
        if decision.url.isFileURL {
            token = nil
            return decision
        }
        do {
            if let carried = decision.mediaToken {
                keep(carried)
            } else if let fresh = try await fetched {
                keep(fresh)
            } else if !tokenValid {
                keep(try await gateway.mediaToken())
            }
        } catch {
            endUnused(decision, of: v)
            throw error
        }
        return decision
    }

    private var tokenValid: Bool { token != nil && clock.elapsedMs < tokenRefreshAt }

    private func keep(_ fresh: MediaToken) {
        token = fresh
        // Timed on this device's clock, with a floor, so a clock that
        // disagrees with the server's cannot cause a reload loop.
        let lifetime = max(Int(fresh.expiresAt.timeIntervalSince(clock.now) * 1000), tokenMinLifetimeMs)
        tokenRefreshAt = clock.elapsedMs + lifetime - tokenMarginMs
    }

    /// A decision that came back after its load was cancelled (a newer
    /// request, Close, the next episode) is not played: its server session
    /// ends, as Live TV's does, and the quality switch it was for is reported
    /// failed (PLAN-APPLE-REVIEW.md §3.1.3).
    private func overtaken(_ v: Viewing, _ d: PlaybackDecision, switchGeneration: Int64? = nil) -> Bool {
        guard Task.isCancelled else { return false }
        endUnused(d, of: v)
        if let switchGeneration, viewing === v, !v.stopped { v.failedGeneration = switchGeneration }
        return true
    }

    /// Ends `d`'s server session unless the viewing plays it, or keeps it to go back to.
    private func endUnused(_ d: PlaybackDecision, of v: Viewing) {
        guard let id = d.sessionID, id != v.decision?.sessionID, id != v.pendingSwitch?.previous.sessionID else { return }
        send(.endSession(id))
    }

    private func apply(_ v: Viewing, _ d: PlaybackDecision, requestedStartMs: Int, playWhenReady: Bool = true, qualitySwitch: ProgressReply? = nil) {
        guard viewing === v, !v.stopped, !v.adminStopped else { return }
        let previous = v.decision
        let seekedWhileLoading = v.pendingPositionMs.map { $0 != requestedStartMs } ?? false
        // Where the stream that played on while this was fetched got to.
        let carried = previous != nil && v.pendingPositionMs == nil ? position(v) : nil
        v.decidedAtMs = clock.elapsedMs
        v.grownMs = nil
        let start = min(max(v.pendingPositionMs ?? carried ?? requestedStartMs, 0), duration(v, d))
        let streamClock = StreamClock.of(d)
        v.decision = d
        v.streamClock = streamClock
        v.playID = d.playID
        if d.next?.fileID != v.next?.fileID {
            v.next = d.next
            if let next = d.next { describeNext(v, next) }
        }
        if let audio = d.selectedAudio { v.selectedAudio = audio }
        if d.url.isFileURL && preferencesReady { chooseOfflineAudio(v, d) }
        if !v.subtitleManuallyChosen, let selected = d.selectedSubtitleID,
           d.subtitles.contains(where: { $0.option.id == selected }) {
            v.selectedSubtitle = selected
        }
        if let burned = d.burnInSubtitleID { v.selectedSubtitle = burned }
        if let previous {
            if let generation = qualitySwitch?.switchGeneration {
                v.pendingSwitch = Viewing.PendingSwitch(previous: previous, atMs: start, generation: generation)
                v.awaitingFrameGeneration = generation
            } else { endReplacedSession(previous, d) }
        }
        // A seek while an event session was being fetched went somewhere else: fetch one there.
        if streamClock.event && seekedWhileLoading && abs(start - d.startMs) > eventStartToleranceMs {
            if let pending = v.pendingSwitch {
                restorePrevious(v, pending)
                return
            }
            reload(v, at: start)
            return
        }
        v.loading = false
        v.pendingPositionMs = nil
        v.error = nil
        // The token goes on the server's own address, which the LAN one then replaces.
        let tokened = token?.apply(to: d.url) ?? d.url
        let local = localRoute?.apply(to: tokened)
        playingLocally = local != nil
        media?.load(PlayerSource(
            url: local ?? tokened,
            mimeType: d.mimeType,
            hls: d.protocol == .hls,
            live: streamClock.event,
            startPositionMs: streamClock.playerPosition(start),
            playWhenReady: playWhenReady,
            title: v.request.title,
            subtitle: v.request.subtitle,
            audio: audioStart(d, v.selectedAudio)
        ))
        refresh(0)
    }

    /// A direct-play file starts on the decided track, which the player
    /// selects itself (PLAN-LANGUAGES.md §5.2). A stream carries only the
    /// track the server chose; a file with one track has nothing to choose.
    private func audioStart(_ d: PlaybackDecision, _ selected: Int?) -> AudioStart? {
        guard d.protocol == .file, d.audio.count > 1, let selected,
              let ordinal = d.audio.firstIndex(where: { $0.index == selected }) else { return nil }
        return AudioStart(ordinal: ordinal, count: d.audio.count)
    }

    /// An offline play chooses its audio here, as the server would for a
    /// stream, from the preferences loaded (PLAN-LANGUAGES.md §5.5); once,
    /// and never over the viewer's pick.
    private func chooseOfflineAudio(_ v: Viewing, _ d: PlaybackDecision) {
        guard !v.offlineAudioChosen, !v.audioManuallyChosen, d.protocol == .file else { return }
        v.offlineAudioChosen = true
        guard let preferred = preferredAudioIndex(tracks: d.audio, profile: profile.current(), languages: preferences.playback.audio.languages) else { return }
        v.selectedAudio = preferred
    }

    /// The player couldn't select the decided track. A stream asks again
    /// naming it and without `selectsAudioTrack`, and gets a remux; an
    /// offline play keeps the file's default.
    private func audioUnmatched() {
        guard let v = active(), let d = v.decision, d.protocol == .file, !v.loading,
              let selected = v.selectedAudio ?? d.selectedAudio, selected != defaultAudioIndex(d.audio) else { return }
        log.warning("Audio options don't line up with the decision's \(d.audio.count) tracks")
        if d.url.isFileURL {
            v.selectedAudio = defaultAudioIndex(d.audio)
            refresh(0)
            return
        }
        v.audioUnselectable = true
        v.selectedAudio = selected
        reload(v, at: position(v))
    }

    /// Looks for subtitles online once the video has started, so starting it
    /// never waits on the provider; the server keeps the answer for a day.
    private func searchOnline(_ v: Viewing) {
        v.searchedOnline = true
        guard v.request.recordingID == nil else { return }
        launch { [self] in
            guard let found = await attempt("search subtitles online", { try await gateway.searchSubtitles(fileID: v.request.fileID) }) ?? nil,
                  viewing === v, !v.stopped
            else { return }
            v.online = found
            refresh(0)
        }
    }

    /// The server validates ownership, current preferences and permission.
    /// A newer local choice or play always wins over a late result.
    private func downloadAutomaticSubtitle(_ v: Viewing) {
        guard preferencesReady else { return }
        v.attemptedAutomaticSubtitle = true
        guard v.request.recordingID == nil else { return }
        let prefs = preferences.playback.subtitles
        guard prefs.automaticDownload, !v.subtitleManuallyChosen,
              v.selectedSubtitle == nil, let decision = v.decision, !decision.url.isFileURL,
              let playID = v.playID else { return }
        // With the foreign-audio choice, only for audio the person doesn't follow.
        let audio = decision.audio.first { $0.index == (v.selectedAudio ?? decision.selectedAudio) }
        guard startingSubtitles(audioLanguage: audio?.language, audioLanguages: preferences.playback.audio.languages, preferences: prefs).mode == .on else { return }
        let revision = v.subtitleRevision
        v.automaticSubtitleInFlight = true
        launch { [self] in
            defer { v.automaticSubtitleInFlight = false }
            guard let track = await attempt("download an automatic subtitle", {
                try await gateway.automaticSubtitle(fileID: v.request.fileID, playID: playID)
            }) ?? nil,
                  viewing === v, !v.stopped, !v.adminStopped, v.playID == playID,
                  v.subtitleRevision == revision, !v.subtitleManuallyChosen,
                  v.selectedSubtitle == nil else { return }
            v.selectedSubtitle = track.option.id
            // The new decision brings the downloaded track and its authenticated URL.
            reload(v, at: position(v), subtitleID: track.option.id, burnIn: false)
        }
    }

    private func manualSubtitleChoice(_ v: Viewing) {
        v.subtitleManuallyChosen = true
        v.subtitleRevision += 1
        guard v.automaticSubtitleInFlight, let playID = v.playID else { return }
        launch { [self] in
            _ = await attempt("cancel automatic subtitle selection") {
                try await gateway.cancelAutomaticSubtitle(fileID: v.request.fileID, playID: playID)
            }
        }
    }

    /// Fills in the next episode's runtime and season finale flag when they come.
    private func describeNext(_ v: Viewing, _ next: UpNext) {
        launch { [self] in
            guard let described = await attempt("look up the next episode", { try await lookups.describe(next) }),
                  viewing === v, v.next?.fileID == next.fileID
            else { return }
            v.next = described
            refresh(0)
        }
    }

    private func fail(_ v: Viewing, _ error: any Error) {
        guard viewing === v else { return }
        log.warning("Playback request failed: \(error.localizedDescription, privacy: .public)")
        v.loading = false
        let message = error.displayMessage
        // Asked again without a codec this device couldn't decode, and that failed too: the decoder's message stands.
        v.error = v.codecFailure.map(errorMessage) ?? (message.isEmpty ? "Could not start playback." : message)
        refresh(0)
    }

    /// Ends the viewing: reports `state` and stops the server's session.
    private func endViewing(_ playState: PlayState) {
        guard let v = viewing, !v.stopped else { return }
        loadTask?.cancel()
        v.loading = false
        if let d = v.decision {
            report(v, playState, position(v), 0)
            if let id = d.sessionID { send(.endSession(id)) }
            if let previous = v.pendingSwitch?.previous { endReplacedSession(previous, d) }
        }
        v.stopped = true
        stopTicker()
        media?.stop()
    }

    /// Leaves the player.
    private func exit(_ v: Viewing) {
        guard !v.exited else { return }
        v.exited = true
        endViewing(.stopped)
        continuation.yield(.exit)
    }

    /// Starts the next episode in place; `finished` reports this one as watched to its end.
    private func goNext(_ v: Viewing, finished: Bool) {
        guard v.decision != nil, let next = v.next else { return }
        if finished {
            v.finished = true
            v.watched = true
        }
        endViewing(.stopped)
        continuation.yield(.startedNext(fileID: next.fileID))
        begin(PlayRequest(
            fileID: next.fileID,
            title: next.showTitle,
            subtitle: "\(episodeCode(season: next.season, episode: next.episode)) · \(next.title)",
            backdropPath: v.request.backdropPath,
            stillPath: next.stillPath,
            posterPath: v.request.posterPath
        ))
    }

    private func autoAdvance(_ v: Viewing) {
        if stillWatching.shouldAsk() {
            v.askStill = true
            v.countdownMs = nil
            refresh(0)
            return
        }
        stillWatching.noteAutoAdvance()
        goNext(v, finished: true)
    }

    private func skipSegment(_ v: Viewing, _ segment: Segment) {
        guard let d = v.decision else { return }
        let end = segment.end(or: duration(v, d))
        if end < duration(v, d) - endSlackMs {
            seekWithin(v, end)
            return
        }
        // Skipping to the end finishes the item: watched, and leave.
        v.finished = true
        v.watched = true
        exit(v)
    }

    private func seekWithin(_ v: Viewing, _ target: Int) {
        guard let p = media else { return }
        if v.loading {
            v.pendingPositionMs = target
        } else if v.streamClock.canSeekLocally(target, bufferedPlayerMs: p.bufferedPositionMs) {
            v.measure.excuse(at: clock.elapsedMs)
            p.seek(toMs: v.streamClock.playerPosition(target))
        } else {
            // Outside an event session's buffer: a new session there, same play.
            reload(v, at: target)
            return
        }
        refresh(0)
    }

    private func adminStop(_ v: Viewing, _ message: String) {
        guard !v.adminStopped, !v.stopped else { return }
        v.adminStopped = true
        loadTask?.cancel()
        v.loading = false
        media?.stop()
        stopTicker()
        var s = state
        s.status = .paused
        s.stopMessage = message
        s.skip = nil
        s.upNext = nil
        s.askStillWatching = false
        s.cues = []
        publish(s)
    }

    private func onPlayerError(_ failure: PlayerFailure) {
        guard let v = active(), v.decision != nil, !v.loading else { return }
        log.warning("Player error: \(failure.detail, privacy: .public)")
        if let pending = v.pendingSwitch {
            restorePrevious(v, pending)
            return
        }
        if let generation = v.awaitingFrameGeneration {
            v.failedGeneration = generation
            v.awaitingFrameGeneration = nil
            v.stepMessage = nil
        }
        // Off AirPlay the address in use plays again: the LAN one may be gone (the phone left home).
        if playingLocally && !external { localRoute = nil }
        if let codec = refusedCodec(v, failure) {
            // The video sent as it is didn't decode: once a play, ask again
            // without its codec, so the server converts it (PLAN-APPLE-REVIEW.md §3.1.2).
            log.warning("Asking again without \(codec, privacy: .public)")
            v.refusedVideoCodecs.insert(codec)
            v.codecFailure = failure
            reload(v, at: position(v))
            return
        }
        if failure.recoverable && v.recoveries < maxRecoveries {
            v.recoveries += 1
            v.recoveredAt = clock.elapsedMs
            reload(v, at: position(v), newToken: failure.httpStatus == 401 || failure.httpStatus == 403)
            return
        }
        v.error = errorMessage(failure)
        refresh(0)
    }

    /// The codec to leave out after `failure`: a decoder or unreadable-source
    /// failure of a stream that copied the video, the first time in the play.
    /// Not for a download, which plays the file it has.
    private func refusedCodec(_ v: Viewing, _ failure: PlayerFailure) -> String? {
        guard failure.kind == .decoder || failure.kind == .source, v.refusedVideoCodecs.isEmpty,
              let d = v.decision, !d.url.isFileURL, let codec = d.copiedVideoCodec else { return nil }
        return codec
    }

    private func restorePrevious(_ v: Viewing, _ pending: Viewing.PendingSwitch, showFailureNote: Bool = true) {
        v.pendingSwitch = nil
        v.awaitingFrameGeneration = nil
        v.acceptedGeneration = nil
        v.failedGeneration = pending.generation
        v.pendingPositionMs = pending.atMs
        v.stepMessage = nil
        apply(v, pending.previous, requestedStartMs: pending.atMs)
        if showFailureNote {
            showNote(v, "Still playing at \(pending.previous.autoStep?.shortLabel ?? "Original"). Quality will retry later.")
        }
        refresh(0)
    }

    // MARK: - The tick

    private func startTicker() {
        guard ticker == nil else { return }
        lastTickAt = clock.elapsedMs
        scheduleTick()
    }

    private func scheduleTick() {
        ticker = clock.after(ms: tickMs) { [weak self] in
            guard let self else { return }
            let now = clock.elapsedMs
            let dt = now - lastTickAt
            lastTickAt = now
            // The next tick first: this one may end the viewing, which cancels it.
            scheduleTick()
            refresh(dt)
        }
    }

    private func stopTicker() {
        ticker?.cancel()
        ticker = nil
    }

    /// Brings ``state`` up to date and acts on it: heartbeats, the prompts and
    /// their timers (advanced by `dtMs`), the end of the file, the token.
    private func refresh(_ dtMs: Int) {
        guard let v = viewing, !v.stopped, !v.adminStopped else { return }
        followRoute()
        guard let d = v.decision, let p = media else {
            var s = state
            s.status = v.error != nil ? .error : .loading
            s.error = v.error
            publish(s)
            return
        }
        let now = clock.elapsedMs
        if d.url.isFileURL, preferencesReady, !v.offlineAudioChosen, !v.audioManuallyChosen, !v.loading, d.protocol == .file {
            // The preferences came after an offline play started: switch in place,
            // once the player has the file's tracks.
            let preferred = preferredAudioIndex(tracks: d.audio, profile: profile.current(), languages: preferences.playback.audio.languages)
            if let preferred, preferred != (v.selectedAudio ?? d.selectedAudio), let ordinal = d.audio.firstIndex(where: { $0.index == preferred }) {
                if p.selectAudio(ordinal: ordinal, of: d.audio.count) {
                    v.selectedAudio = preferred
                    v.offlineAudioChosen = true
                } else if p.phase == .ready {
                    v.offlineAudioChosen = true
                }
            } else {
                v.offlineAudioChosen = true
            }
        }
        if d.url.isFileURL, preferencesReady, !v.subtitleManuallyChosen {
            let audio = d.audio.first { $0.index == (v.selectedAudio ?? d.selectedAudio) }
            v.selectedSubtitle = preferredSubtitleID(tracks: d.subtitles.map(\.option), audioLanguage: audio?.language,
                                                     audioLanguages: preferences.playback.audio.languages,
                                                     preferences: preferences.playback.subtitles)
        }
        let pos = position(v)
        // While a reload is out the old stream may play on: the viewer sees it, the server hears "buffering".
        let carrying = v.loading && playsOn(v) && v.pendingPositionMs == nil
        let buffered = v.loading && !carrying ? 0 : max(v.streamClock.filePosition(p.bufferedPositionMs) - pos, 0)
        let phase = p.phase
        if !v.loading && phase == .ready {
            v.everReady = true
            v.codecFailure = nil
        }
        if v.everReady && !v.searchedOnline { searchOnline(v) }
        if v.everReady && !v.attemptedAutomaticSubtitle { downloadAutomaticSubtitle(v) }
        let waiting: PlayerStatus = v.everReady ? .buffering : .loading
        let status: PlayerStatus =
            if v.error != nil { .error }
            else if (v.loading && !carrying) || phase == .idle { waiting }
            else if phase == .ended { .ended }
            else if !p.playWhenReady { .paused }
            else if phase == .buffering { waiting }
            else { .playing }
        let stalled = !v.loading && v.measure.observe(status, ready: phase == .ready, at: now)
        if stalled && asked == .auto && d.autoStep == .lowest && !v.floorNoted {
            v.floorNoted = true
            showNote(v, "Your connection is too slow to play this smoothly.")
        }
        let ended = status == .ended
        // The end of a growing recording's event playlist is its end, even when it
        // stopped early: Recording and Go to now go, and the length is where it ended.
        if ended, d.growing != nil, v.grownMs == nil { v.grownMs = pos }
        if status == .playing && v.recoveries > 0 && now - v.recoveredAt >= recoveryResetMs { v.recoveries = 0 }

        var forceBeat = false
        if !v.watched && d.watchedAtMs > 0 && pos >= d.watchedAtMs {
            v.watched = true
            forceBeat = true
        }

        // Next Episode: from upNextMs, or at the end even after Watch credits.
        let prefs = preferences.playback
        let next = v.next
        let nextOffered = next != nil && d.upNextMs > 0
        let showNext = showsUpNext(offered: nextOffered, askingStillWatching: v.askStill, status: status, positionMs: pos, upNextMs: d.upNextMs, creditsDismissed: v.creditsDismissed)
        v.countdownMs = upNextCountdown(v.countdownMs, showing: showNext, autoplay: prefs.autoplayNext, status: status, dtMs: dtMs)

        // Skip: the playing segment and what the viewer's setting does with it.
        let segment = v.loading || ended || status == .error || showNext ? nil : activeSegment(d, positionMs: pos, nextOffered: nextOffered)
        let action = segment.map { skipAction($0, durationMs: duration(v, d), mode: prefs.skipMode($0.kind), seekedInto: v.seekedInto.contains($0)) } ?? .none
        if action == .autoDelay && segment == v.fillSegment {
            if status == .playing { v.fillMs += dtMs }
        } else {
            v.fillSegment = action == .autoDelay ? segment : nil
            v.fillMs = 0
        }
        let skip: SkipPrompt? = switch (action, segment) {
        case let (.button, s?): SkipPrompt(segment: s, label: s.kind.skipLabel)
        case let (.autoDelay, s?): SkipPrompt(segment: s, label: s.kind.skipLabel, autoProgress: min(max(Double(v.fillMs) / Double(autoDelayMs), 0), 1))
        default: nil
        }

        var s = state
        s.status = status
        s.isOffline = d.url.isFileURL
        s.positionMs = pos
        s.durationMs = duration(v, d)
        s.growing = stillGrowing(v, d)
        s.bufferedMs = buffered
        s.segments = d.segments
        s.skip = skip
        s.nextEpisode = next
        s.upNext = showNext ? next.map { UpNextPrompt(next: $0, countdownMs: v.countdownMs) } : nil
        s.askStillWatching = v.askStill
        s.audio = d.audio
        s.selectedAudio = v.selectedAudio
        s.subtitles = d.subtitles.map(\.option)
        s.selectedSubtitle = v.selectedSubtitle
        s.cues = cues(v, d, p)
        s.online = v.online
        s.quality = asked
        s.qualities = StreamQuality.offered(apiLevel: apiLevel)
        s.autoStep = asked == .auto ? d.autoStep : nil
        s.qualityNote = now < v.qualityNoteUntil ? v.qualityNote : nil
        s.mode = d.mode
        s.video = d.video
        s.trickplay = d.trickplay
        s.reasons = d.reasons
        s.stopMessage = nil
        s.error = v.error
        publish(s)
        session?.update(NowPlayingInfo(title: s.title, subtitle: s.subtitle, durationMs: duration(v, d), positionMs: pos, playing: status == .playing, artwork: artwork))

        heartbeat(v, v.loading ? waiting : status, pos, buffered, force: forceBeat)

        // Actions last: each may start a new load or a new viewing.
        if ended && !nextOffered {
            v.finished = true
            v.watched = true
            exit(v)
        } else if action == .autoNow, let segment {
            skipSegment(v, segment)
        } else if action == .autoDelay, v.fillMs >= autoDelayMs, let segment {
            skipSegment(v, segment)
        } else if v.countdownMs == 0 {
            autoAdvance(v)
        } else if !v.loading && token != nil && now >= tokenRefreshAt && status != .ended && status != .error {
            // The play outlived its media token: a new one, and a new session here.
            reload(v, at: pos)
        }
    }

    private func publish(_ s: PlayerState) {
        if s != state { state = s }
    }

    // MARK: - Subtitles drawn here

    /// The selected text track's URL (without the token), if it has cues to draw.
    private func cueURL(_ v: Viewing, _ d: PlaybackDecision) -> URL? {
        guard let id = v.selectedSubtitle, id != d.burnInSubtitleID,
              let track = d.subtitles.first(where: { $0.option.id == id }),
              track.format == "webvtt", !track.option.burnInOnly
        else { return nil }
        return track.url
    }

    /// The cues on screen now, in file time. The track's own times are player
    /// time: the server shifts an event playlist's cues to its start.
    private func cues(_ v: Viewing, _ d: PlaybackDecision, _ p: any MediaPlayer) -> [SubtitleCue] {
        guard !v.loading || (playsOn(v) && v.pendingPositionMs == nil), let url = cueURL(v, d) else { return [] }
        guard let track = cueTracks[url] else {
            fetchCues(url, for: v)
            return []
        }
        let offset = v.streamClock.offsetMs
        return track.active(at: p.positionMs).map { SubtitleCue(startMs: $0.startMs + offset, endMs: $0.endMs + offset, text: $0.text) }
    }

    /// About 10 times a second while playing: only a change of cues is news.
    private func updateCues() {
        guard let v = active(), let d = v.decision, let p = media else { return }
        let now = cues(v, d, p)
        if now != state.cues { state.cues = now }
    }

    private func fetchCues(_ url: URL, for v: Viewing) {
        guard !cueFetches.contains(url) else { return }
        cueFetches.insert(url)
        let withToken = token?.apply(to: url) ?? url
        launch { [self] in
            // A failed fetch stays in cueFetches, so it is not retried every tick; a new play tries again.
            guard let text = await attempt("load subtitles", { try await gateway.subtitleText(withToken) }), viewing === v else { return }
            cueTracks[url] = CueTrack(cues: WebVTT.parse(text))
            cueFetches.remove(url)
            updateCues()
        }
    }

    // MARK: - Now Playing's picture

    /// Loads the play's picture when it is not the one shown: the next
    /// episode's still replaces the last one, and a restart keeps it.
    private func showArtwork(for request: PlayRequest) {
        let image = nowPlayingImage(request)
        guard image != artworkImage else { return }
        artworkImage = image
        artwork = nil
        guard let image else { return }
        launch { [self] in
            guard let data = await attempt("load the Now Playing artwork", { try await lookups.artwork(image) }) ?? nil,
                  artworkImage == image
            else { return }
            artwork = NowPlayingArtwork(id: image.id, data: data)
            refresh(0)
        }
    }

    // MARK: - AirPlay

    /// Follows the video onto another screen (PLAN-IOS.md §5). The Apple TV
    /// fetches the stream itself, so when the server's address in use is one
    /// it is unlikely to reach (a tailnet name, a public name, localhost), the
    /// play restarts at its position on the same server's LAN address, as a
    /// quality change restarts it. Heartbeats still go from here to the
    /// address in use. With no LAN address it plays on as it is.
    private func followRoute() {
        let now = media?.externalPlaybackActive == true
        guard now != external else { return }
        external = now
        guard now, !playingLocally else { return }
        if localRoute != nil {
            restartLocally()
            return
        }
        guard !lookingForLocal, let servers, let base = servers.current?.server.baseURL, !isLANHost(base.host() ?? "") else { return }
        lookingForLocal = true
        launch { [self] in
            let found = await servers.localAddress()
            lookingForLocal = false
            guard let found else {
                log.info("AirPlay: no LAN address for the server, so the stream stays where it is")
                return
            }
            localRoute = LocalRoute(from: base, to: found)
            if external { restartLocally() }
        }
    }

    /// Restarts the play on the LAN address. A play still loading picks it up by itself.
    private func restartLocally() {
        guard let v = active(), let d = v.decision, !v.loading, !playingLocally, localRoute?.apply(to: d.url) != nil else { return }
        if let pending = v.pendingSwitch {
            // AirPlay needs an address its receiver can reach. Preserve the
            // earlier stream if that route changes before the new frame.
            restorePrevious(v, pending)
            return
        }
        reload(v, at: position(v))
    }

    // MARK: - Heartbeats

    private func heartbeat(_ v: Viewing, _ status: PlayerStatus, _ pos: Int, _ buffered: Int, force: Bool) {
        let s = playState(for: status)
        guard force || heartbeatDue(s, lastReported: v.lastReported, sinceLastBeatMs: clock.elapsedMs - v.lastBeatAt, auto: asked == .auto) else { return }
        report(v, s, pos, buffered)
    }

    /// Queues a heartbeat; false when none may be sent.
    @discardableResult
    private func report(_ v: Viewing, _ playState: PlayState, _ pos: Int, _ buffered: Int) -> Bool {
        guard let d = v.decision, let playID = v.playID, !v.stopped, !v.adminStopped else { return false }
        // After "closed", nothing but the final "stopped" until the app is back.
        if backgrounded && playState != .closed && playState != .stopped { return false }
        v.lastReported = playState
        v.lastBeatAt = clock.elapsedMs
        let position = v.finished ? duration(v, d) : pos
        let now = clock.elapsedMs
        let transfer = media?.transfer
        send(.report(ProgressReport(
            fileID: v.request.fileID, positionMs: position, durationMs: duration(v, d), playID: playID, state: playState, bufferedMs: buffered,
            throughputBps: transfer?.bps ?? media?.throughputBps,
            throughputAgeMs: transfer?.ageMs,
            switchGeneration: playState == .playing ? v.acceptedGeneration : nil,
            switchFailedGeneration: v.failedGeneration,
            stalls: v.measure.stalls, stallMs: v.measure.stallMs(at: now), startupMs: v.measure.takeStartup(), recordingID: v.request.recordingID
        ), generation: v.generation))
        return true
    }

    private func send(_ message: Outgoing) {
        Self.enqueue(message, into: &outbox, behindDelivery: sending)
        guard !delivering else { return }
        delivering = true
        // Holds the controller until the queue is empty, so the last reports go out after release.
        launch { [self] in
            while !outbox.isEmpty {
                let next = outbox.removeFirst()
                sending = true
                await deliver(next)
                sending = false
            }
            delivering = false
            endHold?()
            endHold = nil
        }
    }

    /// Adds `message` to the queue. While the server can't be reached each
    /// report waits for its time-out (`behindDelivery`: one is out), so a
    /// progress report replaces the one of the same viewing still waiting:
    /// only the newest position is worth sending (PLAN-APPLE-REVIEW.md
    /// §3.1.4). Stops, "closed", session ends and reports that acknowledge or
    /// refuse a quality switch are never dropped, and the rest keep their
    /// order. A dropped report's startup time goes with its replacement.
    static func enqueue(_ message: Outgoing, into outbox: inout [Outgoing], behindDelivery: Bool = true) {
        guard behindDelivery, case .report(var new, let generation) = message, new.isProgress else {
            outbox.append(message)
            return
        }
        outbox.removeAll { queued in
            guard case let .report(old, g) = queued, g == generation, old.isProgress,
                  old.switchGeneration == nil || old.switchGeneration == new.switchGeneration,
                  old.switchFailedGeneration == nil || old.switchFailedGeneration == new.switchFailedGeneration
            else { return false }
            if new.startupMs == nil { new.startupMs = old.startupMs }
            return true
        }
        outbox.append(.report(new, generation: generation))
    }

    private func deliver(_ message: Outgoing) async {
        switch message {
        case let .report(report, generation):
            guard let reply = await attempt("send a heartbeat", { try await gateway.reportProgress(report) }) else { return }
            onReply(generation, report, reply)
        case let .endSession(id):
            _ = await attempt("stop the session") { try await gateway.stopSession(id) }
        }
    }

    private func onReply(_ generation: Int, _ report: ProgressReport, _ reply: ProgressReply) {
        guard let v = viewing, v.generation == generation else { return }
        if let acknowledged = report.switchGeneration, acknowledged == v.acceptedGeneration {
            if let pending = v.pendingSwitch, let current = v.decision { endReplacedSession(pending.previous, current) }
            v.acceptedGeneration = nil
            v.pendingSwitch = nil
            if let message = v.stepMessage { showNote(v, message) }
            v.stepMessage = nil
            refresh(0)
        }
        if let failed = report.switchFailedGeneration, failed == v.failedGeneration { v.failedGeneration = nil }
        if reply.completed { v.watched = true }
        guard !v.stopped else { return }
        // The play had ended (timed out, server restarted): the server carries on with a new one.
        if let id = reply.newPlayID { v.playID = id }
        if let message = reply.stopMessage {
            adminStop(v, message)
        } else if let step = reply.lowerTo {
            changeStep(v, to: step, reply: reply)
        }
    }

    /// Automatic's negotiated step change: the same play again here, still
    /// at Automatic. The saved default quality stays as it is.
    private func changeStep(_ v: Viewing, to step: StreamQuality, reply: ProgressReply) {
        guard asked == .auto, let d = v.decision, !v.loading, v.error == nil,
              v.pendingSwitch == nil, v.awaitingFrameGeneration == nil,
              d.autoStep != step else { return }
        if let generation = reply.switchGeneration {
            guard generation > v.handledGeneration, (apiLevel ?? 0) >= 24 else { return }
            v.handledGeneration = generation
            if let message = reply.lowerMessage { v.stepMessage = message }
            else if step == .original { v.stepMessage = "Connection recovered; playing the original file." }
            else if (StreamQuality.allCases.firstIndex(of: step) ?? 0) > (StreamQuality.allCases.firstIndex(of: d.autoStep ?? .original) ?? 0) { v.stepMessage = "Lowered to \(step.shortLabel) for your connection." }
            else { v.stepMessage = "Raised to \(step.shortLabel) for your connection." }
            reload(v, at: position(v), subtitleID: v.selectedSubtitle, burnIn: burnsIn(v, v.selectedSubtitle), qualitySwitch: reply)
        } else {
            showNote(v, reply.lowerMessage ?? "Lowered to \(step.shortLabel) for your connection.")
            reload(v, at: position(v))
        }
    }

    /// Shows `text` over the video for a few seconds; the next refresh publishes it.
    private func showNote(_ v: Viewing, _ text: String) {
        v.qualityNote = text
        v.qualityNoteUntil = clock.elapsedMs + qualityNoteMs
    }

    // MARK: - Helpers

    private func ensurePlayer() {
        guard media == nil else { return }
        let p = makePlayer()
        media = p
        p.setListener(MediaPlayerListener(
            // Posted, so a callback inside a player call never re-enters the machine.
            changed: { [weak self] in self?.launch { self?.refresh(0) } },
            failed: { [weak self] failure in self?.launch { self?.onPlayerError(failure) } },
            time: { [weak self] in self?.updateCues() },
            firstFrame: { [weak self] in
                self?.launch {
                    guard let v = self?.active(), let generation = v.awaitingFrameGeneration else { return }
                    v.awaitingFrameGeneration = nil
                    v.acceptedGeneration = generation
                    self?.refresh(0)
                }
            },
            audioUnmatched: { [weak self] in self?.launch { self?.audioUnmatched() } }
        ))
        player = p.avPlayer
        session = system?.open(SystemCommands(
            play: { [weak self] in self?.play() },
            pause: { [weak self] in self?.pause() },
            togglePlayPause: { [weak self] in self?.togglePlayPause() },
            seekBy: { [weak self] in self?.seek(byMs: $0) },
            seekTo: { [weak self] in self?.seek(toMs: $0) },
            interruption: { [weak self] began, resume in self?.interrupted(began: began, shouldResume: resume) },
            routeLost: { [weak self] in self?.media?.pause() },
            foreground: { [weak self] in self?.foreground() }
        ))
    }

    /// A call pauses; when it ends the system says whether to carry on.
    private func interrupted(began: Bool, shouldResume: Bool) {
        if began {
            pausedByInterruption = media?.playWhenReady == true
            media?.pause()
        } else {
            if shouldResume && pausedByInterruption { media?.play() }
            pausedByInterruption = false
        }
    }

    private func foreground() {
        guard backgrounded else { return }
        backgrounded = false
        refresh(0)
    }

    /// The server's API level, for what it may be asked; nil when not known.
    private var apiLevel: Int? { servers?.current?.server.apiLevel }

    /// The quality to ask for: the viewer's, with Automatic as Original on a server that cannot pick the step.
    /// A growing recording's length: what the server had recorded when it decided,
    /// plus the time since on this device's monotonic clock (never the device's
    /// date against the server's), up to the planned end. Once it stops growing, fixed.
    private func duration(_ v: Viewing, _ d: PlaybackDecision) -> Int {
        guard let growing = d.growing else { return d.durationMs }
        if let fixed = v.grownMs { return fixed }
        return min(d.durationMs + max(clock.elapsedMs - v.decidedAtMs, 0), growingEnd(d, growing))
    }

    /// The planned length, which a recording can't grow past.
    private func growingEnd(_ d: PlaybackDecision, _ growing: GrowingRecording) -> Int { max(growing.plannedDurationMs, d.durationMs) }

    /// Still recording: not past its planned end, and the stream hasn't ended.
    private func stillGrowing(_ v: Viewing, _ d: PlaybackDecision) -> GrowingRecording? {
        guard let growing = d.growing, v.grownMs == nil, duration(v, d) < growingEnd(d, growing) else { return nil }
        return growing
    }

    private var asked: StreamQuality { quality.playable(apiLevel: apiLevel) }

    /// The play has a stream that is playing or about to, which a reload leaves going.
    private func playsOn(_ v: Viewing) -> Bool {
        guard v.decision != nil, let phase = media?.phase else { return false }
        return phase == .ready || phase == .buffering
    }

    /// The file position now: where a load will start, else the player's time mapped to the file.
    private func position(_ v: Viewing) -> Int {
        guard let d = v.decision else { return v.pendingPositionMs ?? v.request.startMs ?? 0 }
        let raw = v.pendingPositionMs ?? media.map { v.streamClock.filePosition($0.positionMs) } ?? 0
        return min(max(raw, 0), duration(v, d))
    }

    private func endReplacedSession(_ old: PlaybackDecision, _ new: PlaybackDecision) {
        guard let id = old.sessionID, id != new.sessionID else { return }
        send(.endSession(id))
    }

    private func burnsIn(_ v: Viewing, _ subtitleID: String?) -> Bool {
        guard let d = v.decision, let subtitleID, let track = d.subtitles.first(where: { $0.option.id == subtitleID }) else { return false }
        return isImage(track) || d.burnInSubtitleID == subtitleID
    }

    private func isImage(_ track: DecisionSubtitle) -> Bool { track.option.burnInOnly || track.format == "image" }

    private func attempt<T>(_ what: String, _ body: () async throws -> T) async -> T? {
        do {
            return try await body()
        } catch is CancellationError {
            return nil
        } catch {
            log.warning("Could not \(what, privacy: .public): \(error.localizedDescription, privacy: .public)")
            return nil
        }
    }

    /// Starts work on the main actor and keeps track of it until it ends, so tests can wait for quiet.
    @discardableResult
    private func launch(_ work: @escaping @MainActor () async -> Void) -> Task<Void, Never> {
        taskCount += 1
        let id = taskCount
        let task = Task { @MainActor [weak self] in
            await work()
            self?.inFlight[id] = nil
        }
        inFlight[id] = task
        return task
    }

    /// Waits until nothing this controller started is still running (tests drive it with virtual time).
    func settle() async {
        while let task = inFlight.values.first {
            await task.value
        }
    }
}

private extension ProgressReport {
    /// Where the play is, which a newer report of the same viewing says better.
    var isProgress: Bool { state == .playing || state == .paused || state == .buffering }
}
