package media.watchmy.client.core.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StillWatchingTest {
    private var now = 0L
    private val clock = object : Clock {
        override fun elapsedMs() = now

        override fun wallMs() = now
    }
    private val tracker = StillWatching(clock)

    @Test
    fun neverBeforeAPlay() {
        now = 200 * MIN
        assertFalse(tracker.shouldAsk())
    }

    @Test
    fun ninetyMinutesWithoutInput() {
        tracker.notePlay()
        now = 89 * MIN
        assertFalse(tracker.shouldAsk())
        now = 90 * MIN
        assertTrue(tracker.shouldAsk())
        tracker.noteInput()
        assertFalse(tracker.shouldAsk())
    }

    @Test
    fun threeEpisodesWithoutInputOnceTheSessionIsLong() {
        tracker.notePlay()
        now = 30 * MIN
        tracker.noteInput()
        repeat(3) { tracker.noteAutoAdvance() }
        // Three in a row, but the session is not 90 min old yet.
        now = 80 * MIN
        assertFalse(tracker.shouldAsk())
        now = 90 * MIN
        assertTrue(tracker.shouldAsk())
    }

    @Test
    fun inputResetsTheEpisodeCount() {
        tracker.notePlay()
        now = 100 * MIN
        tracker.noteInput()
        repeat(2) { tracker.noteAutoAdvance() }
        assertFalse(tracker.shouldAsk())
        tracker.noteAutoAdvance()
        assertTrue(tracker.shouldAsk())
        tracker.noteInput()
        assertFalse(tracker.shouldAsk())
    }

    @Test
    fun theSessionStartsWithTheFirstPlay() {
        now = 500 * MIN
        tracker.notePlay()
        now = 550 * MIN
        tracker.notePlay()
        now = 589 * MIN
        assertFalse(tracker.shouldAsk())
        now = 590 * MIN
        assertTrue(tracker.shouldAsk())
    }
}
