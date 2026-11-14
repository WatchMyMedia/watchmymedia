import Foundation
import Testing
@testable import WMMData
import WMMModel

@Suite struct SSEParserTests {
    @Test func theParserJoinsDataLinesAndSkipsComments() {
        var parser = SSEParser()
        let lines = [
            ": a comment",
            "event: ping",
            #"data: {"time":"2026-10-29T17:00:00Z"}"#,
            "",
            "id: 7",
            "event: watch.changed",
            #"data: {"itemType":"episode","#,
            #"data: "itemIds":[401]}"#,
            "",
            "data:no space after the colon",
            "",
            "event: dangling",
        ]
        let events = lines.compactMap { parser.line($0) }
        #expect(events == [
            ServerEvent(event: "ping", data: #"{"time":"2026-10-29T17:00:00Z"}"#),
            ServerEvent(event: "watch.changed", data: "{\"itemType\":\"episode\",\n\"itemIds\":[401]}", id: "7"),
            // The last event ID carries on until another id field (the spec's last event ID buffer).
            ServerEvent(event: "message", data: "no space after the colon", id: "7"),
        ])
    }

    @Test func bytesSplitAnywhereMakeTheSameEvents() {
        let stream = Data("\u{FEFF}: hello\r\nevent: watch.changed\r\ndata: {\"itemIds\":[1]}\r\n\r\nevent: library.changed\rdata: é\r\rdata: x\n\n".utf8)
        let expected = [
            ServerEvent(event: "watch.changed", data: #"{"itemIds":[1]}"#),
            ServerEvent(event: "library.changed", data: "é"),
            ServerEvent(event: "message", data: "x"),
        ]
        // Whole, a byte at a time, and in every two-piece split (across CRLF and inside "é" too).
        var whole = SSEParser()
        #expect(whole.feed(stream) == expected)
        var bytewise = SSEParser()
        #expect(stream.flatMap { bytewise.feed([$0]) } == expected)
        for cut in 1..<stream.count {
            var parser = SSEParser()
            let events = parser.feed(stream.prefix(cut)) + parser.feed(stream.dropFirst(cut))
            #expect(events == expected, "split at \(cut)")
        }
    }

    @Test func anEventWithoutDataIsNotSent() {
        var parser = SSEParser()
        #expect(parser.feed(Data("event: ping\n\n: keep-alive\n\n".utf8)).isEmpty)
    }
}

@MainActor
@Suite(.timeLimit(.minutes(1)))
struct ServerEventsTests {
    let h = Harness()
    /// The event stream's backoff and linger, ended by the test (never the real clock).
    let time = ManualSleeps()

    init() {
        let time = time
        h.tuning.eventSleep = { try await time.sleep($0) }
        h.reopen()
    }

    private func stream(_ body: String) -> Reply { Reply(status: 200, body: Data(body.utf8)) }

    private static let ping = "event: ping\ndata: {\"time\":\"t\"}\n\n"

    /// The connection ended and the stream is waiting `delay` before the next.
    private func waiting(_ delay: Duration) async -> Bool {
        await eventually { time.waiting == [delay] }
    }

    @Test func changesComeFromWatchAndLibraryEventsAndAgainAfterAReconnect() async throws {
        try await h.signIn()
        h.router.on(
            "GET /api/events",
            stream(Self.ping + "event: scan.progress\ndata: {\"libraryId\":1}\n\nevent: watch.changed\ndata: {\"itemType\":\"movie\",\"itemIds\":[21]}\n\n"),
            stream(Self.ping),
            stream(Self.ping + "event: library.changed\ndata: {\"libraryId\":2}\n\n")
        )
        let changes = Counter()
        let listening = Task { for await _ in h.services.library.changes() { changes.count += 1 } }
        defer { listening.cancel() }

        // Each connection's news arrives on its own: the next waits for the test.
        #expect(await eventually { changes.count == 1 }, "watch.changed")
        #expect(await waiting(.milliseconds(20)))
        time.advance()
        #expect(await eventually { changes.count == 2 }, "the second connection's ping: events may have been missed")
        #expect(await waiting(.milliseconds(20)))
        time.advance()
        #expect(await eventually { changes.count >= 3 }, "the third connection")
        #expect(await waiting(.milliseconds(20)))
        time.advance()
        // The fourth answers 404 (no events on this server): asked again only at the longest wait.
        #expect(await waiting(.seconds(60)))
        #expect(h.router.requests("GET /api/events").count == 4)

        let first = try #require(h.router.requests("GET /api/events").first)
        #expect(first.value(forHTTPHeaderField: "Authorization") == "Bearer tok-viewer")
        #expect(first.value(forHTTPHeaderField: "Accept") == "text/event-stream")
    }

    @Test func keepAlivePingsOnOneConnectionChangeNothing() async throws {
        try await h.signIn()
        h.router.on("GET /api/events", stream(Self.ping + Self.ping + Self.ping + "event: watch.changed\ndata: {}\n\n"))
        let changes = Counter()
        let listening = Task { for await _ in h.services.library.changes() { changes.count += 1 } }
        defer { listening.cancel() }
        #expect(await eventually { changes.count == 1 }, "watch.changed")
        #expect(await waiting(.milliseconds(20)))
        time.advance()
        // The reconnect is answered 404, so the stream is over: the pings sent nothing.
        #expect(await waiting(.seconds(60)))
        #expect(h.router.requests("GET /api/events").count == 2)
        #expect(changes.count == 1)
    }

    @Test func scanStateTransitionsRefreshTheLibraryButProgressDoesNotFloodIt() async throws {
        try await h.signIn()
        h.router.on("GET /api/events", stream(Self.ping +
            "event: scan.started\ndata: {\"libraryId\":1}\n\n" +
            "event: scan.progress\ndata: {\"libraryId\":1}\n\n" +
            "event: scan.failed\ndata: {\"libraryId\":1}\n\n" +
            "event: scan.finished\ndata: {\"libraryId\":2}\n\n"))
        let changes = Counter()
        let listening = Task { for await _ in h.services.library.changes() { changes.count += 1 } }
        defer { listening.cancel() }
        #expect(await waiting(.milliseconds(20)), "the connection has ended")
        #expect(await eventually { changes.count == 3 })
    }

    @Test func oneConnectionIsSharedByEveryListener() async throws {
        try await h.signIn()
        h.router.on("GET /api/events", stream(Self.ping + "event: watch.changed\ndata: {}\n\n"))
        let a = Counter(), b = Counter()
        let first = Task { for await _ in h.services.library.changes() { a.count += 1 } }
        let second = Task { for await _ in h.services.library.changes() { b.count += 1 } }
        defer {
            first.cancel()
            second.cancel()
        }
        #expect(await eventually { a.count == 1 && b.count == 1 })
        #expect(await waiting(.milliseconds(20)))
        time.advance()
        #expect(await waiting(.seconds(60)), "the reconnect, answered 404")
        #expect(h.router.requests("GET /api/events").count == 2)
    }

    @Test func a401OnTheStreamSignsOutAndStops() async throws {
        try await h.signIn()
        h.router.always("GET /api/events", .problem(401, "sign in required"))
        let listening = Task { for await _ in h.services.library.changes() {} }
        defer { listening.cancel() }
        #expect(await eventually { h.services.auth.user == nil })
        #expect(h.router.requests("GET /api/events").count == 1)
        #expect(time.asked.isEmpty, "no backoff: nothing is asked again")
    }

    @Test func signedOutNothingConnects() async throws {
        try await h.select()
        let listening = Task { for await _ in h.services.library.changes() {} }
        defer { listening.cancel() }
        #expect(await eventually { h.services.events.listenerCount == 1 })
        #expect(h.router.requests("GET /api/events").isEmpty)
    }

    @Test func signingInConnectsTheListenersAlreadyWaiting() async throws {
        try await h.select()
        h.router.on("GET /api/events", stream(Self.ping + "event: watch.changed\ndata: {}\n\n"))
        let changes = Counter()
        let listening = Task { for await _ in h.services.library.changes() { changes.count += 1 } }
        defer { listening.cancel() }
        #expect(await eventually { h.services.events.listenerCount == 1 })
        #expect(h.router.requests("GET /api/events").isEmpty)
        try await h.signIn()
        #expect(await eventually { changes.count == 1 })
    }

    @Test func theStreamClosesWhenTheLastListenerLeaves() async throws {
        h.tuning.eventLinger = .seconds(5)
        h.reopen()
        try await h.signIn()
        h.router.always("GET /api/events", stream(Self.ping))
        let listening = Task { for await _ in h.services.library.changes() {} }
        #expect(await waiting(.milliseconds(20)))
        listening.cancel()
        // The stream lingers; once that ends, the waiting reconnect is cancelled.
        #expect(await eventually { time.waiting.contains(.seconds(5)) })
        time.advance(.seconds(5))
        #expect(await eventually { time.waiting.isEmpty })
        #expect(h.router.requests("GET /api/events").count == 1)
        #expect(h.services.events.listenerCount == 0)
    }

    @Test func aServerWithoutEventsIsAskedAgainAndFollowedOnceItHasThem() async throws {
        h.tuning.eventBackoff = { _ in .milliseconds(20) }
        h.reopen()
        try await h.signIn()
        h.router.on("GET /api/events", .problem(404, "no such route"), stream(Self.ping + "event: watch.changed\ndata: {}\n\n"))
        let changes = Counter()
        let listening = Task { for await _ in h.services.library.changes() { changes.count += 1 } }
        defer { listening.cancel() }
        #expect(await waiting(.milliseconds(20)), "after the 404")
        time.advance()
        #expect(await eventually { changes.count >= 1 }, "the next connection's event")
    }

    @Test func theNetworkComingBackReconnectsAtOnce() async throws {
        let network = FakeNetworkPath()
        h.network = network
        // Waits longer than the test after a failure.
        h.tuning.eventBackoff = { _ in .seconds(60) }
        let events = h.liveEvents()
        try await h.signIn()
        events.answer(503)
        let changes = Counter()
        let listening = Task { for await _ in h.services.library.changes() { changes.count += 1 } }
        defer { listening.cancel() }
        #expect(await eventually { events.requests.count == 1 })
        #expect(await waiting(.seconds(60)))
        network.set(false)
        #expect(events.requests.count == 1, "nothing while the network is gone")
        network.set(true)
        #expect(await eventually { events.requests.count == 2 && events.openCount == 1 }, "at once, not after the backoff")
    }

    @Test func comingBackToTheAppReconnectsAndTellsListenersAfterAskingTheLevelAgain() async throws {
        let events = h.liveEvents()
        try await h.signIn()
        let changes = Counter()
        let listening = Task { for await _ in h.services.library.changes() { changes.count += 1 } }
        defer { listening.cancel() }
        #expect(await eventually { events.openCount == 1 })
        events.send(event: "ping", data: #"{"time":"t"}"#)
        #expect(await eventually { h.services.events.pinged })
        #expect(changes.count == 0, "a first connection misses nothing")
        // An upgrade while the app was away.
        h.router.always("GET /api/server", .json(Fixtures.serverInfo(apiLevel: 9).replacingOccurrences(of: "2321341d1a4a571c275b88b68f88d3c5", with: h.testServer.id)))
        h.services.events.reconnectNow()
        #expect(await eventually { events.requests.count == 2 && events.openCount == 1 })
        events.send(event: "ping", data: #"{"time":"t"}"#)
        #expect(await eventually { changes.count == 1 }, "events may have been missed")
        #expect(h.services.servers.current?.server.apiLevel == 9, "the level was asked again before listeners heard")
        #expect(h.router.requests("GET /api/server").count == 1)
    }

    @Test func eventsNobodyHandlesNeverReachTheMainActor() {
        for name in ["scan.progress", "match.progress", "activity.updated", "plex.changed", "trickplay.ready"] {
            #expect(!ServerEvents.handled.contains(name))
        }
        for name in ["ping", "watch.changed", "library.changed", "scan.finished", "requests.changed", "live.recordings.changed", "rows.changed"] {
            #expect(ServerEvents.handled.contains(name))
        }
    }
}
