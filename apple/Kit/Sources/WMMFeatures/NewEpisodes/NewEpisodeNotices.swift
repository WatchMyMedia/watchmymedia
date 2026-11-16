import Foundation
import WMMModel

/// One notification the check posts (PLAN-NEW-EPISODES.md §6.2; the boards'
/// `notification`): one a show a check, threaded by show, each with its own
/// identifier, so a later check adds to the show's stack rather than
/// replacing it; and, past five shows, one more that opens Home, in a thread
/// of its own.
public struct NewEpisodeNotice: Hashable, Sendable, Identifiable {
    /// The notifications' category, which the app's delegate routes by.
    public static let category = "new-episode"
    /// Every request and thread this feature makes starts so.
    public static let prefix = "new-episode-"
    /// The "more shows" notification's thread.
    public static let moreThread = "new-episode-more"
    /// At most this many shows get a notification of their own at once.
    public static let showLimit = 5
    /// The `source=` of the links these notifications publish.
    public static let linkSource = "new-episode"

    /// The request's identifier: the show's thread and the check's cursor, so each check's is its own.
    public var id: String
    public var title: String
    /// Empty for the "more shows" notification.
    public var body: String
    /// "new-episode-<TMDB id>": the show's stack.
    public var thread: String
    /// What a tap opens: the show's page at the earliest new episode, or Home,
    /// published for the server signed in, so no other sign-in opens it.
    public var link: URL

    public init(id: String, title: String, body: String, thread: String, link: URL) {
        self.id = id
        self.title = title
        self.body = body
        self.thread = thread
        self.link = link
    }
}

/// The notifications for a reply from the feed, in the order iOS should list
/// them: one a show in the reply's order (newest arrival first), and past
/// ``NewEpisodeNotice/showLimit`` the first five, then one that says how many
/// more and opens Home. iOS lists the newest first, so post them in reverse
/// (``newEpisodePostingOrder(_:)``).
public func newEpisodeNotices(_ page: NewEpisodesPage, serverID: String, limit: Int = NewEpisodeNotice.showLimit) -> [NewEpisodeNotice] {
    let shown = page.shows.prefix(limit)
    var notices = shown.map { group in
        let thread = NewEpisodeNotice.prefix + String(group.show.tmdbID)
        let link = DeepLink.show(id: group.show.id, season: group.open.season, episode: group.open.episodeID)
        return NewEpisodeNotice(
            id: "\(thread)-\(page.cursor)",
            title: NewEpisodeWords.noticeTitle(show: group.show.title, count: max(group.count, group.episodes.count, 1)),
            body: NewEpisodeWords.noticeBody(group.episodes, count: group.count),
            thread: thread,
            link: link.url(publishedFor: serverID, source: NewEpisodeNotice.linkSource)
        )
    }
    let more = page.shows.count - shown.count
    if more > 0 {
        notices.append(NewEpisodeNotice(
            id: "\(NewEpisodeNotice.moreThread)-\(page.cursor)", title: NewEpisodeWords.moreShows(more), body: "",
            thread: NewEpisodeNotice.moreThread,
            link: DeepLink.home.url(publishedFor: serverID, source: NewEpisodeNotice.linkSource)
        ))
    }
    return notices
}

/// The order to post `notices` in, so iOS, which lists the newest first,
/// shows them in theirs: the "more shows" one first, then the shows from the
/// last to the first.
public func newEpisodePostingOrder(_ notices: [NewEpisodeNotice]) -> [NewEpisodeNotice] {
    notices.reversed()
}

/// Which feature a notification belongs to, so the app's one delegate hands
/// it to its owner (PLAN-NEW-EPISODES.md §7.4).
public enum NotificationOwner: Hashable, Sendable {
    /// Live TV reminders (PLAN-LIVE-TV-GUIDE.md §6.5).
    case liveReminder
    /// New episodes of followed shows.
    case newEpisode
    /// Anything else (a download's notice): shown, and a tap only opens the app.
    case other

    public static let liveReminderCategory = "live-reminder"
    public static let liveReminderPrefix = "live-reminder-"

    /// By the category, or, for a reminder scheduled before reminders had one, by its identifier.
    public init(category: String, identifier: String) {
        switch category {
        case Self.liveReminderCategory: self = .liveReminder
        case NewEpisodeNotice.category: self = .newEpisode
        default:
            if identifier.hasPrefix(Self.liveReminderPrefix) { self = .liveReminder }
            else if identifier.hasPrefix(NewEpisodeNotice.prefix) { self = .newEpisode }
            else { self = .other }
        }
    }

    /// Shown as a banner while the app is open too; others (a download's notice) stay quiet in the app, as before.
    public var showsInApp: Bool { self != .other }
}
