package media.watchmy.client.presentation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class LoadTest {
    @get:Rule val main = MainDispatcherRule()

    @Test
    fun `an older load that answers last doesn't land over a newer one`() = runTest(main.dispatcher) {
        val loads = Latest(this)
        val slow = CompletableDeferred<String>()
        var shown: String? = null
        loads.launch { shown = slow.await() }
        advanceUntilIdle()
        loads.launch { shown = "newer" }
        advanceUntilIdle()
        slow.complete("older")
        advanceUntilIdle()
        assertEquals("newer", shown)
    }

    @Test
    fun `value is the data once ready`() {
        assertEquals(3, Load.Ready(3).value)
        assertNull(Load.Loading.value)
        assertNull(Load.Failed("No").value)
    }
}
