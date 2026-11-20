import Foundation

// New episodes' words, written once for
// the iPhone, iPad and Apple TV apps, verbatim from the boards' wording
// table, as LiveWording.swift is for Live TV. The web and Android boards
// are drawn from the same list.

public enum NewEpisodeWords {
    // MARK: The bell (iPhone, iPad, Apple TV)

    /// The bell off.
    public static let notifyMe = "Notify me"
    /// The bell on: pressing it again stops.
    public static let notificationsOn = "Notifications on"

    /// The bell's: "Notify me" or "Notifications on".
    public static func bell(following: Bool) -> String { following ? notificationsOn : notifyMe }

    /// What VoiceOver reads: "Notify me of new episodes of The Sleuth Files",
    /// "Notifications on for The Sleuth Files. Turn them off".
    public static func bellLabel(following: Bool, show: String) -> String {
        following ? "Notifications on for \(show). Turn them off" : "Notify me of new episodes of \(show)"
    }

    /// A follow the server refused, under the actions.
    public static func followFailed(_ show: String) -> String {
        "Couldn’t turn on notifications for \(show). Check your connection and try again."
    }

    /// A stop the server refused, in the same place.
    public static func unfollowFailed(_ show: String) -> String {
        "Couldn’t turn off notifications for \(show). Check your connection and try again."
    }

    /// Past the server's 500 (its 409).
    public static let atLimit = "You follow 500 shows, the most you can. Choose Stop notifying for one in Settings › Notifications first."

    /// While the bell is on and iOS refuses, under the actions; and at the top of Settings' section.
    public static let systemRefused = "Notifications are off for WatchMyMedia. Turn them on in Settings."
    public static let openSettings = "Open Settings"

    /// Apple TV, under the buttons while the bell has focus; and the TV's Settings lede.
    public static let tvLine = "Your phone or tablet notifies you. This TV shows new episodes on Home."

    // MARK: The explainer (iPhone, iPad)

    public static let askTitle = "Get notified of new episodes"

    /// "When a new episode of The Sleuth Files reaches the library, this iPhone shows a notification."
    public static func askBody(show: String, device: String) -> String {
        "When a new episode of \(show) reaches the library, this \(device) shows a notification."
    }

    /// The limits, on the explainer and under Settings' switch; iPadOS and the iPad on an iPad.
    public static func limits(device: String) -> String {
        "\(systemName(device: device)) decides when to check, so it can take a few hours. If you close WatchMyMedia from the app switcher, checks stop until you open it again. Your \(device) has to be able to reach your server."
    }

    /// What Not now does: "Not now still follows The Sleuth Files. Its new episodes show on Home, and your other devices still notify you."
    public static func askNotNowKeeps(show: String) -> String {
        "Not now still follows \(show). Its new episodes show on Home, and your other devices still notify you."
    }

    public static let askContinue = "Continue"
    public static let askNotNow = "Not now"

    /// "iOS", or "iPadOS" on an iPad, as Settings names the system.
    public static func systemName(device: String) -> String { device == "iPad" ? "iPadOS" : "iOS" }

    // MARK: The notification (iPhone, iPad)

    /// "New episode · The Sleuth Files", "3 new episodes · The Sleuth Files".
    public static func noticeTitle(show: String, count: Int) -> String {
        count == 1 ? "New episode · \(show)" : "\(formatCount(count)) new episodes · \(show)"
    }

    /// One episode: "S3 E7 · The Long Night", or "S3 E7" with no title.
    /// Several: from the earliest to the latest, naming the season again only
    /// when it changes: "S3 E7 to E9", "S2 E10 to S3 E2". The count says how
    /// many, so a gap in a run still reads "S3 E7 to E9". More arrived than
    /// the server lists (it lists the earliest five): "From S3 E1", since
    /// the latest isn't known (settled with the web, 2026-11-16).
    public static func noticeBody(_ episodes: [ArrivedEpisode], count: Int) -> String {
        guard let first = episodes.first else { return "" }
        if count > episodes.count { return "From \(episodeCode(season: first.season, number: first.number))" }
        guard let last = episodes.last, episodes.count > 1 else {
            return [episodeCode(season: first.season, number: first.number), first.title].compactMap { $0 }.joined(separator: " · ")
        }
        let end = last.season == first.season ? "E\(last.number)" : episodeCode(season: last.season, number: last.number)
        return "\(episodeCode(season: first.season, number: first.number)) to \(end)"
    }

    /// More than five shows in one reply: "4 more shows have new episodes", "1 more show has new episodes".
    public static func moreShows(_ count: Int) -> String {
        count == 1 ? "1 more show has new episodes" : "\(formatCount(count)) more shows have new episodes"
    }

    /// "S3 E7"; specials are season 0, "S0 E3".
    public static func episodeCode(season: Int, number: Int) -> String { "S\(season) E\(number)" }

    // MARK: Settings › Notifications (iPhone, iPad)

    /// The root's row and the page's title.
    public static let title = "Notifications"

    /// The root row's value: "3 shows", "1 show" or "None".
    public static func rootValue(_ count: Int) -> String { count == 0 ? "None" : plural(count, "show") }

    public static let lede = "The shows you follow are the same on every device. Each device has its own switch."

    /// "On this iPhone", "On this iPad".
    public static func onThisDevice(_ device: String) -> String { "On this \(device)" }

    public static let switchTitle = "New episodes"
    public static let switchLine = "A notification when a show you follow gets a new episode"

    /// The switch's Saved line, in place of its line for a few seconds.
    public static func switchSaved(_ on: Bool, device: String) -> String {
        on ? "Saved. This \(device) now shows a notification when a show you follow gets a new episode."
            : "Saved. This \(device) no longer notifies you of new episodes. They still show on Home."
    }

    /// While Background App Refresh isn't available, with Open Settings.
    public static let backgroundRefreshOff = "Background App Refresh is off, so new episodes are only checked while WatchMyMedia is open."

    public static let followedTitle = "Shows you follow"

    /// A show's line: its latest new episode and when ("S3 E7 · 2 days ago"),
    /// "No new episodes yet", or "Not in the library now". The server's
    /// latest can be from before the person followed: that is no new episode yet.
    public static func followLine(_ follow: FollowedShow, now: Date) -> String {
        guard follow.inLibrary else { return notInLibrary }
        guard let latest = follow.latest, latest.arrivedAt >= follow.followedAt else { return noNewYet }
        return "\(episodeCode(season: latest.season, number: latest.number)) · \(formatAgo(latest.arrivedAt, now: now))"
    }

    public static let noNewYet = "No new episodes yet"
    /// Orange; the row opens nothing.
    public static let notInLibrary = "Not in the library now"

    public static let stopNotifying = "Stop notifying"

    /// "Stop notifying me of new episodes of The Sleuth Files".
    public static func stopNotifyingLabel(_ show: String) -> String { "Stop notifying me of new episodes of \(show)" }

    /// A Stop notifying the server refused, as the row's line, orange.
    public static func removeFailed(_ show: String) -> String {
        "Couldn’t stop notifying, so you still follow \(show). Check your connection and try again."
    }

    public static let noneFollowed = "You don’t follow any shows yet. Choose Notify me on a show’s page."

    /// The list that didn't load: title, line and button, in the list's place.
    public static let listFailedTitle = "Couldn’t load the shows you follow"
    public static let listFailedLine = "The server didn’t answer. Check your connection and try again."
    public static let tryAgain = "Try again"

    /// Where Playback shows Live TV reminders; "Playback" is a link to it.
    public static let liveTVPointer = "Live TV reminders are in Playback."
    public static let liveTVPointerLink = "Playback"

    // MARK: Apple TV, Settings

    /// The group's line: "3 shows you follow", "1 show you follow" or "None".
    public static func tvGroupLine(_ count: Int) -> String {
        count == 0 ? "None" : "\(plural(count, "show")) you follow"
    }

    /// The group's panel.
    public static let tvGroupPanel = "The shows you follow. Your phone or tablet notifies you. This TV shows new episodes on Home."

    /// A show's hint in the panel.
    public static func tvShowHint(inLibrary: Bool) -> String {
        inLibrary ? "Select offers Go to show or Stop notifying · Menu goes back to the list"
            : "Select offers Stop notifying · Menu goes back to the list"
    }

    /// Select's menu.
    public static let goToShow = "Go to show"
}
