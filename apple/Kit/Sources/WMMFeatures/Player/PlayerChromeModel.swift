import CoreGraphics
import Foundation
import Observation
import WMMModel

/// What covers the video besides the controls, most important first: the
/// can't-play state, an admin's stop message, Still watching, the picker.
public enum PlayerOverlay: Hashable, Sendable {
    case failed(String)
    case stopped(String)
    case stillWatching
    case picker(PickerTab)
}

/// What changes as the video plays, about ten times a second: the position,
/// the length (which grows while a recording records), how far is buffered,
/// the subtitle cues on screen, Skip's auto-skip fill and the Next Episode
/// countdown. Only the views that draw these read it (the scrubber, the
/// times, the small progress line, the cues, Skip and the card's button), so
/// a tick redraws them and not the whole player (PLAN-APPLE-REVIEW.md §4.3.3).
/// Each value is set only when it changes.
@MainActor
@Observable
public final class PlayerClock {
    public fileprivate(set) var positionMs = 0
    public fileprivate(set) var durationMs = 0
    public fileprivate(set) var bufferedMs = 0
    public fileprivate(set) var cues: [SubtitleCue] = []
    /// Skip's fill in auto-delay mode, 0–1; nil otherwise.
    public fileprivate(set) var skipProgress: Double?
    /// The Next Episode countdown; nil when autoplay is off or the card is not up.
    public fileprivate(set) var upNextCountdownMs: Int?

    public init() {}

    fileprivate func update(from s: PlayerState) {
        if positionMs != s.positionMs { positionMs = s.positionMs }
        if durationMs != s.durationMs { durationMs = s.durationMs }
        if bufferedMs != s.bufferedMs { bufferedMs = s.bufferedMs }
        if cues != s.cues { cues = s.cues }
        if skipProgress != s.skip?.autoProgress { skipProgress = s.skip?.autoProgress }
        if upNextCountdownMs != s.upNext?.countdownMs { upNextCountdownMs = s.upNext?.countdownMs }
    }
}

/// The player screen's own state around a ``PlaybackController``: whether the
/// controls show (they hide 3 s after the last touch while the video plays),
/// taps and double taps on the video, the scrubber's preview while dragging
/// (the time, and the seek thumbnail where the server has made them), which
/// overlay is up, and leaving. Every touch reaches the controller as
/// ``PlaybackController/noteUserInput()``, which resets Still watching.
/// The controller does the playing (PLAN-CLIENTS.md §2.3); this only decides
/// what the screen shows and passes on what the viewer does, as
/// android/mobile/.../PlayerScreen.kt does.
@MainActor
@Observable
public final class PlayerChromeModel {
    /// The controls hide this long after the last touch while playing.
    public static let hideDelayMs = 3_000
    /// A second tap within this long makes a double tap.
    public static let doubleTapMs = 300
    /// How long the "+30 s" stays after the last tap of a run.
    public static let rippleMs = DoubleTapSeek.windowMs + 300
    /// After a seek, the thumb stays at the target this long, or until the player's position catches up.
    public static let holdMs = 1_000

    public let controller: any PlaybackController
    /// The controller's state without what ticks (see ``PlayerClock``): it
    /// changes only when something else does, so the screen that reads it is
    /// not rebuilt ten times a second. It follows the controller a moment
    /// after each change. Its `positionMs`, `bufferedMs` and `cues` are zero
    /// and empty, Skip's `autoProgress` nil and the card's `countdownMs` its
    /// total; a recording that grows has a `durationMs` of zero. Read those
    /// from ``clock``.
    public private(set) var state = PlayerState()
    /// What ticks.
    public let clock = PlayerClock()
    /// What plays now: the request the screen opened with, or the next episode once it started in place.
    public private(set) var request: PlayRequest
    public private(set) var controlsVisible = true
    public private(set) var picker: PickerTab?
    /// The position under the scrubber's thumb while the viewer drags it.
    public private(set) var scrubMs: Int?
    /// The seek thumbnail at `scrubMs`, kept until the next one is cut; nil
    /// before the first has loaded (``showsScrubTile``).
    public private(set) var scrubTile: CGImage?
    /// A sheet failed to load during this scrub: the time shows alone.
    private var scrubTileFailed = false
    /// The double-tap run to show on its side ("+30 s"), while it lasts.
    public private(set) var ripple: SeekBurst?
    public private(set) var pictureInPictureActive = false
    /// When the admin's stop message appeared, for its small print.
    public private(set) var stoppedAt: Date?
    /// Holds the controls up (VoiceOver is running: there is nothing to tap blindly to bring them back).
    public var keepsControlsUp = false {
        didSet {
            guard keepsControlsUp != oldValue else { return }
            if keepsControlsUp { showControls() } else { refreshAutoHide() }
        }
    }
    /// A seek's target, held under the thumb until the player gets there.
    private var heldMs: Int?

    @ObservationIgnored private let thumbnails: SeekThumbnails?
    @ObservationIgnored private var tileIndex: Int?
    @ObservationIgnored private var tileTask: Task<Void, Never>?
    @ObservationIgnored private let onExit: @MainActor () -> Void
    @ObservationIgnored private let now: @MainActor () -> Int
    @ObservationIgnored private let sleep: @MainActor (Duration) async throws -> Void
    @ObservationIgnored private var lastInputMs = 0
    @ObservationIgnored private var autoHideSinceMs = 0
    @ObservationIgnored private var autoHid = false
    @ObservationIgnored private var lastStatus: PlayerStatus?
    @ObservationIgnored private var lastTrickplay: Trickplay?
    @ObservationIgnored private var seeker = DoubleTapSeek()
    @ObservationIgnored private var pendingTapMs: Int?
    /// The pending tap showed the controls itself, so its second tap doesn't toggle them.
    @ObservationIgnored private var pendingTapShowed = false
    @ObservationIgnored private var resumeAfterPicker = false
    @ObservationIgnored private var awaitingDownload: String?
    @ObservationIgnored private var closedInBackground = false
    @ObservationIgnored private var finished = false
    @ObservationIgnored private var disposed = false
    @ObservationIgnored private var hideTask: Task<Void, Never>?
    @ObservationIgnored private var tapTask: Task<Void, Never>?
    @ObservationIgnored private var rippleTask: Task<Void, Never>?
    @ObservationIgnored private var holdTask: Task<Void, Never>?
    @ObservationIgnored private var backgroundTask: Task<Void, Never>?
    @ObservationIgnored private var eventsTask: Task<Void, Never>?

    /// `now` is a monotonic clock in ms and `sleep` waits; tests pass their
    /// own. `thumbnails` cuts the seek thumbnails; nil shows none.
    public init(
        controller: any PlaybackController,
        request: PlayRequest,
        thumbnails: SeekThumbnails? = nil,
        onExit: @escaping @MainActor () -> Void,
        now: @escaping @MainActor () -> Int = { Int(ProcessInfo.processInfo.systemUptime * 1000) },
        sleep: (@MainActor (Duration) async throws -> Void)? = nil
    ) {
        self.controller = controller
        self.request = request
        self.thumbnails = thumbnails
        self.onExit = onExit
        self.now = now
        // Define this closure in the library, rather than copying an async
        // default argument into every caller (swiftlang/swift#92017).
        self.sleep = sleep ?? { try await Task.sleep(for: $0) }
    }

    /// The controller's state now, for decisions (not for views: reading it follows every tick).
    private var live: PlayerState { controller.state }

    /// `s` without what ticks: what ``state`` holds.
    public static func steady(_ s: PlayerState) -> PlayerState {
        var steady = s
        steady.positionMs = 0
        steady.bufferedMs = 0
        steady.cues = []
        if s.growing != nil { steady.durationMs = 0 }
        steady.skip?.autoProgress = nil
        if let upNext = s.upNext, upNext.countdownMs != nil { steady.upNext?.countdownMs = upNext.totalMs }
        return steady
    }

    /// Copies the controller's state into ``clock`` and, when more than what ticks changed, ``state``.
    private func refresh() {
        let s = live
        clock.update(from: s)
        let steady = Self.steady(s)
        if steady != state { state = steady }
    }

    private static func pickerTabs(offline: Bool) -> [PickerTab] { offline ? [.audio, .subtitles] : PickerTab.allCases }

    /// The copy's quality is fixed when downloaded; changing a streaming
    /// preference cannot change its bytes or restart it at a new quality.
    public var pickerTabs: [PickerTab] { Self.pickerTabs(offline: state.isOffline) }
    public var pickerTitle: String { state.isOffline ? "Audio and subtitles" : "Audio, subtitles and quality" }

    // MARK: - Lifecycle

    /// Starts playing and listening to the controller.
    public func start() {
        lastInputMs = now()
        controller.start(request)
        let events = controller.events
        eventsTask = Task { [weak self] in
            for await event in events { self?.handle(event) }
        }
        observeState()
    }

    /// The screen went away: reports "stopped" and lets go of the player. Safe to call twice.
    public func dispose() {
        guard !disposed else { return }
        disposed = true
        for task in [hideTask, tapTask, rippleTask, holdTask, eventsTask, backgroundTask, tileTask] { task?.cancel() }
        controller.stop()
        controller.release()
    }

    /// The viewer leaves (Close, Back).
    public func close() {
        input()
        finish()
    }

    /// Try again after the can't-play state: the same play, from the start of it.
    public func retry() {
        input()
        resetSeekInteraction()
        controller.start(request)
    }

    /// How long to wait, in the background, for Picture in Picture to start by itself.
    public static let pictureInPictureGraceMs = 1_500

    /// The app went to the background. AirPlay (`externalPlayback`) and
    /// Picture in Picture keep playing; when Picture in Picture may still
    /// start by itself it gets a moment to. Otherwise the video pauses and
    /// the server hears "closed".
    public func enteredBackground(externalPlayback: Bool = false, pictureInPictureMayStart: Bool = false) {
        guard !externalPlayback, !pictureInPictureActive, !closedInBackground, !disposed else { return }
        backgroundTask?.cancel()
        guard pictureInPictureMayStart, live.status == .playing || live.status == .buffering else { return closeInBackground() }
        let sleep = sleep
        backgroundTask = Task { [weak self] in
            do { try await sleep(.milliseconds(Self.pictureInPictureGraceMs)) } catch { return }
            guard let self, !Task.isCancelled, !self.pictureInPictureActive else { return }
            self.closeInBackground()
        }
    }

    private func closeInBackground() {
        guard !closedInBackground, !disposed else { return }
        closedInBackground = true
        controller.pause()
        controller.close()
    }

    /// The app is in front again. A video paused on the way out stays paused,
    /// with the controls up, as on Android; the viewer carries on with play.
    public func becameActive() {
        backgroundTask?.cancel()
        backgroundTask = nil
        guard closedInBackground else { return }
        closedInBackground = false
        showControls()
    }

    /// Picture in Picture started or ended. Ending it while the app is in the
    /// background (the viewer closed the small window) pauses, as leaving the app does.
    public func pictureInPictureChanged(active: Bool, appActive: Bool) {
        pictureInPictureActive = active
        if active {
            closePicker()
            controlsVisible = false
        } else if appActive {
            showControls()
        } else {
            closeInBackground()
        }
        refreshAutoHide()
    }

    // MARK: - What shows

    public var overlay: PlayerOverlay? {
        let s = state
        if let error = s.error { return .failed(error) }
        if let message = s.stopMessage { return .stopped(message) }
        if s.askStillWatching { return .stillWatching }
        return picker.map(PlayerOverlay.picker)
    }

    public var showsControls: Bool { controlsVisible && overlay == nil && !pictureInPictureActive }

    /// The Next Episode card shows while the controls are hidden.
    public var showsUpNext: Bool { state.upNext != nil && !controlsVisible && overlay == nil && !pictureInPictureActive }

    /// Skip makes way for the seek thumbnail.
    public var showsSkip: Bool { state.skip != nil && overlay == nil && !showsUpNext && !pictureInPictureActive && !showsScrubTile }

    /// A drag on the scrubber shows the seek thumbnail over the thumb, and
    /// the middle buttons and Skip make way for it; without thumbnails, or
    /// once a sheet fails, the time shows alone (PLAN-TRICKPLAY.md §7.1).
    public var showsScrubTile: Bool { scrubMs != nil && state.trickplay != nil && thumbnails != nil && !scrubTileFailed }

    /// The seek thumbnail's height for its width.
    public var scrubTileAspect: Double {
        guard let t = state.trickplay else { return 9.0 / 16 }
        return Double(t.height) / Double(t.width)
    }

    /// The small progress line beside the Next Episode card and under Still watching.
    public var showsMiniProgress: Bool { showsUpNext || overlay == .stillWatching }

    /// Loading or buffering: a spinner on its own while the controls are hidden, around play/pause while they show.
    public var isWaiting: Bool { state.status == .loading || state.status == .buffering }

    public var isPlaying: Bool { state.status == .playing || state.status == .buffering }

    /// Where the scrubber's thumb and the elapsed time are: the drag, a seek's target until the player gets there, or the player.
    public var shownPositionMs: Int {
        if let scrubMs { return scrubMs }
        let position = clock.positionMs
        if let heldMs, abs(heldMs - position) >= 1_500 { return heldMs }
        return position
    }

    public var bands: [ScrubberBand] { scrubberBands(state.segments, durationMs: state.timelineDurationMs) }

    /// When the controls hide by themselves (monotonic ms), or nil while they stay.
    public var hideAtMs: Int? {
        controlsVisible && autoHides ? max(lastInputMs, autoHideSinceMs) + Self.hideDelayMs : nil
    }

    /// They hide by themselves only while playing or buffering, not while scrubbing or with the picker open.
    public static func autoHides(status: PlayerStatus, scrubbing: Bool, pickerOpen: Bool) -> Bool {
        (status == .playing || status == .buffering) && !scrubbing && !pickerOpen
    }

    private var autoHides: Bool {
        Self.autoHides(status: live.status, scrubbing: scrubMs != nil, pickerOpen: picker != nil) && !pictureInPictureActive && !keepsControlsUp
    }

    // MARK: - Touches

    /// A tap on the video at `x` across `width`: shows hidden controls at
    /// once, and hides shown ones once it is clear no second tap follows; a
    /// double tap on a side seeks 10 s, and further taps there keep seeking
    /// (see ``DoubleTapSeek``). Controls the first tap showed stay up through
    /// the seek.
    public func tap(x: Double, width: Double) {
        input()
        let t = now()
        if let delta = seeker.onTap(x: x, width: width, nowMs: t) {
            cancelPendingTap()
            seekByTaps(delta)
            return
        }
        if let first = pendingTapMs, t - first <= Self.doubleTapMs {
            let showed = pendingTapShowed
            cancelPendingTap()
            if let delta = seeker.onDoubleTap(x: x, width: width, nowMs: t) {
                seekByTaps(delta)
            } else if !showed {
                toggleControls()
            }
            return
        }
        cancelPendingTap()
        pendingTapMs = t
        if !controlsVisible {
            // Nothing a second tap does takes them away again: show them now.
            pendingTapShowed = true
            toggleControls()
            return
        }
        let sleep = sleep
        tapTask = Task { [weak self] in
            do { try await sleep(.milliseconds(Self.doubleTapMs)) } catch { return }
            guard let self, !Task.isCancelled, self.pendingTapMs == t else { return }
            self.pendingTapMs = nil
            self.toggleControls()
        }
    }

    public func toggleControls() {
        controlsVisible.toggle()
        lastInputMs = now()
        refreshAutoHide()
        if controlsVisible { prefetchTiles(scrubbing: false) }
    }

    public func showControls() {
        controlsVisible = true
        lastInputMs = now()
        refreshAutoHide()
        prefetchTiles(scrubbing: false)
    }

    public func togglePlayPause() {
        input()
        controller.togglePlayPause()
    }

    public func seek(byMs delta: Int) {
        input()
        hold(live.positionMs + delta)
        controller.seek(byMs: delta)
    }

    public func seek(toMs position: Int) {
        input()
        hold(position)
        controller.seek(toMs: position)
    }

    /// The viewer drags the scrubber (or holds it) at `x` across `width`: previews that position.
    public func scrub(x: Double, width: Double) {
        let starting = scrubMs == nil
        let s = live
        scrubMs = min(position(atX: x, width: width, durationMs: s.timelineDurationMs), s.durationMs)
        input()
        if starting {
            refreshAutoHide()
            thumbnails?.retryFailed()
            scrubTileFailed = false
            prefetchTiles(scrubbing: true)
        }
        updateScrubTile()
    }

    /// The drag ended: seeks to where the thumb is.
    public func endScrub() {
        guard let target = scrubMs else { return }
        scrubMs = nil
        clearScrubTile()
        seek(toMs: target)
        refreshAutoHide()
    }

    public func cancelScrub() {
        scrubMs = nil
        clearScrubTile()
        refreshAutoHide()
    }

    /// Cuts the tile under the thumb when the drag reaches another one; the
    /// last stays up until it is ready.
    private func updateScrubTile() {
        guard let thumbnails, let t = live.trickplay, let ms = scrubMs, !scrubTileFailed else { return }
        let index = t.tile(atMs: ms).index
        guard index != tileIndex else { return }
        tileIndex = index
        tileTask?.cancel()
        tileTask = Task { [weak self] in
            let image = await thumbnails.tile(t, atMs: ms)
            guard let self, !Task.isCancelled, self.scrubMs != nil, self.tileIndex == index else { return }
            if let image { self.scrubTile = image } else { self.scrubTileFailed = true }
        }
    }

    private func clearScrubTile() {
        tileTask?.cancel()
        tileTask = nil
        tileIndex = nil
        scrubTile = nil
        scrubTileFailed = false
    }

    /// The sheet under the playhead, when the controls appear; the sheets
    /// either side of it when a scrub starts (its own comes with its tile).
    private func prefetchTiles(scrubbing: Bool) {
        guard let thumbnails, let t = live.trickplay else { return }
        let sheet = t.tile(atMs: scrubMs ?? live.positionMs).sheet
        if scrubbing {
            thumbnails.prefetch(t, sheet: sheet - 1)
            thumbnails.prefetch(t, sheet: sheet + 1)
        } else {
            thumbnails.prefetch(t, sheet: sheet)
        }
    }

    /// Switches episodes without replacing the player or its Picture in Picture coordinator.
    public func play(_ request: PlayRequest) {
        input()
        resumeAfterPicker = false
        closePicker()
        self.request = request
        resetSeekInteraction()
        controller.start(request)
        showControls()
    }

    // MARK: - Prompts

    public func skip() {
        input()
        controller.skip()
    }

    public func playNext() {
        input()
        controller.playNext()
    }

    public func watchCredits() {
        input()
        controller.watchCredits()
    }

    public func answerStillWatching(keepWatching: Bool) {
        input()
        controller.answerStillWatching(keepWatching: keepWatching)
    }

    // MARK: - Picker

    /// The compact sheet pauses playback; tablet side panels pass `false` to keep it playing.
    public func openPicker(_ tab: PickerTab, pausesPlayback: Bool = true) {
        guard Self.pickerTabs(offline: live.isOffline).contains(tab) else { return }
        input()
        guard picker == nil else { return selectTab(tab) }
        resumeAfterPicker = pausesPlayback && live.status == .playing
        if resumeAfterPicker { controller.pause() }
        picker = tab
        refreshAutoHide()
    }

    public func selectTab(_ tab: PickerTab) {
        guard Self.pickerTabs(offline: live.isOffline).contains(tab) else { return }
        input()
        if picker != nil { picker = tab }
    }

    public func closePicker() {
        guard picker != nil else { return }
        picker = nil
        awaitingDownload = nil
        if resumeAfterPicker { controller.play() }
        resumeAfterPicker = false
        showControls()
    }

    /// Picking applies at once and closes the picker.
    public func pickAudio(_ index: Int) {
        input()
        controller.selectAudio(index)
        closePicker()
    }

    public func pickSubtitle(_ id: String?) {
        input()
        controller.selectSubtitle(id)
        closePicker()
    }

    public func pickQuality(_ quality: StreamQuality) {
        guard !live.isOffline else { return }
        input()
        controller.selectQuality(quality)
        closePicker()
    }

    /// Downloads an online result; the picker stays open while it downloads and closes once it is on.
    public func download(_ resultID: String) {
        input()
        awaitingDownload = resultID
        controller.downloadSubtitle(resultID)
        downloadChanged()
    }

    // MARK: - Private

    private func input() {
        controller.noteUserInput()
        lastInputMs = now()
        scheduleHide()
    }

    private func finish() {
        guard !finished else { return }
        finished = true
        onExit()
    }

    private func handle(_ event: PlayerEvent) {
        switch event {
        case .exit:
            // An admin's stop shows its message first; Back leaves.
            if live.stopMessage == nil { finish() }
        case .startedNext(let fileID):
            let state = live
            request = PlayRequest(
                fileID: fileID, title: state.title, subtitle: state.subtitle,
                backdropPath: state.backdropPath, stillPath: state.stillPath, posterPath: state.posterPath
            )
            resumeAfterPicker = false
            resetSeekInteraction()
            closePicker()
            showControls()
        }
    }

    /// Follows the controller's state: the status decides whether the
    /// controls may hide, the stop message gets its time, and a finished
    /// download closes the picker.
    private func observeState() {
        guard !disposed else { return }
        withObservationTracking {
            _ = controller.state
        } onChange: { [weak self] in
            Task { @MainActor [weak self] in
                guard let self else { return }
                self.stateChanged()
                self.observeState()
            }
        }
        stateChanged()
    }

    private func stateChanged() {
        refresh()
        let s = live
        if s.status != lastStatus {
            lastStatus = s.status
            refreshAutoHide()
        }
        if s.stopMessage != nil, stoppedAt == nil { stoppedAt = .now }
        if s.stopMessage == nil { stoppedAt = nil }
        // The controls are up when a play starts: its first sheet comes with the decision.
        if s.trickplay != lastTrickplay {
            lastTrickplay = s.trickplay
            if controlsVisible { prefetchTiles(scrubbing: false) }
        }
        downloadChanged()
    }

    private func downloadChanged() {
        guard let id = awaitingDownload, let online = live.online, online.downloading == nil else { return }
        awaitingDownload = nil
        let done = online.results.first { $0.id == id }?.trackID
        if done != nil, online.error == nil { closePicker() }
    }

    private func refreshAutoHide() {
        let allowed = autoHides
        if allowed, !autoHid { autoHideSinceMs = now() }
        autoHid = allowed
        scheduleHide()
    }

    private func scheduleHide() {
        hideTask?.cancel()
        guard let at = hideAtMs else { return }
        let wait = at - now()
        let sleep = sleep
        hideTask = Task { [weak self] in
            if wait > 0 {
                do { try await sleep(.milliseconds(wait)) } catch { return }
            }
            guard let self, !Task.isCancelled, self.hideAtMs == at else { return }
            self.controlsVisible = false
        }
    }

    /// Gestures and their delayed feedback belong to one file, never its successor.
    private func resetSeekInteraction() {
        cancelPendingTap()
        seeker.reset()
        rippleTask?.cancel()
        rippleTask = nil
        ripple = nil
        holdTask?.cancel()
        holdTask = nil
        heldMs = nil
        scrubMs = nil
        clearScrubTile()
    }

    private func cancelPendingTap() {
        pendingTapMs = nil
        pendingTapShowed = false
        tapTask?.cancel()
        tapTask = nil
    }

    private func seekByTaps(_ delta: Int) {
        hold(live.positionMs + delta)
        controller.seek(byMs: delta)
        ripple = seeker.burst
        rippleTask?.cancel()
        let sleep = sleep
        rippleTask = Task { [weak self] in
            do { try await sleep(.milliseconds(Self.rippleMs)) } catch { return }
            guard let self, !Task.isCancelled else { return }
            self.ripple = nil
        }
    }

    private func hold(_ target: Int) {
        heldMs = min(max(target, 0), max(live.durationMs, 0))
        holdTask?.cancel()
        let sleep = sleep
        holdTask = Task { [weak self] in
            do { try await sleep(.milliseconds(Self.holdMs)) } catch { return }
            guard let self, !Task.isCancelled else { return }
            self.heldMs = nil
        }
    }
}
