package media.watchmy.client.presentation.signin

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import media.watchmy.client.fake.FakeAuthRepository
import media.watchmy.client.fake.FakeServerRepository
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.AuthRepository
import media.watchmy.client.model.PairingCode
import media.watchmy.client.model.PairingState
import media.watchmy.client.presentation.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The TV's sign-in: the code first, renewed as each runs out, and the password form as the other way. */
class TvSignInTest {
    @get:Rule val main = MainDispatcherRule()

    @Test
    fun `shows the code with a countdown, then signs in when approved`() = runTest(main.dispatcher) {
        val auth = FakeAuthRepository(signedIn = false)
        val vm = SignInViewModel(auth, FakeServerRepository(signedIn = false), codeFirst = true)
        runCurrent()
        assertTrue(vm.state.value.codeSignIn)
        val waiting = vm.state.value.pairing as PairingState.Waiting
        assertEquals("KQ7F2W", waiting.code.code)
        assertTrue(vm.state.value.codeRemainingMs in 9 * 60_000L..10 * 60_000L)

        advanceTimeBy(8_001)
        assertTrue(vm.state.value.pairing is PairingState.Approved)
        assertEquals("Sam", auth.user.value?.name)
    }

    @Test
    fun `an expired code is replaced by a new one, counting down the seconds`() = runTest(main.dispatcher) {
        val codes = ArrayDeque(listOf("AAABBB", "CCCDDD"))
        val auth = object : AuthRepository by FakeAuthRepository(false) {
            override fun pair(): Flow<PairingState> = flow {
                val code = codes.removeFirst()
                emit(PairingState.Waiting(PairingCode(code, testScheduler.currentTime + 3_000, "http://srv/link?code=$code", 1_000)))
                delay(3_000)
                if (codes.isNotEmpty()) emit(PairingState.Expired) else delay(60_000)
            }
        }
        val vm = SignInViewModel(auth, FakeServerRepository(false), codeFirst = true, now = { testScheduler.currentTime })
        runCurrent()
        assertEquals("AAABBB", (vm.state.value.pairing as PairingState.Waiting).code.code)
        assertEquals("0:03", countdownLabel(vm.state.value.codeRemainingMs))
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2_000L, vm.state.value.codeRemainingMs)
        advanceTimeBy(2_500)
        runCurrent()
        assertEquals("CCCDDD", (vm.state.value.pairing as PairingState.Waiting).code.code)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `a failed pairing shows the message and Try again starts over`() = runTest(main.dispatcher) {
        var calls = 0
        val auth = object : AuthRepository by FakeAuthRepository(false) {
            override fun pair(): Flow<PairingState> = flow {
                calls++
                if (calls == 1) throw ApiException(503, "The server is starting up.")
                emit(PairingState.Waiting(PairingCode("KQ7F2W", Long.MAX_VALUE, "http://srv/link", 1_000)))
                awaitCancellation()
            }
        }
        val vm = SignInViewModel(auth, FakeServerRepository(false), codeFirst = true, now = { 0L })
        runCurrent()
        assertEquals(PairingState.Failed("The server is starting up."), vm.state.value.pairing)
        vm.newCode()
        runCurrent()
        assertEquals("KQ7F2W", (vm.state.value.pairing as PairingState.Waiting).code.code)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `the password form stops the code, checks the fields, shows a wrong password, then signs in`() = runTest(main.dispatcher) {
        val auth = FakeAuthRepository(signedIn = false)
        val vm = SignInViewModel(auth, FakeServerRepository(signedIn = false), codeFirst = true)
        runCurrent()
        vm.usePassword()
        assertFalse(vm.state.value.codeSignIn)
        assertNull(vm.state.value.pairing)

        vm.setName("Sam")
        vm.signIn()
        assertEquals("Enter your name and password.", vm.state.value.error)

        vm.setPassword("nope")
        vm.signIn()
        assertTrue(vm.state.value.busy)
        advanceUntilIdle()
        assertEquals("Wrong name or password.", vm.state.value.error)
        assertNull(auth.user.value)

        vm.setPassword("password")
        assertNull(vm.state.value.error)
        vm.signIn()
        advanceUntilIdle()
        assertNotNull(auth.user.value)
        assertEquals("", vm.state.value.password)
    }

    @Test
    fun `back from the form shows a code again`() = runTest(main.dispatcher) {
        val vm = SignInViewModel(FakeAuthRepository(signedIn = false), FakeServerRepository(signedIn = false), codeFirst = true)
        runCurrent()
        vm.usePassword()
        vm.useCode()
        runCurrent()
        assertTrue(vm.state.value.codeSignIn)
        assertTrue(vm.state.value.pairing is PairingState.Waiting)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `countdown labels round up to the whole second`() {
        assertEquals("4:52", countdownLabel(292_000))
        assertEquals("4:52", countdownLabel(291_001))
        assertEquals("0:00", countdownLabel(0))
        assertEquals("10:00", countdownLabel(600_000))
    }
}
