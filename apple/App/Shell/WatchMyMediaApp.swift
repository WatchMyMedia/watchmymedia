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
    @State private var navigator: Navigator
    @State private var cast: CastService
    @Environment(\.scenePhase) private var scenePhase
    /// Live TV favourites and reminders, shared by every screen.
    @State private var extras: LiveExtrasStore
    @State private var recordings: LiveRecordingsStore
    /// The shows the person follows, and this device's new-episode check.
    @State private var follows: FollowsStore
    @State private var newEpisodes: NewEpisodeNotifier
    /// The person's watchlists: the Watchlist button, the menus, Save to watchlist and the pages.
    @State private var watchlists: WatchlistStore
    /// Servers, the switcher: one for the app, since Home, Settings, Search,
    /// Downloads and links all switch.
    @State private var servers: ServersModel

    init() {
        // Launch to Home's first content, and MetricKit's reports for Copy diagnostics.
        PerformanceSignposts.shared.launchBegan()
        Diagnostics.start()
        let services = ServicesFactory.make(arguments: ProcessInfo.processInfo.arguments)
        _services = State(initialValue: services)
        let memory = NewEpisodeMemory()
        #if DEBUG
        // `-newEpisodesReset`: this device starts as if it had never checked or explained (UI tests).
        if ProcessInfo.processInfo.arguments.contains("-newEpisodesReset") {
            memory.forgetAll()
            memory.explained = false
            memory.notifyHere = true
        }
        #endif
        let newEpisodes = NewEpisodeNotifier(follows: services.follows, router: NotificationRouter.shared, servers: services.servers, memory: memory)
        let navigator = Navigator()
        _navigator = State(initialValue: navigator)
        _servers = State(initialValue: ServersModel(servers: services.servers, downloads: services.downloads, navigator: navigator))
        _follows = State(initialValue: FollowsStore(repository: services.follows))
        _watchlists = State(initialValue: WatchlistStore(repository: services.watchlists, sleep: Self.watchlistBannerSleep))
        _newEpisodes = State(initialValue: newEpisodes)
        BackgroundSync.register(services, newEpisodes: newEpisodes)
        _cast = State(initialValue: CastServicesFactory.make(services: services, arguments: ProcessInfo.processInfo.arguments))
        _extras = State(initialValue: LiveExtrasStore(repository: services.liveTV))
        _recordings = State(initialValue: LiveRecordingsStore(repository: services.liveTV))
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .readsFoldPosture()
                .environment(services)
                .environment(cast)
                .environment(navigator)
                .environment(extras)
                .environment(recordings)
                .environment(NotificationRouter.shared)
                .environment(follows)
                .environment(watchlists)
                .environment(newEpisodes)
                .environment(servers)
                .environment(\.imageLoader, services.images)
                .preferredColorScheme(.dark)
                .tint(Palette.gold)
                // A play or channel link acts only when the app published it for a server
                // this device is signed in to; one for another of them switches to it first.
                .onOpenURL { url in open(url) }
                .task { await navigator.applyLaunchArguments(ProcessInfo.processInfo.arguments, library: services.library, liveTV: services.liveTV) }
                // A reminder's notification opens its channel; a new episode's, its show.
                .task {
                    NotificationRouter.shared.open = { url in open(url) }
                }
                // A quick action on the app's icon, or an App Shortcut.
                .task {
                    QuickActions.shared.open = { url in open(url) }
                }
                // The person's other devices change favourites, reminders and follows too.
                .task { await extras.follow() }
                .task { await follows.listen() }
                .task { await watchlists.listen() }
                .task { await recordings.follow() }
                // Once at launch and on each return to the app.
                .task(id: scenePhase) { if scenePhase == .active { await cast.refresh() } }
                // Offline watching reaches the server in a background refresh too.
                .onChange(of: scenePhase) { _, phase in if phase == .background { BackgroundSync.schedule() } }
        }
    }
}

extension WatchMyMediaApp {
    /// How long "Saved to Watchlist" and its Undo stay up. `-watchlistBannerSeconds <n>`
    /// (debug builds) keeps it up longer, for a UI test on a busy simulator.
    private static var watchlistBannerSleep: @MainActor (Duration) async -> Void {
        #if DEBUG
        let arguments = ProcessInfo.processInfo.arguments
        if let i = arguments.firstIndex(of: "-watchlistBannerSeconds"), arguments.indices.contains(i + 1), let seconds = Double(arguments[i + 1]) {
            return { _ in try? await Task.sleep(for: .seconds(seconds)) }
        }
        #endif
        return { try? await Task.sleep(for: $0) }
    }

    /// A `watchmymedia://` link from outside the app or one it published
    /// (``ServersModel/open(_:library:liveTV:libraryOn:accepts:)``): a title
    /// published for another server this device is signed in to opens in
    /// place, from there, without switching.
    private func open(_ url: URL) {
        Task { await servers.open(url, library: services.library, liveTV: services.liveTV, libraryOn: { services.on($0).library }) }
    }
}

/// Holds what UIKit still decides: which orientations the app allows now
/// (in a phone's window the player asks for landscape while it is on
/// screen and the rest of the app stays portrait; a wide window, an iPad's
/// or the iPhone Duo's inner screen, turns freely), and the download
/// sessions' background events.
final class AppDelegate: NSObject, UIApplicationDelegate {
    /// What a phone's window allows now (``OrientationLock``).
    @MainActor static var orientations: UIInterfaceOrientationMask = .portrait

    /// Set before launch finishes, so a tap on a notification that opened the app reaches it.
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        MainActor.assumeIsolated { UNUserNotificationCenter.current().delegate = NotificationRouter.shared }
        return true
    }

    /// The scene's delegate takes the Home Screen quick actions (``QuickActions``).
    func application(_ application: UIApplication, configurationForConnecting connectingSceneSession: UISceneSession, options: UIScene.ConnectionOptions) -> UISceneConfiguration {
        let configuration = UISceneConfiguration(name: nil, sessionRole: connectingSceneSession.role)
        configuration.delegateClass = QuickActionSceneDelegate.self
        return configuration
    }

    /// By the window's size classes (its scene's, before the window has its
    /// own), so the Duo's outer screen is a phone's and its inner screen,
    /// held either way, a wide one.
    func application(_ application: UIApplication, supportedInterfaceOrientationsFor window: UIWindow?) -> UIInterfaceOrientationMask {
        MainActor.assumeIsolated {
            guard let window else { return Self.orientations }
            let own = window.traitCollection
            let traits = own.horizontalSizeClass == .unspecified ? window.windowScene?.traitCollection ?? own : own
            // Before the window has its size classes (at launch), an iPad
            // turns freely, as it always has, so one held sideways starts sideways.
            guard traits.horizontalSizeClass != .unspecified else {
                return traits.userInterfaceIdiom == .pad ? .all : Self.orientations
            }
            return PlayerWindow(traits) == .wide ? .all : Self.orientations
        }
    }

    /// A download session finished its work while the app was away; the downloads take its events.
    func application(_ application: UIApplication, handleEventsForBackgroundURLSession identifier: String, completionHandler: @escaping () -> Void) {
        MainActor.assumeIsolated {
            if !DownloadServices.handleEvents(identifier: identifier, completion: completionHandler) { completionHandler() }
        }
    }
}
