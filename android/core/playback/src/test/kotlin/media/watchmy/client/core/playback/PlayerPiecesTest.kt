package media.watchmy.client.core.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import media.watchmy.client.core.playback.player.PlayerFailure
import media.watchmy.client.core.playback.player.PlayerSource
import media.watchmy.client.core.playback.player.TextSelection
import media.watchmy.client.core.playback.session.MediaSessionHost
import media.watchmy.client.model.MediaToken
import media.watchmy.client.model.StreamQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pieces both controllers share: the switch machine, the token clock, the quality choice and the player host. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerPiecesTest {
    @Test
    fun `a switch is taken once, accepted by its own load's first frame, and reported until answered`() {
        val s = OutputSwitch<String>(keepFallbackUntilReported = false)
        assertTrue(s.take(3))
        assertFalse("A resend changes nothing", s.take(3))
        assertFalse("Nor does one at or below the generation playing", s.take(4, floor = 4))
        s.begin(5, "old.m3u8", load = 2)
        assertTrue(s.awaitingFrame)
        assertNull("The previous load's frame", s.frame(1))
        assertEquals(5L, s.frame(2))
        assertNull("Live TV drops what to go back to at the frame", s.pending)
        assertEquals(5L, s.accepted)
        assertFalse(s.reported(null, null))
        assertTrue(s.reported(5, null))
        assertNull(s.accepted)
    }

    @Test
    fun `a file keeps the old stream until the server takes the switch, and a failure goes back to it`() {
        val s = OutputSwitch<String>(keepFallbackUntilReported = true)
        s.take(1)
        s.begin(1, "previous", load = 7)
        s.frame(7)
        assertEquals("previous", s.pending?.fallback)
        assertFalse(s.awaitingFrame)
        // An error after the frame, before the server answered: back to the old stream, reported failed.
        assertEquals("previous", s.reject()?.fallback)
        assertNull("Not reported as shown", s.accepted)
        assertEquals(1L, s.failed)
        s.reported(null, 1)
        assertNull(s.failed)
    }

    @Test
    fun `a reload abandons the switch and hands back its old session`() {
        val s = OutputSwitch<String>(keepFallbackUntilReported = true)
        s.take(2)
        s.begin(2, "s1", load = 1)
        s.frame(1)
        assertEquals("s1", s.abandon()?.fallback)
        assertNull(s.pending)
        assertNull(s.accepted)
        s.failed(3)
        s.reset()
        assertNull(s.failed)
        assertTrue("A reset takes generations from the start again", s.take(1))
    }

    @Test
    fun `a token renews in its last minute, and a device clock ahead of the server's can't cause a loop`() {
        var elapsed = 0L
        var wall = 1_000_000L
        val clock = object : Clock {
            override fun elapsedMs() = elapsed
            override fun wallMs() = wall
        }
        val tokens = MediaTokenClock(clock)
        assertNull(tokens.fresh())
        assertFalse(tokens.due)
        tokens.accept(MediaToken("t1", wall + 6 * 3_600_000L))
        elapsed = 6 * 3_600_000L - 61_000
        assertEquals("t1", tokens.fresh()?.token)
        elapsed += 2_000
        assertTrue(tokens.due)
        assertNull(tokens.fresh())
        // Expired already by this device's clock: trusted for half an hour anyway.
        elapsed = 0
        wall += 10 * 3_600_000L
        tokens.accept(MediaToken("t2", wall - 3_600_000L))
        elapsed = 25 * 60_000L
        assertFalse(tokens.due)
        elapsed = 30 * 60_000L
        assertTrue(tokens.due)
        tokens.forget()
        assertNull(tokens.token)
        assertFalse(tokens.due)
    }

    @Test
    fun `a pick holds for the player and is saved for the network in use`() = runTest {
        val prefs = FakePreferences()
        var metered = false
        val choice = QualityChoice(prefs, MeteredNetwork { metered })
        assertEquals(StreamQuality.Original, choice.current())
        prefs.setMobileQuality(StreamQuality.Low)
        metered = true
        assertEquals("On mobile data, that network's default", StreamQuality.Low, choice.current())
        choice.pick(StreamQuality.Hd, this)
        runCurrent()
        assertTrue(choice.picked)
        assertEquals(StreamQuality.Hd, prefs.device.value.mobileQuality)
        assertEquals("The Default quality is left alone", StreamQuality.Original, prefs.quality.value)
        metered = false
        assertEquals("Held whatever the network does", StreamQuality.Hd, choice.current())
    }

    @Test
    fun `the host posts the player's callbacks, numbers its loads and ticks`() = runTest {
        val clock = TestClock(testScheduler)
        val player = FakeMediaPlayer(clock)
        val visibility = FakeVisibility()
        val host = PlayerHost(TestScope(StandardTestDispatcher(testScheduler)), { player }, MediaSessionHost.None, visibility, clock)
        val heard = mutableListOf<String>()
        val events = object : PlayerHost.Events {
            override fun onChanged() { heard += "changed" }
            override fun onError(failure: PlayerFailure) { heard += "error" }
            override fun onFirstFrame(load: Int) { heard += "frame $load" }
        }
        assertEquals(player, host.ensure(events) { it })
        assertEquals("Made once", player, host.ensure(events) { it })
        host.load(SOURCE)
        assertTrue("Posted, not called inside the player's call", heard.isEmpty())
        runCurrent()
        assertEquals(listOf("changed", "frame 1"), heard)
        var seen: Boolean? = null
        host.followVisibility { seen = it }
        visibility.set(false)
        assertEquals(false, seen)
        val ticks = mutableListOf<Long>()
        host.startTicker { ticks += it }
        advanceTimeBy(PlayerHost.TICK_MS * 2 + 1)
        assertEquals(listOf(PlayerHost.TICK_MS, PlayerHost.TICK_MS), ticks)
        host.release()
        assertTrue(player.released)
        assertFalse(host.ticking)
        advanceTimeBy(PlayerHost.TICK_MS * 4)
        assertEquals(2, ticks.size)
    }

    private companion object {
        val SOURCE = PlayerSource("http://srv/a.m3u8", hls = true, live = false, startPositionMs = 0, playWhenReady = true,
            subtitles = emptyList(), text = TextSelection.Off, audioOrdinal = null, title = "A", subtitle = null)
    }
}
