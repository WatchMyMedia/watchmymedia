import Foundation
import Observation
import WMMModel

/// What covers the video on Apple TV besides the system's transport bar and
/// info panel, most important first: the can't-play state, an admin's stop
/// message, Still watching, the Next Episode card (TvPlayerStopped,
/// TvPlayerStillWatching and TvPlayerNext.dc.html).
public enum TVPlayerOverlay: Hashable, Sendable {
    /// Can't play this, with Try again and Back. The video is gone.
    case failed(String)
    /// The admin's message, with Back. The video is gone.
    case stopped(String)
    /// Are you still watching? The video stays behind it but takes no presses.
    case stillWatching
    /// The Next Episode card in the corner, while the transport bar is hidden.
    case upNext(UpNextPrompt)
}

/// A button the system shows: Skip Intro and the other skips over the video
/// (`contextualActions`), and Next Episode in the transport bar
/// (`transportBarCustomMenuItems`) (TvPlayerControls.dc.html).
public struct TVPlayerAction: Hashable, Sendable, Identifiable {
    public enum Kind: String, Hashable, Sendable { case skip, nextEpisode, goToNow }

    public var kind: Kind
    /// "Skip Intro", "Skip Intro in 2" while auto-delay counts down, "Next Episode".
    public var title: String

    public init(kind: Kind, title: String) {
        self.kind = kind
        self.title = title
    }

    public var id: Kind { kind }

    /// The Skip button as an action: in auto-delay mode the seconds until it skips by itself.
    public static func skip(_ prompt: SkipPrompt) -> TVPlayerAction {
        guard let progress = prompt.autoProgress else { return TVPlayerAction(kind: .skip, title: prompt.label) }
        let left = max(Int((Double(skipAutoDelayMs) * (1 - progress) / 1000).rounded(.up)), 1)
        return TVPlayerAction(kind: .skip, title: "\(prompt.label) in \(left)")
    }

    public static let nextEpisode = TVPlayerAction(kind: .nextEpisode, title: "Next Episode")
}

/// The Apple TV player screen's state around a ``PlaybackController``. The
/// screen is the system's `AVPlayerViewController` (PLAN-IOS.md §11.2): its
/// transport bar seeks and scrubs, its info panel holds the Audio, Subtitles
/// and Quality tabs, Skip is its contextual action and Next Episode a button
/// of its transport bar, and the prompts are overlays. This decides which of
/// those show, passes on what the viewer does, and gives Menu its order: the
/// system closes its info panel first; then Menu answers Still watching, puts
/// the Next Episode card away (Watch credits), and otherwise leaves, as
/// android/tv/.../PlayerKeys.kt does. Android hides its controls before
/// leaving; tvOS 27's AVKit passes Menu on while its transport bar is up and
/// has no call to hide the bar, so here Menu leaves with the bar up, as AVKit
/// itself does.
///
/// The info panel leaves the video playing, as the system's own Info tab
/// does: tvOS gives no dependable sign of the panel opening and closing
/// across its versions, so it cannot pause and resume as the phone's picker does.
///
/// The system's controls see the remote, not the app, so the presses it can
/// see stand for the rest: the transport bar coming up, a seek on it, a pick
/// in the info panel, and the app's own buttons each count as input for
/// Still watching.
@MainActor
@Observable
public final class TVPlayerModel {
    public let controller: any PlaybackController
    /// What plays now: the request the screen opened with, or the next episode once it started in place.
    public private(set) var request: PlayRequest
    /// The system's transport bar is up.
    public private(set) var transportBarVisible = false
    /// When the admin's stop message appeared, and where the video was then, for its small print.
    public private(set) var stoppedAt: Date?
    public private(set) var stoppedAtMs: Int?

    @ObservationIgnored private let onExit: @MainActor () -> Void
    @ObservationIgnored private var closedInBackground = false
    @ObservationIgnored private var finished = false
    @ObservationIgnored private var disposed = false
    @ObservationIgnored private var eventsTask: Task<Void, Never>?

    public init(controller: any PlaybackController, request: PlayRequest, onExit: @escaping @MainActor () -> Void) {
        self.controller = controller
        self.request = request
        self.onExit = onExit
    }

    public var state: PlayerState { controller.state }

    // MARK: - Lifecycle

    /// Starts playing and listening to the controller.
    public func start() {
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
        eventsTask?.cancel()
        controller.stop()
        controller.release()
    }

    /// The viewer leaves (Back on the stopped and can't-play states, Stop, Menu).
    public func close() {
        controller.noteUserInput()
        finish()
    }

    /// Try again after the can't-play state: the same play, from the start of it.
    public func retry() {
        controller.noteUserInput()
        controller.start(request)
    }

    /// The app went to the background (the TV button): the video pauses and the server hears "closed".
    public func enteredBackground() {
        guard !closedInBackground, !disposed else { return }
        closedInBackground = true
        controller.pause()
        controller.close()
    }

    /// The app is in front again; the video stays paused until the viewer plays.
    public func becameActive() {
        closedInBackground = false
    }

    // MARK: - What shows

    public var overlay: TVPlayerOverlay? {
        let s = state
        if let error = s.error { return .failed(error) }
        if let message = s.stopMessage { return .stopped(message) }
        if s.askStillWatching { return .stillWatching }
        // The card waits while the system's controls are up; the Next Episode action stands in for it.
        if let upNext = s.upNext, !transportBarVisible { return .upNext(upNext) }
        return nil
    }

    /// The video is on screen: not once the play failed or was stopped.
    public var showsVideo: Bool {
        switch overlay {
        case .failed?, .stopped?: false
        default: true
        }
    }

    /// The video and its system controls take the remote: not while a prompt asks something.
    public var videoTakesInput: Bool {
        switch overlay {
        case nil, .upNext?: true
        default: false
        }
    }

    /// The system's contextual actions: the Skip button while a segment
    /// plays. tvOS shows them while the transport bar is hidden, with focus,
    /// so Select skips.
    public var contextualActions: [TVPlayerAction] {
        guard overlay == nil, let skip = state.skip else { return [] }
        return [.skip(skip)]
    }

    /// The transport bar's own buttons: Next Episode, for an episode with one
    /// after it. Not a contextual action, which would take Select from play
    /// and pause for as long as it showed.
    public var transportBarActions: [TVPlayerAction] {
        guard videoTakesInput else { return [] }
        if state.showsGoToNow { return [.init(kind: .goToNow, title: "Go to now")] }
        return state.nextEpisode != nil ? [.nextEpisode] : []
    }

    // MARK: - The system's controls

    /// The transport bar came up or went away. Coming up means a press on the remote.
    public func transportBarChanged(visible: Bool) {
        if visible { controller.noteUserInput() }
        transportBarVisible = visible
    }

    /// The viewer scrubbed or skipped on the transport bar, which moves the player to `playerMs` itself.
    public func userSeeked(toPlayerMs playerMs: Int) {
        controller.noteSeek(playerMs: playerMs)
    }

    // MARK: - Buttons and the remote

    public func perform(_ kind: TVPlayerAction.Kind) {
        switch kind {
        case .skip:
            controller.noteUserInput()
            controller.skip()
        case .nextEpisode:
            playNext()
        case .goToNow:
            controller.seek(toMs: max(state.durationMs - 3000, 0))
        }
    }

    public func playNext() {
        controller.noteUserInput()
        controller.playNext()
    }

    public func watchCredits() {
        controller.noteUserInput()
        controller.watchCredits()
    }

    public func answerStillWatching(keepWatching: Bool) {
        controller.noteUserInput()
        controller.answerStillWatching(keepWatching: keepWatching)
    }

    /// Play/Pause while a prompt has focus (the system's controls take it otherwise).
    public func playPause() {
        switch overlay {
        case .stillWatching?: answerStillWatching(keepWatching: true)
        case .failed?, .stopped?: break
        default:
            controller.noteUserInput()
            controller.togglePlayPause()
        }
    }

    /// Menu, once the system has closed its info panel: answers Still
    /// watching (any press does), puts the Next Episode card away, or leaves.
    public func back() {
        switch overlay {
        case .stillWatching?: answerStillWatching(keepWatching: true)
        case .upNext?: watchCredits()
        default: close()
        }
    }

    // MARK: - The info panel

    /// Picking applies at once; the panel stays open until the viewer closes it.
    public func pickAudio(_ index: Int) {
        controller.noteUserInput()
        controller.selectAudio(index)
    }

    public func pickSubtitle(_ id: String?) {
        controller.noteUserInput()
        controller.selectSubtitle(id)
    }

    /// Saved as this device's default too.
    public func pickQuality(_ quality: StreamQuality) {
        controller.noteUserInput()
        controller.selectQuality(quality)
    }

    /// Downloads an online result and turns it on.
    public func download(_ resultID: String) {
        controller.noteUserInput()
        controller.downloadSubtitle(resultID)
    }

    // MARK: - Private

    private func finish() {
        guard !finished else { return }
        finished = true
        onExit()
    }

    private func handle(_ event: PlayerEvent) {
        switch event {
        case .exit:
            // An admin's stop shows its message first; Back leaves.
            if state.stopMessage == nil { finish() }
        case .startedNext(let fileID):
            request = PlayRequest(fileID: fileID, title: state.title, subtitle: state.subtitle, backdropPath: state.backdropPath)
        }
    }

    /// Notes when the admin's stop message came, and where the video was.
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
        let s = state
        if s.stopMessage != nil, stoppedAt == nil {
            stoppedAt = .now
            stoppedAtMs = s.positionMs
        }
        if s.stopMessage == nil {
            stoppedAt = nil
            stoppedAtMs = nil
        }
    }
}
