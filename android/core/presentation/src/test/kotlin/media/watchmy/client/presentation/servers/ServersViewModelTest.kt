package media.watchmy.client.presentation.servers

import app.cash.turbine.test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import media.watchmy.client.fake.Cabin
import media.watchmy.client.fake.Den
import media.watchmy.client.fake.FakeAuthRepository
import media.watchmy.client.fake.FakeCastService
import media.watchmy.client.fake.FakeDownloadRepository
import media.watchmy.client.fake.FakeServerRepository
import media.watchmy.client.fake.LivingRoom
import media.watchmy.client.fake.RemoteHome
import media.watchmy.client.model.DownloadRepository
import media.watchmy.client.model.SavedServer
import media.watchmy.client.model.Server
import media.watchmy.client.model.ServerRepository
import media.watchmy.client.model.ServerSwitch
import media.watchmy.client.model.watchItsDownloads
import media.watchmy.client.presentation.MainDispatcherRule
import media.watchmy.client.presentation.keepCollecting
import media.watchmy.client.presentation.servers.ServersViewModel.RowState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Servers on the fakes: the Living room server in use, the Cabin server and
 * the Den server signed in (the Den server never answers), and one more
 * signed out.
 */
class ServersViewModelTest {
    @get:Rule val main = MainDispatcherRule(UnconfinedTestDispatcher())

    /** The fake servers, counting the switches that asked first and holding one until [gate] opens. */
    private class TestServers(val fake: FakeServerRepository = FakeServerRepository()) : ServerRepository by fake {
        val asked = mutableListOf<String>()
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun switchTo(serverId: String, ask: Boolean): ServerSwitch {
            if (ask) asked += serverId
            gate?.await()
            return fake.switchTo(serverId, ask)
        }
    }

    private val servers = TestServers()
    private val auth = FakeAuthRepository(servers = servers.fake)
    private val downloads = FakeDownloadRepository(servers = servers.fake)
    private fun model(downloads: DownloadRepository = this.downloads, repo: ServerRepository = servers) = ServersViewModel(repo, downloads)

    @Test fun `lists the registered servers, the one in use first, then by last use`() = runTest {
        val vm = model()
        keepCollecting(vm.state)
        val s = vm.state.value
        assertEquals(listOf("Living room server", "Cabin server", "Den server", "home.watchmy.media"), s.rows.map { it.name })
        assertEquals(listOf(true, false, false, false), s.rows.map { it.current })
        assertEquals("Sam · 192.168.1.20:27587", s.rows[0].line)
        assertEquals("Sam · 192.168.1.20:27587 · v0.9.3", s.rows[0].detail)
        assertEquals("0.9.3", s.rows[0].version)
        assertEquals(listOf(RowState.Idle, RowState.Idle, RowState.Idle, RowState.SignedOut), s.rows.map { it.state })
        assertFalse("the one in use isn't chosen again", s.rows[0].canChoose)
        assertTrue("one signed out is chosen, for its sign-in", s.rows[3].canChoose)
        assertTrue(s.several)
        assertEquals("Living room and 3 more", s.summary)
        assertFalse(s.full)
        assertNull(s.failure)
    }

    @Test fun `each row says what this device holds from its server`() = runTest {
        val vm = model()
        keepCollecting(vm.state)
        val rows = vm.state.value.rows.associateBy { it.serverId }
        assertEquals(3, rows.getValue(Cabin.id).downloads?.count)
        assertEquals(3, rows.getValue(Den.id).downloads?.count)
        assertEquals(7, rows.getValue(LivingRoom.id).downloads?.count)
        assertNull("nothing from a server no one is signed in to", rows.getValue(RemoteHome.id).downloads)
        // A TV has no downloads at all.
        val tv = model(DownloadRepository.Unavailable)
        keepCollecting(tv.state)
        assertTrue(tv.state.value.rows.all { it.downloads == null })
    }

    @Test fun `a server too old for this app says so and isn't chosen`() = runTest {
        val attic = SavedServer(Server("srv-old", "Attic NAS", "http://attic.local:27587", "0.0.9", 0), "Sam", true, 0, 1)
        val withOld = object : ServerRepository by servers {
            override val saved: Flow<List<SavedServer>> = servers.saved.map { it + attic }
        }
        val vm = model(repo = withOld)
        keepCollecting(vm.state)
        val row = vm.state.value.row("srv-old")!!
        assertEquals(RowState.TooOld, row.state)
        assertFalse(row.canChoose)
    }

    @Test fun `with one server registered there is nothing to switch to and nothing to say on Home`() = runTest {
        listOf(Cabin.id, Den.id, RemoteHome.id).forEach { servers.forget(it) }
        val vm = model()
        keepCollecting(vm.state)
        assertEquals(listOf("Living room server"), vm.state.value.rows.map { it.name })
        assertFalse(vm.state.value.several)
        assertEquals("Living room server", vm.state.value.summary)
    }

    @Test fun `choosing a server asks it first, shows Switching, and switches with no sign-in`() = runTest {
        val vm = model()
        keepCollecting(vm.state)
        servers.gate = CompletableDeferred()
        vm.events.test {
            vm.choose(Cabin.id)
            assertEquals(RowState.Switching, vm.state.value.row(Cabin.id)!!.state)
            assertEquals("still on the first while it is asked", LivingRoom.id, servers.current.value?.server?.id)
            // A second choice while one is under way does nothing.
            vm.choose(Den.id)
            servers.gate!!.complete(Unit)
            assertEquals(ServersViewModel.Event.Switched, awaitItem())
        }
        assertEquals(listOf(Cabin.id), servers.asked)
        assertEquals(Cabin.id, servers.current.value?.server?.id)
        assertEquals("its own sign-in, kept", 4L, auth.user.value?.id)
        assertEquals("the one in use is listed first", listOf("Cabin server", "Living room server", "Den server", "home.watchmy.media"), vm.state.value.rows.map { it.name })
        assertTrue(vm.state.value.rows.none { it.state == RowState.Switching })
    }

    @Test fun `choosing a server whose sign-in ended opens its sign-in`() = runTest {
        val vm = model()
        keepCollecting(vm.state)
        vm.events.test {
            vm.choose(RemoteHome.id)
            assertEquals(ServersViewModel.Event.SignIn, awaitItem())
        }
        assertEquals(RemoteHome.id, servers.current.value?.server?.id)
        assertNull(auth.user.value)
    }

    @Test fun `a server that doesn't answer leaves the app where it was, with Try again`() = runTest {
        val vm = model()
        keepCollecting(vm.state)
        vm.events.test {
            vm.choose(Den.id)
            val failure = vm.state.value.failure!!
            assertEquals("Couldn’t reach Den server", failure.title)
            assertEquals("It may be off, or only reachable on its own network. You’re still on Living room server.", failure.body)
            assertEquals(LivingRoom.id, servers.current.value?.server?.id)
            assertEquals(RowState.Idle, vm.state.value.row(Den.id)!!.state)

            // Try again asks it again; it still doesn't answer.
            vm.retry()
            assertEquals(listOf(Den.id, Den.id), servers.asked)
            assertEquals(Den.id, vm.state.value.failure?.serverId)

            // It is back: Try again switches, and the message goes.
            servers.fake.unreachable = emptySet()
            vm.retry()
            assertEquals(ServersViewModel.Event.Switched, awaitItem())
        }
        assertNull(vm.state.value.failure)
        assertEquals(Den.id, servers.current.value?.server?.id)
    }

    @Test fun `on a device holding its downloads, Watch its downloads switches without asking`() = runTest {
        val vm = model()
        keepCollecting(vm.state)
        vm.events.test {
            vm.choose(Den.id)
            assertEquals(3, vm.state.value.failure!!.downloads)
            assertEquals("Watch its 3 downloads", watchItsDownloads(vm.state.value.failure!!.downloads))
            vm.watchDownloads()
            assertEquals(ServersViewModel.Event.Switched, awaitItem())
        }
        assertEquals("asked once, by the choice", listOf(Den.id), servers.asked)
        assertEquals(Den.id, servers.current.value?.server?.id)
        assertEquals("the Downloads list is that server's now", 3, downloads.downloads.value.size)
    }

    @Test fun `with no downloads from it, as on a TV, the failure offers only Try again`() = runTest {
        val vm = model(DownloadRepository.Unavailable)
        keepCollecting(vm.state)
        vm.choose(Den.id)
        assertEquals(0, vm.state.value.failure!!.downloads)
        vm.dismissFailure()
        assertNull(vm.state.value.failure)
    }

    @Test fun `while this device casts, Servers names the TV a switch stops`() = runTest {
        val vm = ServersViewModel(servers, downloads, FakeCastService("Living room TV", connected = true))
        keepCollecting(vm.state)
        assertEquals("Living room TV", vm.state.value.casting)
        assertNull(model().also { keepCollecting(it.state) }.state.value.casting)
    }

    @Test fun `removing a server that isn't in use deletes its sign-in and its downloads, and nothing else`() = runTest {
        val vm = model()
        keepCollecting(vm.state)
        vm.events.test {
            vm.remove(Cabin.id)
            assertEquals(ServersViewModel.Event.Removed("Cabin server"), awaitItem())
        }
        assertTrue(servers.saved.first().none { it.server.id == Cabin.id })
        assertNull("the sign-in is gone", servers.fake.account(Cabin.id))
        assertFalse("and so are its downloads", Cabin.id in downloads.byServer.value)
        assertEquals("the one in use stays", LivingRoom.id, servers.current.value?.server?.id)
        assertEquals(7, downloads.downloads.value.size)
        assertEquals(listOf("Living room server", "Den server", "home.watchmy.media"), vm.state.value.rows.map { it.name })
        assertNull(vm.state.value.removing)
    }

    @Test fun `removing the server in use moves to the one used last that is still signed in`() = runTest {
        val vm = model()
        keepCollecting(vm.state)
        vm.remove(LivingRoom.id)
        assertEquals(Cabin.id, servers.current.value?.server?.id)
        assertEquals(4L, auth.user.value?.id)
        assertEquals("its downloads are the list now, and the removed server's are gone", setOf(Cabin.id, Den.id), downloads.byServer.value.keys)
    }

    @Test fun `twelve registered is the most a device keeps`() = runTest {
        val twelve = (1..12).map { SavedServer(Server("srv-$it", "Server $it", "http://10.0.0.$it:27587", "0.9.3", 7), "Sam", true, it.toLong(), 1) }
        val full = object : ServerRepository by servers {
            override val saved: Flow<List<SavedServer>> = MutableStateFlow(twelve)
        }
        val vm = model(repo = full)
        keepCollecting(vm.state)
        assertTrue(vm.state.value.full)
    }
}
