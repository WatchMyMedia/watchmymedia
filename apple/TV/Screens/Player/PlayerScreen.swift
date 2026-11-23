import AVFoundation
import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// The player:
/// the system's `AVPlayerViewController` on the controller's player, with
/// Skip as its contextual action, Next Episode among its transport bar's
/// buttons, the title view saying when the play would end, and Info,
/// Episodes, Audio & subtitles and Quality as tabs of its info panel; the
/// app's subtitle cues over the video. Nothing about the stream is on the
/// picture: how it plays is in the Quality tab. The Up next card, Are you
/// still watching, and the stopped and can't-play states are the app's
/// own, over it; while it starts, black with a spinner. The controller does
/// the playing; ``TVPlayerModel`` decides what shows.
struct PlayerScreen: View {
    let request: PlayRequest
    @Environment(AppServices.self) private var services
    @Environment(Navigator.self) private var navigator
    @Environment(\.scenePhase) private var scenePhase
    @State private var model: TVPlayerModel?
    @State private var episodes: TVPlayerEpisodesModel?

    var body: some View {
        ZStack {
            Color.black
            if let model, let episodes { PlayerContent(model: model, episodes: episodes) }
        }
        .ignoresSafeArea()
        .onAppear(perform: begin)
        .onDisappear { model?.dispose() }
        .onChange(of: scenePhase) { _, phase in
            switch phase {
            case .background: model?.enteredBackground()
            case .active: model?.becameActive()
            default: break
            }
        }
    }

    private func begin() {
        guard model == nil else { return }
        let navigator = navigator
        var controller = services.playback.make()
        #if DEBUG
        controller = PlayerLaunchArguments.wrap(controller)
        #endif
        let model = TVPlayerModel(controller: controller, request: request, onExit: { navigator.closePlayer() })
        self.model = model
        episodes = TVPlayerEpisodesModel(library: services.library)
        model.start()
        #if DEBUG
        PlayerLaunchArguments.apply(to: model)
        #endif
    }
}

private struct PlayerContent: View {
    let model: TVPlayerModel
    /// The info panel's Episodes tab, for an episode.
    let episodes: TVPlayerEpisodesModel
    @Environment(AppServices.self) private var services
    /// The fakes have no video: the system's player runs empty over the backdrop.
    @State private var standIn = AVPlayer()
    /// A tab of the info panel over the video, on the fakes, whose empty
    /// player has no info panel (`-playerPanel episodes|tracks|quality`).
    #if DEBUG
    @State private var panelPreview = PlayerLaunchArguments.panel
    #else
    @State private var panelPreview: PlayerPanelPreview? = nil
    #endif

    private var serverName: String? { services.servers.current?.server.name }

    var body: some View {
        let state = model.state
        let title = state.title.isEmpty ? model.request.title : state.title
        ZStack {
            if model.showsVideo {
                SystemPlayer(
                    player: model.controller.player ?? standIn,
                    contextualActions: model.contextualActions,
                    transportBarActions: model.transportBarActions,
                    behavior: .film(model, serverName: serverName, episodesTab: episodes.hasTab ? episodesTab : nil),
                    overlay: VideoOverlay(
                        model: model,
                        standIn: model.controller.player == nil,
                        backdrop: services.library.imageURL(state.backdropPath ?? model.request.backdropPath, size: .w1280)
                    )
                    .environment(\.imageLoader, services.images)
                )
                // Still watching asks first; the remote is its.
                .disabled(!model.videoTakesInput || panelPreview != nil)
                .transition(.opacity)
            }

            if model.overlay == nil, let panelPreview { preview(panelPreview) }

            switch model.overlay {
            case .upNext(let prompt):
                UpNextCard(
                    prompt: prompt,
                    still: services.library.imageURL(prompt.next.stillPath, size: .w780),
                    onPlay: { model.playNext() },
                    onWatchCredits: { model.watchCredits() }
                )
                .padding(.trailing, TVLayout.gutter)
                .padding(.bottom, TVLayout.safeBottom)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
                .transition(.opacity.combined(with: .move(edge: .trailing)))
            case .stillWatching:
                StillWatchingPrompt(title: playerTitleLine(title: title, subtitle: state.subtitle)) { model.answerStillWatching(keepWatching: $0) }
                    .transition(.opacity)
            case .stopped(let message):
                PlayerEndState(
                    kind: .stopped(message: message, line: tvStoppedLine(serverName: serverName),
                                   note: model.stoppedAt.map { tvStoppedNote(at: $0, watchedMs: model.stoppedAtMs) }),
                    onBack: { model.close() }
                )
                .transition(.opacity)
            case .failed(let message):
                PlayerEndState(
                    kind: .failed(words: TVCantPlay(error: message, serverName: serverName),
                                  lower: model.lowerQuality,
                                  details: services.auth.user?.isAdmin == true ? message : nil),
                    onBack: { model.close() },
                    onRetry: { model.retry() },
                    onLower: { model.playAtLowerQuality() }
                )
                .transition(.opacity)
            case nil:
                EmptyView()
            }

            LoadingCover(loading: state.status == .loading && model.overlay == nil)
        }
        .animation(.easeOut(duration: 0.25), value: model.overlay)
        .task(id: model.request) { await episodes.follow(model.request) }
        // While the transport bar shows, its title view says when the play would end, to the minute.
        .task(id: model.transportBarVisible) {
            while model.transportBarVisible, !Task.isCancelled {
                model.refreshEndsAt()
                try? await Task.sleep(for: .seconds(5))
            }
        }
        // What reaches the app once the system's controls have closed what is theirs.
        .onExitCommand {
            if panelPreview != nil { panelPreview = nil } else { model.back() }
        }
        .onPlayPauseCommand { model.playPause() }
    }

    /// The Episodes tab as AVKit hosts it, made when the info panel's tabs are.
    private func episodesTab() -> UIViewController {
        EpisodesTabController(model: model, episodes: episodes, imageURL: { [library = services.library] in library.imageURL($0, size: .w780) }, images: services.images)
    }

    /// A tab over the video where the system's info panel would hold it (the fakes' previews).
    @ViewBuilder
    private func preview(_ panel: PlayerPanelPreview) -> some View {
        Group {
            switch panel {
            case .episodes:
                if episodes.hasTab {
                    EpisodesTab(model: model, episodes: episodes, imageURL: { services.library.imageURL($0, size: .w780) })
                        .environment(\.imageLoader, services.images)
                }
            case .tracks:
                PlaybackOptions(model: model, tab: .tracks, serverName: serverName)
            case .quality:
                PlaybackOptions(model: model, tab: .quality, serverName: serverName)
            }
        }
        .padding(.top, TVLayout.safeTop + 80)
        .frame(maxHeight: .infinity, alignment: .top)
        .background { Color.black.opacity(0.5).ignoresSafeArea() }
    }
}

/// A tab of the info panel shown over the video on the fakes, whose empty
/// player has no info panel: `-playerPanel episodes|tracks|quality`.
enum PlayerPanelPreview: String {
    case episodes, tracks, quality
}

/// Over the video and under the system's controls (`contentOverlayView`):
/// the subtitle cues, lifted clear of the transport bar while it shows, and
/// Automatic's brief note when it changes the quality, with or without the
/// bar, never taking focus. A recording still being made says so while the
/// bar shows. On the fakes, which have no video and so no system title view,
/// the backdrop and the title and episode with when it would end, where the
/// system draws them.
struct VideoOverlay: View {
    let model: TVPlayerModel
    let standIn: Bool
    let backdrop: URL?

    var body: some View {
        let state = model.state
        let bar = model.transportBarVisible
        ZStack {
            if standIn {
                Color.clear
                    .overlay {
                        Artwork(url: backdrop, seed: state.title.isEmpty ? model.request.title : state.title, maxPixel: 1920)
                            .overlay(LinearGradient(stops: [.init(color: .black.opacity(0.62), location: 0), .init(color: .clear, location: 0.22), .init(color: .clear, location: 0.46), .init(color: .black.opacity(0.88), location: 1)], startPoint: .top, endPoint: .bottom))
                    }
                VStack(alignment: .leading, spacing: 8) {
                    Text(state.title.isEmpty ? model.request.title : state.title).font(.tv(.headline, .bold))
                    if let line = tvPlayerSubtitle(state.subtitle ?? model.request.subtitle, endsAt: model.endsAt) {
                        Text(line).font(.tv(.body)).foregroundStyle(Palette.textSecondary)
                    }
                    Text(state.status == .playing ? "Playing" : "Paused").font(.tv(.caption2)).foregroundStyle(Palette.muted)
                }
                .foregroundStyle(Palette.text)
                .padding(.horizontal, TVLayout.gutter)
                .padding(.bottom, bar ? 214 : 120)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomLeading)
            }

            if bar, state.growing != nil {
                Text("\(Image(systemName: "record.circle")) Recording")
                    .font(.tv(.caption1, .bold))
                    .foregroundStyle(Palette.liveText)
                    .padding(.top, TVLayout.safeTop)
                    .padding(.leading, TVLayout.gutter)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                    .transition(.opacity)
            }

            if let note = state.qualityNote {
                Text(note)
                    .font(.tv(.body, .semibold))
                    .foregroundStyle(Palette.text)
                    .padding(.horizontal, 34)
                    .padding(.vertical, 20)
                    .background(Color(hex: 0x18181B).opacity(0.96), in: RoundedRectangle(cornerRadius: 24))
                    .overlay(RoundedRectangle(cornerRadius: 24).strokeBorder(Color(hex: 0x34343A), lineWidth: 2))
                    .shadow(color: .black.opacity(0.6), radius: 25, y: 20)
                    .frame(maxWidth: 1500)
                    .padding(.top, TVLayout.safeTop)
                    .frame(maxHeight: .infinity, alignment: .top)
                    .transition(.opacity)
            }

            SubtitleCuesView(cues: state.cues, bottomPadding: bar ? 320 : 80, sidePadding: 160, baseSize: 46)
        }
        .animation(.easeInOut(duration: 0.25), value: bar)
        .animation(.easeInOut(duration: 0.25), value: state.qualityNote)
        .ignoresSafeArea()
        .allowsHitTesting(false)
    }
}

/// While a play starts: black and a spinner, nothing else. It shows only
/// once starting has taken two seconds, so a quick start never flashes it.
private struct LoadingCover: View {
    let loading: Bool
    @State private var shown = false

    var body: some View {
        ZStack {
            if shown {
                Color.black
                TVSpinner().scaleEffect(1.3)
            }
        }
        .ignoresSafeArea()
        .allowsHitTesting(false)
        .accessibilityHidden(!shown)
        .task(id: loading) {
            guard loading else {
                shown = false
                return
            }
            guard (try? await Task.sleep(for: .seconds(2))) != nil else { return }
            shown = true
        }
    }
}
