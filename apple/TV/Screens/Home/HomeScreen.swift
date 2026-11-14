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

    /// The screen on view: the hero's, one per row after the first, then the end screen.
    @State private var page: HomePage?
    /// The screen that holds focus; a container's focus state, so Back to the
    /// top and Menu can move focus to the first screen.
    @FocusState private var focusedPage: HomePage?
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
        .publishesTopShelf(model.state.value)
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
        let atTop = (page ?? .start) == .start
        return ScrollView(.vertical) {
            screenStack(home, rows: rows)
        }
        .scrollTargetBehavior(.paging)
        .scrollPosition(id: $page)
        .scrollIndicators(.hidden)
        .scrollClipDisabled()
        .ignoresSafeArea()
        // Focus moving into another screen pages to it.
        .onChange(of: focusedPage) { _, focused in
            guard let focused, focused != page ?? .start else { return }
            withAnimation(.easeInOut(duration: 0.3)) { page = focused }
        }
        // Menu on a row screen goes back to the first screen; from there it is the system's.
        .onExitCommand(perform: atTop ? nil : { backToTop() })
    }

    /// The screens, each identified by what it shows (``HomePage``), not its
    /// place: a row appearing above another (Live TV once its capabilities
    /// arrive, Continue watching after a first play) leaves the others' focused
    /// cards and the screen on view where they were.
    private func screenStack(_ home: HomeData, rows: [ScreenRow]) -> some View {
        VStack(spacing: 0) {
            // Without a hero (nothing added lately), the first row has a screen of its own like the others.
            if home.hero == nil, let first = rows.first {
                rowScreen(first, index: 0, rows: rows, page: .start)
            } else {
                startScreen(home, rows: rows)
                    .id(HomePage.start)
            }
            ForEach(Array(rows.enumerated().dropFirst()), id: \.element.id) { index, row in
                rowScreen(row, index: index, rows: rows, page: .row(row.id))
            }
            endScreen(home, rows: rows, index: max(rows.count, 1))
                .id(HomePage.end)
        }
        .scrollTargetLayout()
    }

    private func backToTop() {
        withAnimation(.easeInOut(duration: 0.3)) { page = .start }
        focusedPage = .start
    }

    /// Screen 1: the hero and the first row (TvHomeStart.dc.html).
    private func startScreen(_ home: HomeData, rows: [ScreenRow]) -> some View {
        ZStack(alignment: .topTrailing) {
            if let hero = home.hero { ScreenBackdrop(url: url(hero.backdropPath, .w1280), seed: hero.title) }
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
                    // No panel on this screen, so the row keeps its focused card to itself.
                    TVArrangedRow(row: first, actions: TVRowActions(model), counter: rowCounter(index: 0, of: rows.count))
                        .fixedSize(horizontal: false, vertical: true)
                }
                NextRowLine(name: rows.count > 1 ? rows[1].screenName : HomeEndScreen.name, index: 0)
            }
            .overlay(alignment: .top) { TabBarAccessories() }
        }
        .screen()
        .focused($focusedPage, equals: .start)
    }

    /// A row's screen (TvHomeRow.dc.html), a view of its own: moving along
    /// its row redraws that screen alone, not the others.
    private func rowScreen(_ row: ScreenRow, index: Int, rows: [ScreenRow], page: HomePage) -> some View {
        HomeRowScreen(
            row: row, index: index,
            counter: rowCounter(index: index, of: rows.count),
            nextName: rows.indices.contains(index + 1) ? rows[index + 1].screenName : HomeEndScreen.name,
            model: model, page: page, focusedPage: $focusedPage
        )
        .id(page)
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
        .focused($focusedPage, equals: .end)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("homeEnd")
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

    /// Today's rows, from servers below API level 22, drawn as arranged rows
    /// are: the Live TV row after Continue watching, and what to do first
    /// under it on an empty Home.
    @ViewBuilder private func fixedRows(_ home: HomeData) -> some View {
        ForEach(home.fixedRows) { row in
            TVArrangedRow(row: row, actions: TVRowActions(model))
            if row.style == .live && home.isEmpty {
                firstRun
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
            Button(checkAgainTitle) { Task { await model.refresh() } }
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
}

/// One of Home's screens, by what it shows: the first (the hero's), a row's, or the end.
private enum HomePage: Hashable {
    case start
    case row(String)
    case end
}

private extension TVRowActions {
    /// Home's card actions.
    @MainActor init(_ model: HomeModel) {
        self.init(
            open: { model.open($0) },
            openEpisode: { model.open($0) },
            playEpisode: { model.play($0) },
            resume: { model.resume($0) },
            openPage: { model.openPage(of: $0) },
            setWatched: { await model.setWatched($0, $1) },
            openList: { model.open(.list(id: $0)) }
        )
    }
}

/// A row's screen: the focused card's picture and panel, then the row
/// (TvHomeRow.dc.html). It holds its row's focused card itself, so moving
/// along the row redraws this screen and not every screen on Home; the card
/// actions are made here, from the model, for the same reason.
private struct HomeRowScreen: View {
    let row: ScreenRow
    let index: Int
    let counter: String
    let nextName: String
    let model: HomeModel
    let page: HomePage
    var focusedPage: FocusState<HomePage?>.Binding
    /// The focused card's key (``BrowseItem/cardID``, ``EpisodeCard/cardKey``, ``ContinueItem/cardKey``).
    @State private var focusedCard: String?
    /// The Live TV row's focused channel, or ``TVLiveHomeRow/guideKey``.
    @State private var focusedChannel: String?
    @Environment(AppServices.self) private var services
    @Environment(\.imageLoader) private var imageLoader

    var body: some View {
        let panel = panel()
        ZStack(alignment: .topTrailing) {
            if let panel { ScreenBackdrop(url: panel.pictureURL ?? url(panel.picturePath, .w1280), seed: panel.seed) }
            VStack(alignment: .leading, spacing: 0) {
                if let panel {
                    FocusPanel(panel: panel, moreInRow: row.hasSeveralCards)
                        .padding(.horizontal, TVLayout.gutter)
                        .padding(.top, 150)
                        .accessibilityIdentifier("homeFocusPanel-\(row.id)")
                }
                Spacer(minLength: 0)
                TVArrangedRow(row: row, actions: TVRowActions(model), counter: counter, focusedCard: $focusedCard, focusedChannel: $focusedChannel)
                    // Its own height: a horizontal scroll view would take all the screen left over the panel.
                    .fixedSize(horizontal: false, vertical: true)
                NextRowLine(name: nextName, index: index)
            }
        }
        .screen()
        .focused(focusedPage, equals: page)
        // The cards either side are fetched ahead, so ◀ ▶ does not flash the placeholder.
        .task(id: focusedCard) {
            for neighbour in RowPanel.neighbours(for: row, card: focusedCard) {
                await Artwork.prefetch(neighbour.pictureURL ?? url(neighbour.picturePath, .w1280), maxPixel: 1920, loader: imageLoader)
            }
        }
    }

    private func url(_ path: String?, _ size: ImageSize) -> URL? {
        services.library.imageURL(path, size: size)
    }

    /// The panel for the focused card; the Live TV row's is the focused channel's programme.
    private func panel() -> RowPanel? {
        guard row.style == .live else { return RowPanel.panel(for: row, card: focusedCard) }
        if focusedChannel == TVLiveHomeRow.guideKey { return .fullGuide }
        let channels = services.liveTV.channels.filter(\.available)
        guard let channel = channels.first(where: { $0.id == focusedChannel }) ?? channels.first else { return nil }
        return RowPanel(channel: channel, now: .now)
    }
}

/// The next row's name along the bottom edge, with a chevron (TvHomeRow.dc.html's `.peek`).
private struct NextRowLine: View {
    let name: String
    let index: Int

    var body: some View {
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
        .accessibilityLabel(nextRowLabel(name))
        .accessibilityIdentifier("homeNextRow-\(index)")
    }
}

/// The picture across the top right of a screen, fading to the ground at the left and bottom.
private struct ScreenBackdrop: View {
    let url: URL?
    let seed: String

    var body: some View {
        CrossfadeArtwork(url: url, seed: seed, maxPixel: 1920)
            .frame(width: 1400, height: 660)
            .overlay(RadialGradient(colors: [Palette.gold.opacity(0.10), .clear], center: UnitPoint(x: 0.7, y: 0.3), startRadius: 0, endRadius: 700))
            .overlay(LinearGradient(stops: [.init(color: Palette.ground, location: 0), .init(color: Palette.ground.opacity(0.85), location: 0.22), .init(color: Palette.ground.opacity(0), location: 0.6)], startPoint: .leading, endPoint: .trailing))
            .overlay(LinearGradient(stops: [.init(color: Palette.ground.opacity(0), location: 0.5), .init(color: Palette.ground, location: 1)], startPoint: .top, endPoint: .bottom))
            .clipped()
            .accessibilityHidden(true)
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
