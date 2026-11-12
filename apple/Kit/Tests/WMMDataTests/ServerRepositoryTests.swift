import Foundation
import Synchronization
import Testing
@testable import WMMData
import WMMModel

@MainActor
@Suite(.timeLimit(.minutes(1)))
struct ServerRepositoryTests {
    /// How long one address may take. Long enough for a stubbed answer on a
    /// busy machine: at 400 ms, GitHub's three-core runners timed out answers
    /// that were on their way, with the rest of the tests running beside them.
    static let probeTimeout = Duration.seconds(3)
    let h = Harness(probeTimeout: probeTimeout)
    var servers: any ServerRepository { h.services.servers }
    var host: String { h.server.host }

    @Test func probeAsksTheServerWhoItIs() async throws {
        h.router.on("GET /api/server", .json(Fixtures.serverInfo()))
        let found = try await servers.probe("\(host):27587")
        #expect(found == Server(id: "2321341d1a4a571c275b88b68f88d3c5", name: "Living room server", baseURL: h.router.baseURL, version: "0.9.3", apiLevel: 1))
        #expect(try h.router.last("GET /api/server").value(forHTTPHeaderField: "Authorization") == nil, "probing needs no sign-in")
        #expect(servers.current == nil, "probing does not choose")
    }

    @Test func aServerThatWantsABrowserCheckSaysSoAndIsRememberedThatWay() async throws {
        h.router.on("GET /api/server", .json(Fixtures.serverInfo(turnstile: true)))
        let found = try await servers.probe(host)
        #expect(found.turnstile)
        try await servers.select(found)
        #expect(servers.current?.server.turnstile == true)
    }

    @Test func probeRefusesAServerThatIsTooOld() async throws {
        h.router.on("GET /api/server", .json(Fixtures.serverInfo(apiLevel: 0)))
        let error = await #expect(throws: ServerTooOldError.self) { try await servers.probe(host) }
        #expect(error?.server.name == "Living room server")
        #expect(error?.displayMessage.contains("0.9.3") == true)
    }

    @Test func selectRefusesAServerThatIsTooOld() async {
        var old = h.testServer
        old.apiLevel = 0
        await #expect(throws: ServerTooOldError(server: old)) { try await servers.select(old) }
        #expect(servers.current == nil)
        #expect(servers.saved.isEmpty)
    }

    @Test func probeSaysWhenNothingAnswersAndWhenSomethingElseDoes() async throws {
        let nowhere = "nowhere-\(UUID().uuidString.lowercased()).local"
        let unreachable = await #expect(throws: APIError.self) { try await servers.probe(nowhere) }
        #expect(unreachable?.message == "Couldn't reach \(nowhere). Check the address and that the server is running.")

        h.router.on("GET /api/server", Reply(status: 200, body: Data("<html>router</html>".utf8)))
        let other = await #expect(throws: APIError.self) { try await servers.probe(" \(host) ") }
        #expect(other?.message == "\(host) doesn't look like a WatchMyMedia server.")

        h.router.on("GET /api/server", .problem(404, "not found"))
        let missing = await #expect(throws: APIError.self) { try await servers.probe(host) }
        #expect(missing?.message.contains("doesn't look like a WatchMyMedia server") == true)
    }

    @Test func probeTriesTheNextAddressWhenTheFirstCannotConnect() async throws {
        // A domain is tried over https first, then on the server's port.
        let domain = "probe-\(UUID().uuidString.lowercased()).example"
        StubURLProtocol.register(host: domain) { request in
            guard request.url?.scheme == "http", request.url?.port == 27587 else { throw URLError(.cannotConnectToHost) }
            return (200, ["Content-Type": "application/json"], Data(Fixtures.serverInfo().utf8))
        }
        defer { StubURLProtocol.unregister(host: domain) }
        let found = try await servers.probe(domain)
        #expect(found.baseURL.absoluteString == "http://\(domain):27587")
    }

    @Test func probeGivesUpOnAnAddressThatHangsAndTriesTheNext() async throws {
        // "http://host" tries the server's port, then port 80; the first never answers.
        let hanging = "http://\(host):27587"
        HangingURLProtocol.hang(hanging)
        defer { HangingURLProtocol.release(hanging) }
        h.router.on("GET /api/server", .json(Fixtures.serverInfo()))
        let start = ContinuousClock.now
        let found = try await servers.probe("http://\(host)")
        #expect(found.baseURL.absoluteString == "http://\(host)")
        #expect(ContinuousClock.now - start >= Self.probeTimeout)
    }

    @Test func probeTimesOutWhenNothingAnswersInTime() async throws {
        let hanging = "http://\(host):27587"
        HangingURLProtocol.hang(hanging)
        defer { HangingURLProtocol.release(hanging) }
        let error = await #expect(throws: APIError.self) { try await servers.probe(host) }
        #expect(error?.message == "Couldn't reach \(host). Check the address and that the server is running.")
    }

    @Test func probeRefusesNonsense() async {
        await #expect(throws: APIError.self) { try await servers.probe("my nas") }
    }

    @Test func selectRemembersServersMostRecentFirstAndForgetDropsOne() async throws {
        let a = Server(id: "a", name: "A", baseURL: URL(string: "http://10.0.0.1:27587")!, version: "1", apiLevel: 1)
        let b = Server(id: "b", name: "B", baseURL: URL(string: "http://10.0.0.2:27587")!, version: "1", apiLevel: 1)
        h.clock.now = Date(timeIntervalSince1970: 1_000)
        try await servers.select(a)
        h.clock.now = Date(timeIntervalSince1970: 2_000)
        try await servers.select(b)
        #expect(servers.saved.map(\.server.id) == ["b", "a"])
        #expect(servers.current?.server.id == "b")
        #expect(servers.current?.lastUsedAt == Date(timeIntervalSince1970: 2_000))

        // The same server at a new address moves to the front and keeps its ID.
        h.clock.now = Date(timeIntervalSince1970: 3_000)
        var moved = a
        moved.baseURL = URL(string: "http://10.0.0.9:27587")!
        try await servers.select(moved)
        #expect(servers.saved.map(\.server.id) == ["a", "b"])
        #expect(servers.current?.server.baseURL.absoluteString == "http://10.0.0.9:27587")

        let restarted = h.reopen()
        #expect(restarted.servers.current?.server.id == "a")
        #expect(restarted.servers.saved.map(\.server.id) == ["a", "b"])

        await restarted.servers.forget(serverID: "a")
        #expect(restarted.servers.current == nil)
        #expect(restarted.servers.saved.map(\.server.id) == ["b"])
        #expect(h.reopen().servers.saved.map(\.server.id) == ["b"])
    }

    @Test func choosingAServerAgainRestoresItsSignIn() async throws {
        try await h.signIn()
        let other = Server(id: "other", name: "Other", baseURL: URL(string: "http://10.0.0.2:27587")!, version: "1", apiLevel: 1)
        try await servers.select(other)
        #expect(h.services.auth.user == nil)
        #expect(servers.current?.signedIn == false)
        #expect(servers.saved.first { $0.server.id == "srv-test" }?.signedIn == true)

        try await servers.select(h.testServer)
        #expect(h.services.auth.user == User(id: 2, name: "viewer", isAdmin: false))
        #expect(servers.current?.signedIn == true)
        #expect(servers.current?.userName == "viewer")
        #expect(h.reopen().auth.user?.name == "viewer")
    }

    @Test func aSignInKeptFromBeforeAReinstallComesBackWhenItsServerIsChosen() async throws {
        h.storage.credentials["srv-test"] = StoredCredential(user: User(id: 2, name: "viewer", isAdmin: false), sessionID: "s1", token: "tok-old", baseURL: h.testServer.baseURL)
        let services = h.reopen()
        #expect(services.servers.saved.isEmpty)
        try await services.servers.select(h.testServer)
        #expect(services.auth.user?.name == "viewer")
        #expect(services.servers.current?.userName == "viewer")
        h.router.on("GET /api/auth/sessions", .json(Fixtures.sessions))
        _ = try await services.auth.sessions(all: false)
        #expect(try h.router.last("GET /api/auth/sessions").value(forHTTPHeaderField: "Authorization") == "Bearer tok-old")
    }

    @Test func forgettingTheCurrentServerSignsOutThereToo() async throws {
        try await h.signIn()
        h.router.on("POST /api/auth/logout", .noContent)
        await servers.forget(serverID: h.testServer.id)
        #expect(h.services.auth.user == nil)
        #expect(!servers.saved.contains { $0.server.id == h.testServer.id })
        #expect(h.storage.credentials[h.testServer.id] == nil)
        #expect(await eventually { !h.router.requests("POST /api/auth/logout").isEmpty })
        #expect(try h.router.last("POST /api/auth/logout").value(forHTTPHeaderField: "Authorization") == "Bearer tok-viewer")
    }

    @Test func discoveryPublishesWhatItFindsAndADenial() async throws {
        let found = DiscoveredServer(id: "abc", name: "Living room server", baseURL: URL(string: "http://192.168.1.20:27587")!, version: "0.9.3", apiLevel: 1)
        servers.startDiscovery()
        #expect(h.discovery.running)
        h.discovery.announce([found])
        #expect(servers.discovered == [found])
        #expect(!servers.localNetworkDenied)
        h.discovery.deny()
        #expect(servers.localNetworkDenied)
        servers.stopDiscovery()
        #expect(!h.discovery.running)
    }

    @Test func discoveryRunsUntilEveryPickerHasStopped() {
        let found = DiscoveredServer(id: "abc", name: "Living room server", baseURL: URL(string: "http://192.168.1.20:27587")!, version: "0.9.3", apiLevel: 1)
        // The iPhone picker is on screen; the window widens and the iPad's sign-in screen starts before the picker stops.
        servers.startDiscovery()
        h.discovery.announce([found])
        servers.startDiscovery()
        #expect(servers.discovered == [found], "a second start keeps what was found")
        servers.stopDiscovery()
        #expect(h.discovery.running, "the new screen still browses")
        servers.stopDiscovery()
        #expect(!h.discovery.running)
        servers.stopDiscovery()
        servers.startDiscovery()
        #expect(h.discovery.running, "an extra stop does not unbalance the count")
    }

    @Test func discoveredServicesBecomeServerEntries() throws {
        let v4 = try #require(BonjourDiscovery.discoveredServer(
            serviceName: "Living room server", host: "192.168.1.20", ipv6: false, port: 27587,
            txt: ["id": "abc", "version": "0.9.3", "api": "1", "path": "/"]
        ))
        #expect(v4.id == "abc")
        #expect(v4.baseURL.absoluteString == "http://192.168.1.20:27587")
        #expect(v4.version == "0.9.3")
        #expect(v4.apiLevel == 1)

        let v6 = try #require(BonjourDiscovery.discoveredServer(serviceName: "NAS", host: "fd00::5%en0", ipv6: true, port: 8080, txt: ["path": "/wmm/", "api": "x"]))
        #expect(v6.baseURL.absoluteString == "http://[fd00::5]:8080/wmm")
        #expect(v6.id == "NAS", "no id: the service name")
        #expect(v6.apiLevel == 0)
    }

    // MARK: - The LAN address, for AirPlay

    /// Counts the requests a stub host gets.
    final class Hits: Sendable {
        let count = Mutex(0)
    }

    private func answers(_ host: String, id: String, hits: Hits = Hits()) {
        StubURLProtocol.register(host: host) { request in
            hits.count.withLock { $0 += 1 }
            guard request.url?.path == "/api/server" else { throw URLError(.badURL) }
            let info = #"{"id":"\#(id)","name":"Living room server","version":"0.9.3","apiLevel":2,"setupRequired":false,"turnstile":false}"#
            return (200, ["Content-Type": "application/json"], Data(info.utf8))
        }
    }

    /// The test server, reached over Tailscale.
    private func selectOnTheTailnet() async throws {
        var remote = h.testServer
        remote.baseURL = URL(string: "https://studio.tail1234.ts.net")!
        try await servers.select(remote)
    }

    private func found(_ id: String, _ address: String) -> DiscoveredServer {
        DiscoveredServer(id: id, name: "Living room server", baseURL: URL(string: address)!, version: "0.9.3", apiLevel: 2)
    }

    @Test func aDiscoveredPublicIDDoesNotAuthorizeForwardingTokensToTheLAN() async throws {
        try await selectOnTheTailnet()
        servers.startDiscovery()
        h.discovery.announce([found(h.testServer.id, "http://10.77.1.3:27587")])
        #expect(await servers.localAddress() == nil)
        servers.stopDiscovery()
    }

    @Test func theServerInUseIsAskedAgainAfterAnUpgrade() async throws {
        var before = h.testServer
        before.id = "2321341d1a4a571c275b88b68f88d3c5"
        before.apiLevel = 2
        try await servers.select(before)
        h.router.on("GET /api/server", .json(Fixtures.serverInfo(apiLevel: 3)))
        await servers.refreshCurrent()
        let now = try #require(servers.current?.server)
        #expect(now.apiLevel == 3)
        #expect(now.version == "0.9.3")
        #expect(now.baseURL == before.baseURL)
        #expect(servers.saved.count == 1)
        #expect(h.reopen().servers.current?.server.apiLevel == 3)
    }

    @Test func aRefreshLeavesTheServerAsItWasWhenAnotherAnswersOrNoneDoes() async throws {
        try await servers.select(h.testServer)
        // Another server at the address.
        h.router.on("GET /api/server", .json(Fixtures.serverInfo(apiLevel: 3)))
        await servers.refreshCurrent()
        #expect(servers.current?.server == h.testServer)
        // Nothing there.
        h.router.on("GET /api/server", .problem(503, "Down for maintenance"))
        await servers.refreshCurrent()
        #expect(servers.current?.server == h.testServer)
    }

    @Test func aServerInUseOnTheLANIsItsOwnLANAddress() async throws {
        var home = h.testServer
        home.baseURL = URL(string: "http://10.77.3.2:27587")!
        try await servers.select(home)
        #expect(await servers.localAddress() == home.baseURL)
        #expect(!h.discovery.running, "no need to browse")
    }
}
