import Foundation
import os
import Testing
@testable import WMMModel

/// Writes down what the signposts would record.
private final class ListRecorder: PerformanceRecorder {
    private let log = OSAllocatedUnfairLock<[String]>(initialState: [])
    private let next = OSAllocatedUnfairLock(initialState: 0)

    var entries: [String] { log.withLock { $0 } }

    func begin(_ interval: PerformanceInterval) -> Int {
        let token = next.withLock { $0 += 1; return $0 }
        log.withLock { $0.append("begin \(interval.rawValue) #\(token)") }
        return token
    }

    func end(_ interval: PerformanceInterval, token: Int, outcome: SignpostOutcome) {
        log.withLock { $0.append("end \(interval.rawValue) #\(token) \(outcome.rawValue)") }
    }
}

@Suite struct PerformanceSignpostsTests {
    @Test func launchEndsOnceAtHomesFirstContent() {
        let recorder = ListRecorder()
        let signposts = PerformanceSignposts(recorder: recorder)
        signposts.homeShowedContent() // before the launch began: nothing
        signposts.launchBegan()
        signposts.launchBegan() // a second begin is ignored
        signposts.homeShowedContent()
        signposts.homeShowedContent() // Home loading again doesn't end it again
        signposts.launchBegan() // nor start a second launch
        #expect(recorder.entries == ["begin Launch to Home #1", "end Launch to Home #1 done"])
    }

    @Test func playToDecisionToFirstFrame() {
        let recorder = ListRecorder()
        let signposts = PerformanceSignposts(recorder: recorder)
        signposts.playStarted()
        signposts.playDecided()
        signposts.playDecided() // a second decision (a quality change) is not a new phase
        signposts.playShowedFirstFrame()
        signposts.playShowedFirstFrame()
        #expect(recorder.entries == [
            "begin Play to first frame #1", "begin Play to decision #2",
            "end Play to decision #2 done", "begin Decision to first frame #3",
            "end Decision to first frame #3 done", "end Play to first frame #1 done",
        ])
    }

    @Test func aNewPlayCancelsTheOpenOne() {
        let recorder = ListRecorder()
        let signposts = PerformanceSignposts(recorder: recorder)
        signposts.playStarted()
        signposts.playStarted()
        #expect(recorder.entries == [
            "begin Play to first frame #1", "begin Play to decision #2",
            "end Play to decision #2 cancelled", "end Play to first frame #1 cancelled",
            "begin Play to first frame #3", "begin Play to decision #4",
        ])
    }

    @Test func endsWithoutABeginDoNothing() {
        let recorder = ListRecorder()
        let signposts = PerformanceSignposts(recorder: recorder)
        signposts.playDecided()
        signposts.playShowedFirstFrame()
        signposts.playEnded(.failed)
        #expect(recorder.entries.isEmpty)
    }

    @Test func aFailureBeforeTheFirstFrameEndsBothIntervals() {
        let recorder = ListRecorder()
        let signposts = PerformanceSignposts(recorder: recorder)
        signposts.playStarted()
        signposts.playDecided()
        signposts.playEnded(.failed)
        signposts.playShowedFirstFrame() // too late: nothing open
        #expect(recorder.entries.suffix(2) == ["end Decision to first frame #3 failed", "end Play to first frame #1 failed"])
        #expect(recorder.entries.count == 6)
    }

    @Test func theSystemRecorderBalancesItsIntervals() {
        // OSSignposter can't be read back in a test; this checks it neither
        // crashes nor ends an interval twice.
        let recorder = OSPerformanceRecorder()
        let token = recorder.begin(.launchToHome)
        recorder.end(.launchToHome, token: token, outcome: .done)
        recorder.end(.launchToHome, token: token, outcome: .done)
        for interval in PerformanceInterval.allCases {
            recorder.end(interval, token: recorder.begin(interval), outcome: .cancelled)
        }
    }
}
