import SwiftUI
import WMMCast
import WMMFeatures
import WMMModel
import WMMUI

/// The five tabs, each with its own stack that keeps its place when the
/// person switches away, and the player over everything. On iPad the tabs
/// are a bar at the top with the system's sidebar a tap away
/// (`.sidebarAdaptable`), and Settings is a split view of its own.
struct MainTabView: View {
    @Environment(Navigator.self) private var navigator
    @Environment(AppFlow.self) private var flow
    @Environment(AppServices.self) private var services
    @Environment(CastService.self) private var cast
    @Environment(\.horizontalSizeClass) private var sizeClass

    private var liveAvailable: Bool { services.liveTV.capabilities?.available == true }
    /// Live TV as the tabs are laid out: with it, Settings leaves the tab bar
    /// for a sheet. A server switch keeps the layout it began in until it
    /// ends, since the account it changes can turn Live TV on or off midway.
    /// Following it closed the sheet holding the switch: an iPad switching
    /// from a server with a tuner to one without went back to Settings
    /// before Sign in, every time (2026-11-18).
    private var liveTVLayout: Bool { flow.serverSwitch == nil ? liveAvailable : flow.serverSwitchLiveTV }
    private var settingsPresented: Bool { liveTVLayout && (navigator.tab == .settings || flow.serverSwitch != nil) }
    private var unavailableLivePresented: Bool {
        services.liveTV.capabilities != nil && !liveAvailable && navigator.tab == .liveTV && navigator.playbackPresentation == nil
    }
    private var tabs: [AppTab] { AppTab.phoneTabs(liveTV: liveTVLayout) }

    var body: some View {
        TabView(selection: Binding(get: { (settingsPresented || (!liveTVLayout && navigator.tab == .liveTV)) ? .home : (flow.serverSwitch == nil ? navigator.tab : .settings) }, set: {
            if flow.serverSwitch == nil { navigator.select($0) }
        })) {
            ForEach(tabs, id: \.self) { tab in
                Tab(tab.title, systemImage: tab.systemImage, value: tab) {
                    if flow.serverSwitch != nil && tab != .settings {
                        // Do not keep unsigned browse views or their tasks
                        // alive while the Settings pane changes accounts.
                        Palette.ground
                    } else if tab == .settings && sizeClass.isWide {
                        // Its root beside the page it shows, with Link a TV as a sheet.
                        SettingsScreen()
                    } else {
                        NavigationStack(path: Binding(get: { flow.serverSwitch == nil ? navigator.path(for: tab).pushed : [] }, set: {
                            if flow.serverSwitch == nil { navigator.setPath($0.keepingTask(of: navigator.path(for: tab)), for: tab) }
                        })) {
                            root(tab)
                                .safeAreaInset(edge: .bottom) { CastMiniController() }
                                .navigationDestination(for: Route.self, destination: destination)
                        }
                        .toolbar { ToolbarItem(placement: .topBarTrailing) { CastButton() } }
                    }
                }
                .unavailableDuringServerSwitch(flow.serverSwitch != nil && tab != .settings)
                // Admins: what waits in the Inbox.
                .badge(tab == .settings ? services.requests.options.inbox : 0)
            }
        }
        .tabViewStyle(.sidebarAdaptable)
        // The bar at the top first, as the iPad artboards draw it; the sidebar is a tap away.
        .defaultAdaptableTabBarPlacement(.tabBar)
        .fullScreenCover(item: Binding(get: { flow.serverSwitch == nil ? navigator.playbackPresentation : nil }, set: { if $0 == nil { navigator.closePlayer() } }), onDismiss: continueOnDevice) { target in
            Group {
                switch target {
                case .file(let request): CastFilePresentation(request: request)
                case .live(let request): CastLivePresentation(request: request)
                }
            }.id(target.id)
        }
        .sheet(isPresented: Binding(get: { settingsPresented || unavailableLivePresented }, set: { if !$0 && (!liveAvailable || navigator.tab == .settings) { navigator.select(.home) } })) {
            Group {
                if unavailableLivePresented { NavigationStack { LiveTVScreen() } }
                else { SettingsSheet { destination($0) } }
            }
            .safeAreaInset(edge: .top, alignment: .trailing) {
                Button("Done") { navigator.select(.home) }.padding().disabled(flow.serverSwitch != nil)
            }
            .presentationBackground(Palette.ground)
            .interactiveDismissDisabled(flow.serverSwitch != nil)
        }
        .modifier(TabShellKeys())
        // A note left by a page that has gone ("Deleted").
        .messageToast(Binding(get: { navigator.toast }, set: { navigator.toast = $0 }))
        .fullScreenCover(isPresented: Binding(get: { cast.remotePresented }, set: { cast.remotePresented = $0 }), onDismiss: continueOnDevice) { CastRemoteScreen() }
        // What the user may request, and the Inbox's count, follow the server's
        // events; Live TV follows its own.
        .task {
            for await _ in services.requests.changes() {
                await services.requests.refreshOptions()
            }
        }
    }

    private func continueOnDevice() {
        guard let request = cast.continuingOnDevice else { return }
        cast.continuingOnDevice = nil
        navigator.play(request)
    }

    @ViewBuilder
    private func root(_ tab: AppTab) -> some View {
        switch tab {
        case .home: HomeScreen()
        case .tvShows: LibraryScreen(type: .tv)
        case .movies: LibraryScreen(type: .movie)
        case .search: SearchScreen()
        case .settings: SettingsScreen()
        case .liveTV: LiveTVScreen()
        }
    }

    @ViewBuilder
    private func destination(_ route: Route) -> some View {
        switch route {
        case .show(let id, let season, let episode): ShowScreen(id: id, season: season, episode: episode)
        case .episode(let showID, let season, let id): ShowScreen(id: showID, season: season, episode: id)
        case .movie(let id): MovieScreen(id: id)
        // Link a TV is a sheet over Settings (SettingsTasks), never pushed.
        case .linkTV: EmptyView()
        case .myRequests: RequestsPage()
        case .inbox: InboxScreen()
        case .downloads: DownloadsPage()
        case .liveRecordings: LiveRecordingsScreen()
        case .liveReminders: LiveRemindersScreen()
        case .liveSearch: LiveListingsSearchScreen()
        case .mediaList(let id): MediaListScreen(id: id)
        }
    }
}

/// What the tabs do besides showing: ⌘F for Search on a hardware keyboard,
/// and keeping where the person was for when the system evicts the app.
private struct TabShellKeys: ViewModifier {
    @Environment(Navigator.self) private var navigator
    @Environment(AppFlow.self) private var flow
    @Environment(AppServices.self) private var services

    func body(content: Content) -> some View {
        content
            .background {
                Button("Search") { navigator.findInSearch() }
                    .keyboardShortcut("f", modifiers: .command)
                    .disabled(flow.serverSwitch != nil)
                    .opacity(0)
                    .frame(width: 0, height: 0)
                    .accessibilityHidden(true)
            }
            .onAppear { PlaceKeeping.restoreOnce(navigator, services: services) }
            .onChange(of: navigator.place) { _, _ in
                if flow.serverSwitch == nil { PlaceKeeping.save(navigator, services: services) }
            }
    }
}

private extension TabContent {
    @TabContentBuilder<TabValue>
    func unavailableDuringServerSwitch(_ unavailable: Bool) -> some TabContent<TabValue> {
        if #available(iOS 18.4, *) {
            disabled(unavailable)
        } else {
            // Earlier iOS 18 releases cannot disable a tab. Hide the other
            // destinations until the switch ends; Settings remains mounted.
            // The selection binding and content gates also stay in force.
            hidden(unavailable)
        }
    }
}

/// Settings in the sheet the profile button opens. An iPad's form sheet is a
/// compact width even when the window isn't, so the sheet decides by its own:
/// the split view when wide, else the root in a stack, whose rows push.
private struct SettingsSheet<Destination: View>: View {
    @ViewBuilder let destination: (Route) -> Destination
    @Environment(Navigator.self) private var navigator
    @Environment(\.horizontalSizeClass) private var sizeClass

    var body: some View {
        if sizeClass.isWide {
            SettingsScreen()
        } else {
            NavigationStack(path: Binding(get: { navigator.path(for: .settings).pushed }, set: { navigator.setPath($0.keepingTask(of: navigator.path(for: .settings)), for: .settings) })) {
                SettingsScreen().navigationDestination(for: Route.self, destination: destination)
            }
        }
    }
}

extension [Route] {
    /// The pages a stack pushes: Link a TV in the path is a sheet, not a page.
    var pushed: [Route] { filter { if case .linkTV = $0 { false } else { true } } }

    /// A stack's new pages, with the Link a TV that `path` asked for kept at the end.
    func keepingTask(of path: [Route]) -> [Route] {
        guard let last = path.last, case .linkTV = last else { return self }
        return self + [last]
    }
}
