import AVKit
import SwiftUI
#if DEBUG
import WMMFakes
#endif
import WMMFeatures
import WMMModel
import WMMUI

/// The live player: the system's transport bar at the live edge with
/// Record, Channel down and Channel up as its buttons, Channels and Quality
/// as its tabs, and a banner after every channel change saying it is live,
/// what is on, how long is left and what is next. Nothing about the stream
/// is on the picture. Page Up and Page Down change channel with the banner
/// at once; Back leaves for the guide, on this channel. A channel that
/// can't play says why in a sheet, the most useful thing to do first.
struct LivePlayerScreen: View {
    let request: LivePlayRequest
    @Environment(AppServices.self) private var services
    @Environment(Navigator.self) private var navigator
    @Environment(LiveRecordingsStore.self) private var recordings
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
            let model = TVLivePlayerModel(request: request, repository: services.liveTV, controller: factory.make(),
                                          recordings: recordings, onExit: { navigator.closePlayer() })
            self.model = model
            model.start()
            TVLiveWatching.shared.watched(request.channel)
            #if DEBUG
            if let fake = model.controller as? FakeLivePlaybackController {
                let args = ProcessInfo.processInfo.arguments
                if args.contains("-liveStillWatching") { fake.showStillWatching() }
                if args.contains("-livePauseExpired") { fake.showStopped(reason: "pause_expired") }
                if args.contains("-liveBusy") { fake.showError(reason: "tuners_busy") }
                if args.contains("-liveNoSignal") { fake.showError(reason: "no_signal") }
                if args.contains("-liveTakeover") { fake.showTakeover() }
                if args.contains("-liveTakenOver") { fake.showStopped(reason: "recording") }
                if args.contains("-liveAutoLow") { fake.setAutomaticStep(.p480) }
            }
            #endif
        }
        .onChange(of: model?.state.channel?.id) { _, _ in TVLiveWatching.shared.watched(model?.state.channel) }
        .onDisappear {
            model?.dispose()
            TVLiveWatching.shared.playerClosed()
        }
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
                if problem != nil { backToGuide(); return }
                if model.back(bannerShowing: banner != nil) == .closeBanner { closedBanner = banner?.id }
            }
            .onPlayPauseCommand { model.playPause() }
            .tvLiveSheet(item: Binding(get: { model.clash }, set: { model.clash = $0 })) { found in
                TVLiveRecordingClashView(clash: found) { kept in
                    if kept { recordings.message = found.kept }
                    model.clash = nil
                }
            }
    }

    /// What the channel can't do, in the viewer's words; nil while it plays.
    private var problem: TVLiveProblem? {
        TVLiveProblem(state: model.state, recordingNow: recordings.page(.now)?.items ?? [], otherChannels: model.changesChannel)
    }

    private var content: some View {
        ZStack {
            if model.showsVideo {
                SystemPlayer(
                    player: model.controller.player ?? standIn,
                    contextualActions: model.contextualActions,
                    transportBarActions: model.transportBarActions,
                    behavior: .live(model, extras: extras, recordings: recordings),
                    overlay: LiveVideoOverlay(model: model, standIn: model.controller.player == nil)
                )
                    .disabled(!model.videoTakesInput || previewPanel != nil || model.goTo != nil)
            }
            if let problem, previewPanel == nil {
                problemSheet(problem)
            } else if model.overlay == .stillWatching {
                TVSheet(width: 900) {
                    Text("Are you still watching?").font(.tv(.title3)).foregroundStyle(Palette.text)
                    Text("Live TV stops in \(model.state.stillWatchingSeconds) seconds so the tuner is free for someone else.")
                        .font(.tv(.body)).foregroundStyle(Palette.textSecondary)
                    VStack(spacing: 12) {
                        TVSheetRow(title: "Keep watching") { model.answerStillWatching(keepWatching: true) }
                            .focused($focused, equals: "keep")
                            .accessibilityIdentifier("live-keep-watching")
                        TVSheetRow(title: "Stop watching") { model.answerStillWatching(keepWatching: false) }
                            .accessibilityIdentifier("live-stop-watching")
                    }
                    .padding(.top, 8)
                    .focusSection()
                }
                .defaultFocus($focused, "keep", priority: .userInitiated)
            }
            if let previewPanel, model.overlay == nil || problem != nil {
                // The fake player has no AVPlayerItem, so AVKit cannot open its
                // native tabs; and a channel that can't play shows Other
                // channels here. The same panel content either way.
                LiveOptions(model: model, panel: previewPanel, extras: extras, recordings: recordings)
                    .padding(.horizontal, TVLayout.gutter)
                    .padding(.bottom, TVLayout.safeBottom)
                    .frame(maxHeight: .infinity, alignment: .bottom)
            }
            // Like the reminder banner it never takes focus: Don't record it is first in Channels.
            if let takeover = model.state.takeover, model.overlay == nil {
                VStack(alignment: .leading, spacing: 8) {
                    Text(takeover.message()).font(.tv(.body, .semibold)).foregroundStyle(Palette.warning)
                    Text(takeover.title).font(.tv(.caption1)).foregroundStyle(Palette.textSecondary)
                }
                .padding(.horizontal, 34).padding(.vertical, 24).frame(maxWidth: 1100, alignment: .leading)
                .background(Color(hex: 0x18181B).opacity(0.96), in: RoundedRectangle(cornerRadius: 24))
                .padding(.top, TVLayout.safeTop).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                .allowsHitTesting(false)
                .accessibilityElement(children: .combine)
                .accessibilityIdentifier("live-recording-takeover")
            }
            // A Live TV reminder: Channels shows it first.
            if let reminder = banner, model.overlay == nil {
                TVLiveReminderBanner(reminder: reminder, inPlayer: true)
                    .padding(.top, TVLayout.safeTop)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
            }
            // Go to channel's keypad.
            if model.overlay == nil { LiveChannelKeysLayer(model: model) }
        }
        .animation(.easeOut(duration: 0.3), value: banner)
        .messagePill(Binding(get: { model.clash == nil ? recordings.message : nil }, set: { recordings.message = $0 }))
        // No tuner free: what is recording now, to offer from its start.
        .task(id: model.state.stopReason) {
            if model.state.status == .error, model.state.stopReason == "tuners_busy", recordings.available { await recordings.load(.now, force: true) }
        }
    }

    /// The reminder banner, unless Menu put it away.
    private var banner: LiveReminder? {
        guard let banner = extras.banner, banner.id != closedBanner else { return nil }
        return banner
    }

    /// Why the channel can't play, then what can be done, the first focused.
    private func problemSheet(_ problem: TVLiveProblem) -> some View {
        TVSheet {
            Text(problem.title).font(.tv(.title3)).foregroundStyle(Palette.text)
                .accessibilityAddTraits(.isHeader)
                .accessibilityIdentifier("live-problem-title")
            Text(problem.why).font(.tv(.body)).foregroundStyle(Palette.textSecondary)
            VStack(spacing: 12) {
                ForEach(Array(problem.rows.enumerated()), id: \.element.id) { index, row in
                    TVSheetRow(title: row.title, value: row.value) { perform(row.action) }
                        .focused($focused, equals: index == 0 ? "problem-first" : "problem-\(index)")
                        .accessibilityIdentifier(identifier(row.action))
                }
            }
            .padding(.top, 8)
            .focusSection()
        }
        .defaultFocus($focused, "problem-first", priority: .userInitiated)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("live-problem")
    }

    private func identifier(_ action: TVLiveProblem.Action) -> String {
        switch action {
        case .watch: "live-watch-recording"
        case .stopARecording: "live-stop-a-recording"
        case .tryAgain: "live-retry"
        case .otherChannels: "live-other-channels"
        case .backToGuide: "live-back"
        }
    }

    private func perform(_ action: TVLiveProblem.Action) {
        switch action {
        case .watch(let request):
            model.close()
            navigator.play(request)
        case .stopARecording:
            model.close()
            navigator.setPath([.liveRecordings], for: .liveTV)
            if navigator.tab != .liveTV { navigator.select(.liveTV) }
        case .tryAgain:
            model.retry()
        case .otherChannels:
            previewPanel = .channels
        case .backToGuide:
            backToGuide()
        }
    }

    /// Leaves for the guide, on the channel watched.
    private func backToGuide() {
        model.close()
        if navigator.tab != .liveTV {
            navigator.setPath([], for: .liveTV)
            navigator.select(.liveTV)
        }
    }
}

/// Over the picture: the banner for a few seconds after a channel change,
/// and with the transport bar; a spinner while tuning; Automatic's note when
/// it changes quality.
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
            if banner || model.transportBarVisible || standIn || state.status == .paused {
                TimelineView(.periodic(from: .now, by: 30)) { context in
                    TVLiveBanner(channel: model.listedChannel ?? state.channel, now: context.date, state: state, barUp: model.transportBarVisible)
                }
                .transition(.opacity)
            }
            if state.status == .tuning || state.status == .buffering {
                TVSpinner()
            }
            if let message = state.message ?? state.switchMessage {
                Text(message).font(.tv(.caption1, .semibold))
                    .padding(.horizontal, 28).padding(.vertical, 16)
                    .background(Color(hex: 0x18181B).opacity(0.92), in: RoundedRectangle(cornerRadius: 20))
                    .padding(.top, TVLayout.safeTop).frame(maxHeight: .infinity, alignment: .top)
                    .accessibilityIdentifier(state.message == nil ? "live-switch-message" : "live-message")
            }
        }
        .foregroundStyle(Palette.text)
        .animation(.easeOut(duration: 0.25), value: banner)
        .task(id: model.listings) { if model.listings { await model.live.followSchedule() } }
        .task(id: state.channel?.id) {
            banner = true
            guard (try? await Task.sleep(for: .seconds(6))) != nil else { return }
            banner = false
        }
        .allowsHitTesting(false)
    }
}

/// The banner at the foot of the picture: live (or behind, or paused) and the
/// channel, the programme, its episode, then how long is left in gold, its
/// times and what is next. Above the transport bar while the bar is up.
private struct TVLiveBanner: View {
    let channel: LiveChannel?
    let now: Date
    let state: LivePlayerState
    let barUp: Bool

    var body: some View {
        let programme = channel?.programme(at: now)
        let name = channel.map { "\($0.number) \($0.name)" } ?? "Live TV"
        VStack(alignment: .leading, spacing: 12) {
            TVKicker(text: "\(status) · \(name)", color: Palette.text, live: state.status != .paused && state.status != .tuning)
            Text(programme?.title ?? channel?.name ?? "Live TV")
                .font(.tv(.title3)).lineLimit(1)
                .accessibilityIdentifier("live-player-title")
            if let programme {
                if let line = programme.episodeLine {
                    Text(line).font(.tv(.body)).foregroundStyle(Palette.textSecondary).lineLimit(1)
                }
                FactsLine(lead: liveTimeLeft(programme, now: now), parts: [LiveClock.device.range(programme.start, programme.end)],
                          after: [channel?.programme(after: now).map { "Next: \($0.title)" }].compactMap { $0 })
            } else {
                Text("No programme listings").font(.tv(.body)).foregroundStyle(Palette.textSecondary)
            }
            if state.status == .paused {
                Text("Paused · \(liveTunerReleasesIn(seconds: state.pauseRemainingSeconds))")
                    .font(.tv(.body, .semibold)).foregroundStyle(Palette.gold)
            }
        }
        .frame(maxWidth: 1200, alignment: .leading)
        .padding(.horizontal, TVLayout.gutter)
        .padding(.bottom, barUp ? 300 : TVLayout.safeBottom)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomLeading)
        .background(LinearGradient(stops: [.init(color: .black.opacity(0.55), location: 0), .init(color: .clear, location: 0.22),
                                           .init(color: .clear, location: 0.46), .init(color: .black.opacity(0.88), location: 1)],
                                   startPoint: .top, endPoint: .bottom))
        .accessibilityElement(children: .contain)
    }

    /// "Live", "Behind live", "Paused" or "Tuning".
    private var status: String {
        switch state.status {
        case .tuning: "Tuning"
        case .paused: "Paused"
        default: state.isAtLiveEdge ? "Live" : "Behind live"
        }
    }
}

/// Live TV on the shared ``SystemPlayer``: AVKit keeps its familiar
/// transport, but seeking and file skips are off; Record, Channel down and
/// Channel up are its transport bar's buttons and Go live its contextual
/// action, Page Up, Page Down and the 123 key change channel, Channels and
/// Quality are its tabs, and picture in picture stays on.
private extension SystemPlayerBehavior where Action == TVLivePlayerAction {
    @MainActor static func live(_ model: TVLivePlayerModel, extras: LiveExtrasStore, recordings: LiveRecordingsStore) -> Self {
        Self(
            linear: true,
            pictureInPicture: PictureInPicture(
                possible: { model.pictureInPicturePossibleChanged($0) },
                changed: { model.pictureInPictureChanged(active: $0, appActive: $1) },
                canRestore: { model.controller.player?.currentItem != nil }
            ),
            infoTabs: { LivePanel.allCases.map { LiveOptionsController(model: model, panel: $0, extras: extras, recordings: recordings) } },
            uiAction: { item in
                let result = UIAction(title: item.title, image: UIImage(systemName: item.systemImage), identifier: .init("media.watchmy.live.\(item.kind.rawValue)")) { [weak model] _ in
                    model?.perform(item.kind)
                }
                result.subtitle = item.subtitle
                result.accessibilityLabel = item.accessibilityLabel
                return result
            },
            transportBarChanged: { model.transportBarChanged(visible: $0) },
            // Nothing moves: a press on the bar counts as input.
            seek: { old, _ in
                model.controller.noteUserInput()
                return old
            },
            presses: livePresses(model)
        )
    }

    /// Page Up and Page Down change channel; the 123 key (tvOS 18.1) opens Go to channel.
    @MainActor private static func livePresses(_ model: TVLivePlayerModel) -> [UIPress.PressType: @MainActor () -> Void] {
        var presses: [UIPress.PressType: @MainActor () -> Void] = [
            .pageUp: { [weak model] in model?.pageUp() },
            .pageDown: { [weak model] in model?.pageDown() },
        ]
        if #available(tvOS 18.1, *) {
            presses[.tvRemoteOneTwoThree] = { [weak model] in model?.openGoToChannel() }
        }
        return presses
    }
}

private final class LiveOptionsController: UIHostingController<LiveOptions> {
    init(model: TVLivePlayerModel, panel: LivePanel, extras: LiveExtrasStore, recordings: LiveRecordingsStore) {
        super.init(rootView: LiveOptions(model: model, panel: panel, extras: extras, recordings: recordings))
        title = panel.title
        preferredContentSize = CGSize(width: 1920, height: LiveOptions.height)
        view.backgroundColor = .clear
    }
    @available(*, unavailable)
    @MainActor required dynamic init?(coder: NSCoder) { fatalError("not used") }
}

/// The Channels and Quality tabs. Channels opens on the channel being
/// watched, with Last channel and Go to channel to its left and the others
/// to its right; Don't record it comes first while a recording is about to
/// take the tuner, and a reminder due now comes first among the channels.
private struct LiveOptions: View {
    let model: TVLivePlayerModel
    let panel: LivePanel
    /// Hosted by AVKit outside the app's environment, so handed in.
    let extras: LiveExtrasStore
    let recordings: LiveRecordingsStore
    @FocusState private var focus: String?
    @State private var takeoverError: String?

    static let height: CGFloat = 440

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
        VStack(alignment: .leading, spacing: 18) {
            // AVKit names the tab; this names the panel for VoiceOver and the UI tests.
            Text(panel.title).font(.tv(.caption2, .bold)).foregroundStyle(.clear).frame(height: 1)
                .accessibilityIdentifier("live-panel-\(panel.rawValue)")
            if panel == .channels, let takeover = model.cancellableTakeover {
                takeoverRow(takeover)
            }
            if panel == .channels {
                // Asked again as time passes and when the banner comes, so a reminder that comes due moves first.
                TimelineView(.periodic(from: .now, by: 15)) { context in
                    channelCards(extras.banner == nil ? due : due, now: context.date)
                }
            } else {
                quality
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 40).padding(.vertical, 24)
        .frame(height: Self.height)
        .background(Color(hex: 0x1E1E22).opacity(0.98), in: RoundedRectangle(cornerRadius: 32))
        .defaultFocus($focus, initialFocus, priority: .userInitiated)
        .onAppear {
            // AVKit and the stand-in host can already have focus when this panel
            // opens. Request its entry target once; a later warning never moves it.
            focus = initialFocus
        }
    }

    private var quality: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(model.state.qualities.contains(.auto)
                 ? "Automatic adjusts to this connection. A fixed quality tunes this channel again, at live."
                 : "Changing quality tunes this channel again, at live.")
                .font(.tv(.body)).foregroundStyle(Palette.textSecondary)
            HStack(spacing: 24) {
                ForEach(model.state.qualities, id: \.self) { quality in
                    Button(quality.title) { model.pickQuality(quality) }
                        .buttonStyle(TVChipStyle(selected: quality == model.state.quality))
                        .focused($focus, equals: quality.rawValue)
                        .accessibilityIdentifier("live-quality-option-\(quality.rawValue)")
                }
            }
            .padding(.vertical, 12).frame(maxWidth: .infinity, alignment: .leading).focusSection()
            // What is playing, a press away rather than on the picture.
            Text("Now \(model.state.qualityLabel)").font(.tv(.caption1)).foregroundStyle(Palette.muted)
                .accessibilityIdentifier("live-player-quality")
        }
    }

    /// The takeover banner's action, which the banner itself can't take focus for.
    private func takeoverRow(_ takeover: LiveRecordingTakeover) -> some View {
        HStack(spacing: 24) {
            VStack(alignment: .leading, spacing: 4) {
                Text(takeover.message()).font(.tv(.body, .semibold)).foregroundStyle(Palette.warning)
                Text(takeoverError ?? takeover.title).font(.tv(.caption2)).foregroundStyle(takeoverError == nil ? Palette.muted : Palette.warning)
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
            .buttonStyle(TVChipStyle(selected: false))
            .disabled(recordings.changing.contains(takeover.recordingID))
            .focused($focus, equals: "takeover")
            .accessibilityIdentifier("live-takeover-cancel")
        }
        .frame(maxWidth: .infinity).focusSection()
    }

    /// Last channel and Go to channel, then the channels from the one being watched.
    private func channelCards(_ due: (reminder: LiveReminder, channel: LiveChannel)?, now: Date) -> some View {
        ScrollViewReader { reader in
            ScrollView(.horizontal) {
                HStack(alignment: .top, spacing: TVLayout.cardSpacing) {
                    ForEach(model.channelsPanelActions(now: now)) { action in
                        Button { model.perform(action.kind) } label: {
                            LiveTile(title: action.kind == .lastChannel ? (action.channel ?? action.title) : action.title,
                                     line: action.kind == .lastChannel ? action.title : action.subtitle ?? "",
                                     lineFirst: action.kind == .lastChannel)
                        }
                        .buttonStyle(TVLiveCardButtonStyle())
                        .focused($focus, equals: action.kind.rawValue)
                        .accessibilityLabel(action.accessibilityLabel)
                        .accessibilityIdentifier("live-\(action.kind.rawValue)")
                    }
                    ForEach(channels) { channel in
                        Group {
                            if model.listings {
                                Button { model.switchChannel(channel) } label: {
                                    let reminder = due?.channel.id == channel.id ? due?.reminder : nil
                                    TVLiveOnNowCard(channel: channel, now: now, watching: channel.id == model.state.channel?.id, width: TVCardWidth.wide,
                                                    reminder: reminder, reminderProgramme: reminder.flatMap(extras.programme(for:)))
                                }
                                .buttonStyle(TVLiveCardButtonStyle())
                            } else {
                                Button { model.switchChannel(channel) } label: {
                                    TVLiveChannelCard(channel: channel)
                                        .overlay(alignment: .topTrailing) {
                                            if channel.id == model.state.channel?.id { TVBadge("Watching", style: .gold).padding(12) }
                                        }
                                }
                                .buttonStyle(TVLiveCardButtonStyle())
                            }
                        }
                        .focused($focus, equals: channel.id)
                        .disabled(!channel.available)
                        .id(channel.id)
                        .accessibilityIdentifier("live-channel-option-\(channel.id)")
                    }
                }
                .padding(.vertical, 24)
            }
            .contentMargins(.horizontal, 8, for: .scrollContent)
            .scrollClipDisabled()
            .scrollIndicators(.hidden)
            .focusSection()
            // The channel being watched in sight with Last channel and Go to channel left of it, where the list allows.
            .onAppear {
                if let id = model.state.channel?.id, (channels.firstIndex { $0.id == id } ?? 0) > 1 { reader.scrollTo(id, anchor: .center) }
            }
        }
    }
}

/// A tile in Channels before the channels: Last channel over the channel it
/// goes back to, or Go to channel over "By its number".
private struct LiveTile: View {
    let title: String
    let line: String
    /// The line goes above the title (Last channel).
    var lineFirst = false
    @Environment(\.isFocused) private var focused

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if lineFirst { lineText }
            Text(title).font(.tv(.callout)).foregroundStyle(focused ? Palette.ground : Palette.text).lineLimit(2)
            if !lineFirst { lineText }
        }
        .padding(.horizontal, 28)
        .frame(width: TVCardWidth.wide, height: 180, alignment: .leading)
        .background(focused ? Palette.text : Palette.surfaceRaised, in: RoundedRectangle(cornerRadius: 14))
        .overlay { if !focused { RoundedRectangle(cornerRadius: 14).strokeBorder(Color(hex: 0x34343A), lineWidth: 2) } }
        .focusLift(focused, scale: 1.08)
    }

    private var lineText: some View {
        Text(line).font(lineFirst ? .tv(.caption1) : .tv(.caption2)).foregroundStyle(focused ? Color.tvOnWhiteMuted : Palette.muted).lineLimit(1)
    }
}
