package media.watchmy.client.core.playback

import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.PlayState
import media.watchmy.client.model.PlaybackPreferences
import media.watchmy.client.model.PlayerEvent
import media.watchmy.client.model.Segment
import media.watchmy.client.model.SegmentKind
import media.watchmy.client.model.SkipMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkipTest {
    private val intro = Segment(SegmentKind.Intro, 60_000, 105_000)
    private var h = Harness()

    @After
    fun tearDown() = h.release()

    /** Plays from 55 s, 5 s before the intro, with the intro's skip mode set to [mode]. */
    private fun play(mode: SkipMode, segments: List<Segment> = listOf(intro), from: Long = 55_000) {
        h.release()
        h = Harness(PlaybackPreferences(skip = PlaybackPreferences.DEFAULT_SKIP + (SegmentKind.Intro to mode)))
        h.start(PlayRequest(1, from, "Harbor Lights"), decision(segments = segments))
    }

    @Test
    fun noneShowsNothing() {
        play(SkipMode.None)
        h.advance(10_000)
        assertNull(h.state.skip)
        assertEquals(intro, h.state.segments.single())
        assertTrue(h.player.seeks.isEmpty())
    }

    @Test
    fun askShowsAButton() {
        play(SkipMode.Ask)
        h.advance(4_750)
        assertNull(h.state.skip)
        h.advance(250)
        val skip = h.state.skip!!
        assertEquals(intro, skip.segment)
        assertEquals("Skip Intro", skip.label)
        assertNull(skip.autoProgress)
        h.advance(20_000)
        assertTrue(h.player.seeks.isEmpty())
        h.controller.skip()
        h.run()
        assertEquals(listOf(105_000L), h.player.seeks)
        assertNull(h.state.skip)
        assertTrue(h.events.isEmpty())
    }

    @Test
    fun autoSkipsAtOnce() {
        play(SkipMode.Auto)
        h.advance(5_000)
        assertEquals(listOf(105_000L), h.player.seeks)
        assertNull(h.state.skip)
    }

    @Test
    fun autoDelayFillsForThreeSecondsOfPlayThenSkips() {
        play(SkipMode.AutoDelay)
        h.advance(5_000)
        assertEquals(0f, h.state.skip!!.autoProgress)
        h.advance(1_500)
        assertEquals(0.5f, h.state.skip!!.autoProgress)
        // The fill holds while paused.
        h.controller.pause()
        h.advance(10_000)
        assertEquals(0.5f, h.state.skip!!.autoProgress)
        assertTrue(h.player.seeks.isEmpty())
        h.controller.play()
        h.advance(1_250)
        assertEquals(2_750f / 3_000f, h.state.skip!!.autoProgress!!, 0.001f)
        assertTrue(h.player.seeks.isEmpty())
        h.advance(250)
        assertEquals(listOf(105_000L), h.player.seeks)
        assertNull(h.state.skip)
    }

    @Test
    fun aSegmentSeekedIntoIsNeverSkippedByItself() {
        play(SkipMode.Auto, from = 10_000)
        h.controller.seekTo(70_000)
        h.run()
        val skip = h.state.skip!!
        assertNull(skip.autoProgress)
        h.advance(20_000)
        assertEquals(listOf(70_000L), h.player.seeks)

        play(SkipMode.AutoDelay, from = 10_000)
        h.controller.seekTo(70_000)
        h.advance(10_000)
        assertNull(h.state.skip!!.autoProgress)
        assertEquals(listOf(70_000L), h.player.seeks)
        // The button still works.
        h.controller.skip()
        h.run()
        assertEquals(listOf(70_000L, 105_000L), h.player.seeks)
    }

    @Test
    fun aSegmentUnderThreeSecondsGetsNoButton() {
        play(SkipMode.Ask, segments = listOf(Segment(SegmentKind.Intro, 60_000, 62_000)))
        h.advance(6_000)
        assertNull(h.state.skip)
    }

    @Test
    fun aSkipToTheEndMarksWatchedAndLeaves() {
        val credits = Segment(SegmentKind.Credits, 47 * MIN, null)
        // A film: no next episode, so the final credits get a Skip button.
        h.start(PlayRequest(1, 47 * MIN + 10_000, "Lighthouse"), decision(segments = listOf(credits), creditsMs = 47 * MIN, watchedAtMs = 48 * MIN))
        assertEquals("Skip Credits", h.state.skip!!.label)
        h.controller.skip()
        h.run()
        assertEquals(listOf<PlayerEvent>(PlayerEvent.Exit), h.events)
        val last = h.gateway.reports.last()
        assertEquals(PlayState.Stopped, last.state)
        assertEquals(50 * MIN, last.positionMs)
        assertEquals(listOf(1L), h.watchNext.removals)
        assertTrue(h.watchNext.updates.isEmpty())
        // The screen's own stop afterwards reports nothing more.
        val sent = h.gateway.reports.size
        h.controller.stop()
        h.run()
        assertEquals(sent, h.gateway.reports.size)
    }
}
