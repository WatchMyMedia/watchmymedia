package media.watchmy.client.tv.servers

import app.cash.turbine.test
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import media.watchmy.client.model.DiscoveredServer
import media.watchmy.client.fake.FakeServerRepository
import media.watchmy.client.fake.OfficeMini
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.SavedServer
import media.watchmy.client.model.Server
import media.watchmy.client.model.ServerRepository
import media.watchmy.client.tv.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException

class ServerPickerViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val now = 1_800_000_000_000L

    /** Collects [vm]'s rows for the rest of the test, as the screen does. */
    private fun TestScope.showing(vm: ServerPickerViewModel) = backgroundScope.launch { vm.rows.collect {} }

    @Test
    fun `a remembered server over plain http beyond this network is marked Not encrypted`() = runTest(main.dispatcher) {
        val fake = FakeServerRepository(signedIn = true)
        val lake = SavedServer(Server("srv-lake", "Lake house", "http://203.0.113.20:27587", "0.9.3", 7), "Sam", false, now - 86_400_000)
        val servers = object : ServerRepository by fake {
            override val saved: Flow<List<SavedServer>> = fake.saved.map { it + lake }
        }
        val vm = ServerPickerViewModel(servers, now = { now })
        showing(vm)
        advanceUntilIdle()
        val rows = vm.rows.value!!
        assertEquals(
            "a LAN address, a .local name and https are not; plain http to a public address is",
            mapOf("Living room server" to false, "Office Mac mini" to false, "home.watchmy.media" to false, "Lake house" to true),
            rows.associate { it.name to it.notEncrypted },
        )
    }

    @Test
    fun `lists servers on the network first, then remembered ones`() = runTest(main.dispatcher) {
        val vm = ServerPickerViewModel(FakeServerRepository(signedIn = true), now = { now })
        showing(vm)
        advanceUntilIdle()
        val rows = vm.rows.value!!
        assertEquals(listOf("Living room server", "Office Mac mini", "home.watchmy.media"), rows.map { it.name })
        assertEquals(listOf(true, true, false), rows.map { it.onNetwork })
        assertEquals("192.168.1.20:27587 · v0.9.3 · signed in as Sam", rows[0].detail)
        assertEquals("office-mini.local:27587 · v0.9.1 · not signed in", rows[1].detail)
        assertTrue(rows[2].detail.startsWith("Remembered · last used "))
        assertTrue(rows[0].current)
    }

    @Test
    fun `choosing a server makes it the one in use`() = runTest(main.dispatcher) {
        val repo = FakeServerRepository(signedIn = true)
        val vm = ServerPickerViewModel(repo)
        showing(vm)
        advanceUntilIdle()
        vm.chosen.test {
            vm.choose(vm.rows.value!![1])
            assertEquals("srv-office", vm.form.value.connecting)
            awaitItem()
            assertNull(vm.form.value.connecting)
            assertNull(vm.form.value.rowError)
        }
        assertEquals(OfficeMini.id, repo.current.value?.server?.id)
    }

    @Test
    fun `each choice is told once, and a later look at the picker is not told again`() = runTest(main.dispatcher) {
        val vm = ServerPickerViewModel(FakeServerRepository(signedIn = true))
        showing(vm)
        advanceUntilIdle()
        vm.chosen.test {
            vm.choose(vm.rows.value!![1])
            awaitItem()
            vm.choose(vm.rows.value!![0])
            awaitItem()
            expectNoEvents()
        }
        vm.chosen.test { expectNoEvents() }
    }

    @Test
    fun `looking for servers stops soon after the picker goes`() = runTest(main.dispatcher) {
        val looking = MutableStateFlow(0)
        val repo = object : ServerRepository by FakeServerRepository() {
            override val discovered: Flow<List<DiscoveredServer>> = MutableStateFlow(emptyList<DiscoveredServer>())
                .onStart { looking.value++ }
                .onCompletion { looking.value-- }
        }
        val vm = ServerPickerViewModel(repo)
        assertEquals(0, looking.value)
        val shown = showing(vm)
        runCurrent()
        assertEquals(1, looking.value)
        shown.cancel()
        advanceTimeBy(5_001)
        assertEquals(0, looking.value)
    }

    @Test
    fun `a server that is too old is refused with a clear message`() = runTest(main.dispatcher) {
        val old = Server("srv-old", "Attic NAS", "http://10.0.0.9:27587", "0.5.0", 0)
        val repo = object : ServerRepository by FakeServerRepository() {
            override suspend fun probe(address: String) = old
        }
        val vm = ServerPickerViewModel(repo)
        showing(vm)
        advanceUntilIdle()
        vm.chosen.test {
            vm.choose(vm.rows.value!!.first())
            advanceUntilIdle()
            assertEquals("Attic NAS runs version 0.5.0, which is too old for this app. Update the server.", vm.form.value.rowError)
            expectNoEvents()
        }
    }

    @Test
    fun `an unreachable address says so`() = runTest(main.dispatcher) {
        val repo = object : ServerRepository by FakeServerRepository() {
            // As the repository words a network failure (RealServerRepository.probe).
            override suspend fun probe(address: String): Server =
                throw ApiException(null, "Couldn't reach $address. Check the address and that the server is running.", IOException("timeout"))
        }
        val vm = ServerPickerViewModel(repo)
        vm.setAddress("192.168.1.99")
        vm.connectAddress()
        advanceUntilIdle()
        assertEquals(
            "Couldn't reach 192.168.1.99. Check the address and that the server is running.",
            vm.form.value.addressError,
        )
    }

    @Test
    fun `a typed address connects, and an empty one asks for it`() = runTest(main.dispatcher) {
        val repo = FakeServerRepository()
        val vm = ServerPickerViewModel(repo)
        vm.connectAddress()
        assertTrue(vm.form.value.addressError!!.startsWith("Enter the server's address"))

        vm.setAddress("https://home.watchmy.media")
        assertNull(vm.form.value.addressError)
        vm.chosen.test {
            vm.connectAddress()
            awaitItem()
        }
        assertEquals("srv-remote", repo.current.value?.server?.id)
    }

    @Test
    fun `an http host name is flagged as needing https`() = runTest(main.dispatcher) {
        val vm = ServerPickerViewModel(FakeServerRepository())
        vm.setAddress("http://media.example.net")
        advanceUntilIdle()
        assertTrue(vm.addressNeedsHttps.value)
        vm.setAddress("http://nas.local:27587")
        advanceUntilIdle()
        assertFalse(vm.addressNeedsHttps.value)
    }
}
