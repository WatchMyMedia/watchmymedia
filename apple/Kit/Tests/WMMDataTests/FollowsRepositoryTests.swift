import Foundation
import Testing
@testable import WMMData
import WMMModel

/// Following shows and the new-episode feed (PLAN-NEW-EPISODES.md §5.3, §5.4;
/// API level 29), from replies shaped as api/openapi.json has them.
@Suite(.timeLimit(.minutes(1))) @MainActor struct FollowsRepositoryTests {
    let h = Harness()
    var follows: any FollowsRepository { h.services.follows }

    private func ready(level: Int = followsAPILevel) async throws {
        _ = try await h.signIn()
        var server = h.testServer
        server.apiLevel = level
        try await h.services.servers.select(server)
    }

    @Test func offeredFromLevel29ToTheSignedInPersonWithTheirOwnContext() async throws {
        #expect(!follows.available)
        #expect(follows.contextID == nil)
        try await ready(level: followsAPILevel - 1)
        #expect(!follows.available)
        #expect(try await follows.follows().isEmpty, "below 29 the list isn't asked")
        #expect(h.router.requests("GET /api/me/follows").isEmpty)
        try await ready()
        #expect(follows.available)
        #expect(follows.contextID == "srv-test:2")
        #expect(follows.serverID == "srv-test")
    }

    @Test func followingAndStoppingCallTheShowsRoutes() async throws {
        try await ready()
        h.router.always("PUT /api/shows/11/follow", .noContent)
        h.router.always("DELETE /api/shows/11/follow", .noContent)
        h.router.always("DELETE /api/me/follows/9001", .noContent)
        try await follows.follow(showID: 11)
        try await follows.unfollow(showID: 11)
        try await follows.remove(tmdbID: 9001)
        #expect(h.router.requests("PUT /api/shows/11/follow").count == 1)
        #expect(h.router.requests("DELETE /api/shows/11/follow").count == 1)
        #expect(h.router.requests("DELETE /api/me/follows/9001").count == 1)

        h.router.on("PUT /api/shows/12/follow", .problem(409, "You follow 500 shows"))
        await #expect(throws: APIError.self) { try await follows.follow(showID: 12) }
    }

    @Test func theListMapsAShowThatLeftTheLibrary() async throws {
        try await ready()
        h.router.on("GET /api/me/follows", .json("""
            { "follows": [
              { "tmdbId": 9001, "title": "Harbor Lights", "followedAt": "2026-11-10T09:00:00Z",
                "show": { "id": 11, "title": "Harbor Lights", "posterPath": "/hl.jpg" },
                "latest": { "season": 3, "number": 2, "title": "The Pilot Boat", "arrivedAt": "2026-11-15T20:00:00Z" } },
              { "tmdbId": 9002, "title": "Westward Signals", "followedAt": "2026-10-01T09:00:00Z" }
            ] }
            """))
        let list = try await follows.follows()
        #expect(list.map(\.tmdbID) == [9001, 9002])
        #expect(list[0].showID == 11 && list[0].posterPath == "/hl.jpg" && list[0].inLibrary)
        #expect(list[0].latest == FollowedShow.Latest(season: 3, number: 2, title: "The Pilot Boat", arrivedAt: ISO8601DateFormatter().date(from: "2026-11-15T20:00:00Z")!))
        #expect(list[1].showID == nil && !list[1].inLibrary && list[1].latest == nil)
    }

    @Test func theFeedSendsTheCursorAndGroupsByShow() async throws {
        try await ready()
        h.router.on("GET /api/me/new-episodes", .json(#"{"cursor":"1840","shows":[]}"#), .json("""
            { "cursor": "1846", "shows": [
              { "show": { "id": 11, "tmdbId": 9001, "title": "Harbor Lights" }, "count": 3,
                "episodes": [
                  { "id": 210, "season": 2, "number": 10, "title": "Lantern Rock", "arrivedAt": "2026-11-16T10:00:00Z" },
                  { "id": 301, "season": 3, "number": 1, "arrivedAt": "2026-11-16T10:00:00Z" },
                  { "id": 302, "season": 3, "number": 2, "title": "", "arrivedAt": "2026-11-16T10:00:00Z" } ],
                "open": { "episodeId": 210, "season": 2, "number": 10 } } ] }
            """))
        let first = try await follows.newEpisodes(after: nil)
        #expect(first == NewEpisodesPage(cursor: "1840"))
        #expect(query(try h.router.last("GET /api/me/new-episodes"))["after"] == nil, "a first check starts from now")

        let page = try await follows.newEpisodes(after: "1840")
        #expect(query(try h.router.last("GET /api/me/new-episodes"))["after"] == "1840")
        #expect(page.cursor == "1846")
        let group = try #require(page.shows.first)
        #expect(group.show == .init(id: 11, tmdbID: 9001, title: "Harbor Lights"))
        #expect(group.count == 3)
        #expect(group.episodes.map(\.id) == [210, 301, 302])
        #expect(group.episodes.map(\.title) == ["Lantern Rock", nil, nil], "a blank title is none")
        #expect(group.open == .init(episodeID: 210, season: 2, number: 10))
    }

    @Test func aShowsPageSaysWhetherThePersonFollowsIt() async throws {
        try await ready()
        let followed = Fixtures.show.replacingOccurrences(of: #""tmdbId": 9001,"#, with: #""tmdbId": 9001, "following": true,"#)
        h.router.on("GET /api/shows/11", .json(followed), .json(Fixtures.show))
        #expect(try await h.services.library.show(id: 11).following == true)
        #expect(try await h.services.library.show(id: 11).following == nil, "an older server says nothing")
    }

    @Test func followsChangedReachesTheFollowsListenersOnly() async throws {
        let time = ManualSleeps()
        h.tuning.eventSleep = { try await time.sleep($0) }
        h.reopen()
        try await ready()
        h.router.on("GET /api/events", Reply(status: 200, body: Data((
            "event: ping\ndata: {\"time\":\"t\"}\n\n" +
            "event: follows.changed\ndata: {\"tmdbId\":9001,\"following\":true}\n\n").utf8)))
        let heard = Counter(), library = Counter()
        let listening = Task { for await _ in h.services.follows.changes() { heard.count += 1 } }
        let watching = Task { for await _ in h.services.library.changes() { library.count += 1 } }
        defer { listening.cancel(); watching.cancel() }
        #expect(await eventually { heard.count == 1 })
        #expect(library.count == 0)
    }
}
