package media.watchmy.client.mobile.live

import media.watchmy.client.model.LiveCastPhase
import media.watchmy.client.model.LiveCastState
import media.watchmy.client.model.LiveChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The remote's words, from the LiveCast and TabLiveCast boards. */
class LiveCastWordingTest {
    private val tv = "Living Room TV"
    private fun state(phase: LiveCastPhase, reason: String? = null, error: String? = null, receiverLive: Boolean? = true) =
        LiveCastState(deviceName = tv, receiverLive = receiverLive, phase = phase, channelId = "ch-41", number = "4.1", name = "NFLD-HD", pauseLeftSeconds = 220.takeIf { phase == LiveCastPhase.Paused }, reason = reason, error = error)

    @Test
    fun `the status line and badge follow the TV`() {
        assertEquals("Playing on Living Room TV", castStatusLine(state(LiveCastPhase.Playing), tv))
        assertEquals("Paused on Living Room TV", castStatusLine(state(LiveCastPhase.Paused), tv))
        assertEquals("Tuning 4.1 on Living Room TV…", castStatusLine(state(LiveCastPhase.Tuning), tv))
        assertEquals("Connected to Living Room TV", castStatusLine(state(LiveCastPhase.Failed, "tuners_busy"), tv))
        assertEquals("Connected to Living Room TV", castStatusLine(state(LiveCastPhase.Idle, receiverLive = false), tv))
        assertEquals("Living Room TV", castStatusLine(state(LiveCastPhase.Stopped), tv))
        assertEquals(listOf("LIVE", "PAUSED", "TUNING", "STOPPED", "NOT PLAYING"), listOf(LiveCastPhase.Playing, LiveCastPhase.Paused, LiveCastPhase.Tuning, LiveCastPhase.Stopped, LiveCastPhase.Failed).map { castBadge(state(it)) })
    }

    @Test
    fun `the mini controller says where the channel is, and is gone for a TV that can't play Live TV`() {
        assertEquals("Playing on Living Room TV", castMiniLine(state(LiveCastPhase.Playing), tv))
        assertEquals("Paused on Living Room TV · tuner back in 4 min", castMiniLine(state(LiveCastPhase.Paused), tv))
        assertEquals("Tuning on Living Room TV…", castMiniLine(state(LiveCastPhase.Tuning), tv))
        assertEquals("Living Room TV · all tuners in use", castMiniLine(state(LiveCastPhase.Failed, "tuners_busy"), tv))
        assertEquals("Stopped from Activity on Living Room TV", castMiniLine(state(LiveCastPhase.Stopped), tv))
        assertNull(castMiniLine(state(LiveCastPhase.Playing, receiverLive = false), tv))
        assertNull("nothing on the TV", castMiniLine(state(LiveCastPhase.Idle), tv))
        assertNull(castMiniLine(LiveCastState(deviceName = tv, receiverLive = true), tv))
    }

    @Test
    fun `problems say why nothing plays, with the boards' actions`() {
        val notLive = castProblem(state(LiveCastPhase.Idle, receiverLive = false), tv, "4.1", tablet = false)!!
        assertEquals("Living Room TV can’t play Live TV yet", notLive.title)
        assertEquals("It needs a newer WatchMyMedia on the TV. Movies and shows still cast to it, and 4.1 can play here.", notLive.body)
        assertTrue(notLive.here)
        assertEquals("…and 4.1 can play on this tablet.", "…" + castProblem(state(LiveCastPhase.Idle, receiverLive = false), tv, "4.1", tablet = true)!!.body.substringAfter("it, "))
        val busy = castProblem(state(LiveCastPhase.Failed, "tuners_busy"), tv, "4.1", tablet = false)!!
        assertEquals("All tuners are in use", busy.title)
        assertEquals("Nothing was taken from anyone watching. 4.1 plays on Living Room TV once someone stops.", busy.body)
        assertTrue(busy.retry)
        val stopped = castProblem(state(LiveCastPhase.Stopped, "stopped_by_admin"), tv, "4.1", tablet = false)!!
        assertEquals("Stopped from Activity", stopped.title)
        assertEquals("An admin stopped 4.1 on Living Room TV.", stopped.body)
        assertTrue(stopped.guide)
        val signal = castProblem(state(LiveCastPhase.Failed, error = "No signal on 4.1. The tuner couldn’t pick up this channel."), tv, "4.1", tablet = false)!!
        assertEquals("No signal on 4.1", signal.title)
        assertEquals("The tuner couldn’t pick up this channel.", signal.body)
        assertNull(castProblem(state(LiveCastPhase.Playing), tv, "4.1", tablet = false))
        assertNull(castProblem(state(LiveCastPhase.Tuning), tv, "4.1", tablet = false))
    }

    @Test
    fun `On now starts two channels before the TV's, and keeps every channel`() {
        val channels = (1..6).map { LiveChannel("c$it", "s", "$it.1", "C$it") }
        assertEquals(listOf("c2", "c3", "c4", "c5", "c6", "c1"), remoteRows(channels, "c4").map { it.id })
        assertEquals(channels, remoteRows(channels, "c1"))
        assertEquals(channels, remoteRows(channels, null))
    }

    @Test
    fun `Watch says on TV while it casts`() {
        val c = LiveChannel("c", "s", "4.1", "NFLD-HD")
        assertEquals("Watch 4.1", watchLabel(c, null))
        assertEquals("Watch 4.1 on TV", watchLabel(c, tv))
        assertEquals("Watch 4.1 now on TV", watchLabel(c, tv, later = true))
        assertEquals("Watch 4.1 now", watchLabel(c, null, later = true))
    }
}
