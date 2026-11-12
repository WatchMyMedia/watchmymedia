package media.watchmy.client.presentation.live

import media.watchmy.client.model.LiveChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveNavigationTest {
    private val first = LiveChannel("first", "antenna", "5.1", "News")
    private val unavailable = LiveChannel("gone", "antenna", "7.1", "Gone", available = false)
    private val last = LiveChannel("last", "antenna", "9.1", "Public Television")
    private val channels = listOf(first, unavailable, last)

    @Test fun `channel keys skip unavailable channels and wrap both directions`() {
        assertEquals(last, nextLiveChannel(channels, first.id, 1))
        assertEquals(first, nextLiveChannel(channels, last.id, 1))
        assertEquals(last, nextLiveChannel(channels, first.id, -1))
        assertEquals(first, nextLiveChannel(channels, last.id, -1))
        assertNull(nextLiveChannel(listOf(unavailable), first.id, 1))
        assertNull(nextLiveChannel(emptyList(), first.id, 1))
    }

    @Test fun `removing the playing channel selects first or last without skipping either`() {
        assertEquals(first, nextLiveChannel(channels, "removed", 1))
        assertEquals(last, nextLiveChannel(channels, "removed", -1))
        assertEquals(last, nextLiveChannel(channels, unavailable.id, 1))
    }

    @Test fun `a numeric prefix or duplicate number cannot select an arbitrary channel`() {
        assertNull(exactLiveChannel(channels, "5"))
        assertNull(exactLiveChannel(channels, "7.1"))
        assertEquals(first, exactLiveChannel(channels, "5.1"))
        assertNull(exactLiveChannel(channels + first.copy(id = "other", sourceId = "second"), "5.1"))
        assertEquals(first, exactLiveChannel(channels + first.copy(id = "other", available = false), "5.1"))
    }

    @Test fun `numeric entry is bounded and has only one non-leading separator`() {
        assertEquals("", editLiveNumber("", '.'))
        assertEquals("5.", editLiveNumber("5", '.'))
        assertEquals("5.1", editLiveNumber("5.1", '.'))
        assertEquals("5.1", editLiveNumber("5.1", 'x'))
        assertEquals("5.", editLiveNumber("5.1", '\b'))
        assertEquals("", editLiveNumber("", '\b'))
        assertEquals("1234567890", editLiveNumber("1234567890", '1'))
    }
}
