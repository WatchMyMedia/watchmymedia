import SwiftUI
import UserNotifications
import WMMDownloads
import WMMCast
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
        let services = ServicesFactory.make(arguments: ProcessInfo.processInfo.arguments)
        _services = State(initialValue: services)
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
                .onOpenURL { url in
                    guard let link = DeepLink(url: url) else { return }
                    Task { await navigator.open(link, library: services.library, liveTV: services.liveTV) }
                }
                .task { await navigator.applyLaunchArguments(ProcessInfo.processInfo.arguments, library: services.library, liveTV: services.liveTV) }
                // A reminder's notification opens its channel.
                .task {
                    LiveReminderNotifier.shared.open = { link in
                        Task { await navigator.open(link, library: services.library, liveTV: services.liveTV) }
                    }
                }
                // The person's other devices change favourites and reminders too.
                .task { await extras.follow() }
                .task { await recordings.follow() }
                // Once at launch and on each return to the app.
                .task(id: scenePhase) { if scenePhase == .active { await cast.refresh() } }
        }
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
