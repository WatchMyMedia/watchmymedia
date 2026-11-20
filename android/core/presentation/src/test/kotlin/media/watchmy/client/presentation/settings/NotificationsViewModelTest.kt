package media.watchmy.client.presentation.settings

import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import media.watchmy.client.fake.FakeFollowsRepository
import media.watchmy.client.model.FOLLOWS_API_LEVEL
import media.watchmy.client.model.FollowLatest
import media.watchmy.client.model.FollowedShow
import media.watchmy.client.model.FollowsRepository
import media.watchmy.client.model.NewEpisodes
import media.watchmy.client.model.ApiException
import media.watchmy.client.presentation.Load
import media.watchmy.client.presentation.MainDispatcherRule
import media.watchmy.client.presentation.value
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Settings › Notifications: the list, Stop notifying and this device's switch. */
class NotificationsViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val follows = FakeFollowsRepository(level = { FOLLOWS_API_LEVEL }, latencyMs = { latency })
    private var latency = 0L

    @Test
    fun `the list is read newest first, a show that left the library with it`() = runTest(main.dispatcher) {
        val vm = NotificationsViewModel(follows)
        advanceUntilIdle()
        val list = vm.state.value.list.value!!
        assertEquals(listOf("Harbor Lights", "Copperline", "Ashgrove"), list.map { it.title })
        assertTrue(list.last().gone)
        assertEquals(3, vm.state.value.count)
    }

    @Test
    fun `Stop notifying takes the row away at once`() = runTest(main.dispatcher) {
        val vm = NotificationsViewModel(follows)
        advanceUntilIdle()
        val copperline = vm.state.value.list.value!![1]
        latency = 1_000
        vm.stop(copperline)
        runCurrent()
        assertEquals("before the server answers", listOf("Harbor Lights", "Ashgrove"), vm.state.value.list.value!!.map { it.title })
        advanceUntilIdle()
        assertEquals(listOf("Harbor Lights", "Ashgrove"), vm.state.value.list.value!!.map { it.title })
        assertFalse(follows.following(105))
    }

    @Test
    fun `a refused stop brings the row back with its failure until Try again`() = runTest(main.dispatcher) {
        val vm = NotificationsViewModel(follows)
        advanceUntilIdle()
        val copperline = vm.state.value.list.value!![1]
        follows.refuse = 500
        vm.stop(copperline)
        advanceUntilIdle()
        assertEquals(3, vm.state.value.count)
        assertEquals(setOf(copperline.tmdbId), vm.state.value.refused)
        follows.refuse = null
        vm.stop(copperline)
        advanceUntilIdle()
        assertEquals(emptySet<Long>(), vm.state.value.refused)
        assertEquals(2, vm.state.value.count)
    }

    @Test
    fun `a list that didn't load says why, with the server's sentence when it gave one`() = runTest(main.dispatcher) {
        val failing = object : FollowsRepository by follows {
            override suspend fun refresh(): List<FollowedShow> = throw ApiException(500, "The database is busy")
            override suspend fun newEpisodes(after: String?): NewEpisodes = throw UnsupportedOperationException()
        }
        val vm = NotificationsViewModel(failing)
        advanceUntilIdle()
        assertEquals(Load.Failed("The database is busy. Check your connection and try again."), vm.state.value.list)
    }

    @Test
    fun `the switch is this device's`() = runTest(main.dispatcher) {
        val vm = NotificationsViewModel(follows)
        advanceUntilIdle()
        assertTrue(vm.state.value.notifyHere)
        vm.setNotifyHere(false)
        advanceUntilIdle()
        assertFalse(vm.state.value.notifyHere)
        assertFalse(follows.notifyHere.value)
    }

    @Test
    fun `another device's change reads the list again`() = runTest(main.dispatcher) {
        val vm = NotificationsViewModel(follows)
        advanceUntilIdle()
        follows.changedElsewhere(101, following = true)
        advanceUntilIdle()
        assertEquals("The Quiet Meridian", vm.state.value.list.value!!.first().title)
    }

    @Test
    fun `a new episode updates the open page's lines on library changed`() = runTest(main.dispatcher) {
        val vm = NotificationsViewModel(follows)
        advanceUntilIdle()
        vm.setShown(true)
        advanceUntilIdle()
        follows.arrived(100, 3, 8, "Squall Line")
        advanceUntilIdle()
        assertEquals(FollowLatest(3, 8, "Squall Line", vm.state.value.list.value!![0].latest?.arrivedAtMs), vm.state.value.list.value!![0].latest)
    }

    @Test
    fun `reopening the page reads the list again`() = runTest(main.dispatcher) {
        var reads = 0
        val counting = object : FollowsRepository by follows {
            override suspend fun refresh(): List<FollowedShow> = this@NotificationsViewModelTest.follows.refresh().also { reads++ }
        }
        val vm = NotificationsViewModel(counting)
        advanceUntilIdle()
        val first = reads
        vm.setShown(true)
        advanceUntilIdle()
        vm.setShown(false)
        follows.arrived(100, 3, 8)
        advanceUntilIdle()
        assertEquals("opened once, closed for the change", first + 1, reads)
        vm.setShown(true)
        advanceUntilIdle()
        assertEquals("opened again: read again", first + 2, reads)
    }

    @Test
    fun `an account change while it reads doesn't stop it reading for later changes`() = runTest(main.dispatcher) {
        // The data layer throws a CancellationException of its own when the account changed while it asked.
        var cancelOnce = true
        var reads = 0
        val flaky = object : FollowsRepository by follows {
            override suspend fun refresh(): List<FollowedShow> {
                reads++
                if (cancelOnce) {
                    cancelOnce = false
                    throw kotlinx.coroutines.CancellationException("The signed-in account changed.")
                }
                return this@NotificationsViewModelTest.follows.refresh()
            }
        }
        val vm = NotificationsViewModel(flaky)
        advanceUntilIdle()
        follows.changedElsewhere(101, following = true)
        advanceUntilIdle()
        assertEquals(2, reads)
        follows.changedElsewhere(103, following = true)
        advanceUntilIdle()
        assertEquals("still listening", 3, reads)
        assertEquals("Northfield Precinct", vm.state.value.list.value!!.first().title)
    }
}
