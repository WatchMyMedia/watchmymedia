package media.watchmy.client.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OneCardTest {
    private fun movie(id: Long, title: String, tmdb: Long?, addedAtMs: Long? = null, watched: Boolean = false) = BrowseItem(
        ItemKind.Movie, id, 1, title, 2025, "", emptyList(), null, null, null, null, false, false, null, watched, null, null, 100, null,
        tmdbId = tmdb, addedAtMs = addedAtMs,
    )

    private fun cont(fileId: Long, title: String, tmdb: Long?, movieId: Long, updated: Long) =
        ContinueItem(ContinueItem.Kind.Movie, title, null, null, null, null, fileId, movieId, null, null, null, null, 10, 100, tmdbId = tmdb, updatedAtMs = updated)

    private val home = ServerTag(null, "Home server")
    private val cabin = ServerTag("cabin", "Cabin server")
    private val den = ServerTag("den", "Den server")

    @Test fun `the same TMDB id is one card, where its first copy is, carrying the others' ids`() {
        val merged = mergeItems(
            listOf(
                home to listOf(movie(1, "Ninety Fathoms", 603), movie(2, "Low Water", null)),
                cabin to listOf(movie(9, "Salt Road", 700), movie(31, "Ninety Fathoms", 603, watched = true)),
                den to listOf(movie(2, "Low Water", null)),
            ),
        )
        assertEquals(listOf("Ninety Fathoms", "Low Water", "Salt Road", "Low Water"), merged.map { it.title })
        val fathoms = merged.first()
        assertEquals("the server in use's id", 1L, fathoms.id)
        assertEquals(listOf(Where.Copy("cabin", "Cabin server", 31)), fathoms.where.copies)
        assertTrue("watched on any server is watched", fathoms.watched)
        assertNull("on the server in use, it names nothing", fathoms.where.cardLine())
        assertEquals("On Cabin", merged[2].where.cardLine())
        assertEquals("a title without a TMDB id is never merged", "den", merged[3].where.serverId)
    }

    @Test fun `Continue watching is the copy played last, the row ordered by when each was played on any server`() {
        val merged = mergeContinue(
            listOf(
                home to listOf(cont(11, "Ninety Fathoms", 603, 1, updated = 100), cont(12, "Night Ferry", 800, 2, updated = 300)),
                cabin to listOf(cont(71, "Ninety Fathoms", 603, 31, updated = 500), cont(72, "Low Water", 900, 32, updated = 200)),
            ),
        )
        assertEquals(listOf("Ninety Fathoms", "Night Ferry", "Low Water"), merged.map { it.title })
        val fathoms = merged.first()
        assertEquals("Resume plays from where it was played last", "cabin", fathoms.where.serverId)
        assertEquals(71L, fathoms.fileId)
        assertEquals(listOf(Where.Copy(null, null, 1)), fathoms.where.copies)
        assertEquals("from Cabin", fathoms.where.cardLine(resumes = true))
        assertEquals(PlayRequest(71, title = "Ninety Fathoms", serverId = "cabin"), fathoms.playRequest())
        assertFalse("the server in use has it too", fathoms.where.onlyElsewhere)
    }

    @Test fun `Home's rows fill from every server, newest first, and a late server joins at their end`() {
        val row = ScreenRow(RowIds.RECENTLY_ADDED_MOVIES, RowStyle.Poster, "Recently added", items = listOf(movie(1, "Ninety Fathoms", 603, addedAtMs = 100)))
        val list = ScreenRow("list:4", RowStyle.Poster, "Staff picks", items = listOf(movie(3, "Tin Sky", 5)))
        val mine = HomeData(null, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), LibraryTotals(0, 0, 0, 0, 0), null, null, null, rows = listOf(row, list))
        val theirs = mine.copy(rows = null, recentlyAddedMovies = listOf(movie(31, "Salt Road", 700, addedAtMs = 900), movie(32, "Ninety Fathoms", 603, addedAtMs = 50)))
        val first = mine.mergedWith(listOf(cabin to theirs), inOrder = true)
        assertEquals(listOf("Salt Road", "Ninety Fathoms"), first.rows!![0].items.map { it.title })
        assertEquals("a list stays the server in use's own", listOf("Tin Sky"), first.rows!![1].items.map { it.title })
        val late = mine.mergedWith(listOf(cabin to theirs), inOrder = false)
        assertEquals("nothing drawn moves", listOf("Ninety Fathoms", "Salt Road"), late.rows!![0].items.map { it.title })
        assertTrue(first.rows!![0].mergedAcrossServers)
        assertFalse(first.rows!![1].mergedAcrossServers)
    }

    @Test fun `a late server played further takes the Continue card's place without moving it`() {
        val row = ScreenRow(RowIds.CONTINUE, RowStyle.Continue, "Continue watching", continueItems = listOf(cont(12, "Night Ferry", 800, 2, updated = 300), cont(11, "Ninety Fathoms", 603, 1, updated = 100)))
        val mine = HomeData(null, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), LibraryTotals(0, 0, 0, 0, 0), null, null, null, rows = listOf(row))
        val theirs = mine.copy(rows = null, continueWatching = listOf(cont(71, "Ninety Fathoms", 603, 31, updated = 500), cont(72, "Night Ferry", 800, 32, updated = 200)))
        val late = mine.mergedWith(listOf(cabin to theirs), inOrder = false).rows!![0].continueItems
        assertEquals("nothing drawn moves", listOf("Night Ferry", "Ninety Fathoms"), late.map { it.title })
        assertEquals("played here last: it stays here, with Cabin's copy", null, late[0].where.serverId)
        assertEquals(listOf(Where.Copy("cabin", "Cabin server", 32)), late[0].where.copies)
        assertEquals("played on Cabin later: Resume plays Cabin's copy from there", "cabin", late[1].where.serverId)
        assertEquals(71L, late[1].fileId)
        assertEquals(listOf(Where.Copy(null, null, 1)), late[1].where.copies)
        assertEquals("from Cabin", late[1].where.cardLine(resumes = true))
    }

    @Test fun `search's line names every server a merged result is on`() {
        val merged = Where(null, null, listOf(Where.Copy("cabin", "Cabin server", 31)))
        assertEquals("On Home and Cabin", merged.searchLine("Home server"))
        assertEquals("Cabin server", Where("cabin", "Cabin server").searchLine("Home server"))
        assertEquals("Home, Cabin and Den", serverList(listOf("Home server", "Cabin server", "Den server")))
    }

    @Test fun `a server reached over the internet is away from home`() {
        assertTrue(isAwayFromHome("https://cabin.example.net"))
        assertFalse(isAwayFromHome("http://192.168.1.20:27587"))
        assertFalse(isAwayFromHome("http://127.0.0.1:27588"))
    }
}
