import Foundation

// Rows a person arranges on Home and on each library's Movies or TV screen,
// and rows made from lists (PLAN-ROWS.md §6). The server orders and fills the
// rows; the apps draw them by style. Mirrors android/core/model's rows.

/// The API level from which the server arranges rows and makes them from lists.
public let rowsAPILevel = 22

/// A screen a person arranges: Home, or a library's Movies or TV screen.
public enum RowScreen: Hashable, Sendable {
    case home
    case library(Int)

    /// The server's name for it: `home` or `library-<id>`.
    public var key: String {
        switch self {
        case .home: "home"
        case .library(let id): "library-\(id)"
        }
    }

    public init?(key: String) {
        if key == "home" {
            self = .home
        } else if key.hasPrefix("library-"), let id = Int(key.dropFirst("library-".count)) {
            self = .library(id)
        } else {
            return nil
        }
    }
}

/// How a row is drawn. A style the app doesn't know is skipped when the reply is read.
public enum RowStyle: String, Hashable, Sendable {
    /// 2:3 posters (`items`).
    case poster
    /// 16:9 cards (`items`), as Recently released.
    case wide
    /// Episode cards (`episodes`), as Recently aired.
    case episode
    /// Continue watching (`continueItems`).
    case `continue`
    /// No items: the app draws its Live TV row in this place.
    case live
}

/// Where a row's See all goes.
public enum RowLink: Hashable, Sendable {
    /// The library's grid with these options.
    case library(id: Int, sort: ItemSort?, filter: ItemFilter?, genre: String?, decade: Int?)
    /// The list's page.
    case list(id: Int)
}

/// Where a list comes from: "TMDB", for the row's line and a link out.
public struct RowSource: Hashable, Sendable {
    /// `tmdb`, `mdblist`, `library` or `import`; a plain string.
    public var kind: String
    /// The words to show: From `name`.
    public var name: String
    public var url: URL?

    public init(kind: String, name: String, url: URL? = nil) {
        self.kind = kind
        self.name = name
        self.url = url
    }
}

/// One row of a screen, in the person's order.
public struct ScreenRow: Hashable, Sendable, Identifiable {
    /// `continue`, `live`, `recentlyAddedShows`… or `list:<id>`.
    public var id: String
    public var style: RowStyle
    /// "Recently added · Movies", or a list's name.
    public var title: String
    /// Lists: "18 of 50 · From TMDB".
    public var subtitle: String?
    public var items: [BrowseItem]
    public var episodes: [EpisodeCard]
    public var continueItems: [ContinueItem]
    public var seeAll: RowLink?
    public var source: RowSource?
    public var listID: Int?

    public init(id: String, style: RowStyle, title: String, subtitle: String? = nil, items: [BrowseItem] = [], episodes: [EpisodeCard] = [], continueItems: [ContinueItem] = [], seeAll: RowLink? = nil, source: RowSource? = nil, listID: Int? = nil) {
        self.id = id
        self.style = style
        self.title = title
        self.subtitle = subtitle
        self.items = items
        self.episodes = episodes
        self.continueItems = continueItems
        self.seeAll = seeAll
        self.source = source
        self.listID = listID
    }

    /// A row made from a list.
    public var isList: Bool { listID != nil || id.hasPrefix("list:") }

    /// Nothing to draw. A Live TV row is never empty: the app fills it.
    public var isEmpty: Bool {
        switch style {
        case .poster, .wide: items.isEmpty
        case .episode: episodes.isEmpty
        case .continue: continueItems.isEmpty
        case .live: false
        }
    }

    /// The heading as the apps draw it: a built-in row's title split at its
    /// last " · " ("Recently added", "Movies"), as Home's rows always read;
    /// a list's name whole, with its line under it.
    public var heading: (title: String, detail: String?) {
        guard !isList, let range = title.range(of: " · ", options: .backwards) else { return (title, nil) }
        let detail = String(title[range.upperBound...])
        return detail.isEmpty ? (title, nil) : (String(title[..<range.lowerBound]), detail)
    }
}

/// One row a person can place on a screen, as the editor lists it.
public struct RowLayoutEntry: Hashable, Sendable, Identifiable {
    public enum Kind: Hashable, Sendable {
        case builtin, list
        /// A kind this app doesn't know; it can still be moved and hidden.
        case other(String)
    }

    public enum Owner: Hashable, Sendable {
        /// The person's own list.
        case me
        /// A list for everyone in the household.
        case everyone
    }

    public var id: String
    public var title: String
    /// As ``ScreenRow/style``; nil for a style this app doesn't draw.
    public var style: RowStyle?
    public var kind: Kind
    /// Hidden by the person, or a list made for another screen not placed here yet.
    public var hidden: Bool
    /// Drawn now: not hidden, has something to show, and within the screen's 30 rows.
    public var shown: Bool
    /// Why a row that isn't hidden isn't shown: "Nothing from this list is in your library yet".
    public var note: String?
    public var listID: Int?
    public var owner: Owner?

    public init(id: String, title: String, style: RowStyle?, kind: Kind, hidden: Bool, shown: Bool, note: String? = nil, listID: Int? = nil, owner: Owner? = nil) {
        self.id = id
        self.title = title
        self.style = style
        self.kind = kind
        self.hidden = hidden
        self.shown = shown
        self.note = note
        self.listID = listID
        self.owner = owner
    }
}

/// A screen's rows in order, for the editor.
public struct RowLayout: Hashable, Sendable {
    public var screen: RowScreen
    public var rows: [RowLayoutEntry]
    /// Saved by this person; false while it follows the household's default.
    public var customized: Bool

    public init(screen: RowScreen, rows: [RowLayoutEntry], customized: Bool) {
        self.screen = screen
        self.rows = rows
        self.customized = customized
    }
}

/// One row in a saved order.
public struct RowPlacement: Hashable, Sendable {
    public var id: String
    public var hidden: Bool

    public init(id: String, hidden: Bool = false) {
        self.id = id
        self.hidden = hidden
    }
}

/// A list a row is made from (made on the web).
public struct MediaList: Hashable, Sendable, Identifiable {
    public var id: Int
    public var name: String
    /// `movie`, `tv` or `all`; a plain string.
    public var mediaType: String
    /// `tmdb`, `mdblist`, `library` or `import`; a plain string.
    public var sourceKind: String
    /// The server's words for From <name>: "TMDB", "an IMDb export"; nil from
    /// servers that don't send them, which the app then names by kind.
    public var sourceName: String?
    public var owner: RowLayoutEntry.Owner?
    /// The person may delete it (and change it, on the web).
    public var canEdit: Bool
    /// The screen its row was made on.
    public var screen: RowScreen?
    /// Most entries kept.
    public var limit: Int
    /// Entries fetched.
    public var entries: Int
    /// Entries the library has now.
    public var inLibrary: Int
    /// Imports: entries TMDB could not find.
    public var notFound: Int
    /// A fetch is running.
    public var fetching: Bool
    /// The last fetch that worked.
    public var fetchedAt: Date?
    /// Nil for imports and library lists, which are not fetched.
    public var nextFetchAt: Date?
    /// Why the last fetch failed; the list keeps its last good entries.
    public var error: String?
    public var errorAt: Date?
    /// When it was made: an import's "Imported Nov 2".
    public var createdAt: Date?
    /// A link to the list on its source's site, when it has one.
    public var url: URL?

    public init(id: Int, name: String, mediaType: String = "movie", sourceKind: String, sourceName: String? = nil, owner: RowLayoutEntry.Owner? = .me, canEdit: Bool = true, screen: RowScreen? = .home, limit: Int = 50, entries: Int = 0, inLibrary: Int = 0, notFound: Int = 0, fetching: Bool = false, fetchedAt: Date? = nil, nextFetchAt: Date? = nil, error: String? = nil, errorAt: Date? = nil, createdAt: Date? = nil, url: URL? = nil) {
        self.id = id
        self.name = name
        self.mediaType = mediaType
        self.sourceKind = sourceKind
        self.sourceName = sourceName
        self.owner = owner
        self.canEdit = canEdit
        self.screen = screen
        self.limit = limit
        self.entries = entries
        self.inLibrary = inLibrary
        self.notFound = notFound
        self.fetching = fetching
        self.fetchedAt = fetchedAt
        self.nextFetchAt = nextFetchAt
        self.error = error
        self.errorAt = errorAt
        self.createdAt = createdAt
        self.url = url
    }

    /// The server fetches it from TMDB or MDBList, so Refresh now can ask again.
    public var refreshable: Bool { nextFetchAt != nil || sourceKind == "tmdb" || sourceKind == "mdblist" }
}

/// One title on a list, in the list's order.
public struct MediaListEntry: Hashable, Sendable, Identifiable {
    /// 1 for the first.
    public var rank: Int
    public var mediaType: MediaType?
    public var tmdbID: Int?
    public var title: String
    public var year: Int?
    /// TMDB's poster, for a title the library doesn't have.
    public var posterPath: String?
    /// False for an imported title TMDB could not find.
    public var found: Bool
    /// The library's movie or show, when it has the title.
    public var item: BrowseItem?
    /// What Request would do, when the person may request; nil when requests
    /// aren't set up or the state is one this app doesn't know.
    public var requestState: Requestable.State?

    public init(rank: Int, mediaType: MediaType?, tmdbID: Int?, title: String, year: Int? = nil, posterPath: String? = nil, found: Bool = true, item: BrowseItem? = nil, requestState: Requestable.State? = nil) {
        self.rank = rank
        self.mediaType = mediaType
        self.tmdbID = tmdbID
        self.title = title
        self.year = year
        self.posterPath = posterPath
        self.found = found
        self.item = item
        self.requestState = requestState
    }

    public var id: Int { rank }

    /// The library has it.
    public var inLibrary: Bool { item != nil }

    /// What to send to the request sheet, when Request applies.
    public var requestable: Requestable? {
        guard item == nil, found, let mediaType, let tmdbID, let requestState else { return nil }
        return Requestable(mediaType: mediaType, tmdbID: tmdbID, title: title, year: year, posterPath: posterPath, state: requestState)
    }
}

/// A page of a list's entries.
public struct MediaListEntries: Hashable, Sendable {
    public var items: [MediaListEntry]
    public var total: Int
    public var inLibrary: Int
    public var offset: Int

    public init(items: [MediaListEntry], total: Int, inLibrary: Int, offset: Int = 0) {
        self.items = items
        self.total = total
        self.inLibrary = inLibrary
        self.offset = offset
    }
}

/// Arranged rows and lists on the current server, from ``rowsAPILevel``.
/// Methods throw ``APIError``; a revoked sign-in throws ``SignedOutError``.
@MainActor
public protocol RowsRepository: AnyObject {
    /// The server arranges rows (``rowsAPILevel``). Below it, every screen
    /// keeps its fixed rows and nothing here is called.
    var available: Bool { get }

    /// A library screen's rows in the person's order; empty below ``rowsAPILevel``.
    func libraryRows(libraryID: Int) async throws -> [ScreenRow]

    /// Every row the person can place on `screen`, hidden ones too.
    func layout(_ screen: RowScreen) async throws -> RowLayout

    /// Saves the order and what is hidden; rows left out keep their default place, shown.
    func saveLayout(_ rows: [RowPlacement], for screen: RowScreen) async throws -> RowLayout

    /// Back to the household's default.
    func resetLayout(_ screen: RowScreen) async throws -> RowLayout

    /// The person's own lists, then the household's.
    func lists() async throws -> [MediaList]

    func list(id: Int) async throws -> MediaList

    /// Up to `limit` entries from `offset`, in rank order.
    func entries(listID: Int, offset: Int, limit: Int) async throws -> MediaListEntries

    /// Fetches the list again now.
    func refresh(listID: Int) async throws -> MediaList

    func delete(listID: Int) async throws

    /// Yields when the person's layouts or lists changed (rows.changed,
    /// lists.changed, or a change made here), so screens refetch. Each call is
    /// a new listener; it ends when the task is cancelled.
    func changes() -> AsyncStream<Void>
}

/// A server below ``rowsAPILevel``, or nobody signed in.
@MainActor
public final class UnavailableRows: RowsRepository {
    static let tooOld = APIError(status: nil, message: "This server can’t arrange rows yet. Update the server.")

    public init() {}
    public var available: Bool { false }
    public func libraryRows(libraryID: Int) async throws -> [ScreenRow] { [] }
    public func layout(_ screen: RowScreen) async throws -> RowLayout { throw UnavailableRows.tooOld }
    public func saveLayout(_ rows: [RowPlacement], for screen: RowScreen) async throws -> RowLayout { throw UnavailableRows.tooOld }
    public func resetLayout(_ screen: RowScreen) async throws -> RowLayout { throw UnavailableRows.tooOld }
    public func lists() async throws -> [MediaList] { [] }
    public func list(id: Int) async throws -> MediaList { throw UnavailableRows.tooOld }
    public func entries(listID: Int, offset: Int, limit: Int) async throws -> MediaListEntries { throw UnavailableRows.tooOld }
    public func refresh(listID: Int) async throws -> MediaList { throw UnavailableRows.tooOld }
    public func delete(listID: Int) async throws { throw UnavailableRows.tooOld }
    public func changes() -> AsyncStream<Void> { AsyncStream { _ in } }
}
