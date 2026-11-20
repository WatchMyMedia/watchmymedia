package media.watchmy.client.tv.detail

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.requestFocus
import media.watchmy.client.fake.FakeAppServices
import media.watchmy.client.fake.fakeLatencyMs
import media.watchmy.client.model.ShowArrival
import media.watchmy.client.tv.TvTheme
import media.watchmy.client.ui.LocalAppServices
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Notify me in the TV's About the show,
 * on the fakes, where Sam follows Harbor Lights: OK
 * stops or follows at once, and while the bell has focus a line says who
 * notifies.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class FollowBellTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var latency = 0L
    private val services = FakeAppServices(tv = true)

    @Before fun start() {
        latency = fakeLatencyMs
        fakeLatencyMs = 0
    }

    @After fun stop() {
        fakeLatencyMs = latency
    }

    @Test fun `the bell follows and stops at once, and says who notifies while it has focus`() {
        compose.setContent {
            CompositionLocalProvider(LocalAppServices provides services) {
                TvTheme { ShowPageScreen(showId = 100, arrival = ShowArrival.Show, onPlay = {}) }
            }
        }
        compose.waitForIdle()
        val line = "Your phone or tablet notifies you. This TV shows new episodes on Home."
        compose.onNodeWithText(line).assertDoesNotExist()
        compose.onNode(hasContentDescription("Notifications on for Harbor Lights. Turn them off")).requestFocus()
        compose.waitForIdle()
        compose.onNodeWithText(line).assertExists()

        compose.onNode(hasContentDescription("Notifications on for Harbor Lights. Turn them off")).performClick()
        compose.waitForIdle()
        compose.onNode(hasContentDescription("Notify me of new episodes of Harbor Lights")).assertExists()
        assertTrue(!services.follows.following(100))
        compose.onNode(hasContentDescription("Notify me of new episodes of Harbor Lights")).performClick()
        compose.waitForIdle()
        assertTrue(services.follows.following(100))
    }
}
