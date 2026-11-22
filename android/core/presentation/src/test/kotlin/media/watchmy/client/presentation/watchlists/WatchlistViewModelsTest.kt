package media.watchmy.client.presentation.watchlists

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import media.watchmy.client.fake.FakeData
import media.watchmy.client.fake.FakeRowsData
import media.watchmy.client.fake.FakeRowsRepository
import media.watchmy.client.fake.FakeWatchlistRepository
import media.watchmy.client.model.ListShown
import media.watchmy.client.model.MediaType
import media.watchmy.client.model.WATCHLISTS_API_LEVEL
import media.watchmy.client.model.WatchlistKey
import media.watchmy.client.model.WatchlistTitle
import media.watchmy.client.model.watchlistTitle
import media.watchmy.client.presentation.MainDispatcherRule
import media.watchmy.client.presentation.rows.MediaListViewModel
import media.watchmy.client.fake.FakeLibraryRepository
import media.watchmy.client.fake.FakeRequestRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Saving titles and a watchlist's page, on the fakes, where the person has
 * Watchlist, Halloween (which keeps watched titles) and With the kids.
 */
class WatchlistViewModelsTest {
    @get:Rule val main = MainDispatcherRule()

    private val data = FakeRowsData(watchlistsOn = { true })
    /** The server's answer takes this long, in the test's own time, so a test can look while a call is on its way. */
    private var latency = 0L
    private val fake = FakeWatchlistRepository(data, level = { WATCHLISTS_API_LEVEL }, scope = CoroutineScope(main.dispatcher), latencyMs = { latency })

    /** Dead Reckoning Bay is on With the kids only; Signal to Noise on Watchlist only. */
    private val bay = title("Dead Reckoning Bay")
    private val signal = title("Signal to Noise")

    private fun title(name: String): WatchlistTitle = FakeData.movies.first { it.title == name }.copy(tmdbId = FakeData.tmdbId(FakeData.movies.first { it.title == name }.id)).watchlistTitle()!!

    /** The person keeps only [keep], so a press toggles. */
    private fun onlyOne(keep: Long = FakeRowsData.FIRST_WATCHLIST) = listOf(FakeRowsData.FIRST_WATCHLIST, FakeRowsData.HALLOWEEN, FakeRowsData.WITH_THE_KIDS).filter { it != keep }.forEach(data::delete)

    private fun saver(messages: Boolean = true) = WatchlistViewModel(fake, messages)

    @Test
    fun `with one watchlist a press saves at once, says so, and Undo takes it off`() = runTest(main.dispatcher) {
        onlyOne()
        val vm = saver()
        advanceUntilIdle()
        latency = 1_000
        vm.press(bay, on = false)
        runCurrent()
        assertEquals("before the server answers", listOf(FakeRowsData.FIRST_WATCHLIST), fake.held.value[bay.key])
        advanceUntilIdle()
        assertEquals(listOf(FakeRowsData.FIRST_WATCHLIST), fake.holdingIds(bay.key))
        assertEquals(WatchlistMessage("Saved to Watchlist", undo = true), vm.state.value.message)
        vm.undo()
        advanceUntilIdle()
        assertEquals(emptyList<Long>(), fake.holdingIds(bay.key))
        assertNull(vm.state.value.message)
    }

    @Test
    fun `a removal waits for its message to go, and Undo sends nothing`() = runTest(main.dispatcher) {
        onlyOne()
        val vm = saver()
        advanceUntilIdle()
        val calls = fake.calls
        vm.press(signal, on = true)
        advanceUntilIdle()
        assertEquals("shown off at once", emptyList<Long>(), fake.held.value[signal.key])
        assertEquals(WatchlistMessage("Removed from Watchlist", undo = true), vm.state.value.message)
        assertEquals("nothing sent while the message shows", calls, fake.calls)
        vm.undo()
        advanceUntilIdle()
        assertEquals(calls, fake.calls)
        assertEquals(listOf(FakeRowsData.FIRST_WATCHLIST), fake.holdingIds(signal.key))
        assertEquals(listOf(FakeRowsData.FIRST_WATCHLIST), fake.held.value[signal.key] ?: listOf(FakeRowsData.FIRST_WATCHLIST))
    }

    @Test
    fun `a removal is sent when its message goes`() = runTest(main.dispatcher) {
        onlyOne()
        val vm = saver()
        advanceUntilIdle()
        vm.press(signal, on = true)
        vm.messageShown()
        advanceUntilIdle()
        assertEquals(emptyList<Long>(), fake.holdingIds(signal.key))
    }

    @Test
    fun `a TV says nothing and sends a removal at once`() = runTest(main.dispatcher) {
        onlyOne()
        val vm = saver(messages = false)
        advanceUntilIdle()
        vm.press(signal, on = true)
        advanceUntilIdle()
        assertNull(vm.state.value.message)
        assertEquals(emptyList<Long>(), fake.holdingIds(signal.key))
    }

    @Test
    fun `the first save makes Watchlist`() = runTest(main.dispatcher) {
        listOf(FakeRowsData.FIRST_WATCHLIST, FakeRowsData.HALLOWEEN, FakeRowsData.WITH_THE_KIDS).forEach(data::delete)
        val vm = saver()
        advanceUntilIdle()
        vm.press(bay, on = false)
        advanceUntilIdle()
        assertEquals(listOf("Watchlist"), vm.state.value.watchlists?.map { it.name })
        assertEquals(WatchlistMessage("Saved to Watchlist", undo = true), vm.state.value.message)
    }

    @Test
    fun `a refused save goes back and says why`() = runTest(main.dispatcher) {
        onlyOne()
        val vm = saver()
        advanceUntilIdle()
        fake.refuse = 422
        vm.press(bay, on = false)
        advanceUntilIdle()
        assertNull(fake.held.value[bay.key])
        assertEquals(WatchlistMessage("The server couldn’t save that."), vm.state.value.message)
    }

    @Test
    fun `with several watchlists a press opens the picker, and a row saves at once`() = runTest(main.dispatcher) {
        val vm = saver()
        advanceUntilIdle()
        vm.press(bay, on = true, read = listOf(FakeRowsData.WITH_THE_KIDS))
        advanceUntilIdle()
        val picker = assertNotNull(vm.state.value.picker).let { vm.state.value }
        assertEquals(listOf("Watchlist" to false, "Halloween" to false, "With the kids" to true), picker.checked(fake.held.value).map { (w, on) -> w.name to on })
        vm.toggle(picker.watchlists!!.first { it.name == "Halloween" })
        advanceUntilIdle()
        assertEquals(listOf(FakeRowsData.HALLOWEEN, FakeRowsData.WITH_THE_KIDS), fake.holdingIds(bay.key).sorted())
        assertNull("the picker says nothing", vm.state.value.message)
    }

    @Test
    fun `New watchlist refuses a name already used before asking, and Create saves to the new one`() = runTest(main.dispatcher) {
        val vm = saver()
        advanceUntilIdle()
        vm.open(bay)
        vm.startNaming()
        val calls = fake.calls
        vm.create("halloween")
        assertEquals("You already have a watchlist called halloween.", vm.state.value.picker?.naming?.error)
        assertEquals(calls, fake.calls)
        vm.nameChanged()
        assertNull(vm.state.value.picker?.naming?.error)
        vm.create("Date night")
        advanceUntilIdle()
        val made = vm.state.value.watchlists!!.last()
        assertEquals("Date night", made.name)
        assertNull("back to the list", vm.state.value.picker?.naming)
        assertTrue(made.id in fake.holdingIds(bay.key))
    }

    @Test
    fun `a watchlist's page filters, removes with Undo, and keeps watched titles on the switch`() = runTest(main.dispatcher) {
        val page = MediaListViewModel(FakeRowsRepository(data, level = { WATCHLISTS_API_LEVEL }), FakeRequestRepository({ null }), FakeLibraryRepository(), FakeRowsData.FIRST_WATCHLIST, fake, shown = ListShown.Movies)
        advanceUntilIdle()
        val state = page.state.value
        assertTrue(state.isWatchlist)
        assertTrue("movies only", state.entries.all { it.mediaType == MediaType.Movie })

        val tin = state.entries.first { it.title == "Tin Sky" }
        page.remove(tin)
        assertFalse("off the page at once", page.state.value.entries.any { it.title == "Tin Sky" })
        assertEquals("Removed from Watchlist", page.state.value.message)
        page.undo()
        assertTrue(page.state.value.entries.any { it.title == "Tin Sky" })

        page.setRemoveWatched(false)
        assertFalse(page.state.value.list!!.removeWatched)
        advanceUntilIdle()
        assertFalse(fake.watchlists.value!!.first { it.id == FakeRowsData.FIRST_WATCHLIST }.removeWatched)
    }

    @Test
    fun `a refused switch goes back`() = runTest(main.dispatcher) {
        val page = MediaListViewModel(FakeRowsRepository(data, level = { WATCHLISTS_API_LEVEL }), FakeRequestRepository({ null }), FakeLibraryRepository(), FakeRowsData.HALLOWEEN, fake)
        advanceUntilIdle()
        fake.refuse = 500
        page.setRemoveWatched(true)
        advanceUntilIdle()
        assertFalse(page.state.value.list!!.removeWatched)
        assertEquals("The server couldn’t save that.", page.state.value.message)
    }

    @Test
    fun `Rename refuses another watchlist's name and keeps its own`() = runTest(main.dispatcher) {
        fake.refresh()
        val page = MediaListViewModel(FakeRowsRepository(data, level = { WATCHLISTS_API_LEVEL }), FakeRequestRepository({ null }), FakeLibraryRepository(), FakeRowsData.HALLOWEEN, fake)
        advanceUntilIdle()
        page.startRename()
        page.rename("With the Kids")
        assertEquals("You already have a watchlist called With the Kids.", page.state.value.naming?.error)
        page.rename("Spooky season")
        advanceUntilIdle()
        assertNull(page.state.value.naming)
        assertEquals("Spooky season", page.state.value.list?.name)
    }

    @Test
    fun `the Watchlists page names a new one and opens it`() = runTest(main.dispatcher) {
        val vm = WatchlistsViewModel(fake)
        advanceUntilIdle()
        assertEquals(listOf("Watchlist", "Halloween", "With the kids"), vm.state.value.watchlists?.map { it.name })
        vm.startNaming()
        vm.create("Road trips")
        advanceUntilIdle()
        assertEquals("Road trips", vm.state.value.watchlists!!.last().name)
        assertNull(vm.state.value.naming)
    }

    @Test
    fun `a page that read where a title is corrects what this device held`() = runTest(main.dispatcher) {
        fake.place(bay.key, listOf(FakeRowsData.FIRST_WATCHLIST))
        fake.known(bay.key, listOf(FakeRowsData.WITH_THE_KIDS), fake.revision)
        assertEquals(listOf(FakeRowsData.WITH_THE_KIDS), fake.held.value[bay.key])
    }

    private fun FakeWatchlistRepository.place(key: WatchlistKey, ids: List<Long>) = known(key, ids, revision)
}
