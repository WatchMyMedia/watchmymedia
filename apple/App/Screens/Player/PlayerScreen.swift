import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// The full-screen player, landscape in a phone's window: the video, subtitles,
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
///
/// On the iPhone Duo (PLAN-IPHONE-DUO.md §4.4) the same picture moves and
/// resizes as the window does: beside a panel on the inner screen held wide,
/// above the fold half open on a table, over the season held upright. The
/// video view keeps its place in this view's tree whatever the layout, so a
/// fold never rebuilds the player layer, pauses or rebuffers.
private struct PlayerContent: View {
    let model: PlayerChromeModel
    let episodes: PlayerEpisodesModel
    /// The Episodes panel over the video (an iPad full screen, the Duo's outer screen turned).
    @State private var showsEpisodes = false
    /// The panel beside the picture, which is the tab under the fold.
    @State private var panels = PlayerPanelChoice()
    /// Tabletop by itself when half open on a table, until Use the whole screen.
    @State private var fold = PlayerFold(hasBook: false)
    @Environment(\.foldPosture) private var posture
    @Environment(AppServices.self) private var services
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.horizontalSizeClass) private var horizontal
    @Environment(\.verticalSizeClass) private var vertical

    var body: some View {
        let metrics = PlayerMetrics.for(horizontal: horizontal, vertical: vertical)
        let tabletop = fold.layout == .tabletop
        PlayerShell(model: model, orientations: .landscape) { frame in
            content(frame)
        }
        .onChange(of: posture, initial: true) { _, posture in fold.follow(posture) }
        // Under the fold the controls are always there: they don't hide by themselves.
        .onChange(of: tabletop, initial: true) { _, tabletop in
            model.hold("tabletop", tabletop)
            if tabletop { showsEpisodes = false }
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
        #if DEBUG
        .onAppear { if let panel = PlayerLaunchArguments.panel { panels.open(panel) } }
        #endif
    }

    /// A panel beside the picture stands for the options over it: the
    /// controls stay up while it shows, and Escape closes it first.
    private func panelBesideChanged(_ open: Bool) {
        model.hold("panelBeside", open)
        guard open else {
            PlayerEscape.closePanel = nil
            return
        }
        let panels = $panels
        PlayerEscape.closePanel = { panels.wrappedValue.close() }
    }

    private func content(_ frame: PlayerFrame) -> some View {
        let state = model.state
        let metrics = frame.metrics
        let pictureInPicture = frame.pictureInPicture
        let tabletop = fold.layout == .tabletop
        let arrangement = PlayerArrangement.film(size: frame.size, safe: frame.safeInsets, wide: metrics.sidePanelPicker,
                                                 fold: fold.layout, panelOpen: panels.panel != nil)
        let picture = frame.piece(arrangement.picture)
        // Where the options and episodes open beside the picture or under the fold, not over it.
        let inPlace = tabletop || PlayerArrangement.panelsBeside(size: frame.size, wide: metrics.sidePanelPicker)
        // A phone's window tall enough for a row of buttons under the scrubber (the Duo's outer screen turned).
        let chips = PlayerArrangement.chipRow(size: frame.size, wide: metrics.sidePanelPicker)
        let needsEpisodes = metrics.sidePanelPicker || tabletop || chips
        let options: PlayerOptionsRow = !metrics.sidePanelPicker ? (chips ? .chips : .more)
            : inPlace ? .panels(panels, toggle: { panels.toggle($0) }) : .tabs
        let insets = frame.insets(portrait: arrangement.kind == .portrait)
        let besideOpen = arrangement.kind == .beside && panels.panel != nil
        return ZStack(alignment: .topLeading) {
            (arrangement.kind == .full || arrangement.kind == .beside ? Color.black : Palette.ground)
            // Always first and always the same view: only its frame changes.
            video(in: picture, kind: arrangement.kind, options: options)
                .frame(width: arrangement.picture.width, height: arrangement.picture.height)
                .clipped()
                .offset(x: arrangement.picture.minX, y: arrangement.picture.minY)
            if arrangement.kind == .portrait, let below = arrangement.below {
                PlayerPortraitDetails(model: model, episodes: episodes)
                    .frame(width: below.width, height: below.height)
                    .offset(x: below.minX, y: below.minY)
            }
            if arrangement.kind == .tabletop, let below = arrangement.below {
                TabletopPlayerControls(model: model, episodes: episodes, panels: $panels, safe: frame.piece(below).safeArea) {
                    fold.useWholeScreen()
                }
                .frame(width: below.width, height: below.height)
                .offset(x: below.minX, y: below.minY)
            }
            if arrangement.kind == .beside, let rect = arrangement.panel, let panel = panels.panel {
                PlayerSidePanelView(model: model, episodes: episodes, panel: panel, safe: frame.piece(rect).safeArea) { panels.close() }
                    .frame(width: rect.width, height: rect.height)
                    .offset(x: rect.minX, y: rect.minY)
                    .transition(slide(.trailing))
            }
            if !pictureInPicture.isActive {
                overlay(state, insets: insets, metrics: metrics, safe: frame.safeArea)
                if showsEpisodes, model.overlay == nil {
                    PlayerEpisodesPanel(model: model, episodes: episodes, safe: frame.safeArea) { showsEpisodes = false }
                }
            }
        }
        .frame(width: frame.size.width, height: frame.size.height, alignment: .topLeading)
        .task(id: "\(model.request.fileID)-\(needsEpisodes)") {
            if needsEpisodes { await episodes.load(for: model.request) }
        }
        // The options opened over the picture (the sheet carried from the outer
        // screen, or a launch argument) become the panel beside it, or the tab
        // under the fold, where those are drawn; closing the sheet plays on if it paused.
        .onChange(of: inPlace ? model.picker : nil, initial: true) { _, tab in
            guard let tab else { return }
            panels.open(.options(tab))
            model.closePicker()
        }
        // A panel beside the picture stands for the options over it: the
        // controls stay up while it shows, and Escape closes it first.
        .onChange(of: besideOpen, initial: true) { _, open in panelBesideChanged(open) }
        .onDisappear { PlayerEscape.closePanel = nil }
        .animation(.easeInOut(duration: 0.25), value: arrangement)
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

    /// The picture and what is drawn over it, in `frame` (the picture's piece
    /// of the screen). Above the fold that is only the subtitles, Skip, the
    /// Next Episode card and the notes; the controls are under the fold.
    private func video(in frame: PlayerFrame, kind: PlayerArrangement.Kind, options: PlayerOptionsRow) -> some View {
        let state = model.state
        let metrics = frame.metrics
        let pictureInPicture = frame.pictureInPicture
        let portrait = kind == .portrait
        let tabletop = kind == .tabletop
        let insets = frame.insets(portrait: false)
        let controlsOver = model.showsControls && !tabletop
        // Over the controls' bottom bar, and the row of buttons under it where there is one.
        let chipRow: CGFloat = if case .chips = options { 52 } else { 0 }
        let lift = (portrait ? 56 : metrics.skipLift) + chipRow
        let upNext = tabletop ? state.upNext != nil && model.overlay == nil && !pictureInPicture.isActive : model.showsUpNext
        return ZStack {
                PlayerVideo(
                    player: model.controller.player,
                    backdrop: services.library.imageURL(state.backdropPath ?? model.request.backdropPath, size: .w1280),
                    seed: state.title.isEmpty ? model.request.title : state.title,
                    pictureInPicture: pictureInPicture,
                    layout: frame.videoLayout
                )

                if !pictureInPicture.isActive {
                    PlayerCues(clock: model.clock, bottomPadding: controlsOver ? insets.bottom + lift + 8 : insets.bottom + 8, sidePadding: insets.leading)

                    // Taps on the video: show or hide the controls; double taps on a side seek;
                    // a pinch fills or fits.
                    Color.clear
                        .contentShape(Rectangle())
                        .onTapGesture(coordinateSpace: .local) { model.tap(x: $0.x, width: frame.fullWidth) }
                        .pinchToFit(frame, area: frame.size)
                        .accessibilityHidden(true)

                    if let ripple = model.ripple {
                        SeekRipple(burst: ripple)
                            .frame(width: frame.size.width * 0.4)
                            .frame(maxWidth: .infinity, alignment: ripple.zone == .back ? .leading : .trailing)
                            .transition(.opacity)
                    }

                    if controlsOver {
                        PlayerControlsView(model: model, pictureInPicture: pictureInPicture, insets: insets, metrics: metrics, portrait: portrait, options: options,
                                           onEpisodes: episodes.season == nil && episodes.error == nil ? nil : { showsEpisodes = true },
                                           fitButton: VideoFitButton(frame: frame, area: frame.size, metrics: metrics) { model.showControls() })
                            .transition(.opacity)
                    } else if model.isWaiting, model.overlay == nil {
                        SpinnerRing()
                    }

                    if let skip = state.skip, model.showsSkip {
                        SkipButton(prompt: skip, clock: model.clock, metrics: metrics) { model.skip() }
                            .padding(.trailing, insets.trailing)
                            .padding(.bottom, controlsOver ? insets.bottom + lift : insets.bottom)
                            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
                            .transition(.opacity)
                    }

                    if model.showsMiniProgress, !tabletop {
                        ClockMiniProgress(clock: model.clock)
                            .opacity(model.overlay == .stillWatching ? 0.7 : 1)
                            .padding(.leading, insets.leading)
                            .padding(.bottom, max(insets.bottom - 2, 16))
                            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomLeading)
                            .transition(.opacity)
                    }

                    if let next = state.upNext, upNext {
                        NextEpisodeCard(
                            prompt: next,
                            clock: model.clock,
                            width: metrics.sidePanelPicker && !tabletop ? min(400, frame.size.width * 0.48) : 320,
                            still: services.library.imageURL(next.next.stillPath, size: .w342),
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
                            .padding(.top, insets.top + (tabletop ? 0 : metrics.button) + (metrics.streamChip ? 52 : 16))
                            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                            .allowsHitTesting(false)
                            .transition(.opacity)
                    }

                }
            }
    }

    @ViewBuilder
    private func overlay(_ state: PlayerState, insets: PlayerInsets, metrics: PlayerMetrics, safe: EdgeInsets) -> some View {
        switch model.overlay {
        case .picker(let tab):
            PlayerPickerSheet(model: model, tab: tab, insets: insets, safe: safe, sidePanel: metrics.sidePanelPicker)
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
