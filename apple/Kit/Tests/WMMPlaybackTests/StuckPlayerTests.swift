import Foundation
import Testing
@testable import WMMPlayback
import WMMModel

/// A player that sits buffering with plenty in hand and doesn't move, with
/// no error to retry on: a seek where it is, then a new load, then an error
/// that Play retries (android/core/playback/.../StuckPlayerTest.kt).
@MainActor
@Suite struct StuckPlayerTests {
    let h = Harness()

    /// Plays 20 s of a VOD stream, then sticks: buffering, its buffer full (the fake keeps 30 s ahead), not moving.
    private func playThenStick() async {
        await h.start(PlayRequest(fileID: 1, startMs: 0, title: "Lighthouse"), decision())
        await h.advance(20_000)
        h.player.buffer()
        await h.run()
    }

    @Test func aStuckPlayerIsSeekedWhereItIs() async {
        await playThenStick()
        await h.advance(stuckMs - 1_000)
        #expect(h.player.seeks.isEmpty)
        #expect(h.state.status == .buffering)
        await h.advance(1_500)
        #expect(h.player.seeks == [20_000])
        #expect(h.gateway.decisions.count == 1)
    }

    @Test func aPlayerStillStuckAfterTheSeekLoadsTheStreamAgainThere() async {
        await playThenStick()
        await h.advance(stuckMs + 500)
        await h.advance(stuckMs + 500)
        #expect(h.player.seeks.count == 1)
        #expect(h.gateway.decisions.count == 2)
        #expect(h.gateway.decisions.last?.startMs == 20_000)
        #expect(h.player.loads.last?.startPositionMs == 20_000)
        #expect(h.state.status == .playing)
    }

    @Test func aPlayerStuckAgainAfterTheNewLoadStopsWithAnErrorThatPlayRetries() async {
        await playThenStick()
        await h.advance(2 * (stuckMs + 500))
        h.player.buffer()
        await h.run()
        let at = h.state.positionMs
        await h.advance(stuckMs + 500)
        #expect(h.state.status == .error)
        #expect(h.state.error == stuckMessage)
        #expect(h.player.phase == .idle)
        // The stopped player forgot where it was; the scrubber didn't.
        #expect(h.player.positionMs == 0)
        #expect(h.state.positionMs == at)
        await h.advance(60_000)
        #expect(h.gateway.decisions.count == 2, "no more loads until the viewer asks")

        h.controller.play()
        await h.run()
        #expect(h.gateway.decisions.count == 3)
        #expect(h.gateway.decisions.last?.startMs == at)
        #expect(h.player.loads.last?.startPositionMs == at)
        #expect(h.player.loads.last?.playWhenReady == true)
        #expect(h.state.status == .playing)
        #expect(h.state.error == nil)
    }

    @Test func aSeekWhileStoppedStuckIsWherePlayStarts() async {
        await playThenStick()
        await h.advance(2 * (stuckMs + 500))
        h.player.buffer()
        await h.run()
        await h.advance(stuckMs + 500)
        #expect(h.state.status == .error)
        h.controller.seek(toMs: 5 * minute)
        await h.run()
        #expect(h.state.positionMs == 5 * minute)
        h.controller.play()
        await h.run()
        #expect(h.gateway.decisions.last?.startMs == 5 * minute)
        #expect(h.player.loads.last?.startPositionMs == 5 * minute)
    }

    @Test func aPlayThatGotGoingAgainStartsOverAtTheSeek() async {
        await playThenStick()
        await h.advance(stuckMs + 500)
        h.player.ready()
        // A minute of playing starts the steps over, as it does the recoveries from errors.
        await h.advance(61_000)
        h.player.buffer()
        await h.run()
        await h.advance(stuckMs + 500)
        #expect(h.player.seeks.count == 2)
        #expect(h.gateway.decisions.count == 1)
    }

    @Test func waitingOnTheNetworkIsNotStuck() async {
        h.player.bufferAheadMs = 2_000
        await playThenStick()
        await h.advance(5 * stuckMs)
        #expect(h.player.seeks.isEmpty)
        #expect(h.gateway.decisions.count == 1)
        #expect(h.state.status == .buffering)
    }

    @Test func aLoadThatHasNotStartedYetIsNotStuck() async {
        // A slow first frame: buffering from the load on, with the buffer full.
        h.player.readyOnLoad = false
        await h.start(PlayRequest(fileID: 1, startMs: 0, title: "Lighthouse"), decision())
        await h.advance(5 * stuckMs)
        #expect(h.player.seeks.isEmpty)
        #expect(h.gateway.decisions.count == 1)
    }

    @Test func aPausedPlayIsNotStuck() async {
        await playThenStick()
        h.controller.pause()
        await h.run()
        await h.advance(5 * stuckMs)
        #expect(h.player.seeks.isEmpty)
        #expect(h.gateway.decisions.count == 1)
    }

    @Test func aPlayerThatMovesWhileBufferingIsNotStuck() async {
        await playThenStick()
        // Something keeps moving it (a slow decoder catching up, seeks): each move starts the count again.
        for _ in 0..<5 {
            await h.advance(stuckMs - 2_000)
            h.player.seek(toMs: h.player.positionMs + 1_000)
            await h.run()
        }
        #expect(h.player.seeks.count == 5)
        #expect(h.gateway.decisions.count == 1)
    }

    @Test func overAirPlayTheBufferHereSaysNothing() async {
        await playThenStick()
        h.player.airPlay(true)
        await h.run()
        await h.advance(5 * stuckMs)
        #expect(h.player.seeks.isEmpty)
        #expect(h.gateway.decisions.count == 1)
    }
}
