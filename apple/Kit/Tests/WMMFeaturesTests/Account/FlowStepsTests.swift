import Foundation
import Testing
@testable import WMMFeatures
import WMMFakes
import WMMModel

/// Link a TV and Switch server as stepped tasks:
/// each step's number and names, each button and why it is off, every
/// failure ending in what was or wasn't done, and the finished screen.
@MainActor
@Suite struct FlowStepsTests {
    init() { FakeLatency.duration = .zero }

    private let tv = PendingDevice(code: "K7M4PQ", name: "Living room TV", product: "WatchMyMedia for Android TV", version: "1.4.6",
                                   ip: "192.0.2.44", approved: false, expiresAt: Date(timeIntervalSince1970: 1_000_000 + 8 * 60))

    @Test func aStepSaysItsNumberNameAndTheNext() {
        let first = FlowStep(number: 1, names: LinkTVModel.stepNames)
        #expect(first.line == "Step 1 of 3 · Enter the code · next: Check it’s yours")
        #expect(first.accessibilityLabel == "Step 1 of 3, Enter the code")
        #expect(first.done.isEmpty)
        let second = FlowStep(number: 2, names: AddServerModel.stepNames)
        #expect(second.line == "Step 2 of 3 · Sign in · next: Added")
        #expect(second.shortLine == "Step 2 of 3 · next: Added")
        #expect(second.done == [1])
        let last = FlowStep(number: 3, names: LinkTVModel.stepNames, done: [1, 2, 3])
        #expect(last.line == "Linked")
        #expect(last.isLast && last.next == nil)
    }

    // MARK: Link a TV

    @Test func linkATVGoesFromTheCodeToLinked() async {
        let auth = ScriptedAuth(user: sam)
        auth.lookUpResult = .success(tv)
        let model = LinkTVModel(auth: auth, serverName: "Living room server")
        #expect(model.flowStep.number == 1)
        #expect(model.title == "Enter the code on the TV")
        #expect(model.primaryTitle == "Check this code")
        #expect(model.primaryOffReason == "Check is off until all six characters are in.")
        model.setCode("k7m4")
        #expect(model.codeAccessibilityLabel == "Code, four of six characters entered")
        model.setCode("k7m-4pq")
        #expect(model.primaryOffReason == nil)
        #expect(model.codeAccessibilityLabel == "Code K7M 4PQ")
        #expect(!model.hasBack)

        await model.lookUp()
        #expect(model.flowStep.line == "Step 2 of 3 · Check it’s yours · next: Linked")
        #expect(model.title == "Is this your TV?")
        #expect(model.lede == "Approve only a TV that is showing this code right now.")
        #expect(model.primaryTitle == "Approve Living room TV")
        #expect(model.hasBack)
        #expect(model.approveNote == "Approving signs this TV in as Sam. Anyone with its remote can watch as you until you sign it out under Signed-in devices.")
        // The code lasts 10 minutes and has 8 left: the TV asked 2 minutes ago.
        let rows = model.deviceRows(now: Date(timeIntervalSince1970: 1_000_000))
        #expect(rows.map(\.label) == ["Name", "App", "Address", "Asked"])
        #expect(rows.map(\.value) == ["Living room TV", "WatchMyMedia for Android TV 1.4.6", "192.0.2.44", "2 min ago"])

        model.backToCode()
        #expect(model.flowStep.number == 1)
        #expect(model.code == "K7M4PQ", "the code stays as typed")
        await model.lookUp()
        await model.approve()
        #expect(model.flowStep.number == 3)
        #expect(model.flowStep.done == [1, 2, 3])
        #expect(!model.hasBack, "the TV is signed in")
        #expect(model.primaryTitle == "Done")
        let done = model.finished
        #expect(done?.title == "Living room TV is signed in")
        #expect(done?.lede == "It shows Home in a moment, signed in as Sam.")
        #expect(done?.whatChanged == ["Living room TV is signed in as Sam.", "It is listed under Signed-in devices."])
        #expect(done?.whatHappensNext == ["It stays signed in until you sign it out there, or change your password."])

        model.startAgain()
        #expect(model.flowStep.number == 1)
        #expect(model.code.isEmpty)
    }

    @Test func everyLinkFailureSaysNothingWasLinked() async {
        let auth = ScriptedAuth(user: sam)
        let time = ManualTime()
        let model = LinkTVModel(auth: auth, code: "K7M4PQ", serverName: "Living room server", sleep: { try await time.sleep($0) })

        auth.lookUpResult = .failure(APIError(status: 404, message: "That code is not waiting to be linked."))
        await model.lookUp()
        #expect(model.problem == SettingsNotice(title: "No TV is waiting with that code",
                                                body: "Nothing was linked. Check the code on the TV, or choose Get a new code there, then enter it here."))
        #expect(model.error == "That code is not waiting to be linked.", "today's line still says it")

        auth.lookUpResult = .failure(APIError(status: 429, message: "Too many attempts."))
        await model.lookUp()
        #expect(model.problem == SettingsNotice(title: "Too many wrong codes", body: "Nothing was linked. Try again in a minute."))
        await time.advance(byMs: 60_000)

        auth.lookUpResult = .failure(APIError(status: nil, message: "Couldn't reach the server. Check the connection and try again."))
        await model.lookUp()
        #expect(model.problem == SettingsNotice(title: "Couldn’t reach Living room server", body: "Nothing was linked. Check your connection and try again."))

        model.setCode("K7M4PZ")
        #expect(model.problem == nil, "typing clears it")

        auth.lookUpResult = .success(tv)
        await model.lookUp()
        auth.approveError = APIError(status: 404, message: "Not found.")
        await model.approve()
        #expect(model.problem?.title == "The code ran out before it was approved")
        #expect(model.problem?.body.hasPrefix("Nothing was linked.") == true)
        auth.approveError = APIError(status: nil, message: "Couldn't reach the server.")
        await model.approve()
        #expect(model.problem == SettingsNotice(title: "Couldn’t reach Living room server",
                                                body: "Nothing was linked, and the TV is still waiting. Check your connection and try again."))
        #expect(model.flowStep.number == 2, "still on Check it's yours")
        auth.approveError = APIError(status: 409, message: "Already approved.")
        await model.approve()
        #expect(model.problem?.body == "Someone approved this code a moment ago, so nothing more was linked. It’s listed under Signed-in devices.")
    }

    // MARK: Add a server

    private func signedInServers() -> (FakeServerRepository, FakeAuthRepository) {
        let servers = FakeServerRepository()
        let auth = FakeAuthRepository(servers: servers)
        return (servers, auth)
    }

    @Test func addingAServerSaysWhatChangedAndOneAlreadyAddedSkipsSignIn() async throws {
        let services = AppServices.fake(client: .fakePhone)
        #expect(AddServerModel.stepNames == ["Choose a server", "Sign in", "Added"])
        #expect(AddServerModel.title == "Add a server")
        // Add the Office Mac mini, signing in there.
        let first = try #require(AddServerModel(servers: services.servers, auth: services.auth))
        #expect(first.flowStep.line == "Step 1 of 3 · Choose a server · next: Sign in")
        first.picker.address = FakeServers.officeMini.baseURL.absoluteString
        await first.connectToAddress()
        #expect(first.flowStep.number == 2)
        #expect(first.signInTitle == "Sign in to Office Mac mini")
        #expect(first.signInLede == "office-mini.local:27587 · v0.9.1. Signing in here keeps you signed in on Living room server too.")
        #expect(first.signInOffReason == "Sign in is off until there is a name and a password.")
        first.signIn?.name = "Robin"
        first.signIn?.password = "nope"
        #expect(first.signInOffReason == nil)
        await first.signIn?.signIn()
        #expect(first.passwordProblem == "Wrong name or password. You’re still on Living room server.")
        #expect(first.signInProblem == nil)
        first.signIn?.password = "password"
        await first.signIn?.signIn()
        first.accountChanged()
        #expect(first.outcome == .added)
        #expect(!first.skippedSignIn)
        #expect(first.flowStep.number == 3)
        #expect(first.flowStep.line == "Added")
        let words = try #require(first.finished(device: "iPhone", signedIn: 2))
        #expect(words.title == "Office Mac mini is added")
        #expect(words.lede == "You’re on it now, signed in as Robin.")
        #expect(words.whatChanged == ["This iPhone is signed in to 2 servers. Search looks in all of them."])
        #expect(words.whatHappensNext == ["Tap your initial on Home to switch. It doesn’t ask for a password again."])
        #expect(first.finished(device: "Apple TV", signedIn: 3, tv: true)?.whatHappensNext
            == ["Select your initial beside the tabs to switch. It doesn’t ask for a password again."])

        // Living room server is registered, so it isn't offered; its address typed switches to it with no Sign in.
        let back = try #require(AddServerModel(servers: services.servers, auth: services.auth))
        #expect(!back.remembered.contains { $0.id == FakeServers.livingRoom.id })
        back.picker.address = FakeServers.livingRoom.baseURL.absoluteString
        await back.connectToAddress()
        #expect(back.outcome == .added)
        #expect(back.skippedSignIn, "no Sign in step")
        #expect(back.flowStep.done == [1, 2, 3])
        #expect(back.finished(device: "iPhone", signedIn: 2)?.title == "Living room server was added already")
    }

    @Test func everyFailureSaysYoureStillOnTheFirstServer() async throws {
        let account = SwitchAccount()
        let model = try #require(AddServerModel(servers: account, auth: account))
        #expect(model.stillOn == "You’re still on Living room server.")
        #expect(model.connectOffReason == "Connect is off until there is an address.")
        #expect(model.chooseLede == "Servers on this network appear by themselves. You stay signed in on Living room server, and switching between them needs no password.")

        account.probeError = APIError(status: nil, message: "Couldn't reach the server. Check the connection and try again.")
        model.picker.address = "192.0.2.99"
        #expect(model.connectOffReason == nil)
        await model.connectToAddress()
        #expect(model.chooseProblem == SettingsNotice(
            title: "Couldn’t reach 192.0.2.99",
            body: "Check the address, and that the server is running and on this network. You’re still on Living room server."))
        model.picker.address = "192.0.2.9"
        #expect(model.chooseProblem == nil, "typing clears it")

        var old = FakeServers.officeMini
        old.version = "0.0.9"
        old.apiLevel = 0
        account.probeError = ServerTooOldError(server: old)
        await model.connectToAddress()
        #expect(model.chooseProblem == SettingsNotice(
            title: "That server is too old for this app",
            body: "It is on v0.0.9, and this app needs server 0.1.0 or newer. Update that server, then try again. You’re still on Living room server."))

        account.probeError = APIError(status: nil, message: "This address needs https. Use the server's IP address or .local name on your network, or an https address such as Tailscale Serve gives.")
        await model.connectToAddress()
        #expect(model.chooseProblem?.title == "Couldn’t connect to 192.0.2.9")
        #expect(model.chooseProblem?.body.hasSuffix("You’re still on Living room server.") == true)
    }
}

/// After too many wrong codes, Check this code is off for a minute and says
/// why.
@MainActor
@Suite struct LinkTVTooManyCodesTests {
    init() { FakeLatency.duration = .zero }

    @Test func checkIsOffForAMinuteAfterTooManyWrongCodes() async {
        let time = ManualTime()
        let auth = ScriptedAuth(user: sam)
        auth.lookUpResult = .failure(APIError(status: 429, message: "Too many attempts. Wait a while and try again."))
        let model = LinkTVModel(auth: auth, code: "K7M4PQ", serverName: "Living room server", sleep: { try await time.sleep($0) })
        #expect(model.canCheck && !model.tooManyCodes)
        await model.lookUp()
        #expect(model.tooManyCodes)
        #expect(!model.canCheck)
        #expect(model.primaryOffReason == "Too many wrong codes. Nothing was linked. Try again in a minute.")
        #expect(model.problem == SettingsNotice(title: "Too many wrong codes", body: "Nothing was linked. Try again in a minute."))

        // Another code changes nothing: the server is refusing codes, not this one.
        model.setCode("K7M4PZ")
        #expect(model.primaryOffReason == "Too many wrong codes. Nothing was linked. Try again in a minute.")
        auth.lookUpResult = .success(PendingDevice(code: "K7M4PZ", name: "Den TV", approved: false))
        await model.lookUp()
        #expect(model.flowStep.number == 1, "no code is sent meanwhile")

        await time.advance(byMs: 59_000)
        #expect(model.tooManyCodes)
        await time.advance(byMs: 1_000)
        #expect(!model.tooManyCodes)
        #expect(model.canCheck && model.primaryOffReason == nil)
        await model.lookUp()
        #expect(model.flowStep.number == 2)
    }

    @Test func aRefusedApprovalLeavesTheCodeStepAlone() async {
        let auth = ScriptedAuth(user: sam)
        auth.lookUpResult = .success(PendingDevice(code: "K7M4PQ", name: "Den TV", approved: false))
        auth.approveError = APIError(status: 429, message: "Too many attempts.")
        let model = LinkTVModel(auth: auth, code: "K7M4PQ")
        await model.lookUp()
        await model.approve()
        #expect(model.problem?.title == "Too many tries")
        #expect(!model.tooManyCodes)
    }
}

/// Switch server's Sign in by a code from a signed-in device,
/// and Apple TV's starting on the code.
@MainActor
@Suite struct SwitchServerCodeSignInTests {
    init() { FakeLatency.duration = .zero }

    @Test func aPhoneCanSignInByACodeInstead() async throws {
        let services = AppServices.fake(client: .fakePhone)
        let auth = try #require(services.auth as? FakeAuthRepository)
        auth.pairingApproval = .zero
        let model = try #require(AddServerModel(servers: services.servers, auth: services.auth))
        #expect(!model.pairsByDefault)
        model.picker.address = FakeServers.officeMini.baseURL.absoluteString
        await model.connectToAddress()
        let form = try #require(model.signIn)
        #expect(!form.codeSignIn, "the password form first")
        #expect(model.otherWayTitle == "Use a code from a signed-in device instead")

        let run = form.pairingRun
        model.useCode()
        #expect(form.codeSignIn)
        #expect(form.pairingRun == run + 1, "a fresh code")
        #expect(model.otherWayTitle == "Sign in with a password instead")
        #expect(model.codeLede == "On a phone or computer signed in to Office Mac mini, open Link a TV and enter this code. You’re still on Living room server until it’s approved.")
        #expect(model.signInOffReason == nil, "a code needs no name or password")

        model.usePassword()
        #expect(!form.codeSignIn)
        model.useCode()
        await form.pair()
        model.accountChanged()
        #expect(model.outcome == .added)
        #expect(!model.skippedSignIn)
        #expect(model.finished(device: "iPhone", signedIn: 2)?.title == "Office Mac mini is added")
    }

    @Test func appleTVStartsOnTheCode() async throws {
        let services = AppServices.fake(client: .fakeAppleTV)
        let model = try #require(AddServerModel(servers: services.servers, auth: services.auth, pairsByDefault: true))
        #expect(model.pairsByDefault)
        model.picker.address = FakeServers.officeMini.baseURL.absoluteString
        await model.connectToAddress()
        let form = try #require(model.signIn)
        #expect(form.pairsByDefault && form.codeSignIn)
        #expect(model.otherWayTitle == "Sign in with a password instead")
        model.usePassword()
        #expect(!form.codeSignIn)
        model.useCode()
        #expect(form.codeSignIn)
    }

    @Test func firstRunSignInOnAPhoneCanUseACodeToo() async {
        let servers = FakeServerRepository(signedIn: false)
        let model = SignInModel(servers: servers, auth: FakeAuthRepository(signedIn: false, servers: servers))
        #expect(!model.codeSignIn && !model.usingCode)
        model.usePairingCode()
        #expect(model.codeSignIn && model.usingCode)
        model.usePassword()
        #expect(!model.codeSignIn && !model.usingCode)
    }
}
