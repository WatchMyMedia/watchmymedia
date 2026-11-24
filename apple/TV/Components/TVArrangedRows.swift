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
    /// Remove from Continue watching, where the server offers it.
    var removeFromContinue: ((ContinueItem) async -> Void)?
    /// What ends a row filled from every server (one card): "Still asking
    /// Den server…" while one is out, and a tile with Try again for each
    /// that didn't answer.
    var otherServers: (ScreenRow) -> TVRowServers = { _ in TVRowServers() }
    /// The row's See all: what its card says and what Select
    /// on it does, or nil for a row without one.
    var seeAll: (ScreenRow) -> TVRowSeeAll? = { _ in nil }
}

/// The end of a row filled from every server.
struct TVRowServers {
    /// "Still asking Den server…", a placeholder that takes no focus.
    var asking: String?
    /// Each server that didn't answer: its line, and Try again.
    var failed: [(server: CardServer, line: String, retry: () -> Void)] = []
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
            let servers = actions.otherServers(row)
            ShelfRow(title: heading.title, detail: detail, items: row.continueItems.map(ShelfCard.continuing) + ends(servers), counter: counter, focusedItem: cardKey) { card in
                switch card {
                case .continuing(let item): continueCard(item)
                default: serverEnd(card, servers: servers, poster: false)
                }
            }
        case .episode, .wide, .poster:
            let seeAll = actions.seeAll(row)
            let servers = actions.otherServers(row)
            ShelfRow(title: heading.title, detail: detail, items: cards(seeAll) + ends(servers), counter: counter, focusedItem: cardKey) { card in
                switch card {
                case .item(let item):
                    if row.style == .poster { posterCard(item) } else { wideCard(item) }
                case .episode(let episode):
                    episodeCard(episode)
                case .seeAll:
                    if let seeAll {
                        SeeAllCard(seeAll: seeAll, line: seeAll.words.tvCardLine(listTotal: row.listTotal), poster: row.style == .poster)
                    }
                default:
                    serverEnd(card, servers: servers, poster: row.style == .poster)
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

    /// The cards that end a row filled from every server.
    private func ends(_ servers: TVRowServers) -> [ShelfCard] {
        (servers.asking.map { [ShelfCard.asking($0)] } ?? []) + servers.failed.map { ShelfCard.failed($0.server.id) }
    }

    @ViewBuilder private func serverEnd(_ card: ShelfCard, servers: TVRowServers, poster: Bool) -> some View {
        switch card {
        case .asking(let words):
            ServerTile(words: words, poster: poster, retry: nil)
        case .failed(let id):
            if let failed = servers.failed.first(where: { $0.server.id == id }) {
                ServerTile(words: failed.line, poster: poster, retry: failed.retry)
            }
        default:
            EmptyView()
        }
    }

    /// The row's cards, then See all when it has one.
    private func cards(_ seeAll: TVRowSeeAll?) -> [ShelfCard] {
        var cards = row.style == .episode ? row.episodes.map(ShelfCard.episode) : row.items.map(ShelfCard.item)
        if seeAll != nil { cards.append(.seeAll) }
        return cards
    }

    private func url(_ path: String?, _ size: ImageSize, on server: String? = nil) -> URL? {
        services.library.imageURL(path, size: size, on: server)
    }

    private func posterCard(_ item: BrowseItem) -> some View {
        Button { actions.open(item) } label: {
            // No words under it: the screen's words above the row are the focused card's.
            PosterCard(item: item, imageURL: url(item.posterPath, .w500, on: item.server), caption: false)
        }
        .buttonStyle(.borderless)
        .playPauseCard(.card(item)) { actions.open(item) }
        .contextMenu { watchMenu(item) }
    }

    private func wideCard(_ item: BrowseItem) -> some View {
        Button { actions.open(item) } label: {
            WideCard(title: item.title, imageURL: url(item.backdropPath, .w780, on: item.server), seed: item.title) {
                if let date = formatDate(item.releaseDate) { TVBadge(date).pinned(.bottomLeading) }
                if item.watched { WatchedCheck(size: 40).pinned(.topTrailing) }
            }
        }
        .buttonStyle(.borderless)
        .playPauseCard(.card(item)) { actions.open(item) }
        .contextMenu { watchMenu(item) }
    }

    private func continueCard(_ item: ContinueItem) -> some View {
        Button { actions.resume(item) } label: {
            WideCard(title: item.title, line: item.remainingLine, imageURL: url(item.stillPath ?? item.backdropPath, .w780, on: item.server), seed: item.title) {
                TVProgressBar(progress: item.progress).pinned(.bottom, padding: 14)
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
            if let remove = actions.removeFromContinue {
                Button { Task { await remove(item) } } label: { Label(removeFromContinueLabel, systemImage: "minus.circle") }
            }
        }
    }

    private func episodeCard(_ episode: EpisodeCard) -> some View {
        Button { actions.openEpisode(episode) } label: {
            WideCard(title: episode.showTitle, line: episode.airedLine(), imageURL: url(episode.stillPath ?? episode.showBackdropPath, .w780, on: episode.server), seed: episode.showTitle) {
                if episode.isNew { TVBadge("New", style: .gold).pinned(.topLeading) }
                if !episode.hasFile {
                    TVBadge(notInLibraryBadge, style: .warning).pinned(.topTrailing)
                } else if episode.watched {
                    WatchedCheck(size: 40).pinned(.topTrailing)
                }
                TVBadge(episode.codeBadge).pinned(.bottomLeading)
            }
        }
        .buttonStyle(.borderless)
        .accessibilityHint("Opens the episode on its show")
        .playPauseCard(.card(episode)) { actions.openEpisode(episode) }
        .contextMenu {
            if episode.playRequest != nil {
                Button { actions.playEpisode(episode) } label: { Label("Play", systemImage: "play.fill") }
            }
            WatchToggleButton(watched: episode.watched) { await actions.setWatched(episode.watchTarget, $0) }
        }
    }

    /// Add to Watchlist (or Remove, or Save to watchlist…) first, then Mark watched.
    @ViewBuilder private func watchMenu(_ item: BrowseItem) -> some View {
        TVWatchlistMenuButton(item)
        if let target = item.watchTarget {
            WatchToggleButton(watched: item.watched) { await actions.setWatched(target, $0) }
        }
    }
}

/// A card in a row: an item, an episode, or the row's See all at its end.
enum ShelfCard: Identifiable {
    case item(BrowseItem)
    case episode(EpisodeCard)
    case continuing(ContinueItem)
    case seeAll
    /// "Still asking Den server…" at the end of a row filled from every server.
    case asking(String)
    /// A server that didn't answer, by id: its tile with Try again.
    case failed(String)

    var id: String {
        switch self {
        case .item(let item): item.cardID
        case .episode(let episode): episode.cardKey
        case .continuing(let item): item.cardKey
        case .seeAll: SeeAllWords.cardKey
        case .asking: "server-asking"
        case .failed(let id): "server-failed-\(id)"
        }
    }
}

/// The end of a row filled from every server: "Still asking Den server…",
/// which takes no focus, or a server that didn't answer, which does, and
/// asks it again on Select.
private struct ServerTile: View {
    let words: String
    let poster: Bool
    let retry: (() -> Void)?

    var body: some View {
        if let retry {
            Button(action: retry) { Face(words: words, retry: true, poster: poster) }
                .buttonStyle(.borderless)
                .accessibilityLabel("\(words) Try again")
                .accessibilityIdentifier("row-server-retry")
        } else {
            Face(words: words, retry: false, poster: poster)
                .accessibilityIdentifier("row-server-asking")
        }
    }

    private struct Face: View {
        let words: String
        let retry: Bool
        let poster: Bool
        @Environment(\.isFocused) private var focused

        var body: some View {
            VStack(alignment: .leading, spacing: 10) {
                if !retry { ProgressView().tint(Palette.muted) }
                Text(words).font(.tv(.caption1)).foregroundStyle(focused ? Palette.ground : Palette.textSecondary)
                if retry {
                    Text("Try again").font(.tv(.caption1, .semibold)).foregroundStyle(focused ? Palette.ground : Palette.gold)
                }
            }
            .lineLimit(4)
            .padding(.horizontal, 24)
            .frame(width: poster ? TVCardWidth.poster : TVCardWidth.wide, height: poster ? 390 : 180, alignment: .leading)
            .background(focused ? Palette.text : Palette.surface.opacity(retry ? 1 : 0.6), in: RoundedRectangle(cornerRadius: 14))
            .overlay { if !focused { RoundedRectangle(cornerRadius: 14).strokeBorder(Color(hex: 0x34343A), style: StrokeStyle(lineWidth: 2, dash: retry ? [] : [8, 6])) } }
            .focusLift(focused, scale: 1.08)
        }
    }
}

/// A row's last card, a tile the size of its others:
/// "See all" over a list's count, or "See all TV shows" over "By
/// last aired" for a built-in row's library.
private struct SeeAllCard: View {
    let seeAll: TVRowSeeAll
    let line: String?
    /// A poster row's 260 × 390; else a 16:9 row's 320 × 180.
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
            VStack(alignment: .leading, spacing: 6) {
                Text(poster ? words.title.replacingOccurrences(of: "See all ", with: "See all\n") : words.title)
                    .font(.tv(.callout))
                    .foregroundStyle(focused ? Palette.ground : Palette.text)
                if let line {
                    Text(line).font(.tv(.caption2)).foregroundStyle(focused ? Color.tvOnWhiteMuted : Palette.muted)
                }
            }
            .lineLimit(3)
            .padding(.horizontal, 28)
            .frame(width: poster ? TVCardWidth.poster : TVCardWidth.wide, height: poster ? 390 : 180, alignment: .leading)
            .background(focused ? Palette.text : Palette.surfaceRaised, in: RoundedRectangle(cornerRadius: 14))
            .overlay { if !focused { RoundedRectangle(cornerRadius: 14).strokeBorder(Color(hex: 0x34343A), lineWidth: 2) } }
            .focusLift(focused, scale: 1.08)
        }
    }
}

/// Edit rows, after a library's rows: opens Home rows' sheet for that screen.
struct TVEditRowsButton: View {
    let action: () -> Void

    var body: some View {
        Button(action: action) { Text("\(Image(systemName: "slider.horizontal.3")) Edit rows") }
            .buttonStyle(TVButtonStyle())
            .accessibilityIdentifier("editRows")
    }
}
