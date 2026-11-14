import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// The full-screen player, landscape while it shows: the video, subtitles,
/// the touch controls, Skip, the Next Episode card, the picker, Still
/// watching and the stopped and can't-play states (design/mockups/ios/
/// Player*.dc.html). It renders a ``PlaybackController`` from the app's
/// services, which does the playing (PLAN-CLIENTS.md §2.3), through
/// ``PlayerChromeModel``, which decides what shows. On iPad the controls are
/// the tablet sizes, options open beside the video, and portrait puts the
/// season below it. The same player surface survives rotation.
struct PlayerScreen: View {
    let request: PlayRequest
    @Environment(AppServices.self) private var services
    @Environment(Navigator.self) private var navigator
    @Environment(\.horizontalSizeClass) private var horizontal
    @Environment(\.verticalSizeClass) private var vertical
    @State private var model: PlayerChromeModel?
    @State private var episodes: PlayerEpisodesModel?

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if let model, let episodes { PlayerContent(model: model, episodes: episodes) }
        }
        .onAppear(perform: begin)
        .onDisappear { model?.dispose() }
    }

    private func begin() {
        guard model == nil else { return }
        let navigator = navigator
        var controller = services.playback.make()
        #if DEBUG
        controller = PlayerLaunchArguments.wrap(controller)
        #endif
        let model = PlayerChromeModel(controller: controller, request: request, thumbnails: SeekThumbnails(images: services.images), onExit: { navigator.closePlayer() })
        self.model = model
        episodes = PlayerEpisodesModel(library: services.library)
        model.start()
        #if DEBUG
        PlayerLaunchArguments.apply(to: model, pausesPicker: !PlayerMetrics.for(horizontal: horizontal, vertical: vertical).sidePanelPicker)
        #endif
    }
}

/// The screen around the video, inside the players' shell. It reads the
/// model's steady state, which changes when something other than the
/// position does; what ticks is read only by the small views that draw it
/// (``PlayerClock``).
private struct PlayerContent: View {
    let model: PlayerChromeModel
    let episodes: PlayerEpisodesModel
    @State private var showsEpisodes = false
    @Environment(AppServices.self) private var services
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.horizontalSizeClass) private var horizontal
    @Environment(\.verticalSizeClass) private var vertical

    var body: some View {
        let metrics = PlayerMetrics.for(horizontal: horizontal, vertical: vertical)
        PlayerShell(model: model, orientations: .landscape) { frame in
            content(frame)
        }
        .task(id: "\(model.request.fileID)-\(metrics.sidePanelPicker)") {
            if metrics.sidePanelPicker { await episodes.load(for: model.request) }
        }
        .onChange(of: metrics.sidePanelPicker) { _, tablet in
            if !tablet { showsEpisodes = false }
        }
        .onChange(of: showsEpisodes) { _, open in
            model.hold("panel", open)
            model.showControls()
        }
        .onChange(of: model.overlay) { _, overlay in
            if overlay != nil { showsEpisodes = false }
        }
    }

    private func content(_ frame: PlayerFrame) -> some View {
        let state = model.state
        let metrics = frame.metrics
        let pictureInPicture = frame.pictureInPicture
        let portrait = metrics.sidePanelPicker && frame.tall
        let insets = frame.insets(portrait: portrait)
        return ZStack(alignment: .top) {
            VStack(spacing: 0) {
                video(in: frame, insets: insets, portrait: portrait)
                    .frame(height: portrait ? frame.size.width * 9 / 16 : frame.size.height)
                    .clipped()
                if portrait {
                    PlayerPortraitDetails(model: model, episodes: episodes)
                }
            }
            if !pictureInPicture.isActive {
                overlay(state, insets: insets, metrics: metrics)
                if showsEpisodes, model.overlay == nil {
                    PlayerEpisodesPanel(model: model, episodes: episodes) { showsEpisodes = false }
                }
            }
        }
        .animation(.easeInOut(duration: 0.2), value: model.showsControls)
        .animation(.easeInOut(duration: 0.2), value: model.showsUpNext)
        .animation(.easeInOut(duration: 0.2), value: model.showsSkip)
        .animation(.easeOut(duration: 0.25), value: model.overlay)
        .animation(.easeOut(duration: 0.2), value: model.ripple)
        .animation(.easeInOut(duration: 0.25), value: state.qualityNote)
        // A tick for each double tap that seeks.
        .sensoryFeedback(.impact(weight: .light), trigger: model.ripple) { _, burst in burst != nil }
    }

    /// Slides in from `edge`, or only fades with Reduce Motion.
    private func slide(_ edge: Edge) -> AnyTransition {
        reduceMotion ? .opacity : .opacity.combined(with: .move(edge: edge))
    }

    private func video(in frame: PlayerFrame, insets: PlayerInsets, portrait: Bool) -> some View {
        let state = model.state
        let metrics = frame.metrics
        let pictureInPicture = frame.pictureInPicture
        return ZStack {
                PlayerVideo(
                    player: model.controller.player,
                    backdrop: services.library.imageURL(state.backdropPath ?? model.request.backdropPath, size: .w1280),
                    seed: state.title.isEmpty ? model.request.title : state.title,
                    pictureInPicture: pictureInPicture,
                    layout: frame.videoLayout
                )

                if !pictureInPicture.isActive {
                    PlayerCues(clock: model.clock, bottomPadding: model.showsControls ? insets.bottom + (portrait ? 56 : metrics.skipLift) + 8 : insets.bottom + 8, sidePadding: insets.leading)

                    // Taps on the video: show or hide the controls; double taps on a side seek;
                    // a pinch fills or fits.
                    Color.clear
                        .contentShape(Rectangle())
                        .onTapGesture(coordinateSpace: .local) { model.tap(x: $0.x, width: frame.fullWidth) }
                        .pinchToFit(frame, area: frame.videoArea(portrait: portrait))
                        .accessibilityHidden(true)

                    if let ripple = model.ripple {
                        SeekRipple(burst: ripple)
                            .frame(width: frame.size.width * 0.4)
                            .frame(maxWidth: .infinity, alignment: ripple.zone == .back ? .leading : .trailing)
                            .transition(.opacity)
                    }

                    if model.showsControls {
                        PlayerControlsView(model: model, pictureInPicture: pictureInPicture, insets: insets, metrics: metrics, portrait: portrait, onEpisodes: episodes.season == nil && episodes.error == nil ? nil : { showsEpisodes = true },
                                           fitButton: VideoFitButton(frame: frame, area: frame.videoArea(portrait: portrait), metrics: metrics) { model.showControls() })
                            .transition(.opacity)
                    } else if model.isWaiting, model.overlay == nil {
                        SpinnerRing()
                    }

                    if let skip = state.skip, model.showsSkip {
                        SkipButton(prompt: skip, clock: model.clock, metrics: metrics) { model.skip() }
                            .padding(.trailing, insets.trailing)
                            .padding(.bottom, model.showsControls ? insets.bottom + (portrait ? 56 : metrics.skipLift) : insets.bottom)
                            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
                            .transition(.opacity)
                    }

                    if model.showsMiniProgress {
                        ClockMiniProgress(clock: model.clock)
                            .opacity(model.overlay == .stillWatching ? 0.7 : 1)
                            .padding(.leading, insets.leading)
                            .padding(.bottom, max(insets.bottom - 2, 16))
                            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomLeading)
                            .transition(.opacity)
                    }

                    if let upNext = state.upNext, model.showsUpNext {
                        NextEpisodeCard(
                            prompt: upNext,
                            clock: model.clock,
                            width: metrics.sidePanelPicker ? min(400, frame.size.width * 0.48) : 320,
                            still: services.library.imageURL(upNext.next.stillPath, size: .w342),
                            onPlay: { model.playNext() },
                            onWatchCredits: { model.watchCredits() }
                        )
                        .padding(.trailing, insets.trailing)
                        .padding(.bottom, max(insets.bottom - 2, 16))
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
                        .transition(slide(.trailing))
                    }

                    if let note = state.qualityNote, model.overlay == nil {
                        QualityNote(text: note, large: metrics.streamChip)
                            .padding(.top, insets.top + metrics.button + (metrics.streamChip ? 52 : 16))
                            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                            .allowsHitTesting(false)
                            .transition(.opacity)
                    }

                }
            }
    }

    @ViewBuilder
    private func overlay(_ state: PlayerState, insets: PlayerInsets, metrics: PlayerMetrics) -> some View {
        switch model.overlay {
        case .picker(let tab):
            PlayerPickerSheet(model: model, tab: tab, insets: insets, sidePanel: metrics.sidePanelPicker)
                .transition(slide(metrics.sidePanelPicker ? .trailing : .bottom))
        case .stillWatching:
            StillWatchingDialog(state: state) { model.answerStillWatching(keepWatching: $0) }
                .transition(.opacity)
        case .stopped(let message):
            PlayerEndState(
                kind: .stopped(message: message, note: model.stoppedAt.map { stoppedNote(at: $0) }),
                title: playerTitleLine(title: state.title, subtitle: state.subtitle),
                insets: insets,
                onBack: { model.close() },
                onRetry: { model.retry() }
            )
            .transition(.opacity)
        case .failed(let message):
            let server = services.servers.current?.server
            PlayerEndState(
                kind: .failed(message: message, note: failedNote(mode: state.mode, serverName: server?.name, serverVersion: server?.version)),
                title: playerTitleLine(title: state.title, subtitle: state.subtitle),
                insets: insets,
                onBack: { model.close() },
                onRetry: { model.retry() }
            )
            .transition(.opacity)
        case nil:
            EmptyView()
        }
    }
}

/// The subtitle cues, the only part of the video's overlay that follows them.
private struct PlayerCues: View {
    let clock: PlayerClock
    let bottomPadding: CGFloat
    let sidePadding: CGFloat

    var body: some View {
        SubtitleCuesView(cues: clock.cues, bottomPadding: bottomPadding, sidePadding: sidePadding)
    }
}

/// ``MiniProgress`` at the clock's position.
private struct ClockMiniProgress: View {
    let clock: PlayerClock

    var body: some View {
        MiniProgress(positionMs: clock.positionMs, durationMs: clock.durationMs)
    }
}
