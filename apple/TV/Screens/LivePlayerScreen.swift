import AVKit
import SwiftUI
import WMMFakes
import WMMFeatures
import WMMModel
import WMMUI

/// TvLivePlayer's linear live transport and Channels/Quality tabs, entirely
/// separate from the file player's seek, subtitles and next-episode behavior.
struct LivePlayerScreen: View {
    let request: LivePlayRequest
    @Environment(AppServices.self) private var services
    @Environment(Navigator.self) private var navigator
    @Environment(\.scenePhase) private var scenePhase
    @State private var model: TVLivePlayerModel?

    var body: some View {
        ZStack {
            Color.black
            if let model { TVLivePlayerContent(model: model) }
        }
        .ignoresSafeArea()
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("live-player-screen")
        .onAppear {
            guard model == nil, let factory = services.livePlayback else { return }
            let model = TVLivePlayerModel(request: request, repository: services.liveTV, controller: factory.make(), onExit: { navigator.closePlayer() })
            self.model = model
            model.start()
            #if DEBUG
            if let fake = model.controller as? FakeLivePlaybackController {
                let args = ProcessInfo.processInfo.arguments
                if args.contains("-liveStillWatching") { fake.showStillWatching() }
                if args.contains("-livePauseExpired") { fake.showStopped(reason: "pause_expired") }
                if args.contains("-liveBusy") { fake.showError(reason: "tuners_busy") }
                if args.contains("-liveTakeover") { fake.showTakeover() }
                if args.contains("-liveTakenOver") { fake.showStopped(reason: "recording") }
                if args.contains("-liveAutoLow") { fake.setAutomaticStep(.p480) }
            }
            #endif
        }
        .onDisappear { model?.dispose() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .background { model?.enteredBackground() }
            else if phase == .active { model?.becameActive() }
        }
    }
}

private enum LivePanel: String, CaseIterable { case channels, quality
    var title: String { self == .channels ? "Channels" : "Quality" }
}

private struct TVLivePlayerContent: View {
    let model: TVLivePlayerModel
    @State private var standIn = AVPlayer()
    @State private var previewPanel: LivePanel?
    /// The reminder banner Menu put away; the store keeps it up for its ten seconds elsewhere.
    @State private var closedBanner: String?
    @FocusState private var focused: String?
    @Environment(LiveExtrasStore.self) private var extras
    @Environment(LiveRecordingsStore.self) private var recordings
    @Environment(Navigator.self) private var navigator

    init(model: TVLivePlayerModel) {
        self.model = model
        #if DEBUG
        // The stand-in panel is there from the first frame. Opened on appear,
        // the player under it could take focus first on a busy Mac, and focus
        // then moved to the nearest card, past the panel's default focus.
        let args = ProcessInfo.processInfo.arguments
        if model.controller.player == nil, let index = args.firstIndex(of: "-livePanel"), args.indices.contains(index + 1) {
            _previewPanel = State(initialValue: LivePanel(rawValue: args[index + 1]))
        }
        #endif
    }

    var body: some View {
        content
            // AVKit closes its own info panel first; only bubbled commands reach the app.
            .onExitCommand {
                if previewPanel != nil { previewPanel = nil; return }
                if model.back(bannerShowing: banner != nil) == .closeBanner { closedBanner = banner?.id }
            }
            .onPlayPauseCommand { model.playPause() }
    }

    private var content: some View {
        ZStack {
            if model.showsVideo {
                LiveSystemPlayer(model: model, extras: extras, recordings: recordings, player: model.controller.player ?? standIn,
                                 contextualActions: model.contextualActions, transportBarActions: model.transportBarActions)
                    .disabled(!model.videoTakesInput || previewPanel != nil)
            }
            if let previewPanel, model.overlay == nil {
                // The fake player has no AVPlayerItem, so AVKit cannot open its
                // native tabs. The same panel content is reachable for UI checks.
                LiveOptions(model: model, panel: previewPanel, extras: extras, recordings: recordings)
                    .padding(.horizontal, TVLayout.gutter)
                    .padding(.bottom, 60)
                    .frame(maxHeight: .infinity, alignment: .bottom)
            }
            switch model.overlay {
            case .stillWatching:
                prompt(title: "Are you still watching?", message: "Live TV stops in \(model.state.stillWatchingSeconds) seconds so the tuner is free for someone else.") {
                    Button("Keep watching") { model.answerStillWatching(keepWatching: true) }
                        .buttonStyle(TVButtonStyle(kind: .primary)).focused($focused, equals: "keep")
                        .accessibilityIdentifier("live-keep-watching")
                    Button("Stop watching") { model.answerStillWatching(keepWatching: false) }
                        .buttonStyle(TVButtonStyle()).accessibilityIdentifier("live-stop-watching")
                }
                .defaultFocus($focused, "keep", priority: .userInitiated)
            case .failed(let message):
                prompt(title: "Can't play this channel", message: message) {
                    Button("Try again") { model.retry() }
                        .buttonStyle(TVButtonStyle(kind: .primary)).focused($focused, equals: "retry")
                        .accessibilityIdentifier("live-retry")
                    Button("Back") { model.close() }.buttonStyle(TVButtonStyle()).accessibilityIdentifier("live-back")
                }
                .defaultFocus($focused, "retry", priority: .userInitiated)
            case .stopped:
                prompt(title: model.state.stoppedTitle, message: model.state.stoppedMessage) {
                    // Only once a recording took the tuner (reason "recording"), not for a warning before it.
                    if let taken = model.state.stoppedForRecording {
                        Button("Watch it from the start") { model.close(); navigator.play(.init(fileID: 0, startMs: 0, title: taken.title, recordingID: taken.recordingID)) }
                            .buttonStyle(TVButtonStyle(kind: .primary)).accessibilityIdentifier("live-watch-recording")
                    }
                    Button("Watch live") { model.retry() }
                        .buttonStyle(TVButtonStyle(kind: .primary)).focused($focused, equals: "retry")
                        .accessibilityIdentifier("live-retry")
                    Button("Back") { model.close() }.buttonStyle(TVButtonStyle()).accessibilityIdentifier("live-back")
                }
                .defaultFocus($focused, "retry", priority: .userInitiated)
            case nil:
                EmptyView()
            }
            // Like the reminder banner it never takes focus: a swipe down reaches Don't record it, first in Channels.
            if let takeover = model.state.takeover, model.overlay == nil {
                VStack(alignment: .leading, spacing: 10) {
                    Text(takeover.message()).font(.system(size: 26, weight: .bold)).foregroundStyle(Palette.warning)
                    Text(takeover.title).font(.system(size: 22)).foregroundStyle(Palette.textSecondary)
                    if model.cancellableTakeover != nil {
                        TVLiveHint(systemImage: "chevron.down", text: "Swipe down · Don’t record it")
                    }
                }
                .padding(24).frame(maxWidth: 1000, alignment: .leading)
                .background(Palette.sheet, in: RoundedRectangle(cornerRadius: 20))
                .padding(.top, 50).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                .allowsHitTesting(false)
                .accessibilityElement(children: .combine)
                .accessibilityIdentifier("live-recording-takeover")
            }
            // A Live TV reminder: Swipe down opens Channels with it first (TvLiveReminder's `bannerPlayer`).
            if let reminder = banner, model.overlay == nil {
                TVLiveReminderBanner(reminder: reminder, inPlayer: true)
                    .padding(.top, 60)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
            }
        }
        .animation(.easeOut(duration: 0.3), value: banner)
    }

    /// The reminder banner, unless Menu put it away (TvLiveReminder's `bannerPlayer`).
    private var banner: LiveReminder? {
        guard let banner = extras.banner, banner.id != closedBanner else { return nil }
        return banner
    }

    private func prompt<Actions: View>(title: String, message: String, @ViewBuilder actions: () -> Actions) -> some View {
        ZStack {
            Color.black.opacity(0.75)
            VStack(spacing: 24) {
                Text(title).font(.system(size: 44, weight: .bold))
                Text(model.state.channel?.title ?? "Live TV").font(.system(size: 24, weight: .semibold)).foregroundStyle(Palette.gold)
                Text(message).font(.system(size: 22)).foregroundStyle(Palette.muted).multilineTextAlignment(.center)
                HStack(spacing: 24, content: actions).padding(.top, 12).frame(maxWidth: .infinity).focusSection()
            }
            .padding(48).frame(width: 900)
            .background(Palette.sheet.opacity(0.97), in: RoundedRectangle(cornerRadius: 28))
        }
        .foregroundStyle(Palette.text)
    }
}

private struct LiveVideoOverlay: View {
    let model: TVLivePlayerModel
    let standIn: Bool
    @State private var banner = true

    var body: some View {
        let state = model.state
        ZStack {
            if standIn {
                Artwork(url: nil, seed: state.channel?.name ?? "Live TV", maxPixel: 1920)
                    .overlay(.black.opacity(0.45))
            }
            if let channel = model.listedChannel, banner || model.transportBarVisible || standIn || state.status == .paused {
                TimelineView(.periodic(from: .now, by: 30)) { context in
                    TVLiveBanner(channel: channel, now: context.date, state: state)
                }
            } else if banner || model.transportBarVisible || standIn || state.status == .paused {
                VStack(alignment: .leading, spacing: 14) {
                    HStack(spacing: 16) {
                        Text(state.status == .tuning ? "TUNING" : state.isAtLiveEdge && state.status != .paused ? "LIVE" : "BEHIND LIVE")
                            .font(.system(size: 17, weight: .bold)).tracking(1.2)
                            .padding(.horizontal, 14).padding(.vertical, 7)
                            .background(Color(hex: 0xB42318), in: Capsule())
                        Text(state.qualityLabel).font(.system(size: 20)).foregroundStyle(Palette.textSecondary)
                            .accessibilityIdentifier("live-player-quality")
                        Spacer()
                        Text("Swipe down for Channels and Quality").font(.system(size: 20)).foregroundStyle(Palette.muted)
                    }
                    Text(state.channel?.title ?? "Live TV").font(.system(size: 44, weight: .bold))
                        .accessibilityIdentifier("live-player-title")
                    Text("No programme listings").font(.system(size: 20)).foregroundStyle(Palette.muted)
                    if state.status == .paused {
                        Text("Paused · Tuner releases in \(state.pauseRemainingSeconds / 60):\(String(format: "%02d", state.pauseRemainingSeconds % 60))")
                            .font(.system(size: 22)).foregroundStyle(Palette.gold)
                    }
                }
                .padding(TVLayout.gutter)
                .background(LinearGradient(colors: [.black.opacity(0.8), .clear], startPoint: .top, endPoint: .bottom))
                .frame(maxHeight: .infinity, alignment: .top)
            }
            if state.status == .tuning || state.status == .buffering {
                ProgressView(state.status == .tuning ? "Tuning channel" : "Buffering").tint(Palette.gold)
            }
            if let message = state.message {
                Text(message).font(.system(size: 22)).padding(20).background(Palette.ground.opacity(0.9), in: Capsule())
                    .padding(.bottom, 260).frame(maxHeight: .infinity, alignment: .bottom)
            }
            if let message = state.switchMessage {
                Text(message).font(.system(size: 20)).padding(18).background(Palette.ground.opacity(0.9), in: Capsule())
                    .padding(.bottom, 330).frame(maxHeight: .infinity, alignment: .bottom)
                    .accessibilityIdentifier("live-switch-message")
            }
        }
        .foregroundStyle(Palette.text)
        .task(id: model.listings) { if model.listings { await model.live.followSchedule() } }
        .task(id: state.channel?.id) {
            banner = true
            guard (try? await Task.sleep(for: .seconds(6))) != nil else { return }
            banner = false
        }
        .allowsHitTesting(false)
    }
}

/// AVKit retains its familiar transport, but seeking and file skips are disabled.
private struct LiveSystemPlayer: UIViewControllerRepresentable {
    let model: TVLivePlayerModel
    let extras: LiveExtrasStore
    let recordings: LiveRecordingsStore
    let player: AVPlayer
    let contextualActions: [TVLivePlayerAction]
    let transportBarActions: [TVLivePlayerAction]
    func makeCoordinator() -> Coordinator { Coordinator(model: model) }

    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let controller = AVPlayerViewController()
        controller.player = player
        controller.delegate = context.coordinator
        controller.requiresLinearPlayback = true
        controller.allowedSubtitleOptionLanguages = []
        controller.customInfoViewControllers = LivePanel.allCases.map { LiveOptionsController(model: model, panel: $0, extras: extras, recordings: recordings) }
        context.coordinator.installOverlay(in: controller, standIn: model.controller.player == nil)
        context.coordinator.updateActions(in: controller, contextual: contextualActions, transport: transportBarActions)
        model.pictureInPicturePossibleChanged(controller.allowsPictureInPicturePlayback && AVPictureInPictureController.isPictureInPictureSupported())
        return controller
    }
    func updateUIViewController(_ controller: AVPlayerViewController, context: Context) {
        if controller.player !== player { controller.player = player }
        context.coordinator.updateActions(in: controller, contextual: contextualActions, transport: transportBarActions)
    }
    static func dismantleUIViewController(_ controller: AVPlayerViewController, coordinator: Coordinator) {
        controller.player = nil
    }

    @MainActor final class Coordinator: NSObject, @MainActor AVPlayerViewControllerDelegate {
        let model: TVLivePlayerModel
        private var overlayHost: UIViewController?
        private var contextual: [TVLivePlayerAction] = []
        private var transport: [TVLivePlayerAction] = []
        init(model: TVLivePlayerModel) { self.model = model }
        func installOverlay(in controller: AVPlayerViewController, standIn: Bool) {
            guard let container = controller.contentOverlayView else { return }
            let host = UIHostingController(rootView: LiveVideoOverlay(model: model, standIn: standIn))
            host.view.backgroundColor = .clear
            host.view.isUserInteractionEnabled = false
            host.view.frame = container.bounds
            host.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
            container.addSubview(host.view)
            overlayHost = host
        }
        func updateActions(in controller: AVPlayerViewController, contextual newContextual: [TVLivePlayerAction], transport newTransport: [TVLivePlayerAction]) {
            if contextual != newContextual {
                contextual = newContextual
                controller.contextualActions = contextual.map(action)
            }
            if transport != newTransport {
                transport = newTransport
                controller.transportBarCustomMenuItems = transport.map(action)
            }
        }
        private func action(_ item: TVLivePlayerAction) -> UIAction {
            let result = UIAction(title: item.title, image: UIImage(systemName: item.systemImage), identifier: .init("media.watchmy.live.\(item.kind.rawValue)")) { [weak model] _ in model?.perform(item.kind) }
            result.accessibilityLabel = item.accessibilityLabel
            return result
        }
        func playerViewController(_ playerViewController: AVPlayerViewController, willTransitionToVisibilityOfTransportBar visible: Bool, with coordinator: any AVPlayerViewControllerAnimationCoordinator) {
            model.transportBarChanged(visible: visible)
        }
        func playerViewController(_ playerViewController: AVPlayerViewController, timeToSeekAfterUserNavigatedFrom oldTime: CMTime, to targetTime: CMTime) -> CMTime {
            model.controller.noteUserInput()
            return oldTime
        }
        func playerViewControllerShouldAutomaticallyDismissAtPictureInPictureStart(_ playerViewController: AVPlayerViewController) -> Bool { false }
        func playerViewControllerWillStartPictureInPicture(_ playerViewController: AVPlayerViewController) {
            model.pictureInPictureChanged(active: true, appActive: UIApplication.shared.applicationState == .active)
        }
        func playerViewControllerDidStopPictureInPicture(_ playerViewController: AVPlayerViewController) {
            model.pictureInPictureChanged(active: false, appActive: UIApplication.shared.applicationState == .active)
        }
        func playerViewController(_ playerViewController: AVPlayerViewController, failedToStartPictureInPictureWithError error: any Error) {
            model.pictureInPictureChanged(active: false, appActive: UIApplication.shared.applicationState == .active)
        }
        func playerViewController(_ playerViewController: AVPlayerViewController, restoreUserInterfaceForPictureInPictureStopWithCompletionHandler completionHandler: @escaping (Bool) -> Void) {
            completionHandler(model.controller.player?.currentItem != nil)
        }
    }
}

private final class LiveOptionsController: UIHostingController<LiveOptions> {
    init(model: TVLivePlayerModel, panel: LivePanel, extras: LiveExtrasStore, recordings: LiveRecordingsStore) {
        super.init(rootView: LiveOptions(model: model, panel: panel, extras: extras, recordings: recordings))
        title = panel.title
        preferredContentSize = CGSize(width: 1920, height: 430)
        view.backgroundColor = .clear
    }
    @available(*, unavailable)
    @MainActor required dynamic init?(coder: NSCoder) { fatalError("not used") }
}

private struct LiveOptions: View {
    let model: TVLivePlayerModel
    let panel: LivePanel
    /// Hosted by AVKit outside the app's environment, so handed in.
    let extras: LiveExtrasStore
    let recordings: LiveRecordingsStore
    @FocusState private var focus: String?
    @State private var takeoverError: String?

    /// A reminder due now, and the channel it is on, which comes first and takes focus.
    private var due: (reminder: LiveReminder, channel: LiveChannel)? {
        guard model.listings, let reminder = extras.due(),
              let channel = model.channels.first(where: { $0.id == reminder.channelID }) ?? model.channels.first(where: { $0.number == reminder.number })
        else { return nil }
        return (reminder, channel)
    }

    private var channels: [LiveChannel] {
        guard let due else { return model.channels }
        return [due.channel] + model.channels.filter { $0.id != due.channel.id }
    }

    private var initialFocus: String? {
        guard panel == .channels else { return model.state.quality.rawValue }
        if model.cancellableTakeover != nil { return "takeover" }
        return due?.channel.id ?? model.state.channel?.id
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            Text(panel.title).font(.system(size: 28, weight: .bold)).accessibilityIdentifier("live-panel-\(panel.rawValue)")
            if panel == .channels, let takeover = model.cancellableTakeover {
                takeoverRow(takeover)
            }
            if panel == .channels {
                // Asked again as time passes and when the banner comes, so a reminder that comes due moves first.
                TimelineView(.periodic(from: .now, by: 15)) { _ in
                    let due = extras.banner == nil ? self.due : self.due
                    VStack(alignment: .leading, spacing: 20) {
                        channelCards(due)
                        if due != nil {
                            Text(liveReminderPanelNote).font(.system(size: 16)).foregroundStyle(Palette.faint)
                        }
                    }
                }
            } else {
                Text(model.state.qualities.contains(.auto)
                     ? "Automatic adjusts to this connection. Fixed quality tunes this channel again, at live."
                     : "Changing quality tunes this channel again, at live.")
                    .font(.system(size: 20)).foregroundStyle(Palette.muted)
                if model.state.quality == .auto {
                    Text("Now \(model.state.qualityLabel)").font(.system(size: 19)).foregroundStyle(Palette.gold)
                }
                HStack(spacing: 30) {
                    ForEach(model.state.qualities, id: \.self) { quality in
                        Button {
                            model.pickQuality(quality)
                        } label: {
                            if quality == model.state.quality {
                                Text("\(Image(systemName: "checkmark")) \(quality.title)")
                            } else {
                                Text(quality.title)
                            }
                        }
                        .buttonStyle(TVChipStyle(selected: quality == model.state.quality, height: 58, fontSize: 24))
                        .focused($focus, equals: quality.rawValue)
                        .accessibilityIdentifier("live-quality-option-\(quality.rawValue)")
                    }
                }.padding(.vertical, 20).frame(maxWidth: .infinity, alignment: .leading).focusSection()
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, TVLayout.gutter - 20).padding(.vertical, 24)
        .frame(height: 430)
        .background(Palette.sheet.opacity(0.96), in: RoundedRectangle(cornerRadius: 28))
        .defaultFocus($focus, initialFocus, priority: .userInitiated)
        .onAppear {
            // AVKit and the stand-in host can already have focus when this panel
            // opens. Request its entry target once; a later warning never moves it.
            focus = initialFocus
        }
    }

    /// The takeover banner's action, which the banner itself can't take focus for.
    private func takeoverRow(_ takeover: LiveRecordingTakeover) -> some View {
        HStack(spacing: 24) {
            VStack(alignment: .leading, spacing: 4) {
                Text(takeover.message()).font(.system(size: 22, weight: .semibold)).foregroundStyle(Palette.warning)
                Text(takeoverError ?? takeover.title).font(.system(size: 18)).foregroundStyle(takeoverError == nil ? Palette.muted : Palette.warning)
            }
            Spacer(minLength: 0)
            Button("Don’t record it") {
                takeoverError = nil
                Task {
                    if !(await recordings.cancelTakeover(takeover)) {
                        takeoverError = recordings.message ?? "Couldn’t stop the recording. Try again."
                    }
                }
            }
            .buttonStyle(TVChipStyle(selected: false, height: 58, fontSize: 24))
            .disabled(recordings.changing.contains(takeover.recordingID))
            .focused($focus, equals: "takeover")
            .accessibilityIdentifier("live-takeover-cancel")
        }
        .frame(maxWidth: .infinity).focusSection()
    }

    private func channelCards(_ due: (reminder: LiveReminder, channel: LiveChannel)?) -> some View {
        ScrollView(.horizontal) {
            HStack(spacing: 24) {
                ForEach(channels) { channel in
                    Group {
                        if model.listings {
                            Button { model.switchChannel(channel) } label: {
                                TimelineView(.periodic(from: .now, by: 30)) { context in
                                    let reminder = due?.channel.id == channel.id ? due?.reminder : nil
                                    TVLiveOnNowCard(channel: channel, now: context.date, watching: channel.id == model.state.channel?.id, width: 330,
                                                    reminder: reminder, reminderProgramme: reminder.flatMap(extras.programme(for:)))
                                }
                            }
                            .buttonStyle(TVLiveCardButtonStyle())
                        } else {
                            Button { model.switchChannel(channel) } label: {
                                LiveChannelOption(channel: channel, selected: channel.id == model.state.channel?.id)
                            }
                            .buttonStyle(.borderless)
                        }
                    }
                    .focused($focus, equals: channel.id)
                    .disabled(!channel.available)
                    .accessibilityIdentifier("live-channel-option-\(channel.id)")
                }
            }.padding(20)
        }.scrollClipDisabled().focusSection()
    }
}

private struct LiveChannelOption: View {
    let channel: LiveChannel
    let selected: Bool
    @Environment(\.isFocused) private var focused
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text(channel.number).font(.system(size: 32, weight: .bold)).monospacedDigit()
                Spacer()
                if selected { Text("\(Image(systemName: "speaker.wave.2.fill")) LIVE").font(.system(size: 17, weight: .bold)) }
            }
            Text(channel.name).font(.system(size: 24, weight: .semibold)).lineLimit(1)
            Text(channel.available ? "No listings" : channel.reason.map(LiveTVError.message) ?? "Channel unavailable")
                .font(.system(size: 18)).foregroundStyle(focused ? Palette.ground.opacity(0.7) : Palette.muted).lineLimit(2)
        }
        .foregroundStyle(focused ? Palette.ground : Palette.text)
        .padding(20).frame(width: 330, height: 180)
        .background(focused ? Palette.text : Palette.surfaceRaised, in: RoundedRectangle(cornerRadius: 16))
        .focusRim(focused, scale: 1.05)
    }
}

/// TvLivePlayer's banner with listings: the hint and quality at the top; at
/// the bottom the picture, logo, number and LIVE, title, episode line and
/// tags, times and progress, and what is next.
private struct TVLiveBanner: View {
    let channel: LiveChannel
    let now: Date
    let state: LivePlayerState

    var body: some View {
        let programme = channel.programme(at: now)
        VStack(spacing: 0) {
            HStack {
                Spacer()
                Text("\(Image(systemName: "chevron.down")) Swipe down for Channels and Quality").font(.system(size: 19)).foregroundStyle(Palette.muted)
                Spacer()
            }
            .overlay(alignment: .trailing) {
                Text(state.qualityLabel).font(.system(size: 19)).foregroundStyle(Palette.textSecondary)
                    .padding(.horizontal, 12).padding(.vertical, 5).background(.black.opacity(0.5), in: Capsule())
                    .accessibilityIdentifier("live-player-quality")
            }
            .padding(.horizontal, TVLayout.gutter).padding(.top, 50)
            Spacer()
            HStack(alignment: .bottom, spacing: 28) {
                LivePictureView(programme: programme, channel: channel, maxPixel: 600)
                    .frame(width: 260, height: 146).clipShape(RoundedRectangle(cornerRadius: 12))
                VStack(alignment: .leading, spacing: 8) {
                    HStack(spacing: 12) {
                        if channel.logoURL != nil { LiveLogo(url: channel.logoURL) { Color.clear }.frame(width: 44, height: 33) }
                        Text("\(channel.number) \(channel.name)").font(.system(size: 24, weight: .semibold))
                        Text(state.status == .tuning ? "TUNING" : state.isAtLiveEdge && state.status != .paused ? "LIVE" : "BEHIND LIVE")
                            .font(.system(size: 15, weight: .bold)).tracking(1)
                            .padding(.horizontal, 10).padding(.vertical, 4)
                            .background(Palette.liveRed, in: Capsule())
                    }
                    Text(programme?.title ?? channel.name).font(.system(size: 40, weight: .bold)).lineLimit(1)
                        .accessibilityIdentifier("live-player-title")
                    if let programme {
                        HStack(spacing: 10) {
                            if let line = programme.episodeLine { Text(line).font(.system(size: 21)).foregroundStyle(Palette.textSecondary) }
                            if !programme.tags.isEmpty { LiveTags(programme.tags, size: 14) }
                        }
                        HStack(spacing: 14) {
                            Text(LiveClock.device.range(programme.start, programme.end)).font(.system(size: 19)).foregroundStyle(Palette.textSecondary)
                            LiveProgressBar(progress: programme.progress(at: now), height: 5).frame(width: 240)
                            Text(liveTimeLeft(programme, now: now)).font(.system(size: 19)).foregroundStyle(Palette.muted)
                        }
                    } else {
                        Text("No listings for this channel.").font(.system(size: 21)).foregroundStyle(Palette.muted)
                    }
                    if state.status == .paused {
                        Text("Paused · Tuner releases in \(state.pauseRemainingSeconds / 60):\(String(format: "%02d", state.pauseRemainingSeconds % 60))")
                            .font(.system(size: 21)).foregroundStyle(Palette.gold)
                    }
                }
                Spacer(minLength: 20)
                if let next = channel.programme(after: now) {
                    Text(liveNextLine(next)).font(.system(size: 21)).foregroundStyle(Palette.textSecondary)
                }
            }
            .padding(.horizontal, TVLayout.gutter)
            .padding(.bottom, 170)
        }
        .background(LinearGradient(stops: [.init(color: .black.opacity(0.6), location: 0), .init(color: .clear, location: 0.2),
                                           .init(color: .clear, location: 0.5), .init(color: .black.opacity(0.85), location: 1)],
                                   startPoint: .top, endPoint: .bottom))
    }
}
