import Foundation
import Testing
@testable import WMMData
import WMMModel

/// Related titles (PLAN-RELATED.md §6, API level 30) from replies shaped as
/// api/openapi.json has them.
@MainActor
@Suite(.timeLimit(.minutes(1)))
struct RelatedRepositoryTests {
    let h = Harness()
    var library: any LibraryRepository { h.services.library }

    /// Signs in on a server at `apiLevel`.
    private func signIn(apiLevel: Int) async throws {
        var server = h.testServer
        server.apiLevel = apiLevel
        try await h.services.servers.select(server)
        h.router.on("POST /api/auth/login", .json(Fixtures.signIn))
        _ = try await h.services.auth.signIn(name: "viewer", password: "fixture-viewer-1")
    }

    private static func movie(_ id: Int, _ title: String, watched: Bool = false, isNew: Bool = false) -> String {
        """
        { "kind": "movie", "id": \(id), "libraryId": 1, "title": "\(title)", "year": 2021, "overview": "", "genres": [], "hdr": false,
          "addedAt": "2026-10-28T10:00:00Z", "isNew": \(isNew), "totalSize": 2000, "watched": \(watched) }
        """
    }

    private static let movieRelated = """
        { "rows": [
            { "id": "collection", "style": "poster", "title": "Paper Moons Collection",
              "items": [\(movie(20, "Paper Moons", watched: true)), \(movie(22, "Paper Moons Rising"))] },
            { "id": "similar", "style": "poster", "title": "More like this",
              "items": [\(movie(31, "Glass Harbour", isNew: true)), \(movie(32, "Low Orbit")), \(movie(33, "Salt Road"))] },
            { "id": "spotlight", "style": "carousel", "title": "A style from a later server", "items": [\(movie(34, "Night Ferry"))] }
        ] }
        """

    @Test func fromLevel30AMoviesRowsAreAskedForAndReadInOrder() async throws {
        try await signIn(apiLevel: relatedAPILevel)
        h.router.on("GET /api/movies/21/related", .json(Self.movieRelated))
        #expect(library.offersRelated)

        let rows = try await library.relatedRows(.movie(id: 21))

        #expect(h.router.requests("GET /api/movies/21/related").count == 1)
        // The carousel, a style this app doesn't know, is left out.
        #expect(rows.map(\.id) == ["collection", "similar"])
        #expect(rows.map(\.style) == [.poster, .poster])
        #expect(rows[0].title == "Paper Moons Collection")
        #expect(rows[0].items.map(\.title) == ["Paper Moons", "Paper Moons Rising"])
        #expect(rows[0].items[0].watched)
        #expect(rows[0].items.allSatisfy { $0.kind == .movie })
        #expect(rows[1].title == "More like this")
        #expect(rows[1].items.map(\.id) == [31, 32, 33])
        #expect(rows[1].items[0].isNew)
        #expect(rows.allSatisfy { $0.seeAll == nil })
    }

    @Test func aShowsRowsComeFromItsOwnRoute() async throws {
        try await signIn(apiLevel: relatedAPILevel)
        h.router.on("GET /api/shows/7/related", .json("""
            { "rows": [{ "id": "similar", "style": "poster", "title": "More like this", "items": [
                { "kind": "show", "id": 8, "libraryId": 2, "title": "Tidewater County", "subtitle": "2021 · 3 seasons", "overview": "", "genres": [],
                  "hdr": false, "addedAt": "2026-10-28T10:00:00Z", "isNew": false, "totalSize": 1, "watched": false, "progress": 0.5 }
            ] }] }
            """))

        let rows = try await library.relatedRows(.show(id: 7))

        #expect(h.router.requests("GET /api/shows/7/related").count == 1)
        #expect(rows.map(\.id) == ["similar"])
        let show = try #require(rows.first?.items.first)
        #expect(show.kind == .show)
        #expect(show.title == "Tidewater County")
        #expect(show.subtitle == "2021 · 3 seasons")
        #expect(show.progress == 0.5)
    }

    @Test func nothingQualifyingIsNoRows() async throws {
        try await signIn(apiLevel: relatedAPILevel)
        h.router.on("GET /api/movies/21/related", .json(#"{ "rows": [{ "id": "similar", "style": "poster", "title": "More like this", "items": [] }] }"#))

        #expect(try await library.relatedRows(.movie(id: 21)).isEmpty, "a row with nothing in it is left out")

        h.router.on("GET /api/movies/21/related", .json(#"{ "rows": [] }"#))
        #expect(try await library.relatedRows(.movie(id: 21)).isEmpty)
    }

    @Test func belowLevel30NothingIsAsked() async throws {
        try await signIn(apiLevel: relatedAPILevel - 1)
        h.router.on("GET /api/movies/21/related", .json(Self.movieRelated))

        #expect(!library.offersRelated)
        #expect(try await library.relatedRows(.movie(id: 21)).isEmpty)
        #expect(try await library.relatedRows(.show(id: 7)).isEmpty)
        #expect(h.router.requests("GET /api/movies/21/related").isEmpty)
        #expect(h.router.requests("GET /api/shows/7/related").isEmpty)
    }

    @Test func aServerErrorIsThrownAsItsMessage() async throws {
        try await signIn(apiLevel: relatedAPILevel)
        h.router.on("GET /api/movies/21/related", .problem(500, "The related titles couldn’t be worked out."))

        await #expect(throws: APIError.self) { try await library.relatedRows(.movie(id: 21)) }
    }
}
