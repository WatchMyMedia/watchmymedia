import Foundation
import Observation
import WMMModel

/// One play on a ``PlaybackController`` from start to finish, as every player
/// screen's model needs it (PLAN-APPLE-REVIEW.md §5.2): it starts the
/// controller and listens to it, follows its state, swaps in the next episode
/// when one starts in place, closes the play once when the app goes to the
/// background, and leaves once. ``TVPlayerModel`` and the phone's
/// ``PlayerChromeModel`` are built on it.
///
/// The screen's own model keeps what is only its own: which overlay shows,
/// the controls, the remote's keys, how long picture in picture gets.
@MainActor
@Observable
public final class PlayerSession {
    public let controller: any PlaybackController
    /// What plays now: the request the screen opened with, or the next episode once it started in place.
    public private(set) var request: PlayRequest

    /// ``dispose()`` ran: the player is let go of and nothing more is followed.
    @ObservationIgnored public private(set) var isDisposed = false
    /// The play was closed when the app went to the background and not opened again.
    @ObservationIgnored public private(set) var isClosedInBackground = false

    @ObservationIgnored private let onExit: @MainActor () -> Void
    @ObservationIgnored private var stateChanged: @MainActor () -> Void = {}
    @ObservationIgnored private var startedNext: @MainActor () -> Void = {}
    @ObservationIgnored private var finished = false
    @ObservationIgnored private var started = false
    @ObservationIgnored private var eventsTask: Task<Void, Never>?

    public init(controller: any PlaybackController, request: PlayRequest, onExit: @escaping @MainActor () -> Void) {
        self.controller = controller
        self.request = request
        self.onExit = onExit
    }

    /// Starts playing and listening to the controller. `stateChanged` runs
    /// now and after every change to the controller's state; `startedNext`
    /// after the next episode started in place and became ``request``. Once.
    public func start(stateChanged: @escaping @MainActor () -> Void = {}, startedNext: @escaping @MainActor () -> Void = {}) {
        guard !started, !isDisposed else { return }
        started = true
        self.stateChanged = stateChanged
        self.startedNext = startedNext
        controller.start(request)
        let events = controller.events
        eventsTask = Task { [weak self] in
            for await event in events { self?.handle(event) }
        }
        follow()
    }

    /// The same play again, from the start of it (Try again).
    public func restart() {
        controller.start(request)
    }

    /// Plays `request` on the same controller instead, and it becomes
    /// ``request`` (the phone's episodes panel switches episodes this way,
    /// keeping its Picture in Picture).
    public func play(_ request: PlayRequest) {
        self.request = request
        controller.start(request)
    }

    /// The screen went away: reports "stopped" and lets go of the player. Safe to call twice.
    public func dispose() {
        guard !isDisposed else { return }
        isDisposed = true
        eventsTask?.cancel()
        controller.stop()
        controller.release()
    }

    /// The viewer leaves, or the play ended: the screen's `onExit`, once.
    public func finish() {
        guard !finished else { return }
        finished = true
        onExit()
    }

    /// The app went to the background: the video pauses and the server hears
    /// "closed", once until ``reopen()``. False when nothing was done.
    @discardableResult
    public func closeInBackground() -> Bool {
        guard !isClosedInBackground, !isDisposed else { return false }
        isClosedInBackground = true
        controller.pause()
        controller.close()
        return true
    }

    /// The app is in front again; the video stays paused until the viewer
    /// plays. True when the play had been closed in the background.
    @discardableResult
    public func reopen() -> Bool {
        guard isClosedInBackground else { return false }
        isClosedInBackground = false
        return true
    }

    /// The request for the episode that started after `request`'s, named from
    /// the controller's state. The still and poster too, so a retry keeps the
    /// episode's Now Playing picture.
    public static func next(fileID: Int, state: PlayerState) -> PlayRequest {
        PlayRequest(
            fileID: fileID, title: state.title, subtitle: state.subtitle,
            backdropPath: state.backdropPath, stillPath: state.stillPath, posterPath: state.posterPath
        )
    }

    private func handle(_ event: PlayerEvent) {
        switch event {
        case .exit:
            // An admin's stop shows its message first; Back leaves.
            if controller.state.stopMessage == nil { finish() }
        case .startedNext(let fileID):
            request = Self.next(fileID: fileID, state: controller.state)
            startedNext()
        }
    }

    /// Calls `stateChanged` now and after each change to the controller's state, until disposed.
    private func follow() {
        guard !isDisposed else { return }
        withObservationTracking {
            _ = controller.state
        } onChange: { [weak self] in
            Task { @MainActor [weak self] in self?.follow() }
        }
        stateChanged()
    }
}
