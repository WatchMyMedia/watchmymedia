import Foundation
import Observation
import UIKit
import UserNotifications
import WMMFeatures
import WMMModel

/// New-episode notifications on iPhone and iPad (PLAN-NEW-EPISODES.md §6,
/// §7.4): runs the Kit's ``NewEpisodeChecker`` and posts what it finds at
/// once through UNUserNotificationCenter, in the `new-episode` category,
/// threaded by show, each check's with its own identifier so a later one
/// stacks on the show's. The check runs in the background refresh after the
/// downloads' sync (``BackgroundSync``), when the app opens or comes back,
/// and three minutes after the last library change while it is open
/// (``RootView``). A sign-out or another server forgets the cursor and
/// takes away what the last account was told. ``NotificationRouter`` hands
/// a tap here, and opens the link only for the server it came from.
@MainActor @Observable
final class NewEpisodeNotifier {
    /// The category, with no actions: a tap opens the show (the boards' `notification`).
    nonisolated static var categoryDefinition: UNNotificationCategory {
        UNNotificationCategory(identifier: NewEpisodeNotice.category, actions: [], intentIdentifiers: [])
    }

    /// What this device keeps: the cursor, its switch and the explainer's mark.
    let memory: NewEpisodeMemory
    @ObservationIgnored let checker: NewEpisodeChecker
    /// Background App Refresh as iOS last said; checked when Settings › Notifications appears and the app comes forward.
    private(set) var backgroundRefresh = true
    @ObservationIgnored private let follows: any FollowsRepository
    /// The server and person the delivered notifications were for.
    @ObservationIgnored private var context: String?

    init(follows: any FollowsRepository, router: NotificationRouter, memory: NewEpisodeMemory = NewEpisodeMemory()) {
        self.memory = memory
        self.follows = follows
        context = follows.contextID
        checker = NewEpisodeChecker(
            repository: follows, memory: memory,
            permission: { await router.currentPermission() },
            post: { notice in await Self.post(notice) }
        )
    }

    /// Reads Background App Refresh: off for the app, for every app, in Low Power Mode or by a restriction.
    func refreshBackgroundStatus() {
        backgroundRefresh = UIApplication.shared.backgroundRefreshStatus == .available
    }

    /// Checks now, unless one is running.
    @discardableResult
    func check() async -> NewEpisodeChecker.Outcome { await checker.check() }

    /// A sign-out, another account or another server: the cursor goes, and so
    /// do the notifications the last one was shown, which name its shows.
    func accountChanged() async {
        checker.accountChanged()
        // The same person on the same server (their name changed, say) keeps what they were shown.
        guard follows.contextID != context else { return }
        context = follows.contextID
        let center = UNUserNotificationCenter.current()
        let delivered = await center.deliveredNotifications()
        let ours = delivered.map(\.request).filter { $0.content.categoryIdentifier == NewEpisodeNotice.category }.map(\.identifier)
        if !ours.isEmpty { center.removeDeliveredNotifications(withIdentifiers: ours) }
    }

    /// Shows one, now. The checker hands them over in the order to post
    /// them: iOS lists the newest first, so last to first they read in the
    /// reply's order, the "more shows" one after the five.
    private static func post(_ notice: NewEpisodeNotice) async {
        let content = UNMutableNotificationContent()
        content.title = notice.title
        content.body = notice.body
        content.sound = .default
        content.threadIdentifier = notice.thread
        content.relevanceScore = notice.relevance
        content.categoryIdentifier = NewEpisodeNotice.category
        content.userInfo = ["link": notice.link.absoluteString]
        try? await UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: notice.id, content: content, trigger: nil))
    }

    /// What a tap opens: the show's page at its earliest new episode, or Home.
    nonisolated static func link(of response: UNNotificationResponse) -> URL? {
        guard response.actionIdentifier == UNNotificationDefaultActionIdentifier,
              let link = response.notification.request.content.userInfo["link"] as? String else { return nil }
        return URL(string: link)
    }
}
