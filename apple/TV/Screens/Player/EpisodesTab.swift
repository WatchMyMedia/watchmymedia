import SwiftUI
import UIKit
import WMMFeatures
import WMMModel
import WMMUI

/// The info panel's Episodes tab:
/// after Info, for episodes only. The show's
/// name and its seasons as buttons, focus reaching one choosing it; under
/// them the chosen season's episodes on disk, focus starting on the one
/// playing; what the focused one is, and what happens in it. Select or Play/Pause
/// on another episode plays it from where the person left it; the one
/// playing keeps playing. ``TVPlayerEpisodesModel`` decides all of it.
struct EpisodesTab: View {
    /// The panel's height under the system's tab bar.
    static let height: CGFloat = 660

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
        VStack(alignment: .leading, spacing: 24) {
            seasonsRow
            switch episodes.status {
            case .failed(let message):
                HStack(spacing: 24) {
                    Text(message).font(.tv(.body)).foregroundStyle(Palette.textSecondary)
                    Button("Try again") { Task { await episodes.retry() } }
                        .buttonStyle(TVButtonStyle(kind: .primary))
                        .accessibilityIdentifier("player-episodes-retry")
                }
                .frame(maxWidth: .infinity, minHeight: 300, alignment: .leading)
                .focusSection()
            case .loaded where !episodes.chosenSeasonPending:
                episodeRow
            default:
                TVSpinner().frame(maxWidth: .infinity, minHeight: 300)
            }
            focusDetail
            Spacer(minLength: 0)
        }
        .padding(EdgeInsets(top: 32, leading: 40, bottom: 32, trailing: 40))
        .frame(maxWidth: .infinity, alignment: .leading)
        // tvOS draws nothing behind a custom tab: the panel, over the video.
        .background(Color(hex: 0x1E1E22).opacity(0.94), in: RoundedRectangle(cornerRadius: 32))
        .padding(.horizontal, TVLayout.gutter)
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
                .font(.tv(.callout))
                .foregroundStyle(Palette.text)
                .lineLimit(1)
                .padding(.trailing, 14)
            ForEach(episodes.seasons) { season in
                Button(season.chipName) { Task { await episodes.choose(season: season.number) } }
                    .buttonStyle(TVChipStyle(selected: season.number == episodes.chosenSeason))
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
            HStack(spacing: TVLayout.cardSpacing) {
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
            .padding(.vertical, 24)
            .padding(.horizontal, 4)
        }
        .scrollClipDisabled()
        .frame(height: 320)
        .padding(.vertical, -12)
        .focusSection()
    }

    /// What the focused episode or season is, or where the person is; for
    /// an episode, two lines of what happens in it.
    private var focusDetail: some View {
        let lines: (String, String?)? = {
            switch focus {
            case .episode(let id)?:
                guard let episode = episodes.episodes.first(where: { $0.id == id }) else { return nil }
                return (episodes.focusLine(episode), episode.overview)
            case .season(let number)?:
                guard let season = episodes.seasons.first(where: { $0.number == number }) else { return nil }
                return (episodes.seasonLine(season), nil)
            case nil:
                return episodes.panelLine.map { ($0, nil) }
            }
        }()
        return VStack(alignment: .leading, spacing: 8) {
            Text(lines?.0 ?? " ")
                .font(.tv(.body, .semibold))
                .foregroundStyle(Palette.text)
                .lineLimit(1)
                .accessibilityIdentifier("player-episodes-focus-line")
            Text(lines?.1 ?? " ")
                .font(.tv(.caption1))
                .foregroundStyle(Palette.textSecondary)
                .lineLimit(2, reservesSpace: true)
        }
        .frame(maxWidth: 1200, alignment: .leading)
    }

    private func play(_ episode: EpisodeDetail) {
        guard let request = episodes.request(for: episode) else { return }
        model.play(request)
    }
}

/// An episode in the tab: a 320 x 180 still with Now playing, its length,
/// the watched check and how far it was watched on it; the number and the
/// title under it. Focused, it lifts as any card does.
private struct EpisodeCard: View {
    let episode: EpisodeDetail
    let episodes: TVPlayerEpisodesModel
    let progress: Double?
    let still: URL?
    @Environment(\.isFocused) private var focused

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Artwork(url: still, seed: episode.title, maxPixel: 720)
                .frame(width: TVCardWidth.wide, height: 180)
                .overlay(alignment: .topLeading) {
                    if episodes.isPlaying(episode) { TVBadge("Now playing").padding(12) }
                }
                .overlay(alignment: .topTrailing) {
                    if episodes.showsWatched(episode) {
                        WatchedCheck(size: 40).padding(12)
                    } else if let duration = episodes.durationLabel(episode) {
                        TVBadge(duration).monospacedDigit().padding(12)
                    }
                }
                .overlay(alignment: .bottom) {
                    if let progress { TVProgressBar(progress: progress).padding(14) }
                }
                .overlay { FocusSheen(focused: focused) }
                .clipShape(RoundedRectangle(cornerRadius: 14))
                .focusLift(focused)
                .zIndex(focused ? 2 : 0)
            VStack(alignment: .leading, spacing: 2) {
                Text(episodes.numberLabel(episode))
                    .font(.tv(.caption2))
                    .monospacedDigit()
                    .foregroundStyle(Palette.muted)
                Text(episode.title)
                    .font(.tv(.caption1, .semibold))
                    .foregroundStyle(focused ? .white : Color.tvTitle)
                    .lineLimit(1)
            }
            .padding(.top, 16)
            .offset(y: focused ? 10 : 0)
            .animation(.easeOut(duration: 0.18), value: focused)
        }
        .frame(width: TVCardWidth.wide, alignment: .leading)
    }
}

/// The card draws its own lift; held down, it settles a little.
private struct EpisodeCardStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.scaleEffect(configuration.isPressed ? 0.97 : 1)
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
