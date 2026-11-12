package media.watchmy.client.mobile.library

import kotlinx.coroutines.CompletableDeferred
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import media.watchmy.client.fake.FakeAuthRepository
import media.watchmy.client.fake.FakeData
import media.watchmy.client.fake.FakeLibraryRepository
import media.watchmy.client.model.ItemPage
import media.watchmy.client.model.ItemQuery
import media.watchmy.client.model.LibraryRepository
import media.watchmy.client.mobile.MainDispatcherRule
import media.watchmy.client.mobile.TestLibrary
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.ItemFilter
import media.watchmy.client.model.ItemSort
import media.watchmy.client.model.Library
import media.watchmy.client.model.LibraryType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LibraryViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val library = TestLibrary()

    private fun vm(type: LibraryType = LibraryType.Tv, pageSize: Int = 60) = LibraryViewModel(library, FakeAuthRepository(), type, pageSize, saved = SavedStateHandle())

    @Test fun `loads the TV library sorted by date added`() = runTest {
        val vm = vm()
        val s = vm.state.value
        assertFalse(s.loading)
        assertEquals(FakeData.TV_LIBRARY, s.libraryId)
        assertEquals(16, s.total)
        assertEquals("Harbor Lights", s.items.first().title)
        assertEquals(ItemSort.Added, library.queries.single().sort)
        assertTrue("Drama" in s.genres)
        assertTrue(2020 in s.decades)
    }

    @Test fun `the header counts the whole library unless a filter is on`() = runTest {
        val vm = vm()
        val all = vm.state.value
        assertEquals("16 shows · 312 episodes", countLine(all, "show"))
        // Servers without counts: what the grid holds.
        assertEquals("16 shows", countLine(all.copy(summary = null), "show"))
        vm.setFilter(ItemFilter.FourK)
        assertEquals("7 shows", countLine(vm.state.value, "show"))
        assertEquals("10 movies", countLine(vm(LibraryType.Movie).state.value, "movie"))
    }

    @Test fun `filters, genre and decade go into the query and narrow the grid`() = runTest {
        val vm = vm()
        vm.setFilter(ItemFilter.FourK)
        assertTrue(vm.state.value.items.all { it.quality == "4K" })
        assertTrue(vm.state.value.filtered)

        vm.setGenre("Sci-Fi")
        assertEquals(listOf("The Quiet Meridian", "Orbital Decay", "Kestrel Station"), vm.state.value.items.map { it.title })
        vm.setGenre("Sci-Fi")
        assertNull("picking the genre again clears it", vm.state.value.genre)

        vm.setDecade(2010)
        assertEquals(2010, library.queries.last().decade)
        assertTrue(vm.state.value.items.all { it.year in 2010..2019 })

        vm.clearFilters()
        assertFalse(vm.state.value.filtered)
        assertEquals(16, vm.state.value.items.size)
    }

    @Test fun `a mark shows on the card at once, and goes back if the server refuses it`() = runTest {
        val vm = vm(LibraryType.Movie)
        val item = vm.state.value.items.first { !it.watched }
        library.markGate = CompletableDeferred()
        vm.setWatched(item, true)
        assertTrue("shown before the server answers", vm.state.value.items.first { it.id == item.id }.watched)
        library.failure = ApiException(null, "Couldn't reach the server.")
        library.markGate!!.complete(Unit)
        assertEquals(item, vm.state.value.items.first { it.id == item.id })
        assertEquals("Couldn't reach the server.", vm.state.value.message)
    }

    @Test fun `sorting asks again`() = runTest {
        val vm = vm()
        vm.setSort(ItemSort.Title)
        assertEquals(ItemSort.Title, library.queries.last().sort)
        assertEquals("Copperline", vm.state.value.items.first().title)
    }

    @Test fun `pages load as the grid scrolls`() = runTest {
        val vm = vm(pageSize = 5)
        assertEquals(5, vm.state.value.items.size)
        assertTrue(vm.state.value.hasMore)
        vm.loadMore()
        assertEquals(10, vm.state.value.items.size)
        assertEquals(5, library.queries.last().offset)
        vm.loadMore()
        vm.loadMore()
        assertEquals(16, vm.state.value.items.size)
        assertFalse(vm.state.value.hasMore)
        val calls = library.queries.size
        vm.loadMore()
        assertEquals("nothing more to load", calls, library.queries.size)
    }

    @Test fun `a failed page offers Try again without losing the grid`() = runTest {
        val vm = vm(pageSize = 5)
        library.failure = ApiException(500, "Database is locked.")
        vm.loadMore()
        assertEquals("Database is locked.", vm.state.value.moreError)
        assertEquals(5, vm.state.value.items.size)
        library.failure = null
        vm.retryMore()
        assertNull(vm.state.value.moreError)
        assertEquals(10, vm.state.value.items.size)
    }

    @Test fun `a failed first load is an error with Try again`() = runTest {
        library.failure = ApiException(null, "Couldn't reach the server.")
        val vm = vm()
        assertEquals("Couldn't reach the server.", vm.state.value.error)
        library.failure = null
        vm.retry()
        assertNull(vm.state.value.error)
        assertEquals(16, vm.state.value.items.size)
    }

    @Test fun `no library of the type is its own empty state`() = runTest {
        library.librariesOverride = listOf(Library(1, "Movies", LibraryType.Movie))
        val vm = vm(LibraryType.Tv)
        assertTrue(vm.state.value.noLibrary)
        assertTrue(vm.state.value.items.isEmpty())
    }

    @Test fun `marking watched updates the card in place`() = runTest {
        val vm = vm(LibraryType.Movie)
        val item = vm.state.value.items.first { !it.watched }
        vm.setWatched(item, true)
        assertEquals(listOf("Movie ${item.id} true"), library.watchCalls)
        assertTrue(vm.state.value.items.first { it.id == item.id }.watched)
        assertEquals("Marked as watched", vm.state.value.message)
    }

    @Test fun `title jump reaches a letter beyond the loaded page and paging continues at that offset`() = runTest {
        val vm = vm(pageSize = 2)
        vm.setSort(ItemSort.Title)
        val all = FakeLibraryRepository().items(ItemQuery(FakeData.TV_LIBRARY, sort = ItemSort.Title))
        val entry = vm.state.value.letters!!.first { it.offset >= 5 && it.offset < all.total - 4 }
        vm.jumpToLetter(entry.letter)
        assertEquals(entry.offset, library.queries.last().offset)
        assertEquals(all.items.drop(entry.offset).take(2), vm.state.value.items)
        assertEquals(entry.offset, vm.state.value.offset)
        assertEquals(1, vm.state.value.jumpRevision)
        vm.loadMore()
        assertEquals(entry.offset + 2, library.queries.last().offset)
        assertEquals(all.items.drop(entry.offset).take(4), vm.state.value.items)
        while (vm.state.value.hasMore) vm.loadMore()
        assertEquals(all.items.drop(entry.offset), vm.state.value.items)
        vm.jumpToLetter(all.letters!!.first().letter)
        assertEquals(0, vm.state.value.offset)
    }

    @Test fun `changing filters after a jump starts at zero with a filtered title index`() = runTest {
        val vm = vm(pageSize = 2)
        vm.setSort(ItemSort.Title)
        vm.jumpToLetter(vm.state.value.letters!!.last().letter)
        vm.setGenre("Drama")
        assertEquals(0, library.queries.last().offset)
        assertEquals(0, vm.state.value.offset)
        val expected = FakeLibraryRepository().items(ItemQuery(FakeData.TV_LIBRARY, sort = ItemSort.Title, genre = "Drama", limit = 2))
        assertEquals(expected.letters, vm.state.value.letters)
        assertEquals(expected.items, vm.state.value.items)
    }

    @Test fun `older server without letters still pages and ignores a letter jump`() = runTest {
        val fake = FakeLibraryRepository()
        val old = TestLibrary(object : LibraryRepository by fake {
            override suspend fun items(query: ItemQuery) = fake.items(query).copy(letters = null)
        })
        val vm = LibraryViewModel(old, FakeAuthRepository(), LibraryType.Tv, 2, saved = SavedStateHandle())
        vm.setSort(ItemSort.Title)
        assertNull(vm.state.value.letters)
        val calls = old.queries.size
        vm.jumpToLetter("Z")
        assertEquals(calls, old.queries.size)
        vm.loadMore()
        assertEquals(4, vm.state.value.items.size)
        assertEquals(2, old.queries.last().offset)
    }

    @Test fun `failed jump preserves the loaded window and can be retried`() = runTest {
        val vm = vm(pageSize = 2)
        vm.setSort(ItemSort.Title)
        val before = vm.state.value.items
        val letter = vm.state.value.letters!!.last()
        library.failure = ApiException(500, "Try again later.")
        vm.jumpToLetter(letter.letter)
        assertEquals(before, vm.state.value.items)
        assertEquals(0, vm.state.value.offset)
        assertFalse(vm.state.value.loading)
        assertEquals("Try again later.", vm.state.value.message)
        library.failure = null
        vm.jumpToLetter(letter.letter)
        assertEquals(letter.offset, vm.state.value.offset)
        assertFalse(vm.state.value.hasMore)
    }

    @Test fun `jump cancels an in flight page without appending its stale results`() = runTest {
        val fake = FakeLibraryRepository()
        val gate = CompletableDeferred<Unit>()
        val delayed = object : LibraryRepository by fake {
            override suspend fun items(query: ItemQuery): ItemPage {
                if (query.offset == 2) gate.await()
                return fake.items(query)
            }
        }
        val vm = LibraryViewModel(delayed, FakeAuthRepository(), LibraryType.Tv, 2, saved = SavedStateHandle())
        vm.setSort(ItemSort.Title)
        vm.loadMore()
        assertTrue(vm.state.value.loadingMore)
        val letter = vm.state.value.letters!!.last()
        vm.jumpToLetter(letter.letter)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(letter.offset, vm.state.value.offset)
        assertEquals(1, vm.state.value.items.size)
        assertFalse(vm.state.value.loadingMore)
    }

    @Test fun `typing filters is debounced and keeps only the latest query`() = runTest {
        val vm = vm(pageSize = 2)
        vm.setText("H")
        vm.setText("Ha")
        vm.setText("Harbor")
        assertEquals(1, library.queries.size)
        advanceUntilIdle()
        assertEquals(2, library.queries.size)
        assertEquals("Harbor", library.queries.last().text)
        assertEquals(listOf("Harbor Lights"), vm.state.value.items.map { it.title })
    }

    @Test fun `genre counts never use partial page counts or issue extra library scans`() = runTest {
        val fake = FakeLibraryRepository()
        val old = TestLibrary(object : LibraryRepository by fake {
            override suspend fun items(query: ItemQuery) = fake.items(query).copy(genreCounts = null)
        })
        val vm = LibraryViewModel(old, FakeAuthRepository(), LibraryType.Tv, 2, saved = SavedStateHandle())
        assertTrue(vm.state.value.genreCounts.isEmpty())
        assertEquals(1, old.queries.size)
        vm.setGenre("Drama")
        val dramaTotal = vm.state.value.total
        assertEquals(dramaTotal, vm.state.value.genreCounts["Drama"])
        assertEquals(setOf("Drama"), vm.state.value.genreCounts.keys)
        assertEquals(2, old.queries.size)
        vm.setFilter(ItemFilter.FourK)
        assertEquals("old-server fallback follows the current quality filter", vm.state.value.total, vm.state.value.genreCounts["Drama"])
        vm.clearFilters()
        while (vm.state.value.hasMore) vm.loadMore()
        vm.state.value.genres.forEach { genre ->
            assertEquals(FakeData.shows.count { genre in it.genres }, vm.state.value.genreCounts[genre])
        }
        assertTrue("every query was a normal grid page", old.queries.all { it.limit == 2 })
    }

    @Test fun `server genre counts cover every genre before paging and exclude selected genre`() = runTest {
        val vm = vm(pageSize = 2)
        assertEquals(1, library.queries.size)
        assertEquals(FakeData.shows.count { "Drama" in it.genres }, vm.state.value.genreCounts["Drama"])
        vm.setGenre("Drama")
        vm.setFilter(ItemFilter.FourK)
        vm.setDecade(2020)
        vm.setText("ar")
        advanceUntilIdle()
        val expected = FakeData.shows.filter { it.quality == "4K" && it.year in 2020..2029 && it.title.contains("ar", ignoreCase = true) }
        vm.state.value.genres.forEach { genre -> assertEquals(expected.count { genre in it.genres }, vm.state.value.genreCounts[genre]) }
        assertTrue(library.queries.all { it.limit == 2 })
    }

    @Test fun `an authoritative empty genre aggregate is not replaced by client counts`() = runTest {
        val fake = FakeLibraryRepository()
        val emptyCounts = object : LibraryRepository by fake {
            override suspend fun items(query: ItemQuery) = fake.items(query).copy(genreCounts = emptyMap())
        }
        val vm = LibraryViewModel(emptyCounts, FakeAuthRepository(), LibraryType.Tv, saved = SavedStateHandle())
        assertTrue(vm.state.value.genreCountsFromServer)
        assertTrue(vm.state.value.genreCounts.isEmpty())
    }

    @Test fun `quiet and pull refresh preserve the chosen letter and explicit return resets it`() = runTest {
        val fake = FakeLibraryRepository()
        val events = MutableSharedFlow<Unit>()
        val observed = TestLibrary(object : LibraryRepository by fake { override val changes = events })
        val vm = LibraryViewModel(observed, FakeAuthRepository(), LibraryType.Tv, 2, saved = SavedStateHandle())
        vm.setSort(ItemSort.Title)
        val letter = vm.state.value.letters!!.last()
        vm.jumpToLetter(letter.letter)
        events.emit(Unit)
        assertEquals(letter.offset, observed.queries.last().offset)
        assertEquals(letter.offset, vm.state.value.offset)
        assertEquals(letter.letter, vm.state.value.anchorLetter)
        vm.refresh()
        assertEquals(letter.offset, observed.queries.last().offset)
        vm.returnToStart()
        assertEquals(0, vm.state.value.offset)
        assertNull(vm.state.value.anchorLetter)
    }

    @Test fun `quiet refresh follows a letter whose offset moved when the library changed`() = runTest {
        val fake = FakeLibraryRepository()
        val events = MutableSharedFlow<Unit>()
        var inserted = false
        val observed = TestLibrary(object : LibraryRepository by fake {
            override val changes = events
            override suspend fun items(query: ItemQuery): ItemPage {
                val all = fake.items(query.copy(offset = 0, limit = 1000))
                if (!inserted) return fake.items(query)
                val items = listOf(all.items.first().copy(id = 9999, title = "A Copperline")) + all.items
                return all.copy(items = items.drop(query.offset).take(query.limit), total = items.size, offset = query.offset,
                    letters = all.letters!!.map { it.copy(offset = if (it.offset == 0) 0 else it.offset + 1) })
            }
        })
        val vm = LibraryViewModel(observed, FakeAuthRepository(), LibraryType.Tv, 2, saved = SavedStateHandle())
        vm.setSort(ItemSort.Title)
        val letter = vm.state.value.letters!!.last()
        vm.jumpToLetter(letter.letter)
        inserted = true
        events.emit(Unit)
        assertEquals(listOf(letter.offset, letter.offset + 1), observed.queries.takeLast(2).map { it.offset })
        assertEquals(letter.offset + 1, vm.state.value.offset)
        assertEquals("Westward Signals", vm.state.value.items.first().title)
        vm.setFilter(ItemFilter.FourK)
        assertEquals(0, vm.state.value.offset)
        assertNull(vm.state.value.anchorLetter)
    }

    @Test fun `a library change or a pull to refresh keeps every loaded page`() = runTest {
        val fake = FakeLibraryRepository()
        val events = MutableSharedFlow<Unit>()
        val observed = TestLibrary(object : LibraryRepository by fake { override val changes = events })
        val vm = LibraryViewModel(observed, FakeAuthRepository(), LibraryType.Tv, 5, saved = SavedStateHandle())
        vm.loadMore()
        vm.loadMore()
        val loaded = vm.state.value.items
        assertEquals(15, loaded.size)

        // Marking one watched changes the library on the server, which says so.
        events.emit(Unit)
        assertEquals(loaded, vm.state.value.items)
        assertEquals(0, vm.state.value.offset)
        assertEquals(15, observed.queries.last().limit)
        assertTrue(vm.state.value.hasMore)

        vm.refresh()
        assertEquals(loaded, vm.state.value.items)
        vm.loadMore()
        assertEquals(FakeData.shows, vm.state.value.items)
    }

    @Test fun `the choices and the text outlive the process`() = runTest {
        val saved = SavedStateHandle()
        val vm = LibraryViewModel(library, FakeAuthRepository(), LibraryType.Tv, saved = saved)
        vm.setSort(ItemSort.Title)
        vm.setFilter(ItemFilter.Unwatched)
        vm.setGenre("Drama")
        vm.setDecade(2020)
        vm.setText("harbor")
        advanceUntilIdle()

        val restored = LibraryViewModel(library, FakeAuthRepository(), LibraryType.Tv, saved = saved)
        advanceUntilIdle()
        val s = restored.state.value
        assertEquals(ItemSort.Title, s.sort)
        assertEquals(ItemFilter.Unwatched, s.filter)
        assertEquals("Drama", s.genre)
        assertEquals(2020, s.decade)
        assertEquals("harbor", s.text)
        val q = library.queries.last()
        assertEquals(ItemSort.Title, q.sort)
        assertEquals("harbor", q.text)
    }
}
