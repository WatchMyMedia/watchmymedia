import Foundation
import Testing
@testable import WMMPlayback
import WMMModel

/// The pieces the file and Live TV controllers share (PLAN-APPLE-REVIEW.md §5.1).
@MainActor
@Suite struct SharedPiecesTests {
    @Test func aSwitchIsTakenOnceAndReportedUntilHeard() {
        var s = SwitchGenerations<Int>()
        let first = s.take(2, playing: 1), again = s.take(2), playing = s.take(3, playing: 3)
        #expect(first)
        #expect(!again, "a directive is acted on once")
        #expect(!playing, "nor one the server already plays")
        s.accept(2)
        s.fail(1)
        let none = s.acknowledge(accepted: nil), older = s.acknowledge(accepted: 1)
        #expect(!none)
        #expect(!older, "a reply to an older report leaves the newer one to send")
        #expect(s.accepted == 2)
        let heard = s.acknowledge(accepted: 2)
        #expect(heard)
        #expect(s.accepted == nil)
        s.acknowledge(failed: 1)
        #expect(s.failed == nil)
    }

    @Test func thePumpKeepsOneOutAndTheNewestWaiting() async {
        final class Owner {}
        var gates: [CheckedContinuation<Void, Never>] = []
        var sent: [Int] = []
        let pump = HeartbeatPump<Int>(owner: Owner(), coalesce: { n, waiting, behind in
            if behind { waiting = [n] } else { waiting.append(n) }
        }, deliver: { n in
            sent.append(n)
            await withCheckedContinuation { gates.append($0) }
        })
        pump.submit(1)
        for _ in 0..<10 { await Task.yield() }
        #expect(pump.sending)
        pump.submit(2)
        pump.submit(3)
        #expect(sent == [1], "one out at a time")
        gates.removeFirst().resume()
        for _ in 0..<10 { await Task.yield() }
        #expect(sent == [1, 3], "the newest waiting replaced the one before it")
        gates.removeFirst().resume()
        await pump.drained()
        #expect(!pump.busy)
    }
}
