package media.watchmy.client.mobile.servers

import app.cash.turbine.test
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import media.watchmy.client.fake.FakeServerRepository
import media.watchmy.client.mobile.MainDispatcherRule
import media.watchmy.client.mobile.keepCollecting
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.SavedServer
import media.watchmy.client.model.Server
import media.watchmy.client.model.ServerRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ServerPickerViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    /** The fake, plus an old server and an unreachable one. */
    private class TestServers(val fake: FakeServerRepository = FakeServerRepository(signedIn = true)) : ServerRepository by fake {
        override suspend fun probe(address: String): Server = when {
            "old" in address -> Server("srv-old", "Attic NAS", "http://old.local:27587", "0.8.2", 0)
            "down" in address -> throw ApiException(null, "")
            else -> fake.probe(address)
        }
    }

    private val servers = TestServers()
    private val vm by lazy { ServerPickerViewModel(servers, now = { System.currentTimeMillis() }) }

    @Test fun `lists discovered servers with their sign-in, and remembered ones not on this network`() = runTest {
        keepCollecting(vm.state)
        val s = vm.state.value
        val found = s.discovered!!
        assertEquals(listOf("Living room server", "Office Mac mini"), found.map { it.name })
        assertEquals("192.168.1.20:27587 · v0.9.3 · signed in as Sam", found[0].detail)
        assertEquals("office-mini.local:27587 · v0.9.1 · not signed in", found[1].detail)
        assertTrue(found[0].current)
        assertEquals(listOf("home.watchmy.media"), s.remembered.map { it.name })
        assertTrue(s.remembered[0].detail, s.remembered[0].detail.startsWith("Last used 3 days ago"))
    }

    @Test fun `a remembered server over plain http beyond this network is marked Not encrypted`() = runTest {
        val lake = SavedServer(Server("srv-lake", "Lake house", "http://203.0.113.20:27587", "0.9.3", 7), "Sam", false, 0)
        val withLake = object : ServerRepository by servers {
            override val saved: Flow<List<SavedServer>> = servers.saved.map { it + lake }
        }
        val vm = ServerPickerViewModel(withLake, now = { System.currentTimeMillis() })
        keepCollecting(vm.state)
        val s = vm.state.value
        assertEquals("on this network", listOf(false, false), s.discovered!!.map { it.notEncrypted })
        assertEquals(mapOf("home.watchmy.media" to false, "Lake house" to true), s.remembered.associate { it.name to it.notEncrypted })
    }

    @Test fun `choosing a server selects it and reports back`() = runTest {
        keepCollecting(vm.state)
        vm.chosen.test {
            vm.choose(vm.state.value.discovered!![1])
            awaitItem()
        }
        assertEquals("srv-office", servers.current.value?.server?.id)
        assertNull(vm.state.value.connecting)
    }

    @Test fun `a typed address connects`() = runTest {
        keepCollecting(vm.state)
        vm.chosen.test {
            vm.setAddress("https://home.watchmy.media")
            vm.connectToAddress()
            awaitItem()
        }
        assertEquals("srv-remote", servers.current.value?.server?.id)
        assertEquals("", vm.state.value.address)
    }

    @Test fun `an unreachable address says so under the field`() = runTest {
        keepCollecting(vm.state)
        vm.setAddress("down.example.net")
        vm.connectToAddress()
        val s = vm.state.value
        assertEquals("Couldn't reach down.example.net. Check the address, and that the server is running and on this network.", s.addressError)
        assertNull(s.connecting)
        assertEquals("srv-living", servers.current.value?.server?.id)
    }

    @Test fun `a server that is too old is refused with its version`() = runTest {
        keepCollecting(vm.state)
        vm.setAddress("old.local")
        vm.connectToAddress()
        assertEquals("Attic NAS runs version 0.8.2, which is too old for this app. Update the server.", vm.state.value.addressError)
        assertEquals("srv-living", servers.current.value?.server?.id)
    }

    @Test fun `an empty address asks for one`() = runTest {
        keepCollecting(vm.state)
        vm.connectToAddress()
        assertEquals("Enter the server's address.", vm.state.value.addressError)
        vm.setAddress("n")
        assertNull(vm.state.value.addressError)
    }

    @Test fun `forgetting a remembered server removes it`() = runTest {
        keepCollecting(vm.state)
        vm.forget(vm.state.value.remembered.single())
        assertTrue(vm.state.value.remembered.isEmpty())
    }
}
