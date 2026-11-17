package media.watchmy.client.presentation.detail

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import media.watchmy.client.fake.FakeData
import media.watchmy.client.fake.FakeLibraryRepository
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.LibraryRepository
import media.watchmy.client.model.RELATED_API_LEVEL
import media.watchmy.client.model.RelatedRowIds
import media.watchmy.client.model.RowStyle
import media.watchmy.client.model.ScreenRow
import media.watchmy.client.model.ShowArrival
import media.watchmy.client.presentation.Load
import media.watchmy.client.presentation.MainDispatcherRule
import media.watchmy.client.presentation.TestLibrary
import media.watchmy.client.presentation.value
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The rows at the end of the movie and show pages (PLAN-RELATED.md §6–§7), on both ViewModels. */
class RelatedRowsTest {
    @get:Rule val main = MainDispatcherRule()

    /** The fake library, counting the related requests, with a failure and a hold on demand. */
    private class Related(private val fake: LibraryRepository) : LibraryRepository by fake {
        constructor(level: Int = RELATED_API_LEVEL) : this(TestLibrary(FakeLibraryRepository(level = { level })))

        var asked = 0
        var failure: Exception? = null
        var hold: CompletableDeferred<Unit>? = null
        var reply: ((List<ScreenRow>) -> List<ScreenRow>)? = null

        private suspend fun counted(block: suspend () -> List<ScreenRow>): List<ScreenRow> {
            asked++
            hold?.await()
            failure?.let { throw it }
            return block().let { rows -> reply?.invoke(rows) ?: rows }
        }

        override suspend fun movieRelated(id: Long) = counted { fake.movieRelated(id) }

        override suspend fun showRelated(id: Long) = counted { fake.showRelated(id) }
    }

    @Test
    fun `a movie's page ends with its collection, then More like this, once the movie is in`() = runTest(main.dispatcher) {
        val library = Related()
        val vm = MovieViewModel(library, 500)
        advanceUntilIdle()
        val rows = vm.state.value.related
        assertEquals(listOf(RelatedRowIds.COLLECTION, RelatedRowIds.SIMILAR), rows.map { it.id })
        assertEquals(FakeData.COLLECTION_NAME, rows[0].title)
        assertEquals("release order, without this film", listOf("Cold Harbour Road", "Low Tide at Carrow"), rows[0].items.map { it.title })
        assertEquals("More like this", rows[1].title)
        assertEquals(1, library.asked)
    }

    @Test
    fun `the page never waits for the rows`() = runTest(main.dispatcher) {
        val library = Related().apply { hold = CompletableDeferred() }
        val vm = MovieViewModel(library, 500)
        advanceUntilIdle()
        assertTrue("the movie is in", vm.state.value.movie is Load.Ready)
        assertTrue("no rows yet", vm.state.value.related.isEmpty())
        library.hold!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, vm.state.value.related.size)
    }

    @Test
    fun `an older server gets no rows, and the page is as before`() = runTest(main.dispatcher) {
        val movie = MovieViewModel(Related(level = RELATED_API_LEVEL - 1), 500)
        val show = ShowPageViewModel(Related(level = RELATED_API_LEVEL - 1), 100, ShowArrival.Show)
        advanceUntilIdle()
        assertEquals("Dead Reckoning Bay", movie.state.value.movie.value?.title)
        assertTrue(movie.state.value.related.isEmpty())
        assertEquals("Harbor Lights", show.state.value.show?.title)
        assertTrue(show.state.value.related.isEmpty())
    }

    @Test
    fun `a title nothing is like ends as before`() = runTest(main.dispatcher) {
        val movie = MovieViewModel(Related(), FakeData.unlike.first { it >= 500 })
        val show = ShowPageViewModel(Related(), FakeData.unlike.first { it < 500 }, ShowArrival.Show)
        advanceUntilIdle()
        assertTrue(movie.state.value.movie is Load.Ready)
        assertTrue(movie.state.value.related.isEmpty())
        assertTrue(show.state.value.related.isEmpty())
    }

    @Test
    fun `a failure leaves the page without rows and says nothing, and the next load asks again`() = runTest(main.dispatcher) {
        val library = Related().apply { failure = ApiException(500, "Something broke.") }
        val vm = MovieViewModel(library, 500)
        advanceUntilIdle()
        assertTrue(vm.state.value.movie is Load.Ready)
        assertTrue(vm.state.value.related.isEmpty())
        assertEquals("the page says nothing of it", null, vm.state.value.message)

        library.failure = null
        vm.retry()
        advanceUntilIdle()
        assertEquals(2, library.asked)
        assertEquals(2, vm.state.value.related.size)
    }

    @Test
    fun `the rows are kept with the page, so a quiet reload doesn't ask again`() = runTest(main.dispatcher) {
        val library = Related()
        val vm = MovieViewModel(library, 500)
        advanceUntilIdle()
        vm.setWatched(true)
        advanceUntilIdle()
        assertEquals(1, library.asked)
        assertEquals(2, vm.state.value.related.size)
    }

    @Test
    fun `rows the page can't draw are left out`() = runTest(main.dispatcher) {
        val library = Related().apply {
            reply = { rows -> rows + ScreenRow("shelf", RowStyle.Wide, "A row from a newer server", items = rows.first().items) + rows.first().copy(id = "empty", items = emptyList()) }
        }
        val vm = MovieViewModel(library, 500)
        advanceUntilIdle()
        assertEquals(listOf(RelatedRowIds.COLLECTION, RelatedRowIds.SIMILAR), vm.state.value.related.map { it.id })
    }

    @Test
    fun `a show's page ends with More like this, shows only, once the show is in`() = runTest(main.dispatcher) {
        val library = Related()
        val vm = ShowPageViewModel(library, 100, ShowArrival.Show)
        advanceUntilIdle()
        val row = vm.state.value.related.single()
        assertEquals(RelatedRowIds.SIMILAR, row.id)
        assertTrue(row.items.isNotEmpty() && row.items.none { it.id == 100L } && row.items.all { it.kind == media.watchmy.client.model.ItemKind.Show })

        // Another season loads the season alone; the row stays as it is.
        vm.selectSeason(1)
        advanceUntilIdle()
        assertEquals(1, library.asked)
        assertEquals(row, vm.state.value.related.single())
    }

    @Test
    fun `a show's failed row leaves the page as it is`() = runTest(main.dispatcher) {
        val library = Related().apply { failure = ApiException(null, "Couldn't reach the server.", java.io.IOException("offline")) }
        val vm = ShowPageViewModel(library, 100, ShowArrival.Show)
        advanceUntilIdle()
        assertTrue(vm.state.value.page is Load.Ready)
        assertTrue(vm.state.value.related.isEmpty())
        assertEquals(null, vm.state.value.message)
    }
}
