import Foundation
import WMMFeatures
import WMMModel

/// Keeps where the person was (the tab and each tab's pages, ``SavedPlace``)
/// for the account and server signed in, so the app reopens there after the
/// system evicts it (PLAN-APPLE-REVIEW.md §6.3): saved whenever it changes
/// (a view's scene phase didn't report the background on the iOS 27.2
/// simulator), restored once a launch when the tabs first show. Never
/// the player or Settings (a sheet on a Live TV server), and never another
/// account's place. A launch with `-open` or `-tab` goes where it says
/// instead, and on the fakes nothing is kept unless `-fakesRestorePlace`
/// is given, so UI tests start where they ask to.
@MainActor
enum PlaceKeeping {
    private static var restored = false
    private static let store = PlaceStore()

    private static var arguments: [String] { ProcessInfo.processInfo.arguments }
    private static var enabled: Bool { !arguments.contains("-fakes") || arguments.contains("-fakesRestorePlace") }

    private static func account(_ services: AppServices) -> (server: String, user: Int)? {
        guard let server = services.servers.current?.server.id, let user = services.auth.user?.id else { return nil }
        return (server, user)
    }

    static func save(_ navigator: Navigator, services: AppServices) {
        guard enabled, let account = account(services) else { return }
        store.save(navigator.place, server: account.server, user: account.user)
    }

    /// The first time the tabs show this launch.
    static func restoreOnce(_ navigator: Navigator, services: AppServices) {
        guard !restored else { return }
        restored = true
        guard enabled, !arguments.contains("-open"), !arguments.contains("-tab"),
              let account = account(services), let place = store.place(server: account.server, user: account.user) else { return }
        navigator.restore(place)
    }
}
