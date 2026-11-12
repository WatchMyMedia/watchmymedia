package media.watchmy.client.tv.live

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import media.watchmy.client.fake.FakeAppServices
import media.watchmy.client.fake.FakeLiveChannels
import media.watchmy.client.fake.FakeLivePlaybackController
import media.watchmy.client.model.CastedLive
import media.watchmy.client.model.LiveCastSender
import media.watchmy.client.model.LiveTarget
import media.watchmy.client.model.PlayerStatus
import media.watchmy.client.tv.TvTheme
import media.watchmy.client.ui.LocalAppServices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A channel cast to this TV (Cast Connect, the LiveCastConnect board), on the fakes. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class CastLivePlayerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val services = FakeAppServices(tv = true)
    private val controller = FakeLivePlaybackController(latencyMs = 0)
    private val sender = MutableStateFlow(LiveCastSender("Pixel 9"))
    private var exited = false
    private val target = LiveTarget(FakeLiveChannels.first { it.number == "4.1" })
    private val live = CastedLive(1, target, controller, services.live, MutableStateFlow(FakeLiveChannels).asStateFlow(), listings = false, sender = sender.asStateFlow())

    private fun show() {
        compose.setContent {
            CompositionLocalProvider(LocalAppServices provides services) {
                TvTheme { CastLivePlayer(live, onExit = { exited = true }) }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `the TV says who cast, keeps channel changes and Stop casting, and leaves the rest to the phone`() {
        show()
        compose.onNodeWithText("Cast from Pixel 9").assertExists()
        compose.onNodeWithText("Quality and the channel list are on the phone.").assertExists()
        compose.onNodeWithText("▲ ▼ Change channel · OK Controls").assertExists()
        compose.onNodeWithText("Stop casting").assertExists()
        compose.onNodeWithText("Ch + 4.2").assertExists()
        compose.onNodeWithText("Full guide").assertDoesNotExist()
        compose.onNodeWithText("Quality 1080p").assertDoesNotExist()
        compose.onNodeWithText("Channels").assertDoesNotExist()
        compose.onNodeWithText("Go to channel").assertDoesNotExist()
        compose.onNodeWithText("Ch + 4.2").performClick()
        compose.runOnIdle { assertEquals("4.2", controller.state.value.target?.channel?.number) }
        compose.onNodeWithText("Stop casting").performClick()
        compose.runOnIdle {
            assertEquals(PlayerStatus.Ended, controller.state.value.status)
            assertTrue(exited)
        }
    }

    @Test
    fun `the phone's pause is named, and the number keys and Menu do nothing here`() {
        show()
        sender.value = sender.value.copy(pausedThere = true)
        controller.pause()
        compose.waitForIdle()
        compose.onNodeWithText("Paused on Pixel 9. After 5 minutes the tuner is given back.").assertExists()
        compose.onRoot().performKeyInput { pressKey(Key.Five) }
        compose.onRoot().performKeyInput { pressKey(Key.Menu) }
        compose.waitForIdle()
        compose.onNodeWithText("Go to channel").assertDoesNotExist()
        compose.onNodeWithText("Live TV plays at one quality. Changing it tunes the channel again, at live.").assertDoesNotExist()
    }

    @Test
    fun `all tuners busy offers Try again and Stop casting`() {
        controller.nextFailure = "tuners_busy"
        show()
        compose.onNodeWithText("All tuners are in use").assertExists()
        compose.onNodeWithText("Pixel 9 asked for 4.1, but the HDHomeRun’s tuners are all busy. Try again when someone stops watching, or pick another channel on the phone.").assertExists()
        compose.onNodeWithText("Try again").performClick()
        compose.runOnIdle { assertEquals(PlayerStatus.Playing, controller.state.value.status) }
    }

    @Test
    fun `when the phone stops casting the TV says so, then goes back`() {
        show()
        controller.stop()
        sender.value = sender.value.copy(stopped = true)
        compose.waitForIdle()
        compose.onNodeWithText("Pixel 9 stopped casting").assertExists()
        compose.onNodeWithText("4.1 NFLD-HD stopped on this TV, and its tuner is free again.").assertExists()
        compose.onNodeWithText("This TV goes back to what it showed before in 5 s.").assertExists()
        compose.mainClock.advanceTimeBy(STOPPED_SHOWN_MS + 100)
        compose.runOnIdle { assertTrue(exited) }
    }
}
