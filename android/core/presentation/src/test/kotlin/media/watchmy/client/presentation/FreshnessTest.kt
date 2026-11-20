package media.watchmy.client.presentation

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import media.watchmy.client.fake.FakeLibraryRepository
import media.watchmy.client.model.LibraryChange
import media.watchmy.client.model.LibraryRepository
import media.watchmy.client.model.ShowArrival
import media.watchmy.client.presentation.detail.MovieViewModel
import media.watchmy.client.presentation.detail.ShowPageViewModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Pages reload for their own changes, at once while shown and once when next shown. */
class FreshnessTest {
    @get:Rule val main = MainDispatcherRule(UnconfinedTestDispatcher())

    private val events = MutableSharedFlow<LibraryChange>()

    /** The fake library with its changes driven by the test, counting page loads. */
    private inner class Counting(private val fake: LibraryRepository = FakeLibraryRepository()) : LibraryRepository by fake {
        var movieLoads = 0
        var showLoads = 0
        override val libraryChanges = events

        override suspend fun movie(id: Long) = fake.movie(id).also { movieLoads++ }

        override suspend fun show(id: Long) = fake.show(id).also { showLoads++ }
    }

    @Test
    fun `a change reloads a shown page at once, and a hidden one once when it is shown again`() = runTest(main.dispatcher) {
        var reloads = 0
        val freshness = Freshness(backgroundScope, events, { 7L in it.movies }) { reloads++ }
        advanceUntilIdle()
        events.emit(LibraryChange(movies = setOf(7)))
        assertEquals(1, reloads)

        freshness.setShown(false)
        events.emit(LibraryChange(movies = setOf(7)))
        events.emit(LibraryChange(movies = setOf(7)))
        assertEquals("nothing while hidden", 1, reloads)
        freshness.setShown(true)
        assertEquals("one reload for both", 2, reloads)
        freshness.setShown(false)
        freshness.setShown(true)
        assertEquals("nothing came meanwhile", 2, reloads)
    }

    @Test
    fun `a change the page doesn't show is ignored, shown or not`() = runTest(main.dispatcher) {
        var reloads = 0
        val freshness = Freshness(backgroundScope, events, { 7L in it.movies }) { reloads++ }
        advanceUntilIdle()
        events.emit(LibraryChange(movies = setOf(8)))
        freshness.setShown(false)
        events.emit(LibraryChange(episodes = setOf(9)))
        freshness.setShown(true)
        assertEquals(0, reloads)
    }

    @Test
    fun `a movie page reloads for its own movie and library changes, not another item's`() = runTest(main.dispatcher) {
        val library = Counting()
        val vm = MovieViewModel(library, 501)
        advanceUntilIdle()
        assertEquals(1, library.movieLoads)
        events.emit(LibraryChange(movies = setOf(500)))
        events.emit(LibraryChange(episodes = setOf(100035)))
        advanceUntilIdle()
        assertEquals(1, library.movieLoads)
        events.emit(LibraryChange(movies = setOf(501)))
        advanceUntilIdle()
        assertEquals(2, library.movieLoads)
        events.emit(LibraryChange(libraries = setOf(2)))
        advanceUntilIdle()
        assertEquals(3, library.movieLoads)
        vm.setShown(false)
        events.emit(LibraryChange.Everything)
        advanceUntilIdle()
        assertEquals(3, library.movieLoads)
        vm.setShown(true)
        advanceUntilIdle()
        assertEquals(4, library.movieLoads)
    }

    @Test
    fun `a show page reloads for its loaded episodes, not a movie or another show's episode`() = runTest(main.dispatcher) {
        val library = Counting()
        val vm = ShowPageViewModel(library, 100, ShowArrival.Episode(100035, 3))
        advanceUntilIdle()
        val loads = library.showLoads
        val episode = vm.state.value.season!!.episodes.first().id
        events.emit(LibraryChange(movies = setOf(501)))
        events.emit(LibraryChange(episodes = setOf(987654)))
        advanceUntilIdle()
        assertEquals(loads, library.showLoads)
        events.emit(LibraryChange(episodes = setOf(episode)))
        advanceUntilIdle()
        assertEquals(loads + 1, library.showLoads)
    }
}
