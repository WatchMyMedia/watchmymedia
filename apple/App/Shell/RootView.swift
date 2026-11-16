import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// Whether the person asked to pick another server (from sign-in or Settings).
@MainActor
@Observable
final class AppFlow {
    var choosingServer = false
    /// Kept above the size-class branches, so resizing retains the form and
    /// the original account needed by Cancel.
    var serverSwitch: ServerSwitchModel?
    var appliedSwitchLaunch = false
    /// Rename this iPhone and Change password while their sheets are up. The
    /// sheet shows while its model is here; the fields write to the model,
    /// since a field writes its text back as it loses focus while closing.
    var rename: RenameModel?
    var changePassword: ChangePasswordModel?
    /// Next step cards answered Not now, until the app is next launched.
    var dismissedSteps: Set<SettingsNextStep.Kind> = []
    /// Done on Switch server's last step: the root then shows the new server's Home.
    var serverSwitchDone = false
    /// Link a TV's steps, kept here for the same reason as the server switch.
    @ObservationIgnored private var linkTV: LinkTVModel?
    /// The sign-in form, kept here like the server switch, so an iPad window
    /// resized during sign-in (which swaps the iPhone and iPad screens) keeps
    /// what was typed (PLAN-APPLE-REVIEW.md §3.3.4).
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

    /// Switch server's sheet went away: swiped down, it is Cancel, unless the
    /// switch finished, when it is Done.
    func closeServerSwitchSheet() {
        guard let switching = serverSwitch else { return }
        if switching.outcome == .switched { serverSwitchDone = true } else { Task { await switching.cancel() } }
    }
}

/// Server picker until a server is chosen, sign-in until someone is signed
/// in, then the tabs. On iPad (a regular width) the picker and the sign-in
/// form share one screen (PadSignIn.dc.html).
struct RootView: View {
    @Environment(AppServices.self) private var services
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.horizontalSizeClass) private var sizeClass
    @Environment(Navigator.self) private var navigator
    @Environment(LiveExtrasStore.self) private var extras
    @Environment(NotificationRouter.self) private var router
    @Environment(FollowsStore.self) private var follows
    @Environment(NewEpisodeNotifier.self) private var newEpisodes
    private var notifier: LiveReminderNotifier { .shared }
    @State private var flow = AppFlow()
    @State private var accountGeneration = 0

    var body: some View {
        Group {
            if flow.serverSwitch != nil || (services.servers.current != nil && services.auth.user != nil && !flow.choosingServer) {
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
            // Another account's reminders never notify here, nor are its new episodes kept.
            Task { await notifier.reschedule([]) }
            Task { await newEpisodes.accountChanged() }
            if let switching = flow.serverSwitch {
                switching.accountChanged()
            } else {
                // Link a TV can be queued before signing in. Keep that
                // intent while discarding every account's browse routes.
                resetAccountViews(preservingLink: old.user == nil)
            }
        }
        // A switch that finished shows what changed until Done
        // (Settings2SwitchDone.dc.html); cancelled or overtaken, it ends at once.
        .onChange(of: flow.serverSwitch?.outcome) { _, outcome in
            guard let outcome, outcome != .switched else { return }
            endServerSwitch(outcome)
        }
        .onChange(of: flow.serverSwitchDone) { _, done in
            guard done else { return }
            flow.serverSwitchDone = false
            endServerSwitch(.switched)
        }
        // At launch, on every return to the app and on every sign-in (which
        // doesn't change scenePhase): one task, so launch asks once. A server
        // upgraded meanwhile may do more (Automatic quality from API 3), so its
        // level comes first; the rest don't wait for each other
        // (PLAN-APPLE-REVIEW.md §4.1.3–4).
        .task(id: Refresh(account: account, active: scenePhase == .active)) {
            guard scenePhase == .active else { return }
            await services.servers.refreshCurrent()
            newEpisodes.refreshBackgroundStatus()
            let services = services, extras = extras, follows = follows, newEpisodes = newEpisodes
            await together(
                { await services.requests.refreshOptions() },
                // Live TV's access, then its reminders, which need it.
                { try? await services.liveTV.refresh(); await extras.refresh() },
                // Offline progress and what the server decided of the downloads (PLAN-DOWNLOADS.md §6).
                { await services.downloads.sync() },
                // The shows followed, then new episodes of them (PLAN-NEW-EPISODES.md §6.1).
                { await follows.refresh(); await newEpisodes.check() }
            )
        }
        // While the app is open, new episodes are looked for 3 minutes after the last library change.
        .task(id: account) { await newEpisodes.checker.followLibrary(services.library.libraryChanges()) }
        // Reminders as notifications (PLAN-LIVE-TV-GUIDE.md §6.5): the soonest 64,
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

    private func endServerSwitch(_ outcome: ServerSwitchModel.Outcome) {
        flow.serverSwitch = nil
        flow.choosingServer = false
        resetAccountViews(rebuild: outcome != .cancelled)
        navigator.tab = outcome == .cancelled ? .settings : .home
    }

    private func resetAccountViews(preservingLink: Bool = false, rebuild: Bool = true) {
        let link = preservingLink ? navigator.pendingLinkTV : nil
        navigator.closePlayer()
        for tab in AppTab.allCases { navigator.setPath([], for: tab) }
        if let link { navigator.queueLinkTV(code: link.code) }
        if rebuild { accountGeneration += 1 }
    }
}
