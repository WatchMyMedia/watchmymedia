import Foundation
import Observation
import WMMModel

/// Touch and remote screens render the same live lifecycle without inheriting any file controls.
@MainActor @Observable
public final class LivePlayerModel {
    public let controller: any LivePlaybackController
    public var onExit: (@MainActor () -> Void)?
    public var state: LivePlayerState { controller.state }
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
    @ObservationIgnored private var pipActive = false
    @ObservationIgnored private var backgroundTask: Task<Void, Never>?

    public init(request: LivePlayRequest, repository: any LiveTVRepository, controller: any LivePlaybackController, onExit: (@MainActor () -> Void)? = nil) {
        self.request = request; self.repository = repository; self.controller = controller; self.onExit = onExit
    }
    public func start() {
        guard !started, !disposed else { return }
        started = true; controller.start(request)
    }
    public func switchChannel(_ channel: LiveChannel) {
        guard !disposed, channel.available else { return }
        if channel.id != request.channel.id { lastChannel = request.channel }
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
        controller.release()
    }
    public func enteredBackground(externalPlayback: Bool, pictureInPictureMayStart: Bool) {
        guard !disposed, !externalPlayback, !pipActive else { return }
        pipPending = pictureInPictureMayStart
        backgroundTask?.cancel()
        if pictureInPictureMayStart {
            backgroundTask = Task { [weak self] in
                try? await Task.sleep(for: .seconds(2))
                guard !Task.isCancelled, let self, !self.pipActive else { return }
                self.pipPending = false; self.controller.stop()
            }
        } else { controller.stop() }
    }
    public func becameActive() {
        backgroundTask?.cancel(); backgroundTask = nil; pipPending = false
        // Returning never silently allocates a tuner; the stopped screen offers Play.
    }
    public func pictureInPictureChanged(active: Bool, appActive: Bool) {
        guard !disposed else { return }
        pipActive = active; pipPending = false
        backgroundTask?.cancel(); backgroundTask = nil
        if !active, !appActive { controller.stop() }
    }
}
