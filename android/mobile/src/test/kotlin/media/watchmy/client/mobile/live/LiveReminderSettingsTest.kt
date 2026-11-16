package media.watchmy.client.mobile.live

import android.app.NotificationManager
import android.provider.Settings
import media.watchmy.client.model.LIVE_REMINDER_CHANNEL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LiveReminderSettingsTest {
    private val app = RuntimeEnvironment.getApplication()

    private fun notificationsEnabled(on: Boolean) =
        shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(on)

    @Test
    fun appNotificationsOffOpensTheAppsScreen() {
        // The channel's screen can't turn the app's notifications on: its switch does nothing.
        notificationsEnabled(false)
        openReminderNotifications(app)
        val started = shadowOf(app).nextStartedActivity
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, started.action)
        assertEquals(app.packageName, started.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertNull(started.getStringExtra(Settings.EXTRA_CHANNEL_ID))
    }

    @Test
    fun appNotificationsOnOpensTheRemindersChannel() {
        notificationsEnabled(true)
        openReminderNotifications(app)
        val started = shadowOf(app).nextStartedActivity
        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, started.action)
        assertEquals(app.packageName, started.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertEquals(LIVE_REMINDER_CHANNEL, started.getStringExtra(Settings.EXTRA_CHANNEL_ID))
    }
}
