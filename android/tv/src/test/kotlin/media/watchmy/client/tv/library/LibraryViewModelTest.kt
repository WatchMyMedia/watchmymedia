package media.watchmy.client.tv.library

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceUntilIdle
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.test.runTest
import media.watchmy.client.fake.FakeData
import media.watchmy.client.fake.FakeLibraryRepository
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.ContinueItem
import media.watchmy.client.model.ItemFilter
import media.watchmy.client.model.ItemPage
import media.watchmy.client.model.ItemQuery
import media.watchmy.client.model.ItemSort
import media.watchmy.client.model.Library
import media.watchmy.client.model.LibraryRepository
import media.watchmy.client.model.LibraryType
import media.watchmy.client.tv.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LibraryViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    /** Records each query; fails the next [failures] page loads; [counts] false acts as a server without library counts. */
    private class Recording(var failures: Int = 0, val counts: Boolean = true) : LibraryRepository by FakeLibraryRepository() {
        val queries = mutableListOf<ItemQuery>()
        private val inner = FakeLibraryRepository()

        override suspend fun items(query: ItemQuery): ItemPage {
            if (query.limit > 1) queries += query
            if (failures > 0 && query.limit > 1) {
                failures--
                throw ApiException(500, "Library unavailable.")
            }
            return inner.items(query).let { if (counts) it else it.copy(summary = null) }
        }
    }

    @Test
    fun `loads the first page sorted by date added, with the summary line`() = runTest(main.dispatcher) {
        val library = Recording()
        val vm = LibraryViewModel(library, LibraryType.Tv, pageSize = 5, saved = SavedStateHandle())
        assertTrue(vm.state.value.loading)
        advanceUntilIdle()
        val s = vm.state.value
        assertFalse(s.loading)
        assertEquals(5, s.items.size)
        assertEquals(16, s.total)
        assertTrue(s.hasMore)
        assertEquals(ItemSort.Added, library.queries.single().sort)
        assertEquals(FakeData.TV_LIBRARY, library.queries.single().libraryId)
        assertEquals("16 shows · 312 episodes · 12 continuing", s.summary)
        assertTrue("Drama" in s.genres)
        assertTrue(2020 in s.decades)
    }

    @Test
    fun `without the library's counts the summary comes from Home's totals`() = runTest(main.dispatcher) {
        val vm = LibraryViewModel(Recording(counts = false), LibraryType.Tv, pageSize = 5, saved = SavedStateHandle())
        advanceUntilIdle()
        assertEquals("142 shows · 3,418 episodes · 12 continuing", vm.state.value.summary)
        val movies = LibraryViewModel(Recording(counts = false), LibraryType.Movie, saved = SavedStateHandle())
        advanceUntilIdle()
        assertEquals("214 movies · 9 unwatched", movies.state.value.summary)
    }

    @Test
    fun `keeps a page loaded ahead of focus`() = runTest(main.dispatcher) {
        val library = Recording()
        val vm = LibraryViewModel(library, LibraryType.Tv, pageSize = 5, saved = SavedStateHandle())
        advanceUntilIdle()
        assertEquals(5, vm.state.value.items.size)
        // The first page on screen fetches the second.
        vm.onVisible(1)
        assertTrue(vm.state.value.loadingMore)
        advanceUntilIdle()
        assertEquals(10, vm.state.value.items.size)
        assertEquals(5, library.queries.last().offset)
        // Still on the first page: the second is already here.
        vm.onVisible(4)
        assertFalse(vm.state.value.loadingMore)
        // Into the second page: the third.
        vm.onVisible(5)
        advanceUntilIdle()
        assertEquals(15, vm.state.value.items.size)
        assertEquals(10, library.queries.last().offset)
        assertEquals(FakeData.shows.take(15).map { it.id }, vm.state.value.items.map { it.id })
    }

    @Test
    fun `filters, genres and decades reload from the first page`() = runTest(main.dispatcher) {
        val vm = LibraryViewModel(Recording(), LibraryType.Tv, saved = SavedStateHandle())
        advanceUntilIdle()

        vm.setFilter(ItemFilter.Ended)
        advanceUntilIdle()
        assertEquals(listOf("The Lantern House", "Westward Signals", "Ferrous", "Pale Water"), vm.state.value.items.map { it.title })

        vm.setFilter(ItemFilter.All)
        vm.toggleGenre("Sci-Fi")
        advanceUntilIdle()
        assertEquals(setOf("The Quiet Meridian", "Orbital Decay", "Kestrel Station"), vm.state.value.items.map { it.title }.toSet())
        vm.toggleGenre("Sci-Fi")
        assertNull(vm.state.value.choices.genre)

        vm.setDecade(2010)
        advanceUntilIdle()
        assertTrue(vm.state.value.items.all { it.year!! in 2010..2019 })
        assertTrue(vm.state.value.choices.narrowed)
        vm.clearChoices()
        advanceUntilIdle()
        assertEquals(16, vm.state.value.items.size)
    }

    @Test
    fun `sorting by title`() = runTest(main.dispatcher) {
        val vm = LibraryViewModel(Recording(), LibraryType.Movie, saved = SavedStateHandle())
        advanceUntilIdle()
        vm.setSort(ItemSort.Title)
        advanceUntilIdle()
        // The fake sorts as the server does, ignoring a leading "The".
        assertEquals(listOf("The Archivist", "Cold Harbour Road", "Dead Reckoning Bay"), vm.state.value.items.take(3).map { it.title })
        assertEquals(ItemSort.Title, vm.state.value.choices.sort)
    }

    @Test
    fun `movies offer movie sorts and filters`() = runTest(main.dispatcher) {
        val vm = LibraryViewModel(Recording(), LibraryType.Movie, saved = SavedStateHandle())
        assertTrue(ItemSort.Runtime in vm.sorts && ItemSort.Released in vm.sorts && ItemSort.Aired !in vm.sorts)
        assertFalse(ItemFilter.Continuing in vm.filters)
        advanceUntilIdle()
        assertEquals("10 movies · 9 unwatched", vm.state.value.summary)
        assertEquals("2026 · 1 h 52 min", vm.state.value.items.first().gridSubtitle())
    }

    @Test
    fun `a failed load shows the message and retries`() = runTest(main.dispatcher) {
        val vm = LibraryViewModel(Recording(failures = 1), LibraryType.Tv, saved = SavedStateHandle())
        advanceUntilIdle()
        assertEquals("Library unavailable.", vm.state.value.error)
        vm.reload()
        advanceUntilIdle()
        assertNull(vm.state.value.error)
        assertEquals(16, vm.state.value.items.size)
    }

    @Test
    fun `a server without that kind of library says so`() = runTest(main.dispatcher) {
        val library = object : LibraryRepository by FakeLibraryRepository() {
            override suspend fun libraries() = listOf(Library(1, "Movies", LibraryType.Movie))
        }
        val vm = LibraryViewModel(library, LibraryType.Tv, saved = SavedStateHandle())
        advanceUntilIdle()
        assertEquals("This server has no TV library yet. Add one in the web app.", vm.state.value.error)
    }

    @Test
    fun `long-press marks a movie watched in place`() = runTest(main.dispatcher) {
        val vm = LibraryViewModel(Recording(), LibraryType.Movie, saved = SavedStateHandle())
        advanceUntilIdle()
        val first = vm.state.value.items.first()
        vm.toggleWatched(first)
        assertTrue("shown before the server answers", vm.state.value.items.first().watched)
        advanceUntilIdle()
        assertTrue(vm.state.value.items.first().watched)
        assertEquals("Marked ${first.title} watched", vm.state.value.notice)
    }

    @Test
    fun `a mark the server refuses goes back as it was`() = runTest(main.dispatcher) {
        val refusing = object : LibraryRepository by Recording() {
            override suspend fun setWatched(kind: ContinueItem.Kind, id: Long, watched: Boolean) = throw ApiException(null, "Couldn't reach the server.")
        }
        val vm = LibraryViewModel(refusing, LibraryType.Movie, saved = SavedStateHandle())
        advanceUntilIdle()
        val first = vm.state.value.items.first()
        vm.toggleWatched(first)
        advanceUntilIdle()
        assertEquals(first, vm.state.value.items.first())
        assertEquals("Couldn't reach the server.", vm.state.value.notice)
    }

    @Test
    fun `the choices outlive the process`() = runTest(main.dispatcher) {
        val saved = SavedStateHandle()
        val vm = LibraryViewModel(Recording(), LibraryType.Tv, pageSize = 5, saved = saved)
        advanceUntilIdle()
        vm.setSort(ItemSort.Title)
        vm.setFilter(ItemFilter.Unwatched)
        vm.toggleGenre("Drama")
        vm.setDecade(2020)
        advanceUntilIdle()

        val library = Recording()
        val restored = LibraryViewModel(library, LibraryType.Tv, pageSize = 5, saved = saved)
        advanceUntilIdle()
        assertEquals(LibraryViewModel.Choices(ItemSort.Title, ItemFilter.Unwatched, "Drama", 2020), restored.state.value.choices)
        val q = library.queries.first()
        assertEquals(ItemSort.Title, q.sort)
        assertEquals(ItemFilter.Unwatched, q.filter)
        assertEquals("Drama", q.genre)
        assertEquals(2020, q.decade)
    }

    @Test
    fun `a quiet refresh that answers late doesn't land over a newer load`() = runTest(main.dispatcher) {
        val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val gate = CompletableDeferred<Unit>()
        val library = object : LibraryRepository by FakeLibraryRepository() {
            private val inner = FakeLibraryRepository()
            var holdNext = false
            override val changes = changes

            override suspend fun items(query: ItemQuery): ItemPage {
                if (holdNext && query.limit > 1) {
                    holdNext = false
                    gate.await()
                }
                return inner.items(query)
            }
        }
        val vm = LibraryViewModel(library, LibraryType.Tv, pageSize = 5, saved = SavedStateHandle())
        advanceUntilIdle()
        library.holdNext = true
        changes.tryEmit(Unit)
        advanceUntilIdle()
        vm.setSort(ItemSort.Title)
        advanceUntilIdle()
        val titles = vm.state.value.items.map { it.title }
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(titles, vm.state.value.items.map { it.title })
        assertEquals(ItemSort.Title, vm.state.value.choices.sort)
    }
}
