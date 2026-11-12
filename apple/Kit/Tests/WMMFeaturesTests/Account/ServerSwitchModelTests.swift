import Foundation
import Observation
import Testing
@testable import WMMFeatures
import WMMFakes
import WMMModel

@MainActor
@Suite struct ServerSwitchModelTests {
    @Test func theAppsFakesRestoreTheOriginalAccountWhenSignInIsCancelled() async throws {
        FakeLatency.duration = .zero
        let services = AppServices.fake(client: .fakePad)
        let model = try #require(ServerSwitchModel(servers: services.servers, auth: services.auth))
        model.picker.address = FakeServers.officeMini.baseURL.absoluteString
        await model.connectToAddress()
        #expect(model.phase == .signingIn)
        #expect(services.auth.user == nil)
        model.signIn?.name = "Robin"
        model.signIn?.password = "unfinished"
        await model.cancel()
        #expect(model.outcome == .cancelled)
        #expect(services.servers.current?.server == FakeServers.livingRoom)
        #expect(services.auth.user?.name == "Sam")
        #expect(services.servers.saved.first { $0.id == FakeServers.officeMini.id }?.signedIn == false)
    }

    @Test func theAppsFakesSignInAndSwitchBetweenRememberedAccounts() async throws {
        FakeLatency.duration = .zero
        let services = AppServices.fake(client: .fakePad)
        let model = try #require(ServerSwitchModel(servers: services.servers, auth: services.auth))
        model.picker.address = FakeServers.officeMini.baseURL.absoluteString
        await model.connectToAddress()
        let form = try #require(model.signIn)
        form.name = "Robin"
        form.password = "password"
        await form.signIn()
        model.accountChanged()
        #expect(model.outcome == .switched)
        #expect(services.auth.user?.name == "Robin")

        let returning = try #require(ServerSwitchModel(servers: services.servers, auth: services.auth))
        returning.picker.address = FakeServers.livingRoom.baseURL.absoluteString
        await returning.connectToAddress()
        #expect(returning.outcome == .switched)
        #expect(returning.signIn == nil)
        #expect(services.auth.user?.name == "Sam")
        #expect(services.servers.saved.first { $0.id == FakeServers.officeMini.id }?.signedIn == true)
    }

    @Test func startsOnlyForASignedInAccount() {
        let account = SwitchAccount()
        account.users = [:]
        #expect(ServerSwitchModel(servers: account, auth: account) == nil)
    }

    @Test func rememberedSignInFinishesWithoutAForm() async throws {
        let account = SwitchAccount()
        account.users[FakeServers.officeMini.baseURL] = viewer
        let model = try #require(ServerSwitchModel(servers: account, auth: account))
        model.picker.address = FakeServers.officeMini.baseURL.absoluteString
        await model.connectToAddress()
        #expect(model.outcome == .switched)
        #expect(model.signIn == nil)
        #expect(account.user == viewer)
        #expect(account.users[FakeServers.livingRoom.baseURL] == sam)
    }

    @Test func newServerWaitsForPasswordAndKeepsTheOriginalSignIn() async throws {
        let (account, model) = try fixture()
        await model.connectToAddress()
        #expect(model.phase == .signingIn)
        #expect(model.outcome == nil)
        #expect(account.user == nil)
        let form = try #require(model.signIn)
        form.name = "Robin"
        form.password = "password"
        await form.signIn()
        model.accountChanged()
        #expect(model.outcome == .switched)
        #expect(account.user == viewer)
        #expect(account.users[FakeServers.livingRoom.baseURL] == sam)
    }

    @Test func protectedServerKeepsPairingInTheSwitch() async throws {
        let (account, model) = try fixture()
        model.picker.address = FakeServers.remoteHome.baseURL.absoluteString
        await model.connectToAddress()
        #expect(model.signIn?.codeSignIn == true)
        #expect(model.outcome == nil)
        account.users[FakeServers.remoteHome.baseURL] = viewer
        model.accountChanged()
        #expect(model.outcome == .switched)
    }

    @Test func cancellingTheListDoesNotReselectOrSignOut() async throws {
        let (account, model) = try fixture()
        await model.cancel()
        #expect(model.outcome == .cancelled)
        #expect(account.selected.isEmpty)
        #expect(account.user == sam)
    }

    @Test func cancellingSignInRestoresTheOriginalAccountOffline() async throws {
        let (account, model) = try fixture()
        await model.connectToAddress()
        account.probeError = URLError(.notConnectedToInternet)
        await model.cancel()
        #expect(model.outcome == .cancelled)
        #expect(account.current?.server == FakeServers.livingRoom)
        #expect(account.user == sam)
        #expect(account.probed.count == 1)
    }

    @Test func backRestoresTheAccountAndOffersAnotherChoice() async throws {
        let (account, model) = try fixture()
        await model.connectToAddress()
        model.signIn?.password = "unfinished"
        await model.backToPicker()
        #expect(model.phase == .picking)
        #expect(model.signIn == nil)
        #expect(account.user == sam)
        model.picker.address = FakeServers.remoteHome.baseURL.absoluteString
        await model.connectToAddress()
        #expect(model.signIn?.codeSignIn == true)
        #expect(model.signIn?.password.isEmpty == true)
    }

    @Test func failedSelectionKeepsThePickerAndCanRetry() async throws {
        let (account, model) = try fixture()
        account.selectError = APIError(status: nil, message: "Selection failed.")
        await model.connectToAddress()
        #expect(model.phase == .picking)
        #expect(model.picker.addressError == "Selection failed.")
        #expect(account.user == sam)
        account.selectError = nil
        await model.connectToAddress()
        #expect(model.phase == .signingIn)
    }

    @Test func cancellationWaitsForAnInFlightProbe() async throws {
        let (account, model) = try fixture()
        account.holdProbe = true
        let connect = Task { await model.connectToAddress() }
        #expect(await eventually { account.probeGate != nil })
        await model.cancel()
        #expect(model.phase == .connecting)
        account.releaseProbe()
        await connect.value
        await model.cancel()
        #expect(model.outcome == .cancelled)
        #expect(account.user == sam)
    }

    @Test func signOutDuringAProbePreventsItsLateSelection() async throws {
        let (account, model) = try fixture()
        account.holdProbe = true
        let connect = Task { await model.connectToAddress() }
        #expect(await eventually { account.probeGate != nil })
        await account.signOut()
        model.accountChanged()
        account.releaseProbe()
        await connect.value
        #expect(model.outcome == .accountChanged)
        #expect(account.selected.isEmpty)
        #expect(account.user == nil)
    }

    @Test func externalAccountChangeDoesNotRestoreOverTheNewContext() async throws {
        let (account, model) = try fixture()
        await model.connectToAddress()
        try await account.select(FakeServers.remoteHome)
        model.accountChanged()
        await model.cancel()
        #expect(model.outcome == .accountChanged)
        #expect(account.current?.server == FakeServers.remoteHome)
    }

    @Test func returningToTheSameAccountDoesNotReviveAnInterruptedProbe() async throws {
        let (account, model) = try fixture()
        account.holdProbe = true
        let connect = Task { await model.connectToAddress() }
        #expect(await eventually { account.probeGate != nil })
        await account.signOut()
        model.accountChanged()
        account.users[FakeServers.livingRoom.baseURL] = sam
        model.accountChanged()
        account.releaseProbe()
        await connect.value
        #expect(model.outcome == .accountChanged)
        #expect(account.selected.isEmpty)
        #expect(account.user == sam)
    }

    @Test func anOriginalServerForgottenMeanwhileIsNotRecreated() async throws {
        let (account, model) = try fixture()
        await model.connectToAddress()
        await account.forget(serverID: FakeServers.livingRoom.id)
        await model.cancel()
        #expect(model.outcome == .accountChanged)
        #expect(!account.saved.contains { $0.id == FakeServers.livingRoom.id })
        #expect(account.current?.server == FakeServers.officeMini)
    }

    @Test func cancellationNeverMovesCredentialsToAChangedOrigin() async throws {
        let (account, model) = try fixture()
        await model.connectToAddress()
        var replacement = FakeServers.livingRoom
        replacement.baseURL = URL(string: "https://another.example.net")!
        account.entries[replacement.id] = replacement
        await model.cancel()
        #expect(model.outcome == .accountChanged)
        #expect(account.selected == [FakeServers.officeMini])
        #expect(account.users[replacement.baseURL] == nil)
    }

    @Test func cancellationDoesNotResurrectARevokedAccount() async throws {
        let (account, model) = try fixture()
        await model.connectToAddress()
        account.users[FakeServers.livingRoom.baseURL] = nil
        await model.cancel()
        #expect(model.outcome == .accountChanged)
        #expect(account.current?.server == FakeServers.livingRoom)
        #expect(account.user == nil)
    }

    @Test func failedRestorationShowsTheErrorAndAllowsRetry() async throws {
        let (account, model) = try fixture()
        await model.connectToAddress()
        account.selectError = APIError(status: nil, message: "Try again.")
        await model.cancel()
        #expect(model.phase == .signingIn)
        #expect(model.error?.contains("Try again.") == true)
        #expect(account.user == nil)
        account.selectError = nil
        await model.cancel()
        #expect(model.outcome == .cancelled)
        #expect(account.user == sam)
    }

    private func fixture() throws -> (SwitchAccount, ServerSwitchModel) {
        let account = SwitchAccount()
        let model = try #require(ServerSwitchModel(servers: account, auth: account))
        model.picker.address = FakeServers.officeMini.baseURL.absoluteString
        return (account, model)
    }
}

/// Unlike a loose auth stub, the current user follows the selected endpoint,
/// as LocalStore does. No credentials are manufactured by selecting a server.
@MainActor
@Observable
private final class SwitchAccount: ServerRepository, AuthRepository {
    var entries = Dictionary(uniqueKeysWithValues: [FakeServers.livingRoom, FakeServers.officeMini, FakeServers.remoteHome].map { ($0.id, $0) })
    var users: [URL: User] = [FakeServers.livingRoom.baseURL: sam]
    var currentID = FakeServers.livingRoom.id
    var selected: [Server] = []
    var probed: [String] = []
    var selectError: (any Error)?
    var probeError: (any Error)?
    var holdProbe = false
    var probeGate: CheckedContinuation<Void, Never>?
    var discovered: [DiscoveredServer] = []
    var localNetworkDenied = false
    var deviceName = "Sam’s iPad"

    var user: User? { entries[currentID].flatMap { users[$0.baseURL] } }
    var saved: [SavedServer] { entries.values.map(savedServer) }
    var current: SavedServer? { entries[currentID].map(savedServer) }
    private func savedServer(_ server: Server) -> SavedServer {
        SavedServer(server: server, userName: users[server.baseURL]?.name, signedIn: users[server.baseURL] != nil, lastUsedAt: .now)
    }

    func startDiscovery() {}
    func stopDiscovery() {}
    func refreshCurrent() async {}
    func localAddress() async -> URL? { nil }
    func probe(_ address: String) async throws -> Server {
        probed.append(address)
        if holdProbe { await withCheckedContinuation { probeGate = $0 } }
        if let probeError { throw probeError }
        return try #require(entries.values.first { $0.baseURL.absoluteString == address })
    }
    func releaseProbe() { probeGate?.resume(); probeGate = nil }
    func select(_ server: Server) async throws {
        if let selectError { throw selectError }
        selected.append(server)
        entries[server.id] = server
        currentID = server.id
    }
    func forget(serverID: String) async {
        if let server = entries.removeValue(forKey: serverID) { users[server.baseURL] = nil }
    }
    func signIn(name: String, password: String) async throws -> User {
        let server = try #require(entries[currentID])
        users[server.baseURL] = viewer
        return viewer
    }
    func signOut() async {
        if let server = entries[currentID] { users[server.baseURL] = nil }
    }
    func pair() -> AsyncStream<PairingState> { AsyncStream { $0.finish() } }
    func setDeviceName(_ name: String) async { deviceName = name }
    func sessions(all: Bool) async throws -> [DeviceSession] { [] }
    func revoke(sessionID: String) async throws {}
    func lookUpPairing(code: String) async throws -> PendingDevice { throw CancellationError() }
    func approvePairing(code: String) async throws -> PendingDevice { throw CancellationError() }
}
