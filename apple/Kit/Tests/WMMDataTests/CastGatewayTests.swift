import Foundation
import Testing
@testable import WMMData
import WMMModel

@MainActor
@Suite struct CastGatewayTests {
    @Test func scopedGrantComesOnlyFromTheSelectedServer() async throws {
        let h = Harness()
        var server = h.testServer; server.apiLevel = 18
        try await h.services.servers.select(server)
        h.router.on("POST /api/auth/login", .json(Fixtures.signIn))
        _ = try await h.services.auth.signIn(name: "viewer", password: "fixture-viewer-1")
        h.router.always("GET /api/server", .json(#"{"id":"srv-test","name":"Test server","version":"1","apiLevel":18,"setupRequired":false,"turnstile":false,"casting":true,"castAppId":"0A4D0B6A"}"#))
        h.router.on("POST /api/auth/cast-token", .json(#"{"token":"c1.scoped","param":"access_token","expiresAt":"2099-01-01T00:00:00Z","urls":["https://cast-origin.example/wmm","https://name:secret@bad.example","file:///tmp/no","https://bad.example/?key=secret"]}"#))
        let grant = try await h.services.casting.grant()
        #expect(grant.token == "c1.scoped")
        #expect(grant.capability.identity.origin == h.router.baseURL)
        #expect(grant.urls == [URL(string: "https://cast-origin.example/wmm")!, h.router.baseURL])
        #expect(try h.router.last("POST /api/auth/cast-token").value(forHTTPHeaderField: "Authorization") == "Bearer tok-viewer")
        #expect(h.router.server.seen.allSatisfy { $0.url?.host == h.router.baseURL.host }, "there are no bearer probes to alternate origins")
    }

    @Test func oldDisabledAndDifferentReceiverServersCannotIssueALoadGrant() async throws {
        let h = Harness()
        try await h.signIn()
        #expect(try await h.services.casting.capability() == nil)
        #expect(h.router.requests("POST /api/auth/cast-token").isEmpty)
        var server = h.testServer; server.apiLevel = 18
        try await h.services.servers.select(server)
        h.router.always("GET /api/server", .json(#"{"id":"srv-test","name":"Test server","version":"1","apiLevel":18,"setupRequired":false,"turnstile":false,"casting":false,"castAppId":"0A4D0B6A"}"#))
        #expect(try await h.services.casting.capability() == nil)
        h.router.always("GET /api/server", .json(#"{"id":"different-server","name":"Other","version":"1","apiLevel":18,"setupRequired":false,"turnstile":false,"casting":true,"castAppId":"0A4D0B6A"}"#))
        #expect(try await h.services.casting.capability() == nil)
        #expect(h.router.requests("POST /api/auth/cast-token").isEmpty)
    }

    // Cast's check reads GET /api/server through ServerInfoReader (PLAN-APPLE-REVIEW.md §5.6).

    private nonisolated static let castingServer = #"{"id":"srv-test","name":"Test server","version":"1","apiLevel":18,"setupRequired":false,"turnstile":false,"casting":true,"castAppId":"0A4D0B6A"}"#

    private func signedInAtLevel18() async throws -> Harness {
        let h = Harness()
        var server = h.testServer; server.apiLevel = 18
        try await h.services.servers.select(server)
        h.router.on("POST /api/auth/login", .json(Fixtures.signIn))
        _ = try await h.services.auth.signIn(name: "viewer", password: "fixture-viewer-1")
        return h
    }

    @Test func theCheckAndTheLevelsRefreshShareOneRequest() async throws {
        let h = try await signedInAtLevel18()
        h.router.always("GET /api/server", .json(Self.castingServer))
        await h.services.serverRepository.refreshCurrent()
        let asked = h.router.requests("GET /api/server").count
        #expect(try await h.services.casting.capability() != nil)
        #expect(h.router.requests("GET /api/server").count == asked)
        #expect(h.router.requests("GET /api/server").allSatisfy { $0.value(forHTTPHeaderField: "Authorization") == nil }, "the description is public")
    }

    @Test func anotherServerAnsweringAtTheAddressIssuesNoGrant() async throws {
        let h = try await signedInAtLevel18()
        h.router.always("GET /api/server", .json(Self.castingServer.replacingOccurrences(of: "srv-test", with: "different-server")))
        #expect(try await h.services.casting.capability() == nil)
        await #expect(throws: APIError.self) { try await h.services.casting.grant() }
        #expect(h.router.requests("POST /api/auth/cast-token").isEmpty)
    }

    @Test func anAccountChangeWhileCheckingIsASignOut() async throws {
        let h = try await signedInAtLevel18()
        let gate = ReplyGate()
        h.server.respondAsync { request in
            guard request.url?.path == "/api/server" else { return (404, Data()) }
            await gate.wait()
            return (200, Data(Self.castingServer.utf8))
        }
        let checking = Task { try await h.services.casting.capability() }
        #expect(await eventually { h.server.seen.contains { $0.url?.path == "/api/server" } })
        h.services.store.setCredential(nil, serverID: h.testServer.id)
        await gate.release()
        await #expect(throws: SignedOutError()) { try await checking.value }
    }
}
