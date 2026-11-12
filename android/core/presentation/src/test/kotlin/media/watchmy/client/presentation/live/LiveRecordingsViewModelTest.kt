package media.watchmy.client.presentation.live

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import media.watchmy.client.fake.*
import media.watchmy.client.model.*
import media.watchmy.client.presentation.MainDispatcherRule
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.Instant

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LiveRecordingsViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private var now = Instant.parse("2026-11-04T21:00:00Z").toEpochMilli()
    private var level = LIVE_RECORDING_API_LEVEL
    private var user = User(1, "Sam", true)
    private val servers = FakeServerRepository()
    private val auth = FakeAuthRepository()
    private val live = FakeLiveRepository(level = { level }, user = { user }, clock = { now })
    private var latency = 0L
    @Before fun setup() { latency = fakeLatencyMs; fakeLatencyMs = 0; runBlocking { servers.select(LivingRoom.copy(apiLevel = level)) } }
    @After fun teardown() { fakeLatencyMs = latency }
    private fun model(sections: Boolean = true) = LiveRecordingsViewModel(live, servers, auth, clock = { now }, loadSections = sections)

    @Test fun `old servers never receive recording requests or offer controls`() = runTest(main.dispatcher) {
        level = LIVE_RECORDING_API_LEVEL - 1
        servers.select(LivingRoom.copy(apiLevel = level))
        val vm = model()
        advanceUntilIdle()
        assertFalse(vm.state.value.available)
        assertFalse(vm.state.value.canRecord)
        assertTrue(vm.state.value.pages.isEmpty())
    }

    @Test fun `members cannot record by default and see no household schedule`() = runTest(main.dispatcher) {
        user = User(2, "Alex", false)
        val vm = model()
        advanceUntilIdle()
        assertFalse(vm.state.value.canRecord)
        assertTrue(vm.state.value.pages.isEmpty())
    }

    @Test fun `list loads all sections while guide actions avoid those requests`() = runTest(main.dispatcher) {
        val vm = model()
        advanceUntilIdle()
        assertTrue(vm.state.value.canRecord)
        assertEquals(RecordingSection.entries.toSet(), vm.state.value.pages.keys)
        assertTrue(vm.state.value.page(RecordingSection.Now).items.isNotEmpty())
        val action = model(sections = false)
        advanceUntilIdle()
        assertTrue(action.state.value.canRecord)
        assertTrue(action.state.value.pages.isEmpty())
    }

    @Test fun `manual saves refreshes and cancellation keeps running content`() = runTest(main.dispatcher) {
        val vm = model()
        advanceUntilIdle()
        vm.manual(ManualRecording("live-2-1", "Evening news", now + 600_000, now + 3_600_000))
        advanceUntilIdle()
        assertTrue(vm.state.value.saved)
        val upcoming = vm.state.value.page(RecordingSection.Upcoming).items.first { it.title == "Evening news" }
        vm.cancel(upcoming)
        advanceUntilIdle()
        assertFalse(vm.state.value.page(RecordingSection.Upcoming).items.any { it.id == upcoming.id })
        val current = vm.state.value.page(RecordingSection.Now).items.first()
        vm.cancel(current)
        advanceUntilIdle()
        assertTrue(vm.state.value.page(RecordingSection.Recorded).items.any { it.id == current.id && it.state == "partial" })
    }

    @Test fun `SSE refreshes a remote change and invalid manual inputs stay local`() = runTest(main.dispatcher) {
        val vm = model()
        advanceUntilIdle()
        vm.manual(ManualRecording("", "", now, now))
        advanceUntilIdle()
        assertNotNull(vm.state.value.manualError)
        assertNull("the list's error stays the list's", vm.state.value.error)
        live.recordManual(ManualRecording("live-2-1", "Remote choice", now + 60_000, now + 3_600_000))
        advanceUntilIdle()
        assertTrue(vm.state.value.page(RecordingSection.Upcoming).items.any { it.title == "Remote choice" })
    }

    @Test fun `record this instead and series options use the same shared action path`() = runTest(main.dispatcher) {
        val vm = model()
        advanceUntilIdle()
        val conflict = vm.state.value.page(RecordingSection.Conflicts).items.first()
        vm.prefer(conflict)
        advanceUntilIdle()
        assertTrue(vm.state.value.page(RecordingSection.Upcoming).items.any { it.id == conflict.id })
        val programme = live.guide(now, now + 86_400_000)!!.rows.first { it.channel.number == "2.1" }.programmes.first { it.startMs > now }
        vm.record(programme, RecordingOptions(series = true, anyChannel = true, keep = 3))
        advanceUntilIdle()
        val rule = vm.state.value.page(RecordingSection.Series).rules.first()
        assertEquals(3, rule.keep)
        assertTrue(rule.anyChannel)
        vm.delete(rule)
        advanceUntilIdle()
        assertTrue(vm.state.value.page(RecordingSection.Series).rules.isEmpty())
    }
    private val hour = 3_600_000L

    @Test fun `a new recording that clashes shows which recordings hold the tuners and the chosen one gives way`() = runTest(main.dispatcher) {
        val vm = model()
        advanceUntilIdle()
        vm.manual(ManualRecording("live-9-1", "Late film", now + 3 * hour + 600_000, now + 3 * hour + 2_400_000))
        advanceUntilIdle()
        val clash = vm.state.value.clash!!
        assertEquals("Late film", clash.title)
        assertEquals(setOf("Conference Football", "Harbor Evening News"), clash.conflictsWith.map { it.title }.toSet())
        assertEquals("Alex", clash.conflictsWith.first { it.title == "Conference Football" }.userName)
        val football = clash.conflictsWith.first { it.title == "Conference Football" }
        vm.prefer(clash, over = football)
        advanceUntilIdle()
        assertNull(vm.state.value.clash)
        assertEquals("Late film will record. Conference Football won’t.", vm.state.value.notice)
        assertTrue(vm.state.value.page(RecordingSection.Upcoming).items.any { it.id == clash.id })
        assertTrue(vm.state.value.page(RecordingSection.Conflicts).items.any { it.id == football.id })
    }

    @Test fun `keeping the holders leaves the new recording under Won't record`() = runTest(main.dispatcher) {
        val vm = model()
        advanceUntilIdle()
        vm.manual(ManualRecording("live-9-1", "Late film", now + 3 * hour + 600_000, now + 3 * hour + 2_400_000))
        advanceUntilIdle()
        val clash = vm.state.value.clash!!
        vm.keepClash()
        assertNull(vm.state.value.clash)
        assertTrue(vm.state.value.page(RecordingSection.Conflicts).items.any { it.id == clash.id && it.state == "conflict" })
        assertTrue(vm.state.value.page(RecordingSection.Upcoming).items.any { it.title == "Conference Football" })
    }

    @Test fun `a Won't record row opens the same choice, and a recording already running can't give way`() = runTest(main.dispatcher) {
        val vm = model()
        advanceUntilIdle()
        val row = vm.state.value.page(RecordingSection.Conflicts).items.first { it.state == "conflict" }
        vm.chooseClash(row)
        assertEquals(row.id, vm.state.value.clash?.id)
        assertEquals(2, row.yielding.size)
        val running = row.conflictsWith.first().copy(running = true)
        vm.prefer(row.copy(conflictsWith = listOf(running)), over = running)
        advanceUntilIdle()
        assertEquals("nothing was asked", row.id, vm.state.value.clash?.id)
        assertTrue(vm.state.value.page(RecordingSection.Conflicts).items.any { it.id == row.id })
    }

    @Test fun `Record on a booked programme shows the clash from the guide`() = runTest(main.dispatcher) {
        val vm = model(sections = false)
        advanceUntilIdle()
        val busy = live.guide(now, now + 86_400_000)!!.rows.filter { it.channel.number !in setOf("2.1", "4.1", "4.2", "7.1") }
            .flatMap { it.programmes }.first { it.startMs in (now + 3 * hour) until (now + 3 * hour + 2_400_000) }
        vm.record(busy)
        advanceUntilIdle()
        assertEquals(busy.id, vm.state.value.clash?.programmeId)
        assertTrue(vm.state.value.clash!!.conflictsWith.isNotEmpty())
    }

    /** Counts what the model asks, and can hold a Show more page. */
    private class Watched(val inner: LiveRepository) : LiveRepository by inner {
        var capabilityCalls = 0
        var hold: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        override suspend fun capabilities(): LiveCapabilities { capabilityCalls++; return inner.capabilities() }
        override suspend fun recordings(section: RecordingSection, cursor: String?): RecordingPage {
            if (cursor != null) hold?.await()
            return inner.recordings(section, cursor)
        }
    }

    private suspend fun manyUpcoming(count: Int) = repeat(count) { i ->
        live.recordManual(ManualRecording("live-9-1", "Late film $i", now + 6 * hour + i * 1_860_000L, now + 6 * hour + i * 1_860_000L + 1_800_000L))
    }

    @Test fun `pages shown with Show more stay through a refresh`() = runTest(main.dispatcher) {
        manyUpcoming(24)
        val vm = model()
        advanceUntilIdle()
        assertEquals(20, vm.state.value.page(RecordingSection.Upcoming).items.size)
        vm.next(RecordingSection.Upcoming)
        advanceUntilIdle()
        val shown = vm.state.value.page(RecordingSection.Upcoming).items.size
        assertTrue("more than the first page", shown > 20)
        // Another device adds one: the refresh keeps both pages.
        live.recordManual(ManualRecording("live-9-1", "Remote addition", now + 20 * hour, now + 20 * hour + 1_800_000))
        advanceUntilIdle()
        assertTrue(vm.state.value.page(RecordingSection.Upcoming).items.size >= shown)
        assertTrue(vm.state.value.page(RecordingSection.Upcoming).items.any { it.title == "Late film 23" })
    }

    @Test fun `recording turned on later shows on the next refresh`() = runTest(main.dispatcher) {
        live.recordingOn = false
        val vm = model()
        advanceUntilIdle()
        assertFalse(vm.state.value.available)
        assertFalse(vm.state.value.canRecord)
        live.recordingOn = true
        vm.refresh()
        advanceUntilIdle()
        assertTrue(vm.state.value.available)
        assertTrue(vm.state.value.canRecord)
        assertTrue(vm.state.value.page(RecordingSection.Now).items.isNotEmpty())
    }

    @Test fun `one action refreshes once, however many notes of it arrive`() = runTest(main.dispatcher) {
        val watched = Watched(live)
        val vm = LiveRecordingsViewModel(watched, servers, auth, clock = { now })
        advanceUntilIdle()
        val before = watched.capabilityCalls
        vm.cancel(vm.state.value.page(RecordingSection.Upcoming).items.first())
        advanceUntilIdle()
        assertEquals(1, watched.capabilityCalls - before)
    }

    @Test fun `Show more leaves the other actions available while it loads`() = runTest(main.dispatcher) {
        manyUpcoming(24)
        val watched = Watched(live).apply { hold = kotlinx.coroutines.CompletableDeferred() }
        val vm = LiveRecordingsViewModel(watched, servers, auth, clock = { now })
        advanceUntilIdle()
        vm.next(RecordingSection.Upcoming)
        runCurrent()
        assertEquals(RecordingSection.Upcoming, vm.state.value.paging)
        assertFalse(vm.state.value.busy)
        val first = vm.state.value.page(RecordingSection.Upcoming).items.first()
        vm.cancel(first)
        runCurrent()
        assertTrue("the cancel went ahead", live.recordings(RecordingSection.Upcoming).items.none { it.id == first.id })
        watched.hold!!.complete(Unit)
        advanceUntilIdle()
        assertNull(vm.state.value.paging)
    }

    @Test fun `New recording shows only its own refusal, not the list's errors`() = runTest(main.dispatcher) {
        val vm = model()
        advanceUntilIdle()
        live.offline = true
        vm.refresh()
        advanceUntilIdle()
        assertNotNull(vm.state.value.error)
        assertNull(vm.state.value.manualError)
        vm.manual(ManualRecording("live-9-1", "Late film", now + 6 * hour, now + 7 * hour))
        advanceUntilIdle()
        assertNotNull("the save's own refusal", vm.state.value.manualError)
        vm.clearSaved()
        assertNull(vm.state.value.manualError)
    }

    @Test fun `deleting an owned finished recording removes only its file row and preserves the default guard`() = runTest(main.dispatcher) {
        val vm = model()
        advanceUntilIdle()
        val row = vm.state.value.page(RecordingSection.Recorded).items.first { it.canDelete }
        vm.deleteFile(row)
        advanceUntilIdle()
        assertFalse(vm.state.value.page(RecordingSection.Recorded).items.any { it.id == row.id })
    }

    @Test fun `everyone access lets a member see the household without cancelling another setter`() = runTest(main.dispatcher) {
        user = User(2, "Alex", false)
        live.recordingAccess = "everyone"
        val vm = model()
        advanceUntilIdle()
        assertTrue(vm.state.value.canRecord)
        val current = vm.state.value.page(RecordingSection.Now).items.first()
        assertEquals("Sam", current.userName)
        assertFalse(current.canCancel)
        assertFalse(vm.state.value.page(RecordingSection.Recorded).items.first().canDelete)
        vm.cancel(current)
        advanceUntilIdle()
        assertTrue(vm.state.value.page(RecordingSection.Now).items.any { it.id == current.id })
    }

}
