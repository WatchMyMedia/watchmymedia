package media.watchmy.client.core.playback

import kotlinx.coroutines.runBlocking
import media.watchmy.client.core.playback.watchnext.WatchNext
import media.watchmy.client.core.playback.watchnext.WatchNextEntry
import media.watchmy.client.core.playback.watchnext.WatchNextLibrary
import media.watchmy.client.model.ContinueItem
import media.watchmy.client.model.HomeData
import media.watchmy.client.model.LibraryRepository
import media.watchmy.client.model.LibraryTotals
import media.watchmy.client.model.RowIds
import media.watchmy.client.model.RowStyle
import media.watchmy.client.model.ScreenRow
import media.watchmy.client.model.WatchTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The TV's screens' library keeps the launcher's Watch Next row in step with the server. */
class WatchNextLibraryTest {
    private class Row : WatchNext {
        val synced = mutableListOf<List<WatchNextEntry>>()
        val marked = mutableListOf<WatchTarget>()
        override fun update(entry: WatchNextEntry) = Unit
        override fun remove(fileId: Long) = Unit
        override fun sync(continueWatching: List<WatchNextEntry>) {
            synced += continueWatching
        }
        override fun removeMarked(target: WatchTarget) {
            marked += target
        }
    }

    private val episode = ContinueItem(ContinueItem.Kind.Episode, "Harbor Lights", "S03 E07 · The Long Dark", null, null, "/still.jpg", 9101, null, 100, 100037, 3, 7, 28 * 60_000L, 52 * 60_000L)
    private val movie = ContinueItem(ContinueItem.Kind.Movie, "Dead Reckoning Bay", null, null, "/b.jpg", null, 9002, 500, null, null, null, null, 31 * 60_000L, 103 * 60_000L)

    private val row = Row()
    private val marks = mutableListOf<String>()
    private val server = object : LibraryRepository by object : StubLibrary() {} {
        override suspend fun home() = HomeData(null, listOf(episode, movie), emptyList(), emptyList(), emptyList(), emptyList(), LibraryTotals(1, 1, 1, 2, 0), null, null, null)
        override suspend fun setWatched(kind: ContinueItem.Kind, id: Long, watched: Boolean) {
            marks += "$kind $id $watched"
        }
        override suspend fun setSeasonWatched(showId: Long, season: Int, watched: Boolean) {
            marks += "season $showId $season $watched"
        }
        override suspend fun setShowWatched(showId: Long, watched: Boolean) {
            marks += "show $showId $watched"
        }
    }
    private val library = WatchNextLibrary(server, row, now = { 1_000_000L })

    @Test
    fun eachHomeLoadBringsTheRowInLineWithContinueWatchingNewestFirst() = runBlocking {
        library.home()
        val (first, second) = row.synced.single()
        assertEquals(WatchNextEntry(9101, "Harbor Lights", "S03 E07 · The Long Dark", true, 28 * 60_000L, 52 * 60_000L, 1_000_000L, itemId = 100037, showId = 100, season = 3), first)
        assertEquals(WatchNextEntry(9002, "Dead Reckoning Bay", null, false, 31 * 60_000L, 103 * 60_000L, 999_999L, itemId = 500), second)
        assertTrue(row.synced.single().none { it.next })
    }

    @Test
    fun aMarkTakesTheItemsEntriesOffTheRowOnceTheServerHasIt() = runBlocking {
        library.setWatched(ContinueItem.Kind.Movie, 500, true)
        library.setWatched(ContinueItem.Kind.Episode, 100037, false)
        library.setSeasonWatched(100, 3, true)
        library.setShowWatched(100, true)
        assertEquals(listOf("Movie 500 true", "Episode 100037 false", "season 100 3 true", "show 100 true"), marks)
        assertEquals(listOf(WatchTarget.Movie(500), WatchTarget.Episode(100037), WatchTarget.Season(100, 3), WatchTarget.Show(100)), row.marked)
    }

    @Test
    fun anArrangedHomeFollowsItsContinueRowOrContinueWatchingAloneWhenHidden() = runBlocking {
        val totals = LibraryTotals(1, 1, 1, 2, 0)
        var rows = listOf(ScreenRow(RowIds.CONTINUE, RowStyle.Continue, "Continue watching", continueItems = listOf(movie)))
        var asked = 0
        val arranged = object : LibraryRepository by server {
            override suspend fun arrangedHome() = HomeData(null, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), totals, null, null, null, rows = rows)
            override suspend fun continueWatching(): List<ContinueItem> = listOf(episode, movie).also { asked++ }
        }
        val watched = WatchNextLibrary(arranged, row, now = { 1_000_000L })
        watched.arrangedHome()
        assertEquals(listOf(9002L), row.synced.last().map { it.fileId })
        assertEquals("the row's own items, no extra call", 0, asked)

        rows = emptyList()
        watched.arrangedHome()
        assertEquals("hidden on Home, still on the launcher", listOf(9101L, 9002L), row.synced.last().map { it.fileId })
        assertEquals(1, asked)
    }

    @Test
    fun aMarkTheServerRefusesLeavesTheRow() = runBlocking {
        val refusing = WatchNextLibrary(object : StubLibrary() {}, row)
        runCatching { refusing.setWatched(ContinueItem.Kind.Movie, 500, true) }
        assertTrue(row.marked.isEmpty())
    }
}
