package media.watchmy.client.mobile.live

import android.app.NotificationChannel
import android.app.NotificationManager
import android.provider.Settings
import media.watchmy.client.mobile.settings.notificationsAllowed
import media.watchmy.client.mobile.settings.openNotifications
import media.watchmy.client.model.LIVE_REMINDER_CHANNEL
import media.watchmy.client.model.NEW_EPISODES_CHANNEL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Which of Android's screens Turn on notifications opens, and whether the
 * notifications can show, for both channels: Live TV reminders (public #54)
 * and new episodes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LiveReminderSettingsTest {
    private val app = RuntimeEnvironment.getApplication()
    private val manager = app.getSystemService(NotificationManager::class.java)

    private fun notificationsEnabled(on: Boolean) = shadowOf(manager).setNotificationsEnabled(on)

    private fun channel(id: String, importance: Int) = manager.createNotificationChannel(NotificationChannel(id, id, importance))

    @Test
    fun appNotificationsOffOpensTheAppsScreen() {
        // The channel's screen can't turn the app's notifications on: its switch does nothing.
        notificationsEnabled(false)
        for (channel in listOf(LIVE_REMINDER_CHANNEL, NEW_EPISODES_CHANNEL)) {
            openNotifications(app, channel)
            val started = shadowOf(app).nextStartedActivity
            assertEquals(channel, Settings.ACTION_APP_NOTIFICATION_SETTINGS, started.action)
            assertEquals(app.packageName, started.getStringExtra(Settings.EXTRA_APP_PACKAGE))
            assertNull(started.getStringExtra(Settings.EXTRA_CHANNEL_ID))
        }
    }

    @Test
    fun appNotificationsOnOpensTheRemindersChannel() {
        notificationsEnabled(true)
        openNotifications(app, LIVE_REMINDER_CHANNEL)
        val started = shadowOf(app).nextStartedActivity
        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, started.action)
        assertEquals(app.packageName, started.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertEquals(LIVE_REMINDER_CHANNEL, started.getStringExtra(Settings.EXTRA_CHANNEL_ID))
    }

    @Test
    fun appNotificationsOnOpensTheNewEpisodesChannel() {
        notificationsEnabled(true)
        openNotifications(app, NEW_EPISODES_CHANNEL)
        val started = shadowOf(app).nextStartedActivity
        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, started.action)
        assertEquals(app.packageName, started.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertEquals(NEW_EPISODES_CHANNEL, started.getStringExtra(Settings.EXTRA_CHANNEL_ID))
    }

    @Test
    fun bothChannelsAreOffWhileTheAppsNotificationsAre() {
        channel(LIVE_REMINDER_CHANNEL, NotificationManager.IMPORTANCE_HIGH)
        channel(NEW_EPISODES_CHANNEL, NotificationManager.IMPORTANCE_DEFAULT)
        notificationsEnabled(false)
        assertFalse(notificationsAllowed(app, LIVE_REMINDER_CHANNEL))
        assertFalse(notificationsAllowed(app, NEW_EPISODES_CHANNEL))
        notificationsEnabled(true)
        assertTrue(notificationsAllowed(app, LIVE_REMINDER_CHANNEL))
        assertTrue(notificationsAllowed(app, NEW_EPISODES_CHANNEL))
    }

    @Test
    fun oneChannelOffLeavesTheOtherOn() {
        notificationsEnabled(true)
        channel(LIVE_REMINDER_CHANNEL, NotificationManager.IMPORTANCE_HIGH)
        channel(NEW_EPISODES_CHANNEL, NotificationManager.IMPORTANCE_NONE)
        assertTrue(notificationsAllowed(app, LIVE_REMINDER_CHANNEL))
        assertFalse(notificationsAllowed(app, NEW_EPISODES_CHANNEL))
    }

    @Test
    fun aChannelNotMadeYetCountsAsOn() {
        // Android makes a channel on; the app makes it at start, so until then nothing has turned it off.
        notificationsEnabled(true)
        assertTrue(notificationsAllowed(app, NEW_EPISODES_CHANNEL))
    }
}
