import SwiftUI
import UIKit
import WMMFeatures
import WMMModel
import WMMUI

/// The info panel's Episodes tab (TvPlayerEpisodes.dc.html,
/// PLAN-APPLE-REVIEW.md §6.2): after Info, for episodes only. The show's
/// name and its seasons as buttons, focus reaching one choosing it; under
/// them the chosen season's episodes on disk, focus starting on the one
/// playing; what the focused one is, and how to leave. Select or Play/Pause
/// on another episode plays it from where the person left it; the one
/// playing keeps playing. ``TVPlayerEpisodesModel`` decides all of it.
struct EpisodesTab: View {
    /// The panel's height under the system's tab bar.
    static let height: CGFloat = 540

    let model: TVPlayerModel
    let episodes: TVPlayerEpisodesModel
    /// The image URL of a TMDB path (the tab is hosted outside the app's environment).
    let imageURL: (String?) -> URL?

    enum Focus: Hashable {
        case season(Int)
        case episode(Int)
    }

    @FocusState private var focus: Focus?

    var body: some View {
        VStack(alignment: .leading, spacing: 26) {
            seasonsRow
            switch episodes.status {
            case .failed(let message):
                HStack(spacing: 24) {
                    Text(message).font(.system(size: 21)).foregroundStyle(Palette.muted)
                    Button("Try again") { Task { await episodes.retry() } }
                        .buttonStyle(TVButtonStyle(height: 56, fontSize: 21))
                        .accessibilityIdentifier("player-episodes-retry")
                }
                .frame(maxWidth: .infinity, minHeight: 246, alignment: .leading)
                .focusSection()
            case .loaded where !episodes.chosenSeasonPending:
                episodeRow
            default:
                ProgressView().tint(Palette.gold).frame(maxWidth: .infinity, minHeight: 246)
            }
            focusDetail
            Spacer(minLength: 0)
            Text(TVPlayerEpisodesModel.footnote)
                .font(.system(size: 16))
                .foregroundStyle(Palette.faint)
        }
        .padding(.horizontal, TVLayout.gutter - 26)
        .padding(.top, 20)
        .padding(.bottom, 28)
        .frame(maxWidth: .infinity, alignment: .leading)
        // tvOS draws nothing behind a custom tab: the artboard's panel, over the video.
        .background(Color(hex: 0x161618).opacity(0.94), in: RoundedRectangle(cornerRadius: 28))
        .frame(height: Self.height)
        .defaultFocus($focus, episodes.initialFocusID.map(Focus.episode), priority: .userInitiated)
        .onChange(of: focus) { old, new in
            if case .season(let number)? = new { Task { await episodes.choose(season: number) } }
            // Down from the seasons lands on the nearest card: move it to the
            // one playing, or the season's first, as the tab opens on.
            if case .season? = old, case .episode(let id)? = new, let start = episodes.initialFocusID, id != start {
                focus = .episode(start)
            }
        }
        // The row arrives after the tab, and the focus engine gives its first
        // card focus: move it to the one playing (or the season's first), unless
        // focus is on the seasons, where moving along chooses them.
        .onChange(of: episodes.initialFocusID, initial: true) { _, id in
            guard let id else { return }
            if case .season? = focus { return }
            focus = .episode(id)
        }
        .task(id: episodes.place) { await episodes.open() }
        .onAppear { Task { await episodes.open() } }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("player-episodes-tab")
    }

    private var seasonsRow: some View {
        HStack(spacing: 12) {
            Text(episodes.showTitle.isEmpty ? model.request.title : episodes.showTitle)
                .font(.system(size: 24, weight: .bold))
                .foregroundStyle(Palette.text)
                .lineLimit(1)
                .padding(.trailing, 14)
            ForEach(episodes.seasons) { season in
                Button(season.chipName) { Task { await episodes.choose(season: season.number) } }
                    .buttonStyle(SeasonButtonStyle(chosen: season.number == episodes.chosenSeason))
                    .focused($focus, equals: .season(season.number))
                    .accessibilityIdentifier("player-season-\(season.number)")
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .focusSection()
    }

    private var episodeRow: some View {
        ScrollView(.horizontal) {
            // Not lazy: a season is a few dozen cards at most, and every card is then there for focus and VoiceOver.
            HStack(spacing: 28) {
                ForEach(episodes.episodes) { episode in
                    Button { play(episode) } label: {
                        EpisodeCard(
                            episode: episode, episodes: episodes,
                            progress: episodes.progress(episode, playingPositionMs: model.state.positionMs, playingDurationMs: model.state.durationMs),
                            still: imageURL(episode.stillPath)
                        )
                    }
                    .buttonStyle(EpisodeCardStyle())
                    .focused($focus, equals: .episode(episode.id))
                    .onPlayPauseCommand { play(episode) }
                    .accessibilityLabel(episodes.accessibilityLabel(episode))
                    .accessibilityIdentifier("player-episode-\(episode.number)")
                }
            }
            .padding(.vertical, 22)
            .padding(.horizontal, 4)
        }
        .scrollClipDisabled()
        .frame(height: 290)
        .padding(.vertical, -12)
        .focusSection()
    }

    /// What the focused episode or season is, or where the person is.
    private var focusDetail: some View {
        let lines: (String, String)? = {
            switch focus {
            case .episode(let id)?:
                guard let episode = episodes.episodes.first(where: { $0.id == id }) else { return nil }
                return (episodes.focusLine(episode), episode.overview)
            case .season(let number)?:
                guard let season = episodes.seasons.first(where: { $0.number == number }) else { return nil }
                return (episodes.seasonLine(season), TVPlayerEpisodesModel.seasonHint)
            case nil:
                return episodes.panelLine.map { ($0, TVPlayerEpisodesModel.panelHint) }
            }
        }()
        return VStack(alignment: .leading, spacing: 8) {
            Text(lines?.0 ?? " ")
                .font(.system(size: 21, weight: .semibold))
                .foregroundStyle(Palette.text)
                .lineLimit(1)
                .accessibilityIdentifier("player-episodes-focus-line")
            Text(lines?.1 ?? " ")
                .font(.system(size: 19))
                .foregroundStyle(Color(hex: 0xBDBDBD))
                .lineSpacing(6)
                .lineLimit(2, reservesSpace: true)
        }
        .frame(maxWidth: 1100, alignment: .leading)
        .padding(.top, 6)
    }

    private func play(_ episode: EpisodeDetail) {
        guard let request = episodes.request(for: episode) else { return }
        model.play(request)
    }
}

/// The artboard's `.ep`: a 330×186 still with the duration, Now playing,
/// the watched check and the progress bar on it; "Episode 8" and the title under it.
private struct EpisodeCard: View {
    let episode: EpisodeDetail
    let episodes: TVPlayerEpisodesModel
    let progress: Double?
    let still: URL?
    @Environment(\.isFocused) private var focused

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Artwork(url: still, seed: episode.title, maxPixel: 700)
                .frame(width: 330, height: 186)
                .overlay(alignment: .topLeading) {
                    if episodes.isPlaying(episode) {
                        Text("\(Image(systemName: "play.fill")) NOW PLAYING")
                            .font(.system(size: 14, weight: .bold))
                            .tracking(0.7)
                            .foregroundStyle(Palette.text)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 5)
                            .background(.black.opacity(0.75), in: RoundedRectangle(cornerRadius: 8))
                            .padding(10)
                    }
                }
                .overlay(alignment: .topTrailing) {
                    if let duration = episodes.durationLabel(episode) {
                        Text(duration)
                            .font(.system(size: 14, weight: .semibold))
                            .monospacedDigit()
                            .foregroundStyle(Palette.text)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 4)
                            .background(.black.opacity(0.72), in: RoundedRectangle(cornerRadius: 6))
                            .padding(10)
                    }
                }
                .overlay(alignment: .bottomTrailing) {
                    if episodes.showsWatched(episode) {
                        Image(systemName: "checkmark")
                            .font(.system(size: 14, weight: .heavy))
                            .foregroundStyle(Palette.ground)
                            .frame(width: 28, height: 28)
                            .background(Palette.gold, in: Circle())
                            .padding(.trailing, 10)
                            .padding(.bottom, 12)
                    }
                }
                .overlay(alignment: .bottom) {
                    if let progress {
                        ZStack(alignment: .leading) {
                            Rectangle().fill(.black.opacity(0.6))
                            Rectangle().fill(Palette.gold).frame(width: 330 * progress)
                        }
                        .frame(height: 6)
                    }
                }
                .overlay {
                    if focused {
                        LinearGradient(colors: [.white.opacity(0.14), .clear], startPoint: .topLeading, endPoint: .center)
                    }
                }
                .clipShape(RoundedRectangle(cornerRadius: 14))
                .focusRim(focused, cornerRadius: 14, scale: 1.08)
                .zIndex(focused ? 2 : 0)
            VStack(alignment: .leading, spacing: 2) {
                Text(episodes.numberLabel(episode))
                    .font(.system(size: 16, weight: .semibold))
                    .monospacedDigit()
                    .foregroundStyle(Palette.muted)
                Text(episode.title)
                    .font(.system(size: 21, weight: .semibold))
                    .foregroundStyle(focused ? .white : Color.tvTitle)
                    .lineLimit(1)
            }
            .padding(.top, 12)
            .offset(y: focused ? 8 : 0)
            .animation(.easeOut(duration: 0.18), value: focused)
        }
        .frame(width: 330, alignment: .leading)
    }
}

/// The card draws its own focus.
private struct EpisodeCardStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.scaleEffect(configuration.isPressed ? 0.97 : 1)
    }
}

/// The artboard's `.ss`: a season button, lighter when chosen; focused, white with the gold rim.
private struct SeasonButtonStyle: ButtonStyle {
    let chosen: Bool

    func makeBody(configuration: Configuration) -> some View {
        Styled(configuration: configuration, chosen: chosen)
    }

    private struct Styled: View {
        let configuration: Configuration
        let chosen: Bool
        @Environment(\.isFocused) private var focused

        var body: some View {
            configuration.label
                .font(.system(size: 19, weight: .semibold))
                .lineLimit(1)
                .foregroundStyle(focused ? Palette.ground : chosen ? Palette.text : Palette.textSecondary)
                .padding(.horizontal, 20)
                .frame(height: 44)
                .background(focused ? Palette.text : .white.opacity(chosen ? 0.22 : 0.08), in: Capsule())
                .overlay {
                    if focused { Capsule().strokeBorder(Palette.gold, lineWidth: 3).padding(-3) }
                }
                .scaleEffect(focused ? 1.06 : configuration.isPressed ? 0.96 : 1)
                .shadow(color: .black.opacity(focused ? 0.6 : 0), radius: focused ? 18 : 0, y: focused ? 14 : 0)
                .animation(.easeOut(duration: 0.15), value: focused)
                .accessibilityAddTraits(chosen ? .isSelected : [])
        }
    }
}

/// The tab as AVKit hosts it, named "Episodes".
final class EpisodesTabController: UIHostingController<AnyView> {
    init(model: TVPlayerModel, episodes: TVPlayerEpisodesModel, imageURL: @escaping (String?) -> URL?, images: any ImageLoader) {
        super.init(rootView: AnyView(
            EpisodesTab(model: model, episodes: episodes, imageURL: imageURL).environment(\.imageLoader, images)
        ))
        title = "Episodes"
        preferredContentSize = CGSize(width: 1920, height: EpisodesTab.height)
        view.backgroundColor = .clear
    }

    @available(*, unavailable)
    @MainActor required dynamic init?(coder: NSCoder) { fatalError("not used") }
}
