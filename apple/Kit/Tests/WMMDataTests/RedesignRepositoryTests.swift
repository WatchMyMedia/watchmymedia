import Foundation
import Testing
@testable import WMMData
import WMMModel

/// What API level 34 adds that the Apple TV app reads: people, Remove from
/// Continue watching, a card's counts, a library's totals and rating
/// filter, trailer kinds and a recording's facts.
@MainActor
@Suite(.timeLimit(.minutes(1)))
struct RedesignRepositoryTests {
    let h = Harness()
    var library: any LibraryRepository { h.services.library }

    /// Signs in on the test server at `apiLevel`.
    private func signIn(apiLevel: Int) async throws {
        var server = h.testServer
        server.apiLevel = apiLevel
        try await h.services.servers.select(server)
        h.router.on("POST /api/auth/login", .json(Fixtures.signIn))
        _ = try await h.services.auth.signIn(name: "viewer", password: "fixture-viewer-1")
    }

    @Test func aPersonsTitlesMapWithTheirParts() async throws {
        try await signIn(apiLevel: ServerFeatures.Level.redesign)
        h.router.on("GET /api/people/4417", .json(#"""
            {"personId": 4417, "name": "Mara Lindqvist", "profilePath": "/ml.jpg",
             "movies": [{"kind": "movie", "id": 21, "libraryId": 1, "title": "Ninety Fathoms", "year": 2024, "overview": "", "genres": [], "hdr": false,
                         "addedAt": "2026-10-29T17:01:24Z", "isNew": false, "totalSize": 5, "watched": true, "character": "Io Vance", "contentRating": "PG-13"}],
             "shows": [{"kind": "show", "id": 11, "libraryId": 2, "title": "Harbor Lights", "overview": "", "genres": [], "hdr": false,
                        "addedAt": "2026-10-29T17:01:24Z", "isNew": false, "totalSize": 5, "watched": false, "character": "Maren Holt / Herself", "unwatchedCount": 4}]}
            """#))
        let person = try await library.person(id: 4417)
        #expect(person.name == "Mara Lindqvist")
        #expect(person.profilePath == "/ml.jpg")
        #expect(person.movies.map(\.character) == ["Io Vance"])
        #expect(person.movies[0].item.watched)
        #expect(person.movies[0].item.contentRating == "PG-13")
        #expect(person.shows.map(\.character) == ["Maren Holt / Herself"])
        #expect(person.shows[0].item.unwatchedCount == 4)
    }

    @Test func nobodyInTheLibraryIsA404() async throws {
        try await signIn(apiLevel: ServerFeatures.Level.redesign)
        h.router.on("GET /api/people/1", .json(#"{"title":"Not Found","status":404,"detail":"No title in the library has this person."}"#, status: 404))
        await #expect(throws: APIError.self) { try await library.person(id: 1) }
    }

    @Test func creditsCarryTheirPersonAndTheCrewTheirJobs() async throws {
        try await signIn(apiLevel: ServerFeatures.Level.redesign)
        let movie = Fixtures.movie
            .replacingOccurrences(of: #""credits": [{ "name": "Ada Brook", "character": "June" }],"#,
                                  with: #""credits": [{ "name": "Ada Brook", "character": "June", "personId": 31 }, { "name": "Old Credit" }], "crew": [{ "personId": 77, "name": "Petra Lind", "job": "Director" }],"#)
            .replacingOccurrences(of: #""tmdbId": 7001,"#, with: #""tmdbId": 7001, "trailers": [{"source": "file", "name": "Teaser", "fileId": 77, "kind": "teaser"}, {"source": "youtube", "name": "Official Trailer", "key": "YE7VzlLtp-4", "kind": "trailer"}],"#)
        h.router.on("GET /api/movies/21", .json(movie))
        let mapped = try await library.movie(id: 21)
        #expect(mapped.cast.map(\.personID) == [31, nil], "a credit stored before person IDs were kept has none")
        #expect(mapped.crew == [Person(name: "Petra Lind", role: "Director", personID: 77)])
        #expect(mapped.trailers.map(\.teaser) == [true, false])
    }

    @Test func removingFromContinueWatchingUsesItsRoutesAndTellsListeners() async throws {
        try await signIn(apiLevel: ServerFeatures.Level.redesign)
        h.router.always("PUT /api/watch/movie/21/hidden", .noContent)
        h.router.always("DELETE /api/watch/episode/401/hidden", .noContent)
        let changes = Counter()
        let listening = Task { for await _ in library.libraryChanges() { changes.count += 1 } }
        defer { listening.cancel() }
        #expect(await eventually { h.services.events.listenerCount == 1 })
        try await library.setHiddenFromContinueWatching(.movie, id: 21, hidden: true)
        try await library.setHiddenFromContinueWatching(.episode, id: 401, hidden: false)
        #expect(h.router.requests("PUT /api/watch/movie/21/hidden").count == 1)
        #expect(h.router.requests("DELETE /api/watch/episode/401/hidden").count == 1)
        #expect(await eventually { changes.count >= 2 })
    }

    @Test func cardsTotalsAndRatingsMap() async throws {
        try await signIn(apiLevel: ServerFeatures.Level.redesign)
        h.router.always("GET /api/libraries/2/items", .json(#"""
            {"items": [{"kind": "show", "id": 11, "libraryId": 2, "title": "Harbor Lights", "overview": "", "genres": [], "hdr": false, "addedAt": "2026-10-29T17:01:24Z",
                        "isNew": true, "totalSize": 5, "watched": false, "contentRating": "TV-14", "unwatchedCount": 2, "newEpisodeCount": 1, "seasonCount": 3,
                        "nextAirDate": "2026-11-26", "lastAirDate": "2026-11-19"}],
             "total": 1, "offset": 0, "limit": 100,
             "facets": {"genres": [], "decades": [], "contentRatings": ["TV-PG", "TV-14", "unrated"]},
             "contentRatingCounts": {"TV-PG": 0, "TV-14": 1, "unrated": 0},
             "totals": {"items": 142, "episodes": 3418, "continuing": 12, "unmatched": 2, "totalSize": 5, "unwatched": 31, "inProgress": 4, "newEpisodes": 2, "addedThisMonth": 6}}
            """#))
        let page = try await library.items(ItemQuery(libraryID: 2, contentRatings: ["TV-14", unratedContentRating]))
        #expect(query(try h.router.last("GET /api/libraries/2/items"))["contentRating"] == "TV-14,unrated")
        let show = try #require(page.items.first)
        #expect(show.contentRating == "TV-14")
        #expect(show.unwatchedCount == 2)
        #expect(show.newEpisodeCount == 1)
        #expect(show.seasonCount == 3)
        #expect(show.nextAirDate == "2026-11-26")
        #expect(show.lastAirDate == "2026-11-19")
        #expect(show.addedAt != nil)
        #expect(page.contentRatings == ["TV-PG", "TV-14", "unrated"])
        #expect(page.contentRatingCounts == ["TV-PG": 0, "TV-14": 1, "unrated": 0])
        #expect(page.summary?.unwatched == 31)
        #expect(page.summary?.inProgress == 4)
        #expect(page.summary?.newEpisodes == 2)
        #expect(page.summary?.addedThisMonth == 6)
    }

    @Test func belowLevel34NoRatingIsSent() async throws {
        try await signIn(apiLevel: ServerFeatures.Level.watchlists)
        h.router.always("GET /api/libraries/2/items", .json(#"{"items":[],"total":0,"offset":0,"limit":100,"facets":{"genres":[],"decades":[]}}"#))
        let page = try await library.items(ItemQuery(libraryID: 2, contentRatings: ["TV-14"]))
        #expect(query(try h.router.last("GET /api/libraries/2/items"))["contentRating"] == nil, "an older server would answer with every title as if filtered")
        #expect(page.contentRatings.isEmpty)
        #expect(page.contentRatingCounts == nil)
    }
}
