package media.watchmy.client.presentation.library

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import media.watchmy.client.fake.FakeData
import media.watchmy.client.model.BrowseRequest
import media.watchmy.client.model.BrowseRequests
import media.watchmy.client.model.ItemFilter
import media.watchmy.client.model.ItemSort
import media.watchmy.client.model.LibraryType
import media.watchmy.client.presentation.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * A row's See all reaching the TV Shows or Movies grid:
 * the grid takes its library's request once, as if the person had
 * chosen it, over what its saved state brought back, and a later choice
 * sticks.
 */
class LibraryBrowseRequestTest {
    @get:Rule val main = MainDispatcherRule(UnconfinedTestDispatcher())

    private val library = GridLibrary()
    private val requests = BrowseRequests()
    private val aired = BrowseRequest(FakeData.TV_LIBRARY, ItemSort.Aired)

    private fun vm(saved: SavedStateHandle = SavedStateHandle(), type: LibraryType = LibraryType.Tv) =
        LibraryViewModel(library, type, saved, requests = requests)

    @Test fun `a request posted before the grid starts makes its first page`() = runTest(main.dispatcher) {
        requests.post(aired)
        val vm = vm()
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals(LibraryViewModel.Choices(sort = ItemSort.Aired), s.choices)
        assertEquals("one load, already sorted the row's way", listOf(ItemSort.Aired), library.queries.map { it.sort })
        assertEquals(0, library.queries.single().offset)
        assertTrue("the screen goes to the grid", s.toGrid)
        assertNull("taken", requests.pending.value)

        vm.gridShown()
        assertFalse(vm.state.value.toGrid)
    }

    @Test fun `the request wins over the choices the saved state brought back`() = runTest(main.dispatcher) {
        val saved = SavedStateHandle()
        vm(saved).apply {
            setSort(ItemSort.Title)
            setFilter(ItemFilter.Unwatched)
            setGenre("Drama")
            setDecade(2020)
            setText("harbor")
        }
        advanceUntilIdle()

        requests.post(aired)
        val restored = vm(saved)
        advanceUntilIdle()
        assertEquals(LibraryViewModel.Choices(sort = ItemSort.Aired), restored.state.value.choices)
        val q = library.queries.last()
        assertEquals(ItemSort.Aired, q.sort)
        assertEquals(ItemFilter.All, q.filter)
        assertNull(q.genre)
        assertNull(q.decade)
        assertEquals("", q.text.orEmpty())
    }

    @Test fun `a request is used once, and kept like the person's own choice`() = runTest(main.dispatcher) {
        val saved = SavedStateHandle()
        requests.post(aired)
        vm(saved)
        advanceUntilIdle()

        // The process comes back from the saved state: the row's sort stays, and nothing is applied again.
        val restored = vm(saved)
        advanceUntilIdle()
        assertEquals(ItemSort.Aired, restored.state.value.choices.sort)
        assertFalse(restored.state.value.toGrid)

        // A second visit to the tab finds nothing to take.
        val later = vm()
        advanceUntilIdle()
        assertEquals(ItemSort.Added, later.state.value.choices.sort)
        assertFalse(later.state.value.toGrid)
    }

    @Test fun `a later sort choice sticks`() = runTest(main.dispatcher) {
        val saved = SavedStateHandle()
        requests.post(aired)
        val vm = vm(saved)
        advanceUntilIdle()
        vm.gridShown()

        vm.setSort(ItemSort.Title)
        advanceUntilIdle()
        assertEquals(ItemSort.Title, vm.state.value.choices.sort)
        assertFalse(vm.state.value.toGrid)

        val restored = vm(saved)
        advanceUntilIdle()
        assertEquals(ItemSort.Title, restored.state.value.choices.sort)
    }

    @Test fun `a request posted while the grid is open is taken at once, from the first page`() = runTest(main.dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.setFilter(ItemFilter.Continuing)
        vm.setText("harbor")
        advanceUntilIdle()
        library.queries.clear()

        requests.post(aired)
        advanceUntilIdle()
        assertNull(requests.pending.value)
        val s = vm.state.value
        assertEquals(LibraryViewModel.Choices(sort = ItemSort.Aired), s.choices)
        assertTrue(s.toGrid)
        assertEquals(0, library.queries.last().offset)
        assertEquals(ItemSort.Aired, library.queries.last().sort)

        // The same row's See all again, after the grid went to its heading: it goes there again.
        vm.gridShown()
        requests.post(aired)
        advanceUntilIdle()
        assertTrue(vm.state.value.toGrid)
    }

    @Test fun `another library's request is left for its own grid`() = runTest(main.dispatcher) {
        val shows = vm()
        advanceUntilIdle()
        val released = BrowseRequest(FakeData.MOVIE_LIBRARY, ItemSort.Released)
        requests.post(released)
        advanceUntilIdle()
        assertEquals(ItemSort.Added, shows.state.value.choices.sort)
        assertFalse(shows.state.value.toGrid)
        assertEquals(released, requests.pending.value)

        val movies = vm(type = LibraryType.Movie)
        advanceUntilIdle()
        assertEquals(ItemSort.Released, movies.state.value.choices.sort)
        assertNull(requests.pending.value)
    }

    @Test fun `a sort the library doesn't offer leaves its sort, and the link's filter, genre and decade apply`() = runTest(main.dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.setSort(ItemSort.Title)
        advanceUntilIdle()

        vm.open(BrowseRequest(FakeData.TV_LIBRARY, ItemSort.Released, ItemFilter.Continuing, "Drama", 2020))
        advanceUntilIdle()
        assertEquals(LibraryViewModel.Choices(ItemSort.Title, ItemFilter.Continuing, "Drama", 2020), vm.state.value.choices)

        vm.open(BrowseRequest(FakeData.TV_LIBRARY, ItemSort.Added, ItemFilter.FourK))
        advanceUntilIdle()
        assertEquals("4K is a TV filter too", LibraryViewModel.Choices(ItemSort.Added, ItemFilter.FourK), vm.state.value.choices)
    }

    @Test fun `without requests the grid starts as it always has`() = runTest(main.dispatcher) {
        requests.post(aired)
        val vm = LibraryViewModel(library, LibraryType.Tv, SavedStateHandle())
        advanceUntilIdle()
        assertEquals(ItemSort.Added, vm.state.value.choices.sort)
        assertEquals("left for a grid that takes it", aired, requests.pending.value)
    }
}
