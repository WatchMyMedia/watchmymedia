import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// Whether the person asked to pick another server (from sign-in or Settings).
@MainActor
@Observable
final class AppFlow {
    var choosingServer = false
}

/// The server picker until a server is chosen, sign-in until someone is
/// signed in, then the tabs.
struct RootView: View {
    @Environment(AppServices.self) private var services
    @Environment(\.scenePhase) private var scenePhase
    @Environment(Navigator.self) private var navigator
    @Environment(LiveExtrasStore.self) private var extras
    @State private var accountGeneration = 0
    @State private var flow = AppFlow()

    var body: some View {
        Group {
            if services.servers.current == nil || flow.choosingServer {
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
        .onChange(of: account) { _, _ in
            navigator.closePlayer()
            for tab in AppTab.allCases { navigator.setPath([], for: tab) }
            navigator.tab = .home
            accountGeneration += 1
        }
        .onChange(of: flow.choosingServer) { _, choosing in
            if choosing { navigator.closePlayer() }
        }
        // At launch, on every return to the app and on every sign-in (which
        // may leave scenePhase unchanged): one task, so launch asks once. A
        // server upgraded meanwhile may do more (Automatic quality from API 3),
        // so its level comes first; the rest don't wait for each other
        // (PLAN-APPLE-REVIEW.md §4.1.3–4).
        .task(id: Refresh(account: account, active: scenePhase == .active)) {
            guard scenePhase == .active else { return }
            await services.servers.refreshCurrent()
            let services = services, extras = extras
            await together(
                { await services.requests.refreshOptions() },
                // Live TV's access, then its reminders, which need it.
                { try? await services.liveTV.refresh(); await extras.refresh() }
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
