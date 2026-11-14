import AVFoundation
import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// The player (PLAN-IOS.md §11.2, design/mockups/apple-tv/TvPlayer*.dc.html):
/// the system's `AVPlayerViewController` on the controller's player, with
/// Skip as its contextual action, Next Episode among its transport bar's
/// buttons, Audio, Subtitles and Quality as tabs of its info panel, and the
/// app's subtitle cues over the video. The Next Episode card, Are you still watching and the stopped and
/// can't-play states are the app's own, over it. The controller does the
/// playing (PLAN-CLIENTS.md §2.3); ``TVPlayerModel`` decides what shows.
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
    /// The Episodes tab over the video, on the fakes, whose empty player has no info panel (`-playerPanel episodes`).
    #if DEBUG
    @State private var episodesPreview = PlayerLaunchArguments.episodesPanel
    #else
    @State private var episodesPreview = false
    #endif

    var body: some View {
        let state = model.state
        let title = state.title.isEmpty ? model.request.title : state.title
        ZStack {
            if model.showsVideo {
                SystemPlayer(
                    player: model.controller.player ?? standIn,
                    contextualActions: model.contextualActions,
                    transportBarActions: model.transportBarActions,
                    behavior: .film(model, episodesTab: episodes.hasTab ? episodesTab : nil),
                    overlay: VideoOverlay(
                        model: model,
                        standIn: model.controller.player == nil,
                        backdrop: services.library.imageURL(state.backdropPath ?? model.request.backdropPath, size: .w1280)
                    )
                    .environment(\.imageLoader, services.images)
                )
                // Still watching asks first; the remote is its.
                .disabled(!model.videoTakesInput || episodesPreview)
                .transition(.opacity)
            }

            if episodesPreview, episodes.hasTab, model.overlay == nil {
                EpisodesTab(model: model, episodes: episodes, imageURL: { services.library.imageURL($0, size: .w780) })
                    .environment(\.imageLoader, services.images)
                    .padding(.top, 40)
                    .frame(maxHeight: .infinity, alignment: .top)
            }

            switch model.overlay {
            case .upNext(let prompt):
                NextEpisodeCard(
                    prompt: prompt,
                    still: services.library.imageURL(prompt.next.stillPath, size: .w342),
                    onPlay: { model.playNext() },
                    onWatchCredits: { model.watchCredits() }
                )
                .padding(TVLayout.gutter)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
                .transition(.opacity.combined(with: .move(edge: .trailing)))
            case .stillWatching:
                StillWatchingPrompt(state: state) { model.answerStillWatching(keepWatching: $0) }
                    .transition(.opacity)
            case .stopped(let message):
                PlayerEndState(
                    kind: .stopped(message: message, note: model.stoppedAt.map { stoppedNote(at: $0, watchedMs: model.stoppedAtMs) }),
                    title: playerTitleLine(title: title, subtitle: state.subtitle),
                    onBack: { model.close() },
                    onRetry: { model.retry() }
                )
                .transition(.opacity)
            case .failed(let message):
                let server = services.servers.current?.server
                PlayerEndState(
                    kind: .failed(message: message, note: failedNote(mode: state.mode, serverName: server?.name, serverVersion: server?.version)),
                    title: playerTitleLine(title: title, subtitle: state.subtitle),
                    onBack: { model.close() },
                    onRetry: { model.retry() }
                )
                .transition(.opacity)
            case nil:
                EmptyView()
            }
        }
        .animation(.easeOut(duration: 0.25), value: model.overlay)
        .task(id: model.request) { await episodes.follow(model.request) }
        // What reaches the app once the system's controls have closed what is theirs.
        .onExitCommand {
            if episodesPreview { episodesPreview = false } else { model.back() }
        }
        .onPlayPauseCommand { model.playPause() }
    }

    /// The Episodes tab as AVKit hosts it, made when the info panel's tabs are.
    private func episodesTab() -> UIViewController {
        EpisodesTabController(model: model, episodes: episodes, imageURL: { [library = services.library] in library.imageURL($0, size: .w780) }, images: services.images)
    }
}

/// Over the video and under the system's controls (`contentOverlayView`):
/// the subtitle cues, lifted clear of the transport bar while it shows, and
/// with it the swipe-down hint and how the server is sending the file, with
/// Automatic's step (TvPlayerControls.dc.html). Automatic's note shows with
/// or without the bar and never takes focus. On the fakes, the backdrop and
/// what would play.
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
                            .overlay(Color.black.opacity(0.45))
                    }
                VStack(alignment: .leading, spacing: 8) {
                    Text(state.title.isEmpty ? model.request.title : state.title).font(.system(size: 48, weight: .bold))
                    if let subtitle = state.subtitle ?? model.request.subtitle { Text(subtitle).font(.system(size: 26)).foregroundStyle(Palette.textSecondary) }
                    Text(state.status == .playing ? "Playing" : "Paused").font(.system(size: 20)).foregroundStyle(Palette.muted)
                }
                .foregroundStyle(Palette.text)
                .padding(TVLayout.gutter)
                .padding(.bottom, bar ? 220 : 0)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomLeading)
            }

            if bar {
                if state.growing != nil {
                    Label("Recording", systemImage: "record.circle").font(.system(size: 24, weight: .bold)).foregroundStyle(Palette.liveText)
                        .padding(.top, 50).padding(.leading, TVLayout.gutter).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                }
                LinearGradient(colors: [.black.opacity(0.6), .clear], startPoint: .top, endPoint: .bottom)
                    .frame(height: 200)
                    .frame(maxHeight: .infinity, alignment: .top)
                    .transition(.opacity)
                ZStack {
                    Label(infoPanelHint, systemImage: "chevron.down")
                        .font(.system(size: 19))
                        .foregroundStyle(Palette.muted)
                    if let line = streamLine(state) {
                        HStack(spacing: 10) {
                            if let chip = autoChip(state) {
                                Text(chip)
                                    .font(.system(size: 17, weight: .semibold))
                                    .foregroundStyle(Palette.gold)
                                    .padding(.horizontal, 14)
                                    .padding(.vertical, 7)
                                    .background(.black.opacity(0.45), in: Capsule())
                                    .overlay(Capsule().strokeBorder(Palette.gold.opacity(0.75), lineWidth: 2))
                            }
                            Text(line)
                                .font(.system(size: 17))
                                .foregroundStyle(Palette.textSecondary)
                                .padding(.horizontal, 14)
                                .padding(.vertical, 8)
                                .background(.black.opacity(0.45), in: Capsule())
                        }
                        .frame(maxWidth: .infinity, alignment: .trailing)
                    }
                }
                .padding(.horizontal, TVLayout.gutter)
                .padding(.top, 40)
                .frame(maxHeight: .infinity, alignment: .top)
                .transition(.opacity)
            }

            if let note = state.qualityNote {
                Text(note)
                    .font(.system(size: 22, weight: .medium))
                    .foregroundStyle(Palette.text)
                    .padding(.horizontal, 28)
                    .padding(.vertical, 14)
                    .background(Palette.ground.opacity(0.88), in: Capsule())
                    .overlay(Capsule().strokeBorder(.white.opacity(0.2)))
                    .shadow(color: .black.opacity(0.55), radius: 20, y: 16)
                    .padding(.top, 110)
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
