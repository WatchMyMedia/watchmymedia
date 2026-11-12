import Foundation
import Testing
import WMMAPI
@testable import WMMData
import WMMModel

/// New enum values reach the production generated decoder before domain mapping.
@MainActor
@Suite struct ResponseCompatibilityTests {
    @Test func unknownSegmentKindsAndSourcesPreserveTheirWireValues() throws {
        let json = Data(#"{"id":1,"kind":"sponsor","startMs":1000,"endMs":2000,"final":false,"source":"new-detector","confidence":1}"#.utf8)
        let segment = try JSONDecoder().decode(Components.Schemas.Segment.self, from: json)
        #expect(segment.kind == .undocumented("sponsor"))
        #expect(segment.source == .undocumented("new-detector"))
        let encoded = try #require(try JSONSerialization.jsonObject(with: JSONEncoder().encode(segment)) as? [String: Any])
        #expect(encoded["kind"] as? String == "sponsor")
        #expect(encoded["source"] as? String == "new-detector")
        #expect(Components.Schemas.Segment.KindPayload(rawValue: "sponsor") == nil)
        #expect(!Components.Schemas.Segment.KindPayload.allCases.contains(segment.kind))
    }

    @Test func requestOnlyEnumsAndMalformedResponseTypesStayStrict() throws {
        #expect(Components.Schemas.CreateOfflineInputBody.QualityPayload(rawValue: "1440") == nil)
        #expect(throws: DecodingError.self) {
            try JSONDecoder().decode(Components.Schemas.CreateOfflineInputBody.QualityPayload.self, from: Data(#""1440""#.utf8))
        }
        #expect(throws: DecodingError.self) {
            try JSONDecoder().decode(Components.Schemas.Segment.KindPayload.self, from: Data("42".utf8))
        }
        #expect(try JSONDecoder().decode(Components.Schemas.Segment.KindPayload.self, from: Data(#""intro""#.utf8)) == .intro)
    }

    @Test func homeKeepsKnownRowsWithoutInventingKindsForFutureContent() async throws {
        let h = Harness()
        try await h.signIn()
        var reply = try #require(try JSONSerialization.jsonObject(with: Data(Fixtures.home.utf8)) as? [String: Any])
        var hero = try #require(reply["hero"] as? [String: Any])
        hero["kind"] = "collection"
        reply["hero"] = hero
        var watching = try #require(reply["continueWatching"] as? [[String: Any]])
        watching[0]["kind"] = "audiobook"
        reply["continueWatching"] = watching
        var shows = try #require(reply["recentlyAddedShows"] as? [[String: Any]])
        shows[0]["kind"] = "collection"
        reply["recentlyAddedShows"] = shows
        var movies = try #require(reply["recentlyAddedMovies"] as? [[String: Any]])
        movies[0]["quality"] = "8K"
        reply["recentlyAddedMovies"] = movies
        let json = String(decoding: try JSONSerialization.data(withJSONObject: reply), as: UTF8.self)
        h.router.on("GET /api/home", .json(json))

        let home = try await h.services.library.home()
        #expect(home.hero == nil)
        #expect(home.continueWatching.count == 1)
        #expect(home.continueWatching.first?.kind == .movie)
        #expect(home.recentlyAddedShows.isEmpty)
        #expect(home.recentlyAddedMovies.first?.quality == "8K")
        #expect(home.recentlyAired.count == 2)
    }

    @Test func anUnknownSeasonRequestDoesNotBecomeAnUnrequestedSeason() async throws {
        let h = Harness()
        try await h.signIn()
        let request: [String: Any] = [
            "id": 7, "status": "future-state", "requestedBy": "viewer", "mine": true,
            "seasons": [1], "createdAt": "2026-10-31T12:00:00Z",
        ]
        var show = try #require(try JSONSerialization.jsonObject(with: Data(Fixtures.show.utf8)) as? [String: Any])
        var seasons = try #require(show["seasons"] as? [[String: Any]])
        seasons[0]["request"] = request
        show["seasons"] = seasons
        h.router.on("GET /api/shows/11", .json(String(decoding: try JSONSerialization.data(withJSONObject: show), as: UTF8.self)))
        #expect(try await h.services.library.show(id: 11).seasons.map(\.number) == [2])

        var season = try #require(try JSONSerialization.jsonObject(with: Data(Fixtures.season.utf8)) as? [String: Any])
        season["request"] = request
        h.router.on("GET /api/shows/11/seasons/1", .json(String(decoding: try JSONSerialization.data(withJSONObject: season), as: UTF8.self)))
        await #expect(throws: APIError.self) { try await h.services.library.season(showID: 11, number: 1) }
    }
}
