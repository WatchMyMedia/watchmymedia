import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// Home (TvHomeStart.dc.html, TvHomeRow.dc.html): one row a screen
/// (PLAN-HOME.md §7). The first screen is the hero with Play and Go to show
/// and the first row of the person's layout; every row after it fills a
/// screen of its own, the focused card's picture, kicker, title, facts and
/// overview above the row and the next row's name along the bottom. ▼ pages
/// to the next row, ▲ back; Menu on a row screen returns to the first. The
/// last screen is Edit rows, Back to the top and the totals. Select on a
/// Continue watching card resumes it; a Recently aired card opens its
/// episode. Long press marks watched. The rows come in the person's order
/// from API level 22 (PLAN-ROWS.md), arranged in Settings › Home rows; below
/// it Home keeps its fixed rows on one scrolling page (TvHome.dc.html).
struct HomeScreen: View {
    @Environment(AppServices.self) private var services
    @Environment(Navigator.self) private var navigator

    var body: some View {
        HomeView(model: HomeModel(library: services.library, auth: services.auth, navigator: navigator, rows: services.rows))
    }
}

private struct HomeView: View {
    @State var model: HomeModel
    @Environment(AppServices.self) private var services
    @Environment(Navigator.self) private var navigator
    @Environment(\.imageLoader) private var imageLoader

    /// The screen on view, by index: 0 the hero's, then one per row after the first, then the end screen.
    @State private var page: Int?
    /// The screen that holds focus; a container's focus state, so Back to the
    /// top and Menu can move focus to the first screen.
    @FocusState private var focusedPage: Int?
    /// Each row's focused card, by row id, for the panel above it.
    @State private var focusedCards: [String: String] = [:]
    /// The Live TV row's focused channel, or ``TVLiveHomeRow/guideKey``.
    @State private var focusedChannel: String?
    @State private var editingRows = false

    private var liveAvailable: Bool { services.liveTV.capabilities?.available == true }

    var body: some View {
        Group {
            if let home = model.state.value {
                if home.isArranged && !home.isEmpty {
                    screens(home)
                } else {
                    page(home)
                }
            } else {
                TabPage {
                    VStack(spacing: 34) {
                        TVLiveHomeRow()
                        TVLoadable(model.state, retry: model.retry) { _ in EmptyView() }
                            .frame(minHeight: 300)
                    }
                }
            }
        }
        .task { await model.run() }
        .messagePill($model.message)
        .noticeAlert($model.notice)
        .fullScreenCover(isPresented: $editingRows) { TVRowEditor(screen: .home, screenName: "Home") }
    }

    private func url(_ path: String?, _ size: ImageSize) -> URL? {
        services.library.imageURL(path, size: size)
    }

    // MARK: One row a screen

    /// The paging screens: the hero and the first row, a screen per further row, the end screen.
    private func screens(_ home: HomeData) -> some View {
        let rows = home.drawnRows(liveTV: liveAvailable)
        let endPage = max(rows.count, 1)
        let atTop = (page ?? 0) == 0
        return ScrollView(.vertical) {
            screenStack(home, rows: rows, endPage: endPage)
        }
        .scrollTargetBehavior(.paging)
        .scrollPosition(id: $page)
        .scrollIndicators(.hidden)
        .scrollClipDisabled()
        .ignoresSafeArea()
        // Focus moving into another screen pages to it.
        .onChange(of: focusedPage) { _, focused in
            guard let focused, focused != page ?? 0 else { return }
            withAnimation(.easeInOut(duration: 0.3)) { page = focused }
        }
        // Menu on a row screen goes back to the first screen; from there it is the system's.
        .onExitCommand(perform: atTop ? nil : { backToTop() })
    }

    private func screenStack(_ home: HomeData, rows: [ScreenRow], endPage: Int) -> some View {
        VStack(spacing: 0) {
            // Without a hero (nothing added lately), the first row has a screen of its own like the others.
            if home.hero == nil, let first = rows.first {
                rowScreen(first, index: 0, rows: rows)
                    .id(0)
            } else {
                startScreen(home, rows: rows)
                    .id(0)
            }
            ForEach(Array(rows.enumerated().dropFirst()), id: \.element.id) { index, row in
                rowScreen(row, index: index, rows: rows)
                    .id(index)
            }
            endScreen(home, rows: rows, index: endPage)
                .id(endPage)
        }
        .scrollTargetLayout()
    }

    private func backToTop() {
        withAnimation(.easeInOut(duration: 0.3)) { page = 0 }
        focusedPage = 0
    }

    /// Screen 1: the hero and the first row (TvHomeStart.dc.html).
    private func startScreen(_ home: HomeData, rows: [ScreenRow]) -> some View {
        ZStack(alignment: .topTrailing) {
            if let hero = home.hero { backdrop(url(hero.backdropPath, .w1280), seed: hero.title) }
            VStack(alignment: .leading, spacing: 0) {
                if let hero = home.hero {
                    HomeHero(
                        hero: hero,
                        tags: home.heroBadges,
                        canPlay: hero.fileID != nil,
                        onPlay: model.playHero,
                        onOpen: model.openHero
                    )
                    .padding(.horizontal, TVLayout.gutter)
                    .padding(.top, 160)
                    .frame(maxWidth: .infinity, alignment: .topLeading)
                    // Across the whole width, so Down from any tab lands on Play.
                    .focusSection()
                }
                Spacer(minLength: 0)
                if let first = rows.first {
                    arrangedRow(first, index: 0, rows: rows)
                }
                nextRowLine(rows.count > 1 ? rows[1].screenName : HomeEndScreen.name, index: 0)
            }
            .overlay(alignment: .top) { TabBarAccessories() }
        }
        .screen()
        .focused($focusedPage, equals: 0)
    }

    /// A row's screen: the focused card's picture and panel, then the row (TvHomeRow.dc.html).
    private func rowScreen(_ row: ScreenRow, index: Int, rows: [ScreenRow]) -> some View {
        let key = focusedCards[row.id]
        let panel = panel(for: row, card: key)
        return ZStack(alignment: .topTrailing) {
            if let panel { backdrop(panel.pictureURL ?? url(panel.picturePath, .w1280), seed: panel.seed) }
            VStack(alignment: .leading, spacing: 0) {
                if let panel {
                    FocusPanel(panel: panel, moreInRow: row.hasSeveralCards)
                        .padding(.horizontal, TVLayout.gutter)
                        .padding(.top, 150)
                        .accessibilityIdentifier("homeFocusPanel-\(row.id)")
                }
                Spacer(minLength: 0)
                arrangedRow(row, index: index, rows: rows)
                nextRowLine(rows.indices.contains(index + 1) ? rows[index + 1].screenName : HomeEndScreen.name, index: index)
            }
        }
        .screen()
        .focused($focusedPage, equals: index)
        // The cards either side are fetched ahead, so ◀ ▶ does not flash the placeholder.
        .task(id: key) {
            for neighbour in RowPanel.neighbours(for: row, card: key) {
                await Artwork.prefetch(neighbour.pictureURL ?? url(neighbour.picturePath, .w1280), maxPixel: 1920, loader: imageLoader)
            }
        }
    }

    /// The last screen: Edit rows, Back to the top and the totals.
    private func endScreen(_ home: HomeData, rows: [ScreenRow], index: Int) -> some View {
        VStack(alignment: .leading, spacing: 22) {
            Text(HomeEndScreen.title)
                .font(.system(size: 44, weight: .bold))
                .tracking(-0.9)
                .foregroundStyle(Palette.text)
                .accessibilityAddTraits(.isHeader)
            Text(HomeEndScreen.message)
                .font(.system(size: 22))
                .lineSpacing(6)
                .foregroundStyle(Palette.textSecondary)
                .frame(maxWidth: 760, alignment: .leading)
            HStack(spacing: 20) {
                if model.canEditRows {
                    TVEditRowsButton { editingRows = true }
                }
                Button(HomeEndScreen.backToTop, action: backToTop)
                    .buttonStyle(TVButtonStyle(height: 60, fontSize: 21))
                    .accessibilityIdentifier("homeBackToTop")
            }
            .padding(.top, 6)
            // Across the width, so Up and Down reach the buttons from anywhere on the row above.
            .frame(maxWidth: .infinity, alignment: .leading)
            .focusSection()
            Text(home.totalsLine())
                .font(.system(size: 17))
                .foregroundStyle(Palette.faint)
                .padding(.top, 18)
        }
        .padding(.horizontal, TVLayout.gutter)
        .padding(.top, 330)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .screen()
        .focused($focusedPage, equals: index)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("homeEnd")
    }

    private func arrangedRow(_ row: ScreenRow, index: Int, rows: [ScreenRow]) -> some View {
        TVArrangedRow(
            row: row, actions: rowActions, counter: rowCounter(index: index, of: rows.count),
            focusedCard: Binding(get: { focusedCards[row.id] }, set: { focusedCards[row.id] = $0 }),
            focusedChannel: $focusedChannel
        )
        // Its own height: a horizontal scroll view would take all the screen left over the panel.
        .fixedSize(horizontal: false, vertical: true)
    }

    /// The panel for a row's focused card; the Live TV row's is the focused channel's programme.
    private func panel(for row: ScreenRow, card key: String?) -> RowPanel? {
        guard row.style == .live else { return RowPanel.panel(for: row, card: key) }
        if focusedChannel == TVLiveHomeRow.guideKey { return .fullGuide }
        let channels = services.liveTV.channels.filter(\.available)
        guard let channel = channels.first(where: { $0.id == focusedChannel }) ?? channels.first else { return nil }
        return RowPanel(channel: channel, now: .now)
    }

    /// The next row's name along the bottom edge, with a chevron (TvHomeRow.dc.html's `.peek`).
    private func nextRowLine(_ name: String, index: Int) -> some View {
        HStack(spacing: 12) {
            Image(systemName: "chevron.down").font(.system(size: 17, weight: .semibold))
            Text(name).lineLimit(1)
        }
        .font(.system(size: 19))
        .foregroundStyle(Palette.muted)
        .frame(maxWidth: .infinity, minHeight: 60, alignment: .leading)
        .overlay(alignment: .top) { Rectangle().fill(Palette.separator).frame(height: 1) }
        .padding(.horizontal, TVLayout.gutter)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Next row: \(name)")
        .accessibilityIdentifier("homeNextRow-\(index)")
    }

    /// The picture across the top right of a screen, fading to the ground at the left and bottom.
    private func backdrop(_ url: URL?, seed: String) -> some View {
        CrossfadeArtwork(url: url, seed: seed, maxPixel: 1920)
            .frame(width: 1400, height: 660)
            .overlay(RadialGradient(colors: [Palette.gold.opacity(0.10), .clear], center: UnitPoint(x: 0.7, y: 0.3), startRadius: 0, endRadius: 700))
            .overlay(LinearGradient(stops: [.init(color: Palette.ground, location: 0), .init(color: Palette.ground.opacity(0.85), location: 0.22), .init(color: Palette.ground.opacity(0), location: 0.6)], startPoint: .leading, endPoint: .trailing))
            .overlay(LinearGradient(stops: [.init(color: Palette.ground.opacity(0), location: 0.5), .init(color: Palette.ground, location: 1)], startPoint: .top, endPoint: .bottom))
            .clipped()
            .accessibilityHidden(true)
    }

    // MARK: One scrolling page (older servers, and an empty Home)

    private func page(_ home: HomeData) -> some View {
        TabPage {
            VStack(alignment: .leading, spacing: 34) {
                if let hero = home.hero {
                    HomeHero(
                        hero: hero,
                        tags: home.heroBadges,
                        canPlay: hero.fileID != nil,
                        onPlay: model.playHero,
                        onOpen: model.openHero
                    )
                    .padding(.horizontal, TVLayout.gutter)
                    .frame(maxWidth: .infinity, minHeight: 360, alignment: .topLeading)
                    // Across the whole width, so Down from any tab lands on Play.
                    .focusSection()
                    .background(alignment: .topTrailing) { heroBackdrop(hero) }
                }
                if home.isArranged {
                    TVLiveHomeRow()
                    firstRun
                } else {
                    fixedRows(home)
                }
                Text(home.totalsLine())
                    .font(.system(size: 17))
                    .foregroundStyle(Palette.faint)
                    .padding(.top, 12)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .overlay(alignment: .top) { Rectangle().fill(Palette.separator).frame(height: 1) }
                    .padding(.horizontal, TVLayout.gutter)
            }
            .padding(.bottom, 60)
        }
    }

    private var rowActions: TVRowActions {
        TVRowActions(
            open: { model.open($0) },
            openEpisode: { model.open($0) },
            playEpisode: { model.play($0) },
            resume: { model.resume($0) },
            openPage: { model.openPage(of: $0) },
            setWatched: { await model.setWatched($0, $1) },
            openList: { model.open(.list(id: $0)) }
        )
    }

    /// Today's rows, from servers below API level 22.
    @ViewBuilder private func fixedRows(_ home: HomeData) -> some View {
                if !home.continueWatching.isEmpty {
                    ShelfRow(title: "Continue watching", items: home.continueWatching) { item in
                        Button { model.resume(item) } label: {
                            WideCard(title: item.title, line: item.remainingLine, imageURL: url(item.stillPath ?? item.backdropPath, .w780), seed: item.title) {
                                ProgressStrip(progress: item.progress, height: 6).pinned(.bottom, padding: 0)
                            }
                        }
                        .buttonStyle(.borderless)
                        .accessibilityHint("Resumes playing")
                        .contextMenu {
                            Button { model.resume(item) } label: { Label("Resume", systemImage: "play.fill") }
                            if let target = item.watchTarget {
                                WatchToggleButton(watched: false) { await model.setWatched(target, $0) }
                            }
                            if item.pageRoute != nil {
                                Button { model.openPage(of: item) } label: { Label(item.pageLabel, systemImage: "info.circle") }
                            }
                        }
                    }
                }
                TVLiveHomeRow()
                if home.isEmpty {
                    firstRun
                }
                if !home.recentlyAddedShows.isEmpty {
                    posterRow("Recently added", detail: "TV Shows", items: home.recentlyAddedShows)
                }
                if !home.recentlyAired.isEmpty {
                    ShelfRow(title: "Recently aired", detail: "Episodes", items: home.recentlyAired) { episode in airedCard(episode) }
                }
                if !home.recentlyAddedMovies.isEmpty {
                    posterRow("Recently added", detail: "Movies", items: home.recentlyAddedMovies)
                }
                if !home.recentlyReleased.isEmpty {
                    ShelfRow(title: "Recently released", detail: "Movies", items: home.recentlyReleased) { item in
                        Button { model.open(item) } label: {
                            WideCard(title: item.title, imageURL: url(item.backdropPath, .w780), seed: item.title) {
                                if let date = formatDate(item.releaseDate) { TVBadge(date).pinned(.bottomLeading, padding: 10) }
                                if item.watched { WatchedCheck(size: 28).pinned(.bottomTrailing, padding: 10) }
                            }
                        }
                        .buttonStyle(.borderless)
                        .contextMenu { watchMenu(item) }
                    }
                }
    }

    private var firstRun: some View {
        let empty = model.emptyState ?? .unavailable
        return VStack(spacing: 22) {
            TVEmptyState(systemImage: "film.stack", title: empty.title, message: empty.message(isAdmin: model.isAdmin))
            if model.offersSetup, let server = services.servers.current?.server {
                Text(server.displayAddress)
                    .font(.title3.monospaced())
                    .foregroundStyle(Palette.text)
                Text("Open this address on your phone or computer.")
                    .foregroundStyle(Palette.muted)
            }
            Button("Check again") { Task { await model.refresh() } }
                .accessibilityIdentifier("homeCheckAgain")
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, TVLayout.gutter)
        .padding(.vertical, 40)
        .focusSection()
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("homeFirstRun")
    }

    private func heroBackdrop(_ hero: Hero) -> some View {
        Artwork(url: url(hero.backdropPath, .w1280), seed: hero.title, maxPixel: 1920)
            .frame(width: 1400, height: 640)
            .overlay(RadialGradient(colors: [Palette.gold.opacity(0.10), .clear], center: UnitPoint(x: 0.7, y: 0.3), startRadius: 0, endRadius: 700))
            .overlay(LinearGradient(stops: [.init(color: Palette.ground, location: 0), .init(color: Palette.ground.opacity(0.85), location: 0.22), .init(color: Palette.ground.opacity(0), location: 0.6)], startPoint: .leading, endPoint: .trailing))
            .overlay(LinearGradient(stops: [.init(color: Palette.ground.opacity(0), location: 0.55), .init(color: Palette.ground, location: 1)], startPoint: .top, endPoint: .bottom))
            .clipped()
            .padding(.top, -200)
            .accessibilityHidden(true)
    }

    private func posterRow(_ title: String, detail: String, items: [BrowseItem]) -> some View {
        ShelfRow(title: title, detail: detail, items: items) { item in
            Button { model.open(item) } label: {
                PosterCard(item: item, imageURL: url(item.posterPath, .w500), width: 160)
            }
            .buttonStyle(.borderless)
            .contextMenu { watchMenu(item) }
        }
    }

    private func airedCard(_ episode: EpisodeCard) -> some View {
        Button { model.open(episode) } label: {
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
                Button { model.play(episode) } label: { Label("Play", systemImage: "play.fill") }
            }
            WatchToggleButton(watched: episode.watched) { await model.setWatched(.episode(id: episode.episodeID), $0) }
        }
    }

    @ViewBuilder private func watchMenu(_ item: BrowseItem) -> some View {
        if let target = item.watchTarget {
            WatchToggleButton(watched: item.watched) { await model.setWatched(target, $0) }
        }
    }
}

private extension View {
    /// One of Home's screens: the height of the screen, the width of it, and a
    /// focus section of its own so ▲ and ▼ move between screens.
    func screen() -> some View {
        frame(maxWidth: .infinity, alignment: .topLeading)
            .containerRelativeFrame(.vertical)
            .clipped()
            .focusSection()
    }
}

/// Above a row screen's row: the focused card's kicker, title, facts with the
/// quality tags and rating, two lines of overview and what Select does
/// (TvHomeRow.dc.html's "Focused title"). VoiceOver reads it as one element,
/// before the row.
private struct FocusPanel: View {
    let panel: RowPanel
    let moreInRow: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(panel.kicker.uppercased())
                .font(.system(size: 19, weight: .semibold))
                .tracking(2.6)
                .foregroundStyle(Palette.gold)
                .lineLimit(1)
            Text(panel.title)
                .font(.system(size: 64, weight: .heavy))
                .tracking(-1.6)
                .foregroundStyle(Palette.text)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
            FactsLine(parts: panel.facts, tags: panel.tags, after: panel.rating)
            if !panel.overview.isEmpty {
                Text(panel.overview)
                    .font(.system(size: 21))
                    .lineSpacing(6)
                    .foregroundStyle(Palette.textSecondary)
                    .lineLimit(2)
                    .frame(maxWidth: 800, alignment: .leading)
            }
            HStack(spacing: 10) {
                Text("Select")
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(Palette.textSecondary)
                    .padding(.horizontal, 8)
                    .frame(minWidth: 34, minHeight: 28)
                    .overlay(RoundedRectangle(cornerRadius: 6).strokeBorder(Palette.outlineStrong))
                Text(panel.hint(moreInRow: moreInRow))
                    .font(.system(size: 17))
                    .foregroundStyle(Palette.faint)
                    .lineLimit(1)
            }
            .padding(.top, 6)
        }
        .frame(maxWidth: 940, alignment: .leading)
        .animation(.easeOut(duration: 0.15), value: panel.title)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(panel.accessibilityLabel)
        .accessibilityHint("Select \(panel.opens)")
    }
}

/// The top of Home: kicker, title, facts with the quality tags, two lines of
/// overview, Play and Go to show.
private struct HomeHero: View {
    private enum Action: Hashable { case play, page }

    let hero: Hero
    let tags: [String]
    let canPlay: Bool
    let onPlay: () -> Void
    let onOpen: () -> Void
    @FocusState private var focus: Action?

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            Text(hero.kicker.uppercased())
                .font(.system(size: 19, weight: .semibold))
                .tracking(2.6)
                .foregroundStyle(Palette.gold)
            Text(hero.title)
                .font(.system(size: 72, weight: .heavy))
                .tracking(-1.8)
                .foregroundStyle(Palette.text)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
                .accessibilityAddTraits(.isHeader)
            FactsLine(parts: hero.factParts, tags: tags + [hero.contentRating].compactMap { $0 })
            if !hero.overview.isEmpty {
                Text(hero.overview)
                    .font(.system(size: 22))
                    .lineSpacing(6)
                    .foregroundStyle(Palette.textSecondary)
                    .lineLimit(2)
                    .frame(maxWidth: 760, alignment: .leading)
            }
            HStack(spacing: 20) {
                if canPlay {
                    Button(action: onPlay) { Label("Play", systemImage: "play.fill") }
                        .buttonStyle(TVButtonStyle(kind: .primary))
                        .focused($focus, equals: .play)
                }
                Button(hero.pageLabel, action: onOpen)
                    .buttonStyle(TVButtonStyle())
                    .focused($focus, equals: .page)
            }
            .padding(.top, 10)
        }
        .frame(maxWidth: 900, alignment: .leading)
        // Coming down from the tab bar (or up from the rows) lands on Play.
        .defaultFocus($focus, canPlay ? .play : .page, priority: .userInitiated)
    }
}
