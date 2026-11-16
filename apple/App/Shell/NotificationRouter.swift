import Foundation
import Observation
import UIKit
import UserNotifications
import WMMFeatures
import WMMModel

/// The app's one notification delegate (PLAN-NEW-EPISODES.md §7.4): it
/// hands each notification to the feature that owns its category, Live TV
/// reminders (``LiveReminderNotifier``) or new episodes
/// (``NewEpisodeNotifier``), and keeps what both share: what iOS says of the
/// app's notifications, its question, and opening a tapped notification's
/// link once the app's navigator exists. A link the app published for
/// another server opens nothing (``DeepLink/opening(_:signedInServer:)``).
@MainActor @Observable
final class NotificationRouter: NSObject, UNUserNotificationCenterDelegate {
    static let shared = NotificationRouter()

    override init() {
        super.init()
        // One call sets every category, so both features' are set together.
        UNUserNotificationCenter.current().setNotificationCategories([
            LiveReminderNotifier.categoryDefinition,
            NewEpisodeNotifier.categoryDefinition,
        ])
    }

    /// What iOS last said; nil until asked.
    private(set) var status: UNAuthorizationStatus?
    /// Remind me's explainer was answered this launch, so it doesn't come again at once.
    private(set) var askedThisLaunch = false
    /// Opens a `watchmymedia://` link the app published; set once the app's navigator exists.
    var open: ((URL) -> Void)? {
        didSet { if let open, let pending { self.pending = nil; open(pending) } }
    }
    private var pending: URL?
    private var center: UNUserNotificationCenter { .current() }

    /// iOS's answer as the Kit words it.
    var permission: NotificationPermission { Self.permission(status) }

    nonisolated static func permission(_ status: UNAuthorizationStatus?) -> NotificationPermission {
        switch status {
        case .authorized?, .provisional?, .ephemeral?: .allowed
        case .denied?: .refused
        default: .notAsked
        }
    }

    /// Asks iOS now, for a check in the background where nothing has asked yet.
    func currentPermission() async -> NotificationPermission {
        await refreshStatus()
        return permission
    }

    /// Remind me's explainer shows before iOS's own ask, while iOS hasn't asked yet.
    func shouldAsk() async -> Bool {
        await refreshStatus()
        return status == .notDetermined && !askedThisLaunch
    }

    func refreshStatus() async {
        status = await center.notificationSettings().authorizationStatus
    }

    /// Continue in an explainer, or a switch turned on: iOS's own ask.
    @discardableResult
    func requestAuthorization() async -> NotificationPermission {
        askedThisLaunch = true
        _ = try? await center.requestAuthorization(options: [.alert, .sound])
        await refreshStatus()
        return permission
    }

    /// Not now in Remind me's explainer: the reminder stays on the server and in Live TV › Reminders.
    func declined() { askedThisLaunch = true }

    /// Opens `url` now, or once the navigator exists.
    func handle(_ url: URL) {
        if let open { open(url) } else { pending = url }
    }

    // MARK: UNUserNotificationCenterDelegate

    /// In the app, the system's banner still shows for reminders and new episodes; a download's notice stays quiet.
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        let request = notification.request
        let owner = NotificationOwner(category: request.content.categoryIdentifier, identifier: request.identifier)
        return owner.showsInApp ? [.banner, .list, .sound] : []
    }

    /// A tap goes to the notification's owner.
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        let request = response.notification.request
        switch NotificationOwner(category: request.content.categoryIdentifier, identifier: request.identifier) {
        case .liveReminder:
            if let link = await LiveReminderNotifier.respond(to: response, center: center) {
                await MainActor.run { NotificationRouter.shared.handle(link) }
            }
        case .newEpisode:
            if let link = NewEpisodeNotifier.link(of: response) {
                await MainActor.run { NotificationRouter.shared.handle(link) }
            }
        case .other:
            break
        }
    }
}
