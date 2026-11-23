package media.watchmy.client.core.playback

import media.watchmy.client.core.playback.player.PlayerPhase
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.PlayerStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A player that sits buffering with plenty in hand and doesn't move, as
 * Media3 1.11 did after two quick seeks on a passthrough audio track: a seek
 * where it is, then a new load, then an error that Play retries.
 */
class StuckPlayerTest {
    private val h = Harness()

    @After
    fun tearDown() = h.release()

    /** Plays 20 s of a VOD stream, then sticks: buffering, its buffer full (the fake keeps 30 s ahead), not moving. */
    private fun playThenStick() {
        h.start(PlayRequest(1, 0, "Lighthouse"), decision())
        h.advance(20_000)
        h.player.buffer()
        h.run()
    }

    @Test
    fun aStuckPlayerIsSeekedWhereItIs() {
        playThenStick()
        h.advance(STUCK_MS - 1_000)
        assertTrue(h.player.seeks.isEmpty())
        assertEquals(PlayerStatus.Buffering, h.state.status)
        h.advance(1_500)
        assertEquals(listOf(20_000L), h.player.seeks)
        assertEquals(1, h.gateway.decisions.size)
    }

    @Test
    fun aPlayerStillStuckAfterTheSeekLoadsTheStreamAgainThere() {
        playThenStick()
        h.advance(STUCK_MS + 500)
        h.advance(STUCK_MS + 500)
        assertEquals(1, h.player.seeks.size)
        assertEquals(2, h.gateway.decisions.size)
        assertEquals(20_000L, h.gateway.decisions.last().startMs)
        assertEquals(20_000L, h.player.loads.last().startPositionMs)
        assertEquals(PlayerStatus.Playing, h.state.status)
    }

    @Test
    fun aPlayerStuckAgainAfterTheNewLoadStopsWithAnErrorThatPlayRetries() {
        playThenStick()
        h.advance(2 * (STUCK_MS + 500))
        h.player.buffer()
        h.advance(STUCK_MS + 500)
        assertEquals(PlayerStatus.Error, h.state.status)
        assertEquals(STUCK_MESSAGE, h.state.error)
        assertEquals(PlayerPhase.Idle, h.player.phase)
        h.advance(60_000)
        assertEquals("no more loads until the viewer asks", 2, h.gateway.decisions.size)

        h.controller.play()
        h.run()
        assertEquals(3, h.gateway.decisions.size)
        assertEquals(PlayerStatus.Playing, h.state.status)
        assertNull(h.state.error)
    }

    @Test
    fun aPlayThatGotGoingAgainStartsOverAtTheSeek() {
        playThenStick()
        h.advance(STUCK_MS + 500)
        h.player.ready()
        // A minute of playing starts the steps over, as it does the recoveries from errors.
        h.advance(61_000)
        h.player.buffer()
        h.advance(STUCK_MS + 500)
        assertEquals(2, h.player.seeks.size)
        assertEquals(1, h.gateway.decisions.size)
    }

    @Test
    fun waitingOnTheNetworkIsNotStuck() {
        h.player.bufferAheadMs = 2_000
        playThenStick()
        h.advance(5 * STUCK_MS)
        assertTrue(h.player.seeks.isEmpty())
        assertEquals(1, h.gateway.decisions.size)
        assertEquals(PlayerStatus.Buffering, h.state.status)
    }

    @Test
    fun aLoadThatHasNotStartedYetIsNotStuck() {
        // A slow decoder's first frame: buffering from the load on, with the buffer full.
        h.player.readyOnLoad = false
        h.start(PlayRequest(1, 0, "Lighthouse"), decision())
        h.advance(5 * STUCK_MS)
        assertTrue(h.player.seeks.isEmpty())
        assertEquals(1, h.gateway.decisions.size)
    }

    @Test
    fun aPausedPlayIsNotStuck() {
        playThenStick()
        h.controller.pause()
        h.advance(5 * STUCK_MS)
        assertTrue(h.player.seeks.isEmpty())
        assertEquals(1, h.gateway.decisions.size)
    }

    @Test
    fun aPlayerThatMovesWhileBufferingIsNotStuck() {
        playThenStick()
        // Something keeps moving it (a slow decoder catching up, seeks): each move starts the count again.
        repeat(5) {
            h.advance(STUCK_MS - 2_000)
            h.player.seekTo(h.player.positionMs + 1_000)
            h.run()
        }
        assertEquals(5, h.player.seeks.size)
        assertEquals(1, h.gateway.decisions.size)
    }
}
