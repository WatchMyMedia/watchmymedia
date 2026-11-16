import Foundation

// Following a show and the episodes that arrive for it (PLAN-NEW-EPISODES.md
// §5, API level 29). A person follows a show by its TMDB id, so one follow
// covers the show in every library and outlives the show's row. Each phone
// and tablet keeps its own cursor into the feed of arrivals and notifies for
// itself; the TVs show the Home row instead.

/// One show the person follows, as Settings › Notifications lists it.
public struct FollowedShow: Hashable, Sendable, Identifiable {
    /// The newest episode that arrived for the show, announced or not.
    public struct Latest: Hashable, Sendable {
        public var season: Int
        public var number: Int
        public var title: String?
        public var arrivedAt: Date

        public init(season: Int, number: Int, title: String? = nil, arrivedAt: Date) {
            self.season = season
            self.number = number
            self.title = title
            self.arrivedAt = arrivedAt
        }
    }

    /// The show's TMDB id: what removing it takes.
    public var tmdbID: Int
    /// The show's title, kept for a show that has left the library.
    public var title: String
    public var followedAt: Date
    /// The show's id while it is in a library; nil once it has left every library.
    public var showID: Int?
    public var posterPath: String?
    public var latest: Latest?

    public init(tmdbID: Int, title: String, followedAt: Date, showID: Int? = nil, posterPath: String? = nil, latest: Latest? = nil) {
        self.tmdbID = tmdbID
        self.title = title
        self.followedAt = followedAt
        self.showID = showID
        self.posterPath = posterPath
        self.latest = latest
    }

    public var id: Int { tmdbID }
    /// The show is in a library, so its row opens it.
    public var inLibrary: Bool { showID != nil }
}

/// An episode that arrived for a followed show.
public struct ArrivedEpisode: Hashable, Sendable, Identifiable {
    public var id: Int
    public var season: Int
    public var number: Int
    public var title: String?
    public var stillPath: String?
    public var arrivedAt: Date

    public init(id: Int, season: Int, number: Int, title: String? = nil, stillPath: String? = nil, arrivedAt: Date) {
        self.id = id
        self.season = season
        self.number = number
        self.title = title
        self.stillPath = stillPath
        self.arrivedAt = arrivedAt
    }
}

/// One show's new episodes: one notification.
public struct NewEpisodeGroup: Hashable, Sendable, Identifiable {
    public struct Show: Hashable, Sendable {
        public var id: Int
        public var tmdbID: Int
        public var title: String
        public var posterPath: String?

        public init(id: Int, tmdbID: Int, title: String, posterPath: String? = nil) {
            self.id = id
            self.tmdbID = tmdbID
            self.title = title
            self.posterPath = posterPath
        }
    }

    /// The episode the notification opens: the earliest of the group.
    public struct Opening: Hashable, Sendable {
        public var episodeID: Int
        public var season: Int
        public var number: Int

        public init(episodeID: Int, season: Int, number: Int) {
            self.episodeID = episodeID
            self.season = season
            self.number = number
        }
    }

    public var show: Show
    /// How many arrived, which may be more than ``episodes`` lists.
    public var count: Int
    /// Earliest first (by season, specials last, then episode), at most 5.
    public var episodes: [ArrivedEpisode]
    public var open: Opening

    public init(show: Show, count: Int, episodes: [ArrivedEpisode], open: Opening) {
        self.show = show
        self.count = count
        self.episodes = episodes
        self.open = open
    }

    public var id: Int { show.tmdbID }
}

/// What arrived since a device's cursor.
public struct NewEpisodesPage: Hashable, Sendable {
    /// Kept by the device and sent as `after` next time. Opaque.
    public var cursor: String
    /// One a show, the show with the newest arrival first.
    public var shows: [NewEpisodeGroup]

    public init(cursor: String, shows: [NewEpisodeGroup] = []) {
        self.cursor = cursor
        self.shows = shows
    }
}

/// Following shows on the current server, from ``ServerFeatures/follows``.
/// Methods throw ``APIError``; a revoked sign-in throws ``SignedOutError``.
@MainActor
public protocol FollowsRepository: AnyObject {
    /// Signed in to a server that offers following (API level 29). Below it
    /// the bell is hidden and nothing here is called.
    var available: Bool { get }

    /// The server and person signed in, "<server id>:<user id>", which a
    /// device's cursor belongs to; nil when nobody is.
    var contextID: String? { get }

    /// The server signed in to, which a notification's link is published for.
    var serverID: String? { get }

    /// Follows the show with this id (the show's own, not its TMDB id).
    func follow(showID: Int) async throws

    /// Stops following the show with this id.
    func unfollow(showID: Int) async throws

    /// The shows the person follows, newest follow first.
    func follows() async throws -> [FollowedShow]

    /// Stops following by TMDB id, for a show that may have left the library.
    func remove(tmdbID: Int) async throws

    /// What arrived for the followed shows after `after`; without it only the cursor.
    func newEpisodes(after: String?) async throws -> NewEpisodesPage

    /// Yields when the person's follows changed on another device
    /// (follows.changed) or the event stream came back after missing some.
    /// Each call is a new listener; it ends when the task is cancelled.
    func changes() -> AsyncStream<Void>
}

/// A server below API level 29, or nobody signed in.
@MainActor
public final class UnavailableFollows: FollowsRepository {
    static let tooOld = APIError(status: nil, message: "This server can’t follow shows yet. Update the server.")

    public init() {}
    public var available: Bool { false }
    public var contextID: String? { nil }
    public var serverID: String? { nil }
    public func follow(showID: Int) async throws { throw UnavailableFollows.tooOld }
    public func unfollow(showID: Int) async throws { throw UnavailableFollows.tooOld }
    public func follows() async throws -> [FollowedShow] { [] }
    public func remove(tmdbID: Int) async throws { throw UnavailableFollows.tooOld }
    public func newEpisodes(after: String?) async throws -> NewEpisodesPage { throw UnavailableFollows.tooOld }
    public func changes() -> AsyncStream<Void> { AsyncStream { _ in } }
}

/// Whether the system lets the app's notifications through, as iOS answers.
public enum NotificationPermission: Hashable, Sendable {
    /// iOS hasn't asked yet: the explainer comes before its question.
    case notAsked
    /// Refused: only the system's Settings can turn them on.
    case refused
    /// Allowed, quietly too (provisional).
    case allowed
}
