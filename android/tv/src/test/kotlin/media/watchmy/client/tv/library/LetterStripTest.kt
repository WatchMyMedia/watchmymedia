package media.watchmy.client.tv.library

import media.watchmy.client.model.LetterOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The A–Z strip's counts and moves (TvLibraryAZ.dc.html). */
class LetterStripTest {
    private val letters = listOf(LetterOffset("#", 0), LetterOffset("A", 2), LetterOffset("C", 5), LetterOffset("M", 9))

    @Test
    fun `each letter counts the titles up to the next one, the last up to the total`() {
        assertEquals(mapOf("#" to 2, "A" to 3, "C" to 4, "M" to 6), letterCounts(letters, total = 15))
    }

    @Test
    fun `up and down skip letters with no titles, and stop at either end`() {
        val counts = letterCounts(letters, total = 15)
        assertEquals("C", nextLetter("A", counts, 1))
        assertEquals("M", nextLetter("C", counts, 1))
        assertEquals("A", nextLetter("C", counts, -1))
        assertEquals("#", nextLetter("A", counts, -1))
        assertNull(nextLetter("M", counts, 1))
        assertNull(nextLetter("#", counts, -1))
    }

    @Test
    fun `the strip offers # and A to Z`() {
        assertEquals(27, Alphabet.size)
        assertEquals("#", Alphabet.first())
        assertEquals("Z", Alphabet.last())
    }
}
