import Foundation
import Testing
@testable import WMMFeatures
import WMMFakes
import WMMModel

/// Link a TV and Switch server as stepped tasks (PLAN-APPLE-SETTINGS.md §5.5,
/// §5.6): each step's number and names, each button and why it is off, every
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
        let second = FlowStep(number: 2, names: ServerSwitchModel.stepNames)
        #expect(second.line == "Step 2 of 3 · Sign in · next: Switched")
        #expect(second.shortLine == "Step 2 of 3 · next: Switched")
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
        let model = LinkTVModel(auth: auth, code: "K7M4PQ", serverName: "Living room server")

        auth.lookUpResult = .failure(APIError(status: 404, message: "That code is not waiting to be linked."))
        await model.lookUp()
        #expect(model.problem == SettingsNotice(title: "No TV is waiting with that code",
                                                body: "Nothing was linked. Check the code on the TV, or choose Get a new code there, then enter it here."))
        #expect(model.error == "That code is not waiting to be linked.", "today's line still says it")

        auth.lookUpResult = .failure(APIError(status: 429, message: "Too many attempts."))
        await model.lookUp()
        #expect(model.problem == SettingsNotice(title: "Too many wrong codes", body: "Nothing was linked. Try again in a minute."))

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

    // MARK: Switch server

    private func signedInServers() -> (FakeServerRepository, FakeAuthRepository) {
        let servers = FakeServerRepository()
        let auth = FakeAuthRepository(servers: servers)
        return (servers, auth)
    }

    @Test func switchingToAServerSignedInToAlreadySkipsSignIn() async throws {
        let services = AppServices.fake(client: .fakePhone)
        // Sign in on the Office Mac mini once, then come back to Living room.
        let first = try #require(ServerSwitchModel(servers: services.servers, auth: services.auth))
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
        #expect(first.outcome == .switched)
        #expect(!first.skippedSignIn)
        #expect(first.flowStep.number == 3)
        let words = try #require(first.finished(device: "iPhone", downloadsKept: true))
        #expect(words.title == "You’re on Office Mac mini")
        #expect(words.lede == "Signed in as Robin.")
        #expect(words.whatChanged == [
            "This iPhone now shows Office Mac mini’s library, Home and settings.",
            "You’re still signed in on Living room server, and its downloads stay on this iPhone.",
        ])
        #expect(words.whatHappensNext == [
            "Switch back any time under Settings › Server. It needs no password.",
            "Playback and Audio & subtitles are your choices on Office Mac mini, kept there.",
        ])

        let back = try #require(ServerSwitchModel(servers: services.servers, auth: services.auth))
        let row = try #require(back.picker.remembered.first { $0.id == FakeServers.livingRoom.id })
        #expect(row.signedIn)
        #expect(back.detail(for: row).hasSuffix(", no password needed"))
        #expect(back.detail(for: row, tv: true).hasSuffix(", so no password is needed"))
        await back.choose(row)
        #expect(back.outcome == .switched)
        #expect(back.skippedSignIn, "no Sign in step")
        #expect(back.flowStep.done == [1, 2, 3])
        #expect(back.finished(device: "Apple TV")?.whatChanged.last == "You’re still signed in on Office Mac mini.")
    }

    @Test func everyFailureSaysYoureStillOnTheFirstServer() async throws {
        let account = SwitchAccount()
        let model = try #require(ServerSwitchModel(servers: account, auth: account))
        #expect(model.stillOn == "You’re still on Living room server.")
        #expect(model.connectOffReason == "Connect is off until there is an address.")
        #expect(model.chooseLede == "Servers on this network appear by themselves. You stay signed in on Living room server, and switching back needs no password.")

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
