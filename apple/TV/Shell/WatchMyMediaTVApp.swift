import SwiftUI
import WMMData
import WMMFeatures
import WMMModel
import WMMUI

/// The Apple TV app: the
/// server picker, sign-in by pairing, then the tvOS tab bar, all over the
/// Kit's screen models. The player is the system's `AVPlayerViewController`.
@main
struct WatchMyMediaTVApp: App {
    @State private var services: AppServices
    @State private var navigator: Navigator
    /// Servers, the switcher: one for the app, since Servers, Settings, Search and links all switch.
    @State private var servers: ServersModel
    /// Live TV favourites and reminders, shared by every screen.
    @State private var extras: LiveExtrasStore
    @State private var recordings: LiveRecordingsStore
    /// The shows the person follows: the bell, Settings' list. The TV doesn't notify.
    @State private var follows: FollowsStore
    /// The person's watchlists: changed at once, with no message, as Apple TV says nothing after a change.
    @State private var watchlists: WatchlistStore

    init() {
        // Launch to Home's first content. tvOS has no MetricKit, so start() does nothing here.
        PerformanceSignposts.shared.launchBegan()
        Diagnostics.start()
        let services = ServicesFactory.make(arguments: ProcessInfo.processInfo.arguments)
        _services = State(initialValue: services)
        let navigator = Navigator()
        _navigator = State(initialValue: navigator)
        // Apple TV keeps no downloads, so a server that doesn't answer offers only Try again.
        _servers = State(initialValue: ServersModel(servers: services.servers, navigator: navigator))
        _extras = State(initialValue: LiveExtrasStore(repository: services.liveTV))
        _recordings = State(initialValue: LiveRecordingsStore(repository: services.liveTV))
        _follows = State(initialValue: FollowsStore(repository: services.follows))
        _watchlists = State(initialValue: WatchlistStore(repository: services.watchlists, confirms: false))
    }

    /// How long a reminder's banner stays: ten seconds, or, in a debug build,
    /// the seconds after `-liveBannerSeconds`, so a UI test on a busy
    /// simulator isn't racing the banner's ten seconds to read it.
    private static var bannerDuration: Duration {
        #if DEBUG
        let arguments = ProcessInfo.processInfo.arguments
        if let i = arguments.firstIndex(of: "-liveBannerSeconds"), arguments.indices.contains(i + 1),
           let seconds = Int(arguments[i + 1]), seconds > 0 {
            return .seconds(seconds)
        }
        #endif
        return .seconds(10)
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(services)
                .environment(navigator)
                .environment(servers)
                .environment(extras)
                .environment(recordings)
                .environment(follows)
                .environment(watchlists)
                .environment(\.imageLoader, services.images)
                .preferredColorScheme(.dark)
                .tint(Palette.gold)
                // A play or channel link acts only when the app published it for a server this
                // Apple TV is signed in to; one for a server that isn't in use switches to it first.
                .onOpenURL { url in
                    Task { await servers.open(url, library: services.library, liveTV: services.liveTV) { $0.opensOnAppleTV } }
                }
                .task { await navigator.applyLaunchArguments(ProcessInfo.processInfo.arguments, library: services.library, liveTV: services.liveTV) }
                // The Top Shelf shows only the signed-in person's rows: cleared when the person or
                // the server changes, then published again for the server now in use.
                .keepsTopShelfForTheAccount(services)
                // The person's other devices change favourites and reminders too.
                .task { await extras.follow() }
                .task { await recordings.follow() }
                .task { await follows.listen() }
                .task { await watchlists.listen() }
                // tvOS notifications carry only badges: a reminder is a banner while the app is open.
                .task(id: extras.alertsKey) { await extras.runBanners(duration: Self.bannerDuration) }
        }
    }
}

extension DeepLink {
    /// Every link but Link a TV, which belongs to the phone (the TV is the linked side).
    var opensOnAppleTV: Bool {
        if case .linkTV = self { return false }
        return true
    }
}
