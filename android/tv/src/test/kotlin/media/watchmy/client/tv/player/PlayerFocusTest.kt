package media.watchmy.client.tv.player

import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import media.watchmy.client.fake.FakeAppServices
import media.watchmy.client.fake.FakePlaybackController
import media.watchmy.client.fake.fakeLatencyMs
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.tv.TvTheme
import media.watchmy.client.ui.ProvideAppServices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The TV player's focus, key by key, on the fakes at a 1080p TV's size:
 * focus starts on the scrubber; ▼ goes to the buttons, which run along one
 * row and stop at its ends; ▲ comes back, and stops at the scrubber when
 * there is no Skip; a panel opens on what is in use and Back returns focus
 * to its button; Back hides the controls, then leaves. The fake player only
 * moves when told, so nothing ticks.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class PlayerFocusTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private var exits = 0

    @Before fun fast() {
        fakeLatencyMs = 0
    }

    /** Harbor Lights at 10:00, well past its intro, playing. */
    private fun play(): FakePlaybackController {
        val player = FakePlaybackController(CoroutineScope(StandardTestDispatcher()))
        compose.setContent {
            ProvideAppServices(FakeAppServices(tv = true)) {
                TvTheme { PlayerScreen(PlayRequest(9101, startMs = 600_000, title = ""), onExit = { exits++ }, controller = player) }
            }
        }
        compose.runOnIdle {
            player.play()
            player.seekTo(600_000)
        }
        compose.waitForIdle()
        return player
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    /** What the focused node says: its description, else its words. */
    private fun focused(): String {
        val node = compose.onNode(isFocused()).fetchSemanticsNode()
        return node.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
            ?: node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
            ?: ""
    }

    private val scrubber get() = compose.onNodeWithContentDescription("Position", substring = true)

    /** ▶ along the buttons until [label] has focus; false when the row ends first. */
    private fun rightTo(label: String): Boolean {
        repeat(8) {
            if (focused().startsWith(label)) return true
            press(Key.DirectionRight)
        }
        return focused().startsWith(label)
    }

    @Test fun `focus starts on the scrubber, and the buttons are one row under it that stops at its ends`() {
        play()
        scrubber.assertIsFocused()
        assertTrue("how long is left and when it ends", compose.onAllNodes(hasText("42 min left", substring = true)).fetchSemanticsNodes().isNotEmpty())

        // No Skip at 10:00: ▲ stays on the scrubber.
        press(Key.DirectionUp)
        scrubber.assertIsFocused()

        press(Key.DirectionDown)
        assertEquals("Pause", focused())
        press(Key.DirectionDown)
        assertEquals("▼ stops at the buttons", "Pause", focused())
        press(Key.DirectionLeft)
        assertEquals("◀ stops at the row's start", "Pause", focused())

        assertTrue("Playback info is in the row: ${focused()}", rightTo("Playback info"))
        press(Key.DirectionRight)
        assertTrue("Next episode is last: ${focused()}", focused().startsWith("Next episode"))
        press(Key.DirectionRight)
        assertTrue("▶ stops at the row's end", focused().startsWith("Next episode"))

        press(Key.DirectionUp)
        scrubber.assertIsFocused()
    }

    @Test fun `Playback info opens on Close and says what is happening, and Back puts focus back on its button`() {
        play()
        press(Key.DirectionDown)
        assertTrue(rightTo("Playback info"))
        press(Key.DirectionCenter)
        assertTrue(
            "one sentence first",
            compose.onAllNodes(hasText("Playing the picture and sound as they are", substring = true)).fetchSemanticsNodes().isNotEmpty(),
        )
        assertTrue("then the video", compose.onAllNodes(hasText("4K HDR, as it is")).fetchSemanticsNodes().isNotEmpty())
        compose.onNode(hasText("Close") and hasClickAction()).assertIsFocused()

        back()
        assertTrue(compose.onAllNodes(hasText("Close") and hasClickAction()).fetchSemanticsNodes().isEmpty())
        assertEquals("Playback info", focused())
    }

    @Test fun `Audio and subtitles opens on the track in use, said in words, and a choice keeps the panel open`() {
        val player = play()
        press(Key.DirectionDown)
        assertTrue(rightTo("Audio & subtitles"))
        press(Key.DirectionCenter)
        assertTrue("the audio in use: ${focused()}", focused().contains("5.1 surround"))
        press(Key.DirectionDown)
        assertTrue("the commentary, in words: ${focused()}", focused().contains("Commentary · stereo"))
        press(Key.DirectionCenter)
        assertEquals(2, player.state.value.selectedAudio)
        assertTrue("still open", focused().contains("Commentary"))

        back()
        assertEquals("Audio & subtitles", focused())
    }

    @Test fun `Back hides the controls, then leaves`() {
        play()
        scrubber.assertIsFocused()
        back()
        assertTrue("the controls are hidden", compose.onAllNodes(hasText("Playback info")).fetchSemanticsNodes().isEmpty())
        assertEquals(0, exits)
        back()
        assertEquals(1, exits)
    }

    @Test fun `with the controls hidden, a press brings them back on the scrubber`() {
        play()
        back()
        press(Key.DirectionUp)
        scrubber.assertIsFocused()
    }
}
