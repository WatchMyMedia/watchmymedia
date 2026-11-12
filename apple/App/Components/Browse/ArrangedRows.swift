import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// What a row's cards do, from the screen that draws them (Home or a library).
struct RowActions {
    var open: (BrowseItem) -> Void
    var openEpisode: (EpisodeCard) -> Void = { _ in }
    var playEpisode: (EpisodeCard) -> Void = { _ in }
    var resume: (ContinueItem) -> Void = { _ in }
    var openPage: (ContinueItem) -> Void = { _ in }
    var setWatched: (WatchTarget, Bool) async -> Void
    /// A row's See all, or nil when it has none here.
    var seeAll: (ScreenRow) -> (() -> Void)?
}

/// One arranged row (PLAN-ROWS.md §6.1), drawn by its style with Home's
/// cards: posters, 16:9 cards, episodes, Continue watching, or the app's
/// Live TV row in the `live` slot. A list row shows its line under the
/// heading ("18 of 50 · From TMDB", LibraryRows.dc.html). On the iPad's Home
/// screens (PLAN-HOME.md §7.6) `counter` is "Row 2 of 7" and `leading` holds
/// the leading card's key, which the row outlines in gold and keeps up to
/// date as the row snaps.
struct ArrangedRow: View {
    let row: ScreenRow
    let actions: RowActions
    var counter: String?
    var leading: Binding<String?>?
    @Environment(AppServices.self) private var services
    @Environment(\.horizontalSizeClass) private var sizeClass

    private var wide: Bool { sizeClass.isWide }
    private var wideCardWidth: CGFloat { wide ? 248 : 220 }

    /// The card with `key` is the leading one: the one `leading` names, else the row's first.
    private func isLeading(_ key: String, first: String?) -> Bool {
        guard let leading else { return false }
        return (leading.wrappedValue ?? first) == key
    }

    var body: some View {
        let heading = row.heading
        switch row.style {
        case .live:
            // On now with listings, Live TV's channels without (only one of them draws).
            VStack(alignment: .leading, spacing: 0) {
                LiveHomeRow()
                LiveOnNowRow(counter: counter, leading: leading)
            }
        case .continue:
            CardRow(heading.title, detail: heading.detail, subtitle: row.subtitle, spacing: wide ? 18 : 12, seeAll: actions.seeAll(row), counter: counter, leading: leading) {
                ForEach(row.continueItems, id: \.cardKey) { item in continueCard(item, leading: isLeading(item.cardKey, first: row.continueItems.first?.cardKey)) }
            }
            .accessibilityIdentifier("row-\(row.id)")
        case .episode:
            CardRow(heading.title, detail: heading.detail, subtitle: row.subtitle, spacing: wide ? 18 : 12, seeAll: actions.seeAll(row), counter: counter, leading: leading) {
                ForEach(row.episodes, id: \.cardKey) { episode in episodeCard(episode, leading: isLeading(episode.cardKey, first: row.episodes.first?.cardKey)) }
            }
            .accessibilityIdentifier("row-\(row.id)")
        case .wide:
            CardRow(heading.title, detail: heading.detail, subtitle: row.subtitle, spacing: wide ? 18 : 12, seeAll: actions.seeAll(row), counter: counter, leading: leading) {
                ForEach(row.items, id: \.cardID) { item in wideCard(item, leading: isLeading(item.cardID, first: row.items.first?.cardID)) }
            }
            .accessibilityIdentifier("row-\(row.id)")
        case .poster:
            CardRow(heading.title, detail: heading.detail, subtitle: row.subtitle, spacing: wide ? 16 : 10, seeAll: actions.seeAll(row), counter: counter, leading: leading) {
                ForEach(row.items, id: \.cardID) { item in
                    Button { actions.open(item) } label: {
                        PosterCard(item: item, imageURL: url(item.posterPath, .w342), width: wide ? 150 : 110, leading: isLeading(item.cardID, first: row.items.first?.cardID))
                    }
                    .buttonStyle(CardButtonStyle())
                    .contextMenu { watchMenu(item) }
                }
            }
            .accessibilityIdentifier("row-\(row.id)")
        }
    }

    private func url(_ path: String?, _ size: ImageSize) -> URL? {
        services.library.imageURL(path, size: size)
    }

    private func continueCard(_ item: ContinueItem, leading: Bool) -> some View {
        Button { actions.resume(item) } label: {
            WideCard(title: item.title, subtitle: item.remainingLine, imageURL: url(item.stillPath ?? item.backdropPath, .w780), seed: item.title, width: wideCardWidth, leading: leading) {
                ProgressStrip(progress: item.progress).pinned(.bottom, padding: 0)
            }
        }
        .buttonStyle(CardButtonStyle())
        .accessibilityHint("Resumes playing")
        .contextMenu {
            if let target = item.watchTarget {
                WatchToggleButton(watched: false) { await actions.setWatched(target, $0) }
            }
            if item.pageRoute != nil {
                Button { actions.openPage(item) } label: { Label(item.pageLabel, systemImage: "info.circle") }
            }
        }
    }

    private func wideCard(_ item: BrowseItem, leading: Bool) -> some View {
        Button { actions.open(item) } label: {
            WideCard(title: item.title, imageURL: url(item.backdropPath, .w780), seed: item.title, width: wideCardWidth, leading: leading) {
                if let date = formatDate(item.releaseDate) { Badge(date).pinned(.bottomLeading) }
                if item.watched { WatchedCheck().pinned(.bottomTrailing) }
            }
        }
        .buttonStyle(CardButtonStyle())
        .contextMenu { watchMenu(item) }
    }

    private func episodeCard(_ episode: EpisodeCard, leading: Bool) -> some View {
        Button { actions.openEpisode(episode) } label: {
            WideCard(title: episode.showTitle, subtitle: episode.airedLine(), imageURL: url(episode.stillPath ?? episode.showBackdropPath, .w780), seed: episode.showTitle, width: wideCardWidth, leading: leading) {
                if episode.isNew { Badge("New", style: .gold).pinned(.topLeading) }
                if !episode.hasFile {
                    Badge("Not in library", style: .warning).pinned(.topTrailing)
                } else if episode.watched {
                    WatchedCheck().pinned(.topTrailing)
                }
                Badge(episode.codeBadge).pinned(.bottomLeading)
            }
        }
        .buttonStyle(CardButtonStyle())
        .accessibilityHint("Opens the episode")
        .contextMenu {
            if episode.playRequest != nil {
                Button { actions.playEpisode(episode) } label: { Label("Play", systemImage: "play.fill") }
            }
            WatchToggleButton(watched: episode.watched) { await actions.setWatched(.episode(id: episode.episodeID), $0) }
        }
    }

    @ViewBuilder private func watchMenu(_ item: BrowseItem) -> some View {
        if let target = item.watchTarget {
            WatchToggleButton(watched: item.watched) { await actions.setWatched(target, $0) }
        }
    }
}

/// Edit Home, or a library's Edit rows: at the end of the rows, it opens their sheet.
struct EditRowsButton: View {
    let title: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Label(title, systemImage: "slider.horizontal.3")
        }
        .buttonStyle(GhostButtonStyle(height: 44, fullWidth: false))
        .accessibilityIdentifier("editRows")
    }
}
