package media.watchmy.client.reminders

import android.app.AlarmManager
import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import media.watchmy.client.model.LiveCapabilities
import media.watchmy.client.model.LiveChannel
import media.watchmy.client.model.LivePersonalChange
import media.watchmy.client.model.LiveReminder
import media.watchmy.client.model.LiveReminderState
import media.watchmy.client.model.LiveReminders
import media.watchmy.client.model.LiveRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * What the app does with reminders' alarms as people sign in and out
 * (ReminderFollower, which WatchMyMediaApp starts on phones and tablets),
 * on the real alarms over Robolectric's AlarmManager.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ReminderFollowerTest {
    private val context = RuntimeEnvironment.getApplication()
    private val manager get() = shadowOf(context.getSystemService(AlarmManager::class.java))
    private val now = 1_800_000_000_000L
    private val minute = 60_000L

    private val dispatcher = UnconfinedTestDispatcher()
    private val scope = CoroutineScope(dispatcher)
    private val live = TestLive()
    private val session = MutableStateFlow<Any?>(0)
    private var account: String? = null
    private val owner = object : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this)
    }

    @Before fun main() = Dispatchers.setMain(dispatcher)

    @After fun end() {
        scope.cancel()
        Dispatchers.resetMain()
    }

    private fun reminder(id: String, startsIn: Long) = LiveReminder(
        id, "live-2-1", "2.1", "HRBR-HD", "p-$id", now + startsIn, now + startsIn + 30 * minute, "Dollar Dash", "Double or Nothing", 3, 14, LiveReminderState.Set,
    )

    private fun list(vararg ids: String) = LiveReminders(ids.mapIndexed { i, id -> reminder(id, (18 + 30 * i) * minute) })

    /** The accounts the alarms set now carry, one per alarm. */
    private fun scheduledFor(): List<String?> = manager.scheduledAlarms.map { shadowOf(it.operation!!).savedIntent.getStringExtra(LiveReminderReceiver.EXTRA_ACCOUNT) }

    private fun follower() = ReminderFollower(LiveReminderAlarms(context), live, session, { account }, scope, nowMs = { now }).also { it.start(owner.lifecycle) }

    /** Someone signs in, out or in as someone else: the data layer forgets the last account's list. */
    private fun switchTo(who: String?) {
        account = who
        live.reminders.value = null
        session.value = (session.value as Int) + 1
    }

    @Test fun `a list sets an alarm for each reminder, for the account signed in`() {
        account = "sam@server"
        follower()
        live.reminders.value = list("a", "b")
        assertEquals(listOf("sam@server", "sam@server"), scheduledFor())
    }

    @Test fun `signing out cancels the alarms`() {
        account = "sam@server"
        follower()
        live.reminders.value = list("a", "b")
        switchTo(null)
        assertTrue(manager.scheduledAlarms.isEmpty())
        assertFalse(LiveReminderAlarms(context).hasAlarms())
    }

    @Test fun `another account has none of the last one's alarms, then its own`() {
        account = "sam@server"
        follower()
        live.reminders.value = list("a", "b")
        switchTo("alex@server")
        // Alex's list hasn't come yet: none of Sam's alarms are left to go off for Alex.
        assertTrue(manager.scheduledAlarms.isEmpty())
        live.reminders.value = list("c")
        assertEquals(listOf("alex@server"), scheduledFor())
    }

    @Test fun `a start keeps the alarms it restored while the same account's list loads`() {
        LiveReminderAlarms(context).schedule(list("a", "b"), "sam@server", emptyMap(), now)
        account = "sam@server"
        follower()
        assertEquals(listOf("sam@server", "sam@server"), scheduledFor())
        live.reminders.value = list("b")
        assertEquals(listOf("sam@server"), scheduledFor())
    }

    @Test fun `a start as someone else drops the alarms it restored`() {
        LiveReminderAlarms(context).schedule(list("a", "b"), "sam@server", emptyMap(), now)
        // Alex is signed in now, on a server with no reminders list (it never comes).
        account = "alex@server"
        follower()
        assertTrue(manager.scheduledAlarms.isEmpty())
    }

    @Test fun `a start signed out drops the alarms it restored`() {
        LiveReminderAlarms(context).schedule(list("a"), "sam@server", emptyMap(), now)
        follower()
        assertTrue(manager.scheduledAlarms.isEmpty())
    }

    @Test fun `the list is read in the foreground and when the server says it changed`() {
        account = "sam@server"
        live.server = list("a")
        follower()
        assertEquals(0, live.refreshes)
        owner.lifecycle.currentState = Lifecycle.State.STARTED
        assertEquals(1, live.refreshes)
        assertEquals(listOf("sam@server"), scheduledFor())
        live.server = list("a", "b")
        live.personal.tryEmit(LivePersonalChange.Reminders)
        assertEquals(2, live.refreshes)
        assertEquals(2, manager.scheduledAlarms.size)
        // In the background the server's changes wait for the next return.
        owner.lifecycle.currentState = Lifecycle.State.CREATED
        live.personal.tryEmit(LivePersonalChange.Reminders)
        assertEquals(2, live.refreshes)
    }

    private class TestLive : LiveRepository {
        override val reminders = MutableStateFlow<LiveReminders?>(null)
        var server: LiveReminders? = null
        var refreshes = 0
        val personal = MutableSharedFlow<LivePersonalChange>(extraBufferCapacity = 4)
        override val personalChanges = personal

        override suspend fun refreshReminders(): LiveReminders? {
            refreshes++
            reminders.value = server
            return server
        }

        override suspend fun capabilities() = LiveCapabilities()
        override suspend fun channels() = emptyList<LiveChannel>()
    }
}
