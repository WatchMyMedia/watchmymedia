import Foundation
import Observation
import WMMModel

/// The iPhone and iPad Live TV player's state around a
/// ``LivePlaybackController``: the channel steps, leaving, the background and
/// Picture in Picture, and whether the controls show. They hide by
/// themselves as the film player's do (``ControlsAutoHide``, 3 s after the
/// last input), while the channel plays or buffers, with Channels and the
/// Quality menu closed and nothing over the video (PLAN-APPLE-REVIEW.md
/// §3.3.1, §5.3).
@MainActor @Observable
public final class LivePlayerModel {
    public let controller: any LivePlaybackController
    public var onExit: (@MainActor () -> Void)?
    public var state: LivePlayerState { controller.state }
    /// Whether the controls show, and when they hide by themselves.
    public let controls: ControlsAutoHide
    /// The channel list beside the video (landscape) is open: the controls stay.
    public var channelsOpen = false { didSet { if channelsOpen != oldValue { controls.refresh() } } }
    /// The Quality menu is open: the controls stay, or the menu would lose its button.
    public var qualityMenuOpen = false { didSet { if qualityMenuOpen != oldValue { controls.refresh() } } }
    public private(set) var pictureInPictureActive = false

    /// Whether the controls may hide by themselves: while the channel plays
    /// or buffers, with nothing open over it.
    public static func autoHides(status: LivePlayerStatus, stillWatching: Bool, channelsOpen: Bool, menuOpen: Bool, pictureInPicture: Bool) -> Bool {
        (status == .playing || status == .buffering) && !stillWatching && !channelsOpen && !menuOpen && !pictureInPicture
    }

    public var showsControls: Bool { controls.visible && !pictureInPictureActive }
    public var channels: [LiveChannel] { repository.channels }
    /// Programme listings show in the banner and the channel lists.
    public var listings: Bool { repository.capabilities?.listings == true }
    /// The playing channel as the channel list has it, with its logo, now and next.
    public var listedChannel: LiveChannel? {
        guard listings, let id = state.channel?.id else { return nil }
        return channels.first { $0.id == id }
    }
    public func followSchedule() async { await LiveSchedule.follow(repository) }
    @ObservationIgnored private let repository: any LiveTVRepository
    @ObservationIgnored private var request: LivePlayRequest
    @ObservationIgnored private var started = false
    @ObservationIgnored private var disposed = false
    @ObservationIgnored private var pipPending = false
    @ObservationIgnored private var backgroundTask: Task<Void, Never>?
    @ObservationIgnored private let sleep: @MainActor (Duration) async throws -> Void
    @ObservationIgnored private var lastStatus: LivePlayerStatus?
    @ObservationIgnored private var lastStillWatching = false

    /// `now` is a monotonic clock in ms and `sleep` waits; tests pass their own.
    public init(
        request: LivePlayRequest, repository: any LiveTVRepository, controller: any LivePlaybackController,
        onExit: (@MainActor () -> Void)? = nil,
        now: @escaping @MainActor () -> Int = { Int(ProcessInfo.processInfo.systemUptime * 1000) },
        sleep: (@MainActor (Duration) async throws -> Void)? = nil
    ) {
        self.request = request; self.repository = repository; self.controller = controller; self.onExit = onExit
        let sleep = sleep ?? { try await Task.sleep(for: $0) }
        self.sleep = sleep
        controls = ControlsAutoHide(now: now, sleep: sleep)
        controls.allows = { [weak self] in
            guard let self else { return false }
            let s = self.controller.state
            return Self.autoHides(status: s.status, stillWatching: s.askStillWatching, channelsOpen: self.channelsOpen, menuOpen: self.qualityMenuOpen, pictureInPicture: self.pictureInPictureActive)
        }
    }
    public func start() {
        guard !started, !disposed else { return }
        started = true; controller.start(request)
        controls.noteInput()
        observeState()
    }

    /// A touch on the video: shows hidden controls, hides shown ones.
    public func tap() {
        controller.noteUserInput()
        controls.toggle()
    }

    /// Any other input (a button, a key): resets Still watching and the hide.
    public func input() {
        controller.noteUserInput()
        controls.noteInput()
    }

    public func showControls() { controls.show() }

    public func togglePlayPause() { input(); controller.togglePlayPause() }
    public func goLive() { input(); controller.goLive() }
    public func selectQuality(_ quality: LiveQuality) { input(); controller.selectQuality(quality) }
    public func answerStillWatching(keepWatching: Bool) { input(); controller.answerStillWatching(keepWatching: keepWatching) }
    public func toggleChannels() { input(); channelsOpen.toggle() }

    /// Follows the controller's state: the status and Still watching decide whether the controls may hide.
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
        let s = controller.state
        guard s.status != lastStatus || s.askStillWatching != lastStillWatching else { return }
        let stopped = s.status == .stopped || s.status == .error
        // A stop, an error or Still watching brings the controls back for when it is answered.
        if (stopped && lastStatus != nil && lastStatus != .stopped && lastStatus != .error) || (s.askStillWatching && !lastStillWatching) { controls.show() }
        lastStatus = s.status
        lastStillWatching = s.askStillWatching
        controls.refresh()
    }
    public func switchChannel(_ channel: LiveChannel) {
        guard !disposed, channel.available else { return }
        if channel.id != request.channel.id { lastChannel = request.channel }
        input()
        request = LivePlayRequest(channel: channel, quality: state.quality)
        controller.start(request)
    }
    /// The channel watched before this one, for a remote's Last key
    /// (PLAN-APPLE-REVIEW.md §6.2): set by every switch, from a list, a guide
    /// or Ch− and Ch+. Nil until the first switch.
    public private(set) var lastChannel: LiveChannel?
    /// Back to ``lastChannel``, as the channel list has it now; pressed
    /// again, back to the one before. Nothing when there is none or it
    /// can't be watched now.
    public func switchToLastChannel() {
        guard let last = lastChannel else { return }
        switchChannel(channels.first { $0.id == last.id } ?? last)
    }
    public func previousChannel() { step(-1) }
    public func nextChannel() { step(1) }
    private func step(_ delta: Int) {
        let playable = channels.filter(\.available)
        guard !playable.isEmpty else { return }
        let current = playable.firstIndex(where: { $0.id == state.channel?.id }) ?? 0
        switchChannel(playable[(current + delta + playable.count) % playable.count])
    }
    public func retry() { guard !disposed else { return }; controller.start(LivePlayRequest(channel: request.channel, quality: state.quality)) }
    public func close() { guard !disposed else { return }; dispose(); onExit?() }
    public func dispose() {
        guard !disposed else { return }
        disposed = true; backgroundTask?.cancel(); backgroundTask = nil
        controls.cancel()
        controller.release()
    }
    /// The app went to the background. AirPlay and Picture in Picture keep
    /// the tuner; when Picture in Picture may still start by itself it gets
    /// the film player's moment (``PlayerChromeModel/pictureInPictureGraceMs``).
    /// Otherwise the session stops and its tuner is released.
    public func enteredBackground(externalPlayback: Bool, pictureInPictureMayStart: Bool) {
        guard !disposed, !externalPlayback, !pictureInPictureActive else { return }
        pipPending = pictureInPictureMayStart
        backgroundTask?.cancel()
        if pictureInPictureMayStart {
            let sleep = sleep
            backgroundTask = Task { [weak self] in
                do { try await sleep(.milliseconds(PlayerChromeModel.pictureInPictureGraceMs)) } catch { return }
                guard !Task.isCancelled, let self, !self.pictureInPictureActive else { return }
                self.pipPending = false; self.controller.stop()
            }
        } else { controller.stop() }
    }
    public func becameActive() {
        backgroundTask?.cancel(); backgroundTask = nil; pipPending = false
        // Returning never silently allocates a tuner; the stopped screen offers Play.
        controls.show()
    }
    public func pictureInPictureChanged(active: Bool, appActive: Bool) {
        guard !disposed else { return }
        pictureInPictureActive = active; pipPending = false
        backgroundTask?.cancel(); backgroundTask = nil
        if active {
            channelsOpen = false
            controls.hide()
        } else if appActive {
            controls.show()
        } else {
            controller.stop()
        }
        controls.refresh()
    }
}
