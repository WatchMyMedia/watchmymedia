import Foundation
import Synchronization
import Testing
@testable import WMMData
import WMMModel

/// The one place a kept sign-in goes on a request: it goes freely to the
/// server in use, and to any other server only after that server has said,
/// asked with no sign-in, that it is the one remembered. So a download's
/// calls to the server it came from, and an operation that began on one
/// server and is still sending when another comes into use, ask first; a
/// different server at the kept address, and nothing at it, are sent no
/// sign-in at all.
@MainActor
struct SignInCheckTests {
    let h = Harness()
    typealias Second = SeveralServersTests.Second

    /// Signed in on the test server (in use) and on a second one.
    private func signInOnBoth() async throws -> (second: Second, cabin: Server, owner: OfflineOwner) {
        try await h.signIn()
        let second = Second()
        let cabin = Server(id: "srv-cabin", name: "Cabin server", baseURL: second.server.baseURL, version: "1.0.0", apiLevel: 1)
        try await h.services.servers.select(cabin)
        _ = try await h.services.auth.signIn(name: "sam", password: "fixture-sam-1")
        try await h.services.servers.select(h.testServer)
        // The sign-ins' own calls (the session's check, the settings) go to the server in use; they are done before a test counts.
        #expect(await eventually { !h.router.requests("GET /api/me/preferences").isEmpty })
        try await Task.sleep(for: .milliseconds(50))
        return (second, cabin, OfflineOwner(serverID: cabin.id, userID: 7, baseURL: cabin.baseURL, apiLevel: 1))
    }

    private func route(_ request: URLRequest) -> String { "\(request.httpMethod ?? "GET") \(request.url?.path ?? "")" }
    private func signed(_ request: URLRequest) -> String? { request.value(forHTTPHeaderField: "Authorization") }

    // MARK: A download's calls to the server it came from

    @Test func aDownloadsCallAsksItsServerFirstAndEachTime() async throws {
        let (second, cabin, owner) = try await signInOnBoth()
        let before = second.server.seen.count
        _ = try await h.services.offline.token(owner, downloadID: "d1")
        _ = try await h.services.offline.token(owner, downloadID: "d1")

        let after = Array(second.server.seen.dropFirst(before))
        #expect(after.map(route) == ["GET /api/server", "POST /api/offline/downloads/d1/token", "GET /api/server", "POST /api/offline/downloads/d1/token"],
                "background work asks each time: nothing says when an answer has grown old")
        #expect(after.map(signed) == [nil, "Bearer tok-cabin", nil, "Bearer tok-cabin"], "the question carries no sign-in; the call carries its own server's")
        #expect(!h.services.library.answerStands(serverID: cabin.id), "its answer isn't kept for anything else")
        #expect(h.router.requests("GET /api/server").isEmpty, "the server in use wasn't asked")
    }

    @Test func aDownloadsCallSendsNoSignInToAnotherServerOrToNothing() async throws {
        for answer in [Second.info(id: "srv-somebody-elses", name: "Somebody else’s"), nil] {
            let h = Harness()
            try await h.signIn()
            let second = Second()
            let cabin = Server(id: "srv-cabin", name: "Cabin server", baseURL: second.server.baseURL, version: "1.0.0", apiLevel: 1)
            try await h.services.servers.select(cabin)
            _ = try await h.services.auth.signIn(name: "sam", password: "fixture-sam-1")
            try await h.services.servers.select(h.testServer)
            #expect(await eventually { !h.router.requests("GET /api/me/preferences").isEmpty })
            try await Task.sleep(for: .milliseconds(50))
            let owner = OfflineOwner(serverID: cabin.id, userID: 7, baseURL: cabin.baseURL, apiLevel: 1)
            let before = second.server.seen.count
            second.itself.withLock { $0 = answer }

            // It fails as a server that can't be reached does: no status, so the download tries again later.
            let failure = await #expect(throws: APIError.self) { _ = try await h.services.offline.token(owner, downloadID: "d1") }
            #expect(failure?.status == nil)
            let file = URL(string: cabin.baseURL.absoluteString + "/api/offline/downloads/d1/file")!
            await #expect(throws: APIError.self) { _ = try await h.services.offline.authorizedRequest(owner, url: file) }

            let after = Array(second.server.seen.dropFirst(before))
            #expect(!after.isEmpty && after.allSatisfy { route($0) == "GET /api/server" }, "only the question reached it")
            #expect(after.allSatisfy { signed($0) == nil }, "and nothing it was sent carried a sign-in")
            #expect(h.services.offline.signedIn(owner), "the sign-in is kept: the server may be back")
        }
    }

    /// An original file and its subtitles are fetched with the owner's
    /// sign-in: the request is made only once the server has answered.
    @Test func aDownloadsSignedFetchAsksItsServerFirst() async throws {
        let (second, cabin, owner) = try await signInOnBoth()
        let before = second.server.seen.count
        let file = URL(string: cabin.baseURL.absoluteString + "/api/offline/downloads/d1/file")!
        let request = try #require(try await h.services.offline.authorizedRequest(owner, url: file))
        #expect(signed(request) == "Bearer tok-cabin")
        let after = Array(second.server.seen.dropFirst(before))
        #expect(after.map(route) == ["GET /api/server"])
        #expect(signed(after[0]) == nil)

        // An address that isn't on its server gets no request, and the server isn't asked for it.
        let elsewhere = try await h.services.offline.authorizedRequest(owner, url: URL(string: "https://elsewhere.example.net/api/offline/downloads/d1/file")!)
        #expect(elsewhere == nil)
        #expect(second.server.seen.count == before + 1)
    }

    /// A question already out is shared by whoever asks next.
    @Test func callsAtOnceShareOneQuestion() async throws {
        let (second, _, owner) = try await signInOnBoth()
        let gate = ReplyGate()
        second.hold.withLock { $0 = gate }
        let before = second.server.seen.count
        let offline = h.services.offline
        let first = Task { try await offline.token(owner, downloadID: "d1") }
        let next = Task { try await offline.token(owner, downloadID: "d1") }
        #expect(await eventually { second.requests("GET /api/server").count == 1 })
        try await Task.sleep(for: .milliseconds(50))
        await gate.release()
        _ = try await first.value
        _ = try await next.value
        let after = Array(second.server.seen.dropFirst(before))
        #expect(after.filter { route($0) == "GET /api/server" }.count == 1)
        #expect(after.filter { route($0) == "POST /api/offline/downloads/d1/token" }.count == 2)
    }

    // MARK: An operation that outlives a switch

    /// What began on one server and is still sending when another comes
    /// into use (a setting being saved, a player's last report) holds the
    /// first server's API: that server is asked before its sign-in goes.
    @Test func anOperationThatOutlivesASwitchAsksTheServerItBeganOn() async throws {
        let (_, cabin, _) = try await signInOnBoth()
        let began = try h.services.store.signedIn().api
        h.router.always("GET /api/home", .json(Fixtures.home))
        _ = try await began.call { try await $0.getHome().ok.body.json }
        #expect(h.router.requests("GET /api/server").isEmpty, "in use, it isn't asked")

        try await h.services.servers.select(cabin)
        let before = h.server.seen.count
        _ = try await began.call { try await $0.getHome().ok.body.json }
        let after = Array(h.server.seen.dropFirst(before))
        #expect(after.map(route) == ["GET /api/server", "GET /api/home"])
        #expect(after.map(signed) == [nil, "Bearer tok-viewer"])
    }

    @Test func anOperationThatOutlivesASwitchSendsNoSignInToAnotherServerOrToNothing() async throws {
        for answer in [Reply.json(Second.info(id: "srv-somebody-elses")), Reply.problem(502, "bad gateway")] {
            let h = Harness()
            try await h.signIn()
            let second = Second()
            let cabin = Server(id: "srv-cabin", name: "Cabin server", baseURL: second.server.baseURL, version: "1.0.0", apiLevel: 1)
            try await h.services.servers.select(cabin)
            _ = try await h.services.auth.signIn(name: "sam", password: "fixture-sam-1")
            try await h.services.servers.select(h.testServer)
            #expect(await eventually { !h.router.requests("GET /api/me/preferences").isEmpty })
            let began = try h.services.store.signedIn().api
            h.router.always("GET /api/home", .json(Fixtures.home))

            try await h.services.servers.select(cabin)
            try await Task.sleep(for: .milliseconds(50))
            h.router.always("GET /api/server", answer)
            let before = h.server.seen.count
            let failure = await #expect(throws: APIError.self) { _ = try await began.call { try await $0.getHome().ok.body.json } }
            #expect(failure?.status == nil)
            // A plain fetch and a request that can't wait are no different.
            await #expect(throws: APIError.self) { _ = try await began.data(began.url("/api/images/w342/a.jpg")) }
            #expect(signed(began.authorizedRequest(began.url("/api/images/w342/a.jpg"))) == nil)

            let after = Array(h.server.seen.dropFirst(before))
            #expect(!after.isEmpty && after.allSatisfy { route($0) == "GET /api/server" }, "only the question reached it")
            #expect(after.allSatisfy { signed($0) == nil })
            #expect(h.router.requests("GET /api/home").isEmpty)
        }
    }

    // MARK: The server in use

    @Test func theServerInUseIsNeverAsked() async throws {
        try await h.signIn()
        let owner = try #require(h.services.offline.owner())
        h.router.always("GET /api/home", .json(Fixtures.home))
        h.router.always("GET /api/search", .json(#"{"movies":[],"shows":[],"episodes":[]}"#))
        h.router.always("POST /api/offline/downloads/d1/token", .json(#"{"token":"o1.abc","expiresAt":"2099-01-01T00:00:00Z","param":"token"}"#))
        _ = try await h.services.library.home()
        _ = try await h.services.library.search("harbor")
        _ = try await h.services.offline.token(owner, downloadID: "d1")
        let file = URL(string: h.router.baseURL.absoluteString + "/api/offline/downloads/d1/file")!
        #expect(signed(try #require(try await h.services.offline.authorizedRequest(owner, url: file))) == "Bearer tok-viewer")
        let api = try h.services.store.signedIn().api
        #expect(signed(api.authorizedRequest(api.url("/api/events"))) == "Bearer tok-viewer")
        #expect(h.router.requests("GET /api/server").isEmpty)
        #expect(h.router.requests("GET /api/home").allSatisfy { signed($0) == "Bearer tok-viewer" })
    }
}

/// The signer by itself: what a check that doesn't stand changes.
struct SignInCheckSignerTests {
    private let base = URL(string: "http://192.0.2.31:27587")!

    private final class Asked: Sendable {
        let count = Mutex(0)
        let fails = Mutex(false)
    }

    private func signer(stands: Bool, _ asked: Asked) -> RequestSigner {
        RequestSigner(baseURL: base, token: "tok", userAgent: "WatchMyMedia/1.0", check: SignInCheck(stands: { stands }, ask: {
            asked.count.withLock { $0 += 1 }
            if asked.fails.withLock({ $0 }) { throw APIError(status: nil, message: "Cabin server didn’t answer at the address kept for it.") }
        }))
    }

    @Test func aRequestThatCannotWaitCarriesTheTokenOnlyWhileTheAnswerStands() {
        let asked = Asked()
        let url = base.appending(path: "api/images/w342/a.jpg")
        #expect(signer(stands: true, asked).request(url).value(forHTTPHeaderField: "Authorization") == "Bearer tok")
        let waiting = signer(stands: false, asked)
        #expect(waiting.request(url).value(forHTTPHeaderField: "Authorization") == nil)
        #expect(waiting.request(url).value(forHTTPHeaderField: "User-Agent") == "WatchMyMedia/1.0", "it still goes, unsigned")
        #expect(waiting.signedRequest(url) == nil)
        #expect(waiting.authorization(path: "/api/home", server: base, operationID: "getHome") == nil)
        #expect(asked.count.withLock { $0 } == 0, "nothing was asked for it")
    }

    @Test func aRequestThatCanWaitAsksFirst() async throws {
        let asked = Asked()
        let url = base.appending(path: "api/home")
        let waiting = signer(stands: false, asked)
        #expect(try await waiting.askedRequest(url).value(forHTTPHeaderField: "Authorization") == "Bearer tok")
        #expect(asked.count.withLock { $0 } == 1)
        #expect(try await waiting.asked(path: "/api/home", server: base, operationID: "getHome"))
        #expect(asked.count.withLock { $0 } == 2)

        // Nothing to ask for: an address that isn't its server's, the password sign-in, a server whose answer stands.
        #expect(try await !waiting.asked(for: URL(string: "https://image.tmdb.org/t/p/w342/a.jpg")!))
        #expect(try await !waiting.asked(path: "/api/auth/login", server: base, operationID: "login"))
        #expect(try await !signer(stands: true, asked).asked(for: url))
        #expect(asked.count.withLock { $0 } == 2)

        asked.fails.withLock { $0 = true }
        await #expect(throws: APIError.self) { _ = try await waiting.askedRequest(url) }
    }
}
