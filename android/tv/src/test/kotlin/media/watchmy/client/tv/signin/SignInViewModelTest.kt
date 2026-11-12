package media.watchmy.client.tv.signin

import androidx.lifecycle.viewModelScope
import app.cash.turbine.test
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
import media.watchmy.client.tv.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SignInViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    @Test
    fun `shows the code with the link and a countdown, then signs in when approved`() = runTest(main.dispatcher) {
        val auth = FakeAuthRepository(signedIn = false)
        val vm = SignInViewModel(auth, FakeServerRepository(signedIn = false))
        runCurrent()
        val waiting = vm.pairing.value as SignInViewModel.Pairing.Waiting
        assertEquals("KQ7F2W", waiting.code)
        assertEquals("192.168.1.20:27587/link", waiting.linkLabel)
        assertTrue(waiting.remainingMs in 9 * 60_000L..10 * 60_000L)

        advanceTimeBy(8_001)
        assertEquals(SignInViewModel.Pairing.Approved, vm.pairing.value)
        assertEquals("Sam", auth.user.value?.name)
    }

    @Test
    fun `an expired code is replaced by a new one`() = runTest(main.dispatcher) {
        val codes = ArrayDeque(listOf("AAABBB", "CCCDDD"))
        val auth = object : AuthRepository by FakeAuthRepository(false) {
            override fun pair(): Flow<PairingState> = flow {
                val code = codes.removeFirst()
                emit(PairingState.Waiting(PairingCode(code, testScheduler.currentTime + 3_000, "http://srv/link?code=$code", 1_000)))
                delay(3_000)
                if (codes.isNotEmpty()) emit(PairingState.Expired) else delay(60_000)
            }
        }
        val vm = SignInViewModel(auth, FakeServerRepository(false), now = { testScheduler.currentTime })
        vm.pairing.test {
            assertEquals(SignInViewModel.Pairing.Starting, awaitItem())
            val first = awaitItem() as SignInViewModel.Pairing.Waiting
            assertEquals("AAABBB", first.code)
            assertEquals("0:03", countdownLabel(first.remainingMs))
            assertEquals(2_000L, (awaitItem() as SignInViewModel.Pairing.Waiting).remainingMs)
            assertEquals(1_000L, (awaitItem() as SignInViewModel.Pairing.Waiting).remainingMs)
            // Then (perhaps 0:00, as the expiry and the last tick land together) a new code.
            var sawStarting = false
            var item = awaitItem()
            while (item !is SignInViewModel.Pairing.Waiting || item.code != "CCCDDD") {
                if (item == SignInViewModel.Pairing.Starting) sawStarting = true
                item = awaitItem()
            }
            assertTrue(sawStarting)
            cancelAndIgnoreRemainingEvents()
        }
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
        val vm = SignInViewModel(auth, FakeServerRepository(false), now = { 0L })
        runCurrent()
        assertEquals(SignInViewModel.Pairing.Failed("The server is starting up."), vm.pairing.value)
        vm.startPairing()
        runCurrent()
        assertEquals("KQ7F2W", (vm.pairing.value as SignInViewModel.Pairing.Waiting).code)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `password sign-in checks the fields, shows a wrong password, then signs in`() = runTest(main.dispatcher) {
        val auth = FakeAuthRepository(signedIn = false)
        val vm = SignInViewModel(auth, FakeServerRepository(signedIn = false))
        vm.stopPairing()
        assertEquals("Sam", vm.form.value.name)

        vm.setName("  ")
        vm.signIn()
        assertEquals("Enter your name.", vm.form.value.error)

        vm.setName("Sam")
        vm.signIn()
        assertEquals("Enter your password.", vm.form.value.error)

        vm.setPassword("nope")
        vm.signIn()
        assertTrue(vm.form.value.busy)
        advanceUntilIdle()
        assertEquals("Wrong name or password.", vm.form.value.error)
        assertNull(auth.user.value)

        vm.setPassword("password")
        assertNull(vm.form.value.error)
        vm.signIn()
        advanceUntilIdle()
        assertNotNull(auth.user.value)
        assertEquals("", vm.form.value.password)
    }

    @Test
    fun `countdown labels round up to the whole second`() {
        assertEquals("4:52", countdownLabel(292_000))
        assertEquals("4:52", countdownLabel(291_001))
        assertEquals("0:00", countdownLabel(0))
        assertEquals("10:00", countdownLabel(600_000))
    }
}
