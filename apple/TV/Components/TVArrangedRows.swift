import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// What a row's cards do, from the screen that draws them (Home or a library).
struct TVRowActions {
    var open: (BrowseItem) -> Void
    var openEpisode: (EpisodeCard) -> Void = { _ in }
    var playEpisode: (EpisodeCard) -> Void = { _ in }
    var resume: (ContinueItem) -> Void = { _ in }
    var openPage: (ContinueItem) -> Void = { _ in }
    var setWatched: (WatchTarget, Bool) async -> Void
    /// A list's page, from the See all card at the end of its row.
    var openList: (Int) -> Void
}

/// One arranged row on Apple TV (PLAN-ROWS.md §6.1; TvLibraryRows.dc.html),
/// drawn by its style with Home's cards, or the app's Live TV row in the
/// `live` slot. A list row has its line beside its name ("18 of 50 · From
/// TMDB") and ends in a See all card. On Home's screens (PLAN-HOME.md §7.4)
/// `counter` is "Row 2 of 7", `focusedCard` receives the focused card's key
/// (``BrowseItem/cardID``, ``EpisodeCard/cardKey``, ``ContinueItem/cardKey``)
/// and `focusedChannel` the Live TV row's focused channel.
struct TVArrangedRow: View {
    let row: ScreenRow
    let actions: TVRowActions
    var counter: String?
    var focusedCard: Binding<String?>?
    var focusedChannel: Binding<String?>?
    @Environment(AppServices.self) private var services

    var body: some View {
        let heading = row.heading
        let detail = row.subtitle ?? heading.detail
        switch row.style {
        case .live:
            TVLiveHomeRow(counter: counter, focusedChannel: focusedChannel)
        case .continue:
            ShelfRow(title: heading.title, detail: detail, items: row.continueItems, counter: counter, focusedItem: focusKey { id in row.continueItems.first { $0.id == id }?.cardKey }) { item in continueCard(item) }
        case .episode:
            ShelfRow(title: heading.title, detail: detail, items: row.episodes, counter: counter, focusedItem: focusKey { id in row.episodes.first { $0.id == id }?.cardKey }) { episode in episodeCard(episode) }
        case .wide:
            ShelfRow(title: heading.title, detail: detail, items: cards, counter: counter, focusedItem: itemKey) { card in
                switch card {
                case .item(let item): wideCard(item)
                case .seeAll(let id, let total): SeeAllCard(total: total, width: 320, height: 180) { actions.openList(id) }
                }
            }
        case .poster:
            ShelfRow(title: heading.title, detail: detail, items: cards, counter: counter, focusedItem: itemKey) { card in
                switch card {
                case .item(let item): posterCard(item)
                case .seeAll(let id, let total): SeeAllCard(total: total, width: 160, height: 240) { actions.openList(id) }
                }
            }
        }
    }

    /// `focusedCard` over the row's cards: a See all card leaves the last item's panel up.
    private var itemKey: Binding<ShelfCard.ID?>? {
        focusKey { id in id.hasPrefix("seeAll-") ? nil : id }
    }

    /// `focusedCard` as a binding over a row's own ids, written through `key`.
    private func focusKey<ID: Hashable>(_ key: @escaping (ID) -> String?) -> Binding<ID?>? {
        guard let focusedCard else { return nil }
        return Binding(get: { nil }, set: { id in if let id, let key = key(id) { focusedCard.wrappedValue = key } })
    }

    /// The row's items, then See all for a list.
    private var cards: [ShelfCard] {
        var cards = row.items.map(ShelfCard.item)
        if case .list(let id)? = row.seeAll { cards.append(.seeAll(listID: id, total: listTotal)) }
        return cards
    }

    /// "50", from the line "18 of 50 · From TMDB".
    private var listTotal: String? {
        guard let line = row.subtitle, let range = line.range(of: " of ") else { return nil }
        let total = line[range.upperBound...].prefix { $0.isNumber || $0 == "," }
        return total.isEmpty ? nil : String(total)
    }

    private func url(_ path: String?, _ size: ImageSize) -> URL? {
        services.library.imageURL(path, size: size)
    }

    private func posterCard(_ item: BrowseItem) -> some View {
        Button { actions.open(item) } label: {
            PosterCard(item: item, imageURL: url(item.posterPath, .w500), width: 160)
        }
        .buttonStyle(.borderless)
        .contextMenu { watchMenu(item) }
    }

    private func wideCard(_ item: BrowseItem) -> some View {
        Button { actions.open(item) } label: {
            WideCard(title: item.title, imageURL: url(item.backdropPath, .w780), seed: item.title) {
                if let date = formatDate(item.releaseDate) { TVBadge(date).pinned(.bottomLeading, padding: 10) }
                if item.watched { WatchedCheck(size: 28).pinned(.bottomTrailing, padding: 10) }
            }
        }
        .buttonStyle(.borderless)
        .contextMenu { watchMenu(item) }
    }

    private func continueCard(_ item: ContinueItem) -> some View {
        Button { actions.resume(item) } label: {
            WideCard(title: item.title, line: item.remainingLine, imageURL: url(item.stillPath ?? item.backdropPath, .w780), seed: item.title) {
                ProgressStrip(progress: item.progress, height: 6).pinned(.bottom, padding: 0)
            }
        }
        .buttonStyle(.borderless)
        .accessibilityHint("Resumes playing")
        .contextMenu {
            Button { actions.resume(item) } label: { Label("Resume", systemImage: "play.fill") }
            if let target = item.watchTarget {
                WatchToggleButton(watched: false) { await actions.setWatched(target, $0) }
            }
            if item.pageRoute != nil {
                Button { actions.openPage(item) } label: { Label(item.pageLabel, systemImage: "info.circle") }
            }
        }
    }

    private func episodeCard(_ episode: EpisodeCard) -> some View {
        Button { actions.openEpisode(episode) } label: {
            WideCard(title: episode.showTitle, line: episode.airedLine(), imageURL: url(episode.stillPath ?? episode.showBackdropPath, .w780), seed: episode.showTitle) {
                if episode.isNew { TVBadge("New", style: .gold).pinned(.topLeading, padding: 10) }
                if !episode.hasFile {
                    TVBadge("Not in library", style: .warning).pinned(.topTrailing, padding: 10)
                } else if episode.watched {
                    WatchedCheck(size: 28).pinned(.topTrailing, padding: 10)
                }
                TVBadge(episode.codeBadge).pinned(.bottomLeading, padding: 10)
            }
        }
        .buttonStyle(.borderless)
        .accessibilityHint("Opens the episode on its show")
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

/// A card in a row: an item, or a list's See all at its end.
enum ShelfCard: Identifiable, Hashable {
    case item(BrowseItem)
    case seeAll(listID: Int, total: String?)

    var id: String {
        switch self {
        case .item(let item): item.cardID
        case .seeAll(let id, _): "seeAll-\(id)"
        }
    }
}

/// "See all 50" on a faint card the size of the row's others.
private struct SeeAllCard: View {
    let total: String?
    let width: CGFloat
    let height: CGFloat
    let action: () -> Void

    var body: some View {
        Button(action: action) { Face(total: total, width: width, height: height) }
            .buttonStyle(.borderless)
            .accessibilityLabel(total.map { "See all \($0)" } ?? "See all")
            .accessibilityIdentifier("row-see-all")
    }

    private struct Face: View {
        let total: String?
        let width: CGFloat
        let height: CGFloat
        @Environment(\.isFocused) private var focused

        var body: some View {
            Text(total.map { "See all\n\($0)" } ?? "See all")
                .font(.system(size: 20, weight: .semibold))
                .multilineTextAlignment(.center)
                .foregroundStyle(focused ? Palette.ground : Palette.textSecondary)
                .frame(width: width, height: height)
                .background(focused ? Palette.text : .white.opacity(0.06), in: RoundedRectangle(cornerRadius: 16))
                .focusRim(focused, cornerRadius: 16)
        }
    }
}

/// Edit rows, after a library's rows: opens Home rows' sheet for that screen.
struct TVEditRowsButton: View {
    let action: () -> Void

    var body: some View {
        Button(action: action) { Text("\(Image(systemName: "slider.horizontal.3")) Edit rows") }
            .buttonStyle(TVButtonStyle(height: 60, fontSize: 21))
            .accessibilityIdentifier("editRows")
    }
}
