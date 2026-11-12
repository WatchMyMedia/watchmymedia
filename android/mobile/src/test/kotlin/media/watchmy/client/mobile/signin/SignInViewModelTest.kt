package media.watchmy.client.mobile.signin

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import media.watchmy.client.fake.FakeServerRepository
import media.watchmy.client.fake.OfficeMini
import media.watchmy.client.fake.RemoteHome
import media.watchmy.client.mobile.MainDispatcherRule
import media.watchmy.client.mobile.TestAuth
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.DiscoveredServer
import media.watchmy.client.model.PairingState
import media.watchmy.client.model.SavedServer
import media.watchmy.client.model.Server
import media.watchmy.client.model.ServerRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SignInViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val auth = TestAuth()
    private val vm by lazy { SignInViewModel(auth, FakeServerRepository(signedIn = false)) }

    @Test fun `signs in and clears the password`() = runTest {
        vm.setName(" sam ")
        vm.setPassword("password")
        vm.signIn()
        assertEquals("sam", auth.user.value?.name)
        val s = vm.state.value
        assertFalse(s.busy)
        assertEquals("", s.password)
        assertNull(s.error)
    }

    @Test fun `a wrong password says so`() = runTest {
        vm.setName("sam")
        vm.setPassword("nope")
        vm.signIn()
        assertNull(auth.user.value)
        assertEquals("Wrong name or password.", vm.state.value.error)
        assertEquals("nope", vm.state.value.password)
    }

    @Test fun `a server that wants a browser check signs in by code instead`() = runTest {
        val servers = FakeServerRepository(signedIn = false)
        val vm = SignInViewModel(auth, servers)
        assertFalse(vm.state.value.codeSignIn)
        servers.select(RemoteHome)
        assertTrue(vm.state.value.codeSignIn)
        assertEquals("KQ7F2W", (vm.state.value.pairing as PairingState.Waiting).code.code)
        vm.setName("sam")
        vm.setPassword("password")
        assertFalse("no password sign-in here", vm.state.value.canSubmit)
        advanceUntilIdle()
        assertTrue(vm.state.value.pairing is PairingState.Approved)
        assertEquals("Sam", auth.user.value?.name)
    }

    @Test fun `signing out after a code sign-in shows a new code, not the approved one`() = runTest {
        val servers = FakeServerRepository(signedIn = false)
        val vm = SignInViewModel(auth, servers)
        servers.select(RemoteHome)
        advanceUntilIdle()
        assertTrue(vm.state.value.pairing is PairingState.Approved)

        auth.signOut()
        assertTrue(vm.state.value.codeSignIn)
        assertEquals("KQ7F2W", (vm.state.value.pairing as PairingState.Waiting).code.code)
        advanceUntilIdle()
        assertEquals("Sam", auth.user.value?.name)
    }

    @Test fun `signing out after a password sign-in starts the form over`() = runTest {
        vm.setName("sam")
        vm.setPassword("password")
        vm.signIn()
        assertEquals("sam", auth.user.value?.name)
        auth.signOut()
        assertEquals(SignInViewModel.State(), vm.state.value)
    }

    @Test fun `a server that turned the browser check on since turns to a code`() = runTest {
        auth.signInFailure = ApiException(403, "This server asks for a browser check before password sign-in. Sign in with a code instead.")
        vm.setName("sam")
        vm.setPassword("password")
        vm.signIn()
        assertTrue(vm.state.value.codeSignIn)
        assertNull(vm.state.value.error)
        assertTrue(vm.state.value.pairing is PairingState.Waiting)
    }

    @Test fun `throttling asks to wait`() = runTest {
        auth.signInFailure = ApiException(429, "too many requests")
        vm.setName("sam")
        vm.setPassword("password")
        vm.signIn()
        assertEquals("Too many tries. Wait a minute, then try again.", vm.state.value.error)
    }

    @Test fun `other failures show the server's message`() = runTest {
        auth.signInFailure = ApiException(null, "Couldn't reach 192.168.1.20.")
        vm.setName("sam")
        vm.setPassword("password")
        vm.signIn()
        assertEquals("Couldn't reach 192.168.1.20.", vm.state.value.error)
    }

    @Test fun `empty fields are caught before asking the server`() = runTest {
        vm.signIn()
        assertEquals("Enter your name and password.", vm.state.value.error)
        vm.setName("sam")
        assertNull("typing clears the error", vm.state.value.error)
        assertFalse(vm.state.value.canSubmit)
    }

    @Test fun `show password toggles`() {
        vm.toggleShowPassword()
        assertTrue(vm.state.value.showPassword)
        vm.toggleShowPassword()
        assertFalse(vm.state.value.showPassword)
    }

    @Test fun `exposes the current server`() {
        assertEquals("Living room server", vm.server.value?.server?.name)
    }

    @Test fun `another server starts the form over`() = runTest {
        val servers = FakeServerRepository(signedIn = false)
        val vm = SignInViewModel(auth, servers)
        auth.signInFailure = ApiException(401, "wrong")
        vm.setName("admin")
        vm.setPassword("nope")
        vm.signIn()
        assertEquals("Wrong name or password.", vm.state.value.error)
        servers.select(OfficeMini)
        val s = vm.state.value
        assertEquals("", s.name)
        assertEquals("", s.password)
        assertNull(s.error)
    }

    @Test fun `a server with no accounts sends you to its web page, then signs in once it has one`() = runTest {
        val servers = NewServer()
        val vm = SignInViewModel(auth, servers)
        assertTrue(vm.state.value.setupRequired)
        vm.setName("sam")
        vm.setPassword("password")
        assertFalse("no sign-in before the first account", vm.state.value.canSubmit)

        vm.checkSetup(quiet = true)
        advanceUntilIdle()
        assertTrue(vm.state.value.setupRequired)
        assertNull("coming back says nothing while it waits", vm.state.value.error)
        vm.checkSetup()
        advanceUntilIdle()
        assertEquals("No account on this server yet. Make one on its web page first.", vm.state.value.error)

        servers.setUp = true
        vm.checkSetup(quiet = true)
        advanceUntilIdle()
        assertFalse(vm.state.value.setupRequired)
        assertFalse(vm.state.value.checking)
        assertFalse("the saved server knows too", servers.current.value!!.server.setupRequired)
    }
}

/** One server with no accounts until [setUp]. */
private class NewServer : ServerRepository {
    var setUp = false
    private fun server() = Server("new", "New server", "http://192.168.1.30:27587", "v0.4.1", 2, setupRequired = !setUp)
    override val discovered: Flow<List<DiscoveredServer>> = flowOf(emptyList())
    override val current = MutableStateFlow<SavedServer?>(SavedServer(server(), null, false, 0))
    override val saved: Flow<List<SavedServer>> = current.map { listOfNotNull(it) }
    override suspend fun probe(address: String): Server = server()
    override suspend fun select(server: Server) {
        current.value = SavedServer(server, null, false, 0)
    }
    override suspend fun forget(serverId: String) = Unit
}
