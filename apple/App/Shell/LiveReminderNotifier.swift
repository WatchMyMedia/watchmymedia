import Foundation
import Observation
import UIKit
import UserNotifications
import WMMFeatures
import WMMModel

/// Live TV reminders as local notifications on iPhone and iPad
/// (PLAN-LIVE-TV-GUIDE.md §6.5): the soonest 64 the server keeps
/// (``LiveExtrasStore/alerts(limit:)``), scheduled again at launch, on
/// foreground, on the server's event and after every change here. A tap
/// opens the channel's player, through a link the app publishes for the
/// server signed in when it was scheduled: another server's opens nothing,
/// and one from before the link named its server opens the guide
/// (``DeepLink/opening(_:signedInServer:)``). Permission is asked at the
/// first Remind me, after the app's own explainer; Not now keeps the
/// reminder on the server. The notification offers Watch (as a tap) and
/// Remind again, which notifies once more five minutes later
/// (PLAN-APPLE-REVIEW.md §6.3). ``NotificationRouter`` is the delegate and
/// hands the reminders' category here.
@MainActor
final class LiveReminderNotifier {
    static let shared = LiveReminderNotifier()

    /// Every request this app schedules starts so; others are left alone.
    nonisolated static let prefix = NotificationOwner.liveReminderPrefix
    /// A Remind again: kept through rescheduling while its server is the one signed in.
    nonisolated static let againPrefix = "live-reminder-again-"
    nonisolated static let category = NotificationOwner.liveReminderCategory
    nonisolated static let watchAction = "watch"
    nonisolated static let remindAgainAction = "remind-again"
    nonisolated static let remindAgainSeconds: TimeInterval = 5 * 60

    /// Watch and Remind again, registered by the router with the other categories.
    nonisolated static var categoryDefinition: UNNotificationCategory {
        let watch = UNNotificationAction(identifier: watchAction, title: "Watch", options: [.foreground])
        let again = UNNotificationAction(identifier: remindAgainAction, title: "Remind again", options: [])
        return UNNotificationCategory(identifier: category, actions: [watch, again], intentIdentifiers: [])
    }

    private var center: UNUserNotificationCenter { .current() }

    /// Replaces this app's pending reminders with `alerts`, for `serverID`'s sign-in.
    func reschedule(_ alerts: [LiveReminderAlert], serverID: String? = nil) async {
        let wanted = Dictionary(alerts.map { (Self.prefix + $0.id, $0) }, uniquingKeysWith: { a, _ in a })
        let pendingRequests = await center.pendingNotificationRequests()
        var keep: Set<String> = []
        var stale: [String] = []
        for request in pendingRequests where request.identifier.hasPrefix(Self.prefix) {
            if request.identifier.hasPrefix(Self.againPrefix) {
                // Asked for on this sign-in: it stays; another server's or a signed-out one goes.
                if serverID == nil || request.content.userInfo["serverID"] as? String != serverID { stale.append(request.identifier) }
                continue
            }
            if let alert = wanted[request.identifier], Self.matches(request, alert),
               request.content.userInfo["serverID"] as? String == serverID { keep.insert(request.identifier) }
            else { stale.append(request.identifier) }
        }
        if !stale.isEmpty { center.removePendingNotificationRequests(withIdentifiers: stale) }
        for (identifier, alert) in wanted where !keep.contains(identifier) {
            let content = UNMutableNotificationContent()
            content.title = alert.title
            content.body = alert.body
            content.sound = .default
            content.threadIdentifier = "live-tv-reminders"
            content.categoryIdentifier = Self.category
            var info: [String: String] = [:]
            if let channelID = alert.reminder.channelID { info["channelID"] = channelID }
            if let serverID { info["serverID"] = serverID }
            content.userInfo = info
            let parts = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute, .second], from: alert.date)
            let request = UNNotificationRequest(identifier: identifier, content: content,
                                                trigger: UNCalendarNotificationTrigger(dateMatching: parts, repeats: false))
            try? await center.add(request)
        }
    }

    private static func matches(_ request: UNNotificationRequest, _ alert: LiveReminderAlert) -> Bool {
        guard let trigger = request.trigger as? UNCalendarNotificationTrigger, let date = trigger.nextTriggerDate() else { return false }
        // One scheduled before the notification had its actions is scheduled again with them.
        return abs(date.timeIntervalSince(alert.date)) < 1 && request.content.title == alert.title && request.content.body == alert.body
            && request.content.categoryIdentifier == category
    }

    /// A tap or Watch: the channel's link, for the router to open. Remind
    /// again notifies once more in five minutes and opens nothing.
    nonisolated static func respond(to response: UNNotificationResponse, center: UNUserNotificationCenter) async -> URL? {
        let request = response.notification.request
        if response.actionIdentifier == remindAgainAction {
            let again = UNNotificationRequest(
                identifier: againPrefix + UUID().uuidString, content: request.content,
                trigger: UNTimeIntervalNotificationTrigger(timeInterval: remindAgainSeconds, repeats: false)
            )
            try? await center.add(again)
            return nil
        }
        guard response.actionIdentifier == UNNotificationDefaultActionIdentifier || response.actionIdentifier == watchAction else { return nil }
        let info = request.content.userInfo
        let channelID = info["channelID"] as? String
        let serverID = info["serverID"] as? String
        let link = DeepLink.liveTV(channelID: channelID)
        return serverID.map { link.url(publishedFor: $0, source: "reminder") } ?? link.url
    }
}
