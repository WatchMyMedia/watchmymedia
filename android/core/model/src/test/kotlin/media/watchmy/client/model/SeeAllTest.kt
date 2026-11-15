package media.watchmy.client.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A row's See all (PLAN-SEE-ALL.md §3, §4.4): its words, the grid choices it hands over, and which of Home's libraries it opens. */
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
}
