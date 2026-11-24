package media.watchmy.client.tv.home

import media.watchmy.client.model.ContinueItem
import media.watchmy.client.model.Hero
import media.watchmy.client.model.RowStyle
import media.watchmy.client.model.ScreenRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What Home leads with, and the rows that follow it. */
class HomeLeadTest {
    private fun item(id: Long) = ContinueItem(
        ContinueItem.Kind.Movie, "Film $id", null, null, null, null, fileId = id, movieId = id, showId = null, episodeId = null,
        season = null, episode = null, positionMs = 600_000, durationMs = 6_000_000,
    )

    private val hero = Hero(Hero.Kind.Movie, "Recently added", "The Salt Road", "2025 · 1 h 52 min", "", null, null, null, 9, null, 90)
    private val added = ScreenRow("added", RowStyle.Wide, "Recently added")

    @Test
    fun `the title most likely to be resumed leads, and Continue watching keeps the rest where it is`() {
        val cw = ScreenRow("cw", RowStyle.Continue, "Continue watching", continueItems = listOf(item(1), item(2), item(3)))
        val (lead, rows) = homeLead(hero, listOf(added, cw))
        assertEquals(HomeLead.Resume(item(1)), lead)
        assertEquals(listOf("added", "cw"), rows.map { it.id })
        assertEquals(listOf(2L, 3L), rows[1].continueItems.map { it.fileId })
        assertEquals(LEAD_RESUME, lead?.key)
    }

    @Test
    fun `Continue watching goes when the title on the card was its only one`() {
        val cw = ScreenRow("cw", RowStyle.Continue, "Continue watching", continueItems = listOf(item(1)))
        val (lead, rows) = homeLead(hero, listOf(cw, added))
        assertEquals(HomeLead.Resume(item(1)), lead)
        assertEquals(listOf("added"), rows.map { it.id })
    }

    @Test
    fun `the card names the episode once, whichever way the code is written`() {
        assertEquals("Open Late", episodeName("S02E01 Open Late"))
        assertEquals("Drift", episodeName("S02 E04 · Drift"))
        assertEquals("The Long Dark", episodeName("The Long Dark"))
        assertNull(episodeName("S02E01"))
        assertNull(episodeName(null))
    }

    @Test
    fun `with nothing in progress the server's pick leads, and without one Home opens on its rows`() {
        val (lead, rows) = homeLead(hero, listOf(added))
        assertEquals(HomeLead.Pick(hero), lead)
        assertEquals("hero:play", lead?.key)
        assertEquals(listOf(added), rows)

        val (none, same) = homeLead(null, listOf(added))
        assertNull(none)
        assertEquals(listOf(added), same)
    }
}
