import Foundation
import Testing
@testable import WMMCast
import WMMFakes
import WMMModel

/// The Cast remote's Sound and Subtitle timing: what goes to the TV, what is
/// saved with the phone's own sign-in, and when it shows at all.
@MainActor
@Suite struct CastSoundAndTimingTests {
    init() { FakeLatency.duration = .zero }

    let gateway = FakeCastGateway()
    let transport = FakeCastTransport()
    let preferences = FakePreferencesRepository()
    let timings = TimingRecorder()
    let sleeper = Sleeper()
    var file: PlayRequest { PlayRequest(fileID: 9101, title: "The Long Harbour", subtitleID: "s2") }

    private func casting(level: Int = ServerFeatures.Level.dialogueAndSubtitles, admin: Bool = false) async -> (CastService, CastSoundAndTiming) {
        gateway.apiLevel = level
        transport.soundReload = nil
        let service = CastService(gateway: gateway, transport: transport, client: .fakePhone)
        await service.refresh()
        transport.connect()
        #expect(await service.play(file, quality: .original))
        let sleeper = sleeper
        let model = CastSoundAndTiming(cast: service, preferences: preferences, timings: timings, isAdmin: { admin },
                                       sleep: { try await sleeper.sleep($0) })
        return (service, model)
    }

    /// The commands sent to the TV, by type, as JSON objects.
    private func sent(_ type: String) -> [[String: Any]] {
        transport.commands.compactMap { try? JSONSerialization.jsonObject(with: $0) as? [String: Any] }.filter { $0["type"] as? String == type }
    }

    /// The TV's state as it reports it, with the subtitle on and its timing.
    private func receiverSays(_ cast: CastService?, timingMs: Int?, shared: Bool?) {
        var subtitle: [String: Any] = ["id": "s2", "language": "eng", "format": "webvtt"]
        if let timingMs { subtitle["timingMs"] = timingMs }
        if let shared { subtitle["timingShared"] = shared }
        var state: [String: Any] = ["type": "state", "v": 1, "features": ["live"], "phase": "playing", "fileId": 9101,
                                    "durationMs": 5_400_000, "subtitles": [subtitle], "selectedSubtitle": "s2"]
        // The same play, or the remote would take it for someone else's.
        if let playID = cast?.receiver?.playId { state["playId"] = playID }
        transport.emit(.message(try! JSONSerialization.data(withJSONObject: state)))
    }

    @Test func aServerBefore35OrAPlayThisSignInDidntStartOffersNeither() async {
        let (service, model) = await casting(level: ServerFeatures.Level.redesign)
        #expect(service.ownsMedia)
        #expect(model.sound == nil)
        #expect(model.timing == nil)

        let other = CastSoundAndTiming(cast: CastService(gateway: FakeCastGateway(), transport: FakeCastTransport(), client: .fakePhone),
                                       preferences: preferences, timings: timings, isAdmin: { true })
        #expect(other.sound == nil, "nothing is cast from this sign-in")
    }

    @Test func aSoundChangeIsSavedThenTheTVDecidesAgainAndTheRowWaitsForIt() async {
        let (service, model) = await casting()
        #expect(model.sound == PlayerSound())
        model.setSound(.dialogueBoost, to: .high)
        #expect(model.sound?.busy == .dialogueBoost)
        #expect(model.sound?.dialogueBoost == .high, "the choice shows while it is saved")
        await until { !sent("sound").isEmpty }
        #expect(preferences.playback.audio.dialogueBoost == .high, "saved with this phone's sign-in first")
        await until { service.receiver?.phase == "loading" }
        #expect(model.sound?.busy == .dialogueBoost, "busy while the TV plays it again")
        transport.finishReload()
        await until { model.sound?.busy == nil }
        #expect(model.sound?.dialogueBoost == .high)
        #expect(sent("sound").count == 1)
    }

    @Test func aRefusedSoundChangeSendsNothingAndSaysWhatItIsStill() async {
        let (_, model) = await casting()
        preferences.refusal = FakeSaveRefusal(parts: ["reduceLoudSounds"])
        model.setSound(.reduceLoudSounds, to: .medium)
        await until { model.sound?.busy == nil }
        #expect(model.sound?.reduceLoudSounds == .off)
        #expect(model.sound?.line(.reduceLoudSounds) == "Couldn’t change the sound, so it’s still Off. Check your connection and choose again.")
        #expect(sent("sound").isEmpty)
    }

    @Test func aTVThatNeverPlaysAgainStillLetsTheRowSettle() async {
        let (_, model) = await casting()
        model.setSound(.dialogueBoost, to: .medium)
        // The fake TV stays loading: the remote gives up waiting, as the web's does.
        await until(tries: 20_000) { model.sound?.busy == nil }
        #expect(model.sound?.dialogueBoost == .medium)
    }

    @Test func theTimingStartsWhereTheTVIsMovesItsCuesAtOnceAndIsSavedASecondLater() async {
        let (service, model) = await casting()
        receiverSays(service, timingMs: 600, shared: true)
        #expect(model.timing?.value == "0.6 s later")
        #expect(model.timing?.shared == true)
        #expect(model.timing?.canReset == false)
        sleeper.holding = true
        model.moveTiming(later: true)
        model.moveTiming(later: true)
        #expect(sent("subtitleTiming").map { $0["timingMs"] as? Int } == [700, 800], "each press moves the TV's cues at once")
        #expect(timings.calls.isEmpty, "nothing is saved while the presses go on")
        sleeper.release()
        await until { timings.calls == ["9101/s2 800"] }
        await until { model.timing?.save == .rest }
        #expect(model.timing?.canReset == true)

        // Reset: back to the shared timing on the TV at once, and on the server.
        model.resetTiming()
        #expect(sent("subtitleTiming").last?["timingMs"] as? Int == 600)
        timings.answer = { _, ms, _ in ms.map { SavedTiming(timingMs: $0, shared: false) } ?? SavedTiming(timingMs: 600, shared: true) }
        await until { timings.calls.last == "9101/s2 reset" }
        await until { model.timing?.shared == true }
        #expect(model.timing?.canReset == false)
    }

    @Test func aTimingThatCantBeSavedSaysSoAndStaysOnTheTV() async {
        let (service, model) = await casting()
        receiverSays(service, timingMs: nil, shared: nil)
        timings.answer = { _, _, _ in throw URLError(.timedOut) }
        model.moveTiming(later: false)
        #expect(sent("subtitleTiming").last?["timingMs"] as? Int == -100)
        await until { model.timing?.failed == true }
        #expect(model.timing?.line == "Couldn’t save, so this timing is for this play only. Check your connection and try again.")
        #expect(model.timing?.value == "0.1 s earlier")
    }

    @Test func onlyAnAdminSavesForEveryone() async {
        let (service, model) = await casting(admin: true)
        receiverSays(service, timingMs: 400, shared: false)
        #expect(model.timing?.canSaveForEveryone == true)
        model.saveTimingForEveryone()
        await until { timings.calls == ["9101/s2 400 everyone"] }
        await until { model.timing?.savedForEveryone == true }
        #expect(model.timing?.shared == true)
        #expect(model.timing?.canSaveForEveryone == false)
    }

    @Test func aTrackInThePictureHasTheTVDecideAgainOnceSaved() async {
        let (service, model) = await casting()
        let state: [String: Any] = ["type": "state", "v": 1, "phase": "playing", "fileId": 9101, "durationMs": 5_400_000,
                                    "playId": service.receiver?.playId as Any,
                                    "subtitles": [["id": "s4", "format": "image", "burnInOnly": true]], "selectedSubtitle": "s4"]
        transport.emit(.message(try! JSONSerialization.data(withJSONObject: state)))
        #expect(model.timing?.burnedIn == true)
        model.moveTiming(later: true)
        #expect(sent("subtitleTiming").isEmpty, "the TV draws no cues of its own for it")
        await until { timings.calls == ["9101/s4 100"] }
        await until { !sent("sound").isEmpty }
    }

    private func until(tries: Int = 2_000, _ condition: () -> Bool) async {
        for _ in 0..<tries { if condition() { return }; await Task.yield() }
        #expect(condition(), "expected state was never reached")
    }
}

/// Lets a test hold the remote's waits (the second after the last press) and release them.
@MainActor
final class Sleeper {
    var holding = false
    private var waiting: [CheckedContinuation<Void, Never>] = []

    func sleep(_ duration: Duration) async throws {
        if holding { await withCheckedContinuation { waiting.append($0) } } else { await Task.yield() }
    }

    func release() {
        holding = false
        let held = waiting
        waiting = []
        held.forEach { $0.resume() }
    }
}

/// The timing routes as the remote calls them: "9101/s2 400", "… everyone", "… reset".
@MainActor
final class TimingRecorder: PlaybackControllerFactory {
    var calls: [String] = []
    var answer: (_ track: String, _ ms: Int?, _ everyone: Bool) throws -> SavedTiming = { _, ms, everyone in
        SavedTiming(timingMs: ms ?? 0, shared: everyone && (ms ?? 0) != 0)
    }

    func make() -> any PlaybackController { FakePlaybackController() }
    func plans(fileIDs: [Int]) async -> [Int: PlaybackPlan] { [:] }

    func setSubtitleTiming(fileID: Int, trackID: String, timingMs: Int, everyone: Bool) async throws -> SavedTiming {
        calls.append("\(fileID)/\(trackID) \(timingMs)\(everyone ? " everyone" : "")")
        return try answer(trackID, timingMs, everyone)
    }

    func deleteSubtitleTiming(fileID: Int, trackID: String) async throws -> SavedTiming {
        calls.append("\(fileID)/\(trackID) reset")
        return try answer(trackID, nil, false)
    }
}
