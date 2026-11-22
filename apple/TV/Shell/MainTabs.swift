import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// The tvOS tab bar at the top: Search as a magnifier,
/// Home, TV Shows, Movies and Settings, each tab with its own stack. Menu
/// pops a stack, and from a section's root goes to Home (from Settings'
/// list, to the top bar); the player covers everything.
///
/// The bar's last tab is the account: the person, on the server in use. It
/// takes focus as any tab does (Right from Settings), and its page is
/// Servers (``ServersScreen``), which Menu closes for Home. It is a tab because nothing else beside the
/// tab bar can take focus from it: on tvOS 27.2 focus never left the tabs
/// sideways, for a button drawn in the page at the bar's height or for one
/// in the bar's own trailing accessory (which draws, and stays out of reach).
struct MainTabs: View {
    @Environment(Navigator.self) private var navigator
    @Environment(AppServices.self) private var services
    @Environment(LiveExtrasStore.self) private var extras
    @Environment(ServersModel.self) private var servers
    @Environment(WatchlistStore.self) private var watchlists
    @Environment(AppFlow.self) private var flow

    /// What the tab bar selects: one of the app's tabs, or the account, whose page is Servers.
    private enum Selection: Hashable {
        case tab(AppTab)
        case servers
    }

    private var liveAvailable: Bool { services.liveTV.capabilities?.available == true }
    private var tabs: [AppTab] {
        var tabs: [AppTab] = [.home, .tvShows, .movies]
        if liveAvailable || navigator.tab == .liveTV { tabs.append(.liveTV) }
        tabs.append(.settings)
        return tabs
    }

    /// Servers is open while the account tab is the one chosen (`ServersModel.presented`):
    /// a switch, Add a server and Manage servers close it, and a server that
    /// didn't answer from somewhere else opens it, by setting that.
    private var selection: Binding<Selection> {
        Binding(get: { servers.presented ? .servers : .tab(navigator.tab) }, set: { chosen in
            switch chosen {
            case .servers:
                servers.presented = true
            case .tab(let tab):
                guard servers.presented else { return navigator.select(tab) }
                servers.presented = false
                // Back on the tab it left: its pages stay as they were (choosing a tab again goes to its root).
                if tab != navigator.tab { navigator.select(tab) }
            }
        })
    }

    var body: some View {
        TabView(selection: selection) {
            Tab(value: Selection.tab(.search), role: .search) { stack(.search) }
            ForEach(tabs, id: \.self) { tab in
                Tab(value: Selection.tab(tab)) { stack(tab) } label: { label(tab) }
            }
            if let user = services.auth.user {
                Tab(value: Selection.servers) { ServersScreen() } label: {
                    // The server in use when this Apple TV has several, else the person.
                    Label(Self.tabName(servers.currentName ?? user.name, room: tabs.contains(.liveTV) ? 12 : 22), systemImage: "person.crop.circle.fill")
                        .accessibilityLabel(ServersWords.accountLabel(user: user.name, server: servers.currentName))
                }
            }
        }
        .fullScreenCover(item: Binding(get: { navigator.playbackPresentation }, set: { if $0 == nil { navigator.closePlayer() } })) { target in
            Group {
                switch target {
                case .file(let request): PlayerScreen(request: request)
                case .live(let request): LivePlayerScreen(request: request)
                }
            }.id(target.id)
        }
        // Save to watchlist, from a title's button or a card's long press: the whole screen.
        .fullScreenCover(item: Binding(get: { watchlists.picker }, set: { if $0 == nil { watchlists.picker?.close() } })) { picker in
            TVWatchlistPicker(picker: picker)
        }
        // A note left by a page that has gone ("Deleted").
        .messagePill(Binding(get: { navigator.toast }, set: { navigator.toast = $0 }))
        .messagePill(Binding(get: { extras.message }, set: { extras.message = $0 }))
        // Why a watchlist change went back.
        .messagePill(Binding(get: { watchlists.message }, set: { watchlists.message = $0 }))
        // A Live TV reminder over the tabs (TvLiveReminder's `banner`); the Live TV player shows its own.
        .overlay(alignment: .topTrailing) {
            if navigator.playbackPresentation == nil, let reminder = extras.banner {
                TVLiveReminderBanner(reminder: reminder, inPlayer: false).padding(.top, 140)
            }
        }
        // Opening Live TV after the banner finds the guide on that programme.
        .onChange(of: extras.banner) { _, reminder in
            guard let reminder, navigator.playbackPresentation == nil, navigator.tab != .liveTV,
                  let channelID = extras.channel(for: reminder)?.id ?? reminder.channelID else { return }
            extras.reveal = .init(channelID: channelID, programmeID: reminder.programmeID, start: reminder.start)
        }
        // What the user may request, and the Inbox's count, follow the server's
        // events; Live TV follows its own.
        .task {
            for await _ in services.requests.changes() {
                await services.requests.refreshOptions()
            }
        }
        #if DEBUG
        // `-servers` opens Servers at launch, and `-serversChoose <server id>` chooses that
        // server in it, for screenshots and UI checks. Once: a switch rebuilds these tabs.
        .task {
            let arguments = ProcessInfo.processInfo.arguments
            guard !flow.appliedServersLaunch, arguments.contains("-servers") || arguments.contains("-serversChoose") else { return }
            flow.appliedServersLaunch = true
            servers.presented = true
            if let i = arguments.firstIndex(of: "-serversChoose"), arguments.indices.contains(i + 1),
               let row = servers.rows.first(where: { $0.id == arguments[i + 1] }) {
                await servers.choose(row)
            }
        }
        #endif
    }

    /// A server's or a person's name as the account tab shows it: cut short
    /// when it is longer than the bar has `room` for. The bar is 1260 pt at
    /// its widest and scrolls past that, which would leave the last tab, this
    /// one, cut off at its edge: with Live TV's tab there is room for about
    /// twelve characters, without it about twenty-two. The tab's spoken label
    /// says the whole name.
    private static func tabName(_ name: String, room: Int) -> String {
        name.count > room ? name.prefix(room - 1).trimmingCharacters(in: .whitespaces) + "…" : name
    }

    /// The tab's name; for admins, Settings with what waits in the Inbox in
    /// gold (tvOS tabs have no badge).
    private func label(_ tab: AppTab) -> Text {
        let waiting = services.requests.options.inbox
        guard tab == .settings, waiting > 0 else { return Text(tab.title) }
        return Text("\(tab.title) \(Text(formatCount(waiting)).foregroundStyle(Palette.gold))")
    }

    private func stack(_ tab: AppTab) -> some View {
        NavigationStack(path: Binding(get: { navigator.path(for: tab) }, set: { navigator.setPath($0, for: tab) })) {
            root(tab)
                .navigationDestination(for: Route.self) { route in
                    // The page a switch landed on takes focus, so Menu goes back from it.
                    destination(route, tab: tab).modifier(FocusOnArrival(wanted: flow.landed == AppFlow.Landing(tab: tab, route: route)))
                }
                // Menu at a section's root goes Home. At Home's (and on the search
                // keyboard) it is the system's, and in Settings, whose page goes back
                // to its list itself, the list's Menu is the system's: the top bar.
                .onExitCommand(perform: tab == .home || tab == .search || tab == .settings ? nil : { navigator.exitSection() })
        }
    }

    @ViewBuilder
    private func root(_ tab: AppTab) -> some View {
        switch tab {
        case .home: HomeScreen()
        case .liveTV: LiveTVScreen()
        case .tvShows: LibraryScreen(type: .tv)
        case .movies: LibraryScreen(type: .movie)
        case .search: SearchScreen()
        case .settings: SettingsScreen()
        }
    }

    @ViewBuilder
    private func destination(_ route: Route, tab: AppTab) -> some View {
        switch route {
        case .show(let id, let season, let episode): ShowScreen(id: id, season: season, episode: episode)
        case .episode(let showID, let season, let id): ShowScreen(id: showID, season: season, episode: id)
        case .movie(let id): MovieScreen(id: id, tab: tab)
        case .linkTV: TVEmptyState(systemImage: "iphone", title: "Link a TV is on your iPhone", message: "This Apple TV is the one being linked. Sign it in with its pairing code.")
        case .myRequests: MyRequestsScreen()
        case .inbox: InboxScreen()
        case .downloads: TVEmptyState(systemImage: "arrow.down.circle", title: "Downloads are on your iPhone and iPad", message: "This Apple TV plays from the server.")
        case .liveRecordings: TVLiveRecordingsScreen()
        case .liveReminders: TVLiveRemindersScreen()
        case .liveSearch: SearchScreen()
        case .mediaList(let id): TVMediaListScreen(id: id)
        case .watchlists: TVWatchlistsScreen()
        }
    }
}

/// Puts focus into a page that the tabs were rebuilt on (a search result on
/// another server opens its title there): new tabs start with focus in the
/// tab bar, and Menu from the tab bar leaves the app, where the person
/// expects to go back to the results. Asked until the page has loaded and
/// taken it.
private struct FocusOnArrival: ViewModifier {
    let wanted: Bool

    func body(content: Content) -> some View {
        if wanted { content.modifier(MovesFocusIn()) } else { content }
    }

    private struct MovesFocusIn: ViewModifier {
        @FocusState private var holdsFocus: Bool

        func body(content: Content) -> some View {
            content
                .focused($holdsFocus)
                .task {
                    for _ in 0..<24 {
                        if holdsFocus { break }
                        holdsFocus = true
                        try? await Task.sleep(for: .milliseconds(150))
                    }
                }
        }
    }
}

/// The lockup at the left of the tab bar. Each page puts it at the top of
/// its scrolling content, so it scrolls away with the tab bar. The account
/// is the bar's last tab, since it takes focus.
struct TabBarAccessories: View {
    var body: some View {
        HStack {
            HStack(spacing: 12) {
                BrandMark(size: 38)
                Wordmark(size: 22)
            }
            Spacer()
        }
        .padding(.horizontal, TVLayout.gutter)
        // Centred on the system tab bar, 80 pt from the top of the screen.
        .frame(height: 160)
        .allowsHitTesting(false)
    }
}

/// A page under the tab bar: a vertical scroll view whose content runs
/// from the screen's edges (the artboards' 90 pt gutter is from the edge, not
/// tvOS's safe area), with the lockup beside the tab bar, scrolling away
/// with it. A page that keeps something below the
/// fold passes `fold`, which says how tall the first screen is.
struct TabPage<Content: View>: View {
    var fold: Binding<PageFold>?
    @ViewBuilder var content: () -> Content
    /// How far below the screen's top the content starts: the tab bar's room.
    @State private var topInset: CGFloat = 0

    var body: some View {
        ScrollView(.vertical) {
            content()
                .frame(maxWidth: .infinity, alignment: .leading)
                .overlay(alignment: .top) { TabBarAccessories().offset(y: -topInset) }
        }
        .onScrollGeometryChange(for: CGFloat.self) { $0.contentInsets.top } action: { _, top in topInset = top }
        .onScrollGeometryChange(for: PageFold.self) { geometry in
            PageFold(height: geometry.containerSize.height - geometry.contentInsets.top,
                     scrolled: geometry.contentOffset.y + geometry.contentInsets.top > 40)
        } action: { _, new in
            if let fold, fold.wrappedValue != new { fold.wrappedValue = new }
        }
        .scrollClipDisabled()
        .ignoresSafeArea(edges: .horizontal)
    }
}

/// What a page's first screen shows below the tab bar, and whether the page
/// has scrolled from its top: for a page that keeps something below the fold,
/// as the movie page keeps its related shelves.
struct PageFold: Equatable {
    /// The height of the first screen, under the tab bar.
    var height: CGFloat = 0
    /// Scrolled down, as focus moving down the page does.
    var scrolled = false
}
