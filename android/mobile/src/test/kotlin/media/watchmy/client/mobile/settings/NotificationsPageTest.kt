package media.watchmy.client.mobile.settings

import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import media.watchmy.client.fake.FakeAppServices
import media.watchmy.client.fake.fakeLatencyMs
import media.watchmy.client.model.NEW_EPISODES_CHANNEL
import media.watchmy.client.model.SettingsGroup
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.WatchMyMediaPhoneTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Settings › Notifications on a phone,
 * on the fakes: the switch, the shows with
 * Stop notifying, and one card when Android won't show the notifications.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port-xxhdpi")
class NotificationsPageTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var latency = 0L
    private val services = FakeAppServices()
    private val manager = RuntimeEnvironment.getApplication().getSystemService(NotificationManager::class.java)

    @Before fun noLatency() {
        latency = fakeLatencyMs
        fakeLatencyMs = 0
    }

    @After fun restoreLatency() {
        fakeLatencyMs = latency
    }

    private fun show(open: SettingsGroup? = SettingsGroup.Notifications, opened: (Long) -> Unit = {}) {
        compose.setContent {
            CompositionLocalProvider(LocalAppServices provides services) {
                WatchMyMediaPhoneTheme {
                    SettingsScreen(onAddServer = {}, onSignedOut = {}, onLinkTv = {}, onNavigate = { route -> (route as? media.watchmy.client.mobile.navigation.MobileRoutes.Show)?.let { opened(it.id) } }, open = open)
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun `the page has this phone's switch, then the shows followed, newest first`() {
        show()
        compose.onNodeWithText("The shows you follow are the same on every device. Each device has its own switch.").assertExists()
        compose.onNodeWithText("ON THIS PHONE").assertExists()
        compose.onNodeWithText("Opens Android’s settings for new episodes").assertExists()
        compose.onNodeWithText("Notifications come about once an hour, and later while the phone is idle. The phone has to be able to reach your server.").assertExists()
        compose.onNodeWithText("Live TV reminders are in Playback.").assertExists()
        compose.onNodeWithText("Harbor Lights").performScrollTo().assertExists()
        compose.onNodeWithText("Not in the library now").performScrollTo().assertExists()
        compose.onNode(hasContentDescription("Stop notifying me of new episodes of Copperline")).assertExists()
    }

    @Test fun `the switch is this phone's, and Stop notifying acts at once`() {
        show()
        compose.onNode(hasText("New episodes") and isToggleable()).performClick()
        compose.waitForIdle()
        assertFalse(services.follows.notifyHere.value)

        compose.onNode(hasContentDescription("Stop notifying me of new episodes of Copperline")).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Copperline").assertDoesNotExist()
        assertFalse(services.follows.following(105))
    }

    @Test fun `a refused stop brings the row back with Try again`() {
        show()
        services.follows.refuse = 500
        compose.onNode(hasContentDescription("Stop notifying me of new episodes of Copperline")).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Couldn’t stop notifying, so you still follow Copperline. Check your connection and try again.").assertExists()
        services.follows.refuse = null
        compose.onNodeWithText("Try again").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Copperline").assertDoesNotExist()
    }

    @Test fun `a show in the library opens its page`() {
        var opened: Long? = null
        show(opened = { opened = it })
        compose.onNodeWithText("Harbor Lights").performScrollTo().performClick()
        assertEquals(100L, opened)
    }

    @Test fun `with the app's notifications off, one card says both can't show, and the page's says new episodes`() {
        shadowOf(manager).setNotificationsEnabled(false)
        show(open = null)
        compose.onNodeWithText("Live TV reminders and new episodes can’t show on this phone").assertExists()
        compose.onNodeWithText("Notifications are off for WatchMyMedia in Android’s settings.").assertExists()
        compose.onNodeWithText("New episodes can’t show on this phone").performScrollTo().assertExists()
    }

    @Test fun `with only New episodes off, the card names the channel`() {
        manager.createNotificationChannel(NotificationChannel(NEW_EPISODES_CHANNEL, "New episodes", NotificationManager.IMPORTANCE_NONE))
        show(open = null)
        compose.onNodeWithText("“New episodes” notifications are off for WatchMyMedia in Android’s settings.").assertExists()
        compose.onNodeWithText("Live TV reminders and new episodes", substring = true).assertDoesNotExist()
    }

    @Test fun `the overview's line counts the shows and says this phone notifies`() {
        show(open = null)
        compose.onNodeWithText("3 shows you follow · this phone notifies you").performScrollTo().assertExists()
    }
}
