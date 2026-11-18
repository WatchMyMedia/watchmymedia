package media.watchmy.client.model

import org.junit.Assert.assertEquals
import org.junit.Test

class TrailerTextTest {
    @Test
    fun `the TV's pause screen says where the trailer is, and how long it is once known`() {
        assertEquals("Paused · 0:13 / 7:45", TrailerText.paused(13_200, 465_040))
        assertEquals("Paused · 0:00", TrailerText.paused(0, 0))
        assertEquals("Paused · 1:02:03 / 1:30:00", TrailerText.paused(3_723_000, 5_400_000))
    }
}
