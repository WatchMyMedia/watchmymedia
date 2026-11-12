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
}
