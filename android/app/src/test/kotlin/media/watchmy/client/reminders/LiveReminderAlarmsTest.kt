package media.watchmy.client.reminders

import android.app.AlarmManager
import android.app.Application
import android.content.Intent
import media.watchmy.client.model.LiveReminder
import media.watchmy.client.model.LiveReminderState
import media.watchmy.client.model.LiveReminders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The phone's reminder alarms (PLAN-LIVE-TV-GUIDE.md §6.5): inexact windows, following the server's list, and set again after a reboot. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LiveReminderAlarmsTest {
    private val context = RuntimeEnvironment.getApplication()
    private val manager get() = shadowOf(context.getSystemService(AlarmManager::class.java))
    private val now = 1_800_000_000_000L
    private val minute = 60_000L

    private fun reminder(id: String, startsIn: Long, state: LiveReminderState = LiveReminderState.Set) = LiveReminder(
        id, "live-2-1", "2.1", "HRBR-HD", "p-$id", now + startsIn, now + startsIn + 30 * minute, "Dollar Dash", "Double or Nothing", 3, 14, state,
    )

    private fun scheduled() = manager.scheduledAlarms.sortedBy { it.triggerAtTime }

    @Test fun `each reminder gets an inexact one-minute window at its lead time`() {
        val alarms = LiveReminderAlarms(context)
        alarms.schedule(LiveReminders(listOf(reminder("a", 18 * minute), reminder("b", 48 * minute), reminder("gone", 60 * minute, LiveReminderState.Gone)), leadMinutes = 5), "acct", mapOf("live-2-1" to "https://server.test/logo"), now)
        val set = scheduled()
        assertEquals(listOf(now + 13 * minute, now + 43 * minute), set.map { it.triggerAtTime })
        assertTrue(set.all { it.type == AlarmManager.RTC_WAKEUP })
        assertTrue(set.all { it.windowLengthMs == LiveReminderAlarms.WINDOW_MS })
        val intent: Intent = shadowOf(set.first().operation!!).savedIntent
        assertEquals(LiveReminderReceiver.ACTION, intent.action)
        assertEquals("Starting in 5 min", intent.getStringExtra(LiveReminderReceiver.EXTRA_TITLE))
        assertEquals("Dollar Dash · 2.1 HRBR-HD", intent.getStringExtra(LiveReminderReceiver.EXTRA_TEXT))
        assertEquals("live-2-1", intent.getStringExtra(LiveReminderReceiver.EXTRA_CHANNEL))
        assertEquals("acct", intent.getStringExtra(LiveReminderReceiver.EXTRA_ACCOUNT))
        assertEquals("https://server.test/logo", intent.getStringExtra(LiveReminderReceiver.EXTRA_LOGO))
    }

    @Test fun `a removed reminder's alarm goes, and signing out cancels the rest`() {
        val alarms = LiveReminderAlarms(context)
        alarms.schedule(LiveReminders(listOf(reminder("a", 18 * minute), reminder("b", 48 * minute))), "acct", emptyMap(), now)
        alarms.schedule(LiveReminders(listOf(reminder("b", 48 * minute)), leadMinutes = 10), "acct", emptyMap(), now)
        assertEquals(listOf(now + 38 * minute), scheduled().map { it.triggerAtTime })
        alarms.cancelAll()
        assertTrue(scheduled().isEmpty())
    }

    @Test fun `after a reboot the kept alarms come back without the network`() {
        LiveReminderAlarms(context).schedule(LiveReminders(listOf(reminder("a", 18 * minute))), "acct", emptyMap(), now)
        // A reboot clears AlarmManager.
        scheduled().forEach { alarm -> alarm.operation?.let { context.getSystemService(AlarmManager::class.java).cancel(it) } }
        assertTrue(scheduled().isEmpty())
        LiveReminderAlarms(context).restore(now)
        assertEquals(listOf(now + 13 * minute), scheduled().map { it.triggerAtTime })
        // Long past by the time the phone is back: nothing comes.
        LiveReminderAlarms(context).restore(now + 3 * 60 * minute)
        assertTrue(scheduled().isEmpty())
    }

    @Test fun `a late alarm says how long is really left`() {
        assertEquals(5, LiveReminderReceiver.minutesUntil(now + 5 * minute, now))
        assertEquals(3, LiveReminderReceiver.minutesUntil(now + 5 * minute, now + 2 * minute + 30_000))
        assertEquals(0, LiveReminderReceiver.minutesUntil(now + 5 * minute, now + 7 * minute))
    }

    @Test fun `a reminder comes once, and again when the programme moves`() {
        val alarms = LiveReminderAlarms(context)
        assertTrue(alarms.firstTime("r@1"))
        assertFalse(alarms.firstTime("r@1"))
        assertTrue(alarms.firstTime("r@2"))
    }
}
