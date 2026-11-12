package media.watchmy.client.mobile.live

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.runBlocking
import media.watchmy.client.fake.FakeAppServices
import media.watchmy.client.fake.fakeLatencyMs
import media.watchmy.client.model.LiveCapabilities
import media.watchmy.client.model.LiveChannel
import media.watchmy.client.presentation.live.LiveChannelsViewModel
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.WatchMyMediaPhoneTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port-xxhdpi")
class LiveGuideScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val services = FakeAppServices()
    private var latency = 0L

    @Before fun noLatency() { latency = fakeLatencyMs; fakeLatencyMs = 0 }
    @After fun restore() { fakeLatencyMs = latency }

    private fun open(guideLevel: Boolean = true, onWatch: (LiveChannel) -> Unit = {}) {
        val channels = runBlocking { services.live.channels() }
        val state = LiveChannelsViewModel.State(
            supported = true, guideLevel = guideLevel, capabilities = LiveCapabilities(true, true, true, guide = true),
            channels = channels, selectedId = "live-4-1",
        )
        compose.setContent {
            CompositionLocalProvider(LocalAppServices provides services) {
                WatchMyMediaPhoneTheme { LiveChannelsScreen(state, "Sam", {}, {}, onWatch, {}, {}) }
            }
        }
        compose.waitForIdle()
    }

    @Test fun `the guide selects what is on the channel watched last and its details watch it`() {
        var watched: LiveChannel? = null
        open(onWatch = { watched = it })
        compose.onNodeWithContentDescription("Selected programme").assertExists()
        compose.onNodeWithText("Today").assertExists()
        compose.onNodeWithText("Tomorrow").assertExists()
        compose.onNode(hasText("Details") and hasClickAction()).performClick()
        compose.onNodeWithContentDescription("Close details").assertExists()
        compose.onAllNodesWithText("Watch 4.1").onLast().performClick()
        assertEquals("live-4-1", watched?.id)
    }

    @Test fun `a channel the feed doesn't list still plays and stale listings say so`() {
        services.live.stale = true
        var watched: LiveChannel? = null
        open(onWatch = { watched = it })
        compose.onNodeWithText("Listings last updated", substring = true).assertExists()
        compose.onNodeWithContentDescription("Watch 4.3, no listings").performClick()
        assertEquals("live-4-3", watched?.id)
    }

    @Test fun `without the listings level the channel-only list stays as it was`() {
        open(guideLevel = false)
        compose.onAllNodesWithText("No listings available").onLast().assertExists()
        compose.onNodeWithText("Details").assertDoesNotExist()
        compose.onNodeWithContentDescription("Selected programme").assertDoesNotExist()
    }
}
