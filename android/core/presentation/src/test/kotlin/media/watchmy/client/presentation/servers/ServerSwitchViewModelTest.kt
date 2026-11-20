package media.watchmy.client.presentation.servers

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import media.watchmy.client.fake.Cabin
import media.watchmy.client.fake.Den
import media.watchmy.client.fake.FakeAuthRepository
import media.watchmy.client.fake.FakeServerRepository
import media.watchmy.client.fake.LivingRoom
import media.watchmy.client.fake.OfficeMini
import media.watchmy.client.fake.RemoteHome
import media.watchmy.client.model.AddServerStep
import media.watchmy.client.model.DeepLink
import media.watchmy.client.model.NOW_ON_MS
import media.watchmy.client.model.ServerRepository
import media.watchmy.client.model.ServerSwitch
import media.watchmy.client.model.linkAccount
import media.watchmy.client.model.watchNextAccount
import media.watchmy.client.presentation.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * What the app does around a change of server, on the fakes: the Living
 * room server in use, the Cabin and Den servers signed in (the Den server
 * never answers), one more signed out, and the Office Mac mini not added.
 */
class ServerSwitchViewModelTest {
    @get:Rule val main = MainDispatcherRule(StandardTestDispatcher())

    /** The fake servers, noting each switch and whether it asked the server first. */
    private class TestServers(val fake: FakeServerRepository = FakeServerRepository()) : ServerRepository by fake {
        val switches = mutableListOf<Pair<String, Boolean>>()

        override suspend fun switchTo(serverId: String, ask: Boolean): ServerSwitch {
            switches += serverId to ask
            return fake.switchTo(serverId, ask)
        }
    }

    private val servers = TestServers()
    private val auth = FakeAuthRepository(servers = servers.fake)
    private fun model(addedStep: Boolean = true) = ServerSwitchViewModel(servers, addedStep = addedStep)

    private val current get() = servers.current.value?.server?.id

    @Test fun `a switch to another server signed in says where the app is now, for four seconds`() = runTest {
        val vm = model()
        runCurrent()
        assertNull("nothing to say at the start", vm.state.value.note)
        servers.switchTo(Cabin.id)
        runCurrent()
        assertEquals("Now on Cabin server", vm.state.value.note)
        advanceTimeBy(NOW_ON_MS - 1)
        assertEquals("Now on Cabin server", vm.state.value.note)
        advanceTimeBy(2)
        assertNull(vm.state.value.note)
    }

    @Test fun `a second switch replaces the note, and it can be put away`() = runTest {
        val vm = model()
        runCurrent()
        servers.switchTo(Cabin.id)
        runCurrent()
        advanceTimeBy(NOW_ON_MS - 500)
        servers.switchTo(LivingRoom.id)
        runCurrent()
        assertEquals("Now on Living room server", vm.state.value.note)
        advanceTimeBy(600)
        assertEquals("the first note's time doesn't end the second", "Now on Living room server", vm.state.value.note)
        vm.noteShown()
        assertNull(vm.state.value.note)
    }

    @Test fun `removing or signing out of the server in use says where the app moved`() = runTest {
        val vm = model()
        runCurrent()
        servers.forget(LivingRoom.id)
        runCurrent()
        assertEquals("Now on Cabin server", vm.state.value.note)
        auth.signOut()
        runCurrent()
        assertEquals("Now on Den server", vm.state.value.note)
    }

    @Test fun `a first sign-in says nothing`() = runTest {
        val alone = FakeServerRepository(signedIn = false)
        val vm = ServerSwitchViewModel(alone)
        runCurrent()
        FakeAuthRepository(signedIn = false, servers = alone).signIn("Sam", "password")
        runCurrent()
        assertNull(vm.state.value.note)
        assertNull(vm.state.value.returnTo)
    }

    @Test fun `a server that needs a sign-in keeps the way back, and Cancel takes it without a note`() = runTest {
        val vm = model()
        runCurrent()
        assertEquals(ServerSwitch.NeedsSignIn, servers.switchTo(RemoteHome.id))
        runCurrent()
        assertEquals(RemoteHome.id, current)
        assertEquals(LivingRoom.id, vm.state.value.returnTo)
        assertNull("no note for a sign-in screen", vm.state.value.note)

        vm.cancelSignIn()
        runCurrent()
        assertEquals("back on the server it was on", LivingRoom.id, current)
        assertEquals("without asking it again", LivingRoom.id to false, servers.switches.last())
        assertNull(vm.state.value.returnTo)
        assertNull(vm.state.value.note)
        assertTrue("the signed-out server stays registered", servers.saved.first().any { it.server.id == RemoteHome.id })
    }

    @Test fun `signing in on the server the app switched to says where it is`() = runTest {
        val vm = model()
        runCurrent()
        servers.switchTo(RemoteHome.id)
        runCurrent()
        auth.signIn("Sam", "password")
        runCurrent()
        assertEquals("Now on home.watchmy.media", vm.state.value.note)
        assertNull(vm.state.value.returnTo)
    }

    @Test fun `Add a server goes from choosing to signing in to Added, and says what changed`() = runTest {
        val vm = model()
        runCurrent()
        vm.startAdding()
        assertEquals(AddServerStep.Choose, vm.state.value.adding)
        assertEquals("Step 1 of 3 · Choose a server · next: Sign in", vm.state.value.adding!!.line)

        // The picker chose the Office Mac mini, which no one has signed in to.
        servers.select(OfficeMini)
        runCurrent()
        assertEquals(AddServerStep.SignIn, vm.state.value.adding)
        assertEquals("Step 2 of 3 · Sign in · next: Added", vm.state.value.adding!!.line)
        assertEquals(LivingRoom.id, vm.state.value.returnTo)

        auth.signIn("Sam", "password")
        runCurrent()
        assertEquals(AddServerStep.Added, vm.state.value.adding)
        assertEquals("Step 3 of 3 · Added", vm.state.value.adding!!.line)
        assertEquals(ServerSwitchViewModel.Added("Office Mac mini", "Sam", signedIn = 4), vm.state.value.added)
        assertNull("the last step says it, not a note", vm.state.value.note)
        assertEquals(OfficeMini.id, current)

        vm.doneAdding()
        assertNull(vm.state.value.adding)
        assertNull(vm.state.value.added)
    }

    @Test fun `Add a server on a TV ends on the new server's Home with the note`() = runTest {
        val vm = model(addedStep = false)
        runCurrent()
        vm.startAdding()
        servers.select(OfficeMini)
        runCurrent()
        auth.signIn("Sam", "password")
        runCurrent()
        assertNull(vm.state.value.adding)
        assertEquals("Now on Office Mac mini", vm.state.value.note)
    }

    @Test fun `closing Add a server before choosing changes nothing`() = runTest {
        val vm = model()
        runCurrent()
        vm.startAdding()
        vm.doneAdding()
        assertNull(vm.state.value.adding)
        assertEquals(LivingRoom.id, current)
    }

    @Test fun `Cancel on the sign-in of a server being added leaves the app where it was and doesn't keep the server`() = runTest {
        val vm = model()
        runCurrent()
        vm.startAdding()
        servers.select(OfficeMini)
        runCurrent()
        vm.cancelSignIn()
        runCurrent()
        assertEquals(LivingRoom.id, current)
        assertNull(vm.state.value.adding)
        assertNull(vm.state.value.note)
        assertFalse("never signed in to, so not kept", servers.saved.first().any { it.server.id == OfficeMini.id })
    }

    @Test fun `a search result on another server switches without asking it again, then lands on Search and the title`() = runTest {
        val vm = model()
        runCurrent()
        vm.openFound(Cabin.id, "harbor", DeepLink.Movie(800))
        runCurrent()
        assertEquals(Cabin.id, current)
        assertEquals("it has just answered the search", listOf(Cabin.id to false), servers.switches)
        assertEquals(ServerSwitchViewModel.Landing("harbor", DeepLink.Movie(800)), vm.state.value.landing)
        assertEquals("Now on Cabin server", vm.state.value.note)
        vm.landingShown()
        assertNull(vm.state.value.landing)
    }

    @Test fun `a result on a server whose sign-in has ended opens nothing but its sign-in`() = runTest {
        val vm = model()
        runCurrent()
        vm.openFound(RemoteHome.id, "harbor", DeepLink.Show(700))
        runCurrent()
        assertEquals(RemoteHome.id, current)
        assertNull(vm.state.value.landing)
        assertEquals("Cancel goes back", LivingRoom.id, vm.state.value.returnTo)
    }

    @Test fun `a link for another registered account switches to its server first`() = runTest {
        val vm = model()
        runCurrent()
        val cabin = servers.saved.first().first { it.server.id == Cabin.id }
        val link = DeepLink.newEpisode(showId = 700, season = 2, episodeId = 700023, account = cabin.linkAccount()!!)
        assertTrue(vm.arrive(link))
        runCurrent()
        assertEquals(Cabin.id, current)
        assertEquals("asked first, as any switch", listOf(Cabin.id to true), servers.switches)
        assertEquals("Now on Cabin server", vm.state.value.note)
        // It opens there: the account in use is the one it names now.
        val account = watchNextAccount(servers.current.value?.server, auth.user.value)
        assertEquals(DeepLink.Show(700, 2, 700023), DeepLink.open(link, account))
    }

    @Test fun `a link for the account in use, for none, or for one not on this device switches nothing`() = runTest {
        val vm = model()
        runCurrent()
        val mine = watchNextAccount(servers.current.value?.server, auth.user.value)!!
        assertTrue(vm.arrive(DeepLink.home(mine)))
        assertTrue(vm.arrive("watchmymedia://movies/500"))
        assertTrue(vm.arrive(DeepLink.home("someone-else")))
        assertTrue(vm.arrive(null))
        assertTrue(servers.switches.isEmpty())
        assertEquals(LivingRoom.id, current)
        assertNull("one for an account that isn't here still opens nothing", DeepLink.open(DeepLink.home("someone-else"), mine))
    }

    @Test fun `a link for a server that doesn't answer leaves the app where it was and says so`() = runTest {
        val vm = model()
        runCurrent()
        val den = servers.saved.first().first { it.server.id == Den.id }
        assertFalse(vm.arrive(DeepLink.home(den.linkAccount()!!)))
        runCurrent()
        assertEquals(LivingRoom.id, current)
        assertEquals("Couldn’t reach Den server. You’re still on Living room server.", vm.state.value.note)
    }
}
