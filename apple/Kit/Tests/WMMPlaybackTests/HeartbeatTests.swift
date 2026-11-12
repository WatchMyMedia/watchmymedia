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
}
