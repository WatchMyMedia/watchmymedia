import SwiftUI
import WMMData
import WMMFeatures
import WMMModel
import WMMUI

/// The Apple TV app (PLAN-IOS.md §11.2, design/mockups/apple-tv): the
/// server picker, sign-in by pairing, then the tvOS tab bar, all over the
/// Kit's screen models. The player is the system's `AVPlayerViewController`.
@main
struct WatchMyMediaTVApp: App {
    @State private var services: AppServices
    @State private var navigator = Navigator()
    /// Live TV favourites and reminders, shared by every screen (PLAN-LIVE-TV-GUIDE.md §6.5).
    @State private var extras: LiveExtrasStore
    @State private var recordings: LiveRecordingsStore

    init() {
        // Launch to Home's first content (PLAN-APPLE-REVIEW.md §4.3.1). tvOS has no MetricKit, so start() does nothing here.
        PerformanceSignposts.shared.launchBegan()
        Diagnostics.start()
        let services = ServicesFactory.make(arguments: ProcessInfo.processInfo.arguments)
        _services = State(initialValue: services)
        _extras = State(initialValue: LiveExtrasStore(repository: services.liveTV))
        _recordings = State(initialValue: LiveRecordingsStore(repository: services.liveTV))
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(services)
                .environment(navigator)
                .environment(extras)
                .environment(recordings)
                .environment(\.imageLoader, services.images)
                .preferredColorScheme(.dark)
                .tint(Palette.gold)
                // A play or channel link acts only when the app published it for this server.
                .onOpenURL { url in
                    guard let link = DeepLink.opening(url, signedInServer: services.servers.current?.server.id), link.opensOnAppleTV else { return }
                    Task { await navigator.open(link, library: services.library, liveTV: services.liveTV) }
                }
                .task { await navigator.applyLaunchArguments(ProcessInfo.processInfo.arguments, library: services.library, liveTV: services.liveTV) }
                // The person's other devices change favourites and reminders too.
                .task { await extras.follow() }
                .task { await recordings.follow() }
                // tvOS notifications carry only badges: a reminder is a banner while the app is open.
                .task(id: extras.alertsKey) { await extras.runBanners() }
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
