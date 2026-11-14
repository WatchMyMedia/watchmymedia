import Foundation
import Testing
@testable import WMMData
import WMMModel

/// Reference data asked for once by everyone who asks at the same time
/// (PLAN-APPLE-REVIEW.md §4.1.3): launch, the sign-in and the foreground
/// refresh all want the request options, Live TV's access and the server's
/// level at once.
@MainActor
@Suite(.timeLimit(.minutes(1)))
struct ReferenceDataTests {
    let h = Harness()
    private static let options = #"{"movies":true,"shows":true,"needsApproval":true,"problems":true,"manage":false,"backend":"arr","inbox":0}"#

    private func count(_ route: String) -> Int { h.router.requests(route).count }

    private func signIn(level: Int) async throws {
        try await h.signIn()
        var server = h.testServer
        server.apiLevel = level
        try await h.services.servers.select(server)
    }

    @Test func requestOptionsAskedAtOnceAreAskedOnceAndAgainAfterAnEvent() async throws {
        h.tuning.eventBackoff = { _ in .seconds(30) }
        let events = h.liveEvents()
        h.router.always("GET /api/requests/options", .json(Self.options))
        try await signIn(level: 6)
        // The sign-in asks by itself; wait for it before counting.
        #expect(await eventually { h.services.requests.options.supported })
        let before = count("GET /api/requests/options")
        let requests = h.services.requests
        await together({ await requests.refreshOptions() }, { await requests.refreshOptions() }, { await requests.refreshOptions() })
        #expect(count("GET /api/requests/options") == before + 1)

        // After a requests event, a caller asks again rather than taking an older answer.
        let listening = Task { for await _ in h.services.requests.changes() {} }
        defer { listening.cancel() }
        #expect(await eventually { events.openCount == 1 })
        events.send(event: "ping", data: #"{"time":"t"}"#)
        events.send(event: "requests.changed", data: #"{"requestId":1,"status":"approved"}"#)
        #expect(await eventually { h.services.events.received[.requests] == 1 })
        await h.services.requests.refreshOptions()
        #expect(count("GET /api/requests/options") == before + 2)
    }

    @Test func liveTVAccessAskedAtOnceIsAskedOnce() async throws {
        h.router.always("GET /api/live-tv/capabilities", .json(#"{"enabled":true,"allowed":true,"playback":true,"guide":true}"#))
        h.router.always("GET /api/live-tv/channels", .json(#"{"items":[]}"#))
        try await signIn(level: 15)
        let live = h.services.liveTV
        await together({ try? await live.refresh() }, { try? await live.refresh() })
        #expect(count("GET /api/live-tv/capabilities") == 1)
        #expect(count("GET /api/live-tv/channels") == 1)
        #expect(h.services.liveTV.capabilities?.available == true)
    }

    @Test func theServersLevelIsAskedOnceAWhileAndSaysWhetherItAnswered() async throws {
        try await h.select()
        h.router.always("GET /api/server", .json(Fixtures.serverInfo(apiLevel: 3).replacingOccurrences(of: "2321341d1a4a571c275b88b68f88d3c5", with: h.testServer.id)))
        #expect(h.services.servers.reachable == nil, "not asked yet")
        let servers = h.services.servers
        await together({ await servers.refreshCurrent() }, { await servers.refreshCurrent() })
        await h.services.servers.refreshCurrent()
        #expect(count("GET /api/server") == 1, "at once, and again within seconds: one request")
        #expect(h.services.servers.current?.server.apiLevel == 3)
        #expect(h.services.servers.reachable == true)

        h.router.always("GET /api/server", .problem(502, "Bad gateway"))
        await h.services.serverRepository.refreshCurrent(fresh: true)
        #expect(count("GET /api/server") == 2)
        #expect(h.services.servers.reachable == false)
        #expect(h.services.servers.current?.server.apiLevel == 3, "kept as it was")
    }
}
