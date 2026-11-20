package media.watchmy.client.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A row's See all: its words, the grid choices it hands over, and which of Home's libraries it opens. */
class SeeAllTest {
    private val tv = 2L
    private val movies = 1L

    private fun home(tvLibrary: Long? = tv, movieLibrary: Long? = movies) =
        HomeData(null, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), LibraryTotals(0, 0, 0, 0, 0), null, movieLibrary, tvLibrary)

    @Test
    fun `each built-in row's card says its library and its sort`() {
        assertEquals(
            SeeAllWords("See all TV shows", "By date added", "See all TV shows, by date added"),
            seeAllWords(RowLink.Library(tv, ItemSort.Added), LibraryType.Tv, "Recently added"),
        )
        assertEquals(
            SeeAllWords("See all TV shows", "By last aired", "See all TV shows, by last aired"),
            seeAllWords(RowLink.Library(tv, ItemSort.Aired), LibraryType.Tv, "Recently aired"),
        )
        assertEquals(
            SeeAllWords("See all movies", "By date added", "See all movies, by date added"),
            seeAllWords(RowLink.Library(movies, ItemSort.Added), LibraryType.Movie, "Recently added"),
        )
        assertEquals(
            SeeAllWords("See all movies", "By release date", "See all movies, by release date"),
            seeAllWords(RowLink.Library(movies, ItemSort.Released), LibraryType.Movie, "Recently released"),
        )
    }

    @Test
    fun `the second line is the sort menu's own label`() {
        ItemSort.entries.filter { it in sortOptions(LibraryType.Movie) }.forEach { sort ->
            val words = seeAllWords(RowLink.Library(movies, sort), LibraryType.Movie, "Row")!!
            assertEquals("By ${sort.label.replaceFirstChar(Char::lowercaseChar)}", words.detail)
        }
    }

    @Test
    fun `no sort, or one the library doesn't offer, has no second line`() {
        assertEquals(SeeAllWords("See all movies", null, "See all movies"), seeAllWords(RowLink.Library(movies), LibraryType.Movie, "Row"))
        assertEquals(SeeAllWords("See all TV shows", null, "See all TV shows"), seeAllWords(RowLink.Library(tv, ItemSort.Released), LibraryType.Tv, "Row"))
    }

    @Test
    fun `a list's card says what the TVs' does and names the list aloud`() {
        assertEquals(
            SeeAllWords("See all", "With the ones you don’t have", "See all of Top rated this year"),
            seeAllWords(RowLink.ToList(7), null, "Top rated this year"),
        )
    }

    @Test
    fun `a library link whose library type isn't known has no words`() {
        assertNull(seeAllWords(RowLink.Library(9, ItemSort.Added), null, "Recently added"))
    }

    @Test
    fun `Home opens its TV and movie libraries and no other`() {
        val home = home()
        assertEquals(LibraryType.Tv, home.libraryType(RowLink.Library(tv)))
        assertEquals(LibraryType.Movie, home.libraryType(RowLink.Library(movies)))
        assertNull("a second movie library has no tab", home.libraryType(RowLink.Library(9)))
        assertNull("a server without a TV library", home(tvLibrary = null).libraryType(RowLink.Library(9)))
    }

    @Test
    fun `a link's choices become the request`() {
        assertEquals(
            BrowseRequest(tv, ItemSort.Aired, ItemFilter.Continuing, "Drama", 1990),
            RowLink.Library(tv, ItemSort.Aired, ItemFilter.Continuing, "Drama", 1990).browseRequest(),
        )
    }

    @Test
    fun `a request is taken once, by its own library's grid`() {
        val requests = BrowseRequests()
        val request = BrowseRequest(tv, ItemSort.Aired)
        requests.post(request)
        assertNull("the movie grid leaves it", requests.take(movies))
        assertEquals(request, requests.pending.value)
        assertEquals(request, requests.take(tv))
        assertNull("taken already", requests.take(tv))
        assertNull(requests.pending.value)
    }

    @Test
    fun `a newer request replaces one not yet taken`() {
        val requests = BrowseRequests()
        requests.post(BrowseRequest(tv, ItemSort.Aired))
        requests.post(BrowseRequest(movies, ItemSort.Released))
        assertNull(requests.take(tv))
        assertEquals(BrowseRequest(movies, ItemSort.Released), requests.take(movies))
    }

    @Test
    fun `past a library row's last card there is no panel, so the tablet keeps the last card leading`() {
        val item = BrowseItem(ItemKind.Movie, 5, movies, "Quiet Hours", 2026, "", emptyList(), null, null, null, null, false, false, null, false, null, null, 98, null)
        val row = ScreenRow(RowIds.RECENTLY_ADDED_MOVIES, RowStyle.Poster, "Recently added · Movies", items = listOf(item), seeAll = RowLink.Library(movies, ItemSort.Added))
        val format = LiveClockFormat()
        assertEquals("Quiet Hours", row.cardPanelAt(0, emptyList(), 0, format)?.title)
        assertNull(row.cardPanelAt(1, emptyList(), 0, format))
        assertEquals(1, row.cardCount)
    }

    @Test
    fun `past a library row's last card the TV's panel names the library and the row's sort`() {
        val item = BrowseItem(ItemKind.Movie, 5, movies, "Quiet Hours", 2026, "", emptyList(), null, null, null, null, false, false, null, false, null, null, 98, null)
        val row = ScreenRow(RowIds.RECENTLY_RELEASED, RowStyle.Wide, "Recently released · Movies", items = listOf(item), seeAll = RowLink.Library(movies, ItemSort.Released))
        assertEquals(
            CardPanel(kicker = "Recently released", title = "See all movies", facts = listOf("By release date"), target = CardTarget.Library),
            row.cardPanelAt(1, emptyList(), 0, LiveClockFormat(), home().seeAllType(row)),
        )
    }

    @Test
    fun `past Recently aired's last episode the panel says TV shows by last aired`() {
        val row = ScreenRow(RowIds.RECENTLY_AIRED, RowStyle.Episode, "Recently aired · Episodes", seeAll = RowLink.Library(tv, ItemSort.Aired))
        val panel = row.cardPanelAt(0, emptyList(), 0, LiveClockFormat(), LibraryType.Tv)
        assertEquals("Recently aired", panel?.kicker)
        assertEquals("See all TV shows", panel?.title)
        assertEquals(listOf("By last aired"), panel?.facts)
        assertEquals(CardTarget.Library, panel?.target)
    }

    @Test
    fun `a list row's See all panel is as it was`() {
        val row = ScreenRow("list:7", RowStyle.Poster, "Top rated", subtitle = "18 of 50 · From TMDB", seeAll = RowLink.ToList(7), listId = 7)
        assertEquals(
            CardPanel(kicker = "Top rated", title = "See all", facts = listOf("18 of 50 · From TMDB"), target = CardTarget.List),
            row.cardPanelAt(0, emptyList(), 0, LiveClockFormat(), null),
        )
    }

    @Test
    fun `Home's See all type is its link's library, and none for a list, another library or no link`() {
        val home = home()
        assertEquals(LibraryType.Tv, home.seeAllType(ScreenRow(RowIds.RECENTLY_AIRED, RowStyle.Episode, "Recently aired", seeAll = RowLink.Library(tv, ItemSort.Aired))))
        assertEquals(LibraryType.Movie, home.seeAllType(ScreenRow(RowIds.RECENTLY_ADDED_MOVIES, RowStyle.Poster, "Recently added", seeAll = RowLink.Library(movies))))
        assertNull(home.seeAllType(ScreenRow("list:7", RowStyle.Poster, "Top rated", seeAll = RowLink.ToList(7), listId = 7)))
        assertNull(home.seeAllType(ScreenRow(RowIds.RECENTLY_ADDED_MOVIES, RowStyle.Poster, "Recently added", seeAll = RowLink.Library(9))))
        assertNull(home.seeAllType(ScreenRow(RowIds.CONTINUE, RowStyle.Continue, "Continue watching")))
    }
}
