import Foundation
import Testing
@testable import WMMFeatures
import WMMFakes
import WMMModel

/// The account screens as the Apple TV app uses them: sign-in by pairing
/// first (TvMain.dc.html) with the password form as the fallback
/// (TvSignInPassword.dc.html), the server picker, and the TV's wording.
@MainActor
@Suite struct AppleTVAccountTests {
    init() { FakeLatency.duration = .zero }

    private func server(_ server: Server) -> TestServers {
        let servers = TestServers()
        servers.current = SavedServer(server: server, userName: nil, signedIn: false, lastUsedAt: .now)
        return servers
    }

    // MARK: Sign in

    @Test func pairsFirstWithThePasswordOneButtonAway() {
        let model = SignInModel(servers: server(FakeServers.livingRoom), auth: ScriptedAuth(), pairsByDefault: true)
        #expect(model.codeSignIn, "the code shows first")
        #expect(model.passwordAllowed)
        #expect(!model.canSubmit)

        model.usePassword()
        #expect(model.usingPassword)
        #expect(!model.codeSignIn)
        model.name = "sam"
        model.password = "pw"
        #expect(model.canSubmit)

        let run = model.pairingRun
        model.usePairingCode()
        #expect(model.codeSignIn)
        #expect(!model.usingPassword)
        #expect(model.pairingRun == run + 1, "back to a fresh code")
    }

    @Test func aServerWithNoAccountsStillShowsTheCode() {
        // Approving it on the server's web page works once its first account is made there.
        var new = FakeServers.livingRoom
        new.setupRequired = true
        let model = SignInModel(servers: server(new), auth: ScriptedAuth(), pairsByDefault: true)
        #expect(!model.setupRequired)
        #expect(model.codeSignIn)
    }

    @Test func signInProtectionHidesThePassword() {
        let model = SignInModel(servers: server(FakeServers.remoteHome), auth: ScriptedAuth(), pairsByDefault: true)
        #expect(!model.passwordAllowed)
        model.usePassword()
        #expect(model.codeSignIn, "the password form never shows")
        #expect(!model.usingPassword)
    }

    @Test func aPasswordRefusedForTheBrowserCheckGoesBackToTheCode() async {
        let auth = ScriptedAuth()
        auth.signInError = APIError(status: 403, message: "Complete the browser check.")
        let model = SignInModel(servers: server(FakeServers.livingRoom), auth: auth, pairsByDefault: true)
        model.usePassword()
        model.name = "sam"
        model.password = "pw"
        await model.signIn()
        #expect(model.codeSignIn)
        #expect(!model.passwordAllowed)
    }

    @Test func aCodeThatRunsOutIsReplacedByItself() async {
        let auth = ScriptedAuth()
        auth.pairingScripts = [
            .init(states: [.starting, .waiting(pairingCode("K7M4PQ")), .expired]),
            .init(states: [.starting, .waiting(pairingCode("R2D2XY"))], hangs: true),
        ]
        let model = SignInModel(servers: server(FakeServers.livingRoom), auth: auth, pairsByDefault: true)
        let task = Task { await model.pair() }
        #expect(await eventually { model.waitingCode?.code == "R2D2XY" })
        #expect(auth.pairingsStarted == 2)
        #expect(!model.pairingEnded)
        task.cancel()
        await task.value
    }

    @Test func aFailedCodeWaitsForTheButton() async {
        let auth = ScriptedAuth()
        auth.pairingScripts = [.init(states: [.starting, .failed("The server is busy.")])]
        let model = SignInModel(servers: server(FakeServers.livingRoom), auth: auth, pairsByDefault: true)
        await model.pair()
        #expect(model.pairingEnded)
        #expect(model.pairingProblem == "The server is busy.")
        #expect(auth.pairingsStarted == 1, "only a code that ran out is replaced by itself")
    }

    @Test func thePhoneStillLeavesAnExpiredCodeToItsButton() async {
        let auth = ScriptedAuth()
        auth.pairingScripts = [.init(states: [.starting, .waiting(pairingCode("K7M4PQ")), .expired])]
        let model = SignInModel(servers: server(FakeServers.remoteHome), auth: auth)
        await model.pair()
        #expect(model.pairing == .expired)
        #expect(auth.pairingsStarted == 1)
    }

    @Test func pairingWithTheFakesSignsIn() async {
        let servers = FakeServerRepository(signedIn: false)
        let auth = FakeAuthRepository(signedIn: false, servers: servers)
        auth.pairingApproval = .zero
        let model = SignInModel(servers: servers, auth: auth, pairsByDefault: true)
        await model.pair()
        #expect(auth.user?.name == "Sam")
    }

    @Test func theFakesRunAsAnAppleTV() async throws {
        let services = AppServices.fake(client: .fakeAppleTV)
        #expect(services.auth.deviceName == "Living room Apple TV")
        let sessions = try await services.auth.sessions(all: false)
        #expect(sessions.first { $0.current }?.name == "Living room Apple TV")
        #expect(sessions.first { $0.current }?.model == "Apple TV 4K")
        #expect(sessions.first { $0.current }?.product == "WatchMyMedia for Apple TV")
        #expect(sessions.first { $0.current }?.platform == "tvos")
        #expect(sessions.contains { $0.name == "Sam’s iPhone" && !$0.current })
        #expect(AppServices.fake().auth.deviceName == "Sam’s iPhone", "the phone's fakes are unchanged")
    }

    // MARK: Server picker

    @Test func aRememberedServerTooOldSaysSo() {
        let servers = TestServers()
        let old = Server(id: "old", name: "Attic NAS", baseURL: URL(string: "http://192.168.1.9:27587")!, version: "0.0.9", apiLevel: 0)
        servers.saved = [SavedServer(server: old, userName: "Sam", signedIn: true, lastUsedAt: .now.addingTimeInterval(-2 * 86_400))]
        let model = ServerPickerModel(servers: servers)
        #expect(model.remembered.single?.tooOld == true)
        #expect(model.remembered.single?.detail == "192.168.1.9:27587 · v0.0.9 · too old for this app")
    }

    @Test func forgettingARememberedServer() async throws {
        let servers = FakeServerRepository()
        let model = ServerPickerModel(servers: servers)
        let row = try #require(model.remembered.first)
        await model.forget(row)
        #expect(!model.remembered.contains { $0.id == row.id })
        #expect(!servers.saved.contains { $0.server.id == row.id })
    }

    // MARK: Wording

    @Test func theCodeCountsDownToTheNextOne() {
        let now = Date(timeIntervalSince1970: 1_000_000)
        #expect(tvPairingWaitingLine(expiresAt: now.addingTimeInterval(292), now: now) == "Waiting for the code · refreshes in 4:52 · this Apple TV signs in by itself once it is entered")
        #expect(tvPairingWaitingLine(expiresAt: now.addingTimeInterval(9.2), now: now).contains("refreshes in 0:10"))
        #expect(tvPairingWaitingLine(expiresAt: now.addingTimeInterval(-5), now: now).contains("refreshes in 0:00"))
    }

    @Test func theLinkPageIsNamedWithoutTheCode() {
        #expect(linkPageLabel(URL(string: "http://192.168.1.20:27587/link?code=K7M4PQ")!) == "192.168.1.20:27587/link")
        #expect(linkPageLabel(URL(string: "https://home.watchmy.media/link?code=K7M4PQ#x")!) == "home.watchmy.media/link")
    }

    @Test func serverAndDeviceLines() {
        let server = FakeServers.livingRoom
        #expect(server.summaryLine == "Living room server · 192.168.1.20:27587 · v0.9.3")
        #expect(server.adminToolsLine == "In the web app at 192.168.1.20:27587")
        #expect(server.aboutLine == "Living room server · v0.9.3 · API 6 · 192.168.1.20:27587")
        #expect(thisDeviceLine(name: "Living room Apple TV", model: "Apple TV 4K") == "This device: Living room Apple TV · Apple TV 4K")
        #expect(thisDeviceLine(name: "Apple TV", model: "Apple TV") == "This device: Apple TV")
        #expect(thisDeviceLine(name: "Den", model: "") == "This device: Den")
    }

    @Test func theFamilyOfAnAppleTV() {
        #expect(deviceFamily(model: "Apple TV") == "Apple TV")
        #expect(deviceFamily(model: "Apple TV 4K") == "Apple TV")
        #expect(deviceFamily(model: "iPad Air") == "iPad")
        #expect(deviceFamily(model: "iPhone 16") == "iPhone")
        let facts = SettingsModel.DeviceFacts(model: "Apple TV 4K", system: "tvOS 18.2", appVersion: "1.0", build: "12")
        #expect(facts.family == "Apple TV")
        #expect(facts.summaryLine == "Apple TV 4K · tvOS 18.2 · app 1.0 (12)")
    }

    @Test func theLicencesNameEveryPackage() {
        #expect(openSourceNotices.map(\.name) == ["Swift OpenAPI Runtime", "Swift OpenAPI URLSession", "Swift HTTP Types"])
    }
}
