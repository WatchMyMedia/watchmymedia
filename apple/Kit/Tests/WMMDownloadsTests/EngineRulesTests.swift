import Foundation
import Testing
@testable import WMMDownloads
import WMMModel

/// The engine's rules that run without the system's sessions
/// (PLAN-APPLE-REVIEW.md §3.1.5 and §3.1.8).
@MainActor
@Suite struct EngineRulesTests {
    @Test func wifiOnlyWaitsOnAHotspotAndInLowDataMode() {
        func notMet(wifi: Bool = true, wired: Bool = false, expensive: Bool = false, constrained: Bool = false, wifiOnly: Bool = true, satisfied: Bool = true) -> DownloadStatus.Reason? {
            SystemDownloadEngine.notMet(satisfied: satisfied, wifi: wifi, wired: wired, expensive: expensive, constrained: constrained, wifiOnly: wifiOnly)
        }
        #expect(notMet() == nil)
        #expect(notMet(wifi: false, wired: true) == nil)
        #expect(notMet(wifi: false) == .wifi, "mobile data")
        // A phone's personal hotspot is Wi-Fi the system marks expensive.
        #expect(notMet(expensive: true) == .wifi)
        #expect(notMet(constrained: true) == .wifi)
        // Wi-Fi and mobile data: anything that connects.
        #expect(notMet(wifi: false, expensive: true, constrained: true, wifiOnly: false) == nil)
        #expect(notMet(satisfied: false) == .network)
        #expect(notMet(wifiOnly: false, satisfied: false) == .network)
    }

    /// A background task and a deadline the test controls.
    @MainActor final class Rig {
        var begun = 0
        var ended = 0
        var deadline: CheckedContinuation<Void, Never>?
        var called: [String] = []
        lazy var hold = HandoverHold(
            beginTask: { [weak self] in
                self?.begun += 1
                return { [weak self] in self?.ended += 1 }
            },
            // Waits until the test resumes it; a test that never does leaves it to the rig's end.
            sleep: { [weak self] _ in
                await withCheckedContinuation { c in
                    Task { @MainActor in
                        if let self { self.deadline = c } else { c.resume() }
                    }
                }
            }
        )
    }

    @Test func theSessionsEventsWaitUntilTheNextDownloadIsHandedOver() async {
        let rig = Rig()
        // A download finished and the next one is being made ready (the engine saves it as preparing).
        rig.hold.setPreparing(true)
        #expect(rig.begun == 1)
        rig.hold.delivered { rig.called.append("hls") }
        #expect(rig.called.isEmpty)
        #expect(rig.hold.waiting == 1)
        // The token came and the playlist answered: the task is handed to the system.
        rig.hold.setPreparing(false)
        #expect(rig.called == ["hls"])
        #expect(rig.ended == 1)
        #expect(rig.hold.waiting == 0)
    }

    @Test func withNothingToMakeReadyTheEventsAreDoneAtOnce() {
        let rig = Rig()
        rig.hold.delivered { rig.called.append("files") }
        #expect(rig.called == ["files"])
        #expect(rig.begun == 0)
        // Saying "not preparing" again changes nothing.
        rig.hold.setPreparing(false)
        #expect(rig.called == ["files"])
        #expect(rig.ended == 0)
    }

    @Test func aPreparationThatTakesTooLongLetsTheAppGoAfterTheLimit() async {
        let rig = Rig()
        rig.hold.setPreparing(true)
        rig.hold.delivered { rig.called.append("hls") }
        rig.hold.delivered { rig.called.append("files") }
        for _ in 0..<50 where rig.deadline == nil { await Task.yield() }
        #expect(rig.called.isEmpty)
        // The server is busy (a 503 to wait out): the system's time runs out first.
        rig.deadline?.resume()
        for _ in 0..<50 where rig.called.count < 2 { await Task.yield() }
        #expect(rig.called == ["hls", "files"])
        // The background task stays until the preparation ends (or the system ends it).
        #expect(rig.ended == 0)
        rig.hold.setPreparing(false)
        #expect(rig.ended == 1)
        #expect(rig.called == ["hls", "files"])
    }
}
