package media.watchmy.client.mobile.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DoubleTapSeekTest {
    private val width = 915f

    @Test
    fun `left two fifths seek back, right two fifths forward, the middle does not seek`() {
        assertEquals(SeekZone.Back, seekZone(0f, width))
        assertEquals(SeekZone.Back, seekZone(width * 0.39f, width))
        assertEquals(SeekZone.None, seekZone(width * 0.5f, width))
        assertEquals(SeekZone.Forward, seekZone(width * 0.61f, width))
        assertEquals(SeekZone.Forward, seekZone(width, width))
        assertEquals(SeekZone.None, seekZone(10f, 0f))
    }

    @Test
    fun `a double tap seeks 10 s on its side`() {
        val seek = DoubleTapSeek()
        assertEquals(-10_000L, seek.onDoubleTap(50f, width, 1_000))
        assertEquals(10_000L, DoubleTapSeek().onDoubleTap(880f, width, 1_000))
    }

    @Test
    fun `a double tap in the middle toggles the controls instead`() {
        val seek = DoubleTapSeek()
        assertNull(seek.onDoubleTap(width / 2, width, 1_000))
        assertNull(seek.burst)
    }

    @Test
    fun `further single taps on the same side add 10 s each and the label shows the total`() {
        val seek = DoubleTapSeek()
        seek.onDoubleTap(880f, width, 1_000)
        assertEquals(10_000L, seek.onTap(870f, width, 1_400))
        assertEquals(10_000L, seek.onTap(860f, width, 1_900))
        assertEquals(30_000L, seek.burst!!.totalMs)
    }

    @Test
    fun `going back the total is negative`() {
        val seek = DoubleTapSeek()
        seek.onDoubleTap(40f, width, 1_000)
        assertEquals(-10_000L, seek.onTap(60f, width, 1_300))
        assertEquals(-20_000L, seek.burst!!.totalMs)
    }

    @Test
    fun `a single tap after the window is an ordinary tap`() {
        val seek = DoubleTapSeek()
        seek.onDoubleTap(880f, width, 1_000)
        assertTrue(seek.isActive(1_000 + DoubleTapSeek.WINDOW_MS))
        assertNull(seek.onTap(880f, width, 1_000 + DoubleTapSeek.WINDOW_MS + 1))
        assertNull(seek.burst)
    }

    @Test
    fun `a single tap on the other side ends the run`() {
        val seek = DoubleTapSeek()
        seek.onDoubleTap(880f, width, 1_000)
        assertNull(seek.onTap(40f, width, 1_200))
        assertFalse(seek.isActive(1_200))
    }

    @Test
    fun `a single tap with no run going is an ordinary tap`() {
        assertNull(DoubleTapSeek().onTap(40f, width, 1_000))
    }
}
