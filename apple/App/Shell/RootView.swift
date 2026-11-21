import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// Whether the person asked to pick another server (from sign-in), and the
/// tasks that outlive the screen that started them.
@MainActor
@Observable
final class AppFlow {
    var choosingServer = false
    /// Add a server's steps (and Sign in again to a server whose sign-in
    /// ended), kept above the size-class branches, so resizing retains the
    /// form and the original account needed by Cancel.
    var addingServer: AddServerModel?
    /// Whether the server had Live TV when the task began. The tabs and
    /// Settings keep that layout until it ends (`MainTabView`).
    var addingServerLiveTV = false
    /// The tab the task was started from (Home, from Servers; Settings),
    /// where Cancel goes back to.
    var addingServerFrom: AppTab = .settings
    var appliedAddLaunch = false
    /// `-servers` opened Servers once this launch; the tabs are rebuilt on every switch.
    var appliedServersLaunch = false
    /// A Settings page asked for from outside Settings (Manage servers in
    /// Servers): Settings opens it and clears this.
    var settingsPage: SettingsPage?
    /// Servers is on screen, as a sheet or a popover.
    var serversShowing = false
    /// What Servers started that needs it gone first (another sheet, or
    /// Settings where that is a sheet): run once it has disappeared.
    @ObservationIgnored var afterServers: (() -> Void)?
    /// Rename this iPhone and Change password while their sheets are up. The
    /// sheet shows while its model is here; the fields write to the model,
    /// since a field writes its text back as it loses focus while closing.
    var rename: RenameModel?
    var changePassword: ChangePasswordModel?
    /// Next step cards answered Not now, until the app is next launched.
    var dismissedSteps: Set<SettingsNextStep.Kind> = []
    /// Done on Add a server's last step: the root then shows the new server's Home.
    var addingServerDone = false
    /// Link a TV's steps, kept here for the same reason as Add a server.
    @ObservationIgnored private var linkTV: LinkTVModel?
    /// The sign-in form, kept here like Add a server, so an iPad window
    /// resized during sign-in (which swaps the iPhone and iPad screens) keeps
    /// what was typed.
    @ObservationIgnored private let signIn = SignInForm()

    func signInModel(_ services: AppServices) -> SignInModel {
        signIn.model(servers: services.servers, auth: services.auth)
    }

    /// A sign-in or sign-out: the next form starts afresh.
    func forgetSignIn() { signIn.forget() }

    /// Link a TV's model while its sheet is up, the same one each time it is asked for.
    func linkTVModel(auth: any AuthRepository, code: String?, serverName: String?) -> LinkTVModel {
        if let linkTV { return linkTV }
        let model = LinkTVModel(auth: auth, code: code, serverName: serverName)
        linkTV = model
        return model
    }

    /// Link a TV's sheet closed: the next one starts afresh.
    func forgetLinkTV() { linkTV = nil }

    /// Starts Add a server from `tab`; with `serverID`, on Sign in for that
    /// registered server, whose sign-in ended. Nothing while one is under
    /// way, or with nobody signed in to come back to.
    func startAddingServer(_ services: AppServices, from tab: AppTab, signingInTo serverID: String? = nil) {
        guard addingServer == nil, let model = AddServerModel(servers: services.servers, auth: services.auth) else { return }
        addingServerLiveTV = services.liveTV.capabilities?.available == true
        addingServerFrom = tab
        addingServer = model
        if let serverID { Task { await model.signIn(to: serverID) } }
    }

    /// Add a server's sheet went away: swiped down, it is Cancel, unless the
    /// server was added, when it is Done.
    func closeAddServerSheet() {
        guard let adding = addingServer else { return }
        if adding.outcome == .added { addingServerDone = true } else { Task { await adding.cancel() } }
    }

    /// Runs `action` now, or once Servers has gone when it is on screen:
    /// what it does may present a sheet of its own.
    func afterServersClose(_ action: @escaping () -> Void) {
        if serversShowing { afterServers = action } else { action() }
    }

    /// Servers has gone from the screen.
    func serversClosed() {
        serversShowing = false
        let action = afterServers
        afterServers = nil
        action?()
    }
}

/// Server picker until a server is chosen, sign-in until someone is signed
/// in, then the tabs. On iPad (a regular width) the picker and the sign-in
/// form share one screen.
struct RootView: View {
    @Environment(AppServices.self) private var services
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.horizontalSizeClass) private var sizeClass
    @Environment(Navigator.self) private var navigator
    @Environment(LiveExtrasStore.self) private var extras
    @Environment(NotificationRouter.self) private var router
    @Environment(FollowsStore.self) private var follows
    @Environment(NewEpisodeNotifier.self) private var newEpisodes
    @Environment(ServersModel.self) private var servers
    private var notifier: LiveReminderNotifier { .shared }
    @State private var flow = AppFlow()
    @State private var accountGeneration = 0

    var body: some View {
        Group {
            if servers.moving {
                // A server was removed, or the one in use signed out of, and
                // the app is on its way to the next: neither sign-in nor the picker shows.
                ProgressView()
                    .tint(Palette.muted)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .accessibilityLabel("Changing server")
            } else if flow.addingServer != nil || (services.servers.current != nil && services.auth.user != nil && !flow.choosingServer) {
                MainTabView()
                    .id(accountGeneration)
            } else if sizeClass.isWide && services.auth.user == nil {
                PadSignInScreen()
            } else if services.servers.current == nil || flow.choosingServer {
                ServerPickerScreen()
            } else if services.auth.user == nil {
                SignInScreen()
            }
        }
        .environment(flow)
        .groundBackground()
        .animation(.default, value: services.auth.user)
        .animation(.default, value: flow.choosingServer)
        .onChange(of: account) { old, _ in
            flow.forgetSignIn()
            // Another account's reminders never notify here.
            Task { await notifier.reschedule([]) }
            // What was kept for a server signed out of or removed goes, once
            // the change is real: Add a server picks the new server before
            // signing in there, and Cancel goes back to the old one, whose
            // place must still be there.
            if flow.addingServer == nil { Task { await newEpisodes.accountChanged() } }
            if let adding = flow.addingServer {
                adding.accountChanged()
            } else {
                // Link a TV can be queued before signing in. Keep that
                // intent while discarding every account's browse routes.
                resetAccountViews(preservingLink: old.user == nil)
                land()
            }
        }
        // Add a server has the screen: Servers, asked for meanwhile (a link whose
        // server didn't answer), doesn't open once the task ends.
        .onChange(of: flow.addingServer != nil) { _, adding in
            if adding { servers.presented = false }
        }
        // A server that was added shows what changed until Done;
        // cancelled or overtaken, the task ends at once.
        .onChange(of: flow.addingServer?.outcome) { _, outcome in
            guard let outcome, outcome != .added else { return }
            endAddingServer(outcome)
        }
        .onChange(of: flow.addingServerDone) { _, done in
            guard done else { return }
            flow.addingServerDone = false
            endAddingServer(.added)
        }
        // A registered server whose sign-in ended was chosen, in Servers or
        // on its page: Add a server's steps, starting on Sign in for it.
        .onChange(of: servers.signInRequest) { _, request in
            guard let request else { return }
            servers.signInRequest = nil
            let tab = navigator.tab
            flow.afterServersClose { flow.startAddingServer(services, from: tab, signingInTo: request.server.id) }
        }
        // At launch, on every return to the app and on every sign-in (which
        // doesn't change scenePhase): one task, so launch asks once. A server
        // upgraded meanwhile may do more (Automatic quality from API 3), so its
        // level comes first; the rest don't wait for each
        // other.
        .task(id: Refresh(account: account, active: scenePhase == .active)) {
            guard scenePhase == .active else { return }
            await services.servers.refreshCurrent()
            newEpisodes.refreshBackgroundStatus()
            let services = services, extras = extras, follows = follows, newEpisodes = newEpisodes
            await together(
                { await services.requests.refreshOptions() },
                // Live TV's access, then its reminders, which need it.
                { try? await services.liveTV.refresh(); await extras.refresh() },
                // Offline progress and what the server decided of the downloads.
                { await services.downloads.sync() },
                // The shows followed, then new episodes of them.
                { await follows.refresh(); await newEpisodes.check() }
            )
        }
        // While the app is open, new episodes are looked for 3 minutes after the last library change.
        .task(id: account) { await newEpisodes.checker.followLibrary(services.library.libraryChanges()) }
        // Reminders as notifications: the soonest 64,
        // again whenever they or the lead time change, once the list is loaded.
        // A server that keeps none clears them; offline, they stay as they were.
        // The quick actions on the app's icon follow the sign-in and what its server offers.
        .onChange(of: quickActions, initial: true) { _, key in
            QuickActions.shared.update(serverID: key.serverID, liveTV: key.liveTV, downloads: key.downloads)
        }
        .task(id: reminderSchedule) {
            if services.auth.user == nil {
                await notifier.reschedule([])
            } else if extras.loaded {
                await notifier.reschedule(extras.alerts(), serverID: services.servers.current?.server.id)
            } else if let capabilities = services.liveTV.capabilities, !capabilities.extrasAvailable {
                await notifier.reschedule([])
            }
        }
    }

    private struct QuickActionsKey: Equatable {
        let serverID: String?
        let liveTV: Bool
        let downloads: Bool
    }

    private var quickActions: QuickActionsKey {
        let signedIn = services.auth.user != nil && services.servers.current?.signedIn == true
        return QuickActionsKey(serverID: signedIn ? services.servers.current?.server.id : nil,
                               liveTV: signedIn && services.liveTV.capabilities?.available == true,
                               downloads: signedIn && services.downloads.enabled)
    }

    private struct Refresh: Equatable {
        let account: Account
        let active: Bool
    }

    /// Changes when the notifications to schedule might.
    private var reminderSchedule: [String] {
        extras.alertsKey + [extras.loaded ? "loaded" : "", services.auth.user.map { "\($0.id)" } ?? "out",
                            services.liveTV.capabilities.map { $0.extrasAvailable ? "extras" : "none" } ?? "unknown",
                            "\(router.status?.rawValue ?? -1)"]
    }

    private struct Account: Equatable {
        let id: String?
        let address: URL?
        let user: User?
        let signedIn: Bool
    }

    private var account: Account {
        Account(id: services.servers.current?.server.id, address: services.servers.current?.server.baseURL,
                user: services.auth.user, signedIn: services.servers.current?.signedIn == true)
    }

    private func endAddingServer(_ outcome: AddServerModel.Outcome) {
        if outcome != .cancelled { Task { await newEpisodes.accountChanged() } }
        flow.addingServer = nil
        flow.choosingServer = false
        resetAccountViews(rebuild: outcome != .cancelled)
        // Cancelled, back where it was started; added, the new server's Home.
        navigator.tab = outcome == .cancelled ? flow.addingServerFrom : .home
    }

    /// The tabs were rebuilt on another server: goes where the switch was
    /// headed (Search with the same words and a title over it, or a link),
    /// which the reset would otherwise have thrown away.
    private func land() {
        guard let link = navigator.land(on: services.servers.current?.server.id) else { return }
        Task { await navigator.open(link, library: services.library, liveTV: services.liveTV) }
    }

    private func resetAccountViews(preservingLink: Bool = false, rebuild: Bool = true) {
        let link = preservingLink ? navigator.pendingLinkTV : nil
        navigator.closePlayer()
        for tab in AppTab.allCases { navigator.setPath([], for: tab) }
        if let link { navigator.queueLinkTV(code: link.code) }
        if rebuild { accountGeneration += 1 }
    }
}
