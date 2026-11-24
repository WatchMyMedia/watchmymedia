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
    var seeAll: (ScreenRow) -> RowSeeAll?
    /// A card from another server in a line ("On Cabin", "from Cabin"), by its server's id and the card.
    var serverLine: (ContinueItem?, String?) -> String? = { _, _ in nil }
    /// What ends a row filled from every server: still asking, and Try again for one that didn't answer.
    var otherServers: (ScreenRow) -> RowServers = { _ in RowServers() }
}

/// The end of a row filled from every server (one card).
struct RowServers {
    var asking: String?
    var failed: [(line: String, retry: () -> Void)] = []
    var isEmpty: Bool { asking == nil && failed.isEmpty }
}

/// A row's See all: what its card and heading link say, and what they open.
struct RowSeeAll {
    let words: SeeAllWords
    let open: () -> Void
}

/// One arranged row, drawn by its style with Home's
/// cards: posters, 16:9 cards, episodes, Continue watching, or the app's
/// Live TV row in the `live` slot. A list row shows its line under the
/// heading ("18 of 50 · From TMDB"). On the iPad's Home
/// screens `counter` is "Row 2 of 7" and `leading` holds
/// the leading card's key, which the row outlines in gold and keeps up to
/// date as the row snaps. A poster, 16:9 or episode row with a See all ends
/// in a ``SeeAllCard`` and has See all in its heading.
struct ArrangedRow: View {
    let row: ScreenRow
    let actions: RowActions
    var counter: String?
    var leading: Binding<String?>?
    /// The page's side margin, where it isn't the iPhone's or the iPad's own (a show page's 40).
    var margin: CGFloat?
    @Environment(\.horizontalSizeClass) private var sizeClass

    private var wide: Bool { sizeClass.isWide }
    private var wideCardWidth: CGFloat { wide ? 248 : 220 }

    /// The card with `key` is the leading one: the one `leading` names, else the row's first.
    private func isLeading(_ key: String, first: String?) -> Bool {
        guard let leading else { return false }
        return (leading.wrappedValue ?? first) == key
    }

    /// `leading` as the row reports it: the See all card never leads, so the
    /// panel above stays on the card it was on (``RowPanel/leadingCard(settledOn:was:)``).
    private var leadingCard: Binding<String?>? {
        guard let leading else { return nil }
        return Binding(get: { leading.wrappedValue }, set: { leading.wrappedValue = RowPanel.leadingCard(settledOn: $0, was: leading.wrappedValue) })
    }

    /// The row's card sizes: the See all card is as big as their artwork.
    private var posterSize: CGSize { wide ? CGSize(width: 150, height: 225) : CGSize(width: 110, height: 165) }
    private var wideSize: CGSize { CGSize(width: wideCardWidth, height: (wideCardWidth * 9 / 16).rounded()) }

    @ViewBuilder private func seeAllCard(_ seeAll: RowSeeAll?, size: CGSize) -> some View {
        if let seeAll { SeeAllCard(seeAll: seeAll, size: size) }
    }

    var body: some View {
        let heading = row.heading
        let seeAll = row.style == .continue ? nil : actions.seeAll(row)
        switch row.style {
        case .live:
            // On now with listings, Live TV's channels without (only one of them draws).
            VStack(alignment: .leading, spacing: 0) {
                LiveHomeRow()
                LiveOnNowRow(counter: counter, leading: leading)
            }
        case .continue:
            // Continue watching has no See all.
            CardRow(heading.title, detail: heading.detail, subtitle: row.subtitle, spacing: wide ? 18 : 12, counter: counter, leading: leading) {
                ForEach(row.continueItems, id: \.cardKey) { item in
                    ContinueCard(item: item, actions: actions, width: wideCardWidth, leading: isLeading(item.cardKey, first: row.continueItems.first?.cardKey))
                }
                ServerEndCards(servers: actions.otherServers(row), size: wideSize)
            }
            .accessibilityIdentifier("row-\(row.id)")
        case .episode:
            CardRow(heading.title, detail: heading.detail, subtitle: row.subtitle, spacing: wide ? 18 : 12, seeAll: seeAll?.open, seeAllLabel: seeAll?.words.accessibilityLabel, counter: counter, leading: leadingCard) {
                ForEach(row.episodes, id: \.cardKey) { episode in
                    EpisodeRowCard(episode: episode, actions: actions, width: wideCardWidth, leading: isLeading(episode.cardKey, first: row.episodes.first?.cardKey))
                }
                ServerEndCards(servers: actions.otherServers(row), size: wideSize)
                seeAllCard(seeAll, size: wideSize)
            }
            .accessibilityIdentifier("row-\(row.id)")
        case .wide:
            CardRow(heading.title, detail: heading.detail, subtitle: row.subtitle, spacing: wide ? 18 : 12, seeAll: seeAll?.open, seeAllLabel: seeAll?.words.accessibilityLabel, counter: counter, leading: leadingCard, margin: margin) {
                ForEach(row.items, id: \.cardID) { item in
                    WideItemCard(item: item, actions: actions, width: wideCardWidth, leading: isLeading(item.cardID, first: row.items.first?.cardID))
                }
                ServerEndCards(servers: actions.otherServers(row), size: wideSize)
                seeAllCard(seeAll, size: wideSize)
            }
            .accessibilityIdentifier("row-\(row.id)")
        case .poster:
            CardRow(heading.title, detail: heading.detail, subtitle: row.subtitle, spacing: wide ? 16 : 10, seeAll: seeAll?.open, seeAllLabel: seeAll?.words.accessibilityLabel, counter: counter, leading: leadingCard, margin: margin) {
                ForEach(row.items, id: \.cardID) { item in
                    PosterItemCard(item: item, actions: actions, width: posterSize.width, leading: isLeading(item.cardID, first: row.items.first?.cardID))
                }
                ServerEndCards(servers: actions.otherServers(row), size: posterSize)
                seeAllCard(seeAll, size: posterSize)
            }
            .accessibilityIdentifier("row-\(row.id)")
        }
    }
}

/// The card at the end of a row:
/// as big as the row's artwork, with no title under it. A grid glyph on a gold
/// tint (a list's glyph for a list), "See all TV shows" and "By last aired".
/// It opens what the heading's See all opens. On iPad's Home screens it is
/// tapped like any card and never leads the row, so the panel has nothing of its own for it.
struct SeeAllCard: View {
    let seeAll: RowSeeAll
    let size: CGSize
    @Environment(\.horizontalSizeClass) private var sizeClass

    private var glyph: String {
        if case .list = seeAll.words.destination { return "list.bullet" }
        return "square.grid.2x2"
    }

    var body: some View {
        let wide = sizeClass.isWide
        let title = seeAll.words.title
        Button(action: seeAll.open) {
            VStack(spacing: wide ? 8 : 6) {
                Image(systemName: glyph)
                    .font(.system(size: wide ? 20 : 17, weight: .semibold))
                    .foregroundStyle(Palette.gold)
                    .frame(width: wide ? 44 : 36, height: wide ? 44 : 36)
                    .background(Palette.gold.opacity(0.18), in: Circle())
                // "See all TV shows" on one line where it fits, else as "See all" over "TV shows".
                ViewThatFits(in: .horizontal) {
                    titleText(title).lineLimit(1)
                    titleText(title.replacingOccurrences(of: "See all ", with: "See all\n"))
                }
                if let detail = seeAll.words.detail {
                    Text(detail)
                        .scaledFont(13)
                        .foregroundStyle(Palette.muted)
                        .multilineTextAlignment(.center)
                        .minimumScaleFactor(0.7)
                }
            }
            .padding(.horizontal, wide ? 14 : 10)
            .frame(width: size.width, height: size.height)
            .background(Palette.surfaceRaised, in: RoundedRectangle(cornerRadius: 10))
            .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(Palette.outline))
            .contentShape(RoundedRectangle(cornerRadius: 10))
        }
        .buttonStyle(CardButtonStyle())
        .accessibilityLabel(seeAll.words.accessibilityLabel)
        .accessibilityIdentifier("row-see-all")
        .id(SeeAllWords.cardKey)
    }

    private func titleText(_ text: String) -> some View {
        Text(text)
            .scaledFont(sizeClass.isWide ? 16 : 15, weight: .semibold)
            .foregroundStyle(Palette.text)
            .multilineTextAlignment(.center)
            .minimumScaleFactor(0.7)
    }
}

// MARK: Home's cards, wherever a row draws them

/// A Continue watching card: resumes on a tap; Mark watched and the page on a long press.
struct ContinueCard: View {
    let item: ContinueItem
    let actions: RowActions
    let width: CGFloat
    var leading = false
    @Environment(AppServices.self) private var services

    var body: some View {
        Button { actions.resume(item) } label: {
            WideCard(title: item.title, subtitle: [item.remainingLine, actions.serverLine(item, item.server)].compactMap { $0 }.joined(separator: " · "),
                     imageURL: services.library.imageURL(item.stillPath ?? item.backdropPath, size: .w780, on: item.server), seed: item.title, width: width, leading: leading) {
                ProgressStrip(progress: item.progress).pinned(.bottom, padding: 0)
            }
        }
        .buttonStyle(CardButtonStyle())
        .accessibilityHint(resumesPlayingHint)
        .contextMenu { ContinueWatchingMenu(item: item, actions: actions) }
    }
}

/// Continue watching's long press, the one menu for every card of it: Mark
/// watched, and the title's page.
struct ContinueWatchingMenu: View {
    let item: ContinueItem
    let actions: RowActions

    var body: some View {
        if let target = item.watchTarget {
            WatchToggleButton(watched: false) { await actions.setWatched(target, $0) }
        }
        if item.pageRoute != nil {
            Button { actions.openPage(item) } label: { Label(item.pageLabel, systemImage: "info.circle") }
        }
    }
}

/// A 16:9 card of a title (Recently released): its release date and watched check.
struct WideItemCard: View {
    let item: BrowseItem
    let actions: RowActions
    let width: CGFloat
    var leading = false
    @Environment(AppServices.self) private var services

    var body: some View {
        Button { actions.open(item) } label: {
            WideCard(title: item.title, subtitle: actions.serverLine(nil, item.server), imageURL: services.library.imageURL(item.backdropPath, size: .w780, on: item.server), seed: item.title, width: width, leading: leading) {
                if let date = formatDate(item.releaseDate) { Badge(date).pinned(.bottomLeading) }
                if item.watched { WatchedCheck().pinned(.bottomTrailing) }
            }
        }
        .buttonStyle(CardButtonStyle())
        .contextMenu { WatchMenu(item: item, actions: actions) }
    }
}

/// A poster card (Recently added): opens the title; Mark watched on a long press.
struct PosterItemCard: View {
    let item: BrowseItem
    let actions: RowActions
    let width: CGFloat
    var leading = false
    @Environment(AppServices.self) private var services

    var body: some View {
        Button { actions.open(item) } label: {
            PosterCard(item: item, imageURL: services.library.imageURL(item.posterPath, size: .w342, on: item.server), width: width, leading: leading,
                       server: actions.serverLine(nil, item.server))
        }
        .buttonStyle(CardButtonStyle())
        .contextMenu { WatchMenu(item: item, actions: actions) }
    }
}

/// An episode's card (Recently aired): opens the episode; Play and Mark watched on a long press.
struct EpisodeRowCard: View {
    let episode: EpisodeCard
    let actions: RowActions
    let width: CGFloat
    var leading = false
    @Environment(AppServices.self) private var services

    var body: some View {
        Button { actions.openEpisode(episode) } label: {
            WideCard(title: episode.showTitle, subtitle: [episode.airedLine(), actions.serverLine(nil, episode.server)].compactMap { $0 }.joined(separator: " · "),
                     imageURL: services.library.imageURL(episode.stillPath ?? episode.showBackdropPath, size: .w780, on: episode.server), seed: episode.showTitle, width: width, leading: leading) {
                if episode.isNew { Badge("New", style: .gold).pinned(.topLeading) }
                if !episode.hasFile {
                    Badge(notInLibraryBadge, style: .warning).pinned(.topTrailing)
                } else if episode.watched {
                    WatchedCheck().pinned(.topTrailing)
                }
                Badge(episode.codeBadge).pinned(.bottomLeading)
            }
        }
        .buttonStyle(CardButtonStyle())
        .accessibilityHint(opensEpisodeHint)
        .contextMenu {
            if episode.playRequest != nil {
                Button { actions.playEpisode(episode) } label: { Label("Play", systemImage: "play.fill") }
            }
            WatchToggleButton(watched: episode.watched) { await actions.setWatched(.episode(id: episode.episodeID), $0) }
        }
    }
}

/// A title's long press: Mark watched or unwatched, when it can be marked;
/// then Add to Watchlist, Remove from Watchlist or Save to watchlist….
struct WatchMenu: View {
    let item: BrowseItem
    let actions: RowActions

    var body: some View {
        if let target = item.watchTarget {
            WatchToggleButton(watched: item.watched) { await actions.setWatched(target, $0) }
        }
        Divider()
        WatchlistMenuButton(item)
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

/// At the end of a row filled from every server: "Still asking Den
/// server…" while one is out, and a card for one that didn't answer, with
/// Try again.
struct ServerEndCards: View {
    let servers: RowServers
    let size: CGSize

    var body: some View {
        if let asking = servers.asking {
            VStack(alignment: .leading, spacing: 8) {
                ProgressView().controlSize(.small)
                Text(asking).scaledFont(12).foregroundStyle(Palette.muted)
            }
            .padding(12)
            .frame(width: size.width, height: size.height, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 10).strokeBorder(Palette.separator, style: StrokeStyle(lineWidth: 1, dash: [6, 4])))
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("row-server-asking")
        }
        ForEach(Array(servers.failed.enumerated()), id: \.offset) { _, failed in
            Button(action: failed.retry) {
                VStack(alignment: .leading, spacing: 8) {
                    Text(failed.line).scaledFont(12).foregroundStyle(Palette.textSecondary).fixedSize(horizontal: false, vertical: true)
                    Text("Try again").scaledFont(13, weight: .semibold).foregroundStyle(Palette.gold)
                }
                .padding(12)
                .frame(width: size.width, height: size.height, alignment: .leading)
                .background(Palette.surface, in: RoundedRectangle(cornerRadius: 10))
            }
            .buttonStyle(CardButtonStyle())
            .accessibilityIdentifier("row-server-retry")
        }
    }
}
