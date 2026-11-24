package media.watchmy.client.presentation.library

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import media.watchmy.client.fake.Cabin
import media.watchmy.client.fake.FakeLibraryRepository
import media.watchmy.client.fake.FakeRowsData
import media.watchmy.client.fake.FakeServerRepository
import media.watchmy.client.fake.fakeLatencyMs
import media.watchmy.client.model.ItemSort
import media.watchmy.client.model.LibraryType
import media.watchmy.client.presentation.MainDispatcherRule
import media.watchmy.client.presentation.servers.OtherServers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Movies from every fake server, merged a page at a time. */
class LibraryServersTest {
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

    private fun vm(pageSize: Int = 60) = LibraryViewModel(library, LibraryType.Movie, SavedStateHandle(), pageSize = pageSize, others = OtherServers(servers, library))

    @Test fun `every server's movies are one grid, in the sort, a card a title, and a silent server is said with Try again`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.setSort(ItemSort.Title)
        advanceUntilIdle()
        val s = vm.state.value
        assertTrue(s.merged)
        val titles = s.items.map { it.title }
        assertEquals("in title order across servers", titles.sortedBy { MergedPages.titleKey(it) }, titles)
        assertEquals(1, titles.count { it == "Dead Reckoning Bay" })
        assertEquals(1, titles.count { it == "Ninety Fathoms" })
        assertTrue("the Cabin server's own titles are there", s.items.any { it.title == "The Harbor Choir" && it.where.serverId == Cabin.id })
        assertTrue(s.retryServers)
        assertEquals("Den server didn’t answer, so its titles aren’t listed.", s.notes.single())
        assertFalse(s.about)
    }

    @Test fun `pages merge in order across servers as the grid scrolls`() = runTest(main.dispatcher) {
        val vm = vm(pageSize = 4)
        vm.setSort(ItemSort.Title)
        advanceUntilIdle()
        assertEquals(4, vm.state.value.items.size)
        assertTrue(vm.state.value.hasMore)
        repeat(6) {
            vm.loadMore()
            advanceUntilIdle()
        }
        val titles = vm.state.value.items.map { it.title }
        assertEquals(titles.sortedBy { MergedPages.titleKey(it) }, titles)
        assertEquals("every title once", titles.distinct(), titles)
        assertFalse(vm.state.value.hasMore)
    }

    @Test fun `a server answering again joins from the start`() = runTest(main.dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        servers.unreachable = emptySet()
        vm.retryServers()
        advanceUntilIdle()
        assertFalse(vm.state.value.retryServers)
        assertTrue(vm.state.value.notes.isEmpty())
    }
}
