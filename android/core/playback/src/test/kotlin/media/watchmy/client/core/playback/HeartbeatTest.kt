package media.watchmy.client.core.playback

import org.junit.Assert.assertFalse
import media.watchmy.client.core.playback.player.PlayerPhase
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.PlayState
import media.watchmy.client.model.PlayerStatus
import media.watchmy.client.model.ProgressReply
import media.watchmy.client.model.StreamQuality
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartbeatTest {
    private val h = Harness()

    @After
    fun tearDown() = h.release()

    private fun startPlaying() = h.start(PlayRequest(1, 0, "Harbor Lights"), decision())

    @Test
    fun cadenceAndStates() {
        startPlaying()
        assertEquals(PlayerStatus.Playing, h.state.status)
        assertEquals(listOf(PlayState.Playing), h.states)

        // Every 10 s while playing.
        h.advance(9_750)
        assertEquals(1, h.gateway.reports.size)
        h.advance(250)
        assertEquals(2, h.gateway.reports.size)
        h.advance(10_000)
        assertEquals(3, h.gateway.reports.size)

        // A state change reports at once; then every 30 s while paused.
        h.controller.pause()
        h.run()
        assertEquals(PlayState.Paused, h.states.last())
        assertEquals(4, h.gateway.reports.size)
        h.advance(29_750)
        assertEquals(4, h.gateway.reports.size)
        h.advance(250)
        assertEquals(listOf(PlayState.Paused, PlayState.Paused), h.states.takeLast(2))

        // Buffering: at once, then every 30 s.
        h.controller.play()
        h.run()
        h.player.buffer()
        h.run()
        assertEquals(listOf(PlayState.Playing, PlayState.Buffering), h.states.takeLast(2))
        val before = h.gateway.reports.size
        h.advance(29_750)
        assertEquals(before, h.gateway.reports.size)
        h.advance(250)
        assertEquals(PlayState.Buffering, h.states.last())
        h.player.ready()
        h.run()
        assertEquals(PlayState.Playing, h.states.last())

        // Leaving reports "stopped" and ends the server's session.
        h.controller.stop()
        h.run()
        assertEquals(PlayState.Stopped, h.states.last())
        assertEquals(listOf("s1"), h.gateway.stoppedSessions)
        h.advance(60_000)
        assertEquals(PlayState.Stopped, h.states.last())

        val r = h.gateway.reports
        assertTrue(r.all { it.fileId == 1L && it.playId == "a1" && it.durationMs == 50 * MIN })
        assertEquals(20_000L, r[2].positionMs)
        assertEquals(30_000L, r.first { it.state == PlayState.Paused }.bufferedMs)
    }

    @Test
    fun pausingOrStoppingPartwayGoesOnWatchNext() {
        startPlaying()
        h.advance(60_000)
        h.controller.pause()
        h.run()
        assertEquals(1, h.watchNext.updates.size)
        assertEquals(60_000L, h.watchNext.updates.last().positionMs)
        assertEquals(50 * MIN, h.watchNext.updates.last().durationMs)
        h.controller.stop()
        h.run()
        assertEquals(2, h.watchNext.updates.size)
        assertTrue(h.watchNext.removals.isEmpty())
    }

    @Test
    fun aNewPlayIdReplacesTheOld() {
        h.gateway.reply = { r -> ProgressReply(completed = false, stopMessage = null, newPlayId = if (r.playId == "a1") "b2" else null) }
        startPlaying()
        h.advance(10_000)
        assertEquals(listOf("a1", "b2"), h.gateway.reports.map { it.playId })
        // Re-requests carry it too.
        h.gateway.decide = { decision(playId = it.playId!!) }
        h.controller.selectQuality(StreamQuality.FullHd)
        h.run()
        assertEquals("b2", h.gateway.decisions.last().playId)
    }

    @Test
    fun theDirectiveStopsThePlayerAndTheReports() {
        h.gateway.reply = { r -> ProgressReply(completed = false, stopMessage = if (r.positionMs >= 20_000) "Server maintenance" else null, newPlayId = null) }
        startPlaying()
        h.advance(20_000)
        assertEquals("Server maintenance", h.state.stopMessage)
        assertEquals(PlayerPhase.Idle, h.player.phase)
        assertEquals(1, h.watchNext.updates.size)
        val sent = h.gateway.reports.size
        h.advance(60_000)
        h.controller.play()
        h.controller.stop()
        h.run()
        assertEquals(sent, h.gateway.reports.size)
        assertEquals("Server maintenance", h.state.stopMessage)
    }

    @Test
    fun backgroundingReportsClosedThenNothingUntilBack() {
        startPlaying()
        h.advance(20_000)
        h.visibility.set(false)
        h.run()
        assertEquals(PlayState.Closed, h.states.last())
        assertTrue(!h.player.playWhenReady)
        assertEquals(1, h.watchNext.updates.size)
        val sent = h.gateway.reports.size
        h.advance(120_000)
        assertEquals(sent, h.gateway.reports.size)

        // Back: the server answers the closed play with a new one.
        h.gateway.reply = { ProgressReply(completed = false, stopMessage = null, newPlayId = "c3") }
        h.visibility.set(true)
        h.run()
        assertEquals(PlayState.Paused, h.states.last())
        h.controller.play()
        h.run()
        assertEquals(PlayState.Playing, h.states.last())
        assertEquals("c3", h.gateway.reports.last().playId)
    }

    @Test
    fun playWhileAwayReportsAgainAndTheServerCarriesThePlayOn() {
        startPlaying()
        h.advance(20_000)
        h.visibility.set(false)
        h.run()
        assertEquals(PlayState.Closed, h.states.last())
        val sent = h.gateway.reports.size

        // Play from the notification or a headset, the app still away: "closed" ended the
        // play, so the server answers the next report with a new one that carries it on.
        h.gateway.reply = { r -> ProgressReply(completed = false, stopMessage = null, newPlayId = if (r.playId == "a1") "c3" else null) }
        h.controller.play()
        h.run()
        assertTrue(h.player.playWhenReady)
        assertEquals(PlayState.Playing, h.states.last())
        h.advance(10_000)
        assertEquals(listOf("a1" to PlayState.Playing, "c3" to PlayState.Playing), h.gateway.reports.drop(sent).map { it.playId to it.state })

        // Back in the app, it goes on as it was.
        h.visibility.set(true)
        h.run()
        assertEquals(PlayState.Playing, h.states.last())
    }

    @Test
    fun onATvLeavingTheAppStopsTheStreamAndItsSessionAndComingBackLoadsItWhereItWas() {
        val tv = Harness(stopInBackground = true)
        tv.start(PlayRequest(1, 0, "Harbor Lights", "S03 E07 · The Long Dark"), decision())
        tv.advance(20_000)
        tv.controller.pause()
        tv.visibility.set(false)
        tv.run()
        assertEquals(PlayState.Closed, tv.states.last())
        assertEquals("The decoder goes", PlayerPhase.Idle, tv.player.phase)
        assertEquals("The server's session ends now, not at its idle timeout", listOf("s1"), tv.gateway.stoppedSessions)
        val sent = tv.gateway.reports.size
        tv.advance(30 * MIN)
        assertEquals("Nothing while away: no reports, no new decision", sent, tv.gateway.reports.size)
        assertEquals(1, tv.gateway.decisions.size)

        tv.gateway.decide = { decision(sessionId = "s2") }
        tv.visibility.set(true)
        tv.run()
        assertEquals(2, tv.gateway.decisions.size)
        assertEquals("Where it was, in the same play", 20_000L, tv.gateway.decisions.last().startMs)
        assertEquals("a1", tv.gateway.decisions.last().playId)
        assertEquals(20_000L, tv.player.loads.last().startPositionMs)
        assertFalse("Paused, as it was left", tv.player.loads.last().playWhenReady)
        assertEquals("The ended session isn't ended twice", listOf("s1"), tv.gateway.stoppedSessions)
        tv.controller.play()
        tv.run()
        assertEquals(PlayState.Playing, tv.states.last())
    }

    @Test
    fun onATvPlayWhileAwayLoadsTheStreamAgainPlaying() {
        val tv = Harness(stopInBackground = true)
        tv.start(PlayRequest(1, 0, "Harbor Lights"), decision())
        tv.advance(10_000)
        tv.visibility.set(false)
        tv.run()
        tv.controller.play()
        tv.run()
        assertEquals(2, tv.gateway.decisions.size)
        assertTrue(tv.player.loads.last().playWhenReady)
    }

    @Test
    fun releaseStillSendsTheLastReport() {
        startPlaying()
        h.advance(5_000)
        h.controller.release()
        h.run()
        assertEquals(PlayState.Stopped, h.states.last())
        assertEquals(5_000L, h.gateway.reports.last().positionMs)
        assertTrue(h.player.released)
    }
}
