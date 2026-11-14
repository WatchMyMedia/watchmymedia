package media.watchmy.client.presentation.library

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
import media.watchmy.client.model.LibraryChange
import media.watchmy.client.model.LibraryRepository
import media.watchmy.client.model.LibraryType
import media.watchmy.client.presentation.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The fake library with queries recorded, failures on demand, and its changes driven by the test. */
class GridLibrary(private val fake: LibraryRepository = FakeLibraryRepository()) : LibraryRepository by fake {
    /** Thrown by every call while set. */
    var failure: Exception? = null

    /** While set, marks wait for it, so a test can look at the grid before the server answers. */
    var markGate: CompletableDeferred<Unit>? = null
    val watchCalls = mutableListOf<String>()

    /** Grid pages asked for (the TV's one-item count queries aren't). */
    val queries = mutableListOf<ItemQuery>()
    var librariesOverride: List<Library>? = null
    val events = MutableSharedFlow<LibraryChange>()
    override val libraryChanges = events

    private fun maybeFail() {
        failure?.let { throw it }
    }

    override suspend fun home() = maybeFail().let { fake.home() }

    override suspend fun libraries(): List<Library> = maybeFail().let { librariesOverride ?: fake.libraries() }

    override suspend fun items(query: ItemQuery): ItemPage {
        maybeFail()
        if (query.limit > 1) queries += query
        return fake.items(query)
    }

    override suspend fun setWatched(kind: ContinueItem.Kind, id: Long, watched: Boolean) {
        markGate?.await()
        maybeFail()
        watchCalls += "$kind $id $watched"
    }
}

/** One Library model for the phone, the tablet and the TV (PLAN-ANDROID-REVIEW.md §5.1). */
class LibraryViewModelTest {
    @get:Rule val main = MainDispatcherRule(UnconfinedTestDispatcher())

    private val library = GridLibrary()

    private fun vm(type: LibraryType = LibraryType.Tv, pageSize: Int = 60, columns: Int = 1, countsLine: Boolean = false, from: LibraryRepository = library) =
        LibraryViewModel(from, type, SavedStateHandle(), pageSize, columns, countsLine)

    @Test fun `loads the TV library sorted by date added`() = runTest(main.dispatcher) {
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

    @Test fun `the header counts the whole library unless a filter is on`() = runTest(main.dispatcher) {
        val vm = vm()
        val all = vm.state.value
        assertEquals("16 shows · 312 episodes", all.countLine(LibraryType.Tv))
        // Servers without counts: what the grid holds.
        assertEquals("16 shows", all.copy(summary = null).countLine(LibraryType.Tv))
        vm.setFilter(ItemFilter.FourK)
        assertEquals("7 shows", vm.state.value.countLine(LibraryType.Tv))
        assertEquals("10 movies", vm(LibraryType.Movie).state.value.countLine(LibraryType.Movie))
    }

    @Test fun `the TV's line under the title, from the counts or from Home's totals`() = runTest(main.dispatcher) {
        assertEquals("16 shows · 312 episodes · 12 continuing", vm(countsLine = true).state.value.countsLine)
        assertNull("only asked for", vm().state.value.countsLine)
        val fake = FakeLibraryRepository()
        val old = object : LibraryRepository by fake {
            override suspend fun items(query: ItemQuery) = fake.items(query).copy(summary = null)
        }
        assertEquals("142 shows · 3,418 episodes · 12 continuing", vm(countsLine = true, from = old).state.value.countsLine)
        assertEquals("214 movies · 9 unwatched", vm(LibraryType.Movie, countsLine = true, from = old).state.value.countsLine)
        assertEquals("10 movies · 9 unwatched", vm(LibraryType.Movie, countsLine = true).state.value.countsLine)
    }

    @Test fun `movies offer movie sorts and filters, and their cards read year and runtime`() = runTest(main.dispatcher) {
        val vm = vm(LibraryType.Movie)
        assertTrue(ItemSort.Runtime in vm.sorts && ItemSort.Released in vm.sorts && ItemSort.Aired !in vm.sorts)
        assertFalse(ItemFilter.Continuing in vm.filters)
        assertEquals("2026 · 1 h 52 min", vm.state.value.items.first().gridSubtitle())
    }

    @Test fun `filters, genre and decade go into the query and narrow the grid`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.setFilter(ItemFilter.FourK)
        assertTrue(vm.state.value.items.all { it.quality == "4K" })
        assertTrue(vm.state.value.filtered)

        vm.setGenre("Sci-Fi")
        assertEquals(listOf("The Quiet Meridian", "Orbital Decay", "Kestrel Station"), vm.state.value.items.map { it.title })
        vm.setGenre("Sci-Fi")
        assertNull("picking the genre again clears it", vm.state.value.choices.genre)

        vm.setDecade(2010)
        assertEquals(2010, library.queries.last().decade)
        assertTrue(vm.state.value.items.all { it.year in 2010..2019 })
        assertTrue(vm.state.value.choices.narrowed)

        vm.clearFilters()
        assertFalse(vm.state.value.filtered)
        assertEquals(16, vm.state.value.items.size)
    }

    @Test fun `a mark shows on the card at once, and goes back if the server refuses it`() = runTest(main.dispatcher) {
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

    @Test fun `marking watched updates the card in place`() = runTest(main.dispatcher) {
        val vm = vm(LibraryType.Movie)
        val item = vm.state.value.items.first { !it.watched }
        vm.setWatched(item, true)
        assertEquals(listOf("Movie ${item.id} true"), library.watchCalls)
        assertTrue(vm.state.value.items.first { it.id == item.id }.watched)
        assertEquals("Marked as watched", vm.state.value.message)
    }

    @Test fun `sorting asks again`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.setSort(ItemSort.Title)
        assertEquals(ItemSort.Title, library.queries.last().sort)
        assertEquals("Copperline", vm.state.value.items.first().title)
    }

    @Test fun `pages load as the grid scrolls`() = runTest(main.dispatcher) {
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

    @Test fun `the TV keeps a page loaded ahead of focus`() = runTest(main.dispatcher) {
        val vm = vm(pageSize = 5)
        // The first page on screen fetches the second.
        vm.onVisible(1)
        assertEquals(10, vm.state.value.items.size)
        assertEquals(5, library.queries.last().offset)
        // Still on the first page: the second is already here.
        val calls = library.queries.size
        vm.onVisible(4)
        assertEquals(calls, library.queries.size)
        // Into the second page: the third.
        vm.onVisible(5)
        assertEquals(15, vm.state.value.items.size)
        assertEquals(FakeData.shows.take(15).map { it.id }, vm.state.value.items.map { it.id })
    }

    @Test fun `a failed page offers Try again without losing the grid`() = runTest(main.dispatcher) {
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

    @Test fun `a failed first load is an error with Try again`() = runTest(main.dispatcher) {
        library.failure = ApiException(null, "Couldn't reach the server.")
        val vm = vm()
        assertEquals("Couldn't reach the server.", vm.state.value.error)
        library.failure = null
        vm.retry()
        assertNull(vm.state.value.error)
        assertEquals(16, vm.state.value.items.size)
    }

    @Test fun `no library of the type is its own empty state`() = runTest(main.dispatcher) {
        library.librariesOverride = listOf(Library(1, "Movies", LibraryType.Movie))
        val vm = vm(LibraryType.Tv)
        assertTrue(vm.state.value.noLibrary)
        assertNull(vm.state.value.error)
        assertTrue(vm.state.value.items.isEmpty())
    }

    @Test fun `title jump reaches a letter beyond the loaded page and paging continues at that offset`() = runTest(main.dispatcher) {
        val vm = vm(pageSize = 2)
        vm.setSort(ItemSort.Title)
        val all = FakeLibraryRepository().items(ItemQuery(FakeData.TV_LIBRARY, sort = ItemSort.Title))
        val entry = vm.state.value.letters!!.first { it.offset >= 5 && it.offset < all.total - 4 }
        vm.jumpToLetter(entry.letter)
        assertEquals(entry.offset, library.queries.last().offset)
        assertEquals(all.items.drop(entry.offset).take(2), vm.state.value.items)
        assertEquals(entry.offset, vm.state.value.offset)
        assertEquals(LibraryViewModel.Jump(1, entry.letter, 0), vm.state.value.jump)
        vm.loadMore()
        assertEquals(entry.offset + 2, library.queries.last().offset)
        assertEquals(all.items.drop(entry.offset).take(4), vm.state.value.items)
        while (vm.state.value.hasMore) vm.loadMore()
        assertEquals(all.items.drop(entry.offset), vm.state.value.items)
        vm.jumpToLetter(all.letters!!.first().letter)
        assertEquals(0, vm.state.value.offset)
    }

    @Test fun `a letter already loaded is jumped to in place`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.setSort(ItemSort.Title)
        val calls = library.queries.size
        val entry = vm.state.value.letters!!.first { it.offset > 3 }
        vm.jumpToLetter(entry.letter)
        assertEquals(calls, library.queries.size)
        assertEquals(LibraryViewModel.Jump(1, entry.letter, entry.offset), vm.state.value.jump)
    }

    @Test fun `the TV's jump starts its window on a row, and the rows before it load as focus goes up`() = runTest(main.dispatcher) {
        val vm = vm(pageSize = 4, columns = 4)
        vm.setSort(ItemSort.Title)
        val all = FakeLibraryRepository().items(ItemQuery(FakeData.TV_LIBRARY, sort = ItemSort.Title))
        val entry = all.letters!!.first { it.offset >= 9 && it.offset % 4 != 0 }
        vm.jumpToLetter(entry.letter)
        val start = entry.offset / 4 * 4
        assertEquals(start, vm.state.value.offset)
        assertEquals(entry.offset - start, vm.state.value.jump!!.index)
        assertEquals(all.items[entry.offset], vm.state.value.items[vm.state.value.jump!!.index])

        // Focus near the window's start: the page before it, prepended (and, this near the end, the next one too).
        vm.onVisible(0)
        assertEquals(start - 4, vm.state.value.offset)
        assertEquals(all.items.subList(start - 4, start + 4), vm.state.value.items.take(8))
        assertTrue(library.queries.any { it.offset == start - 4 && it.limit == 4 })
    }

    @Test fun `the TV strip previews letters, and Back puts the grid back as it was`() = runTest(main.dispatcher) {
        val vm = vm(pageSize = 2)
        vm.setSort(ItemSort.Title)
        val before = vm.state.value
        // Two letters a window apart, beyond the first one.
        val first = before.letters!!.first { it.offset >= 4 }
        val second = before.letters!!.last { it.offset >= first.offset + 2 }
        val letters = listOf(first, second)
        vm.jumpToLetter(letters[0].letter, preview = true)
        vm.jumpToLetter(letters[1].letter, preview = true)
        assertEquals(letters[1].offset, vm.state.value.offset)
        vm.cancelJump()
        assertEquals(before.items, vm.state.value.items)
        assertEquals(before.offset, vm.state.value.offset)
        assertEquals(before.end, vm.state.value.end)

        vm.jumpToLetter(letters[1].letter, preview = true)
        vm.confirmJump()
        vm.cancelJump()
        assertEquals("a confirmed jump stays", letters[1].offset, vm.state.value.offset)
    }

    @Test fun `changing filters after a jump starts at zero with a filtered title index`() = runTest(main.dispatcher) {
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

    @Test fun `older server without letters still pages and ignores a letter jump`() = runTest(main.dispatcher) {
        val fake = FakeLibraryRepository()
        val old = GridLibrary(object : LibraryRepository by fake {
            override suspend fun items(query: ItemQuery) = fake.items(query).copy(letters = null)
        })
        val vm = vm(pageSize = 2, from = old)
        vm.setSort(ItemSort.Title)
        assertNull(vm.state.value.letters)
        val calls = old.queries.size
        vm.jumpToLetter("Z")
        assertEquals(calls, old.queries.size)
        vm.loadMore()
        assertEquals(4, vm.state.value.items.size)
        assertEquals(2, old.queries.last().offset)
    }

    @Test fun `failed jump preserves the loaded window and can be retried`() = runTest(main.dispatcher) {
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

    @Test fun `jump cancels an in flight page without appending its stale results`() = runTest(main.dispatcher) {
        val fake = FakeLibraryRepository()
        val gate = CompletableDeferred<Unit>()
        val delayed = object : LibraryRepository by fake {
            override suspend fun items(query: ItemQuery): ItemPage {
                if (query.offset == 2) gate.await()
                return fake.items(query)
            }
        }
        val vm = vm(pageSize = 2, from = delayed)
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

    @Test fun `typing filters is debounced and keeps only the latest query`() = runTest(main.dispatcher) {
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

    @Test fun `genre counts never use partial page counts or issue extra library scans`() = runTest(main.dispatcher) {
        val fake = FakeLibraryRepository()
        val old = GridLibrary(object : LibraryRepository by fake {
            override suspend fun items(query: ItemQuery) = fake.items(query).copy(genreCounts = null)
        })
        val vm = vm(pageSize = 2, from = old)
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

    @Test fun `server genre counts cover every genre before paging and exclude selected genre`() = runTest(main.dispatcher) {
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

    @Test fun `an authoritative empty genre aggregate is not replaced by client counts`() = runTest(main.dispatcher) {
        val fake = FakeLibraryRepository()
        val emptyCounts = object : LibraryRepository by fake {
            override suspend fun items(query: ItemQuery) = fake.items(query).copy(genreCounts = emptyMap())
        }
        val vm = vm(from = emptyCounts)
        assertTrue(vm.state.value.genreCountsFromServer)
        assertTrue(vm.state.value.genreCounts.isEmpty())
    }

    @Test fun `quiet and pull refresh preserve the chosen letter and explicit return resets it`() = runTest(main.dispatcher) {
        val vm = vm(pageSize = 2)
        vm.setSort(ItemSort.Title)
        val letter = vm.state.value.letters!!.last()
        vm.jumpToLetter(letter.letter)
        library.events.emit(LibraryChange.Everything)
        assertEquals(letter.offset, library.queries.last().offset)
        assertEquals(letter.offset, vm.state.value.offset)
        assertEquals(letter.letter, vm.state.value.anchorLetter)
        vm.refresh()
        assertEquals(letter.offset, library.queries.last().offset)
        vm.returnToStart()
        assertEquals(0, vm.state.value.offset)
        assertNull(vm.state.value.anchorLetter)
    }

    @Test fun `quiet refresh follows a letter whose offset moved when the library changed`() = runTest(main.dispatcher) {
        val fake = FakeLibraryRepository()
        var inserted = false
        val observed = GridLibrary(object : LibraryRepository by fake {
            override suspend fun items(query: ItemQuery): ItemPage {
                val all = fake.items(query.copy(offset = 0, limit = 1000))
                if (!inserted) return fake.items(query)
                val items = listOf(all.items.first().copy(id = 9999, title = "A Copperline")) + all.items
                return all.copy(items = items.drop(query.offset).take(query.limit), total = items.size, offset = query.offset,
                    letters = all.letters!!.map { it.copy(offset = if (it.offset == 0) 0 else it.offset + 1) })
            }
        })
        val vm = vm(pageSize = 2, from = observed)
        vm.setSort(ItemSort.Title)
        val letter = vm.state.value.letters!!.last()
        vm.jumpToLetter(letter.letter)
        inserted = true
        observed.events.emit(LibraryChange(libraries = setOf(FakeData.TV_LIBRARY)))
        assertEquals(listOf(letter.offset, letter.offset + 1), observed.queries.takeLast(2).map { it.offset })
        assertEquals(letter.offset + 1, vm.state.value.offset)
        assertEquals("Westward Signals", vm.state.value.items.first().title)
        vm.setFilter(ItemFilter.FourK)
        assertEquals(0, vm.state.value.offset)
        assertNull(vm.state.value.anchorLetter)
    }

    @Test fun `a library change or a pull to refresh keeps every loaded page`() = runTest(main.dispatcher) {
        val vm = vm(pageSize = 5)
        vm.loadMore()
        vm.loadMore()
        val loaded = vm.state.value.items
        assertEquals(15, loaded.size)

        // Marking one watched changes the library on the server, which says so.
        library.events.emit(LibraryChange(episodes = setOf(1)))
        assertEquals(loaded, vm.state.value.items)
        assertEquals(0, vm.state.value.offset)
        assertEquals(15, library.queries.last().limit)
        assertTrue(vm.state.value.hasMore)

        vm.refresh()
        assertEquals(loaded, vm.state.value.items)
        vm.loadMore()
        assertEquals(FakeData.shows, vm.state.value.items)
    }

    @Test fun `another library's scan is ignored, and a hidden grid reloads once when shown`() = runTest(main.dispatcher) {
        val vm = vm()
        val calls = library.queries.size
        library.events.emit(LibraryChange(libraries = setOf(FakeData.TV_LIBRARY + 1000)))
        assertEquals(calls, library.queries.size)

        vm.setShown(false)
        library.events.emit(LibraryChange(movies = setOf(1)))
        library.events.emit(LibraryChange(libraries = setOf(FakeData.TV_LIBRARY)))
        assertEquals(calls, library.queries.size)
        vm.setShown(true)
        assertEquals(calls + 1, library.queries.size)
    }

    @Test fun `a quiet refresh that answers late doesn't land over a newer load`() = runTest(main.dispatcher) {
        val gate = CompletableDeferred<Unit>()
        var holdNext = false
        val fake = FakeLibraryRepository()
        val held = GridLibrary(object : LibraryRepository by fake {
            override suspend fun items(query: ItemQuery): ItemPage {
                if (holdNext && query.limit > 1) {
                    holdNext = false
                    gate.await()
                }
                return fake.items(query)
            }
        })
        val vm = vm(pageSize = 5, from = held)
        holdNext = true
        held.events.emit(LibraryChange.Everything)
        vm.setSort(ItemSort.Title)
        val titles = vm.state.value.items.map { it.title }
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(titles, vm.state.value.items.map { it.title })
        assertEquals(ItemSort.Title, vm.state.value.choices.sort)
    }

    @Test fun `the choices and the text outlive the process`() = runTest(main.dispatcher) {
        val saved = SavedStateHandle()
        val vm = LibraryViewModel(library, LibraryType.Tv, saved)
        vm.setSort(ItemSort.Title)
        vm.setFilter(ItemFilter.Unwatched)
        vm.setGenre("Drama")
        vm.setDecade(2020)
        vm.setText("harbor")
        advanceUntilIdle()

        val restored = LibraryViewModel(library, LibraryType.Tv, saved)
        advanceUntilIdle()
        assertEquals(LibraryViewModel.Choices(ItemSort.Title, ItemFilter.Unwatched, "Drama", 2020, "harbor"), restored.state.value.choices)
        val q = library.queries.last()
        assertEquals(ItemSort.Title, q.sort)
        assertEquals("harbor", q.text)
    }
}
