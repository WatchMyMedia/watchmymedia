import Foundation
import Observation
import WMMModel

public enum TVLivePlayerOverlay: Hashable, Sendable {
    case failed(String)
    case stopped(String)
    case stillWatching
}

/// Live-only AVKit actions: no seek, file progress, subtitles or next episode.
public struct TVLivePlayerAction: Hashable, Sendable, Identifiable {
    public enum Kind: String, Hashable, Sendable { case previousChannel, nextChannel, goLive, stop }
    public let kind: Kind
    public let title: String
    public var id: Kind { kind }
    public init(kind: Kind, title: String) { self.kind = kind; self.title = title }
    public var accessibilityLabel: String {
        switch kind {
        case .previousChannel: "Previous channel"
        case .nextChannel: "Next channel"
        case .goLive: "Go live"
        case .stop: "Stop live TV"
        }
    }
    public var systemImage: String {
        switch kind {
        case .previousChannel: "chevron.down"
        case .nextChannel: "chevron.up"
        case .goLive: "dot.radiowaves.left.and.right"
        case .stop: "stop.fill"
        }
    }
}

/// tvOS policy around the shared live lifecycle. AVKit gets the first Back to
/// close its info panel; a Back forwarded to this model answers Still watching
/// before a later Back leaves. Opening Channels or Quality keeps video playing.
@MainActor @Observable
public final class TVLivePlayerModel {
    public let live: LivePlayerModel
    public var controller: any LivePlaybackController { live.controller }
    public var state: LivePlayerState { live.state }
    public var channels: [LiveChannel] { live.channels }
    public var listings: Bool { live.listings }
    public var listedChannel: LiveChannel? { live.listedChannel }
    public var onExit: (@MainActor () -> Void)? {
        get { live.onExit }
        set { live.onExit = newValue }
    }
    public private(set) var transportBarVisible = false
    @ObservationIgnored private var disposed = false
    @ObservationIgnored private var pictureInPicturePossible = false

    public init(request: LivePlayRequest, repository: any LiveTVRepository, controller: any LivePlaybackController, onExit: (@MainActor () -> Void)? = nil) {
        live = LivePlayerModel(request: request, repository: repository, controller: controller, onExit: onExit)
    }

    public var overlay: TVLivePlayerOverlay? {
        if state.status == .error { return .failed(state.error ?? "This channel couldn't be played. Try again or choose another channel.") }
        if state.status == .stopped { return .stopped(state.message ?? "The tuner has been released.") }
        if state.askStillWatching { return .stillWatching }
        return nil
    }
    public var showsVideo: Bool {
        switch overlay {
        case .failed?, .stopped?: false
        default: true
        }
    }
    public var videoTakesInput: Bool { !disposed && overlay == nil }
    public var contextualActions: [TVLivePlayerAction] {
        guard videoTakesInput else { return [] }
        var actions: [TVLivePlayerAction] = []
        if channels.filter(\.available).count > 1 {
            actions += [.init(kind: .previousChannel, title: "Ch−"), .init(kind: .nextChannel, title: "Ch+")]
        }
        if !state.isAtLiveEdge || state.status == .paused { actions.append(.init(kind: .goLive, title: "Go live")) }
        return actions
    }
    /// The warning before a recording takes this tuner, when this person may stop it.
    /// The banner never takes focus: Don't record it is reached by a swipe down, in Channels.
    public var cancellableTakeover: LiveRecordingTakeover? {
        guard videoTakesInput, let takeover = state.takeover, takeover.canCancel else { return nil }
        return takeover
    }
    public var transportBarActions: [TVLivePlayerAction] {
        videoTakesInput ? [.init(kind: .stop, title: "Stop")] : []
    }

    public func start() { guard !disposed else { return }; live.start() }
    public func dispose() { guard !disposed else { return }; disposed = true; live.dispose() }
    public func close() { guard !disposed else { return }; disposed = true; live.close() }
    public func retry() { guard !disposed else { return }; controller.noteUserInput(); live.retry() }
    public func switchChannel(_ channel: LiveChannel) { guard !disposed else { return }; live.switchChannel(channel) }
    public func previousChannel() { guard videoTakesInput else { return }; live.previousChannel() }
    public func nextChannel() { guard videoTakesInput else { return }; live.nextChannel() }
    /// The channel watched before this one (not the one before it in the list); no button yet.
    public var lastChannel: LiveChannel? { live.lastChannel }
    public func switchToLastChannel() { guard videoTakesInput else { return }; live.switchToLastChannel() }
    public func enteredBackground() {
        live.enteredBackground(externalPlayback: controller.player?.isExternalPlaybackActive ?? false, pictureInPictureMayStart: pictureInPicturePossible)
    }
    public func pictureInPicturePossibleChanged(_ possible: Bool) { pictureInPicturePossible = possible }
    public func pictureInPictureChanged(active: Bool, appActive: Bool) {
        guard !disposed else { return }
        live.pictureInPictureChanged(active: active, appActive: appActive)
    }
    public func becameActive() { live.becameActive() }

    public func transportBarChanged(visible: Bool) {
        guard !disposed else { return }
        // Programmatic AVKit visibility updates must not dismiss an unanswered prompt.
        if visible && !transportBarVisible && overlay == nil { controller.noteUserInput() }
        transportBarVisible = visible
    }
    public func perform(_ kind: TVLivePlayerAction.Kind) {
        guard !disposed else { return }
        if kind == .stop { close(); return }
        guard videoTakesInput else { return }
        switch kind {
        case .previousChannel: previousChannel()
        case .nextChannel: nextChannel()
        case .goLive: controller.goLive()
        case .stop: break
        }
    }
    public func pickQuality(_ quality: LiveQuality) {
        guard videoTakesInput, state.qualities.contains(quality) else { return }
        controller.noteUserInput(); controller.selectQuality(quality)
    }
    public func answerStillWatching(keepWatching: Bool) {
        guard !disposed else { return }
        if keepWatching { controller.answerStillWatching(keepWatching: true) }
        else { close() }
    }
    public func playPause() {
        // AVKit handles native controls first; this is a command that bubbled
        // to the app or reached one of its own prompts, as for file playback.
        guard !disposed else { return }
        if overlay == .stillWatching { answerStillWatching(keepWatching: true) }
        else if videoTakesInput { controller.noteUserInput(); controller.togglePlayPause() }
    }
    /// Menu that reached the app (AVKit closes its own panel first): it
    /// answers Still watching, then puts away a reminder banner, which never
    /// takes focus so Menu is the remote's way to it, then leaves. Returns
    /// ``TVLiveBack/closeBanner`` when the view should hide `bannerShowing`'s banner.
    @discardableResult
    public func back(bannerShowing: Bool = false) -> TVLiveBack {
        guard !disposed else { return .handled }
        if overlay == .stillWatching { answerStillWatching(keepWatching: true); return .handled }
        if bannerShowing && overlay == nil { return .closeBanner }
        close()
        return .handled
    }
}

/// What Menu in the Live TV player leaves to the view.
public enum TVLiveBack: Hashable, Sendable {
    /// Hide the reminder banner; the channel keeps playing.
    case closeBanner
    /// The model answered the prompt or closed the player.
    case handled
}
