import AppIntents
import UIKit
import WMMFeatures

/// The Home Screen quick actions (a long press on the app's icon) and the
/// App Shortcuts (Siri, Spotlight, the Shortcuts app) for Search, Live TV,
/// Downloads and Continue watching (PLAN-APPLE-REVIEW.md §6.3). Each opens
/// one of the app's links, published for the server signed in (§2.5), so
/// it opens nothing for another server; signed out, the app opens as it is.
/// The quick actions show only what this sign-in has: Live TV when the
/// server offers it, Downloads when they are on. Continue watching opens
/// Home at its top, where that row is.
@MainActor
final class QuickActions {
    static let shared = QuickActions()

    /// The server the links are published for; nil when signed out.
    private(set) var serverID: String?
    /// Opens a link the app published; set once the app's navigator exists.
    var open: ((URL) -> Void)? {
        didSet { if let open, let pending { self.pending = nil; open(pending) } }
    }
    private var pending: URL?

    enum Action: String, CaseIterable {
        case search, liveTV, downloads, continueWatching

        var link: DeepLink {
            switch self {
            case .search: .search
            case .liveTV: .liveTV(channelID: nil)
            case .downloads: .downloads
            case .continueWatching: .home
            }
        }

        var title: String {
            switch self {
            case .search: "Search"
            case .liveTV: "Live TV"
            case .downloads: "Downloads"
            case .continueWatching: "Continue watching"
            }
        }

        var systemImage: String {
            switch self {
            case .search: "magnifyingglass"
            case .liveTV: "antenna.radiowaves.left.and.right"
            case .downloads: "arrow.down.circle"
            case .continueWatching: "play.circle"
            }
        }
    }

    /// The quick actions for this sign-in: none signed out.
    func update(serverID: String?, liveTV: Bool, downloads: Bool) {
        self.serverID = serverID
        guard let serverID else {
            UIApplication.shared.shortcutItems = []
            return
        }
        let actions: [Action] = [.continueWatching, .search] + (liveTV ? [.liveTV] : []) + (downloads ? [.downloads] : [])
        UIApplication.shared.shortcutItems = actions.map { action in
            UIApplicationShortcutItem(
                type: "media.watchmy.client.\(action.rawValue)", localizedTitle: action.title, localizedSubtitle: nil,
                icon: UIApplicationShortcutIcon(systemImageName: action.systemImage),
                userInfo: ["url": action.link.url(publishedFor: serverID, source: "quick-action").absoluteString as NSString]
            )
        }
    }

    /// A quick action was chosen: its link, as published.
    @discardableResult
    func handle(_ item: UIApplicationShortcutItem) -> Bool {
        guard let text = item.userInfo?["url"] as? String, let url = URL(string: text) else { return false }
        handle(url)
        return true
    }

    /// An App Shortcut ran: the action's link for the server signed in now.
    func run(_ action: Action) {
        guard let serverID else { return }
        handle(action.link.url(publishedFor: serverID, source: "shortcut"))
    }

    private func handle(_ url: URL) {
        if let open { open(url) } else { pending = url }
    }
}

/// Hands the quick action the app was launched or brought back with to ``QuickActions``.
final class QuickActionSceneDelegate: NSObject, UIWindowSceneDelegate {
    func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options connectionOptions: UIScene.ConnectionOptions) {
        guard let item = connectionOptions.shortcutItem else { return }
        MainActor.assumeIsolated { _ = QuickActions.shared.handle(item) }
    }

    func windowScene(_ windowScene: UIWindowScene, performActionFor shortcutItem: UIApplicationShortcutItem, completionHandler: @escaping (Bool) -> Void) {
        let handled = MainActor.assumeIsolated { QuickActions.shared.handle(shortcutItem) }
        completionHandler(handled)
    }
}

// MARK: - App Shortcuts

struct OpenSearchIntent: AppIntent {
    static let title: LocalizedStringResource = "Search WatchMyMedia"
    static let openAppWhenRun = true
    @MainActor func perform() async throws -> some IntentResult {
        QuickActions.shared.run(.search)
        return .result()
    }
}

struct OpenLiveTVIntent: AppIntent {
    static let title: LocalizedStringResource = "Open Live TV"
    static let openAppWhenRun = true
    @MainActor func perform() async throws -> some IntentResult {
        QuickActions.shared.run(.liveTV)
        return .result()
    }
}

struct OpenDownloadsIntent: AppIntent {
    static let title: LocalizedStringResource = "Open Downloads"
    static let openAppWhenRun = true
    @MainActor func perform() async throws -> some IntentResult {
        QuickActions.shared.run(.downloads)
        return .result()
    }
}

struct ContinueWatchingIntent: AppIntent {
    static let title: LocalizedStringResource = "Continue watching"
    static let openAppWhenRun = true
    @MainActor func perform() async throws -> some IntentResult {
        QuickActions.shared.run(.continueWatching)
        return .result()
    }
}

struct WatchMyMediaShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(intent: ContinueWatchingIntent(), phrases: ["Continue watching in \(.applicationName)"], shortTitle: "Continue watching", systemImageName: "play.circle")
        AppShortcut(intent: OpenSearchIntent(), phrases: ["Search \(.applicationName)", "Search in \(.applicationName)"], shortTitle: "Search", systemImageName: "magnifyingglass")
        AppShortcut(intent: OpenLiveTVIntent(), phrases: ["Open Live TV in \(.applicationName)", "Watch live TV in \(.applicationName)"], shortTitle: "Live TV", systemImageName: "antenna.radiowaves.left.and.right")
        AppShortcut(intent: OpenDownloadsIntent(), phrases: ["Open downloads in \(.applicationName)"], shortTitle: "Downloads", systemImageName: "arrow.down.circle")
    }
}
