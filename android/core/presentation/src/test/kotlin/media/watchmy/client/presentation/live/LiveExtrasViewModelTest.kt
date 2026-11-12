package media.watchmy.client.presentation.live

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import media.watchmy.client.fake.FakeAuthRepository
import media.watchmy.client.fake.FakeLiveRepository
import media.watchmy.client.fake.FakeServerRepository
import media.watchmy.client.fake.LivingRoom
import media.watchmy.client.fake.fakeLatencyMs
import media.watchmy.client.model.LIVE_GUIDE_API_LEVEL
import media.watchmy.client.model.LIVE_PERSONAL_API_LEVEL
import media.watchmy.client.model.LiveGuideFilter
import media.watchmy.client.model.LivePersonalChange
import media.watchmy.client.model.LiveReminderState
import media.watchmy.client.model.User
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
import java.time.ZoneId

/** Favourites, search and reminders in the shared ViewModels (PLAN-LIVE-TV-GUIDE.md §6.5), on the fakes. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LiveExtrasViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val zone = ZoneId.of("America/New_York")
    /** Mon 2 Nov 2026, 8:12 PM in New York. */
    private var now = Instant.parse("2026-11-03T01:12:00Z").toEpochMilli()
    private val servers = FakeServerRepository()
    private val auth = FakeAuthRepository()
    private var level = LIVE_PERSONAL_API_LEVEL
    private val live = FakeLiveRepository(level = { level }, user = { User(1, "Sam", true) }, clock = { now }, zone = zone)
    private var latency = 0L

    @Before fun noLatency() { latency = fakeLatencyMs; fakeLatencyMs = 0 }
    @After fun restore() { fakeLatencyMs = latency }

    private fun guide() = LiveGuideViewModel(live, servers, auth, clock = { now }, zone = { zone })
    private fun atLevel(l: Int) {
        level = l
        kotlinx.coroutines.runBlocking { servers.select(LivingRoom.copy(apiLevel = l)) }
    }

    @Test fun `Favourites keeps the starred channels in channel order, with or without listings`() = runTest(main.dispatcher) {
        atLevel(LIVE_PERSONAL_API_LEVEL)
        val vm = guide()
        vm.show(true)
        advanceUntilIdle()
        val s = vm.state.value
        assertTrue(s.personal)
        assertEquals(LiveGuideFilter.Favourites, s.filters.first())
        vm.filter(LiveGuideFilter.Favourites)
        // 4.3 has no listings and still shows; the guide's order, not the order they were starred in.
        assertEquals(listOf("2.1", "4.1", "4.3", "7.1", "11.1"), vm.state.value.rows.map { it.channel.number })
    }

    @Test fun `a star shows at once, reaches the server, and the last one gone falls back to All`() = runTest(main.dispatcher) {
        atLevel(LIVE_PERSONAL_API_LEVEL)
        live.favourites = setOf("7.1")
        val vm = guide()
        vm.show(true)
        advanceUntilIdle()
        vm.filter(LiveGuideFilter.Favourites)
        val pnvl = vm.state.value.guide!!.rows.first { it.channel.number == "9.1" }.channel
        vm.toggleFavourite(pnvl)
        assertEquals(listOf("7.1", "9.1"), vm.state.value.rows.map { it.channel.number })
        advanceUntilIdle()
        assertEquals(setOf("7.1", "9.1"), live.favourites)
        val lksd = vm.state.value.guide!!.rows.first { it.channel.number == "7.1" }.channel
        vm.toggleFavourite(lksd)
        vm.toggleFavourite(pnvl)
        advanceUntilIdle()
        assertTrue(live.favourites.isEmpty())
        assertEquals(LiveGuideFilter.All, vm.state.value.shownFilter)
        assertFalse(LiveGuideFilter.Favourites in vm.state.value.filters)
        assertEquals(vm.state.value.guide!!.rows.size, vm.state.value.rows.size)
    }

    @Test fun `a refused star goes back and says why`() = runTest(main.dispatcher) {
        atLevel(LIVE_PERSONAL_API_LEVEL)
        val vm = guide()
        vm.show(true)
        advanceUntilIdle()
        val hrbr = vm.state.value.guide!!.rows.first { it.channel.number == "2.1" }.channel
        assertTrue(vm.state.value.isFavourite(hrbr))
        live.offline = true
        vm.toggleFavourite(hrbr)
        advanceUntilIdle()
        assertTrue(vm.state.value.isFavourite(hrbr))
        assertNotNull(vm.state.value.notice)
    }

    @Test fun `below API level 15 there are no favourites, filters or reminders`() = runTest(main.dispatcher) {
        atLevel(LIVE_GUIDE_API_LEVEL)
        val vm = guide()
        vm.show(true)
        advanceUntilIdle()
        val s = vm.state.value
        assertFalse(s.personal)
        assertTrue(s.favourites.isEmpty())
        assertFalse(LiveGuideFilter.Favourites in s.filters)
        assertNull(s.reminders)
        assertTrue(live.searches.isEmpty())
    }

    @Test fun `remind me and remove keep the guide's reminders`() = runTest(main.dispatcher) {
        atLevel(LIVE_PERSONAL_API_LEVEL)
        val vm = guide()
        vm.show(true)
        advanceUntilIdle()
        val s = vm.state.value
        assertNotNull(s.reminders)
        val dash = s.guide!!.rows.first { it.channel.number == "2.1" }.programmes.first { it.title == "Dollar Dash" && it.startMs > now }
        assertNotNull(s.reminderFor(dash))
        val coast = s.guide!!.rows.first { it.channel.number == "2.1" }.programmes.first { it.title == "Coastline Rescue" && it.startMs > now }
        assertNull(s.reminderFor(coast))
        vm.remind(coast)
        advanceUntilIdle()
        val set = vm.state.value.reminderFor(coast)
        assertNotNull(set)
        assertEquals(LiveReminderState.Set, set!!.state)
        vm.forget(set)
        advanceUntilIdle()
        assertNull(vm.state.value.reminderFor(coast))
    }

    @Test fun `a change on another device is read again`() = runTest(main.dispatcher) {
        atLevel(LIVE_PERSONAL_API_LEVEL)
        val vm = guide()
        vm.show(true)
        advanceUntilIdle()
        live.favourites = setOf("9.1")
        live.changedElsewhere(LivePersonalChange.Favourites)
        advanceUntilIdle()
        val pnvl = vm.state.value.guide!!.rows.first { it.channel.number == "9.1" }.channel
        assertEquals(setOf(pnvl.id), vm.state.value.favourites)
    }

    @Test fun `opening the guide at a programme selects it, even under another filter`() = runTest(main.dispatcher) {
        atLevel(LIVE_PERSONAL_API_LEVEL)
        val vm = guide()
        vm.show(true)
        advanceUntilIdle()
        vm.filter(LiveGuideFilter.Kids)
        val station = vm.state.value.guide!!.rows.first { it.channel.number == "7.1" }.programmes.first { it.title == "Station 9" && it.startMs > now }
        vm.open(LiveGuideViewModel.Target("live-7-1", station.id, station.startMs))
        advanceUntilIdle()
        assertEquals(station.id, vm.state.value.selection?.programme?.id)
        assertEquals(LiveGuideFilter.All, vm.state.value.filter)
    }

    @Test fun `search finds titles and episode titles now and later, and sets reminders`() = runTest(main.dispatcher) {
        atLevel(LIVE_PERSONAL_API_LEVEL)
        val vm = LiveSearchViewModel(SavedStateHandle(), live, servers, clock = { now }, debounceMs = 0)
        vm.search("h")
        advanceUntilIdle()
        assertTrue(live.searches.isEmpty())
        assertNull(vm.state.value.searched)
        vm.search("harbor")
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals("harbor", s.searched)
        assertTrue(s.results.isNotEmpty())
        // The Glass Harbor is on now; Station 9's episode is The Harbor Fire.
        assertTrue(s.onNow.any { it.programme.title == "The Glass Harbor" })
        val station = s.later.first { it.programme.title == "Station 9" }
        assertEquals("The Harbor Fire", station.programme.episodeTitle)
        assertNotNull(s.reminderFor(station))
        val news = s.later.first { it.programme.title == "Harbor News at 10" }
        assertNull(s.reminderFor(news))
        assertTrue(s.canRemind(news))
        vm.toggleReminder(news)
        advanceUntilIdle()
        assertNotNull(vm.state.value.reminderFor(news))
        vm.toggleReminder(news)
        advanceUntilIdle()
        assertNull(vm.state.value.reminderFor(news))
        vm.search("lighthouse")
        advanceUntilIdle()
        assertTrue(vm.state.value.nothing)
    }

    @Test fun `search asks nothing below API level 15`() = runTest(main.dispatcher) {
        atLevel(LIVE_GUIDE_API_LEVEL)
        val vm = LiveSearchViewModel(SavedStateHandle(), live, servers, clock = { now }, debounceMs = 0)
        vm.search("harbor")
        advanceUntilIdle()
        assertFalse(vm.state.value.available)
        assertTrue(live.searches.isEmpty())
    }

    @Test fun `the reminders list removes and keeps the lead time`() = runTest(main.dispatcher) {
        atLevel(LIVE_PERSONAL_API_LEVEL)
        val vm = LiveRemindersViewModel(live, servers, clock = { now })
        advanceUntilIdle()
        val s = vm.state.value
        assertTrue(s.liveTv)
        assertEquals(listOf("Dollar Dash", "Station 9", "The Sleuth Files", "Nature’s Edge", "Great Rail Trips"), s.items.map { it.title })
        assertEquals(LiveReminderState.Moved, s.items[2].state)
        assertEquals(LiveReminderState.Gone, s.items[4].state)
        vm.remove(s.items[0])
        advanceUntilIdle()
        assertEquals(4, vm.state.value.items.size)
        assertEquals(5, vm.state.value.leadMinutes)
        vm.setLead(10)
        advanceUntilIdle()
        assertEquals(10, vm.state.value.leadMinutes)
        assertEquals(10, live.leadMinutes)
    }
}
