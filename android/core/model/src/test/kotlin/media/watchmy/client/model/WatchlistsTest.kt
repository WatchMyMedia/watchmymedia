package media.watchmy.client.model

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Watchlists' words, the same on the phone, tablet and TV, and where titles are while a change is on its way. */
class WatchlistsTest {
    private fun watchlist(id: Long, name: String) = Watchlist(id, name, titles = 0, inLibrary = 0, removeWatched = true)

    private val watchlist = watchlist(1, "Watchlist")
    private val halloween = watchlist(2, "Halloween")
    private val kids = watchlist(3, "With the kids")
    private val title = WatchlistTitle(WatchlistKey(MediaType.Movie, 70299), "Ninety Fathoms", 2025)

    @Test
    fun `the button names where the title is`() {
        val cases = listOf(
            emptyList<String>() to "Watchlist",
            listOf("Watchlist") to "On Watchlist",
            listOf("Halloween") to "On Halloween",
            listOf("Watchlist", "Halloween") to "On 2 watchlists",
        )
        for ((names, label) in cases) assertEquals(names.toString(), label, WatchlistWords.button(names))
    }

    @Test
    fun `a card's menu toggles the only watchlist and picks among several`() {
        val cases = listOf(
            Triple(null, false, "Add to Watchlist"),
            Triple(emptyList<Watchlist>(), false, "Add to Watchlist"),
            Triple(listOf(watchlist), true, "Remove from Watchlist"),
            Triple(listOf(halloween), false, "Add to Halloween"),
            Triple(listOf(watchlist, halloween), false, "Save to watchlist…"),
            Triple(listOf(watchlist, halloween, kids), true, "Save to watchlist…"),
        )
        for ((mine, on, label) in cases) assertEquals("$mine $on", label, watchlistMenuLabel(mine, on))
    }

    @Test
    fun `names of ids not read yet are Watchlist`() {
        assertEquals(listOf("Halloween", "Watchlist"), listOf(watchlist, halloween).namesOf(listOf(2, NEW_WATCHLIST_ID)))
        assertEquals(listOf("Watchlist"), null.namesOf(listOf(9)))
    }

    @Test
    fun `a name another watchlist has is taken, whatever the case`() {
        val mine = listOf(watchlist, halloween)
        assertTrue(mine.nameTaken(" halloween "))
        assertFalse("renaming to its own name", mine.nameTaken("Halloween", except = 2))
        assertFalse(mine.nameTaken("Date night"))
    }

    @Test
    fun `delete says what stays in the library`() {
        val cases = listOf(
            0 to "This can’t be undone.",
            1 to "Its 1 title stays in your library. This can’t be undone.",
            6 to "Its 6 titles stay in your library. This can’t be undone.",
        )
        for ((n, body) in cases) assertEquals(body, WatchlistWords.deleteBody(n))
    }

    @Test
    fun `a watchlist's page and Add a row say what it is, not where it comes from`() {
        val list = MediaList(4, "Halloween", "all", WATCHLIST_KIND, ListOwner.Me, true, "home", 500, entries = 6, inLibrary = 6, fetchedAtMs = 0)
        assertEquals("Watchlist", list.kicker())
        assertNull("no From watchlist, no Updated", list.sourceLine(now = 3_600_000))
        assertEquals("6 titles · 6 in your library", list.summaryLine())
        assertEquals("Delete Halloween?", list.deleteQuestion())
        val row = RowLayoutEntry("list:4", "Halloween", "poster", RowLayoutEntry.Kind.List, hidden = true, shown = false, listId = 4, owner = ListOwner.Me)
        assertEquals("Hidden · Watchlist · 6 of 6", row.editorLine(list))
        assertEquals("Watchlist · 6 of 6 in your library", row.addLine(list))
        assertEquals("Its row goes from every screen. This can’t be undone.", list.copy(sourceKind = "import").deleteBody())
    }

    @Test
    fun `a watchlist made or renamed, announced for Home, reaches every screen`() {
        assertTrue(RowsChange.Layout(RowScreens.HOME).affects(RowScreens.library(2)))
    }

    @Test
    fun `only matched movies and shows can be saved`() {
        val movie = BrowseItem(
            ItemKind.Movie, 500, 1, "Ninety Fathoms", 2025, "", emptyList(), null, null, null, null, false, false, null, false, null, null, null, null, tmdbId = 70299,
        )
        assertEquals(WatchlistKey(MediaType.Movie, 70299), movie.watchlistTitle()?.key)
        assertNull("no TMDB id", movie.copy(tmdbId = null).watchlistTitle())
        assertNull("unmatched", movie.copy(kind = ItemKind.Unmatched).watchlistTitle())
    }

    // The ledger: what the screens show while a change is on its way.

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun ledgerTest(block: suspend TestScope.(WatchlistLedger) -> Unit) = runTest(UnconfinedTestDispatcher()) { block(WatchlistLedger(backgroundScope)) }

    @Test
    fun `a change shows at once and the reply's answer replaces it`() = ledgerTest { ledger ->
        var during: List<Long>? = null
        val reply = ledger.change(title.key, { it + 2 }, { it }) {
            during = ledger.held.value[title.key]
            listOf(1L, 2L)
        }
        assertEquals(listOf(2L), during)
        assertEquals(listOf(1L, 2L), reply)
        assertEquals(listOf(1L, 2L), ledger.held.value[title.key])
    }

    @Test
    fun `a refused change is put back`() = ledgerTest { ledger ->
        ledger.place(title.key, listOf(1))
        val failed = runCatching { ledger.change<List<Long>>(title.key, { it + 2 }, { it }) { throw ApiException(422, "A watchlist holds up to 500 titles.") } }
        assertTrue(failed.isFailure)
        assertEquals(listOf(1L), ledger.held.value[title.key])
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a held removal sends nothing until sent, and Undo puts it back`() = ledgerTest { ledger ->
        ledger.place(title.key, listOf(1, 2))
        var sent = 0
        val removal = ledger.removeLater(title.key, from = 2) { sent++; listOf(1L) }
        assertEquals(listOf(1L), ledger.held.value[title.key])
        removal.undo()
        removal.send()
        runCurrent()
        assertEquals("undone first: never sent", 0, sent)
        assertEquals(listOf(1L, 2L), ledger.held.value[title.key])
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a held removal is sent once, and a refusal puts it back and is said`() = ledgerTest { ledger ->
        ledger.place(title.key, listOf(1))
        val said = mutableListOf<String>()
        backgroundScope.launch { ledger.refusals.collect { said += it } }
        var sent = 0
        val removal = ledger.removeLater(title.key, from = null) { sent++; throw ApiException(500, "The server had a problem (500). Try again.") }
        removal.send()
        removal.send()
        runCurrent()
        assertEquals(1, sent)
        assertEquals(listOf(1L), ledger.held.value[title.key])
        assertEquals(listOf("The server had a problem (500). Try again."), said)
    }

    @Test
    fun `a read begun before a change doesn't undo it`() = ledgerTest { ledger ->
        val since = ledger.revision
        ledger.change(title.key, { it + 1 }, { it }) { listOf(1L) }
        ledger.known(title.key, emptyList(), since)
        assertEquals(listOf(1L), ledger.held.value[title.key])
        ledger.known(title.key, emptyList(), ledger.revision)
        assertEquals(emptyList<Long>(), ledger.held.value[title.key])
    }
}
