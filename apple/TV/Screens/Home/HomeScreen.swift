import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// Home: one row a screen. Every screen is a row with the focused card's
/// words above it (what it is, where the viewer was, when it would end) and
/// its picture behind them; the first is the first row, under the tab bar,
/// and Home opens on its first card, so with something in progress one
/// press resumes it. ▼ pages to the next row, ▲ back; Menu on a later screen
/// returns to the first, and from there goes to the tab bar. The last screen
/// has Edit rows and Back to the top. Select on a Continue watching card
/// resumes it, on an On now card watches the channel, and on any other card
/// opens its page; Play/Pause does the same, and a long press has the
/// card's menu. The rows come in the person's order from API level 22,
/// arranged in Settings › Home rows; below it Home's fixed rows are drawn
/// the same way. File and stream detail is never on Home: it is a press
/// away, on the title's page.
struct HomeScreen: View {
    @Environment(AppServices.self) private var services
    @Environment(Navigator.self) private var navigator

    var body: some View {
        HomeView(model: HomeModel(library: services.library, auth: services.auth, navigator: navigator, rows: services.rows, servers: services.servers,
                                  oneCard: OneCardContext(services: services, choices: CopyChoices())))
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
    /// Home has put focus on its first card once: it opens there, and after
    /// that focus is where the person leaves it.
    @State private var openedOnRow = false

    private var liveAvailable: Bool { services.liveTV.capabilities?.available == true }

    var body: some View {
        Group {
            switch model.state {
            case .loading:
                HomeLoading()
            case .failed(let message):
                HomeProblem(message: message, retry: model.retry)
            case .loaded(let home):
                let rows = drawnRows(home)
                if home.isEmpty || rows.isEmpty {
                    HomeNothingYet(model: model, liveAvailable: liveAvailable)
                } else {
                    screens(home, rows: rows)
                }
            }
        }
        .task { await model.run() }
        .onAppear(perform: backFromSeeAll)
        .onChange(of: navigator.exitsToHome) { backFromSeeAll() }
        .onChange(of: model.state.value != nil, initial: true) { _, loaded in openOnFirstCard(loaded) }
        .publishesTopShelf(model.state.value)
        .messagePill($model.message)
        .noticeAlert($model.notice)
        .fullScreenCover(isPresented: $editingRows) { TVRowEditor(screen: .home, screenName: "Home") }
    }

    /// The rows Home draws, one a screen: the person's arranged rows, or a
    /// server's fixed rows below API level 22. The Live TV row only with Live TV.
    private func drawnRows(_ home: HomeData) -> [ScreenRow] {
        home.isArranged ? home.drawnRows(liveTV: liveAvailable) : home.fixedRows.filter { $0.style != .live || liveAvailable }
    }

    /// Once, when Home first has rows at launch: focus goes into the first
    /// screen, where its row puts it on the first card. Not when Home is
    /// reached later, from the tab bar or by Menu from another tab (focus is
    /// then the system's, in the tab bar), and not over a page a link or a
    /// switch has opened.
    private func openOnFirstCard(_ loaded: Bool) {
        guard loaded, !openedOnRow else { return }
        openedOnRow = true
        guard navigator.tab == .home, navigator.path(for: .home).isEmpty, navigator.exitsToHome == 0 else { return }
        Task { @MainActor in
            await Task.yield()
            paging.focus(.start)
        }
    }

    // MARK: One row a screen

    /// The paging screens: a screen per row, then the end screen.
    private func screens(_ home: HomeData, rows: [ScreenRow]) -> some View {
        HomePager(paging: paging) {
            screenStack(home, rows: rows)
        }
    }

    /// The screens, each identified by what it shows (``HomePage``), not its
    /// place: a row appearing above another (Live TV once its capabilities
    /// arrive, Continue watching after a first play) leaves the others' focused
    /// cards and the screen on view where they were.
    private func screenStack(_ home: HomeData, rows: [ScreenRow]) -> some View {
        VStack(spacing: 0) {
            ForEach(Array(rows.enumerated()), id: \.element.id) { index, row in
                rowScreen(row, index: index, rows: rows, page: index == 0 ? .start : .row(row.id))
            }
            endScreen(home)
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

    /// A row's screen, a view of its own: moving along
    /// its row redraws that screen alone, not the others.
    private func rowScreen(_ row: ScreenRow, index: Int, rows: [ScreenRow], page: HomePage) -> some View {
        HomeRowScreen(
            row: row, first: index == 0,
            counter: tvRowCounter(index: index, of: rows.count),
            nextName: rows.indices.contains(index + 1) ? rows[index + 1].screenName : HomeEndScreen.name,
            index: index,
            model: model,
            leaving: { seeAllLeft = (page, navigator.exitsToHome) }
        )
        .modifier(ScreenFocus(page: page, paging: paging))
        .id(page)
    }

    /// The last screen: Edit rows, Back to the top and the totals.
    private func endScreen(_ home: HomeData) -> some View {
        VStack(alignment: .leading, spacing: 24) {
            Text(HomeEndScreen.title)
                .font(.tv(.title2))
                .foregroundStyle(Palette.text)
                .accessibilityAddTraits(.isHeader)
            Text(HomeEndScreen.message)
                .font(.tv(.body))
                .foregroundStyle(Palette.textSecondary)
                .frame(maxWidth: 860, alignment: .leading)
            HStack(spacing: 24) {
                if model.canEditRows {
                    TVEditRowsButton { editingRows = true }
                }
                Button(HomeEndScreen.backToTop) { paging.backToTop() }
                    .buttonStyle(TVButtonStyle())
                    .accessibilityIdentifier("homeBackToTop")
            }
            .padding(.top, 16)
            // Across the width, so Up and Down reach the buttons from anywhere on the row above.
            .frame(maxWidth: .infinity, alignment: .leading)
            .focusSection()
            Text(home.totalsLine())
                .font(.tv(.caption2))
                .foregroundStyle(Palette.muted)
                .padding(.top, 16)
        }
        .padding(.horizontal, TVLayout.gutter)
        .padding(.top, 300)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .screen()
        .modifier(ScreenFocus(page: .end, paging: paging))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("homeEnd")
    }
}

/// One of Home's screens, by what it shows: the first (the first row's), a later row's, or the end.
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

    /// The screen on view: one per row, then the end screen.
    var page: HomePage?
    /// The screen focus is in, or was in last: Menu goes back to the first
    /// screen from any other, and from the first is the system's (the tab
    /// bar). Kept apart from `page`, which follows the scroll and passes the
    /// screens between on the way back to the top.
    private(set) var focusedPage: HomePage?
    /// The screen asked to take focus: Back to the top, Menu, and Menu back from a See all.
    private(set) var focusWanted: FocusRequest?

    /// Focus moving into another screen pages to it.
    func focusEntered(_ focused: HomePage) {
        focusedPage = focused
        guard focused != page ?? .start else { return }
        withAnimation(.easeInOut(duration: 0.3)) { page = focused }
    }

    /// Moves focus into a screen.
    func focus(_ screen: HomePage) {
        focusWanted = FocusRequest(page: screen, serial: (focusWanted?.serial ?? 0) + 1)
    }

    func backToTop() {
        focusedPage = .start
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
        let atTop = (paging.focusedPage ?? .start) == .start
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
            removeFromContinue: model.canRemoveFromContinueWatching ? { await model.removeFromContinueWatching($0) } : nil,
            otherServers: { row in
                // Every Home row is a screen of its own here, so each row filled from the servers ends this way.
                guard model.isMerged(row) else { return TVRowServers() }
                let failed: [(server: CardServer, line: String, retry: () -> Void)] = model.unanswered.map { failed in
                    let server = failed.server
                    let retry: () -> Void = { Task { @MainActor in await model.retry(server) } }
                    return (server, failed.line, retry)
                }
                return TVRowServers(asking: model.others.stillAsking, failed: failed)
            },
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

/// A row's screen: the focused card's picture behind the screen, its words
/// above the row, then the row and the next row's name.
/// It holds its row's focused card itself, so moving
/// along the row redraws this screen and not every screen on Home; the card
/// actions are made here, from the model, for the same reason.
private struct HomeRowScreen: View {
    let row: ScreenRow
    /// The first screen, under the tab bar.
    let first: Bool
    let counter: String
    let nextName: String
    let index: Int
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
        let backdrop = picture()
        let focusWords = words()
        ZStack(alignment: .topLeading) {
            if let backdrop { ScreenBackdrop(url: backdrop.pictureURL ?? url(backdrop.picturePath, .w1280, on: backdrop.server), seed: backdrop.seed) }
            VStack(alignment: .leading, spacing: 0) {
                if let focusWords {
                    TVFocusPanel(words: focusWords)
                        .padding(.horizontal, TVLayout.gutter)
                        .padding(.top, first ? 212 : 96)
                        .accessibilityIdentifier("homeFocusPanel-\(row.id)")
                }
                Spacer(minLength: 24)
                TVArrangedRow(row: row, actions: TVRowActions(model, leaving: leaving), counter: counter, focusedCard: $focusedCard, focusedChannel: $focusedChannel)
                    // Its own height: a horizontal scroll view would take all the screen left over the words.
                    .fixedSize(horizontal: false, vertical: true)
                NextRowLine(name: nextName, index: index)
            }
            .padding(.bottom, TVLayout.safeBottom)
            if first { TabBarAccessories() }
        }
        .screen()
        // The cards either side are fetched ahead, so ◀ ▶ does not flash the placeholder.
        .task(id: focusedCard) {
            for neighbour in RowPanel.neighbours(for: row, card: focusedCard) {
                await Artwork.prefetch(neighbour.pictureURL ?? url(neighbour.picturePath, .w1280, on: neighbour.server), maxPixel: 1920, loader: imageLoader)
            }
        }
    }

    private func url(_ path: String?, _ size: ImageSize, on server: String? = nil) -> URL? {
        services.library.imageURL(path, size: size, on: server)
    }

    /// The focused channel on the Live TV row, the first with nothing focused yet; nil on Full guide.
    private func liveChannel() -> LiveChannel? {
        guard focusedChannel != TVLiveHomeRow.guideKey else { return nil }
        let channels = services.liveTV.channels.filter(\.available)
        return channels.first(where: { $0.id == focusedChannel }) ?? channels.first
    }

    /// The picture behind the screen: the focused card's, or its programme's on the Live TV row.
    private func picture() -> RowPanel? {
        guard row.style == .live else { return RowPanel.panel(for: row, card: focusedCard) }
        if focusedChannel == TVLiveHomeRow.guideKey { return nil }
        return liveChannel().map { RowPanel(channel: $0, now: .now) }
    }

    /// The words above the row for the focused card; on the Live TV row, the focused channel's programme.
    private func words() -> TVFocusWords? {
        guard row.style == .live else {
            // A card from another server says so in its facts: "On Cabin", "from Cabin".
            var words = TVFocusWords.words(for: row, card: focusedCard)
            if let line = model.whereLine(row: row, card: focusedCard) { words?.facts.append(line) }
            return words
        }
        if focusedChannel == TVLiveHomeRow.guideKey { return .fullGuide }
        return liveChannel().map { TVFocusWords(channel: $0, now: .now) }
    }
}

/// The next row's name at the foot of the screen, with a chevron: where ▼ goes.
private struct NextRowLine: View {
    let name: String
    let index: Int

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "chevron.down").font(.tv(.caption2, .semibold))
            Text(name).lineLimit(1)
        }
        .font(.tv(.caption1))
        .foregroundStyle(Palette.muted)
        .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
        .padding(.horizontal, TVLayout.gutter)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(nextRowLabel(name))
        .accessibilityIdentifier("homeNextRow-\(index)")
    }
}

/// The focused card's picture behind the whole screen, darkened at the left
/// for the words and at the foot for the row.
private struct ScreenBackdrop: View {
    let url: URL?
    let seed: String

    var body: some View {
        CrossfadeArtwork(url: url, seed: seed, maxPixel: 1920)
            .frame(width: 1920, height: 1080)
            .overlay(BackdropGlow(width: 1920, height: 1080))
            .overlay(LinearGradient(stops: [.init(color: Palette.ground.opacity(0.96), location: 0), .init(color: Palette.ground.opacity(0.82), location: 0.34), .init(color: Palette.ground.opacity(0.2), location: 0.66), .init(color: Palette.ground.opacity(0), location: 1)], startPoint: .leading, endPoint: .trailing))
            .overlay(LinearGradient(stops: [.init(color: Palette.ground.opacity(0), location: 0.3), .init(color: Palette.ground.opacity(0.86), location: 0.62), .init(color: Palette.ground, location: 0.86)], startPoint: .top, endPoint: .bottom))
            .clipped()
            .accessibilityHidden(true)
    }
}

/// The warm light over a backdrop's top right: gold fading out over 900 pt.
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
        RadialGradient(colors: [Palette.gold.opacity(0.10), .clear], center: UnitPoint(x: 0.74, y: 0.3), startRadius: 0, endRadius: 900 / Self.shrink)
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

/// Above a row: the focused card's kicker, title, the episode under it, its
/// facts (time left in gold, when it ends, the rating), two lines about it
/// and what is next on a channel. VoiceOver reads it as one element, before
/// the row. No key legend: where Select goes is the card's to show.
private struct TVFocusPanel: View {
    let words: TVFocusWords

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            if let kicker = words.kicker {
                TVKicker(text: kicker, color: words.live ? Palette.text : Palette.gold, live: words.live)
            }
            Text(words.title)
                .font(.tv(.title1))
                .tracking(-1.5)
                .foregroundStyle(Palette.text)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
            if let subtitle = words.subtitle {
                Text(subtitle).font(.tv(.headline)).foregroundStyle(Palette.textSecondary).lineLimit(1)
            }
            if words.lead != nil || !words.facts.isEmpty || words.rating != nil || !words.after.isEmpty {
                FactsLine(lead: words.lead, parts: words.facts, rating: words.rating, after: words.after)
                    .padding(.top, 4)
            }
            if !words.overview.isEmpty {
                Text(words.overview)
                    .font(.tv(.body))
                    .foregroundStyle(Palette.textSecondary)
                    .lineLimit(2)
                    .frame(maxWidth: 860, alignment: .leading)
                    // The first to give way when the row below needs the room.
                    .layoutPriority(-1)
            }
            if let next = words.next {
                Text(next).font(.tv(.caption1)).foregroundStyle(Palette.muted).lineLimit(1)
            }
        }
        .frame(maxWidth: 1100, alignment: .leading)
        .animation(.easeOut(duration: 0.15), value: words.title)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(words.accessibilityLabel)
    }
}

/// Home while it loads: shapes where the words and the row will be, so
/// nothing jumps when they arrive. Nothing here takes focus, so focus stays
/// on the Home tab and Back still works.
private struct HomeLoading: View {
    var body: some View {
        ZStack(alignment: .topLeading) {
            VStack(alignment: .leading, spacing: 0) {
                Skeleton(width: 680, height: 80)
                Skeleton(width: 420, height: 40).padding(.top, 20)
                Skeleton(width: 520, height: 34).padding(.top, 22)
                Skeleton(width: 300, height: 34).padding(.top, 190)
                HStack(spacing: TVLayout.cardSpacing) {
                    ForEach(0..<6, id: \.self) { _ in Skeleton(width: TVCardWidth.wide, height: 180) }
                }
                .padding(.top, 28)
            }
            .padding(.leading, TVLayout.gutter)
            .padding(.top, 212)
            TabBarAccessories()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .ignoresSafeArea()
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Loading Home")
        .accessibilityIdentifier("homeLoading")
    }

    private struct Skeleton: View {
        let width: CGFloat
        let height: CGFloat

        var body: some View {
            RoundedRectangle(cornerRadius: 14)
                .fill(LinearGradient(colors: [Palette.surfaceRaised, Color(hex: 0x232327), Palette.surfaceRaised], startPoint: .leading, endPoint: .trailing))
                .frame(width: width, height: height)
        }
    }
}

/// A Home state with words and buttons at the left of the screen, under the
/// tab bar: the server didn't answer, or it has nothing yet. It opens with
/// focus on its first button, the thing to try.
private struct HomeStatePage<Buttons: View>: View {
    let systemImage: String
    let title: String
    let message: String
    var note: String?
    @ViewBuilder let buttons: () -> Buttons
    @Environment(Navigator.self) private var navigator
    @FocusState private var holdsFocus: Bool

    var body: some View {
        ZStack(alignment: .topLeading) {
            VStack(alignment: .leading, spacing: 24) {
                Image(systemName: systemImage)
                    .font(.system(size: 64, weight: .regular))
                    .foregroundStyle(Palette.muted)
                    .accessibilityHidden(true)
                Text(title).font(.tv(.title2)).foregroundStyle(Palette.text).accessibilityAddTraits(.isHeader)
                Text(message).font(.tv(.body)).foregroundStyle(Palette.textSecondary).frame(maxWidth: 860, alignment: .leading)
                HStack(spacing: 24) { buttons() }
                    .padding(.top, 16)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .focusSection()
                    .focused($holdsFocus)
                if let note {
                    Text(note).font(.tv(.caption1)).foregroundStyle(Palette.muted).frame(maxWidth: 1000, alignment: .leading).padding(.top, 8)
                }
            }
            .padding(.leading, TVLayout.gutter)
            .padding(.top, 300)
            TabBarAccessories()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .ignoresSafeArea()
        .task {
            guard navigator.tab == .home else { return }
            await Task.yield()
            holdsFocus = true
        }
    }
}

/// Home when the server didn't answer: which server, why in words, Try again
/// and Switch server.
private struct HomeProblem: View {
    let message: String
    let retry: () async -> Void
    @Environment(ServersModel.self) private var servers
    @Environment(AppServices.self) private var services

    var body: some View {
        HomeStatePage(
            systemImage: "icloud.slash",
            title: TVHomeWords.failedTitle(serverName: services.servers.current?.server.name, message: message),
            message: TVHomeWords.failedMessage(message)
        ) {
            Button(TVHomeWords.tryAgain) { Task { await retry() } }
                .buttonStyle(TVButtonStyle(kind: .primary))
                .accessibilityIdentifier("homeTryAgain")
            Button(TVHomeWords.switchServer) { servers.presented = true }
                .buttonStyle(TVButtonStyle())
                .accessibilityIdentifier("homeSwitchServer")
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("homeProblem")
    }
}

/// Home on a server with nothing to watch yet: what the library says, Check
/// again, Watch live TV when it has Live TV, and the address for whoever
/// runs the server.
private struct HomeNothingYet: View {
    let model: HomeModel
    let liveAvailable: Bool
    @Environment(AppServices.self) private var services
    @Environment(Navigator.self) private var navigator

    var body: some View {
        let empty = model.emptyState ?? .unavailable
        HomeStatePage(
            systemImage: "film.stack",
            title: empty.title,
            message: empty.message(isAdmin: model.isAdmin),
            note: model.offersSetup ? services.servers.current.map { TVHomeWords.setupLine(address: $0.server.displayAddress) } : nil
        ) {
            Button(TVHomeWords.checkAgain) { Task { await model.refresh() } }
                .buttonStyle(TVButtonStyle(kind: .primary))
                .accessibilityIdentifier("homeCheckAgain")
            if liveAvailable {
                Button(TVHomeWords.watchLiveTV) { navigator.select(.liveTV) }
                    .buttonStyle(TVButtonStyle())
                    .accessibilityIdentifier("homeWatchLive")
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("homeFirstRun")
    }
}
