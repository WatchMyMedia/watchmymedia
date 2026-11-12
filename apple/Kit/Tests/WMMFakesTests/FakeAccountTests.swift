import Foundation
import Testing
import WMMFakes
import WMMModel

@MainActor
@Suite struct FakeAccountTests {
    init() { FakeLatency.duration = .zero }

    @Test func aMemberAccountStaysAMemberWhenItsServerIsRestored() async throws {
        let services = AppServices.fake(admin: false)
        #expect(services.auth.user?.name == "Alex")
        #expect(services.auth.user?.isAdmin == false)
        try await services.servers.select(FakeServers.officeMini)
        #expect(services.auth.user == nil)
        try await services.servers.select(FakeServers.livingRoom)
        #expect(services.auth.user?.name == "Alex")
        #expect(services.auth.user?.isAdmin == false)
    }

    @Test func selectingServersRestoresTheirOwnRememberedAccounts() async throws {
        let servers = FakeServerRepository()
        let auth = FakeAuthRepository(servers: servers)
        #expect(auth.user?.name == "Sam")
        try await servers.select(FakeServers.officeMini)
        #expect(auth.user == nil)
        #expect(servers.current?.signedIn == false)
        _ = try await auth.signIn(name: "Robin", password: "password")
        try await servers.select(FakeServers.livingRoom)
        #expect(auth.user?.name == "Sam")
        try await servers.select(FakeServers.officeMini)
        #expect(auth.user?.name == "Robin")
        #expect(servers.current?.userName == "Robin")
        #expect(servers.current?.signedIn == true)
    }

    @Test func signingOutOnlyRemovesTheCurrentServersAccount() async throws {
        let servers = FakeServerRepository()
        let auth = FakeAuthRepository(servers: servers)
        try await servers.select(FakeServers.officeMini)
        _ = try await auth.signIn(name: "Robin", password: "password")
        await auth.signOut()
        #expect(auth.user == nil)
        try await servers.select(FakeServers.livingRoom)
        #expect(auth.user?.name == "Sam")
        try await servers.select(FakeServers.officeMini)
        #expect(auth.user == nil)
    }

    @Test func choosingAnotherOriginWithTheSameIDDoesNotReuseItsAccount() async throws {
        let servers = FakeServerRepository()
        let auth = FakeAuthRepository(servers: servers)
        var moved = FakeServers.livingRoom
        moved.baseURL = URL(string: "https://another.example.net")!
        try await servers.select(moved)
        #expect(servers.current?.server.baseURL == moved.baseURL)
        #expect(servers.current?.signedIn == false)
        #expect(auth.user == nil)
        try await servers.select(FakeServers.livingRoom)
        #expect(auth.user?.name == "Sam")
    }

    @Test func forgettingAServerAlsoForgetsItsAccount() async throws {
        let servers = FakeServerRepository()
        let auth = FakeAuthRepository(servers: servers)
        await servers.forget(serverID: FakeServers.livingRoom.id)
        #expect(servers.current == nil)
        #expect(auth.user == nil)
        try await servers.select(FakeServers.livingRoom)
        #expect(auth.user == nil)
        #expect(servers.current?.signedIn == false)
    }

    @Test func noSelectedServerCannotSignIn() async {
        let servers = FakeServerRepository(chosen: false)
        let auth = FakeAuthRepository(servers: servers)
        #expect(auth.user == nil)
        await #expect(throws: APIError.self) { try await auth.signIn(name: "Sam", password: "password") }
    }

    @Test func aDelayedPasswordReplyCannotSignInToTheNextServer() async throws {
        let servers = FakeServerRepository(signedIn: false)
        let gate = AuthGate()
        let auth = FakeAuthRepository(signedIn: false, servers: servers, signInDelay: { await gate.wait() })
        let request = Task { try await auth.signIn(name: "Robin", password: "password") }
        #expect(await gate.entered())
        try await servers.select(FakeServers.officeMini)
        gate.release()
        await #expect(throws: CancellationError.self) { try await request.value }
        #expect(auth.user == nil)
        try await servers.select(FakeServers.livingRoom)
        #expect(auth.user == nil)
    }

    @Test func changingAwayAndBackStillInvalidatesAnOldPasswordReply() async throws {
        let servers = FakeServerRepository()
        let gate = AuthGate()
        let auth = FakeAuthRepository(servers: servers, signInDelay: { await gate.wait() })
        let request = Task { try await auth.signIn(name: "Robin", password: "password") }
        #expect(await gate.entered())
        try await servers.select(FakeServers.officeMini)
        try await servers.select(FakeServers.livingRoom)
        gate.release()
        await #expect(throws: CancellationError.self) { try await request.value }
        #expect(auth.user?.name == "Sam")
    }

    @Test func aCancelledPasswordRequestCannotKeepItsReply() async throws {
        let servers = FakeServerRepository(signedIn: false)
        let gate = AuthGate()
        let auth = FakeAuthRepository(signedIn: false, servers: servers, signInDelay: { await gate.wait() })
        let request = Task { try await auth.signIn(name: "Robin", password: "password") }
        #expect(await gate.entered())
        request.cancel()
        gate.release()
        await #expect(throws: CancellationError.self) { try await request.value }
        #expect(auth.user == nil)
    }

    @Test func signingOutInvalidatesAnOldPasswordReply() async throws {
        let servers = FakeServerRepository()
        let gate = AuthGate()
        let auth = FakeAuthRepository(servers: servers, signInDelay: { await gate.wait() })
        let request = Task { try await auth.signIn(name: "Robin", password: "password") }
        #expect(await gate.entered())
        await auth.signOut()
        gate.release()
        await #expect(throws: CancellationError.self) { try await request.value }
        #expect(auth.user == nil)
        #expect(servers.current?.signedIn == false)
    }

    @Test func pairingUsesTheChosenServerAndCannotCompleteAfterSwitching() async throws {
        let servers = FakeServerRepository()
        let gate = AuthGate()
        let auth = FakeAuthRepository(servers: servers, pairingDelay: { await gate.wait() })
        try await servers.select(FakeServers.remoteHome)
        let stream = auth.pair()
        let request = Task { () -> [PairingState] in
            var states: [PairingState] = []
            for await state in stream { states.append(state) }
            return states
        }
        #expect(await gate.entered())
        try await servers.select(FakeServers.livingRoom)
        gate.release()
        let states = await request.value
        let waiting = try #require(states.compactMap { state -> PairingCode? in
            if case .waiting(let code) = state { return code }; return nil
        }.first)
        #expect(waiting.linkURL.absoluteString == "https://home.watchmy.media/link?code=K7M4PQ")
        #expect(!states.contains { if case .approved = $0 { true } else { false } })
        #expect(auth.user?.name == "Sam")
        try await servers.select(FakeServers.remoteHome)
        #expect(auth.user == nil)
    }

    @Test func standaloneAuthStillSupportsAccountScreenTests() async throws {
        let auth = FakeAuthRepository(signedIn: false)
        #expect(auth.user == nil)
        _ = try await auth.signIn(name: "Robin", password: "password")
        #expect(auth.user?.name == "Robin")
        await auth.signOut()
        #expect(auth.user == nil)
    }
}

@MainActor
private final class AuthGate {
    private var continuation: CheckedContinuation<Void, Never>?
    private var released = false

    func wait() async {
        if !released { await withCheckedContinuation { continuation = $0 } }
    }
    func release() { released = true; continuation?.resume(); continuation = nil }
    func entered() async -> Bool {
        for _ in 0..<2000 {
            if continuation != nil { return true }
            await Task.yield()
        }
        return continuation != nil
    }
}
