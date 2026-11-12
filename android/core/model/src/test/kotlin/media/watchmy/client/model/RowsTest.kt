package media.watchmy.client.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The layout editor's moves and the rows' helpers (PLAN-ROWS.md §5, §8.2), the same on the phone, tablet and TV. */
class RowsTest {
    private fun entry(id: String, hidden: Boolean = false, kind: RowLayoutEntry.Kind = RowLayoutEntry.Kind.Builtin, style: String = "poster") =
        RowLayoutEntry(id, id, style, kind, hidden = hidden, shown = !hidden)

    private val layout = listOf(entry("continue", style = "continue"), entry("live", style = "live"), entry("recentlyAddedShows"), entry("list:3", hidden = true, kind = RowLayoutEntry.Kind.List), entry("recentlyReleased", hidden = true, style = "wide"))

    @Test
    fun `move up and down stop at the ends`() {
        assertEquals(listOf("live", "continue", "recentlyAddedShows", "list:3", "recentlyReleased"), layout.moved("live", -1).map { it.id })
        assertEquals(layout, layout.moved("continue", -1))
        assertEquals(layout, layout.moved("recentlyReleased", 1))
        assertEquals(listOf("continue", "live", "list:3", "recentlyAddedShows", "recentlyReleased"), layout.moved("recentlyAddedShows", 1).map { it.id })
        assertEquals("an ID it doesn't have changes nothing", layout, layout.moved("gone", 1))
    }

    @Test
    fun `a drag puts the row where it was dropped`() {
        assertEquals(listOf("recentlyAddedShows", "continue", "live", "list:3", "recentlyReleased"), layout.movedFrom(2, 0).map { it.id })
        assertEquals(layout, layout.movedFrom(1, 9))
    }

    @Test
    fun `hide, show and Add a row keep the row's place`() {
        val hidden = layout.withHidden("live", true)
        assertEquals(layout.map { it.id }, hidden.map { it.id })
        assertTrue(hidden[1].hidden)
        assertFalse(hidden[1].shown)
        assertEquals(listOf("live", "list:3", "recentlyReleased"), hidden.addable.map { it.id })

        val added = hidden.added("recentlyReleased")
        assertEquals(hidden.map { it.id }, added.map { it.id })
        assertFalse(added.last().hidden)
        assertFalse("nothing moved or hid", layout.sameOrderAs(hidden))
        assertTrue(hidden.added("live").sameOrderAs(layout))
    }

    @Test
    fun `Add a row groups the hidden rows, and says when there are no lists of a kind`() {
        val mine = entry("list:3", hidden = true, kind = RowLayoutEntry.Kind.List).copy(owner = ListOwner.Me, listId = 3)
        val groups = addRowGroups(listOf(entry("continue"), entry("recentlyAired", hidden = true), mine), emptyList())
        assertEquals(listOf("Built-in rows", "For everyone", "Your lists"), groups.map { it.name })
        assertEquals("No lists for everyone yet.", groups[1].empty)
        assertEquals(listOf("list:3"), groups[2].rows.map { it.id })

        val household = MediaList(4, "Trending", "all", "tmdb", ListOwner.Everyone, false, "home", 20, entries = 20, inLibrary = 12)
        val shown = entry("list:4", kind = RowLayoutEntry.Kind.List).copy(owner = ListOwner.Everyone, listId = 4)
        val none = addRowGroups(listOf(entry("continue"), shown), listOf(household))
        assertEquals("a household list already on the screen: no group, and no lists of my own", listOf("Your lists"), none.map { it.name })
        assertEquals("From TMDB · 12 of 20 in your library", shown.addLine(household))
        val library = household.copy(sourceKind = "library", mediaType = "movie", inLibrary = 16)
        assertEquals("From your library · 16 movies", shown.addLine(library))
    }

    @Test
    fun `the saved order lists every row, hidden ones too`() {
        assertEquals(
            listOf(RowPlacement("continue", false), RowPlacement("live", false), RowPlacement("recentlyAddedShows", false), RowPlacement("list:3", true), RowPlacement("recentlyReleased", true)),
            layout.placements(),
        )
    }

    @Test
    fun `the editor says whose list it is, why a row isn't drawn, and when this app can't draw it`() {
        val list = MediaList(1, "Best of the 1980s", "all", "tmdb", ListOwner.Everyone, false, "home", 50, entries = 50, inLibrary = 18)
        val row = entry("list:1", kind = RowLayoutEntry.Kind.List).copy(owner = ListOwner.Everyone)
        assertEquals("For everyone · 18 of 50 · From TMDB", row.editorLine(list))
        assertEquals("Your list · 9 of 24 · Imported", row.copy(owner = ListOwner.Me).editorLine(list.copy(sourceKind = "import", entries = 24, inLibrary = 9)))
        assertEquals("Hidden · For everyone", row.copy(hidden = true).editorLine())
        assertEquals("Built in", entry("continue", style = "continue").editorLine())
        assertEquals("Built in · On now, or Watch a channel", entry("live", style = "live").editorLine())
        assertEquals("Shown in newer versions of the app", entry("shelf", style = "carousel").editorLine())
        assertEquals("Not shown: too many rows", row.copy(shown = false, note = "Not shown: too many rows").notShown)
        assertNull(row.copy(hidden = true, note = "Not shown: too many rows").notShown)
        assertNull(RowStyle.of("carousel"))
        assertFalse(layout.overLimit)
        assertTrue(List(31) { entry("r$it") }.overLimit)
    }

    private val movie = BrowseItem(ItemKind.Movie, 5, 1, "Paper Moons", 2021, "", emptyList(), null, null, null, null, false, false, null, false, 0.3f, null, null, null)
    private val cont = ContinueItem(ContinueItem.Kind.Movie, "Paper Moons", null, null, null, null, 70, 5, null, null, null, null, 10, 100)
    private fun home(rows: List<ScreenRow>?) = HomeData(null, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), LibraryTotals(0, 0, 0, 0, 0), null, null, null, rows = rows)

    @Test
    fun `Live TV is drawn only with channels, and an arranged Home with only it is empty`() {
        val rows = listOf(ScreenRow("live", RowStyle.Live, "Live TV"), ScreenRow("list:1", RowStyle.Poster, "Science fiction", items = listOf(movie)))
        assertEquals(listOf("list:1"), rows.drawn(live = false).map { it.id })
        assertEquals(listOf("live", "list:1"), rows.drawn(live = true).map { it.id })
        assertTrue(home(listOf(rows[0])).isEmpty())
        assertFalse(home(rows).isEmpty())
        assertTrue("every row hidden", home(emptyList()).isEmpty())
    }

    @Test
    fun `Continue watching comes from its row, and an arranged Home without one says so`() {
        val withContinue = home(listOf(ScreenRow("continue", RowStyle.Continue, "Continue watching", continueItems = listOf(cont))))
        assertEquals(listOf(cont), withContinue.continueRow)
        assertNull(home(emptyList()).continueRow)
        assertEquals("fixed rows", emptyList<ContinueItem>(), home(null).continueRow)
    }

    @Test
    fun `a mark shows on arranged rows at once`() {
        val rows = listOf(
            ScreenRow("continue", RowStyle.Continue, "Continue watching", continueItems = listOf(cont)),
            ScreenRow("list:1", RowStyle.Poster, "Science fiction", items = listOf(movie)),
        )
        val marked = home(rows).withWatched(WatchTarget.Movie(5), true).rows!!
        assertEquals(emptyList<ContinueItem>(), marked[0].continueItems)
        assertTrue(marked[1].items.single().watched)
        assertNull("no longer partway", marked[1].items.single().progress)
        assertNull("fixed Home has no rows to mark", home(null).withWatched(WatchTarget.Movie(5), true).rows)
    }

    @Test
    fun `built-in headings split at the dot, and a list's line goes beside its name`() {
        assertEquals("Recently added" to "Movies", ScreenRow("recentlyAddedMovies", RowStyle.Poster, "Recently added · Movies").heading())
        assertEquals("Continue watching" to null, ScreenRow("continue", RowStyle.Continue, "Continue watching").heading())
        assertEquals("Top 50 · 2026" to "18 of 50 · From TMDB", ScreenRow("list:1", RowStyle.Poster, "Top 50 · 2026", subtitle = "18 of 50 · From TMDB").heading())
    }

    @Test
    fun `a layout change reads its screen again, a list change every screen`() {
        assertTrue(RowsChange.Layout("home").affects("home"))
        assertFalse(RowsChange.Layout("library-2").affects("home"))
        assertTrue(RowsChange.Layout(null).affects("library-2"))
        assertTrue(RowsChange.Lists(4).affects("library-2"))
        assertEquals(2L, RowScreens.libraryId("library-2"))
        assertNull(RowScreens.libraryId("home"))
    }

    @Test
    fun `a missing entry is requestable only when found and the server says so`() {
        val missing = MediaListEntry(2, MediaType.Movie, 70202, "Harbor Saints", 2020, null, found = true, requestState = RequestableState.Requestable)
        assertTrue(missing.requestable)
        assertNull(missing.missingStatus)
        assertEquals("2020 · Not in your library", missing.missingLine)
        assertEquals(RequestableState.Requestable, missing.requestSubject()?.state)
        assertFalse(missing.copy(requestState = RequestableState.Requested).requestable)
        assertEquals("Requested", missing.copy(requestState = RequestableState.Requested).missingStatus)
        assertFalse(missing.copy(requestState = null).requestable)
        assertEquals("without requests", "Not in library", missing.copy(requestState = null).missingStatus)
        val notFound = missing.copy(found = false, tmdbId = null, requestState = null)
        assertFalse(notFound.requestable)
        assertNull(notFound.requestSubject())
        assertEquals("Not found on TMDB", notFound.missingLine)
    }

    @Test
    fun `a list says where it comes from and how much the library has`() {
        val list = MediaList(1, "Science fiction", "all", "tmdb", ListOwner.Everyone, false, "home", 50, entries = 50, inLibrary = 18)
        assertEquals("From TMDB", list.fromLine())
        assertEquals("List · For everyone", list.kicker())
        assertEquals("18 of 50 in your library", list.summaryLine())
        assertEquals("9 of 24 in your library · 2 not found", list.copy(entries = 24, inLibrary = 9, notFound = 2).summaryLine())
        assertEquals("From TMDB · Updated 3 h ago", list.copy(fetchedAtMs = 0).sourceLine(now = 3 * 3_600_000L))
        assertTrue(list.refreshable)
        assertFalse(list.copy(sourceKind = "import").refreshable)
        assertEquals("Imported", list.copy(sourceKind = "import").fromLine())
        assertNull(list.copy(sourceKind = "trakt").fromLine())
        assertEquals("a source added later, named by the server", "From Trakt", list.copy(sourceKind = "trakt", sourceName = "Trakt").fromLine())
        assertEquals("Imported · from an IMDb export", list.copy(sourceKind = "import", sourceName = "an IMDb export").sourceLine())
        assertEquals("Couldn’t update this list: MDBList refused the key. It’s shown as it was 2 h ago.", list.copy(error = "MDBList refused the key.", fetchedAtMs = 0).errorLine(now = 2 * 3_600_000L))
    }
}
