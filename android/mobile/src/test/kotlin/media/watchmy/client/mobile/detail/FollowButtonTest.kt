package media.watchmy.client.mobile.detail

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import media.watchmy.client.fake.FakeAppServices
import media.watchmy.client.fake.fakeLatencyMs
import media.watchmy.client.model.ShowArrival
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.WatchMyMediaPhoneTheme
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Notify me on the phone's show page (PLAN-NEW-EPISODES.md §7.1; the boards'
 * `bell` and `explainer`), on the fakes, where Sam follows Harbor Lights:
 * the bell's name carries its state, and the first Notify me on Android 13
 * and later explains before Android asks, keeping the follow on Not now.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port-xxhdpi")
class FollowButtonTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var latency = 0L
    private val services = FakeAppServices()

    @Before fun noLatency() {
        latency = fakeLatencyMs
        fakeLatencyMs = 0
    }

    @After fun restoreLatency() {
        fakeLatencyMs = latency
    }

    @Test fun `the bell turns off and on at once, and the first time on explains before Android asks`() {
        compose.setContent {
            CompositionLocalProvider(LocalAppServices provides services) {
                WatchMyMediaPhoneTheme { ShowPageScreen(showId = 100, arrival = ShowArrival.Show, onPlay = {}, onBack = {}) }
            }
        }
        compose.waitForIdle()
        compose.onNode(hasContentDescription("Notifications on for Harbor Lights. Turn them off")).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNode(hasContentDescription("Notify me of new episodes of Harbor Lights")).assertExists()
        assertTrue("stopped on the server", !services.follows.following(100))

        compose.onNode(hasContentDescription("Notify me of new episodes of Harbor Lights")).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Get notified of new episodes").assertExists()
        compose.onNodeWithText("When a new episode of Harbor Lights reaches the library, this phone shows a notification.").assertExists()
        compose.onNodeWithText("Not now").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Get notified of new episodes").assertDoesNotExist()
        compose.onNode(hasContentDescription("Notifications on for Harbor Lights. Turn them off")).assertExists()
        assertTrue("Not now keeps the follow", services.follows.following(100))
    }
}
