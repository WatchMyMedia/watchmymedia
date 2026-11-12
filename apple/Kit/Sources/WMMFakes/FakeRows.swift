import Foundation
import WMMModel

/// Arranged rows and lists on the fakes (PLAN-ROWS.md; every title and name
/// is fictional): Home with two list rows and one list waiting in Add a row,
/// the Movies screen with a list whose last fetch failed, and list pages
/// with titles the library has, missing ones to request, and an imported
/// title TMDB couldn't find. Only at ``rowsAPILevel`` (`-fakesRows`); below
/// it Home keeps its fixed rows.
@MainActor
public final class FakeRowsRepository: RowsRepository {
    /// Layouts saved here, by screen, in the order saved.
    public private(set) var saved: [RowScreen: [RowPlacement]] = [:]
    /// Lists deleted here.
    public private(set) var deleted: Set<Int> = []

    private let servers: FakeServerRepository?
    private let admin: Bool
    private let now: Date
    private var listeners: [UUID: AsyncStream<Void>.Continuation] = [:]

    public init(servers: FakeServerRepository? = nil, admin: Bool = true, now: Date = .now) {
        self.servers = servers
        self.admin = admin
        self.now = now
    }

    public var available: Bool {
        guard let servers else { return true }
        return (servers.current?.server.apiLevel ?? 0) >= rowsAPILevel && servers.currentUser != nil
    }

    // MARK: The lists

    struct Entry {
        var title: String
        var year: Int
        var type: MediaType
        /// The library's movie or show.
        var item: BrowseItem?
        var tmdbID: Int?
        var state: Requestable.State? = .requestable
        var found = true
    }

    private static func movie(_ id: Int) -> Entry {
        let m = FakeData.movies.first { $0.id == id }!
        return Entry(title: m.title, year: m.year ?? 0, type: .movie, item: m, tmdbID: 80_000 + id, state: .available)
    }

    private static func show(_ id: Int) -> Entry {
        let s = FakeData.shows.first { $0.id == id }!
        return Entry(title: s.title, year: s.year ?? 0, type: .tv, item: s, tmdbID: FakeData.tmdbID(showID: id), state: .available)
    }

    private static func missing(_ title: String, _ year: Int, _ type: MediaType, _ tmdbID: Int, _ state: Requestable.State = .requestable) -> Entry {
        Entry(title: title, year: year, type: type, tmdbID: tmdbID, state: state)
    }

    public static let sciFi = 9001
    public static let rainySunday = 9002
    public static let trending = 9003
    public static let best2026 = 9004

    /// Each list's entries, in rank order.
    static let entries: [Int: [Entry]] = [
        sciFi: [
            show(101), missing("Perihelion Drift", 2025, .movie, 72_010), show(104), missing("The Tidewater Array", 2023, .tv, 71_010),
            movie(504), missing("Cinder Moons", 2022, .movie, 72_011, .requested), show(115), missing("A Map of Static", 2020, .movie, 72_012, .downloading),
            missing("Glasswing", 2019, .movie, 72_013), missing("The Ninth Relay", 2018, .tv, 71_011), missing("Low Orbit Choir", 2017, .movie, 72_014),
            missing("Red Shift County", 2016, .tv, 71_012),
        ],
        rainySunday: [
            movie(506), movie(505), missing("The Harbour Keeper", 1987, .movie, 72_002, .requested), movie(503),
            missing("Harbor Ghosts", 2024, .movie, 72_001), Entry(title: "The Kiln Brothers", year: 1996, type: .movie, tmdbID: nil, state: nil, found: false),
            missing("Low Water Mark", 2021, .movie, 72_004),
        ],
        trending: [
            movie(500), show(103), missing("Perihelion Drift", 2025, .movie, 72_010), movie(502), missing("Glasswing", 2019, .movie, 72_013), show(100),
        ],
        best2026: [
            movie(500), movie(504), missing("Perihelion Drift", 2025, .movie, 72_010), movie(502), movie(501), missing("Cinder Moons", 2022, .movie, 72_011, .requested),
            movie(507), movie(509),
        ],
    ]

    /// What TMDB knows of the lists' missing titles, for the request sheet.
    static let catalog: [Requestable] = [
        Requestable(mediaType: .movie, tmdbID: 72_010, title: "Perihelion Drift", year: 2025, overview: "A survey ship’s orbit decays a little more each time its crew forgets something.", state: .requestable),
        Requestable(mediaType: .tv, tmdbID: 71_010, title: "The Tidewater Array", year: 2023, overview: "Radio astronomers on a tidal island hear the same signal at every low tide.", state: .requestable, seasons: [
            RequestableSeason(number: 1, name: "Season 1", episodeCount: 8, airDate: "2023-04-02", state: .missing),
            RequestableSeason(number: 2, name: "Season 2", episodeCount: 8, airDate: "2024-04-07", state: .missing),
        ]),
        Requestable(mediaType: .movie, tmdbID: 72_013, title: "Glasswing", year: 2019, overview: "A glassblower builds a wing that only flies at night.", state: .requestable),
        Requestable(mediaType: .tv, tmdbID: 71_011, title: "The Ninth Relay", year: 2018, overview: "Nine relay stations, eight keepers.", state: .requestable, seasons: [
            RequestableSeason(number: 1, name: "Season 1", episodeCount: 10, airDate: "2018-01-12", state: .missing),
        ]),
        Requestable(mediaType: .movie, tmdbID: 72_014, title: "Low Orbit Choir", year: 2017, overview: "A station’s choir rehearses for a concert nobody will hear.", state: .requestable),
        Requestable(mediaType: .tv, tmdbID: 71_012, title: "Red Shift County", year: 2016, overview: "A sheriff in a county that is slowly moving away.", state: .requestable, seasons: [
            RequestableSeason(number: 1, name: "Season 1", episodeCount: 6, airDate: "2016-10-01", state: .missing),
            RequestableSeason(number: 2, name: "Season 2", episodeCount: 6, airDate: "2017-10-01", state: .missing),
        ]),
    ]

    private func list(_ id: Int) -> MediaList? {
        guard !deleted.contains(id), let entries = Self.entries[id] else { return nil }
        let inLibrary = entries.filter { $0.item != nil }.count
        let hour: TimeInterval = 3600
        switch id {
        case Self.sciFi:
            return MediaList(id: id, name: "Top rated science fiction", mediaType: "all", sourceKind: "tmdb", owner: .everyone, canEdit: admin, screen: .home,
                             limit: 50, entries: entries.count, inLibrary: inLibrary, fetchedAt: now - 3 * hour, nextFetchAt: now + 21 * hour,
                             url: URL(string: "https://www.themoviedb.org/list/9001"))
        case Self.rainySunday:
            return MediaList(id: id, name: "Rainy Sunday films", mediaType: "movie", sourceKind: "import", owner: .me, canEdit: true, screen: .home,
                             limit: 50, entries: entries.count, inLibrary: inLibrary, notFound: 1, fetchedAt: now - 9 * 86_400, createdAt: now - 9 * 86_400)
        case Self.trending:
            return MediaList(id: id, name: "Trending this week", mediaType: "all", sourceKind: "tmdb", owner: .everyone, canEdit: admin, screen: .home,
                             limit: 20, entries: entries.count, inLibrary: inLibrary, fetchedAt: now - 5 * hour, nextFetchAt: now + 19 * hour)
        case Self.best2026:
            return MediaList(id: id, name: "Best of 2026", mediaType: "movie", sourceKind: "mdblist", owner: .everyone, canEdit: admin, screen: .library(FakeData.movieLibrary),
                             limit: 50, entries: entries.count, inLibrary: inLibrary, fetchedAt: now - 3 * 86_400, nextFetchAt: now + hour,
                             error: "MDBList refused the key", errorAt: now - 2 * hour)
        default:
            return nil
        }
    }

    // MARK: The screens

    /// A screen's rows in their default order, and whether each starts hidden.
    private func defaults(_ screen: RowScreen) -> [(entry: RowLayoutEntry, hidden: Bool)] {
        func builtin(_ id: String, _ title: String, _ style: RowStyle) -> (RowLayoutEntry, Bool) {
            (RowLayoutEntry(id: id, title: title, style: style, kind: .builtin, hidden: false, shown: true), false)
        }
        func listRow(_ id: Int, hidden: Bool = false) -> (RowLayoutEntry, Bool)? {
            guard let list = list(id) else { return nil }
            let entry = RowLayoutEntry(id: "list:\(id)", title: list.name, style: .poster, kind: .list, hidden: hidden, shown: !hidden, listID: id, owner: list.owner)
            return (entry, hidden)
        }
        switch screen {
        case .home:
            return [
                builtin("continue", "Continue watching", .continue), builtin("live", "Live TV", .live),
                builtin("recentlyAddedShows", "Recently added · TV Shows", .poster), listRow(Self.sciFi),
                builtin("recentlyAired", "Recently aired · Episodes", .episode), builtin("recentlyAddedMovies", "Recently added · Movies", .poster),
                listRow(Self.rainySunday), builtin("recentlyReleased", "Recently released · Movies", .wide), listRow(Self.trending, hidden: true),
            ].compactMap { $0 }
        case .library(FakeData.movieLibrary):
            return [
                builtin("recentlyAdded", "Recently added", .poster), listRow(Self.best2026), builtin("recentlyReleased", "Recently released", .wide),
                listRow(Self.sciFi, hidden: true), listRow(Self.rainySunday, hidden: true),
            ].compactMap { $0 }
        case .library(FakeData.tvLibrary):
            return [builtin("recentlyAdded", "Recently added", .poster), builtin("recentlyAired", "Recently aired", .episode), listRow(Self.sciFi, hidden: true)].compactMap { $0 }
        case .library:
            return []
        }
    }

    /// The saved order first, then rows never placed in their default place (PLAN-ROWS.md Decision 5).
    private func merged(_ screen: RowScreen) -> [RowLayoutEntry] {
        let all = defaults(screen)
        guard let saved = saved[screen] else { return all.map(\.entry) }
        var result: [RowLayoutEntry] = saved.compactMap { placement in
            guard var entry = all.first(where: { $0.entry.id == placement.id })?.entry else { return nil }
            entry.hidden = placement.hidden
            entry.shown = !placement.hidden
            return entry
        }
        for (index, row) in all.enumerated() where !result.contains(where: { $0.id == row.entry.id }) {
            let before = all[..<index].last { prior in result.contains { $0.id == prior.entry.id } }
            let at = before.flatMap { prior in result.firstIndex { $0.id == prior.entry.id }.map { $0 + 1 } } ?? 0
            result.insert(row.entry, at: at)
        }
        return result
    }

    /// The rows the screen draws, filled from `home` (for Home's built-in rows) and the library.
    func screenRows(_ screen: RowScreen, home: HomeData) -> [ScreenRow] {
        merged(screen).filter { !$0.hidden }.compactMap { entry in
            let row: ScreenRow? = switch (screen, entry.id) {
            case (.home, "continue"): ScreenRow(id: entry.id, style: .continue, title: entry.title, continueItems: home.continueWatching)
            case (.home, "live"): ScreenRow(id: entry.id, style: .live, title: entry.title)
            case (.home, "recentlyAddedShows"):
                ScreenRow(id: entry.id, style: .poster, title: entry.title, items: home.recentlyAddedShows,
                          seeAll: .library(id: FakeData.tvLibrary, sort: .added, filter: nil, genre: nil, decade: nil))
            case (_, "recentlyAired"): ScreenRow(id: entry.id, style: .episode, title: entry.title, episodes: home.recentlyAired)
            case (.home, "recentlyAddedMovies"):
                ScreenRow(id: entry.id, style: .poster, title: entry.title, items: home.recentlyAddedMovies,
                          seeAll: .library(id: FakeData.movieLibrary, sort: .added, filter: nil, genre: nil, decade: nil))
            case (_, "recentlyReleased"):
                ScreenRow(id: entry.id, style: .wide, title: entry.title, items: home.recentlyReleased,
                          seeAll: .library(id: FakeData.movieLibrary, sort: .released, filter: nil, genre: nil, decade: nil))
            case (.library(let id), "recentlyAdded"):
                ScreenRow(id: entry.id, style: .poster, title: entry.title, items: id == FakeData.tvLibrary ? home.recentlyAddedShows : home.recentlyAddedMovies,
                          seeAll: .library(id: id, sort: .added, filter: nil, genre: nil, decade: nil))
            default: entry.listID.flatMap { listRow($0, screen: screen) }
            }
            return row.flatMap { $0.isEmpty ? nil : $0 }
        }
    }

    private func listRow(_ id: Int, screen: RowScreen) -> ScreenRow? {
        guard let list = list(id), let entries = Self.entries[id] else { return nil }
        var items = entries.compactMap(\.item)
        if case .library(let libraryID) = screen { items = items.filter { $0.libraryID == libraryID } }
        let source = RowSource(kind: list.sourceKind, name: list.sourceKind == "mdblist" ? "MDBList" : list.sourceKind == "import" ? "an import" : "TMDB")
        return ScreenRow(
            id: "list:\(id)", style: .poster, title: list.name, subtitle: "\(items.count) of \(entries.count) · From \(source.name)",
            items: items, seeAll: .list(id: id), source: source, listID: id
        )
    }

    // MARK: RowsRepository

    public func libraryRows(libraryID: Int) async throws -> [ScreenRow] {
        await FakeLatency.wait()
        guard available else { return [] }
        return screenRows(.library(libraryID), home: FakeData.home(now: now))
    }

    public func layout(_ screen: RowScreen) async throws -> RowLayout {
        await FakeLatency.wait()
        return RowLayout(screen: screen, rows: merged(screen), customized: saved[screen] != nil)
    }

    public func saveLayout(_ rows: [RowPlacement], for screen: RowScreen) async throws -> RowLayout {
        await FakeLatency.wait()
        let known = Set(defaults(screen).map(\.entry.id))
        if let unknown = rows.first(where: { !known.contains($0.id) }) {
            throw APIError(status: 422, message: "This screen has no row \(unknown.id).")
        }
        saved[screen] = rows
        changed()
        return RowLayout(screen: screen, rows: merged(screen), customized: true)
    }

    public func resetLayout(_ screen: RowScreen) async throws -> RowLayout {
        await FakeLatency.wait()
        saved[screen] = nil
        changed()
        return RowLayout(screen: screen, rows: merged(screen), customized: false)
    }

    public func lists() async throws -> [MediaList] {
        await FakeLatency.wait()
        guard available else { return [] }
        let all = [Self.rainySunday, Self.sciFi, Self.trending, Self.best2026].compactMap(list)
        return all.filter { $0.owner == .me } + all.filter { $0.owner != .me }
    }

    public func list(id: Int) async throws -> MediaList {
        await FakeLatency.wait()
        guard let list = list(id) else { throw APIError(status: 404, message: "That list was deleted.") }
        return list
    }

    public func entries(listID: Int, offset: Int, limit: Int) async throws -> MediaListEntries {
        await FakeLatency.wait()
        guard list(listID) != nil, let entries = Self.entries[listID] else { throw APIError(status: 404, message: "That list was deleted.") }
        let page = entries.enumerated().dropFirst(offset).prefix(limit).map { index, e in
            MediaListEntry(
                rank: index + 1, mediaType: e.type, tmdbID: e.tmdbID, title: e.title, year: e.year, found: e.found, item: e.item,
                requestState: e.item == nil ? e.state : nil
            )
        }
        return MediaListEntries(items: page, total: entries.count, inLibrary: entries.filter { $0.item != nil }.count, offset: offset)
    }

    public func refresh(listID: Int) async throws -> MediaList {
        await FakeLatency.wait()
        guard var list = list(listID) else { throw APIError(status: 404, message: "That list was deleted.") }
        guard list.refreshable else { throw APIError(status: 409, message: "Imported lists aren’t fetched.") }
        list.fetching = true
        changed()
        return list
    }

    public func delete(listID: Int) async throws {
        await FakeLatency.wait()
        guard list(listID)?.canEdit == true else { throw APIError(status: 403, message: "Only an admin can delete the household’s lists.") }
        deleted.insert(listID)
        changed()
    }

    public func changes() -> AsyncStream<Void> {
        AsyncStream(bufferingPolicy: .bufferingNewest(1)) { continuation in
            let id = UUID()
            listeners[id] = continuation
            continuation.onTermination = { [weak self] _ in Task { @MainActor in self?.listeners[id] = nil } }
        }
    }

    func changed() {
        for listener in listeners.values { listener.yield() }
    }
}
