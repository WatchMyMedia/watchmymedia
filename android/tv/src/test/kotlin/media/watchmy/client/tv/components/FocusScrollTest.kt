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

    // A row of cards 200 wide and 24 apart, starting 12 in: the card before the focused one keeps its place at 12.
    private val keyline = KeylineSpec(leading = 12f, gap = 24f, trailing = 50f)

    @Test
    fun `a row keeps the card before the focused one in view`() {
        assertEquals("One card in from the start is where it rests", 0f, keyline.calculateScrollDistance(offset = 236f, size = 200f, containerSize = 1000f))
        assertEquals("Further along doesn't scroll either", 0f, keyline.calculateScrollDistance(offset = 460f, size = 200f, containerSize = 1000f))
        assertEquals("At the edge it scrolls back a card, which brings the one before it in", -224f, keyline.calculateScrollDistance(offset = 12f, size = 200f, containerSize = 1000f))
        assertEquals("The far end is as before", 100f, keyline.calculateScrollDistance(offset = 850f, size = 200f, containerSize = 1000f))
    }

    @Test
    fun `a row too narrow for two cards keeps the focused one whole`() {
        assertEquals(0f, keyline.calculateScrollDistance(offset = 12f, size = 200f, containerSize = 480f))
        assertEquals(-12f, keyline.calculateScrollDistance(offset = 0f, size = 200f, containerSize = 480f))
    }

    @Test
    fun `the pivot always puts the item's top at the pivot`() {
        val pivot = PivotSpec(124f)
        assertEquals(0f, pivot.calculateScrollDistance(offset = 124f, size = 300f, containerSize = 1080f))
        assertEquals("Even when it is already in view", 376f, pivot.calculateScrollDistance(offset = 500f, size = 300f, containerSize = 1080f))
        assertEquals(-124f, pivot.calculateScrollDistance(offset = 0f, size = 300f, containerSize = 1080f))
    }
}
