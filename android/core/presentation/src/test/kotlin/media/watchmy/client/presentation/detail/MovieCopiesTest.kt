package media.watchmy.client.presentation.detail

import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import media.watchmy.client.fake.Cabin
import media.watchmy.client.fake.FakeCabin
import media.watchmy.client.fake.FakeData
import media.watchmy.client.fake.FakeLibraryRepository
import media.watchmy.client.fake.FakeRowsData
import media.watchmy.client.fake.FakeServerRepository
import media.watchmy.client.fake.LivingRoom
import media.watchmy.client.fake.fakeLatencyMs
import media.watchmy.client.model.CopyChoices
import media.watchmy.client.presentation.MainDispatcherRule
import media.watchmy.client.presentation.servers.OtherServers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** A movie on two of the fake servers: the Living room's Ninety Fathoms, and the Cabin's, where Sam got further. */
class MovieCopiesTest {
    @get:Rule val main = MainDispatcherRule()

    private var latency = 0L

    @Before fun fast() {
        latency = fakeLatencyMs
        fakeLatencyMs = 0
    }

    @org.junit.After fun restore() {
        fakeLatencyMs = latency
    }

    private val servers = FakeServerRepository()
    private val library = FakeLibraryRepository(servers = servers, rows = FakeRowsData(watchlistsOn = { true }))
    private val choices = CopyChoices.InMemory()

    private fun vm(here: String? = null, movieId: Long = FakeData.NINETY_FATHOMS) =
        MovieViewModel(library.on(here)!!, movieId, here = here, others = OtherServers(servers, library), choices = choices)

    @Test fun `every server's copy is found by TMDB id, the page's own first, and a silent one is left out`() = runTest(main.dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        val copies = vm.state.value.copies
        assertTrue(copies.several)
        assertEquals(listOf(null, Cabin.id), copies.all.map { it.serverId })
        assertEquals(listOf(FakeData.NINETY_FATHOMS, FakeCabin.NINETY_FATHOMS), copies.all.map { it.titleId })
        assertTrue("the Den server doesn't answer", copies.asking.isEmpty())
        assertEquals(listOf("Living room server", "Cabin server"), copies.all.map { it.serverName })
    }

    @Test fun `Resume plays the copy with the latest place, from that server, until another is chosen`() = runTest(main.dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        assertEquals("Sam got further on the Cabin server", Cabin.id, vm.state.value.furtherElsewhere?.serverId)
        val resume = vm.playRequest()!!
        assertEquals(Cabin.id, resume.serverId)
        assertEquals("that copy resumes itself", null, resume.startMs)
        assertEquals(7000 + FakeCabin.NINETY_FATHOMS, resume.fileId)

        // The Living room's 4K version chosen: Play plays it, from the place Sam got to on the Cabin server.
        val home = vm.state.value.copies.all.first()
        vm.choose(home, home.versions.first())
        val chosen = vm.playRequest()!!
        assertNull(chosen.serverId)
        assertEquals(home.versions.first().fileId, chosen.fileId)
        assertEquals(vm.state.value.copies.all[1].place!!.positionMs, chosen.startMs)
        assertEquals("From the start is from the start", 0L, vm.playRequest(fromStart = true)!!.startMs)
    }

    @Test fun `the choice is kept for the title on this device`() = runTest(main.dispatcher) {
        val first = vm()
        advanceUntilIdle()
        val home = first.state.value.copies.all.first()
        first.choose(home, home.versions.last())
        val again = vm()
        advanceUntilIdle()
        assertEquals(home.versions.last().fileId, again.state.value.copies.play?.version?.fileId)
        assertEquals(LivingRoom.id, choices.chosen("Movie:${FakeData.tmdbId(FakeData.NINETY_FATHOMS)}")?.serverId)
    }

    @Test fun `a movie only another server has opens in place, from there, and names it`() = runTest(main.dispatcher) {
        val choir = FakeCabin.movies.first { it.title == "The Harbor Choir" }.id
        val vm = vm(here = Cabin.id, movieId = choir)
        advanceUntilIdle()
        assertEquals("Cabin server", vm.state.value.hereName)
        assertTrue(vm.state.value.elsewhere)
        assertFalse("no other server has it", vm.state.value.copies.several)
        assertEquals(Cabin.id, vm.playRequest()?.serverId)
    }

    @Test fun `with one server signed in, nothing is asked and the page is as it was`() = runTest(main.dispatcher) {
        val one = FakeServerRepository(several = false)
        val lone = FakeLibraryRepository(servers = one, rows = FakeRowsData(watchlistsOn = { true }))
        val vm = MovieViewModel(lone, FakeData.NINETY_FATHOMS, others = OtherServers(one, lone), choices = choices)
        advanceUntilIdle()
        assertFalse(vm.state.value.copies.several)
        assertNull(vm.playRequest()?.serverId)
    }
}
