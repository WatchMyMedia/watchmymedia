package media.watchmy.client.presentation.home

import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import media.watchmy.client.fake.Cabin
import media.watchmy.client.fake.Den
import media.watchmy.client.fake.FakeLibraryRepository
import media.watchmy.client.fake.FakeRowsData
import media.watchmy.client.fake.FakeServerRepository
import media.watchmy.client.fake.fakeLatencyMs
import media.watchmy.client.model.playRequest
import media.watchmy.client.presentation.MainDispatcherRule
import media.watchmy.client.presentation.servers.OtherServers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Home filled from every fake server: the Living room server in use, the Cabin server, and the Den server that doesn't answer. */
class HomeServersTest {
    @get:Rule val main = MainDispatcherRule()

    private var latency = 0L

    @Before fun fast() {
        latency = fakeLatencyMs
        fakeLatencyMs = 0
    }

    @After fun restore() {
        fakeLatencyMs = latency
    }

    private val servers = FakeServerRepository()
    private val library = FakeLibraryRepository(servers = servers, rows = FakeRowsData(watchlistsOn = { true }))

    @Test fun `Continue watching and the recent rows take every server's cards, and a silent server is named with Try again`() = runTest(main.dispatcher) {
        val vm = HomeViewModel(library, others = OtherServers(servers, library))
        advanceUntilIdle()
        val home = vm.state.value.home!!
        val cabinCards = home.continueWatching.filter { it.where.serverId == Cabin.id }
        assertTrue("the Cabin server's Continue watching is on Home", cabinCards.isNotEmpty())
        assertTrue("its cards play from there", cabinCards.all { it.playRequest().serverId == Cabin.id })
        val dead = home.recentlyAddedMovies.first { it.title == "Dead Reckoning Bay" }
        assertEquals("one card for a title on both", 1, home.recentlyAddedMovies.count { it.title == "Dead Reckoning Bay" })
        assertEquals(listOf(Cabin.id), dead.where.copies.map { it.serverId })
        assertEquals(listOf("Den server"), vm.state.value.notAnswered)

        // Back: its titles join the ends of the rows, and what was drawn stays where it was.
        val before = vm.state.value.home!!.recentlyAddedMovies.map { it.where.serverId to it.id }
        servers.unreachable = emptySet()
        vm.retryElsewhere()
        advanceUntilIdle()
        assertTrue(vm.state.value.notAnswered.isEmpty())
        val after = vm.state.value.home!!.recentlyAddedMovies.map { it.where.serverId to it.id }
        assertEquals(before, after.take(before.size))
        assertTrue("the Den server's copies joined the cards they match", vm.state.value.home!!.recentlyAddedMovies.any { m -> m.where.copies.any { it.serverId == Den.id } })
    }

    @Test fun `a card from another server is taken off its Continue watching there`() = runTest(main.dispatcher) {
        val vm = HomeViewModel(library, others = OtherServers(servers, library))
        advanceUntilIdle()
        val cabin = vm.state.value.home!!.continueWatching.first { it.where.serverId == Cabin.id }
        vm.hideFromContinue(cabin)
        advanceUntilIdle()
        assertTrue(library.on(Cabin.id)!!.continueWatching().none { it.fileId == cabin.fileId })
        assertTrue("the server in use's own row is as it was", library.continueWatching().isNotEmpty())
    }
}
