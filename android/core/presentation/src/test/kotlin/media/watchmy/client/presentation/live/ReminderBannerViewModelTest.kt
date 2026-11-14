package media.watchmy.client.presentation.live

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import media.watchmy.client.fake.FakeLiveRepository
import media.watchmy.client.model.LiveReminder
import media.watchmy.client.model.LiveReminders
import media.watchmy.client.model.LiveRepository
import media.watchmy.client.presentation.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class ReminderBannerViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val minute = 60_000L

    private fun reminder(id: String, startMs: Long) = LiveReminder(id, "c1", "2.1", "HRBR", "p$id", startMs, startMs + 30 * minute, "Programme $id")

    @Test
    fun `a reminder comes when it is due, once, and a moved one comes again`() = runTest(main.dispatcher) {
        val start = 100 * minute
        val reminders = MutableStateFlow<LiveReminders?>(LiveReminders(listOf(reminder("a", start)), leadMinutes = 5))
        val live = object : LiveRepository by FakeLiveRepository() {
            override val reminders = reminders
        }
        val vm = ReminderBannerViewModel(live, clock = { testScheduler.currentTime }, tickMs = minute)
        advanceTimeBy(start - 6 * minute)
        runCurrent()
        assertNull("not yet", vm.banner.value)
        advanceTimeBy(2 * minute)
        runCurrent()
        assertEquals("a", vm.banner.value?.id)
        assertEquals(5, vm.leadMinutes)

        assertEquals("a", vm.watch()?.id)
        runCurrent()
        assertNull("it came once", vm.banner.value)
        advanceTimeBy(minute)
        runCurrent()
        assertNull(vm.banner.value)

        // Moved later: it comes again when that is due.
        reminders.value = LiveReminders(listOf(reminder("a", start + 10 * minute)), leadMinutes = 5)
        advanceTimeBy(10 * minute)
        runCurrent()
        assertEquals("a", vm.banner.value?.id)
        vm.dismiss()
        runCurrent()
        assertNull(vm.banner.value)
        // Its clock ticks while reminders are to come: stop it, or the test's clock never rests.
        vm.viewModelScope.cancel()
    }
}
