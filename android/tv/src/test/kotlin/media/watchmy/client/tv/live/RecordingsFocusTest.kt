package media.watchmy.client.tv.live

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import media.watchmy.client.fake.fakeLatencyMs
import media.watchmy.client.tv.TvTheme
import media.watchmy.client.tv.quietTvFakes
import media.watchmy.client.ui.LocalAppServices
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Recordings' map, on the fakes (one recording now, one recorded, one that
 * clashes): focus starts on the first recording; ▲ reaches the clash's
 * Choose which to keep, then the tabs; a tab shows by taking focus; and
 * deleting asks first, opening on the choice that changes nothing.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class RecordingsFocusTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var latency = 0L

    @Before fun start() {
        latency = fakeLatencyMs
        fakeLatencyMs = 0
    }

    @After fun stop() {
        fakeLatencyMs = latency
    }

    private fun show() {
        val services = quietTvFakes()
        compose.setContent {
            CompositionLocalProvider(LocalAppServices provides services) {
                TvTheme { LiveRecordingsScreenTv(onNavigate = {}, onPlay = {}) }
            }
        }
        compose.waitForIdle()
    }

    /** A remote key, to the panel when one has focus (it has a window of its own), else the screen. */
    private fun press(key: Key) {
        val inDialog = compose.onAllNodes(isFocused() and hasAnyAncestor(isDialog())).fetchSemanticsNodes().isNotEmpty()
        val target = if (inDialog) compose.onNode(isFocused() and hasAnyAncestor(isDialog())) else compose.onAllNodes(isRoot()).onFirst()
        target.performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    @Test fun `focus starts on the recording now, and up reaches the clash and then the tabs`() {
        show()
        compose.onNode(isFocused()).assert(hasText("Rec", substring = true) or hasText("Recording", substring = true))
        compose.onNodeWithText("Choose which to keep").assertExists()
        press(Key.DirectionUp)
        compose.onNode(isFocused()).assert(hasText("Choose which to keep"))
        press(Key.DirectionUp)
        compose.onNode(isFocused()).assert(hasText("Ready to watch"))
        // A tab shows by taking focus: Scheduled lists the one that won't record.
        press(Key.DirectionRight)
        compose.onNode(isFocused()).assert(hasText("Scheduled"))
        compose.onNodeWithText("Won’t record").assertExists()
        // ▼ from the tabs: the clash first, then the list.
        press(Key.DirectionDown)
        compose.onNode(isFocused()).assert(hasText("Choose which to keep"))
        press(Key.DirectionDown)
        compose.onNode(isFocused()).assert(hasClickAction())
    }

    @Test fun `deleting a recording opens on Keep it`() {
        show()
        // The recording made earlier, beside the one recording now.
        press(Key.DirectionRight)
        compose.onNode(isFocused()).assert(hasText("Inspector Vane"))
        press(Key.DirectionCenter)
        compose.onNode(isFocused() and hasText("Watch")).assertExists()
        compose.onNodeWithText("Delete recording…").assertExists()
        repeat(3) { press(Key.DirectionDown) }
        compose.onNode(isFocused() and hasText("Delete recording…")).assertExists()
        press(Key.DirectionCenter)
        compose.onNode(isFocused() and hasText("Keep it")).assertExists()
        press(Key.DirectionCenter)
        compose.onNodeWithText("Delete recording").assertDoesNotExist()
        // Kept, and focus back on the card that opened it.
        compose.onNode(isFocused()).assert(hasText("Inspector Vane"))
    }
}
