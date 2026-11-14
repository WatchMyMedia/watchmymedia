import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// The tvOS tab bar at the top (TvHome.dc.html): Search as a magnifier,
/// Home, TV Shows, Movies and Settings, each tab with its own stack. Menu
/// pops a stack, and from a section's root goes to Home; the player covers
/// everything.
struct MainTabs: View {
    @Environment(Navigator.self) private var navigator
    @Environment(AppServices.self) private var services
    @Environment(LiveExtrasStore.self) private var extras

    private var liveAvailable: Bool { services.liveTV.capabilities?.available == true }
    private var tabs: [AppTab] {
        var tabs: [AppTab] = [.home, .tvShows, .movies]
        if liveAvailable || navigator.tab == .liveTV { tabs.append(.liveTV) }
        tabs.append(.settings)
        return tabs
    }

    var body: some View {
        TabView(selection: Binding(get: { navigator.tab }, set: { navigator.select($0) })) {
            Tab(value: AppTab.search, role: .search) { stack(.search) }
            ForEach(tabs, id: \.self) { tab in
                Tab(value: tab) { stack(tab) } label: { label(tab) }
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
        // A note left by a page that has gone ("Deleted").
        .messagePill(Binding(get: { navigator.toast }, set: { navigator.toast = $0 }))
        .messagePill(Binding(get: { extras.message }, set: { extras.message = $0 }))
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
        // events; Live TV follows its own (PLAN-APPLE-REVIEW.md §4.1.8).
        .task {
            for await _ in services.requests.changes() {
                await services.requests.refreshOptions()
            }
        }
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
                .navigationDestination(for: Route.self) { destination($0, tab: tab) }
                // Menu at a section's root goes Home; at Home's (and on the search keyboard) it is the system's.
                .onExitCommand(perform: tab == .home || tab == .search ? nil : { navigator.exitSection() })
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
        }
    }
}

/// The lockup at the left of the tab bar and the account's initial at its
/// right (TvHome.dc.html). Each page puts them at the top of its scrolling
/// content (``SwiftUICore/View/tabBarAccessories()``), so they scroll away
/// with the tab bar.
struct TabBarAccessories: View {
    @Environment(AppServices.self) private var services

    var body: some View {
        HStack {
            HStack(spacing: 12) {
                BrandMark(size: 38)
                Wordmark(size: 22)
            }
            Spacer()
            if let user = services.auth.user {
                Text(String(user.name.prefix(1)).uppercased())
                    .font(.system(size: 20, weight: .bold))
                    .foregroundStyle(Palette.text)
                    .frame(width: 48, height: 48)
                    .background(Palette.burgundy, in: Circle())
                    .accessibilityLabel("Signed in as \(user.name)")
            }
        }
        .padding(.horizontal, TVLayout.gutter)
        // Centred on the system tab bar, 80 pt from the top of the screen.
        .frame(height: 160)
        .allowsHitTesting(false)
    }
}

/// A page under the tab bar: a vertical scroll view whose content runs
/// from the screen's edges (the artboards' 90 pt gutter is from the edge, not
/// tvOS's safe area), with the lockup and the account's initial beside the
/// tab bar, scrolling away with it.
struct TabPage<Content: View>: View {
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
        .scrollClipDisabled()
        .ignoresSafeArea(edges: .horizontal)
    }
}
