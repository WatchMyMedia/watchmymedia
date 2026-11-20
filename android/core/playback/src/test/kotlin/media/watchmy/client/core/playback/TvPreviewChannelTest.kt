package media.watchmy.client.core.playback

import media.watchmy.client.core.playback.watchnext.TvPreviewChannel
import media.watchmy.client.model.BrowseItem
import media.watchmy.client.model.DeepLink
import media.watchmy.client.model.HomeData
import media.watchmy.client.model.ItemKind
import media.watchmy.client.model.LibraryTotals
import media.watchmy.client.model.RowIds
import media.watchmy.client.model.RowStyle
import media.watchmy.client.model.ScreenRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The launcher's Recently added channel: Home's
 * newest movies and shows, alternating, at most twelve, from the fixed rows
 * or the arranged Home's; each opens its page, for its account only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TvPreviewChannelTest {
    private fun item(kind: ItemKind, id: Long) = BrowseItem(
        kind, id, 1, "$kind $id", 2026, "", emptyList(), null, "/p$id.jpg", null, null, false, false, null, false, null, null, null, null,
    )

    private fun home(movies: List<BrowseItem>, shows: List<BrowseItem>, rows: List<ScreenRow>? = null) =
        HomeData(null, emptyList(), shows, emptyList(), movies, emptyList(), LibraryTotals(0, 0, 0, 0, 0), null, null, null, rows = rows)

    @Test fun `movies and shows alternate, at most twelve, and unmatched files stay off`() {
        val movies = (1L..10L).map { item(ItemKind.Movie, it) } + item(ItemKind.Unmatched, 99)
        val shows = (101L..103L).map { item(ItemKind.Show, it) }
        val picked = TvPreviewChannel.recentlyAdded(home(movies, shows))
        assertEquals(TvPreviewChannel.MAX_PROGRAMMES, picked.size)
        assertEquals(listOf(1L, 101L, 2L, 102L, 3L, 103L, 4L, 5L), picked.take(8).map { it.id })
    }

    @Test fun `an arranged Home's rows of the same name fill it`() {
        val rows = listOf(
            ScreenRow(RowIds.RECENTLY_ADDED_MOVIES, RowStyle.Poster, "Recently added movies", items = listOf(item(ItemKind.Movie, 7))),
            ScreenRow(RowIds.RECENTLY_ADDED_SHOWS, RowStyle.Poster, "Recently added shows", items = listOf(item(ItemKind.Show, 8))),
        )
        assertEquals(listOf(7L, 8L), TvPreviewChannel.recentlyAdded(home(emptyList(), emptyList(), rows)).map { it.id })
    }

    @Test fun `a programme opens its page, for its own account`() {
        val link = TvPreviewChannel.pageLink(item(ItemKind.Show, 101), "server-1:7").toString()
        assertEquals(DeepLink.Show(101), DeepLink.open(link, "server-1:7"))
        assertNull("Another account's channel opens nothing", DeepLink.open(link, "server-1:8"))
        assertEquals(DeepLink.Movie(5), DeepLink.open(TvPreviewChannel.pageLink(item(ItemKind.Movie, 5), "a").toString(), "a"))
    }
}
