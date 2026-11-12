import Foundation
import Testing
@testable import WMMFeatures
import WMMFakes
import WMMModel

@MainActor
@Suite struct LinkTVModelTests {
    init() { FakeLatency.duration = .zero }

    @Test func codesAreTypedLoosely() {
        #expect(normalizePairingCode("abc-234") == "ABC234")
        #expect(normalizePairingCode(" kq7-f2w ") == "KQ7F2W")
        #expect(normalizePairingCode("KQ7 F2W X") == "KQ7F2W")
        #expect(normalizePairingCode("a.b") == "AB")
        #expect(normalizePairingCode("ä7ü") == "7")
        #expect(spacedCode("K7M4PQ") == "K7M 4PQ")
        #expect(spacedCode("K7M") == "K7M")
        #expect(spokenCode("K7M4PQ") == "K 7 M 4 P Q")
    }

    @Test func aCodeFromALinkIsLookedUpAtOnceThenApproved() async {
        let model = LinkTVModel(auth: FakeAuthRepository(), code: "k7m-4pq")
        #expect(model.code == "K7M4PQ")
        #expect(model.complete)
        await model.start()
        guard case .found(let device) = model.step else {
            Issue.record("expected the TV, got \(model.step)")
            return
        }
        #expect(device.name == "Living room TV")
        #expect(device.appLabel == "WatchMyMedia for Android TV 1.0.0")
        #expect(device.ip == "192.168.1.44")
        #expect(device.askedAt.map { formatAgo($0) } == "2 min ago")
        #expect(model.userName == "Sam")

        await model.approve()
        guard case .approved(let approved) = model.step else {
            Issue.record("expected the approved TV, got \(model.step)")
            return
        }
        #expect(approved.approved)
        #expect(!model.busy)
    }

    @Test func typingDoesNotLookUpUntilContinue() async {
        let model = LinkTVModel(auth: FakeAuthRepository())
        await model.start()
        #expect(model.step == .enter)
        model.setCode("k7m4")
        #expect(model.code == "K7M4")
        #expect(!model.complete)
        model.setCode("k7m4pq")
        #expect(model.step == .enter)
        await model.lookUp()
        #expect(model.step != .enter)
    }

    @Test func aShortCodeIsNotSent() async {
        let auth = ScriptedAuth(user: sam)
        auth.lookUpResult = .failure(APIError(status: 500, message: "should not be asked"))
        let model = LinkTVModel(auth: auth)
        model.setCode("KQ7")
        await model.lookUp()
        #expect(model.error == "The code has 6 characters.")
        #expect(model.step == .enter)
    }

    @Test func aWrongCodeSaysSoAndTypingClearsIt() async {
        let auth = ScriptedAuth(user: sam)
        auth.lookUpResult = .failure(APIError(status: 404, message: "That code is not waiting to be linked."))
        let model = LinkTVModel(auth: auth, code: "AAAAAA")
        await model.start()
        #expect(model.step == .enter)
        #expect(model.error == "That code is not waiting to be linked.")
        model.setCode("AAAAA")
        #expect(model.error == nil)
    }

    @Test func tooManyWrongCodesShowTheServersWords() async {
        let auth = ScriptedAuth(user: sam)
        auth.lookUpResult = .failure(APIError(status: 429, message: "Too many tries. Wait a few minutes."))
        let model = LinkTVModel(auth: auth, code: "AAAAAA")
        await model.lookUp()
        #expect(model.error == "Too many tries. Wait a few minutes.")

        auth.lookUpResult = .failure(URLError(.timedOut))
        await model.lookUp()
        #expect(model.error == "Couldn't check that code. Try again.")
    }

    @Test func aFailedApprovalStaysOnTheTVWithTheReason() async {
        let auth = ScriptedAuth(user: sam)
        let model = LinkTVModel(auth: auth, code: "KQ7F2W")
        await model.start()
        auth.approveError = APIError(status: 410, message: "The code ran out. Ask the TV for a new one.")
        await model.approve()
        guard case .found = model.step else {
            Issue.record("expected to stay on the TV, got \(model.step)")
            return
        }
        #expect(model.error == "The code ran out. Ask the TV for a new one.")
    }

    @Test func approvingNeedsATV() async {
        let model = LinkTVModel(auth: ScriptedAuth(user: sam))
        await model.approve()
        #expect(model.step == .enter)
    }

    @Test func aTVWithoutDetailsHasNoAppOrAskedLine() {
        let device = PendingDevice(code: "K7M4PQ", name: "TV", approved: false)
        #expect(device.appLabel == nil)
        #expect(device.askedAt == nil)
    }
}
