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
    /// The row's See all: what its card says and what Select
    /// on it does, or nil for a row without one.
    var seeAll: (ScreenRow) -> TVRowSeeAll? = { _ in nil }
}

/// A row's See all card: its words (``SeeAllWords``) and what Select does, a
/// list's page or the library's grid sorted the row's way.
struct TVRowSeeAll {
    let words: SeeAllWords
    let open: () -> Void
}

/// One arranged row on Apple TV,
/// drawn by its style with Home's cards, or the app's Live TV row in the
/// `live` slot. A list row has its line beside its name ("18 of 50 · From
/// TMDB"). A poster, 16:9 or episode row with a See all (a list, or a
/// built-in row's library) ends in a See all card. On
/// Home's screens `counter` is "Row 2 of 7",
/// `focusedCard` receives the focused card's key (``BrowseItem/cardID``,
/// ``EpisodeCard/cardKey``, ``ContinueItem/cardKey``) and `focusedChannel`
/// the Live TV row's focused channel.
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
        case .episode, .wide, .poster:
            let seeAll = actions.seeAll(row)
            ShelfRow(title: heading.title, detail: detail, items: cards(seeAll), counter: counter, focusedItem: cardKey) { card in
                switch card {
                case .item(let item):
                    if row.style == .poster { posterCard(item) } else { wideCard(item) }
                case .episode(let episode):
                    episodeCard(episode)
                case .seeAll:
                    if let seeAll {
                        SeeAllCard(seeAll: seeAll, line: seeAll.words.tvCardLine(listTotal: row.listTotal), poster: row.style == .poster)
                    }
                }
            }
        }
    }

    /// `focusedCard` over the row's cards: the See all card leaves the last
    /// card's panel up, as it never leads the row (``RowPanel/leadingCard(settledOn:was:)``).
    private var cardKey: Binding<ShelfCard.ID?>? {
        focusKey { id in RowPanel.leadingCard(settledOn: id, was: nil) }
    }

    /// `focusedCard` as a binding over a row's own ids, written through `key`.
    private func focusKey<ID: Hashable>(_ key: @escaping (ID) -> String?) -> Binding<ID?>? {
        guard let focusedCard else { return nil }
        return Binding(get: { nil }, set: { id in if let id, let key = key(id) { focusedCard.wrappedValue = key } })
    }

    /// The row's cards, then See all when it has one.
    private func cards(_ seeAll: TVRowSeeAll?) -> [ShelfCard] {
        var cards = row.style == .episode ? row.episodes.map(ShelfCard.episode) : row.items.map(ShelfCard.item)
        if seeAll != nil { cards.append(.seeAll) }
        return cards
    }

    private func url(_ path: String?, _ size: ImageSize) -> URL? {
        services.library.imageURL(path, size: size)
    }

    private func posterCard(_ item: BrowseItem) -> some View {
        Button { actions.open(item) } label: {
            PosterCard(item: item, imageURL: url(item.posterPath, .w500), width: 160)
        }
        .buttonStyle(.borderless)
        .playPauseCard(.card(item)) { actions.open(item) }
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
        .playPauseCard(.card(item)) { actions.open(item) }
        .contextMenu { watchMenu(item) }
    }

    private func continueCard(_ item: ContinueItem) -> some View {
        Button { actions.resume(item) } label: {
            WideCard(title: item.title, line: item.remainingLine, imageURL: url(item.stillPath ?? item.backdropPath, .w780), seed: item.title) {
                ProgressStrip(progress: item.progress, height: 6).pinned(.bottom, padding: 0)
            }
        }
        .buttonStyle(.borderless)
        .accessibilityHint(resumesPlayingHint)
        .playPauseCard(.card(item)) { actions.resume(item) }
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
                    TVBadge(notInLibraryBadge, style: .warning).pinned(.topTrailing, padding: 10)
                } else if episode.watched {
                    WatchedCheck(size: 28).pinned(.topTrailing, padding: 10)
                }
                TVBadge(episode.codeBadge).pinned(.bottomLeading, padding: 10)
            }
        }
        .buttonStyle(.borderless)
        .accessibilityHint("Opens the episode on its show")
        .playPauseCard(.card(episode)) { actions.openEpisode(episode) }
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

/// A card in a row: an item, an episode, or the row's See all at its end.
enum ShelfCard: Identifiable {
    case item(BrowseItem)
    case episode(EpisodeCard)
    case seeAll

    var id: String {
        switch self {
        case .item(let item): item.cardID
        case .episode(let episode): episode.cardKey
        case .seeAll: SeeAllWords.cardKey
        }
    }
}

/// A row's last card, the size of its others:
/// "See all" over a list's count, or "See all TV shows" over "By
/// last aired" for a built-in row's library.
private struct SeeAllCard: View {
    let seeAll: TVRowSeeAll
    let line: String?
    /// A poster row's 160 × 240; else a 16:9 row's 320 × 180.
    let poster: Bool

    var body: some View {
        Button(action: seeAll.open) { Face(words: seeAll.words, line: line, poster: poster) }
            .buttonStyle(.borderless)
            // Play/Pause on it does what Select does, as on a show's card.
            .playPauseCard(.select) { seeAll.open() }
            .accessibilityLabel(seeAll.words.accessibilityLabel)
            .accessibilityIdentifier("row-see-all")
    }

    /// A list's card as the design draws it, "See all" over the
    /// count in one size; a library's with its name over the sort in a
    /// smaller line, "TV shows" under "See all" on a poster, where the whole
    /// name doesn't fit a line.
    private struct Face: View {
        let words: SeeAllWords
        let line: String?
        let poster: Bool
        @Environment(\.isFocused) private var focused

        var body: some View {
            Group {
                if words.destination == .list {
                    Text([words.title, line].compactMap { $0 }.joined(separator: "\n"))
                } else {
                    VStack(spacing: 8) {
                        Text(poster ? words.title.replacingOccurrences(of: "See all ", with: "See all\n") : words.title)
                        if let line { Text(line).font(.system(size: 17, weight: .regular)) }
                    }
                }
            }
            .font(.system(size: 20, weight: .semibold))
            .multilineTextAlignment(.center)
            .foregroundStyle(focused ? Palette.ground : Palette.textSecondary)
            .padding(.horizontal, 12)
            .frame(width: poster ? 160 : 320, height: poster ? 240 : 180)
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
