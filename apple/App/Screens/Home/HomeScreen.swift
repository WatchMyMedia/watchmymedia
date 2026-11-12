import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// Home (Home.dc.html, HomeScrolled.dc.html): the hero, Continue watching,
/// Recently added shows, Recently aired, Recently added and Recently
/// released movies, and the totals. Pull to refresh; long-press a card for
/// Mark watched. From API level 22 the rows come in the person's order
/// (PLAN-ROWS.md), drawn by style, with Edit Home after them
/// (HomeEditRows.dc.html). On iPad (Pad2HomeStart, Pad2HomeRow and the
/// portrait boards; PLAN-HOME.md §7.6) the arranged rows are screens, one a
/// row: the hero beside the first row, then each row under the leading card's
/// picture and panel, then Edit rows and the totals; a vertical swipe pages.
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
    @Environment(\.horizontalSizeClass) private var sizeClass
    @Environment(\.imageLoader) private var imageLoader

    @State private var width: CGFloat = 0
    @State private var showAllContinue = false
    @State private var setupPage: URL?
    @State private var editingRows = rowEditorLaunch
    /// The iPad's screen on view, by index: 0 the hero's, then one per row after the first, then the end screen.
    @State private var page: Int?
    /// Each row's leading card, by row id, for the panel above it.
    @State private var leadingCards: [String: String] = [:]
    @Environment(\.scenePhase) private var scenePhase

    private var sideBySide: Bool { wide && width >= PadLayout.sideBySide }
    private var wide: Bool { sizeClass.isWide }
    private var margin: CGFloat { wide ? PadLayout.margin : 16 }
    /// A 16:9 card's width: four Continue watching cards across the iPad.
    private var wideCardWidth: CGFloat { wide ? 248 : 220 }
    private var liveAvailable: Bool { services.liveTV.capabilities?.available == true }

    /// No server, but downloads to watch (HomeOffline.dc.html).
    private var offline: Bool {
        if case .failed = model.state { return services.downloads.enabled }
        return false
    }

    /// The iPad draws an arranged Home as screens (PLAN-HOME.md §7.6).
    private var screensShown: Bool {
        guard wide, let home = model.state.value else { return false }
        return home.isArranged && !home.isEmpty
    }

    var body: some View {
        Group {
            if offline {
                HomeOfflineView(downloads: DownloadsModel(downloads: services.downloads, navigator: navigator, device: downloadsDevice(services.client)), retry: model.retry)
            } else {
                content
            }
        }
        .groundBackground()
        .navigationTitle("Home")
        // The screens take the whole height; the large title would take a screen's top.
        .navigationBarTitleDisplayMode(screensShown ? .inline : .large)
        .toolbar { avatar }
        .task { await model.run() }
        .messageToast($model.message)
        .noticeAlert($model.notice)
        .inAppSafari(url: $setupPage)
        .onChange(of: setupPage) { old, new in
            if old != nil && new == nil { Task { await model.refresh() } }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active && model.state.value?.isEmpty == true { Task { await model.refresh() } }
        }
        .sheet(isPresented: $editingRows) { RowEditorSheet(screen: .home, screenName: "Home") }
    }

    private var content: some View {
        VStack(spacing: 0) {
            if model.state.value == nil { LiveHomeRow(); LiveOnNowRow() }
            LoadableView(model.state, retry: model.retry) { home in
                if wide && home.isArranged && !home.isEmpty {
                    screens(home)
                        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width = $0 }
                        // A rotation changes the screens' height, and the scroll view keeps its
                        // offset rather than its page: put the screen on view back at its top.
                        .onChange(of: width) { old, _ in
                            guard old > 0, let current = page, current > 0 else { return }
                            page = nil
                            Task { @MainActor in page = current }
                        }
                } else {
                    ScrollView {
                        // Arranged, Live TV is a row in its place (PLAN-ROWS.md Decision 4).
                        if !home.isArranged || home.isEmpty { LiveHomeRow() }
                        if home.isEmpty {
                            LiveOnNowRow()
                            firstRun
                        } else if home.isArranged {
                            arrangedRows(home)
                        } else {
                            rows(home)
                        }
                    }
                    .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width = $0 }
                    .refreshable { await model.refresh() }
                }
            }
        }
    }

    private var firstRun: some View {
        let empty = model.emptyState ?? .unavailable
        return VStack(spacing: 16) {
            EmptyState(systemImage: "film.stack", title: empty.title, message: empty.message(isAdmin: model.isAdmin))
            if empty == .scanning { ProgressView().tint(Palette.gold).accessibilityLabel("Scanning the library") }
            if model.offersSetup, let url = services.servers.current?.server.baseURL {
                Button("Open the web page") { setupPage = url }
                    .buttonStyle(PrimaryButtonStyle())
                    .accessibilityIdentifier("homeOpenWeb")
            }
            Button("Check again") { Task { await model.refresh() } }
                .buttonStyle(GoldTextButtonStyle())
                .frame(minHeight: 44)
                .accessibilityIdentifier("homeCheckAgain")
        }
        .frame(maxWidth: 520)
        .padding(.horizontal, margin)
        .padding(.top, wide ? 80 : 48)
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("homeFirstRun")
    }

    @ToolbarContentBuilder private var avatar: some ToolbarContent {
        if let initial = model.initial {
            if #available(iOS 26.0, *) {
                ToolbarItem(placement: .topBarTrailing) { avatarButton(initial) }
                    .sharedBackgroundVisibility(.hidden)
            } else {
                ToolbarItem(placement: .topBarTrailing) { avatarButton(initial) }
            }
        }
    }

    /// The user's initial in a burgundy circle; it opens Settings.
    private func avatarButton(_ initial: String) -> some View {
        Button {
            navigator.select(.settings)
        } label: {
            Text(initial)
                .font(.system(size: 15, weight: .bold))
                .foregroundStyle(Palette.text)
                .frame(width: 34, height: 34)
                .background(Palette.burgundy, in: Circle())
                .frame(width: 44, height: 44)
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Settings")
        .accessibilityValue(model.userName.map { "Signed in as \($0)" } ?? "")
    }

    private func url(_ path: String?, _ size: ImageSize) -> URL? {
        services.library.imageURL(path, size: size)
    }

    // MARK: The iPad's screens (PLAN-HOME.md §7.6)

    /// The paging screens: the hero and the first row, a screen per further row, the end screen.
    private func screens(_ home: HomeData) -> some View {
        let rows = home.drawnRows(liveTV: liveAvailable)
        let endPage = max(rows.count, 1)
        return ScrollView(.vertical) {
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
                endScreen(home, rows: rows)
                    .id(endPage)
            }
            .scrollTargetLayout()
        }
        .scrollTargetBehavior(.paging)
        .scrollPosition(id: $page)
        .scrollIndicators(.hidden)
        // The screens run to the bottom edge: paging steps by the scroll view's bounds, and a
        // bottom inset would leave each screen that much taller than a page, so the next
        // screen's top would show under the next-row line.
        .ignoresSafeArea(edges: .bottom)
        .refreshable { await model.refresh() }
        .overlay(alignment: .trailing) { screenDots(count: endPage + 1) }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("homeScreens")
    }

    /// Screen 1: the hero beside the first row's panel at the hero's height
    /// (Pad2HomeStart), or, when the first row is not Continue watching or
    /// the width is portrait's, the hero over the first row (Pad2HomeStartPortrait).
    private func startScreen(_ home: HomeData, rows: [ScreenRow]) -> some View {
        let beside = sideBySide ? home.continueBesideHero(liveTV: liveAvailable) : nil
        return VStack(alignment: .leading, spacing: 16) {
            if let hero = home.hero, let beside {
                HStack(alignment: .top, spacing: 24) {
                    heroCard(hero, home: home, fillsHeight: true)
                    continuePanel(beside, counter: rowCounter(index: 0, of: rows.count), limit: 6)
                        .frame(width: 362)
                }
                .frame(maxHeight: .infinity)
                .padding(.horizontal, margin)
                .padding(.top, 8)
            } else {
                if let hero = home.hero {
                    heroCard(hero, home: home, fillsHeight: true)
                        .frame(maxHeight: .infinity)
                        .padding(.horizontal, margin)
                        .padding(.top, 8)
                }
                if let first = rows.first {
                    arrangedRow(first, index: 0, rows: rows)
                }
            }
            nextRowLine(rows.count > 1 ? rows[1].screenName : HomeEndScreen.name, to: 1)
        }
        .screen()
    }

    /// A row's screen: the leading card's picture and panel above the row (Pad2HomeRow, Pad2HomeRowPortrait).
    private func rowScreen(_ row: ScreenRow, index: Int, rows: [ScreenRow]) -> some View {
        let key = leadingCards[row.id]
        let panel = panel(for: row, card: key)
        return VStack(alignment: .leading, spacing: 16) {
            if let panel {
                LeadPanel(panel: panel, imageURL: panel.pictureURL ?? url(panel.picturePath, .w780), compact: !sideBySide, perform: perform)
                    .frame(maxHeight: .infinity)
                    .padding(.horizontal, margin)
                    .padding(.top, 8)
                    .accessibilityIdentifier("homeLeadPanel-\(row.id)")
            } else {
                Spacer(minLength: 0)
            }
            arrangedRow(row, index: index, rows: rows)
            nextRowLine(rows.indices.contains(index + 1) ? rows[index + 1].screenName : HomeEndScreen.name, to: index + 1)
        }
        .screen()
        // The cards either side are fetched ahead, so a swipe does not flash the placeholder.
        .task(id: key) {
            for neighbour in RowPanel.neighbours(for: row, card: key) {
                await Artwork.prefetch(neighbour.pictureURL ?? url(neighbour.picturePath, .w780), maxPixel: 1600, loader: imageLoader)
            }
        }
    }

    /// The last screen: Edit rows, Back to the top and the totals.
    private func endScreen(_ home: HomeData, rows: [ScreenRow]) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(HomeEndScreen.title)
                .scaledFont(30, weight: .bold)
                .foregroundStyle(Palette.text)
                .accessibilityAddTraits(.isHeader)
            Text(HomeEndScreen.message)
                .scaledFont(15)
                .lineSpacing(4)
                .foregroundStyle(Palette.textSecondary)
            HStack(spacing: 10) {
                if model.canEditRows {
                    EditRowsButton(title: HomeEndScreen.editRows) { editingRows = true }
                }
                Button(HomeEndScreen.backToTop) { withAnimation(.easeInOut(duration: 0.35)) { page = 0 } }
                    .buttonStyle(GhostButtonStyle(height: 44, fullWidth: false))
                    .accessibilityIdentifier("homeBackToTop")
            }
            .padding(.top, 4)
            Text(home.totalsLine())
                .scaledFont(13)
                .foregroundStyle(Palette.faint)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 12)
        }
        .frame(maxWidth: 720, alignment: .leading)
        .padding(.horizontal, margin)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .screen()
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("homeEnd")
    }

    private func arrangedRow(_ row: ScreenRow, index: Int, rows: [ScreenRow]) -> some View {
        ArrangedRow(
            row: row, actions: rowActions, counter: rowCounter(index: index, of: rows.count),
            leading: Binding(get: { leadingCards[row.id] }, set: { leadingCards[row.id] = $0 })
        )
        // Its own height: a horizontal scroll view would take all the screen left over the panel.
        .fixedSize(horizontal: false, vertical: true)
    }

    /// The panel for a row's leading card; the Live TV row's is the leading channel's programme.
    private func panel(for row: ScreenRow, card key: String?) -> RowPanel? {
        guard row.style == .live else { return RowPanel.panel(for: row, card: key) }
        let channels = services.liveTV.channels.filter(\.available)
        guard let channel = channels.first(where: { $0.id == key }) ?? channels.first else { return nil }
        return RowPanel(channel: channel, now: .now)
    }

    /// What a panel's button, or its picture, does.
    private func perform(_ action: RowPanel.Action) {
        switch action {
        case .open(let item): model.open(item)
        case .openShow(let id): navigator.open(.show(id: id))
        case .openEpisode(let episode): model.open(episode)
        case .playEpisode(let episode): model.play(episode)
        case .resume(let item): model.resume(item)
        case .openPage(let item): model.openPage(of: item)
        case .watchChannel(let id):
            if let channel = services.liveTV.channels.first(where: { $0.id == id }) { navigator.playLive(.init(channel: channel)) }
        case .openGuide: navigator.select(.liveTV)
        }
    }

    /// The next row's name along the bottom edge, with a chevron; tapping it pages there (Pad2HomeRow's `.peek`).
    private func nextRowLine(_ name: String, to index: Int) -> some View {
        Button {
            withAnimation(.easeInOut(duration: 0.35)) { page = index }
        } label: {
            HStack(spacing: 8) {
                Image(systemName: "chevron.down").font(.system(size: 13, weight: .semibold))
                Text(name).lineLimit(1)
                Spacer(minLength: 0)
            }
            .scaledFont(13, weight: .semibold)
            .foregroundStyle(Palette.muted)
            .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
            .overlay(alignment: .top) { Rectangle().fill(Palette.separator).frame(height: 1) }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .padding(.leading, margin)
        .padding(.trailing, 40)
        // Above the home indicator, since the screens run under it.
        .padding(.bottom, 16)
        .accessibilityLabel("Next row: \(name)")
        .accessibilityHint("Shows that row")
        .accessibilityIdentifier("homeNextRow-\(index - 1)")
    }

    /// The screen dots along the right edge: the one on view is a gold bar.
    private func screenDots(count: Int) -> some View {
        let current = page ?? 0
        return VStack(spacing: 10) {
            ForEach(0..<count, id: \.self) { index in
                Capsule()
                    .fill(index == current ? Palette.gold : .white.opacity(0.28))
                    .frame(width: 6, height: index == current ? 22 : 6)
            }
        }
        .padding(.trailing, 14)
        .animation(.easeOut(duration: 0.2), value: current)
        .accessibilityElement()
        .accessibilityLabel("Screen \(current + 1) of \(count)")
        .accessibilityIdentifier("homeScreenDots")
    }

    // MARK: Fixed rows (older servers) and the iPhone's arranged rows

    private func rows(_ home: HomeData) -> some View {
        LazyVStack(alignment: .leading, spacing: 22) {
            if sideBySide, let hero = home.hero, !home.continueWatching.isEmpty {
                HStack(alignment: .top, spacing: 24) {
                    heroCard(hero, home: home)
                    continuePanel(home.continueWatching)
                        .frame(width: 362)
                }
                .padding(.horizontal, margin)
            } else if let hero = home.hero {
                heroCard(hero, home: home).padding(.horizontal, margin)
            }
            if !home.continueWatching.isEmpty && (!sideBySide || home.hero == nil || showAllContinue) {
                CardRow("Continue watching", spacing: wide ? 18 : 12) {
                    ForEach(home.continueWatching) { item in
                        Button { model.resume(item) } label: {
                            WideCard(title: item.title, subtitle: item.remainingLine, imageURL: url(item.stillPath ?? item.backdropPath, .w780), seed: item.title, width: wideCardWidth) {
                                ProgressStrip(progress: item.progress).pinned(.bottom, padding: 0)
                            }
                        }
                        .buttonStyle(CardButtonStyle())
                        .accessibilityHint("Resumes playing")
                        .contextMenu {
                            if let target = item.watchTarget {
                                WatchToggleButton(watched: false) { await model.setWatched(target, $0) }
                            }
                            if item.pageRoute != nil {
                                Button { model.openPage(of: item) } label: { Label(item.pageLabel, systemImage: "info.circle") }
                            }
                        }
                    }
                }
            }
            // With listings, On now follows Continue watching (LiveHome); without, L8's channel row stays at the top.
            LiveOnNowRow()
            if !home.recentlyAddedShows.isEmpty {
                posterRow("Recently added", detail: "TV Shows", items: home.recentlyAddedShows, library: .tvShows)
            }
            if !home.recentlyAired.isEmpty {
                CardRow("Recently aired", detail: "Episodes", spacing: wide ? 18 : 12) {
                    ForEach(home.recentlyAired) { episode in airedCard(episode) }
                }
            }
            if !home.recentlyAddedMovies.isEmpty {
                posterRow("Recently added", detail: "Movies", items: home.recentlyAddedMovies, library: .movies)
            }
            if !home.recentlyReleased.isEmpty {
                CardRow("Recently released", detail: "Movies", spacing: wide ? 18 : 12, seeAll: seeAll(.movies)) {
                    ForEach(home.recentlyReleased) { item in
                        Button { model.open(item) } label: {
                            WideCard(title: item.title, imageURL: url(item.backdropPath, .w780), seed: item.title, width: wideCardWidth) {
                                if let date = formatDate(item.releaseDate) { Badge(date).pinned(.bottomLeading) }
                                if item.watched { WatchedCheck().pinned(.bottomTrailing) }
                            }
                        }
                        .buttonStyle(CardButtonStyle())
                        .contextMenu { watchMenu(item) }
                    }
                }
            }
            VStack(alignment: .leading, spacing: 12) {
                RowSeparator()
                Text(home.totalsLine())
                    .scaledFont(12)
                    .foregroundStyle(Palette.faint)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(.horizontal, margin)
            .padding(.top, 4)
        }
        .padding(.top, 8)
        .padding(.bottom, 24)
        .frame(maxWidth: wide ? PadLayout.homeMaximum : .infinity)
        .frame(maxWidth: .infinity)
    }

    /// From API level 22 on iPhone: the hero, the rows in the person's order,
    /// Edit Home, and the totals. (The iPad draws them as screens, above.)
    private func arrangedRows(_ home: HomeData) -> some View {
        let drawn = home.drawnRows(liveTV: liveAvailable)
        // A VStack: rows a LazyVStack makes ahead of the scroll lose their cards (iOS 27).
        return VStack(alignment: .leading, spacing: 22) {
            if let hero = home.hero {
                heroCard(hero, home: home).padding(.horizontal, margin)
            }
            ForEach(drawn) { row in
                ArrangedRow(row: row, actions: rowActions)
            }
            if model.canEditRows {
                EditRowsButton(title: "Edit Home") { editingRows = true }
                    .padding(.horizontal, margin)
            }
            VStack(alignment: .leading, spacing: 12) {
                RowSeparator()
                Text(home.totalsLine())
                    .scaledFont(12)
                    .foregroundStyle(Palette.faint)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(.horizontal, margin)
            .padding(.top, 4)
        }
        .padding(.top, 8)
        .padding(.bottom, 24)
        .frame(maxWidth: .infinity)
    }

    /// What the arranged rows' cards do. A built-in row's See all is the
    /// iPad's, as before; a list's is everywhere, since only it reaches the list's page.
    private var rowActions: RowActions {
        RowActions(
            open: { model.open($0) },
            openEpisode: { model.open($0) },
            playEpisode: { model.play($0) },
            resume: { model.resume($0) },
            openPage: { model.openPage(of: $0) },
            setWatched: { await model.setWatched($0, $1) },
            seeAll: { row in
                guard let link = row.seeAll, model.canOpen(link), wide || row.isList else { return nil }
                return { model.open(link) }
            }
        )
    }

    /// `fillsHeight` is the screens': the card grows to the height its screen gives it.
    private func heroCard(_ hero: Hero, home: HomeData, fillsHeight: Bool = false) -> some View {
        HomeHero(hero: hero, badges: home.heroBadges, imageURL: url(hero.backdropPath, .w1280),
                 canPlay: hero.fileID != nil, wide: wide, fillsHeight: fillsHeight, onPlay: model.playHero, onOpen: model.openHero)
    }

    /// Continue watching beside the hero: `limit` items (three on the
    /// scrolling Home, six on the first screen, where `counter` is "Row 1 of 7").
    private func continuePanel(_ items: [ContinueItem], counter: String? = nil, limit: Int = 3) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text("Continue watching").scaledFont(20, weight: .semibold).foregroundStyle(Palette.text)
                if let counter { Text(counter).scaledFont(13).foregroundStyle(Palette.muted).lineLimit(1) }
                Spacer(minLength: 4)
                if counter == nil {
                    Button(showAllContinue ? "Show less" : "See all") { showAllContinue.toggle() }
                        .buttonStyle(GoldTextButtonStyle(font: .subheadline.weight(.semibold)))
                }
            }
            ForEach(items.prefix(limit)) { item in
                Button { model.resume(item) } label: {
                    HStack(spacing: 14) {
                        Artwork(url: url(item.stillPath ?? item.backdropPath, .w300), seed: item.title)
                            .frame(width: 128, height: 72)
                            .overlay(alignment: .bottom) { ProgressStrip(progress: item.progress) }
                            .clipShape(RoundedRectangle(cornerRadius: 8))
                        VStack(alignment: .leading, spacing: 4) {
                            Text(item.title).scaledFont(15, weight: .semibold).foregroundStyle(Palette.text).lineLimit(1)
                            Text(item.remainingLine).scaledFont(13).foregroundStyle(Palette.muted).lineLimit(2)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
                .buttonStyle(CardButtonStyle())
                .accessibilityHint("Resumes playing")
                .contextMenu {
                    if let target = item.watchTarget {
                        WatchToggleButton(watched: false) { await model.setWatched(target, $0) }
                    }
                    if item.pageRoute != nil {
                        Button { model.openPage(of: item) } label: { Label(item.pageLabel, systemImage: "info.circle") }
                    }
                }
            }
            Spacer(minLength: 0)
        }
        .padding(18)
        .frame(minHeight: 360)
        .background(Palette.surface, in: RoundedRectangle(cornerRadius: 20))
        .overlay(RoundedRectangle(cornerRadius: 20).strokeBorder(Palette.separator))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("row-continue")
    }

    /// See all, on iPad: the row's library, which lists by date added.
    private func seeAll(_ tab: AppTab) -> (() -> Void)? {
        wide ? { navigator.select(tab) } : nil
    }

    private func posterRow(_ title: String, detail: String, items: [BrowseItem], library: AppTab) -> some View {
        CardRow(title, detail: detail, spacing: wide ? 16 : 10, seeAll: seeAll(library)) {
            ForEach(items) { item in
                Button { model.open(item) } label: {
                    PosterCard(item: item, imageURL: url(item.posterPath, .w342), width: wide ? 150 : 110)
                }
                .buttonStyle(CardButtonStyle())
                .contextMenu { watchMenu(item) }
            }
        }
    }

    private func airedCard(_ episode: EpisodeCard) -> some View {
        Button { model.open(episode) } label: {
            WideCard(title: episode.showTitle, subtitle: episode.airedLine(), imageURL: url(episode.stillPath ?? episode.showBackdropPath, .w780), seed: episode.showTitle, width: wideCardWidth) {
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
    /// One of the iPad Home's screens: the height of the scroll view, the full width.
    func screen() -> some View {
        frame(maxWidth: .infinity, alignment: .topLeading)
            .containerRelativeFrame(.vertical)
            .clipped()
    }
}

/// The leading card's panel above a row screen (Pad2HomeRow's "Leading title"):
/// its picture with the kicker, title, facts, two lines of overview and the
/// two buttons the hero would have for it, over a fade at the left and
/// bottom. Tapping the picture does what the first button does. `compact` is
/// portrait's: the copy runs the panel's width. VoiceOver reads it as one
/// element, then the buttons, before the row.
private struct LeadPanel: View {
    let panel: RowPanel
    let imageURL: URL?
    let compact: Bool
    let perform: (RowPanel.Action) -> Void

    var body: some View {
        ZStack(alignment: .bottomLeading) {
            CrossfadeArtwork(url: imageURL, seed: panel.seed, maxPixel: 1600)
            RadialGradient(colors: [Palette.gold.opacity(0.13), .clear], center: UnitPoint(x: 0.72, y: 0.18), startRadius: 0, endRadius: 420)
            LinearGradient(
                stops: compact
                    ? [.init(color: Palette.ground.opacity(0.6), location: 0), .init(color: Palette.ground.opacity(0), location: 0.6)]
                    : [.init(color: Palette.ground.opacity(0.92), location: 0), .init(color: Palette.ground.opacity(0.55), location: 0.5), .init(color: Palette.ground.opacity(0), location: 0.8)],
                startPoint: .leading, endPoint: .trailing
            )
            LinearGradient(stops: [.init(color: Palette.ground.opacity(0), location: compact ? 0.3 : 0.4), .init(color: Palette.ground.opacity(0.9), location: compact ? 0.82 : 1)], startPoint: .top, endPoint: .bottom)
            VStack(alignment: .leading, spacing: 8) {
                Text(panel.kicker.uppercased())
                    .scaledFont(11, weight: .bold)
                    .tracking(1.3)
                    .foregroundStyle(Palette.gold)
                    .lineLimit(1)
                Text(panel.title)
                    .scaledFont(compact ? 40 : 38, weight: .heavy)
                    .tracking(-1)
                    .foregroundStyle(Palette.text)
                    .lineLimit(2)
                    .accessibilityAddTraits(.isHeader)
                facts
                if !panel.overview.isEmpty {
                    Text(panel.overview)
                        .scaledFont(14)
                        .lineSpacing(3)
                        .foregroundStyle(Palette.textSecondary)
                        .lineLimit(2)
                }
                if !panel.buttons.isEmpty {
                    HStack(spacing: 10) {
                        ForEach(Array(panel.buttons.enumerated()), id: \.offset) { index, button in
                            if index == 0 {
                                Button { perform(button.action) } label: { Text(button.label).frame(minWidth: compact ? 150 : 114) }
                                    .buttonStyle(PrimaryButtonStyle(height: 46, fullWidth: false))
                            } else {
                                Button { perform(button.action) } label: { Text(button.label).frame(minWidth: compact ? 150 : 122) }
                                    .buttonStyle(GhostButtonStyle(height: 46, fullWidth: false))
                            }
                        }
                    }
                    .padding(.top, 6)
                }
            }
            .frame(maxWidth: compact ? .infinity : 620, alignment: .leading)
            .padding(EdgeInsets(top: 24, leading: 28, bottom: 24, trailing: compact ? 120 : 28))
            .accessibilityElement(children: .contain)
        }
        .frame(maxWidth: .infinity, minHeight: compact ? 420 : 300)
        .clipShape(RoundedRectangle(cornerRadius: 20))
        .contentShape(RoundedRectangle(cornerRadius: 20))
        .onTapGesture { if let action = panel.mainAction { perform(action) } }
        .accessibilityElement(children: .contain)
        .accessibilityLabel(panel.accessibilityLabel)
    }

    private var facts: some View {
        FlowLayout(spacing: 8, lineSpacing: 4) {
            ForEach(Array(panel.facts.enumerated()), id: \.offset) { index, part in
                if index > 0 { dot }
                Text(part).scaledFont(14).foregroundStyle(Color(hex: 0xE5E5E5))
            }
            ForEach(Array(panel.tags.enumerated()), id: \.offset) { index, tag in
                if index == 0 && !panel.facts.isEmpty { dot }
                Tag(tag)
            }
            if let rating = panel.rating {
                if !panel.facts.isEmpty || !panel.tags.isEmpty { dot }
                Text(rating).scaledFont(14).foregroundStyle(Color(hex: 0xE5E5E5))
            }
        }
        .accessibilityElement(children: .combine)
    }

    private var dot: some View {
        Text("·").scaledFont(14).foregroundStyle(Palette.faint).accessibilityHidden(true)
    }
}

/// The big card at the top of Home: kicker, title, facts with the quality
/// tags, two lines of overview, Play and Go to show. The card is the full
/// width and at least 420 pt tall; the backdrop is drawn behind it at that
/// size, so a wide picture cannot push the text and buttons off the screen.
/// `wide` is the iPad's: 330 pt tall, with the copy on the left over a
/// darkened side.
private struct HomeHero: View {
    let hero: Hero
    let badges: [String]
    let imageURL: URL?
    let canPlay: Bool
    var wide = false
    /// On the iPad's first screen: as tall as the screen allows, not just 360 pt.
    var fillsHeight = false
    let onPlay: () -> Void
    let onOpen: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: wide ? 10 : 8) {
            Text(hero.kicker.uppercased())
                .scaledFont(wide ? 12 : 11, weight: .bold)
                .tracking(wide ? 1.4 : 1.3)
                .foregroundStyle(Palette.gold)
            Text(hero.title)
                .scaledFont(wide ? 44 : 30, weight: .heavy)
                .tracking(wide ? -1.1 : -0.6)
                .foregroundStyle(Palette.text)
                .lineLimit(2)
                .accessibilityAddTraits(.isHeader)
            facts
            if !hero.overview.isEmpty {
                Text(hero.overview)
                    .scaledFont(wide ? 15 : 13)
                    .lineSpacing(wide ? 3 : 2)
                    .foregroundStyle(Palette.textSecondary)
                    .lineLimit(2)
            }
            buttons
                .padding(.top, 6)
        }
        .frame(maxWidth: wide ? 620 : .infinity, alignment: .leading)
        .padding(wide ? EdgeInsets(top: 26, leading: 28, bottom: 26, trailing: 28) : EdgeInsets(top: 16, leading: 16, bottom: 16, trailing: 16))
        .frame(maxWidth: .infinity, minHeight: wide ? 360 : 420, maxHeight: fillsHeight ? .infinity : nil, alignment: .bottomLeading)
        .background { backdrop }
        .clipShape(RoundedRectangle(cornerRadius: wide ? 20 : 16))
        .contentShape(RoundedRectangle(cornerRadius: wide ? 20 : 16))
        .onTapGesture(perform: onOpen)
        .accessibilityElement(children: .contain)
    }

    /// Play and Go to show: halves of the card on iPhone, side by side at their own width on iPad.
    private var buttons: some View {
        HStack(spacing: 10) {
            if canPlay {
                Button(action: onPlay) { Label("Play", systemImage: "play.fill").frame(minWidth: wide ? 114 : nil) }
                    .buttonStyle(PrimaryButtonStyle(height: wide ? 46 : 44, fullWidth: !wide))
            }
            Button(action: onOpen) { Text(hero.pageLabel).frame(minWidth: wide ? 122 : nil) }
                .buttonStyle(GhostButtonStyle(height: wide ? 46 : 44, fullWidth: !wide))
        }
    }

    /// The backdrop with its gold light and the fade to the ground that keeps
    /// the text readable (on iPad, darker on the left, behind the copy); a
    /// background, so it takes the card's size.
    private var backdrop: some View {
        ZStack {
            Artwork(url: imageURL, seed: hero.title, maxPixel: wide ? 2000 : 1400)
            RadialGradient(colors: [Palette.gold.opacity(0.12), .clear], center: UnitPoint(x: wide ? 0.72 : 0.7, y: 0.2), startRadius: 0, endRadius: wide ? 420 : 260)
            if wide {
                LinearGradient(
                    stops: [
                        .init(color: Palette.ground.opacity(0.92), location: 0),
                        .init(color: Palette.ground.opacity(0.6), location: 0.45),
                        .init(color: Palette.ground.opacity(0), location: 0.75),
                    ],
                    startPoint: .leading, endPoint: .trailing
                )
                LinearGradient(
                    stops: [.init(color: Palette.ground.opacity(0), location: 0.5), .init(color: Palette.ground.opacity(0.9), location: 1)],
                    startPoint: .top, endPoint: .bottom
                )
            } else {
                LinearGradient(
                    stops: [.init(color: Palette.ground.opacity(0), location: 0.3), .init(color: Palette.ground.opacity(0.92), location: 0.75)],
                    startPoint: .top, endPoint: .bottom
                )
            }
        }
        .accessibilityHidden(true)
    }

    private var facts: some View {
        FlowLayout(spacing: wide ? 8 : 6, lineSpacing: 4) {
            ForEach(Array(hero.factParts.enumerated()), id: \.offset) { index, part in
                if index > 0 { dot }
                Text(part).scaledFont(wide ? 15 : 13).foregroundStyle(Color(hex: 0xE5E5E5))
            }
            ForEach(Array((badges + [hero.contentRating].compactMap { $0 }).enumerated()), id: \.offset) { index, tag in
                if index == 0 && !hero.factParts.isEmpty { dot }
                Tag(tag)
            }
        }
        .accessibilityElement(children: .combine)
    }

    private var dot: some View {
        Text("·").scaledFont(wide ? 15 : 13).foregroundStyle(Palette.faint).accessibilityHidden(true)
    }
}
