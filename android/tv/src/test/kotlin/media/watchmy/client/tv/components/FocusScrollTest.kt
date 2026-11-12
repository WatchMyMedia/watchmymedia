package media.watchmy.client.tv.components

import org.junit.Assert.assertEquals
import org.junit.Test

/** How far TV rows and pages scroll to bring the focused item into view. */
class FocusScrollTest {
    // Items must sit between 100 from the start and 50 from the end of a 1000-long container.
    private val keep = KeepInViewSpec(leading = 100f, trailing = 50f)

    @Test
    fun `an item already in view doesn't scroll`() {
        assertEquals(0f, keep.calculateScrollDistance(offset = 100f, size = 200f, containerSize = 1000f))
        assertEquals(0f, keep.calculateScrollDistance(offset = 750f, size = 200f, containerSize = 1000f))
    }

    @Test
    fun `an item off either end scrolls just enough`() {
        assertEquals("Back until its start is at 100", -60f, keep.calculateScrollDistance(offset = 40f, size = 200f, containerSize = 1000f))
        assertEquals("On until its end is at 950", 100f, keep.calculateScrollDistance(offset = 850f, size = 200f, containerSize = 1000f))
        assertEquals("Far off screen", 1250f, keep.calculateScrollDistance(offset = 2000f, size = 200f, containerSize = 1000f))
    }

    @Test
    fun `an item bigger than the space lines up its start`() {
        assertEquals(400f, keep.calculateScrollDistance(offset = 500f, size = 900f, containerSize = 1000f))
        assertEquals(-100f, keep.calculateScrollDistance(offset = 0f, size = 900f, containerSize = 1000f))
    }

    @Test
    fun `the pivot always puts the item's top at the pivot`() {
        val pivot = PivotSpec(124f)
        assertEquals(0f, pivot.calculateScrollDistance(offset = 124f, size = 300f, containerSize = 1080f))
        assertEquals("Even when it is already in view", 376f, pivot.calculateScrollDistance(offset = 500f, size = 300f, containerSize = 1080f))
        assertEquals(-124f, pivot.calculateScrollDistance(offset = 0f, size = 300f, containerSize = 1080f))
    }
}
