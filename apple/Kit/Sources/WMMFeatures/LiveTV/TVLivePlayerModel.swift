import Foundation
import Observation
import WMMModel

public enum TVLivePlayerOverlay: Hashable, Sendable {
    case failed(String)
    case stopped(String)
    case stillWatching
}

/// Live-only AVKit actions: no seek, file progress, subtitles or next episode.
/// Record, Channel down and Channel up are the transport bar's buttons, to
/// the left of the system's (as contextual actions they took Select from the
/// bar); Last channel and Go to channel lead the Channels tab.
public struct TVLivePlayerAction: Hashable, Sendable, Identifiable {
    public enum Kind: String, Hashable, Sendable { case record, stopRecording, lastChannel, previousChannel, nextChannel, goToChannel, goLive, stop }
    public let kind: Kind
    public let title: String
    /// Under the title while the button has focus: where it goes ("7.1 LKSD-HD · Valley Bake-Off").
    public let subtitle: String?
    /// The channel it tunes, for VoiceOver ("7.1 LKSD-HD").
    public let channel: String?
    public var id: Kind { kind }
    public init(kind: Kind, title: String, subtitle: String? = nil, channel: String? = nil) {
        self.kind = kind; self.title = title; self.subtitle = subtitle; self.channel = channel
    }
    /// "Channel up, 4.3 Coast Shop".
    public var accessibilityLabel: String {
        let name = switch kind {
        case .record: "Record this programme"
        case .stopRecording: "Stop recording this programme"
        case .lastChannel: "Last channel"
        case .previousChannel: "Channel down"
        case .nextChannel: "Channel up"
        case .goToChannel: "Go to channel"
        case .goLive: "Go live"
        case .stop: "Stop live TV"
        }
        return [name, channel].compactMap { $0 }.joined(separator: ", ")
    }
    public var systemImage: String {
        switch kind {
        case .record: "record.circle"
        case .stopRecording: "stop.circle"
        case .lastChannel: "arrow.counterclockwise"
        case .previousChannel: "chevron.down"
        case .nextChannel: "chevron.up"
        case .goToChannel: "number"
        case .goLive: "dot.radiowaves.left.and.right"
        case .stop: "stop.fill"
        }
    }

    /// "7.1 LKSD-HD", as the buttons name a channel.
    public static func label(_ channel: LiveChannel) -> String { "\(channel.number) \(channel.name)" }
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
    /// Go to channel's keypad, while it is open.
    public private(set) var goTo: GoToChannelModel?
    /// Recordings, for the bar's Record; nil where the screen records nothing.
    public let recordings: LiveRecordingsStore?
    /// Record found the tuners booked: the choice of which one gives way.
    public var clash: LiveRecordingClash?
    @ObservationIgnored private var disposed = false
    @ObservationIgnored private var pictureInPicturePossible = false

    public init(request: LivePlayRequest, repository: any LiveTVRepository, controller: any LivePlaybackController,
                recordings: LiveRecordingsStore? = nil, onExit: (@MainActor () -> Void)? = nil) {
        live = LivePlayerModel(request: request, repository: repository, controller: controller, onExit: onExit)
        self.recordings = recordings
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
    /// Go live, while behind the live edge or paused: a contextual action, so Select takes it.
    public var contextualActions: [TVLivePlayerAction] {
        guard videoTakesInput, !state.isAtLiveEdge || state.status == .paused else { return [] }
        return [.init(kind: .goLive, title: "Go live")]
    }
    /// More than one channel can be watched: the channel buttons and keys do something.
    public var changesChannel: Bool { channels.filter(\.available).count > 1 }
    /// The warning before a recording takes this tuner, when this person may stop it.
    /// The banner never takes focus: Don't record it is reached by a swipe down, in Channels.
    public var cancellableTakeover: LiveRecordingTakeover? {
        guard videoTakesInput, let takeover = state.takeover, takeover.canCancel else { return nil }
        return takeover
    }
    /// The transport bar's buttons: Record (or Stop recording) for the
    /// programme on now, then Channel down and Channel up naming where they
    /// go. Back stops; Last channel and Go to channel are in Channels.
    public var transportBarActions: [TVLivePlayerAction] {
        transportBarActions(now: .now)
    }

    public func transportBarActions(now: Date) -> [TVLivePlayerAction] {
        guard videoTakesInput else { return [] }
        var actions: [TVLivePlayerAction] = []
        if let offer = recordable(now: now) {
            actions.append(offer.stop == nil
                ? .init(kind: .record, title: "Record", subtitle: offer.programme.title)
                : .init(kind: .stopRecording, title: liveStopRecording, subtitle: offer.programme.title))
        }
        if changesChannel {
            let down = live.channel(steppedBy: -1).map(TVLivePlayerAction.label)
            let up = live.channel(steppedBy: 1).map(TVLivePlayerAction.label)
            actions += [
                .init(kind: .previousChannel, title: "Channel down", subtitle: down, channel: down),
                .init(kind: .nextChannel, title: "Channel up", subtitle: up, channel: up),
            ]
        }
        return actions
    }

    /// Channels' first tiles, left of the channel being watched: Last channel
    /// once there is one that can be watched, then Go to channel.
    public func channelsPanelActions(now: Date = .now) -> [TVLivePlayerAction] {
        guard videoTakesInput else { return [] }
        var actions: [TVLivePlayerAction] = []
        if let last = live.lastChannel.map({ last in channels.first { $0.id == last.id } ?? last }), last.available, last.id != state.channel?.id {
            let name = TVLivePlayerAction.label(last)
            let on = listings ? last.programme(at: now)?.title : nil
            actions.append(.init(kind: .lastChannel, title: "Last channel", subtitle: [name, on].compactMap { $0 }.joined(separator: " · "), channel: name))
        }
        if changesChannel {
            actions.append(.init(kind: .goToChannel, title: "Go to channel", subtitle: "By its number"))
        }
        return actions
    }

    /// The programme on now that the bar's Record would record, and the
    /// recording of it this person may stop once one is under way. Nil
    /// without listings, where the server records nothing for this person,
    /// or while someone else's recording has it.
    private func recordable(now: Date) -> (programme: LiveProgramme, stop: LiveRecordingMark?)? {
        guard let recordings, recordings.available, let channel = listedChannel, let programme = channel.programme(at: now) else { return nil }
        let actions = LiveProgrammeActions(programme: programme, channel: channel, now: now, recordingAvailable: true,
                                           mark: recordings.mark(for: programme), reminded: false, canRemind: false)
        switch actions.recording {
        case .record?: return (programme, nil)
        case .marked?: return actions.cancel.map { (programme, $0) }
        case nil: return nil
        }
    }


    public func start() { guard !disposed else { return }; live.start() }
    public func dispose() { guard !disposed else { return }; disposed = true; closeGoToChannel(); live.dispose() }
    public func close() { guard !disposed else { return }; disposed = true; closeGoToChannel(); live.close() }
    public func retry() { guard !disposed else { return }; controller.noteUserInput(); live.retry() }
    public func switchChannel(_ channel: LiveChannel) { guard !disposed else { return }; live.switchChannel(channel) }
    public func previousChannel() { guard videoTakesInput else { return }; live.previousChannel() }
    public func nextChannel() { guard videoTakesInput else { return }; live.nextChannel() }
    /// The channel watched before this one (not the one before it in the list): the Last channel button.
    public var lastChannel: LiveChannel? { live.lastChannel }
    public func switchToLastChannel() { guard videoTakesInput else { return }; live.switchToLastChannel() }

    // MARK: - The remote's channel keys

    /// Page Up: the next channel, as Ch+ does, with a note naming it.
    public func pageUp() { page(1) }
    /// Page Down: the channel before, as Ch− does.
    public func pageDown() { page(-1) }

    /// The banner names the new channel at once, as after any change.
    private func page(_ delta: Int) {
        guard videoTakesInput, goTo == nil, let target = live.channel(steppedBy: delta) else { return }
        live.switchChannel(target)
    }

    /// The 123 key or the Go to channel button: the keypad.
    public func openGoToChannel() {
        guard videoTakesInput, goTo == nil, changesChannel else { return }
        controller.noteUserInput()
        goTo = GoToChannelModel(
            channels: { [weak self] in self?.channels ?? [] },
            tune: { [weak self] channel in
                self?.closeGoToChannel()
                self?.switchChannel(channel)
            }
        )
    }

    /// Back on the keypad, or a match tuned: it closes and nothing more tunes.
    public func closeGoToChannel() {
        goTo?.cancel()
        goTo = nil
    }

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
        case .record, .stopRecording: record()
        case .lastChannel: switchToLastChannel()
        case .previousChannel: previousChannel()
        case .nextChannel: nextChannel()
        case .goToChannel: openGoToChannel()
        case .goLive: controller.goLive()
        case .stop: break
        }
    }
    /// The bar's Record: the rest of the programme on now, just this
    /// episode, with a clash's choice of which gives way left in ``clash``;
    /// or Stop recording, for this person's recording of it.
    private func record() {
        guard let recordings, let offer = recordable(now: .now) else { return }
        controller.noteUserInput()
        Task { @MainActor [weak self] in
            if let stop = offer.stop {
                await recordings.cancel(stop, programmeID: offer.programme.id)
            } else if let result = await recordings.record(offer.programme, options: .init()), let found = LiveRecordingClash(result) {
                self?.clash = found
            }
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
        if goTo != nil { closeGoToChannel(); return .handled }
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
