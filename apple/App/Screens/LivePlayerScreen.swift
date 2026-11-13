import SwiftUI
import UIKit
import WMMCast
#if DEBUG
import WMMFakes
#endif
import WMMFeatures
import WMMModel
import WMMUI

/// A dedicated live surface: no file scrubber, progress or next episode.
/// It is drawn inside the players' shell (``PlayerShell``), so its controls
/// hide by themselves as the film player's do and the status bar and home
/// indicator stay off the picture (PLAN-APPLE-REVIEW.md §3.3.1, §5.3).
struct LivePlayerScreen: View {
    let request: LivePlayRequest
    @Environment(AppServices.self) private var services
    @Environment(Navigator.self) private var navigator
    @State private var model: LivePlayerModel?

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if let model { LivePlayerContent(model: model) }
            else { ProgressView("Opening live channel") }
        }
        .onAppear {
            guard model == nil, let factory = services.livePlayback else { return }
            let model = LivePlayerModel(request: request, repository: services.liveTV, controller: factory.make(), onExit: { navigator.closePlayer() })
            self.model = model
            model.start()
            #if DEBUG
            // Screenshots and UI tests: the controls stay up, as in the film player.
            if ProcessInfo.processInfo.arguments.contains("-playerKeepControls") { model.controls.hold("kept", true) }
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
    }
}

private struct LivePlayerContent: View {
    let model: LivePlayerModel
    @Environment(Navigator.self) private var navigator
    @Environment(\.horizontalSizeClass) private var sizeClass
    @Environment(LiveRecordingsStore.self) private var recordings
    /// The takeover warning OK'd, by its recording.
    @State private var dismissedTakeover: String?
    @State private var takeoverError: String?

    private var state: LivePlayerState { model.state }
    private var stopped: Bool { state.status == .stopped || state.status == .error }

    var body: some View {
        PlayerShell(model: model, orientations: .allButUpsideDown) { frame in
            content(frame)
        }
        .task(id: model.listings) { if model.listings { await model.followSchedule() } }
    }

    private func content(_ frame: PlayerFrame) -> some View {
        let portrait = frame.tall
        let pip = frame.pictureInPicture
        return VStack(spacing: 0) {
                video(frame, portrait: portrait, pip: pip)
                    .frame(height: portrait ? frame.size.width * 9 / 16 : frame.size.height)
                if portrait {
                    if let channel = model.listedChannel {
                        TimelineView(.periodic(from: .now, by: 30)) { context in
                            VStack(alignment: .leading, spacing: 0) {
                                LiveNowBanner(channel: channel, now: context.date, wide: sizeClass.isWide, cellular: state.cellular) {
                                    HStack(spacing: 10) {
                                        Button { model.close(); navigator.select(.liveTV) } label: { Label("Guide", systemImage: "rectangle.split.3x1") }
                                            .buttonStyle(GhostButtonStyle(height: 44, fullWidth: !sizeClass.isWide))
                                            .accessibilityHint("Stops watching and opens the guide")
                                        qualityMenu.menuStyle(.button).buttonStyle(GhostButtonStyle(height: 44, fullWidth: !sizeClass.isWide))
                                    }
                                }
                                .padding(16)
                                Divider().overlay(Palette.separator)
                                HStack(alignment: .firstTextBaseline) {
                                    Text(liveOnNowHeading(now: context.date)).font(.headline)
                                    Spacer()
                                }
                                .padding(.horizontal, 16).padding(.vertical, 12)
                            }
                        }
                    } else {
                        VStack(alignment: .leading, spacing: 12) {
                            identity
                            HStack {
                                Text("Channels").font(.headline)
                                Spacer()
                                qualityMenu
                            }
                        }.padding(16)
                    }
                    channelList
                }
            }
            // Under the camera housing on a tall screen; a full-screen picture goes edge to edge.
            .padding(.top, portrait ? frame.safeArea.top : 0)
            .background(portrait ? Palette.ground : .black)
            .overlay(alignment: .trailing) {
                if model.channelsOpen && !portrait && !pip.isActive {
                    VStack(alignment: .leading, spacing: 12) {
                        HStack {
                            if model.listings {
                                TimelineView(.periodic(from: .now, by: 30)) { context in
                                    Text(liveOnNowHeading(now: context.date)).font(.title3.bold())
                                }
                                Spacer()
                                Button("Full guide") { model.close(); navigator.select(.liveTV) }
                                    .font(.subheadline.weight(.semibold)).foregroundStyle(Palette.gold).frame(minHeight: 44)
                            } else {
                                Text("Channels").font(.title2.bold())
                                Spacer()
                            }
                            Button { model.channelsOpen = false } label: { Image(systemName: "xmark").frame(width: 44, height: 44) }.accessibilityLabel("Close channels")
                        }.padding(.horizontal, 16).padding(.top, 8 + frame.safeArea.top)
                        channelList
                    }
                    .padding(.trailing, frame.safeArea.trailing)
                    .frame(width: min(380, frame.size.width * 0.48) + frame.safeArea.trailing)
                    .background(Palette.ground.opacity(0.97))
                }
            }
            .overlay { if state.askStillWatching { stillWatching } }
            .foregroundStyle(Palette.text)
            .animation(.easeInOut(duration: 0.2), value: model.showsControls)
    }

    private func video(_ frame: PlayerFrame, portrait: Bool, pip: PictureInPicture) -> some View {
        let insets = frame.insets(portrait: portrait)
        let metrics = frame.metrics
        return ZStack {
            PlayerVideo(player: model.controller.player, backdrop: nil, seed: state.channel?.name ?? "Live TV", pictureInPicture: pip)
            Color.clear.contentShape(Rectangle()).onTapGesture { model.tap() }.accessibilityHidden(true)
            if stopped { stoppedPrompt }
            else if model.showsControls && !pip.isActive {
                VStack(spacing: 12) {
                    HStack(spacing: 10) {
                        RoundIconButton(systemImage: "xmark", label: "Stop and close live TV", size: metrics.button, iconSize: metrics.buttonIcon) { model.close() }
                        if !portrait {
                            let programme = model.listedChannel?.programme(at: .now)
                            Text([state.channel?.title ?? "Live TV", programme?.title].compactMap(\.self).joined(separator: " · "))
                                .font(.headline).lineLimit(1)
                        }
                        Spacer()
                        CastLiveHandoverButton(model: model)
                        AirPlayButton()
                            .frame(width: metrics.button, height: metrics.button)
                            .background(PlayerStyle.scrim, in: Circle())
                            .accessibilityLabel("AirPlay")
                        if pip.isPossible {
                            RoundIconButton(systemImage: "pip.enter", label: "Picture in Picture", size: metrics.button, iconSize: metrics.buttonIcon) {
                                model.input()
                                pip.toggle()
                            }
                        }
                    }
                    Spacer(minLength: 0)
                    if state.status == .tuning || state.status == .buffering {
                        ProgressView(state.status == .tuning ? "Tuning channel" : "Buffering").tint(.white)
                    } else {
                        HStack(spacing: portrait ? 32 : metrics.transportSpacing) {
                            RoundIconButton(systemImage: "chevron.down", label: "Previous channel", size: metrics.seekButton, iconSize: 24) { model.previousChannel() }
                            RoundIconButton(systemImage: state.status == .paused ? "play.fill" : "pause.fill", label: state.status == .paused ? "Play live TV" : "Pause live TV",
                                            size: portrait ? 56 : metrics.playButton, iconSize: portrait ? 32 : metrics.playIcon, background: .clear) { model.togglePlayPause() }
                                .accessibilityIdentifier("live-play-pause")
                            RoundIconButton(systemImage: "chevron.up", label: "Next channel", size: metrics.seekButton, iconSize: 24) { model.nextChannel() }
                        }
                    }
                    Spacer(minLength: 0)
                    if !portrait, !model.channelsOpen, let channel = model.listedChannel {
                        TimelineView(.periodic(from: .now, by: 30)) { context in
                            LivePlayerBanner(channel: channel, now: context.date)
                        }
                    }
                    HStack(spacing: 16) {
                        if state.isAtLiveEdge && state.status != .paused {
                            Label("LIVE", systemImage: "circle.fill").font(.caption.bold()).foregroundStyle(Palette.gold)
                        } else {
                            Button("Go live") { model.goLive() }.font(.subheadline.bold()).foregroundStyle(Palette.gold)
                        }
                        if state.status == .paused {
                            Text(liveTunerReleasesIn(seconds: state.pauseRemainingSeconds))
                                .font(.caption).foregroundStyle(Palette.textSecondary)
                        }
                        Spacer(minLength: 0)
                        if !portrait {
                            Button("Channels") { model.toggleChannels() }.font(.subheadline.bold())
                            qualityMenu
                        }
                        RoundIconButton(systemImage: portrait ? "arrow.up.left.and.arrow.down.right" : "arrow.down.right.and.arrow.up.left", label: portrait ? "Full screen" : "Portrait view",
                                        size: metrics.button, iconSize: metrics.buttonIcon) {
                            model.input()
                            OrientationLock.allow(portrait ? .landscape : .portrait)
                        }
                    }
                }
                .padding(.leading, portrait ? 12 : insets.leading)
                .padding(.trailing, portrait ? 12 : insets.trailing)
                .padding(.top, portrait ? 12 : insets.top)
                .padding(.bottom, portrait ? 12 : insets.bottom)
                .background(LinearGradient(colors: [.black.opacity(0.65), .clear, .black.opacity(0.65)], startPoint: .top, endPoint: .bottom).allowsHitTesting(false))
                .transition(.opacity)
            }
            if let takeover = state.takeover, !stopped, dismissedTakeover != takeover.recordingID {
                takeoverBanner(takeover)
            }
            if let message = state.message, !stopped {
                Text(message).font(.caption).padding(10).background(.black.opacity(0.85), in: Capsule())
                    .padding(.top, 54).frame(maxHeight: .infinity, alignment: .top).allowsHitTesting(false)
            }
            if let message = state.switchMessage, !stopped {
                Text(message).font(.caption).padding(10).background(.black.opacity(0.85), in: Capsule())
                    .padding(.bottom, 52).frame(maxHeight: .infinity, alignment: .bottom).allowsHitTesting(false)
                    .accessibilityIdentifier("live-switch-message")
            }
        }
        .clipped()
    }

    private var identity: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(state.channel?.title ?? "Live TV").font(.title3.bold())
            Text("No programme listings").font(.caption).foregroundStyle(Palette.muted)
            if state.cellular {
                Text("Live TV is limited to 720p on mobile data.").font(.caption).foregroundStyle(Palette.muted)
            }
        }
    }

    private var qualityMenu: some View {
        Menu {
            ForEach(state.qualities, id: \.self) { quality in
                Button { model.selectQuality(quality) } label: {
                    if quality == state.quality { Label(quality == .auto ? state.qualityLabel : quality.title, systemImage: "checkmark") }
                    else { Text(quality.title) }
                }
            }
            if state.qualities.contains(.auto) { Text("Automatic adjusts to this connection. Fixed quality tunes again at live.") }
            if state.cellular { Text("720p maximum on mobile data") }
            // Open: the controls stay, so the menu keeps its button.
            Color.clear.frame(width: 0, height: 0)
                .onAppear { model.qualityMenuOpen = true }
                .onDisappear { model.qualityMenuOpen = false }
        } label: { Label("Quality · \(state.qualityLabel)", systemImage: "slider.horizontal.3").font(.subheadline) }
        .frame(minHeight: 44).accessibilityIdentifier("live-quality")
    }

    private var channelList: some View {
        ScrollView {
            LazyVStack(spacing: 0) {
                ForEach(model.channels) { channel in
                    let selected = !stopped && state.channel?.id == channel.id
                    if model.listings {
                        TimelineView(.periodic(from: .now, by: 30)) { context in
                            LiveChannelNowRow(channel: channel, now: context.date, watching: selected) {
                                model.switchChannel(channel); model.channelsOpen = false
                            }
                        }
                    } else {
                        LiveChannelRow(channel: channel, selected: selected) {
                            model.switchChannel(channel); model.channelsOpen = false
                        }
                    }
                    Divider().overlay(Palette.separator)
                }
            }
        }
        .accessibilityIdentifier("live-player-channels")
    }

    /// "At 9:00 PM a recording needs this tuner. Watching stops then." with OK, and
    /// Don't record it for whoever set it and admins. Only its own buttons take taps;
    /// the rest of the video still shows and hides the controls.
    private func takeoverBanner(_ takeover: LiveRecordingTakeover) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            VStack(alignment: .leading, spacing: 4) {
                Text(takeover.message()).font(.subheadline.weight(.semibold)).foregroundStyle(Palette.warning)
                Text(takeover.title).font(.footnote).foregroundStyle(Palette.textSecondary)
                if let takeoverError { Text(takeoverError).font(.footnote).foregroundStyle(Palette.warning).accessibilityIdentifier("live-takeover-error") }
            }
            .allowsHitTesting(false)
            HStack(spacing: 20) {
                if takeover.canCancel {
                    Button("Don’t record it") {
                        takeoverError = nil
                        Task {
                            if await recordings.cancelTakeover(takeover) { dismissedTakeover = takeover.recordingID }
                            else { takeoverError = recordings.message ?? "Couldn’t stop the recording. Try again." }
                        }
                    }
                    .disabled(recordings.changing.contains(takeover.recordingID))
                    .accessibilityIdentifier("live-takeover-cancel")
                }
                Button("OK") { dismissedTakeover = takeover.recordingID }.accessibilityIdentifier("live-takeover-ok")
            }
            .font(.subheadline.weight(.semibold)).foregroundStyle(Palette.gold).frame(minHeight: 44)
        }
        .padding(14)
        .background { RoundedRectangle(cornerRadius: 12).fill(Palette.sheet).allowsHitTesting(false) }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("live-recording-takeover")
        .padding(.horizontal, 24).padding(.top, 65)
        .frame(maxWidth: 640, maxHeight: .infinity, alignment: .top)
    }

    private var stoppedPrompt: some View {
        VStack(spacing: 12) {
            Text(state.stoppedTitle).font(.headline)
            Text(state.stoppedMessage)
                .font(.subheadline).multilineTextAlignment(.center)
            // Side by side where they fit, else one above another (the portrait video is narrow).
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 24) { stoppedButtons }
                VStack(spacing: 10) { stoppedButtons }
            }
            .buttonStyle(.borderedProminent).tint(Palette.burgundy)
        }
        .padding(24).frame(maxWidth: 480)
        .background(Palette.ground.opacity(0.96), in: RoundedRectangle(cornerRadius: 20))
    }

    @ViewBuilder private var stoppedButtons: some View {
        // Only once a recording took the tuner (reason "recording"), not for a warning before it.
        if let taken = state.stoppedForRecording {
            Button("Watch it from the start") { model.close(); navigator.play(.init(fileID: 0, startMs: 0, title: taken.title, recordingID: taken.recordingID)) }
                .accessibilityIdentifier("live-watch-recording")
        }
        Button(state.status == .error ? "Try again" : "Watch live") { model.retry() }
        Button("Channels") { model.close() }
    }

    private var stillWatching: some View {
        ZStack {
            Color.black.opacity(0.8).ignoresSafeArea()
            VStack(spacing: 18) {
                Text("Are you still watching?").font(.title2.bold())
                Text("The tuner will be released in \(state.stillWatchingSeconds) seconds.").multilineTextAlignment(.center)
                Button("Keep watching") { model.answerStillWatching(keepWatching: true) }
                    .buttonStyle(.borderedProminent).tint(Palette.burgundy)
                Button("Stop watching") { model.answerStillWatching(keepWatching: false) }
            }.padding(24).frame(maxWidth: 480)
        }
    }

}

/// The programme under the video (`LiveWatch`, `Pad2LiveWatchListings`): the
/// channel with its logo and LIVE, the picture, title, episode line, tags,
/// times and progress, what is next, and on iPad the description.
struct LiveNowBanner<Actions: View>: View {
    let channel: LiveChannel
    let now: Date
    let wide: Bool
    let cellular: Bool
    @ViewBuilder let actions: () -> Actions

    var body: some View {
        let programme = channel.programme(at: now)
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 10) {
                LivePictureView(programme: nil, channel: channel).frame(width: 40, height: 40).clipShape(RoundedRectangle(cornerRadius: 8))
                Text(channel.number).font(.title3.weight(.bold)).monospacedDigit()
                Text(channel.name).font(.title3.weight(.semibold)).lineLimit(1)
                if wide { LiveBadge() }
                Spacer(minLength: 0)
                if wide { actions() } else { LiveBadge() }
            }
            .accessibilityElement(children: .combine)
            .accessibilityLabel("\(channel.number) \(channel.name), live")
            if let programme {
                HStack(alignment: .top, spacing: 14) {
                    LivePictureView(programme: programme, channel: channel)
                        .frame(width: wide ? 172 : 120, height: wide ? 97 : 68)
                        .clipShape(RoundedRectangle(cornerRadius: 8))
                    VStack(alignment: .leading, spacing: 4) {
                        Text(programme.title).font(.title3.weight(.bold)).lineLimit(2)
                            .accessibilityIdentifier("live-banner-title")
                        if let line = programme.episodeLine { Text(line).font(.subheadline).foregroundStyle(Palette.textSecondary).lineLimit(1) }
                        HStack(spacing: 6) {
                            if !programme.tags.isEmpty { LiveTags(programme.tags, size: 10) }
                            Text(liveTimeLine(programme, now: now)).font(.footnote).foregroundStyle(Palette.muted).lineLimit(1)
                        }
                        if wide {
                            LiveProgressBar(progress: programme.progress(at: now)).padding(.top, 4)
                            if let next = channel.programme(after: now) {
                                Text(liveNextLine(next)).font(.footnote).foregroundStyle(Palette.muted)
                            }
                        }
                    }
                }
                if !wide {
                    LiveProgressBar(progress: programme.progress(at: now))
                    if let next = channel.programme(after: now) {
                        Text(liveNextLine(next)).font(.footnote).foregroundStyle(Palette.muted)
                    }
                }
                if wide, let description = programme.description {
                    Text(description).font(.subheadline).foregroundStyle(Palette.textSecondary).lineLimit(3)
                }
            } else {
                Text("No listings for this channel.").font(.subheadline).foregroundStyle(Palette.muted)
            }
            if cellular {
                Text("Live TV is limited to 720p on mobile data.").font(.caption).foregroundStyle(Palette.muted)
            }
            if !wide { actions() }
        }
        .foregroundStyle(Palette.text)
    }
}

/// The full-screen player's banner (`LivePlayer`): logo and number, picture, title, tags, episode line, times and Next.
struct LivePlayerBanner: View {
    let channel: LiveChannel
    let now: Date

    var body: some View {
        let programme = channel.programme(at: now)
        HStack(spacing: 14) {
            VStack(spacing: 4) {
                LiveLogo(url: channel.logoURL) { Color.clear }.frame(width: 44, height: 22)
                Text(channel.number).font(.title3.weight(.bold)).monospacedDigit()
                Text(channel.name).font(.caption2).foregroundStyle(Palette.muted).lineLimit(1)
            }
            .frame(width: 74)
            if let programme {
                LivePictureView(programme: programme, channel: channel)
                    .frame(width: 120, height: 68).clipShape(RoundedRectangle(cornerRadius: 8))
                VStack(alignment: .leading, spacing: 3) {
                    HStack(spacing: 6) {
                        Text(programme.title).font(.headline).lineLimit(1)
                        if !programme.tags.isEmpty { LiveTags(programme.tags, size: 10) }
                    }
                    if let line = programme.episodeLine { Text(line).font(.footnote).foregroundStyle(Palette.textSecondary).lineLimit(1) }
                    Text(liveTimeLine(programme, now: now)).font(.footnote).foregroundStyle(Palette.muted).lineLimit(1)
                    LiveProgressBar(progress: programme.progress(at: now))
                    if let next = channel.programme(after: now) {
                        Text(liveNextLine(next)).font(.caption).foregroundStyle(Palette.muted).lineLimit(1)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            } else {
                Text("No listings for this channel.").font(.subheadline).foregroundStyle(Palette.muted)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .padding(12)
        .frame(maxWidth: 560)
        .background(.black.opacity(0.72), in: RoundedRectangle(cornerRadius: 14))
        .accessibilityElement(children: .combine)
    }
}

/// A channel in the player's On now list: picture with logo and number, title, progress, channel and time left.
struct LiveChannelNowRow: View {
    let channel: LiveChannel
    let now: Date
    let watching: Bool
    let action: () -> Void

    var body: some View {
        let programme = channel.programme(at: now)
        Button(action: action) {
            HStack(spacing: 14) {
                LivePictureView(programme: programme, channel: channel)
                    .frame(width: 96, height: 54)
                    .overlay(alignment: .topLeading) { if programme != nil { LiveChannelChip(channel: channel, size: 10).padding(4) } }
                    .clipShape(RoundedRectangle(cornerRadius: 8))
                VStack(alignment: .leading, spacing: 4) {
                    Text(programme?.title ?? channel.name).font(.body.weight(.semibold)).lineLimit(1)
                    if let programme {
                        LiveProgressBar(progress: programme.progress(at: now))
                        Text("\(channel.name) · \(liveTimeLeft(programme, now: now))").font(.caption).foregroundStyle(Palette.muted).lineLimit(1)
                    } else {
                        Text(channel.available ? "No listings" : (channel.reason.map(LiveTVError.message) ?? "Channel unavailable"))
                            .font(.caption).foregroundStyle(Palette.muted).lineLimit(1)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                if watching { Text("Watching").font(.subheadline.weight(.semibold)).foregroundStyle(Palette.gold) }
            }
            .foregroundStyle(Palette.text)
            .padding(.horizontal, 16).padding(.vertical, 10)
            .background(watching ? Palette.gold.opacity(0.08) : .clear)
            .overlay(alignment: .leading) { if watching { Rectangle().fill(Palette.gold).frame(width: 3) } }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!channel.available)
        .accessibilityLabel("\(channel.number) \(channel.name), \(programme.map { "\($0.title), \(liveTimeLeft($0, now: now))" } ?? "No listings")\(watching ? ", playing" : "")")
        .accessibilityIdentifier("live-channel-\(channel.number)")
    }
}
