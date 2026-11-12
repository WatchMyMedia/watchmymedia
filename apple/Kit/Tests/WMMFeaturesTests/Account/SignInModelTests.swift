import Foundation
import Testing
@testable import WMMFeatures
import WMMFakes
import WMMModel

@MainActor
@Suite struct SignInModelTests {
    init() { FakeLatency.duration = .zero }

    private func fakes(remote: Bool = false) async -> (FakeServerRepository, FakeAuthRepository) {
        let servers = FakeServerRepository(signedIn: false)
        if remote { try? await servers.select(FakeServers.remoteHome) }
        return (servers, FakeAuthRepository(signedIn: false, servers: servers))
    }

    private func passwordServer() -> TestServers {
        let servers = TestServers()
        servers.current = SavedServer(server: FakeServers.livingRoom, userName: nil, signedIn: false, lastUsedAt: .now)
        return servers
    }

    private func pairingServer() -> TestServers {
        let servers = TestServers()
        servers.current = SavedServer(server: FakeServers.remoteHome, userName: nil, signedIn: false, lastUsedAt: .now)
        return servers
    }

    // MARK: Password

    @Test func signsInAndClearsThePassword() async {
        let (servers, auth) = await fakes()
        let model = SignInModel(servers: servers, auth: auth)
        #expect(model.server?.name == "Living room server")
        #expect(!model.codeSignIn)
        model.name = " sam "
        model.password = "password"
        model.showPassword = true
        #expect(model.canSubmit)
        await model.signIn()
        #expect(auth.user?.name == "sam")
        #expect(model.password == "")
        #expect(!model.showPassword)
        #expect(model.error == nil)
        #expect(!model.busy)
    }

    @Test func aWrongPasswordSaysSoAndTypingClearsIt() async {
        let (servers, auth) = await fakes()
        let model = SignInModel(servers: servers, auth: auth)
        model.name = "sam"
        model.password = "nope"
        await model.signIn()
        #expect(model.error == "Wrong name or password.")
        #expect(auth.user == nil)
        #expect(!model.busy)
        model.password = "nope2"
        #expect(model.error == nil)
    }

    @Test func throttlingAsksToWait() async {
        let auth = ScriptedAuth()
        auth.signInError = APIError(status: 429, message: "Too many requests.")
        let model = SignInModel(servers: passwordServer(), auth: auth)
        model.name = "sam"
        model.password = "pw"
        await model.signIn()
        #expect(model.error == "Too many tries. Wait a minute, then try again.")
    }

    @Test func otherFailuresShowTheServersMessage() async {
        let auth = ScriptedAuth()
        auth.signInError = APIError(status: 500, message: "The database is busy. Try again.")
        let model = SignInModel(servers: passwordServer(), auth: auth)
        model.name = "sam"
        model.password = "pw"
        await model.signIn()
        #expect(model.error == "The database is busy. Try again.")

        auth.signInError = URLError(.notConnectedToInternet)
        await model.signIn()
        #expect(model.error == "Couldn't sign in. Check the server is running, then try again.")
    }

    @Test func emptyFieldsAreCaughtBeforeAskingTheServer() async {
        let auth = ScriptedAuth()
        let model = SignInModel(servers: passwordServer(), auth: auth)
        model.name = "  "
        model.password = "pw"
        #expect(!model.canSubmit)
        await model.signIn()
        #expect(model.error == "Enter your name and password.")
        #expect(auth.signInCalls == 0)
    }

    @Test func showPasswordToggles() {
        let model = SignInModel(servers: passwordServer(), auth: ScriptedAuth())
        model.toggleShowPassword()
        #expect(model.showPassword)
        model.toggleShowPassword()
        #expect(!model.showPassword)
    }

    @Test func aServerThatTurnedTheBrowserCheckOnSinceTurnsToPairing() async {
        let auth = ScriptedAuth()
        auth.signInError = APIError(status: 403, message: "Complete the browser check.")
        let model = SignInModel(servers: passwordServer(), auth: auth)
        model.name = "sam"
        model.password = "pw"
        let run = model.pairingRun
        await model.signIn()
        #expect(model.codeSignIn)
        #expect(model.error == nil)
        #expect(model.pairingRun == run + 1)
        #expect(!model.canSubmit)
    }

    @Test func theFooterNamesThisDevice() {
        #expect(signInFooter(deviceName: "Sam’s iPhone") == "Needs server 0.1.0 or newer · This device: Sam’s iPhone")
        #expect(signInFooter(deviceName: " ") == "Needs server 0.1.0 or newer")
        #expect(SignInModel(servers: passwordServer(), auth: ScriptedAuth()).deviceName == "Sam’s iPhone")
    }

    // MARK: Pairing

    @Test func aServerWithTheBrowserCheckSignsInByPairing() async {
        let (servers, auth) = await fakes(remote: true)
        auth.pairingApproval = .zero
        let model = SignInModel(servers: servers, auth: auth)
        #expect(model.codeSignIn)
        #expect(!model.canSubmit)
        await model.pair()
        #expect(model.pairing == .approved(User(id: 1, name: "Sam", isAdmin: true)))
        #expect(auth.user?.name == "Sam")
        #expect(servers.current?.signedIn == true)
    }

    @Test func thePairingShowsTheCodeWhileItWaits() async {
        let auth = ScriptedAuth()
        auth.pairingScripts = [.init(states: [.starting, .waiting(pairingCode("K7M4PQ"))], hangs: true)]
        let model = SignInModel(servers: pairingServer(), auth: auth)
        let task = Task { await model.pair() }
        #expect(await eventually { model.waitingCode != nil })
        #expect(model.waitingCode?.spaced == "K7M 4PQ")
        #expect(model.waitingCode?.linkURL.absoluteString == "https://home.watchmy.media/link?code=K7M4PQ")
        #expect(!model.pairingEnded)
        task.cancel()
        await task.value
        #expect(await eventually { auth.pairingsEnded == 1 })
    }

    @Test func anExpiredCodeOffersANewOne() async {
        let auth = ScriptedAuth()
        auth.pairingScripts = [
            .init(states: [.starting, .waiting(pairingCode("K7M4PQ")), .expired]),
            .init(states: [.starting, .waiting(pairingCode("R2D2XY"))], hangs: true),
        ]
        let model = SignInModel(servers: pairingServer(), auth: auth)
        await model.pair()
        #expect(model.pairing == .expired)
        #expect(model.pairingEnded)
        #expect(model.pairingProblem == "The code ran out. Get a new one to sign in.")
        #expect(model.waitingCode == nil)

        let run = model.pairingRun
        model.newCode()
        #expect(model.pairingRun == run + 1)
        #expect(model.pairing == nil)
        let task = Task { await model.pair() }
        #expect(await eventually { model.waitingCode?.code == "R2D2XY" })
        #expect(auth.pairingsStarted == 2)
        task.cancel()
        await task.value
    }

    @Test func aFailedPairingSaysWhyAndOffersANewCode() async {
        let auth = ScriptedAuth()
        auth.pairingScripts = [.init(states: [.starting, .failed("Too many codes from this address. Wait a few minutes.")])]
        let model = SignInModel(servers: pairingServer(), auth: auth)
        await model.pair()
        #expect(model.pairingEnded)
        #expect(model.pairingProblem == "Too many codes from this address. Wait a few minutes.")

        auth.pairingScripts = [.init(states: [.failed("")])]
        model.newCode()
        await model.pair()
        #expect(model.pairingProblem == "Couldn't get a code.")
    }

    @Test func leavingTheScreenStopsThePairing() async throws {
        let servers = FakeServerRepository(signedIn: false)
        try await servers.select(FakeServers.remoteHome)
        // Approved only after the screen has gone, however late that is on a busy machine.
        let (approval, approve) = AsyncStream.makeStream(of: Void.self)
        let auth = FakeAuthRepository(signedIn: false, servers: servers, pairingDelay: { for await _ in approval { break } })
        let model = SignInModel(servers: servers, auth: auth)
        let task = Task { await model.pair() }
        #expect(await eventually { model.waitingCode != nil })
        task.cancel()
        await task.value
        approve.yield()
        try await Task.sleep(for: .milliseconds(300))
        #expect(auth.user == nil, "a cancelled pairing never signs in")
    }

    @Test func aPasswordServerDoesNotPair() async {
        let auth = ScriptedAuth()
        let model = SignInModel(servers: passwordServer(), auth: auth)
        await model.pair()
        #expect(model.pairing == nil)
        #expect(auth.pairingsStarted == 0)
    }

    // MARK: A server with no accounts

    /// A server whose first account isn't made yet, which a probe finds the same until told otherwise.
    private func newServer(turnstile: Bool = false) -> (TestServers, Server) {
        let server = Server(id: "new", name: "Den server", baseURL: URL(string: "http://192.168.1.30:27587")!, version: "v0.4.1", apiLevel: 2, setupRequired: true, turnstile: turnstile)
        let servers = TestServers()
        servers.current = SavedServer(server: server, userName: nil, signedIn: false, lastUsedAt: .now)
        servers.probeResult = .success(server)
        return (servers, server)
    }

    @Test func aServerWithNoAccountsSendsYouToItsWebPage() async {
        let (servers, _) = newServer(turnstile: true)
        let auth = ScriptedAuth()
        let model = SignInModel(servers: servers, auth: auth)
        #expect(model.setupRequired)
        #expect(!model.codeSignIn, "no code: nobody could approve it yet")
        #expect(model.setupPage?.absoluteString == "http://192.168.1.30:27587")
        model.name = "sam"
        model.password = "pw"
        #expect(!model.canSubmit)
        await model.signIn()
        #expect(auth.signInCalls == 0)
        #expect(model.error == nil, "not the password form's error")
        await model.pair()
        #expect(auth.pairingsStarted == 0)
    }

    @Test func checkingAgainWaitsQuietlyThenMovesOnOnceSetUp() async {
        let (servers, server) = newServer()
        let model = SignInModel(servers: servers, auth: ScriptedAuth())
        await model.checkSetup(quiet: true)
        #expect(model.setupRequired)
        #expect(model.error == nil, "coming back says nothing while it waits")
        #expect(servers.probed == ["http://192.168.1.30:27587"])
        await model.checkSetup()
        #expect(model.error == "No account on this server yet. Make one on its web page first.")
        #expect(servers.selected.isEmpty)

        var setUp = server
        setUp.setupRequired = false
        servers.probeResult = .success(setUp)
        await model.checkSetup(quiet: true)
        #expect(!model.setupRequired)
        #expect(!model.checking)
        #expect(model.error == nil)
        #expect(servers.selected == [setUp], "the saved server knows too")
        #expect(!model.codeSignIn, "the password form")
    }

    @Test func aServerSetUpWithTheBrowserCheckGoesOnToTheCode() async {
        let (servers, server) = newServer(turnstile: true)
        let model = SignInModel(servers: servers, auth: ScriptedAuth())
        var setUp = server
        setUp.setupRequired = false
        servers.probeResult = .success(setUp)
        await model.checkSetup()
        #expect(!model.setupRequired)
        #expect(model.codeSignIn)
    }

    @Test func checkingAgainSaysWhenTheServerIsOutOfReach() async {
        let (servers, _) = newServer()
        servers.probeResult = .failure(APIError(status: nil, message: "Couldn't reach 192.168.1.30:27587. Check the address and that the server is running."))
        let model = SignInModel(servers: servers, auth: ScriptedAuth())
        await model.checkSetup(quiet: true)
        #expect(model.error == nil)
        await model.checkSetup()
        #expect(model.error == "Couldn't reach 192.168.1.30:27587. Check the address and that the server is running.")
        #expect(model.setupRequired)
        #expect(!model.checking)
    }

    @Test func anotherServerAtTheAddressIsNotTakenForThisOne() async {
        let (servers, _) = newServer()
        servers.probeResult = .success(Server(id: "other", name: "Other", baseURL: URL(string: "http://192.168.1.30:27587")!, version: "v0.4.1", apiLevel: 2))
        let model = SignInModel(servers: servers, auth: ScriptedAuth())
        await model.checkSetup()
        #expect(servers.selected.isEmpty)
        #expect(model.setupRequired)
        #expect(model.error == "No account on this server yet. Make one on its web page first.")
    }

    @Test func aServerWithAccountsHasNothingToCheck() async {
        let servers = passwordServer()
        let model = SignInModel(servers: servers, auth: ScriptedAuth())
        #expect(!model.setupRequired)
        await model.checkSetup()
        #expect(servers.probed.isEmpty)
    }

    // MARK: Link a TV

    @Test func linkATVWithAnEmptyFormAsksForTheNameAndWaitsForTheSignIn() async {
        let nav = Navigator()
        let model = SignInModel(servers: passwordServer(), auth: ScriptedAuth())
        #expect(model.linkTVSubtitle(navigator: nav) == "Enter the code shown on your TV")
        #expect(await model.linkTV(navigator: nav) == .focusName)
        #expect(nav.pendingLinkTV == PendingLinkTV(code: nil))
        #expect(nav.tab == .settings)
        #expect(nav.path(for: .settings) == [.linkTV(code: nil)])
        #expect(model.linkTVSubtitle(navigator: nav) == "Sign in first, then enter the TV’s code")
    }

    @Test func linkATVWithTheFormFilledInSignsIn() async {
        let nav = Navigator()
        let auth = ScriptedAuth()
        let model = SignInModel(servers: passwordServer(), auth: auth)
        model.name = "sam"
        model.password = "pw"
        #expect(await model.linkTV(navigator: nav) == .signingIn)
        #expect(auth.user?.name == "sam")
        #expect(nav.path(for: .settings) == [.linkTV(code: nil)])
    }

    @Test func aLinkWithACodeKeepsItsCode() async {
        let nav = Navigator()
        await nav.open(.linkTV(code: "K7M4PQ"), library: FakeLibraryRepository())
        let model = SignInModel(servers: passwordServer(), auth: ScriptedAuth())
        #expect(model.linkTVSubtitle(navigator: nav) == "Sign in to approve the code K7M 4PQ")
        _ = await model.linkTV(navigator: nav)
        #expect(nav.path(for: .settings) == [.linkTV(code: "K7M4PQ")])
    }

    @Test func pairingSignInSaysToSignInFirst() async {
        let nav = Navigator()
        let auth = ScriptedAuth()
        let model = SignInModel(servers: pairingServer(), auth: auth)
        #expect(model.linkTVSubtitle(navigator: nav) == "Sign in first, then enter the TV’s code")
        #expect(await model.linkTV(navigator: nav) == .none)
        #expect(auth.signInCalls == 0)
        #expect(nav.pendingLinkTV != nil)
    }
}
