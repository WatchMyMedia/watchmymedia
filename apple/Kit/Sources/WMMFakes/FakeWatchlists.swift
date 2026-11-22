import Foundation
import WMMModel

/// Watchlists on the fakes (every title and name is fictional): Watchlist,
/// with titles the library has and three it doesn't (one to request, one
/// requested, one downloading); Halloween, which keeps watched titles; and
/// With the kids. `seeded: false` starts with none, as a person who has
/// saved nothing. Only at ``watchlistsAPILevel`` (`-fakesWatchlists`). The
/// fake rows draw each as a row and a list page (``FakeRowsRepository``).
@MainActor
public final class FakeWatchlistsRepository: WatchlistsRepository {
    struct Saved {
        var title: WatchlistTitle
        var addedAt: Date
        /// A title the library lacks: what Request would do.
        var state: Requestable.State?
    }

    struct Kept {
        var watchlist: Watchlist
        /// Newest saved first.
        var saved: [Saved]
    }

    /// The next change fails, as an unanswered server would.
    public var failNext = false
    /// Calls made here, in order ("saveToOnly", "remove:9101"…), so a test can tell what reached the server.
    public private(set) var calls: [String] = []

    public static let watchlist = 9101
    public static let halloween = 9102
    public static let withTheKids = 9103

    /// The fake rows, which draw each watchlist and share its changes.
    weak var rows: FakeRowsRepository?
    private let servers: FakeServerRepository?
    private let now: Date
    private var kept: [Kept]
    private var nextID = 9104
    private var listeners: [UUID: AsyncStream<Void>.Continuation] = [:]

    public init(servers: FakeServerRepository? = nil, seeded: Bool = true, now: Date = .now) {
        self.servers = servers
        self.now = now
        kept = seeded ? Self.seed(now: now) : []
    }

    private static func seed(now: Date) -> [Kept] {
        let day: TimeInterval = 86_400
        func movie(_ id: Int, _ daysAgo: Double) -> Saved {
            let m = FakeData.movies.first { $0.id == id }!
            return Saved(title: WatchlistTitle(key: .init(mediaType: .movie, tmdbID: FakeData.tmdbID(movieID: id)), title: m.title, year: m.year), addedAt: now - daysAgo * day)
        }
        func show(_ id: Int, _ daysAgo: Double) -> Saved {
            let s = FakeData.shows.first { $0.id == id }!
            return Saved(title: WatchlistTitle(key: .init(mediaType: .tv, tmdbID: FakeData.tmdbID(showID: id)), title: s.title, year: s.year), addedAt: now - daysAgo * day)
        }
        func missing(_ title: String, _ year: Int, _ type: MediaType, _ tmdbID: Int, _ state: Requestable.State, _ daysAgo: Double) -> Saved {
            Saved(title: WatchlistTitle(key: .init(mediaType: type, tmdbID: tmdbID), title: title, year: year), addedAt: now - daysAgo * day, state: state)
        }
        func list(_ id: Int, _ name: String, keeps: Bool = false, made: Double, _ saved: [Saved]) -> Kept {
            Kept(watchlist: Watchlist(id: id, name: name, removeWatched: !keeps, createdAt: now - made * day), saved: saved)
        }
        return [
            list(watchlist, "Watchlist", made: 60, [
                missing("Amberline", 2026, .movie, 91_001, .requestable, 1), movie(504, 2), show(100, 3), movie(507, 4), movie(502, 5),
                show(104, 6), missing("Winter Harbor", 2026, .movie, 91_002, .downloading, 7), show(109, 8),
                missing("The Ninth Bell", 2026, .movie, 91_003, .requested, 9), movie(505, 10),
            ]),
            list(halloween, "Halloween", keeps: true, made: 40, [movie(506, 3), movie(508, 4), show(110, 5)]),
            list(withTheKids, "With the kids", made: 20, [movie(501, 2), show(102, 6), missing("Northern Static", 2025, .tv, 71_020, .requestable, 9)]),
        ]
    }

    /// One watchlist, Watchlist, holding `title`: a person who has saved one title.
    public func seedOnlyWatchlist(holding title: WatchlistTitle) {
        kept = [Kept(watchlist: Watchlist(id: Self.watchlist, name: WatchlistWords.firstName, createdAt: now), saved: [Saved(title: title, addedAt: now)])]
    }

    public var available: Bool {
        guard let servers else { return true }
        return ServerFeatures(apiLevel: servers.current?.server.apiLevel).watchlists && servers.currentUser != nil
    }

    public var contextID: String? {
        guard let servers else { return "fake:1" }
        guard let current = servers.current, let user = servers.currentUser else { return nil }
        return "\(current.id):\(user.id)"
    }

    // MARK: What the fake rows and library read

    /// The library's card for a saved title, if it has one.
    static func libraryItem(_ key: WatchlistKey) -> BrowseItem? {
        switch key.mediaType {
        case .movie: FakeData.movies.first { FakeData.tmdbID(movieID: $0.id) == key.tmdbID }
        case .tv: FakeData.shows.first { FakeData.tmdbID(showID: $0.id) == key.tmdbID }
        }
    }

    /// The ids of the watchlists that hold the title, oldest first.
    public func holding(_ key: WatchlistKey) -> [Int] {
        kept.filter { $0.saved.contains { $0.title.key == key } }.map(\.watchlist.id)
    }

    /// Every watchlist's id and name, in the order made.
    var ids: [Int] { kept.map(\.watchlist.id) }

    /// A watchlist as the lists' routes send it.
    func mediaList(_ id: Int) -> MediaList? {
        guard let k = kept.first(where: { $0.watchlist.id == id }) else { return nil }
        let w = counted(k)
        return MediaList(id: id, name: w.name, mediaType: "all", sourceKind: watchlistSourceKind, owner: .me, canEdit: true, screen: .home,
                         limit: 500, entries: w.titles, inLibrary: w.inLibrary, fetchedAt: k.saved.first?.addedAt ?? w.createdAt,
                         createdAt: w.createdAt, removeWatched: w.removeWatched)
    }

    /// A watchlist's page, sorted and filtered as the server does.
    func entries(_ id: Int, sort: ListSort?, mediaType: MediaType?) -> [MediaListEntry]? {
        guard let k = kept.first(where: { $0.watchlist.id == id }) else { return nil }
        var saved = k.saved.filter { mediaType == nil || $0.title.key.mediaType == mediaType }
        switch sort ?? .added {
        case .added: break
        case .title: saved.sort { $0.title.title.localizedCompare($1.title.title) == .orderedAscending }
        case .released: saved.sort { ($0.title.year ?? 0) > ($1.title.year ?? 0) }
        }
        return saved.enumerated().map { index, s in
            let item = Self.libraryItem(s.title.key).map { item -> BrowseItem in
                var copy = item
                copy.watchlisted = true
                return copy
            }
            return MediaListEntry(rank: index + 1, mediaType: s.title.key.mediaType, tmdbID: s.title.key.tmdbID, title: s.title.title, year: s.title.year,
                                  posterPath: s.title.posterPath, item: item, requestState: item == nil ? s.state ?? .requestable : nil,
                                  watchlisted: true, addedAt: s.addedAt)
        }
    }

    /// The row a watchlist draws on `screen`: what the library has of the
    /// screen's types, newest first, leaving out on Home what Continue
    /// watching shows (`started`, the library's ids); nil when it has nothing.
    func row(_ id: Int, screen: RowScreen, started: Set<Int>) -> ScreenRow? {
        guard let k = kept.first(where: { $0.watchlist.id == id }) else { return nil }
        let kind: ItemKind? = switch screen {
        case .home: nil
        case .library(let library): library == FakeData.tvLibrary ? .show : .movie
        }
        let fits = k.saved.filter { kind == nil || ($0.title.key.mediaType == .tv) == (kind == .show) }
        let items = fits.compactMap { Self.libraryItem($0.title.key) }.filter { screen != .home || !started.contains($0.id) }.map { item -> BrowseItem in
            var copy = item
            copy.watchlisted = true
            return copy
        }
        guard !items.isEmpty else { return nil }
        let inLibrary = fits.filter { Self.libraryItem($0.title.key) != nil }.count
        return ScreenRow(id: "list:\(id)", style: .poster, title: k.watchlist.name,
                         subtitle: inLibrary < fits.count ? listCountLine(inLibrary: inLibrary, total: fits.count) : nil,
                         items: Array(items.prefix(20)), seeAll: .list(id: id), listID: id)
    }

    // MARK: WatchlistsRepository

    public func watchlists(containing key: WatchlistKey?) async throws -> WatchlistSet {
        await FakeLatency.wait()
        guard available else { return WatchlistSet(items: []) }
        return WatchlistSet(items: kept.map { k in
            var w = counted(k)
            if let key { w.contains = k.saved.contains { $0.title.key == key } }
            return w
        })
    }

    public func create(name: String) async throws -> Watchlist {
        await FakeLatency.wait()
        try refuseIfAsked("create")
        try checkName(name, except: nil)
        guard kept.count < 25 else { throw APIError(status: 422, message: WatchlistWords.atLimit(25)) }
        let list = Watchlist(id: nextID, name: name, createdAt: Date())
        nextID += 1
        kept.append(Kept(watchlist: list, saved: []))
        changed()
        return list
    }

    public func update(id: Int, name: String?, removeWatched: Bool?) async throws -> Watchlist {
        await FakeLatency.wait()
        try refuseIfAsked("update:\(id)")
        let i = try index(id)
        if let name {
            try checkName(name, except: id)
            kept[i].watchlist.name = name
        }
        if let removeWatched { kept[i].watchlist.removeWatched = removeWatched }
        changed()
        return counted(kept[i])
    }

    public func delete(id: Int) async throws {
        await FakeLatency.wait()
        try refuseIfAsked("delete:\(id)")
        kept.remove(at: try index(id))
        changed()
    }

    /// Deletes a watchlist from its list page's Delete, which the lists' route does too.
    func deleteFromLists(_ id: Int) -> Bool {
        guard let i = kept.firstIndex(where: { $0.watchlist.id == id }) else { return false }
        kept.remove(at: i)
        changed()
        return true
    }

    public func save(_ title: WatchlistTitle, to id: Int) async throws -> WatchlistChange {
        await FakeLatency.wait()
        try refuseIfAsked("save:\(id)")
        let i = try index(id)
        insert(title, at: i)
        changed()
        return WatchlistChange(holding: holding(title.key), watchlist: counted(kept[i]))
    }

    public func remove(_ key: WatchlistKey, from id: Int) async throws -> WatchlistChange {
        await FakeLatency.wait()
        try refuseIfAsked("remove:\(id)")
        let i = try index(id)
        kept[i].saved.removeAll { $0.title.key == key }
        changed()
        return WatchlistChange(holding: holding(key), watchlist: counted(kept[i]))
    }

    public func saveToOnly(_ title: WatchlistTitle) async throws -> WatchlistChange {
        await FakeLatency.wait()
        try refuseIfAsked("saveToOnly")
        guard kept.count <= 1 else { throw APIError(status: 409, message: "You have more than one watchlist: choose which.") }
        var created = false
        if kept.isEmpty {
            kept.append(Kept(watchlist: Watchlist(id: nextID, name: WatchlistWords.firstName, createdAt: Date()), saved: []))
            nextID += 1
            created = true
        }
        insert(title, at: 0)
        changed()
        return WatchlistChange(holding: holding(title.key), watchlist: counted(kept[0]), created: created)
    }

    public func removeFromAll(_ key: WatchlistKey) async throws -> WatchlistChange {
        await FakeLatency.wait()
        try refuseIfAsked("removeFromAll")
        let removed = holding(key)
        for i in kept.indices { kept[i].saved.removeAll { $0.title.key == key } }
        changed()
        return WatchlistChange(holding: [], removed: removed)
    }

    /// With the fake rows, their changes: on the server both are rows.changed and lists.changed.
    public func changes() -> AsyncStream<Void> {
        if let rows { return rows.changes() }
        return AsyncStream(bufferingPolicy: .bufferingNewest(1)) { continuation in
            let id = UUID()
            listeners[id] = continuation
            continuation.onTermination = { [weak self] _ in Task { @MainActor in self?.listeners[id] = nil } }
        }
    }

    /// Tells listeners the watchlists changed, as lists.changed from another device does.
    public func changed() {
        if let rows { return rows.changed() }
        for listener in listeners.values { listener.yield() }
    }

    // MARK: Private

    private func counted(_ k: Kept) -> Watchlist {
        var w = k.watchlist
        w.titles = k.saved.count
        w.inLibrary = k.saved.filter { Self.libraryItem($0.title.key) != nil }.count
        w.posters = k.saved.prefix(4).compactMap(\.title.posterPath)
        return w
    }

    private func insert(_ title: WatchlistTitle, at i: Int) {
        guard !kept[i].saved.contains(where: { $0.title.key == title.key }) else { return }
        kept[i].saved.insert(Saved(title: title, addedAt: Date(), state: Self.libraryItem(title.key) == nil ? .requestable : nil), at: 0)
    }

    private func index(_ id: Int) throws -> Int {
        guard let i = kept.firstIndex(where: { $0.watchlist.id == id }) else { throw APIError(status: 404, message: "That watchlist was deleted.") }
        return i
    }

    private func checkName(_ name: String, except id: Int?) throws {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        if let other = kept.first(where: { $0.watchlist.id != id && $0.watchlist.name.localizedCaseInsensitiveCompare(trimmed) == .orderedSame }) {
            throw APIError(status: 422, message: WatchlistWords.nameTaken(other.watchlist.name))
        }
    }

    private func refuseIfAsked(_ call: String) throws {
        calls.append(call)
        guard failNext else { return }
        failNext = false
        throw APIError(status: nil, message: "The server can’t be reached.")
    }
}
