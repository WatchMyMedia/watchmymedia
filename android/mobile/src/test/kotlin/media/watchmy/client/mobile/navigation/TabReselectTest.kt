package media.watchmy.client.mobile.navigation

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.navigation.compose.rememberNavController
import media.watchmy.client.fake.FakeAppServices
import media.watchmy.client.fake.fakeLatencyMs
import media.watchmy.client.ui.LocalAppServices
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Tapping the tab already shown takes its page back to the top (PLAN-ANDROID-REVIEW §6.3). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port-xxhdpi")
class TabReselectTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var latency = 0L

    @Before fun noLatency() {
        latency = fakeLatencyMs
        fakeLatencyMs = 0
    }

    @After fun restoreLatency() {
        fakeLatencyMs = latency
    }

    private fun scrolled() = compose.onAllNodes(hasScrollToIndexAction()).onFirst().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    @Test fun `tapping Home again scrolls Home to the top`() {
        compose.setContent {
            CompositionLocalProvider(LocalAppServices provides FakeAppServices()) {
                MobileNavHost(onSwitchServer = {}, onSignedOut = {}, nav = rememberNavController())
            }
        }
        compose.waitForIdle()
        compose.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToIndex(4)
        compose.waitForIdle()
        assertTrue("scrolled down", scrolled() > 0f)

        compose.onAllNodes(hasText("Home") and hasClickAction()).onFirst().performClick()
        compose.waitForIdle()
        assertEquals(0f, scrolled())
    }

    @Test fun `tapping TV Shows again scrolls the library grid to the top`() {
        compose.setContent {
            CompositionLocalProvider(LocalAppServices provides FakeAppServices()) {
                MobileNavHost(onSwitchServer = {}, onSignedOut = {}, nav = rememberNavController())
            }
        }
        compose.waitForIdle()
        compose.onAllNodes(hasText("TV Shows") and hasClickAction()).onFirst().performClick()
        compose.waitForIdle()
        compose.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToIndex(12)
        compose.waitForIdle()
        assertTrue("scrolled down", scrolled() > 0f)

        compose.onAllNodes(hasText("TV Shows") and hasClickAction()).onFirst().performClick()
        compose.waitForIdle()
        assertEquals(0f, scrolled())
    }
}
