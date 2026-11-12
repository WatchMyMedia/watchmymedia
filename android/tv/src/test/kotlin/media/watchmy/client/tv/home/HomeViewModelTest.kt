package media.watchmy.client.tv.home

import app.cash.turbine.test
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import media.watchmy.client.fake.FakeData
import media.watchmy.client.fake.FakeLibraryRepository
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.ContinueItem
import media.watchmy.client.model.HomeData
import media.watchmy.client.model.LibraryRepository
import media.watchmy.client.presentation.Load
import media.watchmy.client.tv.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class HomeViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    /** The fake library, failing [failures] times first and reporting [changes]. */
    private class Library(var failures: Int = 0) : LibraryRepository by FakeLibraryRepository() {
        var calls = 0
        val changesFlow = MutableSharedFlow<Unit>()
        override val changes = changesFlow

        override suspend fun home(): HomeData {
            calls++
            if (failures > 0) {
                failures--
                throw ApiException(500, "The library is being scanned.")
            }
            return FakeData.home()
        }
    }

    @Test
    fun `loads the hero and rows`() = runTest(main.dispatcher) {
        val vm = HomeViewModel(Library())
        vm.state.test {
            assertTrue(awaitItem().loading)
            val home = awaitItem().home!!
            assertEquals("Harbor Lights", home.hero?.title)
            assertEquals(5, home.continueWatching.size)
            assertTrue(home.recentlyAired.any { !it.hasFile })
        }
    }

    @Test
    fun `a movie marked shows at once, and Home goes back as it was if the server refuses it`() = runTest(main.dispatcher) {
        val refusing = object : LibraryRepository by Library() {
            var refuse = true
            override suspend fun setWatched(kind: ContinueItem.Kind, id: Long, watched: Boolean) {
                if (refuse) throw ApiException(null, "Couldn't reach the server.")
            }
        }
        val vm = HomeViewModel(refusing)
        advanceUntilIdle()
        val before = vm.state.value.home!!
        val movie = before.recentlyAddedMovies.first { !it.watched }
        vm.toggleMovieWatched(movie)
        val shown = vm.state.value.home!!
        assertTrue("shown before the server answers", shown.recentlyAddedMovies.first { it.id == movie.id }.watched)
        advanceUntilIdle()
        assertEquals(false, vm.state.value.home!!.recentlyAddedMovies.first { it.id == movie.id }.watched)
        assertEquals("Couldn't reach the server.", vm.state.value.message)
    }

    @Test
    fun `a failure shows the message and Try again reloads`() = runTest(main.dispatcher) {
        val library = Library(failures = 1)
        val vm = HomeViewModel(library)
        advanceUntilIdle()
        assertEquals("The library is being scanned.", vm.state.value.error)
        vm.load()
        assertTrue(vm.state.value.loading)
        advanceUntilIdle()
        assertTrue(vm.state.value.home != null)
    }

    @Test
    fun `a change on the server refetches quietly and keeps rows when that fails`() = runTest(main.dispatcher) {
        val library = Library()
        val vm = HomeViewModel(library)
        advanceUntilIdle()
        library.failures = 1
        library.changesFlow.emit(Unit)
        advanceUntilIdle()
        assertEquals(2, library.calls)
        assertTrue(vm.state.value.home != null)
    }

    @Test
    fun `long-press on a movie marks it watched and says so`() = runTest(main.dispatcher) {
        val vm = HomeViewModel(Library())
        advanceUntilIdle()
        vm.toggleMovieWatched(FakeData.movies[0])
        advanceUntilIdle()
        assertEquals("Marked as watched", vm.state.value.message)
        vm.messageShown()
        assertNull(vm.state.value.message)
    }

    @Test
    fun `the hero carries its certification`() {
        assertEquals("TV-14", FakeData.home().hero!!.contentRating)
    }
}
