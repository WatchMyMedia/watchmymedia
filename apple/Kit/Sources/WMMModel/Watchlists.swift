import Foundation

// Watchlists: movies and shows a person means to watch, in lists they name.
// A watchlist is one of the person's lists (`source.kind` "watchlist"), so
// its rows, its place in Edit rows and its page are the lists' own; what is
// new is saving a title to one, by its TMDB id, from the title's page and
// the long-press menus (API level 33).

/// The `source.kind` of a list that is a watchlist.
public let watchlistSourceKind = "watchlist"

/// What a watchlist keeps a title by: whether it is a movie or a show, and its TMDB id.
public struct WatchlistKey: Hashable, Sendable {
    public var mediaType: MediaType
    public var tmdbID: Int

    public init(mediaType: MediaType, tmdbID: Int) {
        self.mediaType = mediaType
        self.tmdbID = tmdbID
    }
}

/// A title to save, with what the server needs for one it has no record of
/// (a list's missing title): its title, year and TMDB poster.
public struct WatchlistTitle: Hashable, Sendable {
    public var key: WatchlistKey
    public var title: String
    public var year: Int?
    public var posterPath: String?

    public init(key: WatchlistKey, title: String, year: Int? = nil, posterPath: String? = nil) {
        self.key = key
        self.title = title
        self.year = year
        self.posterPath = posterPath
    }
}

/// One of the person's watchlists.
public struct Watchlist: Hashable, Sendable, Identifiable {
    public var id: Int
    public var name: String
    /// Titles on it, the library's or not.
    public var titles: Int
    /// Of those, the ones the library has.
    public var inLibrary: Int
    /// Titles leave once they are watched; off, they stay.
    public var removeWatched: Bool
    /// The first four titles' poster paths, newest saved first.
    public var posters: [String]
    /// It holds the title the list was asked about; nil when none was.
    public var contains: Bool?
    public var createdAt: Date?

    public init(id: Int, name: String, titles: Int = 0, inLibrary: Int = 0, removeWatched: Bool = true, posters: [String] = [], contains: Bool? = nil, createdAt: Date? = nil) {
        self.id = id
        self.name = name
        self.titles = titles
        self.inLibrary = inLibrary
        self.removeWatched = removeWatched
        self.posters = posters
        self.contains = contains
        self.createdAt = createdAt
    }
}

/// The person's watchlists, in the order made, and the server's limits.
public struct WatchlistSet: Hashable, Sendable {
    public var items: [Watchlist]
    /// Most watchlists a person can make: 25.
    public var max: Int
    /// Most titles a watchlist holds: 500.
    public var maxTitles: Int

    public init(items: [Watchlist], max: Int = 25, maxTitles: Int = 500) {
        self.items = items
        self.max = max
        self.maxTitles = maxTitles
    }
}

/// What a save or remove left: the watchlists holding the title now.
public struct WatchlistChange: Hashable, Sendable {
    /// The ids of the watchlists that hold the title now, oldest first.
    public var holding: [Int]
    /// The watchlist saved to or taken from, with its counts after.
    public var watchlist: Watchlist?
    /// The one-press save made the first watchlist, called Watchlist.
    public var created: Bool
    /// Taken off every watchlist: the ones it was on.
    public var removed: [Int]

    public init(holding: [Int], watchlist: Watchlist? = nil, created: Bool = false, removed: [Int] = []) {
        self.holding = holding
        self.watchlist = watchlist
        self.created = created
        self.removed = removed
    }
}

/// Watchlists on the current server as the signed-in person, from
/// ``ServerFeatures/watchlists``. Methods throw ``APIError``: a 409 from
/// ``saveToOnly(_:)`` means the person has two or more; a revoked sign-in
/// throws ``SignedOutError``.
@MainActor
public protocol WatchlistsRepository: AnyObject {
    /// Signed in to a server with watchlists. Below it nothing here is called
    /// and the apps offer no button or menu item.
    var available: Bool { get }

    /// The server and person signed in, "<server id>:<user id>"; nil when nobody is.
    var contextID: String? { get }

    /// The person's watchlists, in the order made; with `containing`, each says whether it holds that title.
    func watchlists(containing: WatchlistKey?) async throws -> WatchlistSet

    func create(name: String) async throws -> Watchlist

    /// Renames it, or turns removing watched titles on or off.
    func update(id: Int, name: String?, removeWatched: Bool?) async throws -> Watchlist

    func delete(id: Int) async throws

    func save(_ title: WatchlistTitle, to id: Int) async throws -> WatchlistChange

    func remove(_ key: WatchlistKey, from id: Int) async throws -> WatchlistChange

    /// The one press: saves to the person's only watchlist, making Watchlist
    /// when they have none. With two or more it throws a 409.
    func saveToOnly(_ title: WatchlistTitle) async throws -> WatchlistChange

    /// Takes the title off every one of the person's watchlists.
    func removeFromAll(_ key: WatchlistKey) async throws -> WatchlistChange

    /// Yields when the person's lists or layouts changed (lists.changed,
    /// rows.changed, or a change made here). Each call is a new listener; it
    /// ends when the task is cancelled.
    func changes() -> AsyncStream<Void>
}

/// A server below API level 33, or nobody signed in.
@MainActor
public final class UnavailableWatchlists: WatchlistsRepository {
    static let tooOld = APIError(status: nil, message: "This server can’t keep watchlists yet. Update the server.")

    public init() {}
    public var available: Bool { false }
    public var contextID: String? { nil }
    public func watchlists(containing: WatchlistKey?) async throws -> WatchlistSet { WatchlistSet(items: []) }
    public func create(name: String) async throws -> Watchlist { throw Self.tooOld }
    public func update(id: Int, name: String?, removeWatched: Bool?) async throws -> Watchlist { throw Self.tooOld }
    public func delete(id: Int) async throws { throw Self.tooOld }
    public func save(_ title: WatchlistTitle, to id: Int) async throws -> WatchlistChange { throw Self.tooOld }
    public func remove(_ key: WatchlistKey, from id: Int) async throws -> WatchlistChange { throw Self.tooOld }
    public func saveToOnly(_ title: WatchlistTitle) async throws -> WatchlistChange { throw Self.tooOld }
    public func removeFromAll(_ key: WatchlistKey) async throws -> WatchlistChange { throw Self.tooOld }
    public func changes() -> AsyncStream<Void> { AsyncStream { _ in } }
}

extension BrowseItem {
    /// What a watchlist keeps this card's title by; nil for a title TMDB hasn't matched.
    public var watchlistKey: WatchlistKey? {
        guard let tmdbID else { return nil }
        switch kind {
        case .movie: return WatchlistKey(mediaType: .movie, tmdbID: tmdbID)
        case .show: return WatchlistKey(mediaType: .tv, tmdbID: tmdbID)
        case .unmatched: return nil
        }
    }

    public var watchlistTitle: WatchlistTitle? {
        watchlistKey.map { WatchlistTitle(key: $0, title: title, year: year, posterPath: posterPath) }
    }
}

extension MovieDetail {
    public var watchlistTitle: WatchlistTitle? {
        tmdbID.map { WatchlistTitle(key: WatchlistKey(mediaType: .movie, tmdbID: $0), title: title, year: year, posterPath: posterPath) }
    }
}

extension ShowDetail {
    public var watchlistTitle: WatchlistTitle? {
        tmdbID.map { WatchlistTitle(key: WatchlistKey(mediaType: .tv, tmdbID: $0), title: title, year: year, posterPath: posterPath) }
    }
}

extension MediaListEntry {
    /// A list's title, the library's or not; nil for one TMDB couldn't find.
    public var watchlistTitle: WatchlistTitle? {
        guard found, let mediaType, let tmdbID else { return nil }
        return WatchlistTitle(key: WatchlistKey(mediaType: mediaType, tmdbID: tmdbID), title: item?.title ?? title, year: item?.year ?? year,
                              posterPath: posterPath)
    }
}
