package media.watchmy.client.mobile.player

import org.junit.Assert.assertEquals
import org.junit.Test

class ScrubberModelTest {
    @Test
    fun `buffer reaches from the position and stops at the end`() {
        assertEquals(0.5f, bufferedFraction(20_000, 30_000, 100_000), 1e-6f)
        assertEquals(1f, bufferedFraction(90_000, 30_000, 100_000), 0f)
        assertEquals(0.2f, bufferedFraction(20_000, -5_000, 100_000), 1e-6f)
    }

    @Test
    fun `a touch on the track maps to a position, clamped to the file`() {
        assertEquals(25_000L, positionAt(100f, 400f, 100_000))
        assertEquals(0L, positionAt(-20f, 400f, 100_000))
        assertEquals(100_000L, positionAt(500f, 400f, 100_000))
        assertEquals(0L, positionAt(100f, 0f, 100_000))
    }

    @Test
    fun `time left uses a real minus sign`() {
        assertEquals("−51:22", remainingLabel(48_000, 52 * 60_000L + 10_000))
        assertEquals("−0:00", remainingLabel(70_000, 60_000))
    }
}
