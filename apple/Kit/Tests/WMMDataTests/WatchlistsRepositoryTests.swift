import Foundation
import Testing
@testable import WMMData
import WMMModel

/// Watchlists (API level 33), from replies shaped as api/openapi.json has them.
@Suite(.timeLimit(.minutes(1))) @MainActor struct WatchlistsRepositoryTests {
    let h = Harness()
    var watchlists: any WatchlistsRepository { h.services.watchlists }

    private func ready(level: Int = watchlistsAPILevel) async throws {
        _ = try await h.signIn()
        var server = h.testServer
        server.apiLevel = level
        try await h.services.servers.select(server)
    }

    static let halloween = """
        { "id": 9102, "name": "Halloween", "titles": 6, "inLibrary": 6, "removeWatched": false, "posters": ["/bo.jpg", ""],
          "contains": true, "createdAt": "2026-10-01T09:00:00Z", "updatedAt": "2026-11-20T09:00:00Z" }
        """

    @Test func offeredFromLevel33AndAskedForATitle() async throws {
        try await ready(level: watchlistsAPILevel - 1)
        #expect(!watchlists.available)
        #expect(try await watchlists.watchlists(containing: nil).items.isEmpty)
        #expect(h.router.requests("GET /api/me/watchlists").isEmpty, "below 33 nothing is asked")
        try await ready()
        h.router.on("GET /api/me/watchlists", .json(#"{ "items": [\#(Self.halloween)], "max": 25, "maxTitles": 500 }"#))
        let set = try await watchlists.watchlists(containing: WatchlistKey(mediaType: .tv, tmdbID: 70_100))
        #expect(set.items.map(\.name) == ["Halloween"])
        #expect(set.items[0].contains == true && !set.items[0].removeWatched)
        #expect(set.items[0].posters == ["/bo.jpg"], "a blank poster path is dropped")
        let asked = try #require(h.router.requests("GET /api/me/watchlists").last?.url)
        #expect(asked.query?.contains("mediaType=tv") == true && asked.query?.contains("tmdbId=70100") == true)
    }

    @Test func theOnePressSaysWhichHoldTheTitleAndA409MeansSeveral() async throws {
        try await ready()
        h.router.on("PUT /api/me/watchlist/titles/movie/80502", .json(#"{ "watchlists": [9101], "created": true, "watchlist": \#(Self.halloween) }"#))
        let title = WatchlistTitle(key: WatchlistKey(mediaType: .movie, tmdbID: 80_502), title: "Ninety Fathoms", year: 2026)
        let change = try await watchlists.saveToOnly(title)
        #expect(change.holding == [9101] && change.created)
        h.router.on("PUT /api/me/watchlist/titles/movie/80502", .problem(409, "You have more than one watchlist"))
        let refusal = await #expect(throws: APIError.self) { try await watchlists.saveToOnly(title) }
        #expect(refusal?.status == 409)
    }

    /// A watchlist through the lists' own routes, as a level-33 server sends it.
    @Test func aWatchlistReadsAsAListOfItsOwnKind() async throws {
        try await ready()
        let rows = h.services.rows
        h.router.on("GET /api/lists/9101", .json("""
            { "id": 9101, "name": "Watchlist", "mediaType": "all", "source": { "kind": "watchlist" }, "owner": "me", "canEdit": true,
              "screen": "home", "limit": 500, "entries": 1, "inLibrary": 0, "fetching": false, "fetchedAt": "2026-11-22T06:08:21.087Z",
              "createdAt": "2026-11-22T06:08:21.087Z", "updatedAt": "2026-11-22T06:08:21.087Z", "removeWatched": true }
            """))
        h.router.on("GET /api/lists/9101/entries", .json("""
            { "items": [ { "rank": 1, "mediaType": "movie", "tmdbId": 91001, "title": "Amberline", "year": 2026, "found": true,
                           "watchlisted": true, "addedAt": "2026-11-22T06:08:21.087Z" } ],
              "total": 1, "inLibrary": 0, "limit": 100, "offset": 0 }
            """))
        let list = try await rows.list(id: 9101)
        #expect(list.isWatchlist && list.removeWatched && !list.refreshable)
        let entries = try await rows.entries(listID: 9101, offset: 0, limit: 250, sort: .title, mediaType: .movie)
        #expect(entries.items.first?.watchlisted == true && entries.items.first?.addedAt != nil)
        let asked = try #require(h.router.requests("GET /api/lists/9101/entries").last?.url?.query)
        #expect(asked.contains("sort=title") && asked.contains("mediaType=movie"))
    }
}
