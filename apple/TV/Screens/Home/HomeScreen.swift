import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// Home: one row a screen.
/// The first screen is the hero with Play and Go to show
/// and the first row of the person's layout; every row after it fills a
/// screen of its own, the focused card's picture, kicker, title, facts and
/// overview above the row and the next row's name along the bottom. ▼ pages
/// to the next row, ▲ back; Menu on a row screen returns to the first. The
/// last screen is Edit rows, Back to the top and the totals. Select on a
/// Continue watching card resumes it; a Recently aired card opens its
/// episode. Long press marks watched. The rows come in the person's order
/// from API level 22, arranged in Settings › Home rows; below
/// it Home keeps its fixed rows on one scrolling page.
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

    /// The screen on view and the screen asked to take focus. `body` reads
    /// neither, so a focus move or a page turn doesn't draw every screen again.
    @State private var paging = HomePaging()
    @State private var editingRows = false
    /// The row screen a See all left for its library's tab, and the count of
    /// Menu's returns to Home then (``Navigator/exitsToHome``): Menu back from
    /// that grid brings focus back to the row's See all.
    @State private var seeAllLeft: (page: HomePage, exits: Int)?

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
        .onAppear(perform: backFromSeeAll)
        .onChange(of: navigator.exitsToHome) { backFromSeeAll() }
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
        return HomePager(paging: paging) {
            screenStack(home, rows: rows)
        }
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

    /// Home shows again: after Menu from the grid a row's See all opened, its
    /// screen comes back with focus in the row, which lands on See all (the
    /// card it had). Choosing Home in the tab bar leaves focus there, as the
    /// system does; either way the row is remembered once.
    private func backFromSeeAll() {
        guard navigator.tab == .home, let left = seeAllLeft else { return }
        seeAllLeft = nil
        guard navigator.exitsToHome > left.exits else { return }
        Task { @MainActor in
            await Task.yield()
            paging.page = left.page
            paging.focus(left.page)
        }
    }

    /// Screen 1: the hero and the first row.
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
        .modifier(ScreenFocus(page: .start, paging: paging))
    }

    /// A row's screen, a view of its own: moving along
    /// its row redraws that screen alone, not the others.
    private func rowScreen(_ row: ScreenRow, index: Int, rows: [ScreenRow], page: HomePage) -> some View {
        HomeRowScreen(
            row: row, index: index,
            counter: rowCounter(index: index, of: rows.count),
            nextName: rows.indices.contains(index + 1) ? rows[index + 1].screenName : HomeEndScreen.name,
            model: model,
            leaving: { seeAllLeft = (page, navigator.exitsToHome) }
        )
        .modifier(ScreenFocus(page: page, paging: paging))
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
                Button(HomeEndScreen.backToTop) { paging.backToTop() }
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
        .modifier(ScreenFocus(page: .end, paging: paging))
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
            .overlay(BackdropGlow(width: 1400, height: 640))
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

/// Which of Home's screens is on view and which is asked to take focus.
/// It is kept apart from `HomeView`, whose body builds every screen: only
/// the pager reads the page and only the screens read the request, so a
/// focus move or a page turn draws those again and not all of Home.
///
/// While `HomeView` held both, its body ran again when focus first came in
/// from the tab bar, and the first such run after Home loads updates every
/// screen at once: that Down kept the main thread 225 ms, any later move
/// 30 (tvOS 27.2 simulator, 2026-11-18; 135 of it this, the rest drawing
/// the backdrops' glow). On CI's slower runner it passed the half second
/// after which the focus engine repeats an arrow still held, and the
/// remote's release waits behind the work, so one press went on from Play
/// to the row below (`TVUITests/SeeAllTests.swift`).
@MainActor @Observable
private final class HomePaging {
    /// A screen asked to take focus; `serial` makes asking for the same screen again a new value.
    struct FocusRequest: Equatable {
        var page: HomePage
        var serial: Int
    }

    /// The screen on view: the hero's, one per row after the first, then the end screen.
    var page: HomePage?
    /// The screen asked to take focus: Back to the top, Menu, and Menu back from a See all.
    private(set) var focusWanted: FocusRequest?

    /// Focus moving into another screen pages to it.
    func focusEntered(_ focused: HomePage) {
        guard focused != page ?? .start else { return }
        withAnimation(.easeInOut(duration: 0.3)) { page = focused }
    }

    /// Moves focus into a screen.
    func focus(_ screen: HomePage) {
        focusWanted = FocusRequest(page: screen, serial: (focusWanted?.serial ?? 0) + 1)
    }

    func backToTop() {
        withAnimation(.easeInOut(duration: 0.3)) { page = .start }
        focus(.start)
    }
}

/// The paging scroll view around Home's screens. The screens are built by
/// `HomeView` and handed over as a value, so a page turn runs this body and
/// leaves them as they were.
private struct HomePager<Screens: View>: View {
    @Bindable var paging: HomePaging
    let screens: Screens

    init(paging: HomePaging, @ViewBuilder screens: () -> Screens) {
        self.paging = paging
        self.screens = screens()
    }

    var body: some View {
        let atTop = (paging.page ?? .start) == .start
        ScrollView(.vertical) {
            screens
        }
        .scrollTargetBehavior(.paging)
        .scrollPosition(id: $paging.page)
        .scrollIndicators(.hidden)
        .scrollClipDisabled()
        .ignoresSafeArea()
        // Menu on a row screen goes back to the first screen; from there it is the system's.
        .onExitCommand(perform: atTop ? nil : { paging.backToTop() })
    }
}

/// One of Home's screens holding focus, with a focus state of its own: it
/// says when focus comes into the screen, and moves focus into it when asked.
private struct ScreenFocus: ViewModifier {
    let page: HomePage
    let paging: HomePaging
    @FocusState private var holdsFocus: Bool

    func body(content: Content) -> some View {
        content
            .focused($holdsFocus)
            .onChange(of: holdsFocus) { _, holds in
                if holds { paging.focusEntered(page) }
            }
            .onChange(of: paging.focusWanted) { _, wanted in
                if wanted?.page == page { holdsFocus = true }
            }
    }
}

private extension TVRowActions {
    /// Home's card actions. A row's See all opens a list's page, or the
    /// library's tab at its grid sorted the row's way,
    /// for a link Home can open; `leaving` hears of the second first.
    @MainActor init(_ model: HomeModel, leaving: @escaping () -> Void = {}) {
        self.init(
            open: { model.open($0) },
            openEpisode: { model.open($0) },
            playEpisode: { model.play($0) },
            resume: { model.resume($0) },
            openPage: { model.openPage(of: $0) },
            setWatched: { await model.setWatched($0, $1) },
            seeAll: { row in
                guard let link = row.seeAll, let words = model.seeAll(row) else { return nil }
                return TVRowSeeAll(words: words) {
                    if case .library = link { leaving() }
                    model.open(link)
                }
            }
        )
    }
}

/// A row's screen: the focused card's picture and panel, then the row.
/// It holds its row's focused card itself, so moving
/// along the row redraws this screen and not every screen on Home; the card
/// actions are made here, from the model, for the same reason.
private struct HomeRowScreen: View {
    let row: ScreenRow
    let index: Int
    let counter: String
    let nextName: String
    let model: HomeModel
    /// The row's See all is leaving Home for its library's tab.
    let leaving: () -> Void
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
                    FocusPanel(panel: panel, moreInRow: row.hasSeveralCards, atStart: atStart)
                        .padding(.horizontal, TVLayout.gutter)
                        .padding(.top, 150)
                        .accessibilityIdentifier("homeFocusPanel-\(row.id)")
                }
                Spacer(minLength: 0)
                TVArrangedRow(row: row, actions: TVRowActions(model, leaving: leaving), counter: counter, focusedCard: $focusedCard, focusedChannel: $focusedChannel)
                    // Its own height: a horizontal scroll view would take all the screen left over the panel.
                    .fixedSize(horizontal: false, vertical: true)
                NextRowLine(name: nextName, index: index)
            }
        }
        .screen()
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

    /// The focused card is the row's first; on the Live TV row, the first channel.
    private var atStart: Bool {
        guard row.style == .live else { return row.isFirstCard(focusedCard) }
        let first = services.liveTV.channels.first(where: \.available)?.id
        return focusedChannel == nil || focusedChannel == first
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

/// The next row's name along the bottom edge, with a chevron.
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
            .overlay(BackdropGlow(width: 1400, height: 660))
            .overlay(LinearGradient(stops: [.init(color: Palette.ground, location: 0), .init(color: Palette.ground.opacity(0.85), location: 0.22), .init(color: Palette.ground.opacity(0), location: 0.6)], startPoint: .leading, endPoint: .trailing))
            .overlay(LinearGradient(stops: [.init(color: Palette.ground.opacity(0), location: 0.5), .init(color: Palette.ground, location: 1)], startPoint: .top, endPoint: .bottom))
            .clipped()
            .accessibilityHidden(true)
    }
}

/// The warm light over a backdrop's top right: gold fading out over 700 pt.
/// SwiftUI draws a radial gradient on the main thread, a pixel at a time,
/// at the size of its view: at 1400 × 660 pt on every screen of Home, this
/// one was 110 of the 240 ms that the first page turn kept the main thread
/// (tvOS 27.2 simulator, 2026-11-18). So it is drawn a tenth of the size
/// and scaled up, which a light this soft doesn't show: under Home's first
/// screen no pixel changed by more than 2 in 255.
private struct BackdropGlow: View {
    let width: CGFloat
    let height: CGFloat
    private static let shrink: CGFloat = 10

    var body: some View {
        RadialGradient(colors: [Palette.gold.opacity(0.10), .clear], center: UnitPoint(x: 0.7, y: 0.3), startRadius: 0, endRadius: 700 / Self.shrink)
            .frame(width: width / Self.shrink, height: height / Self.shrink)
            .scaleEffect(Self.shrink)
            .frame(width: width, height: height)
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
/// quality tags and rating, two lines of overview and what Select does.
/// VoiceOver reads it as one element,
/// before the row.
private struct FocusPanel: View {
    let panel: RowPanel
    let moreInRow: Bool
    /// The row's first card: the hint offers only ▶.
    var atStart = false

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
                Text(panel.hint(moreInRow: moreInRow, atStart: atStart))
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
