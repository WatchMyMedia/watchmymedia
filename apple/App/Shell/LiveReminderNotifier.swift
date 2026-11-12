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
/// opens the channel's player. Permission is asked at the first Remind me,
/// after the app's own explainer; Not now keeps the reminder on the server.
@MainActor @Observable
final class LiveReminderNotifier: NSObject, UNUserNotificationCenterDelegate {
    static let shared = LiveReminderNotifier()

    /// Every request this app schedules starts so; others are left alone.
    nonisolated static let prefix = "live-reminder-"

    /// What iOS last said; nil until asked.
    private(set) var status: UNAuthorizationStatus?
    /// The explainer was answered this launch, so it doesn't come again at once.
    private(set) var askedThisLaunch = false
    /// Opens a `watchmymedia://live-tv/<channel>` link; set once the app's navigator exists.
    var open: ((DeepLink) -> Void)? {
        didSet { if let open, let pending { self.pending = nil; open(pending) } }
    }
    private var pending: DeepLink?
    private var center: UNUserNotificationCenter { .current() }

    /// The explainer shows before iOS's own ask, while iOS hasn't asked yet.
    func shouldAsk() async -> Bool {
        await refreshStatus()
        return status == .notDetermined && !askedThisLaunch
    }

    func refreshStatus() async {
        status = await center.notificationSettings().authorizationStatus
    }

    /// Continue in the explainer: iOS's own ask.
    func requestAuthorization() async {
        askedThisLaunch = true
        _ = try? await center.requestAuthorization(options: [.alert, .sound])
        await refreshStatus()
    }

    /// Not now: the reminder stays on the server and in Live TV › Reminders.
    func declined() { askedThisLaunch = true }

    /// Replaces this app's pending reminders with `alerts`.
    func reschedule(_ alerts: [LiveReminderAlert]) async {
        let wanted = Dictionary(alerts.map { (Self.prefix + $0.id, $0) }, uniquingKeysWith: { a, _ in a })
        let pendingRequests = await center.pendingNotificationRequests()
        var keep: Set<String> = []
        var stale: [String] = []
        for request in pendingRequests where request.identifier.hasPrefix(Self.prefix) {
            if let alert = wanted[request.identifier], Self.matches(request, alert) { keep.insert(request.identifier) }
            else { stale.append(request.identifier) }
        }
        if !stale.isEmpty { center.removePendingNotificationRequests(withIdentifiers: stale) }
        for (identifier, alert) in wanted where !keep.contains(identifier) {
            let content = UNMutableNotificationContent()
            content.title = alert.title
            content.body = alert.body
            content.sound = .default
            content.threadIdentifier = "live-tv-reminders"
            if let channelID = alert.reminder.channelID { content.userInfo = ["channelID": channelID] }
            let parts = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute, .second], from: alert.date)
            let request = UNNotificationRequest(identifier: identifier, content: content,
                                                trigger: UNCalendarNotificationTrigger(dateMatching: parts, repeats: false))
            try? await center.add(request)
        }
    }

    private static func matches(_ request: UNNotificationRequest, _ alert: LiveReminderAlert) -> Bool {
        guard let trigger = request.trigger as? UNCalendarNotificationTrigger, let date = trigger.nextTriggerDate() else { return false }
        return abs(date.timeIntervalSince(alert.date)) < 1 && request.content.title == alert.title && request.content.body == alert.body
    }

    // MARK: UNUserNotificationCenterDelegate

    /// In the app, the system's banner still shows.
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        notification.request.identifier.hasPrefix(Self.prefix) ? [.banner, .list, .sound] : []
    }

    /// A tap opens the channel in the player.
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        guard response.notification.request.identifier.hasPrefix(Self.prefix),
              response.actionIdentifier == UNNotificationDefaultActionIdentifier else { return }
        let channelID = response.notification.request.content.userInfo["channelID"] as? String
        await MainActor.run { self.handle(.liveTV(channelID: channelID)) }
    }

    private func handle(_ link: DeepLink) {
        if let open { open(link) } else { pending = link }
    }
}
