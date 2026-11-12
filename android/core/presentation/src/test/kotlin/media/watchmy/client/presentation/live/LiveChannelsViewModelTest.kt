package media.watchmy.client.presentation.live

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import media.watchmy.client.fake.FakeAuthRepository
import media.watchmy.client.fake.FakeServerRepository
import media.watchmy.client.fake.LivingRoom
import media.watchmy.client.fake.OfficeMini
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.LiveCapabilities
import media.watchmy.client.model.LiveChannel
import media.watchmy.client.model.LiveRepository
import media.watchmy.client.presentation.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LiveChannelsViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val servers = FakeServerRepository()
    private val auth = FakeAuthRepository()
    private val channel = LiveChannel("harbor", "fixture", "2.1", "HRBR-HD")
    private val weather = LiveChannel("weather", "fixture", "2.2", "Harbor Weather")
    private val enabled = LiveCapabilities(enabled = true, allowed = true, playback = true)
    private val live = TestLive()

    private inner class TestLive : LiveRepository {
        var capabilitiesCalls = 0
        var channelCalls = 0
        var capabilities = enabled
        var channels = listOf(channel, weather)
        var failure: ApiException? = null
        var pending: CompletableDeferred<List<LiveChannel>>? = null
        override suspend fun capabilities(): LiveCapabilities {
            capabilitiesCalls++
            failure?.let { throw it }
            return capabilities
        }
        override suspend fun channels(): List<LiveChannel> {
            channelCalls++
            val wait = pending
            return if (wait == null) channels else withContext(NonCancellable) { wait.await() }
        }
    }

    private fun model() = LiveChannelsViewModel(live, servers, auth)

    @Test
    fun `older servers never request live routes and preserve hidden navigation`() = runTest(main.dispatcher) {
        servers.select(LivingRoom.copy(apiLevel = 10))
        val vm = model()
        advanceUntilIdle()
        assertFalse(vm.state.value.supported)
        assertFalse(vm.state.value.available)
        assertEquals(0, live.capabilitiesCalls)
        vm.refresh()
        advanceUntilIdle()
        assertEquals(0, live.capabilitiesCalls)
    }

    @Test
    fun `all capability flags must allow playback before channels load`() = runTest(main.dispatcher) {
        servers.select(LivingRoom.copy(apiLevel = 11))
        live.capabilities = enabled.copy(allowed = false)
        val vm = model()
        advanceUntilIdle()
        assertFalse(vm.state.value.available)
        assertEquals(0, live.channelCalls)
        live.capabilities = enabled.copy(playback = false)
        vm.refresh()
        advanceUntilIdle()
        assertFalse(vm.state.value.available)
        assertEquals(0, live.channelCalls)
        live.capabilities = enabled
        vm.refresh()
        advanceUntilIdle()
        assertTrue(vm.state.value.available)
        assertEquals(listOf(channel, weather), vm.state.value.channels)
    }

    @Test
    fun `channel browsing works without listings and filters names or subchannel numbers`() = runTest(main.dispatcher) {
        servers.select(LivingRoom.copy(apiLevel = 11))
        val vm = model()
        advanceUntilIdle()
        vm.search(" 2.2 ")
        assertEquals(listOf(weather), vm.state.value.visibleChannels)
        vm.search("WEATHER")
        assertEquals(listOf(weather), vm.state.value.visibleChannels)
        vm.select(weather)
        assertEquals(weather, vm.state.value.selected)
        vm.search("missing")
        assertTrue(vm.state.value.visibleChannels.isEmpty())
    }

    @Test
    fun `revocation clears channels and selection on refresh`() = runTest(main.dispatcher) {
        servers.select(LivingRoom.copy(apiLevel = 11))
        val vm = model()
        advanceUntilIdle()
        vm.select(weather)
        live.capabilities = enabled.copy(allowed = false)
        vm.refresh()
        advanceUntilIdle()
        assertFalse(vm.state.value.available)
        assertTrue(vm.state.value.channels.isEmpty())
        assertNull(vm.state.value.selected)
    }

    @Test
    fun `server change ignores an old response even when its request ignores cancellation`() = runTest(main.dispatcher) {
        servers.select(LivingRoom.copy(apiLevel = 11))
        val old = CompletableDeferred<List<LiveChannel>>()
        live.pending = old
        val vm = model()
        runCurrent()
        live.pending = null
        live.channels = listOf(weather)
        servers.select(OfficeMini.copy(apiLevel = 11))
        runCurrent()
        assertEquals(listOf(weather), vm.state.value.channels)
        old.complete(listOf(channel))
        advanceUntilIdle()
        assertEquals(listOf(weather), vm.state.value.channels)
    }

    @Test
    fun `signout clears capability and late channel response cannot reopen it`() = runTest(main.dispatcher) {
        servers.select(LivingRoom.copy(apiLevel = 11))
        val old = CompletableDeferred<List<LiveChannel>>()
        live.pending = old
        val vm = model()
        runCurrent()
        auth.signOut()
        runCurrent()
        assertFalse(vm.state.value.available)
        assertTrue(vm.state.value.channels.isEmpty())
        old.complete(listOf(channel))
        advanceUntilIdle()
        assertFalse(vm.state.value.available)
        assertTrue(vm.state.value.channels.isEmpty())
    }

    @Test
    fun `changing account reloads permissions even when the server is unchanged`() = runTest(main.dispatcher) {
        servers.select(LivingRoom.copy(apiLevel = 11))
        val vm = model()
        advanceUntilIdle()
        live.capabilities = enabled.copy(allowed = false)
        auth.signIn("Alex", "password")
        advanceUntilIdle()
        assertFalse(vm.state.value.available)
        assertEquals(2, live.capabilitiesCalls)
    }

    @Test
    fun `failed refresh preserves current channels and retry clears the error`() = runTest(main.dispatcher) {
        servers.select(LivingRoom.copy(apiLevel = 11))
        val vm = model()
        advanceUntilIdle()
        live.failure = ApiException(null, "Couldn't reach the server.")
        vm.refresh()
        advanceUntilIdle()
        assertEquals("Couldn't reach the server.", vm.state.value.error)
        assertEquals(listOf(channel, weather), vm.state.value.channels)
        live.failure = null
        vm.refresh()
        advanceUntilIdle()
        assertNull(vm.state.value.error)
        assertFalse(vm.state.value.loading)
    }
}
