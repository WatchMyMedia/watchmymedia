import Foundation
import Testing
@testable import WMMFeatures
import WMMFakes
import WMMModel

@MainActor
@Suite struct ServerPickerModelTests {
    init() { FakeLatency.duration = .zero }

    @Test func listsFoundServersWithTheirSignInAndRememberedOnesNotFound() {
        let servers = FakeServerRepository()
        let model = ServerPickerModel(servers: servers)
        model.startDiscovery()

        #expect(model.found.map(\.name) == ["Living room server", "Office Mac mini"])
        #expect(model.found[0].detail == "192.168.1.20:27587 · v0.9.3 · signed in as Sam")
        #expect(model.found[0].current)
        #expect(model.found[1].detail == "office-mini.local:27587 · v0.9.1 · not signed in")
        #expect(!model.found[1].current)
        #expect(model.remembered.map(\.name) == ["home.watchmy.media", "Old NAS"])
        #expect(model.remembered[0].detail == "Last used 3 days ago · not signed in")
        #expect(model.remembered[0].kind == .remembered)
        #expect(!model.remembered[0].tooOld)
    }

    @Test func aRememberedServerTooOldSaysSoAndIsRefused() async throws {
        let servers = FakeServerRepository(signedIn: false, chosen: false)
        let model = ServerPickerModel(servers: servers)
        let old = try #require(model.remembered.first { $0.name == "Old NAS" })
        #expect(old.tooOld)
        #expect(old.detail == "nas.local:27587 · v0.6.0 · too old for this app")
        #expect(await !model.choose(old))
        #expect(model.error == "Old NAS runs version 0.6.0, which is too old for this app. Update the server.")
        #expect(servers.current == nil)
    }

    @Test func aServerTooOldIsMarkedInTheList() {
        let servers = TestServers()
        servers.discovered = [DiscoveredServer(id: "old", name: "Attic NAS", baseURL: URL(string: "http://192.168.1.9:27587")!, version: "0.0.9", apiLevel: 0)]
        let model = ServerPickerModel(servers: servers)
        #expect(model.found[0].tooOld)
        #expect(model.found[0].detail == "192.168.1.9:27587 · v0.0.9 · too old for this app")
    }

    @Test func aTaggedVersionHasOneV() {
        let servers = TestServers()
        servers.discovered = [DiscoveredServer(id: "new", name: "Den", baseURL: URL(string: "http://192.168.1.30:27587")!, version: "v0.4.1", apiLevel: 2)]
        #expect(ServerPickerModel(servers: servers).found[0].detail == "192.168.1.30:27587 · v0.4.1 · not signed in")
    }

    @Test func discoveryStartsAndStopsWithTheScreen() {
        let servers = TestServers()
        let model = ServerPickerModel(servers: servers)
        model.startDiscovery()
        model.startDiscovery()
        #expect(servers.starts == 1)
        #expect(model.discovering)
        model.stopDiscovery()
        model.stopDiscovery()
        #expect(servers.stops == 1)
        #expect(!model.discovering)
        model.startDiscovery()
        #expect(servers.starts == 2)
    }

    @Test func resizingARetainedPickerKeepsDiscoveryUntilBothViewsLeave() {
        let servers = TestServers()
        let model = ServerPickerModel(servers: servers)
        let wide = UUID(), compact = UUID()
        model.startDiscovery(owner: wide)
        model.startDiscovery(owner: compact)
        model.stopDiscovery(owner: wide)
        #expect(model.discovering)
        #expect(servers.starts == 1)
        #expect(servers.stops == 0)
        model.stopDiscovery(owner: compact)
        #expect(!model.discovering)
        #expect(servers.stops == 1)
    }

    @Test func choosingAFoundServerProbesThenSelectsIt() async {
        let servers = FakeServerRepository(signedIn: false, chosen: false)
        let model = ServerPickerModel(servers: servers)
        model.startDiscovery()
        #expect(!model.canGoBack)

        let chosen = await model.choose(model.found[1])
        #expect(chosen)
        #expect(servers.current?.server.id == FakeServers.officeMini.id)
        #expect(model.connecting == nil)
        #expect(model.error == nil)
        #expect(model.canGoBack)
    }

    @Test func aTypedAddressConnectsAndClearsTheField() async {
        let servers = FakeServerRepository(signedIn: false, chosen: false)
        let model = ServerPickerModel(servers: servers)
        model.address = "home.watchmy.media"
        #expect(await model.connectToAddress())
        #expect(servers.current?.server.id == FakeServers.remoteHome.id)
        #expect(model.address == "")
        #expect(model.addressError == nil)
    }

    @Test func anEmptyAddressAsksForOne() async {
        let servers = TestServers()
        let model = ServerPickerModel(servers: servers)
        model.address = "   "
        #expect(await !model.connectToAddress())
        #expect(model.addressError == "Enter the server's address.")
        #expect(servers.probed.isEmpty)
    }

    @Test func aBadAddressSaysSoUnderTheFieldAndTypingClearsIt() async {
        let model = ServerPickerModel(servers: FakeServerRepository(chosen: false))
        model.address = "not an address"
        #expect(await !model.connectToAddress())
        #expect(model.addressError == "That doesn't look like a server address.")
        #expect(model.error == nil)
        model.address = "192.168.1.20"
        #expect(model.addressError == nil)
    }

    @Test func aServerTooOldIsRefusedWithItsVersion() async {
        let servers = TestServers()
        let old = Server(id: "old", name: "Attic NAS", baseURL: URL(string: "http://192.168.1.9:27587")!, version: "0.0.9", apiLevel: 0)
        servers.probeResult = .success(old)
        let model = ServerPickerModel(servers: servers)
        model.address = "192.168.1.9"
        #expect(await !model.connectToAddress())
        #expect(model.addressError == "Attic NAS runs version 0.0.9, which is too old for this app. Update the server.")
        #expect(servers.selected.isEmpty)
        #expect(servers.current == nil)
    }

    @Test func anUnreachableListedServerSaysSoAboveTheList() async {
        let servers = TestServers()
        servers.discovered = [DiscoveredServer(id: "srv-office", name: "Office Mac mini", baseURL: URL(string: "http://office-mini.local:27587")!, version: "0.9.1", apiLevel: 2)]
        servers.probeResult = .failure(URLError(.timedOut))
        let model = ServerPickerModel(servers: servers)
        #expect(await !model.choose(model.found[0]))
        #expect(servers.probed == ["http://office-mini.local:27587"])
        #expect(model.error == "Couldn't reach Office Mac mini. Check the address, and that the server is running and on this network.")
        #expect(model.addressError == nil)
        model.dismissError()
        #expect(model.error == nil)
    }

    @Test func theServersOwnWordsAreShownWhenItSentSome() async {
        let servers = TestServers()
        servers.probeResult = .failure(APIError(status: nil, message: "This address needs https."))
        let model = ServerPickerModel(servers: servers)
        model.address = "http://home.example.net"
        #expect(await !model.connectToAddress())
        #expect(model.addressError == "This address needs https.")
    }

    @Test func forgettingARememberedServerRemovesIt() async {
        let servers = FakeServerRepository()
        let model = ServerPickerModel(servers: servers)
        model.startDiscovery()
        await model.forget(model.remembered[0])
        #expect(model.remembered.map(\.name) == ["Old NAS"])
        #expect(!servers.saved.contains { $0.server.id == FakeServers.remoteHome.id })
    }

    @Test func plainHTTPToAHostNameGetsTheHTTPSNote() {
        #expect(needsHTTPSNudge("http://home.example.net"))
        #expect(needsHTTPSNudge("HTTP://home.example.net:8080/watch"))
        #expect(!needsHTTPSNudge("http://192.168.1.20:27587"))
        #expect(!needsHTTPSNudge("http://nas.local"))
        #expect(!needsHTTPSNudge("https://home.example.net"))
        #expect(!needsHTTPSNudge("home.example.net"))
        let model = ServerPickerModel(servers: TestServers())
        model.address = "http://media.example.org"
        #expect(model.showsHTTPSNudge)
    }

    @Test func theLocalNetworkPermissionComesFromTheRepository() {
        let servers = TestServers()
        servers.localNetworkDenied = true
        #expect(ServerPickerModel(servers: servers).localNetworkDenied)
    }
}
