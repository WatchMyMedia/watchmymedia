import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// Whether the person asked to pick another server (from sign-in), or is
/// adding a server from Servers or Settings.
@MainActor
@Observable
final class AppFlow {
    var choosingServer = false
    /// Add a server (and signing in again to one whose sign-in ended), kept
    /// here because a server added rebuilds the tabs under it; nil when none
    /// is under way.
    var addingServer: AddServerModel?
    /// Where Settings puts focus when it next appears (back from Add a server, from Servers' Manage servers).
    var settingsReturn: SettingsFocus?
    /// The server whose page Settings shows over itself; nil when none is up.
    var serverPage: String?
    /// The page a switch landed on (a search result on another server), for
    /// the tabs to put focus in: rebuilt, they start with focus in the tab
    /// bar, where Menu leaves the app instead of going back to the results.
    var landed: Landing?

    struct Landing: Equatable {
        let tab: AppTab
        let route: Route
    }
    /// `-servers` was applied (debug builds): once a launch, since a switch rebuilds the tabs.
    @ObservationIgnored var appliedServersLaunch = false
    /// Settings' launch arguments were applied (debug builds): once a launch, for the same reason.
    @ObservationIgnored var appliedSettingsLaunch = false
    /// The registered server Add a server's steps are signing in to again, from Sign in; nil when adding one.
    private(set) var signingInAgain: SavedServer?

    /// Where Add a server was started, which is where cancelling it goes back to.
    enum AddOrigin { case settings, servers }

    /// Starts Add a server, on Sign in for `serverID` when it names a
    /// registered server whose sign-in ended; the server picker when nobody
    /// is signed in to come back to.
    func startAdding(_ services: AppServices, signingInTo serverID: String? = nil, from origin: AddOrigin = .settings) {
        guard addingServer == nil else { return }
        // Sign in starts on the pairing code, as Apple TV's own sign-in does.
        let model = AddServerModel(servers: services.servers, auth: services.auth, pairsByDefault: true)
        addingServer = model
        self.origin = origin
        signingInAgain = serverID.flatMap { id in services.servers.registered.first { $0.server.id == id } }
        returnRow = serverID.map(SettingsFocus.serverRow) ?? .row(.server, SettingsFocus.addServerKey)
        guard let model else {
            choosingServer = true
            return
        }
        if let serverID { Task { await model.signIn(to: serverID) } }
    }

    /// Ends Add a server once it is added: on Home, or on Settings › Server.
    func endAdding(goingTo tab: AppTab) {
        finishAdding()
        endedOn = tab
        if tab == .settings { settingsReturn = returnRow }
    }

    /// Ends Add a server that was cancelled: back where it was started, on
    /// Servers or on its row in Settings › Server.
    func cancelAdding() {
        finishAdding()
        if origin == .servers {
            backToServers = true
        } else {
            endedOn = .settings
            settingsReturn = returnRow
        }
    }

    private func finishAdding() {
        addingServer?.picker.stopDiscovery()
        addingServer = nil
        signingInAgain = nil
    }

    /// The tab Add a server ended on, for the root to select once the tabs are back.
    var endedOn: AppTab?
    /// Add a server was cancelled from Servers: the root opens Servers again once the tabs are back.
    var backToServers = false
    /// The Settings row Add a server goes back to when it is cancelled.
    @ObservationIgnored private var returnRow: SettingsFocus = .row(.server, SettingsFocus.addServerKey)
    /// Where the Add a server under way was started.
    private(set) var origin = AddOrigin.settings

    /// "Settings" or "Servers": where Menu goes back to from Add a server's first step.
    var addOriginName: String { origin == .servers ? ServersWords.title : SettingsWords.settings }
}

/// The server picker until a server is chosen, sign-in until someone is
/// signed in, then the tabs. A switch to another server (Servers, a search
/// result, a link) changes the account, which rebuilds the tabs; they then
/// go where the switch was headed (``Navigator/land(on:)``).
struct RootView: View {
    @Environment(AppServices.self) private var services
    @Environment(\.scenePhase) private var scenePhase
    @Environment(Navigator.self) private var navigator
    @Environment(LiveExtrasStore.self) private var extras
    @Environment(FollowsStore.self) private var follows
    @Environment(WatchlistStore.self) private var watchlists
    @Environment(ServersModel.self) private var servers
    @State private var accountGeneration = 0
    @State private var flow = AppFlow()

    var body: some View {
        Group {
            if let adding = flow.addingServer {
                AddServerScreen(model: adding)
            } else if servers.moving {
                // On its way to the next server after a sign-out or a removal: neither sign-in nor the picker.
                Palette.ground.ignoresSafeArea()
            } else if services.servers.current == nil || flow.choosingServer {
                ServerPickerScreen()
            } else if services.auth.user == nil {
                SignInScreen()
            } else {
                MainTabs().id(accountGeneration)
            }
        }
        .environment(flow)
        .groundBackground()
        // "Now on Cabin server" after a switch, at the top right, taking no focus.
        .serverNote(servers)
        .animation(.default, value: services.auth.user)
        .animation(.default, value: flow.choosingServer)
        .animation(.default, value: flow.addingServer == nil)
        .onChange(of: account) { _, _ in
            navigator.closePlayer()
            for tab in AppTab.allCases { navigator.setPath([], for: tab) }
            navigator.tab = .home
            flow.serverPage = nil
            accountGeneration += 1
            flow.addingServer?.accountChanged()
            // A switch from Servers, a search result or a link: where it was headed, on the new server.
            let current = services.servers.current?.server.id
            let arrival = flow.addingServer == nil && navigator.arrival?.serverID == current ? navigator.arrival : nil
            flow.landed = arrival.flatMap { arrival in arrival.route.map { AppFlow.Landing(tab: arrival.tab, route: $0) } }
            if flow.addingServer == nil, let link = navigator.land(on: current) {
                Task { await navigator.open(link, library: services.library, liveTV: services.liveTV) }
            }
        }
        // A registered server whose sign-in ended was chosen: its sign-in, as Add a server's second step.
        .onChange(of: servers.signInRequest) { _, request in
            guard let request else { return }
            servers.signInRequest = nil
            // From its row in Servers, or from its page in Settings: cancelling goes back there.
            let fromPage = flow.serverPage != nil
            flow.serverPage = nil
            flow.startAdding(services, signingInTo: request.server.id, from: fromPage ? .settings : .servers)
        }
        // Cancelled, Add a server goes back to Settings › Server; an account
        // change from elsewhere ends it where the root says. Added, it
        // stays up on its last step until Go to Home or Back to Settings.
        .onChange(of: flow.addingServer?.outcome) { _, outcome in
            switch outcome {
            case .cancelled: flow.cancelAdding()
            case .accountChanged: flow.endAdding(goingTo: .home)
            case .added, nil: break
            }
        }
        .onChange(of: flow.endedOn) { _, tab in
            guard let tab else { return }
            flow.endedOn = nil
            navigator.tab = tab
        }
        .onChange(of: flow.backToServers) { _, back in
            guard back else { return }
            flow.backToServers = false
            servers.presented = true
        }
        .onChange(of: flow.choosingServer) { _, choosing in
            if choosing { navigator.closePlayer() }
        }
        .onChange(of: flow.addingServer == nil) { _, ended in
            if !ended { navigator.closePlayer() }
        }
        // At launch, on every return to the app and on every sign-in (which
        // may leave scenePhase unchanged): one task, so launch asks once. A
        // server upgraded meanwhile may do more (Automatic quality from API 3),
        // so its level comes first; the rest don't wait for each
        // other.
        .task(id: Refresh(account: account, active: scenePhase == .active)) {
            guard scenePhase == .active else { return }
            await services.servers.refreshCurrent()
            let services = services, extras = extras, follows = follows, watchlists = watchlists
            await together(
                // The count of watchlists decides between the toggle and the picker.
                { await watchlists.refresh() },
                { await services.requests.refreshOptions() },
                // Live TV's access, then its reminders, which need it.
                { try? await services.liveTV.refresh(); await extras.refresh() },
                // The shows followed, for the bell and Settings.
                { await follows.refresh() }
            )
        }
    }

    private struct Refresh: Equatable {
        let account: Account
        let active: Bool
    }

    private struct Account: Equatable {
        let serverID: String?
        let address: URL?
        let user: User?
        let contextID: String?
    }
    private var account: Account {
        Account(serverID: services.servers.current?.server.id,
                address: services.servers.current?.server.baseURL,
                user: services.auth.user, contextID: services.liveTV.contextID)
    }
}
