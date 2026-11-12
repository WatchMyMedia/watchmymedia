package media.watchmy.client.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekingTest {
    @Test
    fun aPressIsTenSeconds() {
        val a = SeekAccelerator()
        assertEquals(10_000, a.step(nowMs = 1_000, repeat = false))
        a.release()
        assertEquals(10_000, a.step(nowMs = 1_150, repeat = false))
    }

    @Test
    fun holdingAcceleratesAndRepeatsAreThrottled() {
        val a = SeekAccelerator()
        a.step(0, repeat = false)
        // Key repeat runs at about 20 a second; only one step every 200 ms counts.
        val steps = (1..200).map { i -> 500L + i * 50 }.associateWith { a.step(it, repeat = true) }
        assertEquals(10_000, steps.getValue(550))
        assertEquals(0, steps.getValue(600))
        val taken = steps.filterValues { it > 0 }
        taken.keys.zipWithNext().forEach { (x, y) -> assertTrue("$x→$y", y - x >= SeekAccelerator.REPEAT_INTERVAL_MS) }
        assertEquals(10_000, taken.entries.first { it.key in 1_000..1_999 }.value)
        assertEquals(30_000, taken.entries.first { it.key in 2_000..3_999 }.value)
        assertEquals(60_000, taken.entries.first { it.key in 4_000..6_999 }.value)
        assertEquals(SeekAccelerator.MAX_STEP_MS, taken.entries.first { it.key >= 7_000 }.value)
        // Steps never shrink while the key is held.
        taken.values.zipWithNext().forEach { (x, y) -> assertTrue(y >= x) }
    }

    @Test
    fun aRepeatWithoutAPressStartsOver() {
        val a = SeekAccelerator()
        a.step(0, repeat = false)
        a.step(9_000, repeat = true)
        a.release()
        assertEquals(10_000, a.step(9_500, repeat = true))
    }

    @Test
    fun pressesMoveTheTargetAndReleaseSendsIt() {
        val s = SeekState()
        s.press(forward = true, positionMs = 60_000, durationMs = 3_120_000, nowMs = 0, repeat = false)
        assertEquals(70_000L, s.targetMs)
        assertEquals(70_000L, s.displayed(60_100))
        assertTrue(s.holding)
        assertEquals(70_000L, s.release())
        assertFalse(s.holding)
        // A second tap before the player lands builds on the target, not the stale position.
        s.press(forward = true, positionMs = 60_200, durationMs = 3_120_000, nowMs = 400, repeat = false)
        assertEquals(80_000L, s.release())
        assertFalse(s.landed(60_300))
        assertTrue(s.landed(79_000))
        s.settle()
        assertNull(s.targetMs)
        assertEquals(80_500L, s.displayed(80_500))
    }

    @Test
    fun theTargetStaysInsideTheFile() {
        val s = SeekState()
        s.press(forward = false, positionMs = 4_000, durationMs = 3_120_000, nowMs = 0, repeat = false)
        assertEquals(0L, s.release())
        s.settle()
        s.press(forward = true, positionMs = 3_115_000, durationMs = 3_120_000, nowMs = 1_000, repeat = false)
        assertEquals(3_120_000L, s.release())
        s.settle()
        // Unknown duration: no upper bound.
        s.press(forward = true, positionMs = 5_000, durationMs = 0, nowMs = 2_000, repeat = false)
        assertEquals(15_000L, s.release())
    }

    @Test
    fun settleWaitsForTheKeyToComeUp() {
        val s = SeekState()
        s.press(forward = true, positionMs = 0, durationMs = 100_000, nowMs = 0, repeat = false)
        s.settle()
        assertEquals(10_000L, s.targetMs)
    }

    @Test
    fun theStepIsWhatTheSeekThumbnailsChipSays() {
        val s = SeekState()
        assertEquals(0L, s.stepMs)
        s.press(forward = true, positionMs = 60_000, durationMs = 3_120_000, nowMs = 0, repeat = false)
        assertEquals(10_000L, s.stepMs)
        // Held: the step grows, and a throttled repeat leaves the last one showing.
        s.press(forward = true, positionMs = 60_000, durationMs = 3_120_000, nowMs = 2_500, repeat = true)
        assertEquals(30_000L, s.stepMs)
        s.press(forward = true, positionMs = 60_000, durationMs = 3_120_000, nowMs = 2_550, repeat = true)
        assertEquals(30_000L, s.stepMs)
        s.release()
        s.press(forward = false, positionMs = 60_000, durationMs = 3_120_000, nowMs = 4_000, repeat = false)
        assertEquals(-10_000L, s.stepMs)
    }

    @Test
    fun theChipSaysTheStepInSecondsOrMinutes() {
        assertEquals("+10 s", seekStepLabel(10_000))
        assertEquals("+30 s", seekStepLabel(30_000))
        assertEquals("+1 min", seekStepLabel(60_000))
        assertEquals("+2 min", seekStepLabel(120_000))
        assertEquals("−10 s", seekStepLabel(-10_000))
        assertEquals("−2 min", seekStepLabel(-120_000))
    }
}

