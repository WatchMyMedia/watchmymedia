package media.watchmy.client.tv.live

import android.view.KeyEvent as AndroidKey
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.media3.common.Player
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import media.watchmy.client.fake.FakeAppServices
import media.watchmy.client.fake.FakeLiveChannels
import media.watchmy.client.fake.OfficeMini
import media.watchmy.client.fake.fakeLatencyMs
import media.watchmy.client.model.AppServices
import media.watchmy.client.model.LiveCapabilities
import media.watchmy.client.model.LiveChannel
import media.watchmy.client.model.LivePlaybackController
import media.watchmy.client.model.LivePlaybackControllerFactory
import media.watchmy.client.model.LivePlayerState
import media.watchmy.client.model.LiveSession
import media.watchmy.client.model.LiveSessionState
import media.watchmy.client.model.LiveTarget
import media.watchmy.client.model.PlayerStatus
import media.watchmy.client.model.StreamQuality
import media.watchmy.client.presentation.live.LiveChannelsViewModel
import media.watchmy.client.tv.TvTheme
import media.watchmy.client.tv.TvApp
import media.watchmy.client.tv.components.LocalLiveTVAvailable
import media.watchmy.client.ui.LocalAppServices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class LiveTVScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val channels = FakeLiveChannels

    @Test fun `channel browser restores selected focus and starts the typed channel without listings`() {
        var watched: LiveTarget? = null
        compose.setContent {
            CompositionLocalProvider(LocalAppServices provides FakeAppServices(tv = true), LocalLiveTVAvailable provides true) {
                TvTheme {
                    LiveChannelsScreen(
                        LiveChannelsViewModel.State(supported = true, capabilities = LiveCapabilities(true, true, true), channels = channels, selectedId = channels[1].id),
                        onSelect = {}, onWatch = { watched = LiveTarget(it) }, onRefresh = {}, onNavigate = {},
                    )
                }
            }
        }
        val chosen = compose.onNodeWithContentDescription("2.2 Harbor Weather, Watch live")
        chosen.assertIsFocused().performClick()
        assertEquals(channels[1], watched?.channel)
        compose.onNodeWithText("Go to channel").assertExists()
    }

    @Test fun `with listings the hero follows focus down the guide`() {
        val previous = fakeLatencyMs
        fakeLatencyMs = 0
        try {
            val services = FakeAppServices(tv = true)
            val listed = runBlocking { services.live.channels() }
            compose.setContent {
                CompositionLocalProvider(LocalAppServices provides services, LocalLiveTVAvailable provides true) {
                    TvTheme {
                        LiveChannelsScreen(
                            LiveChannelsViewModel.State(supported = true, guideLevel = true, capabilities = LiveCapabilities(true, true, true, guide = true), channels = listed, selectedId = "live-2-2"),
                            onSelect = {}, onWatch = {}, onRefresh = {}, onNavigate = {},
                        )
                    }
                }
            }
            compose.waitForIdle()
            compose.onNodeWithText("Watch 2.2").assertExists()
            press(Key.DirectionDown)
            compose.onNodeWithText("Watch 2.3", substring = true).assertExists()
            // Down keeps to the rows in order, a channel without listings included.
            press(Key.DirectionDown)
            press(Key.DirectionDown)
            press(Key.DirectionDown)
            compose.onNodeWithText("No listings · Plays live").assertExists()
            compose.onNodeWithText("Go to channel").assertExists()
        } finally { fakeLatencyMs = previous }
    }

    @Test fun `numeric keypad keeps partial numbers inert until an exact channel is explicitly selected`() {
        var watched: LiveTarget? = null
        compose.setContent { TvTheme { ChannelNumberDialog(channels, "", onWatch = { watched = LiveTarget(it) }, onDismiss = {}) } }
        compose.onNode(hasText("2") and hasClickAction()).performClick()
        compose.onNodeWithText("Watch").assertIsNotEnabled()
        assertEquals(null, watched)
        compose.onNode(hasText(".") and hasClickAction()).performClick()
        compose.onNode(hasText("1") and hasClickAction()).performClick()
        compose.onNodeWithText("Watch 2.1").performClick()
        assertEquals(channels.first(), watched?.channel)
    }

    @Test fun `Back hides controls before releasing the tuner and leaving`() {
        val controller = RecordingController()
        var exits = 0
        showPlayer(controller, exit = { exits++ })
        compose.onNode(hasText("Pause") and hasClickAction()).assertExists()
        press(Key.Back)
        compose.onNode(hasText("Pause") and hasClickAction()).assertDoesNotExist()
        assertEquals(0, controller.stops)
        press(Key.Back)
        assertEquals(1, exits)
        assertEquals(1, controller.stops)
    }

    @Test fun `channel panel closes on Back then hidden Up changes channel and Stop releases immediately`() {
        val controller = RecordingController()
        var exits = 0
        showPlayer(controller, exit = { exits++ })
        compose.onNode(hasText("Channels") and hasClickAction()).performClick()
        compose.onNodeWithText("Watching").assertExists()
        press(Key.Back)
        compose.onNodeWithText("Watching").assertDoesNotExist()
        press(Key.Back)
        press(Key.DirectionUp)
        assertEquals(channels[1], controller.state.value.target?.channel)
        native(AndroidKey.KEYCODE_MEDIA_STOP)
        assertEquals(1, controller.stops)
        assertEquals(1, exits)
    }

    @Test fun `live quality contains four fixed choices and no file playback controls`() {
        val controller = RecordingController()
        showPlayer(controller)
        compose.onNode(hasText("Quality 1080p") and hasClickAction()).performClick()
        compose.onNodeWithText("Automatic").assertDoesNotExist()
        compose.onNodeWithText("Original quality").assertDoesNotExist()
        compose.onNodeWithText("360p · 1 Mbps").performClick()
        assertEquals(StreamQuality.Low, controller.state.value.quality)
        compose.onNodeWithText("Next episode").assertDoesNotExist()
        compose.onNodeWithText("Skip intro").assertDoesNotExist()
        compose.onNodeWithContentDescription("Cast").assertDoesNotExist()
    }

    @Test fun `terminal errors retain an actionable retry and Stop still exits`() {
        val controller = RecordingController()
        var exits = 0
        showPlayer(controller, exit = { exits++ })
        press(Key.Back)
        compose.runOnIdle { controller.state.value = controller.state.value.copy(status = PlayerStatus.Error, error = "All tuners are in use. Try again when a tuner is free.") }
        compose.onNodeWithText("Tune again").assertIsFocused().performClick()
        assertTrue(controller.retries > 0)
        compose.onNode(hasText("Pause") and hasClickAction()).assertIsFocused()
        native(AndroidKey.KEYCODE_MEDIA_STOP)
        assertEquals(1, exits)
    }

    @Test fun `still watching after hidden controls can be answered with Back and controls regain focus`() {
        val controller = RecordingController()
        showPlayer(controller)
        press(Key.Back)
        compose.runOnIdle { controller.state.value = controller.state.value.copy(askStillWatching = true) }
        compose.onNodeWithText("Keep watching").assertIsFocused()
        press(Key.Back)
        compose.onNode(hasText("Pause") and hasClickAction()).assertIsFocused()
        assertEquals(false, controller.state.value.askStillWatching)
    }

    @Test fun `still watching Stop remains reachable with an ordinary D-pad`() {
        val controller = RecordingController()
        var exits = 0
        showPlayer(controller, exit = { exits++ })
        press(Key.Back)
        compose.runOnIdle { controller.state.value = controller.state.value.copy(askStillWatching = true) }
        compose.onNodeWithText("Keep watching").assertIsFocused()
        press(Key.DirectionRight)
        compose.onNodeWithText("Stop").assertIsFocused()
        assertTrue(controller.state.value.askStillWatching)
        press(Key.DirectionCenter)
        assertEquals(1, controller.stops)
        assertEquals(1, exits)
    }

    @Test fun `unavailable channel dialog restores the scrolled row after current channel is offscreen`() {
        val many = (1..30).map { channels.first().copy(id = "channel-$it", number = "$it.1", name = "Channel $it", available = it != 13, reason = "offline") }
        showPlayer(RecordingController(), lineup = many)
        compose.onNode(hasText("Channels") and hasClickAction()).performClick()
        repeat(12) { press(Key.DirectionDown) }
        compose.onNodeWithText("Channel 13").assertIsFocused()
        press(Key.DirectionCenter)
        compose.onNodeWithText("Back to channels").performClick()
        compose.onNodeWithText("Channel 13").assertIsFocused()
        press(Key.DirectionDown)
        compose.onNodeWithText("Channel 14").assertIsFocused()
    }

    @Test fun `direct account and server changes release live playback and reset navigation to Home`() {
        val previousLatency = fakeLatencyMs
        fakeLatencyMs = 0
        val base = FakeAppServices(tv = true).also { it.live.listings = false }
        val controllers = mutableListOf<RecordingController>()
        val services = object : AppServices by base {
            override val livePlayback = LivePlaybackControllerFactory { RecordingController().also(controllers::add) }
        }
        try {
            compose.setContent { CompositionLocalProvider(LocalAppServices provides services) { TvApp() } }
            compose.onNodeWithContentDescription("Live TV").performClick()
            compose.onNodeWithContentDescription("2.1 HRBR-HD, Watch live").performClick()
            compose.onNode(hasText("Pause") and hasClickAction()).assertExists()
            compose.runOnIdle { runBlocking { base.auth.signIn("Alex", "password") } }
            compose.onNode(hasText("Play") and hasClickAction()).assertIsFocused()
            assertEquals(1, controllers.single().releases)
            compose.onNodeWithContentDescription("Live TV").performClick()
            compose.onNodeWithContentDescription("2.1 HRBR-HD, Watch live").performClick()
            compose.onNode(hasText("Pause") and hasClickAction()).assertExists()
            compose.runOnIdle { runBlocking { base.servers.select(OfficeMini) } }
            compose.onNode(hasText("Play") and hasClickAction()).assertIsFocused()
            assertEquals(2, controllers.size)
            assertEquals(1, controllers.last().releases)
        } finally { fakeLatencyMs = previousLatency }
    }

    private fun showPlayer(controller: RecordingController, exit: () -> Unit = {}, lineup: List<LiveChannel> = channels) {
        compose.setContent {
            CompositionLocalProvider(LocalAppServices provides FakeAppServices(tv = true)) {
                TvTheme { LivePlayerScreen(LiveTarget(lineup.first()), lineup, onExit = exit, onChannels = exit, controller = controller) }
            }
        }
        compose.waitForIdle()
    }
    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }
    private fun native(code: Int) {
        compose.runOnUiThread {
            compose.activity.dispatchKeyEvent(AndroidKey(AndroidKey.ACTION_DOWN, code))
            compose.activity.dispatchKeyEvent(AndroidKey(AndroidKey.ACTION_UP, code))
        }
        compose.waitForIdle()
    }

    private class RecordingController : LivePlaybackController {
        override val state = MutableStateFlow(LivePlayerState())
        override val player = MutableStateFlow<Player?>(null)
        var stops = 0
        var retries = 0
        var releases = 0
        override fun start(target: LiveTarget) { state.value = LivePlayerState(target, PlayerStatus.Playing, LiveSession("fake", target.channel, LiveSessionState.Playing, state.value.quality, null), quality = state.value.quality) }
        override fun play() { state.value = state.value.copy(status = PlayerStatus.Playing) }
        override fun pause() { state.value = state.value.copy(status = PlayerStatus.Paused, atLiveEdge = false) }
        override fun togglePlayPause() = if (state.value.status == PlayerStatus.Paused) play() else pause()
        override fun goLive() { state.value = state.value.copy(status = PlayerStatus.Playing, atLiveEdge = true) }
        override fun selectQuality(quality: StreamQuality) { state.value = state.value.copy(quality = quality) }
        override fun noteUserInput() { state.value = state.value.copy(askStillWatching = false) }
        override fun answerStillWatching(keepWatching: Boolean) { state.value = state.value.copy(askStillWatching = false) }
        override fun retry() { retries++; play() }
        override fun stop() { stops++; state.value = state.value.copy(status = PlayerStatus.Ended) }
        override fun release() { releases++ }
    }
}
