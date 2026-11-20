import Foundation
import Synchronization
import Testing
@testable import WMMData
import WMMModel

/// Two servers signed in to at once: search across them and their pictures
/// go to each server's own address with its own sign-in and never another's,
/// and removing the one that isn't in use does what signing out of the one
/// in use does.
@MainActor
struct SeveralServersTests {
    let h = Harness()

    /// What the second server answers; a test changes it.
    final class Second: Sendable {
        let server = StubServer()
        let searchStatus = Mutex(200)

        init() {
            server.respond { [weak self] method, path, _ in
                switch "\(method) \(path)" {
                case "POST /api/auth/login":
                    return (200, Data(#"{"id":7,"name":"sam","isAdmin":true,"sessionId":"5c1e0d90b273fc14","token":"tok-cabin","expiresAt":"2027-10-29T17:09:01Z"}"#.utf8))
                case "GET /api/auth/me": return (200, Data(#"{"id":7,"name":"sam","isAdmin":true}"#.utf8))
                case "GET /api/me/preferences": return (200, Data(Fixtures.preferences.utf8))
                case "GET /api/search":
                    let status = self?.searchStatus.withLock { $0 } ?? 200
                    return status == 200 ? (200, Data(#"{"movies":[],"shows":[],"episodes":[]}"#.utf8)) : (status, Data(#"{"title":"Error","status":401,"detail":"sign in required"}"#.utf8))
                case "GET /api/images/w342/far.jpg": return (200, Data([7, 7]))
                case "POST /api/auth/logout", "POST /api/offline/sync": return (204, nil)
                default: return (404, nil)
                }
            }
        }

        func requests(_ route: String) -> [URLRequest] {
            server.seen.filter { "\($0.httpMethod ?? "GET") \($0.url?.path ?? "")" == route }
        }
    }

    /// Signs in on the test server, then on a second one, and comes back:
    /// both are signed in and the test server is the one in use.
    private func signInOnBoth() async throws -> (second: Second, cabin: Server) {
        try await h.signIn()
        let second = Second()
        let cabin = Server(id: "srv-cabin", name: "Cabin server", baseURL: second.server.baseURL, version: "1.0.0", apiLevel: 1)
        try await h.services.servers.select(cabin)
        _ = try await h.services.auth.signIn(name: "sam", password: "fixture-sam-1")
        try await h.services.servers.select(h.testServer)
        #expect(h.services.auth.user?.name == "viewer")
        #expect(h.services.servers.saved.filter(\.signedIn).map(\.server.id).sorted() == ["srv-cabin", "srv-test"])
        return (second, cabin)
    }

    private func authorizations(_ requests: [URLRequest]) -> Set<String> {
        Set(requests.compactMap { $0.value(forHTTPHeaderField: "Authorization") })
    }

    @Test func searchOnAnotherServerGoesToItsAddressWithItsSignIn() async throws {
        let (second, cabin) = try await signInOnBoth()
        let found = try await h.services.library.search("harbor", serverID: cabin.id)
        #expect(found.isEmpty)
        let asked = try #require(second.requests("GET /api/search").last)
        #expect(query(asked)["q"] == "harbor")
        #expect(asked.value(forHTTPHeaderField: "Authorization") == "Bearer tok-cabin")
        #expect(h.router.requests("GET /api/search").isEmpty, "the server in use wasn't asked")

        // The server in use, asked the usual way, gets its own.
        h.router.on("GET /api/search", .json(#"{"movies":[],"shows":[],"episodes":[]}"#))
        _ = try await h.services.library.search("harbor")
        #expect(try h.router.last("GET /api/search").value(forHTTPHeaderField: "Authorization") == "Bearer tok-viewer")

        // Neither server ever saw the other's sign-in.
        #expect(!authorizations(second.server.seen).contains("Bearer tok-viewer"))
        #expect(!authorizations(h.server.seen).contains("Bearer tok-cabin"))
    }

    @Test func aServerThatIsNotSignedInToIsNotAsked() async throws {
        try await h.signIn()
        let other = StubServer()
        other.respond { _, _, _ in (200, Data(#"{"movies":[],"shows":[],"episodes":[]}"#.utf8)) }
        // Chosen once, never signed in to.
        try await h.services.servers.select(Server(id: "srv-other", name: "Other", baseURL: other.baseURL, version: "1.0.0", apiLevel: 1))
        try await h.services.servers.select(h.testServer)
        await #expect(throws: SignedOutError()) { _ = try await h.services.library.search("harbor", serverID: "srv-other") }
        await #expect(throws: SignedOutError()) { _ = try await h.services.library.search("harbor", serverID: "never-heard-of") }
        #expect(other.seen.isEmpty, "nothing is sent where there is no sign-in to send")
    }

    @Test func aRefusalOnAnotherServerSignsOutThereAndLeavesTheOneInUse() async throws {
        let (second, cabin) = try await signInOnBoth()
        second.searchStatus.withLock { $0 = 401 }
        await #expect(throws: SignedOutError()) { _ = try await h.services.library.search("harbor", serverID: cabin.id) }
        #expect(h.services.servers.saved.first { $0.server.id == cabin.id }?.signedIn == false, "it stays on the list, signed out")
        #expect(h.services.auth.user?.name == "viewer")
        #expect(h.services.servers.current?.server.id == "srv-test")
    }

    @Test func aPictureIsSignedForTheServerItIsOn() async throws {
        let (second, cabin) = try await signInOnBoth()
        let far = try #require(h.services.library.imageURL("/far.jpg", size: .w342, serverID: cabin.id))
        #expect(far.absoluteString == "\(second.server.baseURL.absoluteString)/api/images/w342/far.jpg")
        #expect(try await h.services.images.data(for: far) == Data([7, 7]))
        #expect(second.requests("GET /api/images/w342/far.jpg").last?.value(forHTTPHeaderField: "Authorization") == "Bearer tok-cabin")

        h.router.always("GET /api/images/w342/near.jpg", Reply(status: 200, body: Data([1])))
        let near = try #require(h.services.library.imageURL("/near.jpg", size: .w342))
        #expect(try await h.services.images.data(for: near) == Data([1]))
        #expect(try h.router.last("GET /api/images/w342/near.jpg").value(forHTTPHeaderField: "Authorization") == "Bearer tok-viewer")

        // A picture anywhere else carries no sign-in at all.
        let elsewhere = StubServer()
        elsewhere.respond { _, _, _ in (200, Data([9])) }
        #expect(try await h.services.images.data(for: elsewhere.baseURL.appending(path: "x.jpg")) == Data([9]))
        #expect(elsewhere.seen.first?.value(forHTTPHeaderField: "Authorization") == nil)

        #expect(!authorizations(second.server.seen).contains("Bearer tok-viewer"))
        #expect(!authorizations(h.server.seen).contains("Bearer tok-cabin"))
        #expect(h.services.library.imageURL("/far.jpg", size: .w342, serverID: "never-heard-of") == nil)
    }

    /// Removing a server that isn't in use: its downloads' report runs while
    /// its session stands, then its sign-in goes (which the downloads hear,
    /// to delete what they hold from it), and its session is ended there.
    @Test func removingAServerThatIsNotInUseDoesWhatSigningOutDoes() async throws {
        let (second, cabin) = try await signInOnBoth()
        final class Heard {
            var owners: [OfflineOwner] = []
            var signedInDuringHook: [Bool] = []
            var changes = 0
        }
        let heard = Heard()
        let offline = h.services.offline
        offline.beforeSignOut { owner in
            heard.owners.append(owner)
            heard.signedInDuringHook.append(offline.signedIn(owner))
        }
        offline.onSessionChange { heard.changes += 1 }

        await h.services.servers.forget(serverID: cabin.id)

        let owner = OfflineOwner(serverID: cabin.id, userID: 7, baseURL: cabin.baseURL, apiLevel: 1)
        #expect(heard.owners == [owner], "the report is for the server being removed, not the one in use")
        #expect(heard.signedInDuringHook == [true], "while its session still stands")
        #expect(!offline.signedIn(owner))
        #expect(heard.changes == 1, "the downloads hear that a sign-in went, though the one in use didn't change")
        #expect(!h.services.servers.saved.contains { $0.server.id == cabin.id })
        #expect(h.storage.credentials[cabin.id] == nil)
        #expect(h.services.servers.current?.server.id == "srv-test")
        #expect(h.services.auth.user?.name == "viewer", "the server in use stays signed in")
        #expect(await eventually { !second.requests("POST /api/auth/logout").isEmpty })
        #expect(second.requests("POST /api/auth/logout").last?.value(forHTTPHeaderField: "Authorization") == "Bearer tok-cabin")
        #expect(h.router.requests("POST /api/auth/logout").isEmpty)
        #expect(h.reopen().servers.saved.map(\.server.id) == ["srv-test"])
    }

    @Test func signingOutOfTheServerInUseReportsItsOwnAccount() async throws {
        let (_, cabin) = try await signInOnBoth()
        final class Heard { var owners: [OfflineOwner] = [] }
        let heard = Heard()
        h.services.offline.beforeSignOut { heard.owners.append($0) }
        h.router.on("POST /api/auth/logout", .noContent)
        await h.services.auth.signOut()
        #expect(heard.owners.map(\.serverID) == ["srv-test"])
        #expect(h.services.auth.user == nil)
        #expect(h.services.servers.saved.first { $0.server.id == cabin.id }?.signedIn == true, "the other server keeps its sign-in")
    }
}
