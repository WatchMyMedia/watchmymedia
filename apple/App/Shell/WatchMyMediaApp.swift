import SwiftUI
import UserNotifications
import WMMDownloads
import WMMCast
import WMMData
import WMMFeatures
import WMMModel
import WMMUI

@main
struct WatchMyMediaApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @State private var services: AppServices
    @State private var navigator = Navigator()
    @State private var cast: CastService
    @Environment(\.scenePhase) private var scenePhase
    /// Live TV favourites and reminders, shared by every screen (PLAN-LIVE-TV-GUIDE.md §6.5).
    @State private var extras: LiveExtrasStore
    @State private var recordings: LiveRecordingsStore

    init() {
        // Launch to Home's first content, and MetricKit's reports for Copy diagnostics (PLAN-APPLE-REVIEW.md §4.3.1, §6.4).
        PerformanceSignposts.shared.launchBegan()
        Diagnostics.start()
        let services = ServicesFactory.make(arguments: ProcessInfo.processInfo.arguments)
        _services = State(initialValue: services)
        BackgroundSync.register(services)
        _cast = State(initialValue: CastServicesFactory.make(services: services, arguments: ProcessInfo.processInfo.arguments))
        _extras = State(initialValue: LiveExtrasStore(repository: services.liveTV))
        _recordings = State(initialValue: LiveRecordingsStore(repository: services.liveTV))
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(services)
                .environment(cast)
                .environment(navigator)
                .environment(extras)
                .environment(recordings)
                .environment(LiveReminderNotifier.shared)
                .environment(\.imageLoader, services.images)
                .preferredColorScheme(.dark)
                .tint(Palette.gold)
                // A play or channel link acts only when the app published it for this server.
                .onOpenURL { url in open(url) }
                .task { await navigator.applyLaunchArguments(ProcessInfo.processInfo.arguments, library: services.library, liveTV: services.liveTV) }
                // A reminder's notification opens its channel.
                .task {
                    LiveReminderNotifier.shared.open = { url in open(url) }
                }
                // A quick action on the app's icon, or an App Shortcut.
                .task {
                    QuickActions.shared.open = { url in open(url) }
                }
                // The person's other devices change favourites and reminders too.
                .task { await extras.follow() }
                .task { await recordings.follow() }
                // Once at launch and on each return to the app.
                .task(id: scenePhase) { if scenePhase == .active { await cast.refresh() } }
                // Offline watching reaches the server in a background refresh too.
                .onChange(of: scenePhase) { _, phase in if phase == .background { BackgroundSync.schedule() } }
        }
    }
}

extension WatchMyMediaApp {
    /// A `watchmymedia://` link from outside the app or one it published
    /// (``DeepLink/opening(_:signedInServer:)``).
    private func open(_ url: URL) {
        guard let link = DeepLink.opening(url, signedInServer: services.servers.current?.server.id) else { return }
        Task { await navigator.open(link, library: services.library, liveTV: services.liveTV) }
    }
}

/// Holds what UIKit still decides: which orientations the app allows now
/// (the player asks for landscape while it is on screen; the rest of the
/// iPhone app stays portrait), and the download sessions' background events.
final class AppDelegate: NSObject, UIApplicationDelegate {
    @MainActor static var orientations: UIInterfaceOrientationMask = UIDevice.current.userInterfaceIdiom == .pad ? .all : .portrait

    /// Set before launch finishes, so a tap on a reminder that opened the app reaches it.
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        MainActor.assumeIsolated { UNUserNotificationCenter.current().delegate = LiveReminderNotifier.shared }
        return true
    }

    /// The scene's delegate takes the Home Screen quick actions (``QuickActions``).
    func application(_ application: UIApplication, configurationForConnecting connectingSceneSession: UISceneSession, options: UIScene.ConnectionOptions) -> UISceneConfiguration {
        let configuration = UISceneConfiguration(name: nil, sessionRole: connectingSceneSession.role)
        configuration.delegateClass = QuickActionSceneDelegate.self
        return configuration
    }

    func application(_ application: UIApplication, supportedInterfaceOrientationsFor window: UIWindow?) -> UIInterfaceOrientationMask {
        MainActor.assumeIsolated { Self.orientations }
    }

    /// A download session finished its work while the app was away; the downloads take its events.
    func application(_ application: UIApplication, handleEventsForBackgroundURLSession identifier: String, completionHandler: @escaping () -> Void) {
        MainActor.assumeIsolated {
            if !DownloadServices.handleEvents(identifier: identifier, completion: completionHandler) { completionHandler() }
        }
    }
}
