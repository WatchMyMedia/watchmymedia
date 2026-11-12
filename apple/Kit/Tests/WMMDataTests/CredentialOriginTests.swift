import Foundation
import Synchronization
import Testing
@testable import WMMData
import WMMModel

@MainActor
struct CredentialOriginTests {
    @Test func legacyInstallKeepsItsCredentialOnlyAtItsSavedAddress() async throws {
        let h = Harness()
        try await h.signIn()
        var old = try #require(h.storage.credentials[h.testServer.id])
        old.baseURL = nil
        h.storage.credentials[h.testServer.id] = old
        let reopened = h.reopen()
        #expect(reopened.auth.user?.name == "viewer")
        #expect(h.storage.credentials[h.testServer.id]?.baseURL == h.testServer.baseURL)
        h.storage.state = StoredState()
        h.storage.credentials[h.testServer.id] = old
        let reinstalled = h.reopen()
        try await reinstalled.servers.select(h.testServer)
        #expect(reinstalled.auth.user == nil, "orphan legacy credentials have no trusted origin")
    }

    @Test func delayedSignInCannotReplaceAnAccountAfterEndpointChange() async throws {
        let h = Harness()
        try await h.services.servers.select(h.testServer)
        let reached = Mutex(false)
        let resume = DispatchSemaphore(value: 0)
        h.router.server.respond { _, path, _ in
            if path == "/api/auth/login" {
                reached.withLock { $0 = true }
                _ = resume.wait(timeout: .now() + 5)
                return (200, Data(Fixtures.signIn.utf8))
            }
            return (204, nil)
        }
        let pending = Task { try await h.services.auth.signIn(name: "viewer", password: "fixture-viewer-1") }
        defer { resume.signal() }
        for _ in 0..<200 {
            if reached.withLock({ $0 }) { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        #expect(reached.withLock { $0 })
        var moved = h.testServer
        moved.baseURL = URL(string: "https://different.example/wmm")!
        try await h.services.servers.select(moved)
        resume.signal()
        await #expect(throws: CancellationError.self) { _ = try await pending.value }
        #expect(h.services.auth.user == nil)
        #expect(h.services.store.loadCredential(serverID: moved.id, baseURL: moved.baseURL) == nil)
    }

    @Test func schemePortAndProxyPathBelongToTheCredentialScope() {
        let origin = URL(string: "https://server.example/wmm")!
        #expect(ServerAPI.sameEndpoint(origin, URL(string: "https://SERVER.example:443/wmm/")!))
        for changed in ["http://server.example/wmm", "https://server.example:8443/wmm", "https://server.example/other", "https://other.example/wmm"] {
            #expect(!ServerAPI.sameEndpoint(origin, URL(string: changed)!))
        }
    }

    @Test func queuedPlaybackReportsKeepTheOriginalServerAndAccount() async throws {
        let h = Harness()
        try await h.signIn()
        let gateway = APIPlaybackGateway(api: { try h.services.api() }, apiLevel: { h.services.apiLevel }).bound()
        let other = Router()
        let server = Server(id: "other", name: "Other", baseURL: other.baseURL, version: "1", apiLevel: 1)
        try await h.services.servers.select(server)
        h.services.store.setCredential(StoredCredential(user: User(id: 3, name: "next", isAdmin: false), token: "next-token", baseURL: other.baseURL), serverID: server.id)
        h.router.on("PUT /api/files/4/progress", .json(#"{"positionMs":5000,"durationMs":200000,"completed":false,"updatedAt":"2026-10-29T13:09:35Z"}"#))
        _ = try await gateway.reportProgress(ProgressReport(fileID: 4, positionMs: 5000, durationMs: 200000, playID: "1111111111111111", state: .stopped, bufferedMs: 0))
        #expect(try h.router.last("PUT /api/files/4/progress").value(forHTTPHeaderField: "Authorization") == "Bearer tok-viewer")
        #expect(other.requests("PUT /api/files/4/progress").isEmpty)
    }

    @Test func publicServerIDCannotMoveSavedBearerToAnotherOrigin() async throws {
        let h = Harness()
        try await h.signIn()
        let impostor = Router()
        impostor.on("GET /api/server", .json(#"{"id":"srv-test","name":"Test server","version":"1.0.0","apiLevel":1,"setupRequired":false,"turnstile":false}"#))
        impostor.on("GET /api/auth/sessions", .json(Fixtures.sessions))
        let probed = try await h.services.servers.probe(impostor.baseURL.absoluteString)
        #expect(probed.baseURL != h.testServer.baseURL)
        #expect(probed.id == h.testServer.id)
        try await h.services.servers.select(probed)
        #expect(h.services.auth.user == nil)
        await #expect(throws: SignedOutError.self) { _ = try await h.services.auth.sessions(all: false) }
        #expect(impostor.requests("GET /api/auth/sessions").isEmpty)
    }
}
