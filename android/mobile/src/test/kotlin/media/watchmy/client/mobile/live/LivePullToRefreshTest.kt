package media.watchmy.client.mobile.live

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import media.watchmy.client.fake.FakeAppServices
import media.watchmy.client.fake.FakeLiveChannels
import media.watchmy.client.model.LiveCapabilities
import media.watchmy.client.presentation.live.LiveChannelsViewModel
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.WatchMyMediaPhoneTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Pulling Live TV's channel list down asks the server again. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port-xxhdpi")
class LivePullToRefreshTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `pulling the channels down refreshes them`() {
        var refreshes = 0
        val state = LiveChannelsViewModel.State(supported = true, capabilities = LiveCapabilities(enabled = true, allowed = true, playback = true), channels = FakeLiveChannels)
        compose.setContent {
            CompositionLocalProvider(LocalAppServices provides FakeAppServices()) {
                WatchMyMediaPhoneTheme {
                    LiveChannelsScreen(state, "Sam", onSearch = {}, onSelect = {}, onWatch = {}, onRefresh = { refreshes++ }, onSettings = {})
                }
            }
        }
        compose.onAllNodes(hasScrollAction()).onFirst().performTouchInput { swipeDown(startY = top + 10f, endY = bottom) }
        compose.waitForIdle()
        assertEquals(1, refreshes)
    }
}
