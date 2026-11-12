import Foundation

/// Favourite channels, searching the listings and reminders
/// (PLAN-LIVE-TV-GUIDE.md §5.7, §6.5): servers from this level offer them
/// alongside listings. Below it, or without listings, the guide is G5's.
public let liveExtrasAPILevel = 15

/// A reminder for one programme, kept on the server for all of a person's
/// devices. It keeps its own copy of the programme, since the listings it was
/// set from are replaced every day.
public struct LiveReminder: Hashable, Sendable, Identifiable {
    public enum State: Hashable, Sendable {
        case set
        /// A listings refresh moved the programme; ``LiveReminder/start`` is its new start.
        case moved
        /// No longer in the listings: shown as it was kept, and never notifies.
        case gone
        /// A state this app doesn't know yet: shown as set, and never notifies.
        case other(String)

        public init(_ raw: String) {
            switch raw {
            case "set": self = .set
            case "moved": self = .moved
            case "gone": self = .gone
            default: self = .other(raw)
            }
        }
    }

    public var id: String
    /// The viewer's channel with this number; nil when none is available now.
    public var channelID: String?
    public var number: String
    public var channelName: String?
    /// The listing it is for, as in the guide.
    public var programmeID: String
    public var start: Date
    public var end: Date
    public var title: String
    public var episodeTitle: String?
    public var season: Int?
    public var episode: Int?
    public var state: State

    public init(id: String, channelID: String? = nil, number: String, channelName: String? = nil, programmeID: String,
                start: Date, end: Date, title: String, episodeTitle: String? = nil, season: Int? = nil, episode: Int? = nil,
                state: State = .set) {
        self.id = id; self.channelID = channelID; self.number = number; self.channelName = channelName
        self.programmeID = programmeID; self.start = start; self.end = end; self.title = title
        self.episodeTitle = episodeTitle; self.season = season; self.episode = episode; self.state = state
    }

    /// Set from a programme in the guide or search, before the server answers.
    public init(programme: LiveProgramme, channel: LiveChannel, id: String? = nil) {
        self.init(id: id ?? "pending-\(programme.id)", channelID: channel.id, number: channel.number, channelName: channel.name,
                  programmeID: programme.id, start: programme.start, end: programme.end, title: programme.title,
                  episodeTitle: programme.episodeTitle, season: programme.season, episode: programme.episode)
    }

    /// Whether it still reminds: set or moved. Gone, and states this app doesn't know, never notify.
    public var notifies: Bool { state == .set || state == .moved }
    /// "4.1 NFLD-HD", or the number alone when the channel is unknown.
    public var channelTitle: String { [number, channelName].compactMap(\.self).joined(separator: " ") }

    /// "S2 E4 · The Low Tide", "2026 E14 · Owls of the Valley" or the episode title; nil when there is nothing to say.
    public var episodeLine: String? {
        LiveProgramme(id: programmeID, start: start, end: end, title: title, episodeTitle: episodeTitle,
                      season: season, episode: episode).episodeLine
    }

    /// When the reminder comes, `leadMinutes` before the start.
    public func alertDate(leadMinutes: Int) -> Date { start.addingTimeInterval(-Double(max(leadMinutes, 0)) * 60) }
}

/// GET /api/live-tv/reminders: those that haven't ended, soonest first, and the person's lead time.
public struct LiveReminders: Hashable, Sendable {
    public var items: [LiveReminder]
    public var leadMinutes: Int
    public init(items: [LiveReminder] = [], leadMinutes: Int = LiveReminderLead.standard) {
        self.items = items; self.leadMinutes = leadMinutes
    }
}

/// How long before a programme its reminder comes, a per-person choice in Settings.
public enum LiveReminderLead {
    public static let choices = [0, 1, 5, 10, 15]
    public static let standard = 5
}

/// A programme search found, and the channel it is on.
public struct LiveSearchHit: Hashable, Sendable, Identifiable {
    public var channel: LiveChannel
    public var programme: LiveProgramme
    public var id: String { "\(channel.id)/\(programme.id)" }
    public init(channel: LiveChannel, programme: LiveProgramme) { self.channel = channel; self.programme = programme }
}

/// Search needs this many characters, as the server does.
public let liveSearchMinimumLength = 2

/// One local notification for a reminder on iPhone and iPad, or the Apple TV's banner.
public struct LiveReminderAlert: Hashable, Sendable, Identifiable {
    public var reminder: LiveReminder
    public var date: Date
    /// "Starting in 5 min".
    public var title: String
    /// "Conference Football · 4.1 NFLD-HD".
    public var body: String
    public var id: String { reminder.id }
}

/// The alerts to schedule, soonest first: one for each reminder that still
/// notifies and whose time hasn't passed, at most `limit` (iOS keeps 64).
public func liveReminderAlerts(_ reminders: [LiveReminder], leadMinutes: Int, now: Date, limit: Int = 64) -> [LiveReminderAlert] {
    let lead = max(leadMinutes, 0)
    return reminders
        .filter { $0.notifies && $0.alertDate(leadMinutes: lead) > now && !$0.id.hasPrefix("pending-") }
        .sorted { ($0.alertDate(leadMinutes: lead), $0.id) < ($1.alertDate(leadMinutes: lead), $1.id) }
        .prefix(max(limit, 0))
        .map { LiveReminderAlert(reminder: $0, date: $0.alertDate(leadMinutes: lead), title: liveStartingIn(leadMinutes: lead),
                                 body: "\($0.title) · \($0.channelTitle)") }
}

/// The reminder that is due at `now`, for the Apple TV's banner, Home's bell
/// and the player's Channels panel: from its alert until a minute after the
/// programme starts. The soonest when several are.
public func liveReminderDue(_ reminders: [LiveReminder], leadMinutes: Int, now: Date) -> LiveReminder? {
    reminders
        .filter { $0.notifies && $0.alertDate(leadMinutes: leadMinutes) <= now && now < $0.start.addingTimeInterval(60) }
        .min { $0.start < $1.start }
}

extension LiveTVCapabilities {
    /// Favourites, search and reminders show: listings from a server at ``liveExtrasAPILevel``.
    public var extrasAvailable: Bool { listings && extras }
}

extension LiveTVRepository {
    public func setFavourite(_ favourite: Bool, channelID: String) async throws { throw LiveTVError(reason: "unavailable") }
    public func searchListings(_ query: String, limit: Int) async throws -> [LiveSearchHit] { throw LiveTVError(reason: "unavailable") }
    public func reminders() async throws -> LiveReminders { throw LiveTVError(reason: "unavailable") }
    public func remind(programmeID: String) async throws -> LiveReminder { throw LiveTVError(reason: "unavailable") }
    public func removeReminder(id: String) async throws { throw LiveTVError(reason: "unavailable") }
    public func setReminderLead(minutes: Int) async throws { throw LiveTVError(reason: "unavailable") }
    public func changes() -> AsyncStream<Void> { AsyncStream { $0.finish() } }
}
