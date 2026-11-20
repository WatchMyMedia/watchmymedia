import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// Whether the person asked to pick another server (from sign-in), or is
/// switching servers from Settings.
@MainActor
@Observable
final class AppFlow {
    var choosingServer = false
    /// Settings › Server › Switch server, kept here because a switch
    /// rebuilds the tabs under it; nil when none is under way.
    var serverSwitch: ServerSwitchModel?
    /// Where Settings puts focus when it next appears (back from a switch).
    var settingsReturn: SettingsFocus?

    /// Starts Switch server, or the server picker when nobody is signed in to come back to.
    func startSwitch(_ services: AppServices) {
        guard serverSwitch == nil else { return }
        // Sign in starts on the pairing code, as Apple TV's own sign-in does.
        serverSwitch = ServerSwitchModel(servers: services.servers, auth: services.auth, pairsByDefault: true)
        if serverSwitch == nil { choosingServer = true }
    }

    /// Ends Switch server on Home, or on Settings with focus on Switch server.
    func endSwitch(goingTo tab: AppTab) {
        serverSwitch?.picker.stopDiscovery()
        serverSwitch = nil
        endedOn = tab
        if tab == .settings { settingsReturn = .row(.server, "switch") }
    }

    /// The tab a switch ended on, for the root to select once the tabs are back.
    var endedOn: AppTab?
}

/// The server picker until a server is chosen, sign-in until someone is
/// signed in, then the tabs.
struct RootView: View {
    @Environment(AppServices.self) private var services
    @Environment(\.scenePhase) private var scenePhase
    @Environment(Navigator.self) private var navigator
    @Environment(LiveExtrasStore.self) private var extras
    @Environment(FollowsStore.self) private var follows
    @State private var accountGeneration = 0
    @State private var flow = AppFlow()

    var body: some View {
        Group {
            if let switching = flow.serverSwitch {
                SwitchServerScreen(model: switching)
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
        .animation(.default, value: services.auth.user)
        .animation(.default, value: flow.choosingServer)
        .animation(.default, value: flow.serverSwitch == nil)
        .onChange(of: account) { _, _ in
            navigator.closePlayer()
            for tab in AppTab.allCases { navigator.setPath([], for: tab) }
            navigator.tab = .home
            accountGeneration += 1
            flow.serverSwitch?.accountChanged()
        }
        // Cancelled, the switch goes back to Settings › Server; an account
        // change from elsewhere ends it where the root says. Switched, it
        // stays up on its last step until Go to Home or Back to Settings.
        .onChange(of: flow.serverSwitch?.outcome) { _, outcome in
            switch outcome {
            case .cancelled: flow.endSwitch(goingTo: .settings)
            case .accountChanged: flow.endSwitch(goingTo: .home)
            case .switched, nil: break
            }
        }
        .onChange(of: flow.endedOn) { _, tab in
            guard let tab else { return }
            flow.endedOn = nil
            navigator.tab = tab
        }
        .onChange(of: flow.choosingServer) { _, choosing in
            if choosing { navigator.closePlayer() }
        }
        .onChange(of: flow.serverSwitch == nil) { _, ended in
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
            let services = services, extras = extras, follows = follows
            await together(
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
