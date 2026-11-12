package media.watchmy.client.presentation.rows

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import media.watchmy.client.fake.FakeAppServices
import media.watchmy.client.fake.FakeData
import media.watchmy.client.fake.FakeRowsData
import media.watchmy.client.fake.FakeRowsRepository
import media.watchmy.client.fake.LivingRoom
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.MediaType
import media.watchmy.client.model.ROWS_API_LEVEL
import media.watchmy.client.model.RequestableState
import media.watchmy.client.model.RowIds
import media.watchmy.client.model.RowLayout
import media.watchmy.client.model.RowPlacement
import media.watchmy.client.model.RowScreens
import media.watchmy.client.model.RowStyle
import media.watchmy.client.model.RowsChange
import media.watchmy.client.model.RowsRepository
import media.watchmy.client.model.continueRow
import media.watchmy.client.presentation.Load
import media.watchmy.client.presentation.MainDispatcherRule
import media.watchmy.client.presentation.home.HomeViewModel
import media.watchmy.client.presentation.value
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Home, the library rows, the row editor and the list page on the fakes (PLAN-ROWS.md §8.2). */
class RowsViewModelsTest {
    @get:Rule val main = MainDispatcherRule()

    private val services = FakeAppServices()

    private fun at(level: Int) = runBlocking { services.servers.select(LivingRoom.copy(apiLevel = level)) }

    @Test
    fun `Home comes in the person's order from level 22`() = runTest(main.dispatcher) {
        val vm = HomeViewModel(services.library, services.auth, services.rows)
        advanceUntilIdle()
        val home = vm.state.value.home!!
        val rows = home.rows!!
        assertEquals(RowIds.CONTINUE, rows.first().id)
        assertTrue("a list row", rows.any { it.id == RowIds.list(FakeRowsData.SCI_FI) && it.subtitle?.endsWith("From TMDB") == true })
        assertFalse("the import list is hidden: Add a row offers it", rows.any { it.id == RowIds.list(FakeRowsData.WATCHLIST) })
        assertTrue(home.continueWatching.isEmpty())
        assertEquals(FakeData.continueWatching, home.continueRow)
        assertEquals("Harbor Lights", home.hero?.title)
    }

    @Test
    fun `below level 22 Home keeps today's fixed rows`() = runTest(main.dispatcher) {
        at(ROWS_API_LEVEL - 1)
        val vm = HomeViewModel(services.library, services.auth, services.rows)
        advanceUntilIdle()
        val home = vm.state.value.home!!
        assertNull(home.rows)
        assertEquals(5, home.continueWatching.size)
        assertTrue(home.recentlyAddedMovies.isNotEmpty())
    }

    @Test
    fun `Home is read again when its layout or a list changes, not another screen's`() = runTest(main.dispatcher) {
        var calls = 0
        val changes = MutableSharedFlow<RowsChange>()
        val rows = object : RowsRepository by services.rows {
            override val changes = changes
        }
        val library = object : media.watchmy.client.model.LibraryRepository by services.library {
            override suspend fun arrangedHome() = services.library.arrangedHome().also { calls++ }
        }
        HomeViewModel(library, services.auth, rows)
        advanceUntilIdle()
        assertEquals(1, calls)
        changes.emit(RowsChange.Layout(RowScreens.library(FakeData.TV_LIBRARY)))
        advanceUntilIdle()
        assertEquals(1, calls)
        changes.emit(RowsChange.Layout(RowScreens.HOME))
        advanceUntilIdle()
        assertEquals(2, calls)
        changes.emit(RowsChange.Lists(FakeRowsData.SCI_FI))
        advanceUntilIdle()
        assertEquals(3, calls)
    }

    @Test
    fun `the editor's changes wait for Done, which saves them once, and Home follows`() = runTest(main.dispatcher) {
        val saves = mutableListOf<List<RowPlacement>>()
        val rows = object : RowsRepository by services.rows {
            override suspend fun saveLayout(screen: String, rows: List<RowPlacement>): RowLayout = services.rows.saveLayout(screen, rows).also { saves += rows }
        }
        val editor = RowLayoutViewModel(rows, RowScreens.HOME)
        val events = mutableListOf<RowLayoutViewModel.Event>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { editor.events.collect { events += it } }
        advanceUntilIdle()
        assertEquals(RowIds.CONTINUE, editor.state.value.entries.first().id)
        assertEquals("the hidden import list is offered", listOf(RowIds.list(FakeRowsData.WATCHLIST)), editor.state.value.addGroups.first { it.name == "Your lists" }.rows.map { it.id })
        assertTrue("its lines come from the lists", editor.state.value.lists.isNotEmpty())

        editor.moveDown(RowIds.CONTINUE)
        editor.hide(RowIds.RECENTLY_RELEASED)
        editor.add(RowIds.list(FakeRowsData.WATCHLIST))
        advanceUntilIdle()
        assertEquals("Sam’s watchlist is back on Home.", editor.state.value.message)
        assertTrue(editor.state.value.dirty)
        assertEquals("nothing saved before Done", 0, saves.size)

        editor.done()
        advanceUntilIdle()
        assertEquals(1, saves.size)
        assertEquals(listOf(RowLayoutViewModel.Event.Done), events)
        val saved = editor.state.value
        assertFalse(saved.dirty)
        assertTrue(saved.customized)
        assertEquals(listOf(RowIds.LIVE, RowIds.CONTINUE), saved.entries.take(2).map { it.id })
        assertTrue(saved.entries.first { it.id == RowIds.RECENTLY_RELEASED }.hidden)

        val home = HomeViewModel(services.library, services.auth, services.rows)
        advanceUntilIdle()
        val drawn = home.state.value.home!!.rows!!.map { it.id }
        assertEquals(RowIds.LIVE, drawn.first())
        assertTrue(RowIds.list(FakeRowsData.WATCHLIST) in drawn)
        assertFalse(RowIds.RECENTLY_RELEASED in drawn)

        editor.reset()
        advanceUntilIdle()
        assertFalse(editor.state.value.customized)
        assertEquals("Home is back to the rows everyone starts with.", editor.state.value.message)
        assertEquals(RowIds.CONTINUE, editor.state.value.entries.first().id)
    }

    @Test
    fun `Done with nothing changed saves nothing`() = runTest(main.dispatcher) {
        var saves = 0
        val rows = object : RowsRepository by services.rows {
            override suspend fun saveLayout(screen: String, rows: List<RowPlacement>): RowLayout = services.rows.saveLayout(screen, rows).also { saves++ }
        }
        val editor = RowLayoutViewModel(rows, RowScreens.HOME)
        val events = mutableListOf<RowLayoutViewModel.Event>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { editor.events.collect { events += it } }
        advanceUntilIdle()
        editor.moveDown(RowIds.CONTINUE)
        editor.moveUp(RowIds.CONTINUE)
        editor.done()
        advanceUntilIdle()
        assertEquals(0, saves)
        assertEquals(listOf(RowLayoutViewModel.Event.Done), events)
    }

    @Test
    fun `with autosave a drag is saved once, when it ends`() = runTest(main.dispatcher) {
        val saves = mutableListOf<List<RowPlacement>>()
        val rows = object : RowsRepository by services.rows {
            override suspend fun saveLayout(screen: String, rows: List<RowPlacement>): RowLayout = services.rows.saveLayout(screen, rows).also { saves += rows }
        }
        val editor = RowLayoutViewModel(rows, RowScreens.HOME, autosave = true)
        advanceUntilIdle()
        editor.drag(0, 1)
        editor.drag(1, 2)
        advanceUntilIdle()
        assertEquals(0, saves.size)
        editor.dropped()
        advanceUntilIdle()
        assertEquals(1, saves.size)
        assertEquals(RowIds.CONTINUE, saves.single()[2].id)
    }

    @Test
    fun `with autosave, changes made while a save is on its way go next, and the last order wins`() = runTest(main.dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val saves = mutableListOf<List<String>>()
        val rows = object : RowsRepository by services.rows {
            override suspend fun saveLayout(screen: String, rows: List<RowPlacement>): RowLayout {
                if (saves.isEmpty()) gate.await()
                saves += rows.map { it.id }
                return services.rows.saveLayout(screen, rows)
            }
        }
        val editor = RowLayoutViewModel(rows, RowScreens.HOME, autosave = true)
        advanceUntilIdle()
        editor.moveDown(RowIds.CONTINUE)
        advanceUntilIdle()
        editor.moveDown(RowIds.CONTINUE)
        editor.moveDown(RowIds.CONTINUE)
        assertTrue(editor.state.value.saving)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals("the first, then the latest once", 2, saves.size)
        assertEquals(3, saves.last().indexOf(RowIds.CONTINUE))
        assertEquals(3, editor.state.value.entries.indexOfFirst { it.id == RowIds.CONTINUE })
    }

    @Test
    fun `with autosave a save the server refuses puts back what it has, and Done keeps the changes to try again`() = runTest(main.dispatcher) {
        val rows = object : RowsRepository by services.rows {
            override suspend fun saveLayout(screen: String, rows: List<RowPlacement>): RowLayout = throw ApiException(422, "No row called gone on this screen.")
        }
        val tv = RowLayoutViewModel(rows, RowScreens.HOME, autosave = true)
        advanceUntilIdle()
        val before = tv.state.value.entries
        tv.moveDown(RowIds.CONTINUE)
        advanceUntilIdle()
        assertEquals(before, tv.state.value.entries)
        assertEquals("No row called gone on this screen.", tv.state.value.message)

        val phone = RowLayoutViewModel(rows, RowScreens.HOME)
        advanceUntilIdle()
        phone.moveDown(RowIds.CONTINUE)
        phone.done()
        advanceUntilIdle()
        assertTrue(phone.state.value.dirty)
        assertEquals("No row called gone on this screen.", phone.state.value.message)
    }

    @Test
    fun `a library's rows above its grid, only from level 22`() = runTest(main.dispatcher) {
        val vm = LibraryRowsViewModel(services.library, services.rows)
        vm.show(FakeData.MOVIE_LIBRARY)
        advanceUntilIdle()
        assertEquals(listOf(RowIds.RECENTLY_ADDED, RowIds.list(FakeRowsData.UNWATCHED_4K), RowIds.RECENTLY_RELEASED), vm.state.value.rows.map { it.id })
        assertTrue(vm.state.value.arrangeable)

        at(ROWS_API_LEVEL - 1)
        val old = LibraryRowsViewModel(services.library, services.rows)
        old.show(FakeData.MOVIE_LIBRARY)
        advanceUntilIdle()
        assertTrue(old.state.value.rows.isEmpty())
        assertFalse(old.state.value.arrangeable)
    }

    @Test
    fun `the list page has the library's titles, the missing ones with Request, and Not found`() = runTest(main.dispatcher) {
        val vm = MediaListViewModel(services.rows, services.requests, services.library, FakeRowsData.WATCHLIST)
        advanceUntilIdle()
        val page = vm.state.value.page.value!!
        assertEquals("Sam’s watchlist", page.list.name)
        assertEquals(listOf(1, 2, 3, 4, 5, 6), page.entries.map { it.rank })
        assertNotNull(page.entries[0].item)
        val missing = page.entries[2]
        assertEquals(MediaType.Tv, missing.mediaType)
        assertEquals("Saltharbor is downloading in the fake Sonarr", RequestableState.Downloading, missing.requestState)
        assertEquals("Not found on TMDB", page.entries[3].missingLine)
        assertFalse(page.entries[3].found)
        assertTrue(vm.state.value.options.canRequest)

        val festival = MediaListViewModel(services.rows, services.requests, services.library, FakeRowsData.FESTIVAL)
        advanceUntilIdle()
        val harborSaints = festival.state.value.page.value!!.entries.first { it.title == "Harbor Saints" }
        assertTrue(harborSaints.requestable)
        services.requests.request(MediaType.Movie, harborSaints.tmdbId!!)
        festival.requested("Requested.")
        advanceUntilIdle()
        assertEquals(RequestableState.Requested, festival.state.value.page.value!!.entries.first { it.title == "Harbor Saints" }.requestState)
        assertEquals("Requested.", festival.state.value.message)
    }

    @Test
    fun `refresh and delete from the list page`() = runTest(main.dispatcher) {
        val vm = MediaListViewModel(services.rows, services.requests, services.library, FakeRowsData.FESTIVAL)
        advanceUntilIdle()
        assertEquals("MDBList refused the key", vm.state.value.list?.error)
        vm.refreshList()
        advanceUntilIdle()
        assertNull(vm.state.value.list?.error)

        val events = mutableListOf<MediaListViewModel.Event>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        vm.delete()
        advanceUntilIdle()
        assertEquals(listOf(MediaListViewModel.Event.Deleted), events)
        assertFalse(services.rows.lists().any { it.id == FakeRowsData.FESTIVAL })
    }

    @Test
    fun `a list that is gone says so`() = runTest(main.dispatcher) {
        val vm = MediaListViewModel(FakeRowsRepository(), services.requests, services.library, 99)
        advanceUntilIdle()
        assertTrue(vm.state.value.page is Load.Failed)
    }

    @Test
    fun `unknown styles never reach the screens`() {
        assertNull(RowStyle.of("carousel"))
    }
}
