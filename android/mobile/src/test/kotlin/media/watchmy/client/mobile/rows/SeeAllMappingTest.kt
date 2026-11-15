package media.watchmy.client.mobile.rows

import androidx.compose.ui.unit.dp
import media.watchmy.client.mobile.navigation.LibraryTab
import media.watchmy.client.mobile.navigation.MobileRoutes
import media.watchmy.client.model.BrowseRequest
import media.watchmy.client.model.BrowseRequests
import media.watchmy.client.model.HomeData
import media.watchmy.client.model.ItemSort
import media.watchmy.client.model.LibraryTotals
import media.watchmy.client.model.LibraryType
import media.watchmy.client.model.RowIds
import media.watchmy.client.model.RowLink
import media.watchmy.client.model.RowStyle
import media.watchmy.client.model.ScreenRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where Home's rows and the library's rows send See all (PLAN-SEE-ALL.md
 * §4.4). Home's phone and tablet layouts both take [homeSeeAll], so the
 * phone has it on built-in rows now, as the tablet did, with the row's sort.
 */
class SeeAllMappingTest {
    private val tv = 2L
    private val movies = 1L
    private val home = HomeData(null, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), LibraryTotals(0, 0, 0, 0, 0), null, movies, tv)
    private val requests = BrowseRequests()
    private val went = mutableListOf<Any>()

    private fun row(id: String, style: RowStyle, title: String, link: RowLink?) = ScreenRow(id, style, title, seeAll = link)

    @Test fun `a built-in row opens its library's tab and hands it the row's sort`() {
        val aired = homeSeeAll(row(RowIds.RECENTLY_AIRED, RowStyle.Episode, "Recently aired · Episodes", RowLink.Library(tv, ItemSort.Aired)), home, requests, went::add)!!
        assertEquals("See all TV shows", aired.words.title)
        assertEquals("By last aired", aired.words.detail)
        assertEquals("See all TV shows, by last aired", aired.words.spoken)
        assertEquals(false, aired.list)
        aired.onClick()
        assertEquals(listOf<Any>(LibraryTab(LibraryType.Tv)), went)
        assertEquals(BrowseRequest(tv, ItemSort.Aired), requests.pending.value)

        val released = homeSeeAll(row(RowIds.RECENTLY_RELEASED, RowStyle.Wide, "Recently released · Movies", RowLink.Library(movies, ItemSort.Released)), home, requests, went::add)!!
        assertEquals("See all movies, by release date", released.words.spoken)
        released.onClick()
        assertEquals(LibraryTab(LibraryType.Movie), went.last())
        assertEquals("the newer request replaced the other", BrowseRequest(movies, ItemSort.Released), requests.pending.value)
    }

    @Test fun `a list row opens the list's page`() {
        val list = homeSeeAll(row(RowIds.list(7), RowStyle.Poster, "Top rated this year", RowLink.ToList(7)), home, requests, went::add)!!
        assertTrue(list.list)
        assertEquals("See all of Top rated this year", list.words.spoken)
        list.onClick()
        assertEquals(listOf<Any>(MobileRoutes.MediaList(7)), went)
        assertNull(requests.pending.value)
    }

    @Test fun `rows with no link, or a library Home has no tab for, have no See all`() {
        assertNull(homeSeeAll(row(RowIds.CONTINUE, RowStyle.Continue, "Continue watching", null), home, requests, went::add))
        assertNull(homeSeeAll(row(RowIds.RECENTLY_ADDED_MOVIES, RowStyle.Poster, "Recently added · Movies", RowLink.Library(9, ItemSort.Added)), home, requests, went::add))
    }

    @Test fun `a library's own rows sort the grid below`() {
        val opened = mutableListOf<BrowseRequest>()
        val added = row(RowIds.RECENTLY_ADDED, RowStyle.Poster, "Recently added", RowLink.Library(movies, ItemSort.Added))
        val seeAll = librarySeeAll(added, LibraryType.Movie, movies, opened::add, went::add)!!
        assertEquals("See all movies, by date added", seeAll.words.spoken)
        seeAll.onClick()
        assertEquals(listOf(BrowseRequest(movies, ItemSort.Added)), opened)
        assertTrue("no navigation: the grid is on this screen", went.isEmpty())

        assertNull("another library's link", librarySeeAll(added, LibraryType.Movie, 5, opened::add, went::add))
        assertNull("before the grid knows its library", librarySeeAll(added, LibraryType.Movie, null, opened::add, went::add))
    }

    @Test fun `the card is the size of the row's cards`() {
        assertEquals(116.dp to 2f / 3f, seeAllCardSize(RowStyle.Poster, tablet = false))
        assertEquals(160.dp to 16f / 9f, seeAllCardSize(RowStyle.Wide, tablet = false))
        assertEquals(232.dp to 16f / 9f, seeAllCardSize(RowStyle.Episode, tablet = false))
        assertEquals(150.dp to 2f / 3f, seeAllCardSize(RowStyle.Poster, tablet = true))
        assertEquals(240.dp to 16f / 9f, seeAllCardSize(RowStyle.Wide, tablet = true))
        assertEquals(232.dp to 16f / 9f, seeAllCardSize(RowStyle.Episode, tablet = true))
        assertNull(seeAllCardSize(RowStyle.Continue, tablet = false))
        assertNull(seeAllCardSize(RowStyle.Live, tablet = true))
    }
}
