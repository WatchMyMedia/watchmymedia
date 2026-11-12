package media.watchmy.client.presentation.live

import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import media.watchmy.client.fake.FakeAuthRepository
import media.watchmy.client.fake.FakeLiveRepository
import media.watchmy.client.fake.FakeServerRepository
import media.watchmy.client.fake.LivingRoom
import media.watchmy.client.fake.OfficeMini
import media.watchmy.client.fake.fakeLatencyMs
import media.watchmy.client.model.LIVE_GUIDE_API_LEVEL
import media.watchmy.client.model.LIVE_RECORDING_API_LEVEL
import media.watchmy.client.model.RecordingOptions
import media.watchmy.client.model.LiveGuideFilter
import media.watchmy.client.model.User
import media.watchmy.client.model.at
import media.watchmy.client.presentation.MainDispatcherRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LiveGuideViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val zone = ZoneId.of("America/New_York")
    /** Mon 2 Nov 2026, 8:12 PM in New York: the boards' now. */
    private var now = Instant.parse("2026-11-03T01:12:00Z").toEpochMilli()
    private val servers = FakeServerRepository()
    private val auth = FakeAuthRepository()
    private var level = LIVE_GUIDE_API_LEVEL
    private val live = FakeLiveRepository(level = { level }, user = { User(1, "Sam", true) }, clock = { now }, zone = zone)
    private var latency = 0L

    @Before fun noLatency() { latency = fakeLatencyMs; fakeLatencyMs = 0 }
    @After fun restore() { fakeLatencyMs = latency }

    private fun model() = LiveGuideViewModel(live, servers, auth, clock = { now }, zone = { zone })
    private fun at(local: String) = LocalDateTime.parse(local).atZone(zone).toInstant().toEpochMilli()

    @Test fun `nothing is asked until a screen shows listings`() = runTest(main.dispatcher) {
        val vm = model()
        advanceUntilIdle()
        assertTrue(live.guideRequests.isEmpty())
        vm.show(false)
        advanceUntilIdle()
        assertTrue(live.guideRequests.isEmpty())
        assertFalse(vm.state.value.enabled)
    }

    @Test fun `the guide starts at the half hour now with the channel watched last selected`() = runTest(main.dispatcher) {
        val vm = model()
        vm.show(true, channelId = "live-4-1")
        advanceUntilIdle()
        val state = vm.state.value
        assertEquals(at("2026-11-02T20:00"), state.windowStartMs)
        assertEquals("The Sleuth Files", state.selection?.programme?.title)
        assertEquals(listOf("Today", "Tomorrow", "Wed"), state.days.map { it.label })
        assertTrue(state.showsNow)
        assertFalse(state.canGoEarlier)
        // One request covers a server window ahead, so moving later doesn't ask again.
        assertEquals(1, live.guideRequests.size)
        vm.later()
        vm.later()
        advanceUntilIdle()
        assertEquals(at("2026-11-02T21:00"), vm.state.value.windowStartMs)
        assertEquals(1, live.guideRequests.size)
        assertTrue(vm.state.value.canGoEarlier)
    }

    @Test fun `the selected programme's details follow a recording set and cancelled`() = runTest(main.dispatcher) {
        level = LIVE_RECORDING_API_LEVEL
        val vm = model()
        vm.show(true, channelId = "live-2-1")
        advanceUntilIdle()
        val row = vm.state.value.rows.first { it.channel.id == "live-2-1" }
        val later = row.programmes.first { it.startMs > now && it.recording == null }
        vm.select(row.channel, later)
        val actions = LiveRecordingsViewModel(live, servers, auth, clock = { now }, loadSections = false)
        advanceUntilIdle()
        actions.record(later, RecordingOptions())
        advanceUntilIdle()
        val mark = vm.state.value.selection?.programme?.recording
        assertEquals(later.id, vm.state.value.selection?.programme?.id)
        assertEquals("scheduled", mark?.state)
        actions.cancel(mark!!.id)
        advanceUntilIdle()
        assertNull(vm.state.value.selection?.programme?.recording)
    }

    @Test fun `channels without listings and gone channels show only under All channels`() = runTest(main.dispatcher) {
        val vm = model()
        vm.show(true)
        advanceUntilIdle()
        val all = vm.state.value.rows
        assertFalse(all.first { it.channel.number == "4.3" }.listings)
        assertTrue(all.first { it.channel.number == "4.3" }.programmes.isEmpty())
        vm.filter(LiveGuideFilter.Movies)
        val movies = vm.state.value.rows.map { it.channel.number }
        // At 8:12 PM The Iron Coast is on 2.3 and The Glass Harbor on 20.1.
        assertEquals(listOf("2.3", "20.1"), movies)
        vm.filter(LiveGuideFilter.News)
        assertTrue("2.2" in vm.state.value.rows.map { it.channel.number })
        assertFalse("4.3" in vm.state.value.rows.map { it.channel.number })
    }

    @Test fun `days move the window to the time it is now and stop where listings end`() = runTest(main.dispatcher) {
        val vm = model()
        vm.setSpan(60)
        vm.show(true)
        advanceUntilIdle()
        vm.pickDay(1)
        advanceUntilIdle()
        assertEquals(at("2026-11-03T20:00"), vm.state.value.windowStartMs)
        assertEquals(1, vm.state.value.dayIndex)
        vm.pickDay(2)
        advanceUntilIdle()
        // Listings end Wed 7 PM: the last window starts half an hour before.
        assertEquals(at("2026-11-04T18:30"), vm.state.value.windowStartMs)
        assertFalse(vm.state.value.canGoLater)
        vm.toNow()
        advanceUntilIdle()
        assertEquals(at("2026-11-02T20:00"), vm.state.value.windowStartMs)
        assertEquals(0, vm.state.value.dayIndex)
    }

    @Test fun `the selection follows what is picked or focused`() = runTest(main.dispatcher) {
        val vm = model()
        vm.setSpan(120)
        vm.show(true)
        advanceUntilIdle()
        val row = vm.state.value.rows.first { it.channel.number == "7.1" }
        val later = row.programmes.first { it.startMs > now }
        vm.select(row.channel, later)
        assertEquals("Station 9", vm.state.value.selection?.programme?.title)
        assertEquals(row.channel.id, vm.state.value.selection?.channel?.id)
    }

    @Test fun `another account drops the listings and starts again at now`() = runTest(main.dispatcher) {
        val vm = model()
        vm.show(true)
        advanceUntilIdle()
        vm.later()
        advanceUntilIdle()
        servers.select(OfficeMini)
        advanceUntilIdle()
        assertEquals(at("2026-11-02T20:00"), vm.state.value.windowStartMs)
        assertNotNull(vm.state.value.guide)
        assertTrue(vm.state.value.enabled)
    }

    @Test fun `older servers answer no listings and the screen keeps channels`() = runTest(main.dispatcher) {
        level = LIVE_GUIDE_API_LEVEL - 1
        servers.select(LivingRoom.copy(apiLevel = level))
        val vm = model()
        vm.show(true)
        advanceUntilIdle()
        assertNull(vm.state.value.guide)
        assertTrue(live.guideRequests.isEmpty())
    }

    @Test fun `the fakes list the boards' evening around now`() = runTest(main.dispatcher) {
        val channels = live.channels()
        val sleuth = channels.first { it.number == "4.1" }
        assertEquals("The Sleuth Files", sleuth.now?.title)
        assertEquals("Conference Football", sleuth.next?.title)
        assertNull(channels.first { it.number == "4.3" }.now)
        assertNull(channels.first { it.number == "4.4" }.logo)
        val guide = live.guide(at("2026-11-02T20:00"), at("2026-11-02T22:00"))!!
        assertEquals("Weather Now", guide.rows.first { it.channel.number == "2.2" }.programmes.at(now)?.title)
        assertTrue(guide.rows.first { it.channel.number == "2.3" }.programmes.any { it.title == "Laugh Lines" || it.title == "The Iron Coast" })
    }
}
