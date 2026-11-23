package media.watchmy.client.tv.player

import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import media.watchmy.client.fake.FakeAppServices
import media.watchmy.client.fake.FakePlaybackController
import media.watchmy.client.fake.fakeLatencyMs
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.PlaybackController
import media.watchmy.client.ui.ProvideAppServices
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The remote's seeks on the TV player: a run of quick presses of ◀ or ▶ is
 * one seek, sent once the remote has been quiet a moment, to where the
 * presses add up. On the fakes, at a 1080p TV's size.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class PlayerSeekTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun fast() {
        fakeLatencyMs = 0
    }

    private val seeks = mutableListOf<Long>()

    /** Playing at 10:00, with every seek the screen sends counted. */
    private fun play() {
        val player = FakePlaybackController(CoroutineScope(StandardTestDispatcher()))
        val counted = object : PlaybackController by player {
            override fun seekTo(positionMs: Long) {
                seeks += positionMs
                player.seekTo(positionMs)
            }
        }
        compose.setContent {
            ProvideAppServices(FakeAppServices(tv = true)) {
                PlayerScreen(PlayRequest(9101, startMs = 600_000, title = ""), onExit = {}, controller = counted)
            }
        }
        compose.runOnIdle {
            player.play()
            player.seekTo(600_000)
        }
        compose.waitForIdle()
    }

    /**
     * A press and release. With the clock stopped, the main looper still has
     * to run for the screen to see the release (Compose applies state written
     * outside a composition from a posted task); on a TV it always does.
     */
    private fun press(key: Key) {
        compose.onRoot().performKeyInput {
            keyDown(key)
            keyUp(key)
        }
        compose.waitForIdle()
    }

    @Test fun `three quick presses back are one seek thirty seconds back`() {
        play()
        compose.mainClock.autoAdvance = false
        repeat(3) {
            press(Key.DirectionLeft)
            compose.mainClock.advanceTimeBy(150)
        }
        assertEquals("nothing goes while the presses come", emptyList<Long>(), seeks)
        compose.mainClock.advanceTimeBy(SeekState.SEND_AFTER_MS + 100)
        assertEquals(listOf(570_000L), seeks)
    }

    @Test fun `presses further apart than the quiet time are a seek each`() {
        play()
        compose.mainClock.autoAdvance = false
        press(Key.DirectionLeft)
        compose.mainClock.advanceTimeBy(SeekState.SEND_AFTER_MS + 100)
        assertEquals(listOf(590_000L), seeks)
        press(Key.DirectionLeft)
        compose.mainClock.advanceTimeBy(SeekState.SEND_AFTER_MS + 100)
        assertEquals(listOf(590_000L, 580_000L), seeks)
    }
}
