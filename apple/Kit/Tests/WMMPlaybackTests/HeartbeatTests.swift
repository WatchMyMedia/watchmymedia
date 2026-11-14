import Foundation
import Testing
@testable import WMMPlayback
import WMMModel

/// android/core/playback/.../HeartbeatTest.kt.
@MainActor
@Suite struct HeartbeatTests {
    let h = Harness()

    private func startPlaying() async {
        await h.start(PlayRequest(fileID: 1, startMs: 0, title: "Harbor Lights"), decision())
    }

    @Test func cadenceAndStates() async {
        await startPlaying()
        #expect(h.state.status == .playing)
        #expect(h.states == [.playing])

        // Every 10 s while playing.
        await h.advance(9_750)
        #expect(h.gateway.reports.count == 1)
        await h.advance(250)
        #expect(h.gateway.reports.count == 2)
        await h.advance(10_000)
        #expect(h.gateway.reports.count == 3)

        // A state change reports at once; then every 30 s while paused.
        h.controller.pause()
        await h.run()
        #expect(h.states.last == .paused)
        #expect(h.gateway.reports.count == 4)
        await h.advance(29_750)
        #expect(h.gateway.reports.count == 4)
        await h.advance(250)
        #expect(Array(h.states.suffix(2)) == [.paused, .paused])

        // Buffering: at once, then every 30 s.
        h.controller.play()
        await h.run()
        h.player.buffer()
        await h.run()
        #expect(Array(h.states.suffix(2)) == [.playing, .buffering])
        let before = h.gateway.reports.count
        await h.advance(29_750)
        #expect(h.gateway.reports.count == before)
        await h.advance(250)
        #expect(h.states.last == .buffering)
        h.player.ready()
        await h.run()
        #expect(h.states.last == .playing)

        // Leaving reports "stopped" and ends the server's session.
        h.controller.stop()
        await h.run()
        #expect(h.states.last == .stopped)
        #expect(h.gateway.stoppedSessions == ["s1"])
        await h.advance(60_000)
        #expect(h.states.last == .stopped)

        let r = h.gateway.reports
        #expect(r.allSatisfy { $0.fileID == 1 && $0.playID == "a1" && $0.durationMs == 50 * minute })
        #expect(r[2].positionMs == 20_000)
        #expect(r.first { $0.state == .paused }?.bufferedMs == 30_000)
    }

    @Test func aNewPlayIDReplacesTheOld() async {
        h.gateway.reply = { r in ProgressReply(completed: false, newPlayID: r.playID == "a1" ? "b2" : nil) }
        await startPlaying()
        await h.advance(10_000)
        #expect(h.gateway.reports.map(\.playID) == ["a1", "b2"])
        // Re-requests carry it too.
        h.gateway.answer = { decision(playID: $0.playID!) }
        h.controller.selectQuality(.fullHD)
        await h.run()
        #expect(h.gateway.decisions.last?.playID == "b2")
    }

    @Test func theDirectiveStopsThePlayerAndTheReports() async {
        h.gateway.reply = { r in ProgressReply(completed: false, stopMessage: r.positionMs >= 20_000 ? "Server maintenance" : nil) }
        await startPlaying()
        await h.advance(20_000)
        #expect(h.state.stopMessage == "Server maintenance")
        #expect(h.state.status == .paused)
        #expect(h.player.phase == .idle)
        let sent = h.gateway.reports.count
        await h.advance(60_000)
        h.controller.play()
        h.controller.stop()
        await h.run()
        #expect(h.gateway.reports.count == sent)
        #expect(h.state.stopMessage == "Server maintenance")
    }

    @Test func closingReportsClosedThenNothingUntilBack() async {
        await startPlaying()
        await h.advance(20_000)
        h.controller.close()
        await h.run()
        #expect(h.states.last == .closed)
        #expect(!h.player.playWhenReady)
        // The OS is asked for time to send it, and told when it went.
        #expect(h.system.holds == 1)
        #expect(h.system.released == 1)
        let sent = h.gateway.reports.count
        await h.advance(120_000)
        #expect(h.gateway.reports.count == sent)

        // Back: the server answers the closed play with a new one.
        h.gateway.reply = { _ in ProgressReply(completed: false, newPlayID: "c3") }
        h.system.commands?.foreground()
        await h.run()
        #expect(h.states.last == .paused)
        h.controller.play()
        await h.run()
        #expect(h.states.last == .playing)
        #expect(h.gateway.reports.last?.playID == "c3")
    }

    @Test func playingAfterClosingWithoutComingBackReportsAgain() async {
        // Play from the lock screen while the app is still in the background.
        await startPlaying()
        h.controller.close()
        await h.run()
        h.system.commands?.play()
        await h.run()
        #expect(h.states.suffix(2) == [.closed, .playing])
    }

    @Test func releaseStillSendsTheLastReport() async {
        await startPlaying()
        await h.advance(5_000)
        h.controller.release()
        await h.run()
        #expect(h.states.last == .stopped)
        #expect(h.gateway.reports.last?.positionMs == 5_000)
        #expect(h.player.released)
        #expect(h.controller.player == nil)
        #expect(h.system.closed == 1)
        // Nothing more after release.
        h.controller.start(PlayRequest(fileID: 1, title: "Again"))
        await h.run()
        #expect(h.gateway.decisions.count == 1)
    }

    /// Moves virtual time on without waiting for the controller's work to end, which a held report never does.
    private func advanceHeld(_ ms: Int) async {
        await h.clock.advance(by: ms) { for _ in 0..<5 { await Task.yield() } }
    }

    @Test func aServerOutOfReachGetsOnlyTheNewestPositionThenTheStop() async {
        // PLAN-APPLE-REVIEW.md §3.1.4: each report waits for its time-out, so positions pile up behind it.
        await startPlaying()
        h.gateway.holdReports = true
        await advanceHeld(10_000)
        #expect(h.gateway.reports.map(\.positionMs) == [0, 10_000])
        await advanceHeld(30_000)
        h.controller.stop()
        await advanceHeld(0)
        h.gateway.releaseReports()
        await h.run()
        // The positions at 20 and 30 s were replaced by the one at 40 s; the stop and the session's end still go, in order.
        #expect(h.gateway.reports.map(\.positionMs) == [0, 10_000, 40_000, 40_000])
        #expect(h.states == [.playing, .playing, .playing, .stopped])
        #expect(h.gateway.stoppedSessions == ["s1"])
    }

    @Test func theQueueKeepsStopsAcknowledgementsAndOrder() {
        typealias Outgoing = RealPlaybackController.Outgoing
        func report(_ state: PlayState, at pos: Int, switchGeneration: Int64? = nil, failed: Int64? = nil, startup: Int? = nil) -> ProgressReport {
            ProgressReport(fileID: 1, positionMs: pos, durationMs: 50 * minute, playID: "a1", state: state, bufferedMs: 0,
                           switchGeneration: switchGeneration, switchFailedGeneration: failed, startupMs: startup)
        }
        func names(_ queue: [Outgoing]) -> [String] {
            queue.map {
                switch $0 {
                case let .report(r, g): "\(g):\(r.state.rawValue)@\(r.positionMs)\(r.switchGeneration.map { " ack\($0)" } ?? "")\(r.switchFailedGeneration.map { " failed\($0)" } ?? "")"
                case let .endSession(id): "end \(id)"
                }
            }
        }
        var queue: [Outgoing] = []
        for message: Outgoing in [
            .report(report(.playing, at: 10, startup: 900), generation: 1),
            .endSession("s0"),
            .report(report(.playing, at: 20), generation: 1),
            .report(report(.paused, at: 30), generation: 1),
        ] { RealPlaybackController.enqueue(message, into: &queue) }
        // One position per viewing, the newest, after what was queued before it; its startup time kept.
        #expect(names(queue) == ["end s0", "1:paused@30"])
        if case let .report(r, _)? = queue.last { #expect(r.startupMs == 900) }

        // A switch acknowledgement and a refused switch stay until a report carrying the same goes after them.
        RealPlaybackController.enqueue(.report(report(.playing, at: 40, switchGeneration: 5), generation: 1), into: &queue)
        RealPlaybackController.enqueue(.report(report(.buffering, at: 50), generation: 1), into: &queue)
        RealPlaybackController.enqueue(.report(report(.playing, at: 60, failed: 6), generation: 1), into: &queue)
        RealPlaybackController.enqueue(.report(report(.playing, at: 70, failed: 6), generation: 1), into: &queue)
        #expect(names(queue) == ["end s0", "1:playing@40 ack5", "1:playing@70 failed6"])

        // Stops and "closed" are never replaced, and another viewing's reports are its own.
        RealPlaybackController.enqueue(.report(report(.closed, at: 80), generation: 1), into: &queue)
        RealPlaybackController.enqueue(.report(report(.stopped, at: 80), generation: 1), into: &queue)
        RealPlaybackController.enqueue(.report(report(.playing, at: 0), generation: 2), into: &queue)
        RealPlaybackController.enqueue(.report(report(.playing, at: 10), generation: 2), into: &queue)
        #expect(names(queue) == ["end s0", "1:playing@40 ack5", "1:playing@70 failed6", "1:closed@80", "1:stopped@80", "2:playing@10"])

        // With nothing out waiting for the server, every report goes: a reload's "buffering" is heard.
        var flowing: [Outgoing] = []
        RealPlaybackController.enqueue(.report(report(.buffering, at: 10), generation: 1), into: &flowing, behindDelivery: false)
        RealPlaybackController.enqueue(.report(report(.playing, at: 10), generation: 1), into: &flowing, behindDelivery: false)
        #expect(names(flowing) == ["1:buffering@10", "1:playing@10"])
    }
}
