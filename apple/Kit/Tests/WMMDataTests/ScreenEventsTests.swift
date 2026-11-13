import Foundation
import Testing
@testable import WMMData
import WMMFeatures
import WMMModel

/// The screen models fed by the real ``ServerEvents`` and repositories
/// (PLAN-APPLE-REVIEW.md §4.1.1): what a mark, its echo and a scan cost
/// in requests.
@MainActor
@Suite(.timeLimit(.minutes(1)))
struct ScreenEventsTests {
    let h = Harness()

    /// Signed in on a stream the test writes to, with no reconnect during the test.
    private func connect() async throws -> LiveEventStream {
        h.tuning.eventBackoff = { _ in .seconds(30) }
        let events = h.liveEvents()
        try await h.signIn()
        return events
    }

    private func count(_ route: String) -> Int { h.router.requests(route).count }

    @Test func aMarkLoadsHomeOnceAndTheServersEchoIsDropped() async throws {
        let events = try await connect()
        h.router.always("GET /api/home", .json(Fixtures.home))
        h.router.always("POST /api/watch/movie/21/watched", .noContent)
        let model = HomeModel(library: h.services.library, auth: h.services.auth, navigator: Navigator())
        let running = Task { await model.run() }
        defer { running.cancel() }
        #expect(await eventually { model.state.value != nil && events.openCount == 1 })
        events.send(event: "ping", data: #"{"time":"t"}"#)

        await model.setWatched(.movie(id: 21), true)
        #expect(await eventually { count("GET /api/home") == 2 }, "the mark's own notice")
        // The server's echo of it: nothing more.
        events.send(event: "watch.changed", data: #"{"itemType":"movie","itemIds":[21]}"#)
        try await Task.sleep(for: .milliseconds(100))
        #expect(count("GET /api/home") == 2)
        // Another device's change is news.
        events.send(event: "watch.changed", data: #"{"itemType":"episode","itemIds":[401]}"#)
        #expect(await eventually { count("GET /api/home") == 3 })
    }

    @Test func anEchoBeforeTheReplyIsDroppedToo() async throws {
        let events = try await connect()
        let changes = Counter()
        let listening = Task { for await _ in h.services.library.libraryChanges() { changes.count += 1 } }
        defer { listening.cancel() }
        #expect(await eventually { events.openCount == 1 })
        events.send(event: "ping", data: #"{"time":"t"}"#)
        // What the repository does before the request: the echo can come before the reply.
        let echo = h.services.events.expect(.watched(.movie, ids: [21]))
        events.send(event: "watch.changed", data: #"{"itemType":"movie","itemIds":[21]}"#)
        events.send(event: "library.changed", data: #"{"libraryId":1}"#)
        #expect(await eventually { changes.count == 1 }, "library.changed, after the dropped echo")
        h.services.events.forget(echo)
        // Used up: a second one is news.
        events.send(event: "watch.changed", data: #"{"itemType":"movie","itemIds":[21]}"#)
        #expect(await eventually { changes.count == 2 })
    }

    @Test func aMarkLoadsTheShowAndItsSeasonOnce() async throws {
        let events = try await connect()
        h.router.always("GET /api/shows/11", .json(Fixtures.show))
        h.router.always("GET /api/shows/11/seasons/1", .json(Fixtures.season))
        h.router.always("POST /api/watch/episode/401/watched", .noContent)
        h.router.always("DELETE /api/watch/episode/401/watched", .noContent)
        let model = ShowPageModel(library: h.services.library, navigator: Navigator(), showID: 11, arrival: .episode(id: 401, season: 1))
        let running = Task { await model.run() }
        defer { running.cancel() }
        #expect(await eventually { model.episode?.id == 401 && events.openCount == 1 })
        events.send(event: "ping", data: #"{"time":"t"}"#)
        let shows = count("GET /api/shows/11"), seasons = count("GET /api/shows/11/seasons/1")

        await model.toggleWatched()
        events.send(event: "watch.changed", data: #"{"itemType":"episode","itemIds":[401]}"#)
        #expect(await eventually { count("GET /api/shows/11/seasons/1") == seasons + 1 })
        try await Task.sleep(for: .milliseconds(100))
        #expect(count("GET /api/shows/11") == shows + 1, "one load, not three")
        #expect(count("GET /api/shows/11/seasons/1") == seasons + 1)
    }

    @Test func scansLoadNothingWhileLibraryChangedLoadsOnce() async throws {
        let events = try await connect()
        h.router.always("GET /api/home", .json(Fixtures.home))
        let model = HomeModel(library: h.services.library, auth: h.services.auth, navigator: Navigator())
        let running = Task { await model.run() }
        defer { running.cancel() }
        #expect(await eventually { model.state.value != nil && events.openCount == 1 })
        events.send(event: "ping", data: #"{"time":"t"}"#)
        events.send(event: "scan.started", data: #"{"libraryId":1,"total":3}"#)
        events.send(event: "scan.progress", data: #"{"libraryId":1}"#)
        events.send(event: "scan.finished", data: #"{"libraryId":1}"#)
        try await Task.sleep(for: .milliseconds(100))
        #expect(count("GET /api/home") == 1)
        events.send(event: "library.changed", data: #"{"libraryId":1}"#)
        #expect(await eventually { count("GET /api/home") == 2 })
    }
}
