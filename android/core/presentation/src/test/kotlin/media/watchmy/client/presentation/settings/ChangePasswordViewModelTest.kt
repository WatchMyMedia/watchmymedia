package media.watchmy.client.presentation.settings

import app.cash.turbine.test
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import media.watchmy.client.fake.FakeAuthRepository
import media.watchmy.client.fake.FakeServerRepository
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.AuthRepository
import media.watchmy.client.model.DeviceSession
import media.watchmy.client.model.SignedOutException
import media.watchmy.client.presentation.Load
import media.watchmy.client.presentation.MainDispatcherRule
import media.watchmy.client.presentation.value
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Change password (SettingsPassword.dc.html): one test per message on the board, against the fakes. */
class ChangePasswordViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    /** The fake sign-in ("password" is the current password), with the calls recorded and failures on demand. */
    private class StubAuth(val fake: FakeAuthRepository = FakeAuthRepository()) : AuthRepository by fake {
        val changes = mutableListOf<Pair<String, String>>()
        var sessionCalls = 0
        var changeFailure: Exception? = null
        var sessionsFailure: Exception? = null
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun sessions(all: Boolean): List<DeviceSession> {
            sessionCalls++
            sessionsFailure?.let { throw it }
            return fake.sessions(all)
        }

        override suspend fun changePassword(current: String, new: String) {
            changes += current to new
            gate?.await()
            changeFailure?.let { throw it }
            fake.changePassword(current, new)
        }
    }

    private fun vm(auth: AuthRepository = StubAuth()) = ChangePasswordViewModel(auth, FakeServerRepository())

    private fun ChangePasswordViewModel.type(current: String, new: String) {
        setCurrent(current)
        setNew(new)
    }

    @Test
    fun `lists the other devices, the most recent first, and counts them on the button`() = runTest(main.dispatcher) {
        val vm = vm()
        assertEquals(Load.Loading, vm.state.value.others)
        assertEquals("Change password", vm.state.value.button)
        assertEquals("Signs out your other devices", changePasswordSignsOut(vm.state.value.signsOut))
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals(listOf("Sam's Pixel", "Chrome on macOS"), s.others.value?.map { it.name })
        assertEquals(2, s.signsOut)
        assertEquals("Change password and sign out 2 devices", s.button)
        assertEquals("Signs out 2 other devices", changePasswordSignsOut(s.signsOut))
    }

    @Test
    fun `the button and heading word one device and none`() {
        assertEquals("Change password", changePasswordButton(0))
        assertEquals("Change password and sign out 1 device", changePasswordButton(1))
        assertEquals("Change password and sign out 3 devices", changePasswordButton(3))
        assertEquals("Other devices", changePasswordSignsOut(0))
        assertEquals("Signs out 1 other device", changePasswordSignsOut(1))
        assertEquals("This tablet stays signed in. Your other devices are signed out and will ask for the new password.", changePasswordLede("tablet"))
    }

    @Test
    fun `the button is off until both fields have something`() = runTest(main.dispatcher) {
        val auth = StubAuth()
        val vm = vm(auth)
        advanceUntilIdle()
        assertFalse(vm.state.value.canSubmit)
        vm.setCurrent("password")
        assertFalse(vm.state.value.canSubmit)
        vm.submit()
        advanceUntilIdle()
        assertTrue("nothing sent", auth.changes.isEmpty())
        vm.setNew("lighthouse-2026")
        assertTrue(vm.state.value.canSubmit)
    }

    @Test
    fun `a changed password reports how many other devices it signed out`() = runTest(main.dispatcher) {
        val auth = StubAuth()
        val vm = vm(auth)
        advanceUntilIdle()
        vm.type("password", "lighthouse-2026")
        vm.events.test {
            vm.submit()
            assertTrue(vm.state.value.working)
            advanceUntilIdle()
            assertEquals(ChangePasswordViewModel.Event.Changed(2), awaitItem())
        }
        assertEquals(listOf("password" to "lighthouse-2026"), auth.changes)
        assertEquals("the fake signed the others out", listOf(true), auth.fake.sessions().map { it.current })
        val s = vm.state.value
        assertTrue(s.changed)
        assertFalse(s.working)
        assertEquals("nothing typed is kept", "", s.current + s.new)
        assertNotNull("this device is still signed in", auth.user.value)
    }

    @Test
    fun `Changing… while it goes, and a second press does nothing`() = runTest(main.dispatcher) {
        val auth = StubAuth().apply { gate = CompletableDeferred() }
        val vm = vm(auth)
        advanceUntilIdle()
        vm.type("password", "lighthouse-2026")
        vm.submit()
        advanceUntilIdle()
        assertEquals("Changing…", vm.state.value.button)
        assertFalse(vm.state.value.canSubmit)
        vm.submit()
        advanceUntilIdle()
        assertEquals(1, auth.changes.size)
        auth.gate!!.complete(Unit)
        advanceUntilIdle()
        assertTrue(vm.state.value.changed)
    }

    @Test
    fun `a wrong current password says so under it and leaves this device signed in`() = runTest(main.dispatcher) {
        val auth = StubAuth()
        val vm = vm(auth)
        advanceUntilIdle()
        vm.type("harbour-12", "lighthouse-2026")
        vm.submit()
        advanceUntilIdle()
        val refusal = vm.state.value.refusal
        assertEquals(ChangePasswordRefusal.WrongCurrent, refusal)
        assertEquals(ChangePasswordRefusal.Place.Current, refusal?.place)
        assertEquals("That password is wrong. Nothing was changed.", refusal?.message)
        assertNotNull(auth.user.value)
        assertTrue("the button stays on", vm.state.value.canSubmit)
        vm.setNew("lighthouse-2027")
        assertEquals("a change to the other field keeps it", ChangePasswordRefusal.WrongCurrent, vm.state.value.refusal)
        vm.setCurrent("password")
        assertNull(vm.state.value.refusal)
    }

    @Test
    fun `a short new password says so under it, sends nothing and holds the button until it changes`() = runTest(main.dispatcher) {
        val auth = StubAuth()
        val vm = vm(auth)
        advanceUntilIdle()
        vm.type("password", "short")
        vm.submit()
        advanceUntilIdle()
        val refusal = vm.state.value.refusal
        assertEquals(ChangePasswordRefusal.TooShort, refusal)
        assertEquals(ChangePasswordRefusal.Place.New, refusal?.place)
        assertEquals("Use at least 8 characters.", refusal?.message)
        assertTrue(auth.changes.isEmpty())
        assertFalse(vm.state.value.canSubmit)
        vm.setNew("shorter-still-long")
        assertNull(vm.state.value.refusal)
        assertTrue(vm.state.value.canSubmit)
    }

    @Test
    fun `a new password over 256 characters says so under it`() = runTest(main.dispatcher) {
        val auth = StubAuth()
        val vm = vm(auth)
        advanceUntilIdle()
        vm.type("password", "x".repeat(257))
        vm.submit()
        advanceUntilIdle()
        assertEquals("Use at most 256 characters.", vm.state.value.refusal?.message)
        assertTrue(auth.changes.isEmpty())
        vm.setNew("x".repeat(256))
        vm.submit()
        advanceUntilIdle()
        assertEquals(1, auth.changes.size)
    }

    @Test
    fun `length is counted in characters, as the server counts it`() = runTest(main.dispatcher) {
        val auth = StubAuth()
        val vm = vm(auth)
        advanceUntilIdle()
        // Seven emoji are fourteen UTF-16 units but seven characters.
        vm.type("password", "🔑".repeat(7))
        vm.submit()
        advanceUntilIdle()
        assertEquals(ChangePasswordRefusal.TooShort, vm.state.value.refusal)
        vm.setNew("🔑".repeat(8))
        vm.submit()
        advanceUntilIdle()
        assertEquals(1, auth.changes.size)
    }

    @Test
    fun `too many tries says so above the button and holds it off until a field changes`() = runTest(main.dispatcher) {
        val auth = StubAuth().apply { changeFailure = ApiException(429, "Too many attempts; try again later.") }
        val vm = vm(auth)
        advanceUntilIdle()
        vm.type("password", "lighthouse-2026")
        vm.submit()
        advanceUntilIdle()
        val refusal = vm.state.value.refusal
        assertEquals(ChangePasswordRefusal.TooManyTries, refusal)
        assertEquals(ChangePasswordRefusal.Place.Button, refusal?.place)
        assertEquals("Too many tries. Nothing was changed. Try again in a few minutes.", refusal?.spoken)
        assertFalse(vm.state.value.canSubmit)
        vm.setCurrent("password!")
        assertTrue(vm.state.value.canSubmit)
    }

    @Test
    fun `an unreachable server is named above the button, which says Try again`() = runTest(main.dispatcher) {
        val auth = StubAuth().apply { changeFailure = ApiException(null, "Couldn't reach the server.", IOException("timeout")) }
        val vm = vm(auth)
        advanceUntilIdle()
        vm.type("password", "lighthouse-2026")
        vm.submit()
        advanceUntilIdle()
        val refusal = vm.state.value.refusal
        assertEquals(ChangePasswordRefusal.Unreachable("Living room server"), refusal)
        assertEquals(
            "Couldn’t reach Living room server. Your password wasn’t changed. Check your connection, then try again.",
            refusal?.spoken,
        )
        assertEquals("Try again", vm.state.value.button)
        assertTrue(vm.state.value.canSubmit)
        auth.changeFailure = null
        vm.submit()
        assertNull("trying again puts it away", vm.state.value.refusal)
        advanceUntilIdle()
        assertTrue(vm.state.value.changed)
    }

    @Test
    fun `anything else is the server's sentence above the button`() = runTest(main.dispatcher) {
        val auth = StubAuth().apply { changeFailure = ApiException(503, "The server is busy. Try again in a moment.") }
        val vm = vm(auth)
        advanceUntilIdle()
        vm.type("password", "lighthouse-2026")
        vm.submit()
        advanceUntilIdle()
        val refusal = vm.state.value.refusal
        assertEquals(ChangePasswordRefusal.Place.Button, refusal?.place)
        assertEquals("Your password wasn’t changed", refusal?.title)
        assertEquals("The server is busy. Try again in a moment.", refusal?.message)
        assertEquals("Change password and sign out 2 devices", vm.state.value.button)
    }

    @Test
    fun `a sign-in that ended is not a wrong password`() = runTest(main.dispatcher) {
        val auth = StubAuth().apply { changeFailure = SignedOutException() }
        val vm = vm(auth)
        advanceUntilIdle()
        vm.type("password", "lighthouse-2026")
        vm.submit()
        advanceUntilIdle()
        assertEquals(ChangePasswordRefusal.SignedOut, vm.state.value.refusal)
    }

    @Test
    fun `a list that didn't load says why, and loads again`() = runTest(main.dispatcher) {
        val auth = StubAuth().apply { sessionsFailure = ApiException(500, "Couldn't read the devices.") }
        val vm = vm(auth)
        advanceUntilIdle()
        assertEquals(Load.Failed("Couldn't read the devices."), vm.state.value.others)
        assertEquals("Change password", vm.state.value.button)
        auth.sessionsFailure = null
        vm.loadOthers()
        advanceUntilIdle()
        assertEquals(2, vm.state.value.signsOut)
    }

    @Test
    fun `devices not yet listed are listed before the change, so the count is right`() = runTest(main.dispatcher) {
        val auth = StubAuth().apply { sessionsFailure = ApiException(500, "Couldn't read the devices.") }
        val vm = vm(auth)
        advanceUntilIdle()
        auth.sessionsFailure = null
        vm.type("password", "lighthouse-2026")
        vm.events.test {
            vm.submit()
            advanceUntilIdle()
            assertEquals(ChangePasswordViewModel.Event.Changed(2), awaitItem())
        }
        assertEquals(2, auth.sessionCalls)
    }

    @Test
    fun `a list that can't be had stops the change before anything is sent`() = runTest(main.dispatcher) {
        val auth = StubAuth().apply { sessionsFailure = ApiException(null, "Couldn't reach the server.", IOException("offline")) }
        val vm = vm(auth)
        advanceUntilIdle()
        vm.type("password", "lighthouse-2026")
        vm.submit()
        advanceUntilIdle()
        assertEquals(ChangePasswordRefusal.Unreachable("Living room server"), vm.state.value.refusal)
        assertTrue(auth.changes.isEmpty())
        assertFalse(vm.state.value.working)
    }

    @Test
    fun `closing with nothing typed just closes`() = runTest(main.dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.events.test {
            vm.requestClose()
            assertEquals(ChangePasswordViewModel.Event.Close, awaitItem())
        }
        assertFalse(vm.state.value.leaving)
    }

    @Test
    fun `closing with something typed asks first, and Keep editing keeps it`() = runTest(main.dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.setNew("light")
        vm.events.test {
            vm.requestClose()
            assertTrue(vm.state.value.leaving)
            expectNoEvents()
            vm.keepEditing()
            assertFalse(vm.state.value.leaving)
            assertEquals("light", vm.state.value.new)
            vm.requestClose()
            vm.leave()
            assertFalse(vm.state.value.leaving)
            assertEquals(ChangePasswordViewModel.Event.Close, awaitItem())
        }
    }

    @Test
    fun `after the change, closing doesn't ask`() = runTest(main.dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.type("password", "lighthouse-2026")
        vm.events.test {
            vm.submit()
            advanceUntilIdle()
            assertEquals(ChangePasswordViewModel.Event.Changed(2), awaitItem())
            vm.requestClose()
            assertEquals(ChangePasswordViewModel.Event.Close, awaitItem())
        }
    }

    @Test
    fun `the eyes show each password on its own`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.toggleShowNew()
        assertTrue(vm.state.value.showNew)
        assertFalse(vm.state.value.showCurrent)
        vm.toggleShowCurrent()
        vm.toggleShowNew()
        assertTrue(vm.state.value.showCurrent)
        assertFalse(vm.state.value.showNew)
    }
}
