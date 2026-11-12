package media.watchmy.client.mobile.live

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.media3.common.Player
import kotlinx.coroutines.flow.MutableStateFlow
import media.watchmy.client.fake.FakeAppServices
import media.watchmy.client.fake.FakeLiveChannels
import media.watchmy.client.model.AppServices
import media.watchmy.client.model.LivePlaybackController
import media.watchmy.client.model.LivePlaybackControllerFactory
import media.watchmy.client.model.LivePlayerState
import media.watchmy.client.model.LiveSession
import media.watchmy.client.model.LiveSessionState
import media.watchmy.client.model.LiveTarget
import media.watchmy.client.model.PlayerStatus
import media.watchmy.client.model.StreamQuality
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.WatchMyMediaPhoneTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port-xxhdpi")
class LivePlayerScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val controller = RecordingController()
    private val visible = mutableStateOf(true)
    private val configuration = mutableStateOf(Configuration().apply {
        screenWidthDp = 411
        screenHeightDp = 891
        smallestScreenWidthDp = 411
    })
    private val services = object : AppServices by FakeAppServices() {
        override val livePlayback = LivePlaybackControllerFactory { controller }
    }

    private fun open() {
        compose.setContent {
            CompositionLocalProvider(LocalAppServices provides services, LocalConfiguration provides configuration.value) {
                WatchMyMediaPhoneTheme {
                    if (visible.value) LivePlayerScreen(LiveTarget(FakeLiveChannels.first()), FakeLiveChannels, onExit = { visible.value = false })
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `channel buttons retune the typed target without file-player controls`() {
        open()
        compose.onNodeWithContentDescription("Next channel").performClick()
        compose.runOnIdle { assertEquals(FakeLiveChannels[1], controller.state.value.target?.channel) }
        compose.onNodeWithContentDescription("Pause live TV").performClick()
        compose.runOnIdle { assertEquals(PlayerStatus.Paused, controller.state.value.status) }
        compose.onNodeWithText("Go live").performClick()
        compose.runOnIdle { assertEquals(PlayerStatus.Playing, controller.state.value.status) }
        compose.onNodeWithContentDescription("Cast").assertDoesNotExist()
        compose.onNodeWithContentDescription("Forward 10 seconds").assertDoesNotExist()
        compose.onNodeWithText("Next episode").assertDoesNotExist()
        compose.onNodeWithText("Download").assertDoesNotExist()
    }

    @Test
    fun `tablet rotation follows the device without a forced full screen orientation`() {
        configuration.value = Configuration(configuration.value).apply {
            screenWidthDp = 800
            screenHeightDp = 1280
            smallestScreenWidthDp = 800
        }
        val original = compose.activity.requestedOrientation
        open()
        compose.onNodeWithContentDescription("Full screen").assertDoesNotExist()
        compose.runOnIdle { assertEquals(original, compose.activity.requestedOrientation) }
    }

    @Test
    fun `full screen can return to the original rotation policy without leaving the channel`() {
        val original = compose.activity.requestedOrientation
        open()
        compose.onNodeWithContentDescription("Full screen").performClick()
        compose.runOnIdle {
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, compose.activity.requestedOrientation)
            configuration.value = Configuration(configuration.value).apply { screenWidthDp = 891; screenHeightDp = 411 }
        }
        compose.onNodeWithContentDescription("Exit full screen").performClick()
        compose.runOnIdle {
            assertEquals(original, compose.activity.requestedOrientation)
            assertEquals(1, controller.starts)
            assertEquals(0, controller.stops)
        }
    }

    @Test
    fun `Back stops the live session and disposal releases the controller`() {
        open()
        compose.onNodeWithContentDescription("Back to channels").performClick()
        compose.runOnIdle {
            assertTrue(controller.stops > 0)
            assertEquals(1, controller.releases)
        }
    }

    @Test
    fun `backgrounding releases the tuner and returning requires an explicit retry`() {
        open()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertTrue(controller.stops > 0)
        assertEquals(PlayerStatus.Ended, controller.state.value.status)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        assertEquals("Returning does not restart the tuner", 1, controller.starts)
        compose.onNodeWithText("Go live").assertDoesNotExist()
        compose.onNodeWithText("Try again").performClick()
        compose.runOnIdle { assertEquals(2, controller.starts) }
    }

    @Test
    fun `the takeover warning is announced, offers Don't record it to its setter, and OK hides it`() {
        open()
        compose.runOnIdle {
            val session = controller.state.value.session!!
            controller.state.value = controller.state.value.copy(session = session.copy(takeoverAtMs = System.currentTimeMillis() + 120_000, takeoverRecordingId = "r1", takeoverCanCancel = true))
        }
        val warning = hasText("a recording needs this tuner. Watching stops then.", substring = true)
        compose.onNode(warning).assertExists()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion) and hasAnyDescendant(warning)).assertExists()
        compose.onNodeWithText("Don’t record it").assertExists()
        compose.onNodeWithText("OK").performClick()
        compose.onNode(warning).assertDoesNotExist()
    }

    @Test
    fun `someone who didn't set the recording sees the warning without Don't record it`() {
        open()
        compose.runOnIdle {
            val session = controller.state.value.session!!
            controller.state.value = controller.state.value.copy(session = session.copy(takeoverAtMs = System.currentTimeMillis() + 120_000, takeoverRecordingId = "r1"))
        }
        compose.onNodeWithText("a recording needs this tuner", substring = true).assertExists()
        compose.onNodeWithText("Don’t record it").assertDoesNotExist()
    }

    private class RecordingController : LivePlaybackController {
        override val state = MutableStateFlow(LivePlayerState())
        override val player = MutableStateFlow<Player?>(null)
        var starts = 0
        var stops = 0
        var releases = 0
        override fun start(target: LiveTarget) {
            starts++
            state.value = state.value.copy(target = target, status = PlayerStatus.Playing, session = LiveSession("test-$starts", target.channel, LiveSessionState.Playing, state.value.quality, null))
        }
        override fun play() { state.value = state.value.copy(status = PlayerStatus.Playing) }
        override fun pause() { state.value = state.value.copy(status = PlayerStatus.Paused, atLiveEdge = false) }
        override fun togglePlayPause() { if (state.value.status == PlayerStatus.Paused) play() else pause() }
        override fun goLive() { state.value = state.value.copy(status = PlayerStatus.Playing, atLiveEdge = true) }
        override fun selectQuality(quality: StreamQuality) { state.value = state.value.copy(quality = quality) }
        override fun noteUserInput() = Unit
        override fun answerStillWatching(keepWatching: Boolean) = Unit
        override fun retry() { state.value.target?.let(::start) }
        override fun stop() { stops++; state.value = state.value.copy(status = PlayerStatus.Ended, session = null) }
        override fun release() { releases++; stop() }
    }
}
