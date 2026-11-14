import Foundation
import Testing
@testable import WMMData
import WMMModel

/// Arranged rows and lists (PLAN-ROWS.md §6, API level 22) from replies
/// shaped as api/openapi.json has them.
@MainActor
@Suite(.timeLimit(.minutes(1)))
struct RowsRepositoryTests {
    let h = Harness()
    var library: any LibraryRepository { h.services.library }
    var rows: any RowsRepository { h.services.rows }

    /// Signs in on a server at `apiLevel`.
    private func signIn(apiLevel: Int) async throws {
        var server = h.testServer
        server.apiLevel = apiLevel
        try await h.services.servers.select(server)
        h.router.on("POST /api/auth/login", .json(Fixtures.signIn))
        _ = try await h.services.auth.signIn(name: "viewer", password: "fixture-viewer-1")
    }

    /// Home at level 22: the fixed arrays empty, the rows in the person's order.
    private static let arrangedHome = """
        {
          "continueWatching": [], "recentlyAddedShows": [], "recentlyAired": [], "recentlyAddedMovies": [], "recentlyReleased": [],
          "totals": { "movies": 4, "shows": 1, "episodes": 6, "files": 10, "totalSize": 1 },
          "movieLibraryId": 1, "tvLibraryId": 2,
          "rows": [
            { "id": "list:7", "style": "poster", "title": "Top rated science fiction", "subtitle": "1 of 50 · From TMDB",
              "items": [\(Self.movie)], "seeAll": { "kind": "list", "listId": 7 },
              "source": { "kind": "tmdb", "name": "TMDB", "url": "https://www.themoviedb.org/list/7" }, "listId": 7 },
            { "id": "continue", "style": "continue", "title": "Continue watching",
              "continue": [{ "kind": "movie", "title": "Paper Moons", "fileId": 5, "movieId": 21, "positionMs": 1000, "durationMs": 4000, "updatedAt": "2026-10-27T20:15:00Z" }] },
            { "id": "live", "style": "live", "title": "Live TV" },
            { "id": "spotlight", "style": "carousel", "title": "A style from a later server", "items": [\(Self.movie)] },
            { "id": "recentlyAired", "style": "episode", "title": "Recently aired · Episodes", "episodes": [] },
            { "id": "recentlyReleased", "style": "wide", "title": "Recently released · Movies", "items": [\(Self.movie)],
              "seeAll": { "kind": "library", "libraryId": 1, "sort": "released", "filter": "4k" } },
            { "id": "recentlyAddedMovies", "style": "poster", "title": "Recently added · Movies", "items": [\(Self.movie)],
              "seeAll": { "kind": "collection", "libraryId": 1 } }
          ]
        }
        """

    private static let movie = """
        { "kind": "movie", "id": 21, "libraryId": 1, "title": "Paper Moons", "year": 2021, "overview": "", "genres": [], "hdr": false,
          "addedAt": "2026-10-28T10:00:00Z", "isNew": false, "totalSize": 2000, "watched": false }
        """

    @Test func fromLevel22HomeIsAskedForArrangedAndDrawsItsRowsInOrder() async throws {
        try await signIn(apiLevel: rowsAPILevel)
        h.router.on("GET /api/home", .json(Self.arrangedHome))
        let home = try await library.home()

        #expect(query(try h.router.last("GET /api/home"))["arranged"] == "true")
        let rows = try #require(home.rows)
        // The carousel (a style this app doesn't know) and the empty Recently aired are skipped.
        #expect(rows.map(\.id) == ["list:7", "continue", "live", "recentlyReleased", "recentlyAddedMovies"])
        #expect(rows.map(\.style) == [.poster, .continue, .live, .wide, .poster])

        let list = rows[0]
        #expect(list.isList)
        #expect(list.listID == 7)
        #expect(list.subtitle == "1 of 50 · From TMDB")
        #expect(list.items.map(\.title) == ["Paper Moons"])
        #expect(list.seeAll == .list(id: 7))
        #expect(list.source == RowSource(kind: "tmdb", name: "TMDB", url: URL(string: "https://www.themoviedb.org/list/7")))

        #expect(rows[1].continueItems.map(\.fileID) == [5])
        #expect(rows[3].seeAll == .library(id: 1, sort: .released, filter: .fourK, genre: nil, decade: nil))
        #expect(rows[4].seeAll == nil, "a link kind this app doesn't know has no See all")
        #expect(home.continueWatching.isEmpty)
        #expect(home.totals.movies == 4)
    }

    @Test func belowLevel22HomeKeepsItsFixedRows() async throws {
        try await signIn(apiLevel: rowsAPILevel - 1)
        h.router.on("GET /api/home", .json(Fixtures.home))
        let home = try await library.home()

        #expect(query(try h.router.last("GET /api/home"))["arranged"] == nil, "older servers are asked as they always were")
        #expect(home.rows == nil)
        #expect(home.continueWatching.count == 2)
        #expect(home.recentlyAddedShows.count == 1)
        #expect(!rows.available)
    }

    @Test func aLevel22ServerThatSendsFixedRowsKeepsThem() async throws {
        try await signIn(apiLevel: rowsAPILevel)
        h.router.on("GET /api/home", .json(Fixtures.home))
        let home = try await library.home()
        #expect(home.rows == nil)
        #expect(home.continueWatching.count == 2)
    }

    @Test func anArrangedHomeWithEveryRowHiddenIsStillArranged() async throws {
        try await signIn(apiLevel: rowsAPILevel)
        // rows is omitempty: with none to show the field is left out.
        h.router.on("GET /api/home", .json(#"{"continueWatching":[],"recentlyAddedShows":[],"recentlyAired":[],"recentlyAddedMovies":[],"recentlyReleased":[],"totals":{"movies":4,"shows":1,"episodes":6,"files":10,"totalSize":1}}"#))
        let home = try await library.home()
        #expect(home.rows == [])
        #expect(home.rows != nil, "arranged, so Edit Home is still offered")
    }

    @Test func libraryRowsAreAskedForOnlyFromLevel22() async throws {
        try await signIn(apiLevel: rowsAPILevel - 1)
        #expect(try await rows.libraryRows(libraryID: 1).isEmpty)
        #expect(h.router.requests("GET /api/libraries/1/rows").isEmpty)
    }

    @Test func libraryRowsComeArranged() async throws {
        try await signIn(apiLevel: rowsAPILevel)
        h.router.on("GET /api/libraries/1/rows", .json("""
            { "recentlyAdded": [], "recentlyAired": [], "recentlyReleased": [],
              "rows": [
                { "id": "recentlyAdded", "style": "poster", "title": "Recently added", "items": [\(Self.movie)] },
                { "id": "list:9", "style": "poster", "title": "Best of 2026", "subtitle": "1 of 20 · From MDBList", "items": [\(Self.movie)], "listId": 9 }
              ] }
            """))
        let loaded = try await rows.libraryRows(libraryID: 1)
        #expect(loaded.map(\.id) == ["recentlyAdded", "list:9"])
        #expect(query(try h.router.last("GET /api/libraries/1/rows"))["arranged"] == "true")
    }

    @Test func theLayoutListsEveryRowWithWhatIsHiddenAndWhy() async throws {
        try await signIn(apiLevel: rowsAPILevel)
        h.router.on("GET /api/me/rows/home", .json("""
            { "screen": "home", "customized": true, "rows": [
              { "id": "continue", "title": "Continue watching", "style": "continue", "kind": "builtin", "hidden": false, "shown": true },
              { "id": "list:3", "title": "Trending this week", "style": "poster", "kind": "list", "hidden": false, "shown": false,
                "note": "Nothing from this list is in your library yet", "listId": 3, "owner": "everyone" },
              { "id": "spotlight", "title": "Spotlight", "style": "carousel", "kind": "featured", "hidden": true, "shown": false }
            ] }
            """))
        let layout = try await rows.layout(.home)
        #expect(layout.screen == .home)
        #expect(layout.customized)
        #expect(layout.rows.map(\.id) == ["continue", "list:3", "spotlight"])
        #expect(layout.rows[0].kind == .builtin)
        #expect(layout.rows[1].kind == .list)
        #expect(layout.rows[1].owner == .everyone)
        #expect(layout.rows[1].note == "Nothing from this list is in your library yet")
        #expect(layout.rows[1].listID == 3)
        #expect(layout.rows[2].style == nil, "a style this app doesn't draw is still listed, to move or hide")
        #expect(layout.rows[2].kind == .other("featured"))
        #expect(layout.rows[2].hidden)
    }

    @Test func savingSendsTheOrderWithOnlyTheHiddenMarked() async throws {
        try await signIn(apiLevel: rowsAPILevel)
        h.router.on("PUT /api/me/rows/library-1", .json(#"{"screen":"library-1","customized":true,"rows":[]}"#))
        let changes = Counter()
        let listening = Task { for await _ in rows.changes() { changes.count += 1 } }
        defer { listening.cancel() }
        #expect(await eventually { h.services.events.listenerCount == 1 })

        let saved = try await rows.saveLayout([RowPlacement(id: "list:9"), RowPlacement(id: "recentlyAdded", hidden: true)], for: .library(1))
        #expect(saved.customized)
        let body = try h.body(try h.router.last("PUT /api/me/rows/library-1"))
        let sent = try #require(body["rows"] as? [[String: Any]])
        #expect(sent.map { $0["id"] as? String } == ["list:9", "recentlyAdded"])
        #expect(sent[0]["hidden"] == nil)
        #expect(sent[1]["hidden"] as? Bool == true)
        #expect(await eventually { changes.count == 1 }, "screens here refetch at once")
    }

    @Test func resettingAsksForTheHouseholdsOrder() async throws {
        try await signIn(apiLevel: rowsAPILevel)
        h.router.on("DELETE /api/me/rows/home", .json(#"{"screen":"home","customized":false,"rows":[{"id":"continue","title":"Continue watching","style":"continue","kind":"builtin","hidden":false,"shown":true}]}"#))
        let layout = try await rows.resetLayout(.home)
        #expect(!layout.customized)
        #expect(layout.rows.map(\.id) == ["continue"])
    }

    @Test func aListAndItsEntries() async throws {
        try await signIn(apiLevel: rowsAPILevel)
        h.router.on("GET /api/lists/9", .json("""
            { "id": 9, "name": "Best of 2026", "mediaType": "movie", "source": { "kind": "mdblist", "ref": "u/best-2026" }, "owner": "everyone",
              "sourceName": "MDBList", "sourceUrl": "https://mdblist.com/lists/u/best-2026",
              "canEdit": false, "screen": "library-1", "limit": 50, "entries": 20, "inLibrary": 5, "fetching": false,
              "fetchedAt": "2026-11-04T10:00:00Z", "nextFetchAt": "2026-11-08T10:00:00Z", "error": "MDBList refused the key",
              "createdAt": "2026-11-01T10:00:00Z", "updatedAt": "2026-11-04T10:00:00Z" }
            """))
        h.router.on("GET /api/lists/9/entries", .json("""
            { "total": 4, "inLibrary": 1, "limit": 250, "offset": 0, "items": [
              { "rank": 1, "mediaType": "movie", "tmdbId": 21, "title": "Paper Moons", "year": 2021, "found": true, "item": \(Self.movie) },
              { "rank": 2, "mediaType": "movie", "tmdbId": 72010, "title": "Perihelion Drift", "year": 2025, "posterPath": "/pd.jpg", "found": true, "requestState": "requestable" },
              { "rank": 3, "mediaType": "tv", "tmdbId": 71010, "title": "The Tidewater Array", "found": true, "requestState": "queued-elsewhere" },
              { "rank": 4, "mediaType": "movie", "title": "The Kiln Brothers", "year": 1996, "found": false }
            ] }
            """))
        let list = try await rows.list(id: 9)
        #expect(list.name == "Best of 2026")
        #expect(list.sourceKind == "mdblist")
        #expect(list.sourceLine == "From MDBList")
        #expect(list.url == URL(string: "https://mdblist.com/lists/u/best-2026"))
        #expect(list.owner == .everyone)
        #expect(!list.canEdit)
        #expect(list.screen == .library(1))
        #expect(list.error == "MDBList refused the key")
        #expect(list.refreshable)

        let entries = try await rows.entries(listID: 9, offset: 0, limit: 250)
        #expect(query(try h.router.last("GET /api/lists/9/entries")) == ["limit": "250", "offset": "0"])
        #expect(entries.total == 4)
        #expect(entries.items.map(\.rank) == [1, 2, 3, 4])
        #expect(entries.items[0].item?.id == 21)
        #expect(entries.items[1].requestable?.posterPath == "/pd.jpg")
        #expect(entries.items[2].mediaType == .tv)
        #expect(entries.items[2].requestState == nil, "a state this app doesn't know offers nothing")
        #expect(!entries.items[3].found)
        #expect(entries.items[3].tmdbID == nil)
    }

    @Test func refreshingAndDeletingAList() async throws {
        try await signIn(apiLevel: rowsAPILevel)
        h.router.on("POST /api/lists/9/refresh", .json("""
            { "id": 9, "name": "Best of 2026", "mediaType": "movie", "source": { "kind": "tmdb" }, "owner": "me", "canEdit": true,
              "screen": "home", "limit": 50, "entries": 20, "inLibrary": 5, "fetching": true,
              "createdAt": "2026-11-01T10:00:00Z", "updatedAt": "2026-11-04T10:00:00Z" }
            """))
        h.router.on("DELETE /api/lists/9", .noContent)
        #expect(try await rows.refresh(listID: 9).fetching)
        try await rows.delete(listID: 9)
        #expect(h.router.requests("DELETE /api/lists/9").count == 1)
    }

    @Test func rowsAndListsEventsTellTheRowsListeners() async throws {
        try await signIn(apiLevel: rowsAPILevel)
        h.router.on("GET /api/events", Reply(status: 200, body: Data((
            "event: ping\ndata: {}\n\n" +
            "event: rows.changed\ndata: {\"screen\":\"home\"}\n\n" +
            "event: lists.changed\ndata: {\"listId\":9}\n\n").utf8)))
        let rowChanges = Counter(), libraryChanges = Counter()
        let a = Task { for await _ in rows.changes() { rowChanges.count += 1 } }
        let b = Task { for await _ in library.changes() { libraryChanges.count += 1 } }
        defer {
            a.cancel()
            b.cancel()
        }
        #expect(await eventually { h.router.requests("GET /api/events").count == 2 }, "the reconnect, answered 404")
        #expect(await eventually { rowChanges.count >= 1 })
        #expect(libraryChanges.count == 0, "the library's listeners don't refetch for a layout")
    }
}
