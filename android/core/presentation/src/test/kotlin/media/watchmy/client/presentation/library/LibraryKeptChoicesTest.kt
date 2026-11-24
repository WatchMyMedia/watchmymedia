package media.watchmy.client.presentation.library

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import media.watchmy.client.fake.FakeData
import media.watchmy.client.model.BrowseRequest
import media.watchmy.client.model.BrowseRequests
import media.watchmy.client.model.GridChoices
import media.watchmy.client.model.ItemFilter
import media.watchmy.client.model.ItemSort
import media.watchmy.client.model.LibraryType
import media.watchmy.client.presentation.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/** The TV keeps each library's sort and filters on the device, so the next visit opens as the last was left. */
class LibraryKeptChoicesTest {
    @get:Rule val main = MainDispatcherRule(UnconfinedTestDispatcher())

    private val library = GridLibrary()
    private val kept = GridChoices.InMemory()
    private val requests = BrowseRequests()

    /** A visit: a new grid, with a new saved state, as the rail opens one. */
    private fun visit(type: LibraryType = LibraryType.Tv, keptAs: String? = "home/$type") =
        LibraryViewModel(library, type, SavedStateHandle(), requests = requests, kept = kept, keptAs = keptAs)

    @Test fun `a library opens with the sort and filters it was left with`() = runTest(main.dispatcher) {
        visit().apply {
            setSort(ItemSort.Title)
            setFilter(ItemFilter.Unwatched)
            setGenre("Drama")
            setDecade(2020)
        }
        val next = visit()
        advanceUntilIdle()
        assertEquals(LibraryViewModel.Choices(sort = ItemSort.Title, filter = ItemFilter.Unwatched, genre = "Drama", decade = 2020), next.state.value.choices)
        assertEquals("the first page is asked for that way", ItemSort.Title, library.queries.last().sort)
    }

    @Test fun `each library and server keeps its own, and Clear filters is kept too`() = runTest(main.dispatcher) {
        visit().setFilter(ItemFilter.Unwatched)
        assertEquals(LibraryViewModel.Choices(), visit(LibraryType.Movie).state.value.choices)
        assertEquals(LibraryViewModel.Choices(), visit(keptAs = "cabin/${LibraryType.Tv}").state.value.choices)

        visit().clearFilters()
        assertEquals(LibraryViewModel.Choices(), visit().state.value.choices)
    }

    @Test fun `a row's See all sorts that visit only`() = runTest(main.dispatcher) {
        visit().setSort(ItemSort.Title)
        visit().open(BrowseRequest(FakeData.TV_LIBRARY, ItemSort.Aired))
        assertEquals(ItemSort.Title, visit().state.value.choices.sort)
    }

    @Test fun `a sort the library doesn't offer reads as the default, and the phone keeps nothing`() = runTest(main.dispatcher) {
        kept.keep("home/${LibraryType.Tv}", mapOf("sort" to ItemSort.Runtime.name, "filter" to "Gone"))
        assertEquals(LibraryViewModel.Choices(), visit().state.value.choices)

        LibraryViewModel(library, LibraryType.Movie, SavedStateHandle()).setSort(ItemSort.Title)
        assertNull(kept.recalled("home/${LibraryType.Movie}"))
    }
}
