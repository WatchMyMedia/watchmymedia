package media.watchmy.client.tv.player

import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The frame both TV players draw in: the
 * controls hide after five seconds without a press, a press starts the wait
 * again, the screen stays on while asked, focus goes where the player says,
 * and Back reaches the player by either path until it leaves.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class PlayerShellTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private var controls by mutableStateOf(true)
    private var playing by mutableStateOf(true)
    private var backs = 0
    private var keys = 0
    private lateinit var shell: PlayerShellState

    private fun player() {
        compose.setContent {
            shell = rememberPlayerShellState()
            val button = remember { FocusRequester() }
            PlayerShell(
                shell,
                keepScreenOn = playing,
                autoHide = controls && playing,
                onHide = { controls = false },
                onBack = { backs++ },
                onKey = { keys++; shell.noteInput(); false },
                focusTarget = { wanted -> wanted ?: if (controls) button else shell.root },
                focusKeys = arrayOf<Any?>(controls),
            ) {
                if (controls) Box(Modifier.size(10.dp).testTag("button").focusRequester(button).focusable())
            }
        }
        compose.waitForIdle()
    }

    @Test fun `the controls hide five seconds after the last press, and focus goes back to the screen`() {
        compose.mainClock.autoAdvance = false
        player()
        compose.onNodeWithTag("button").assertIsFocused()
        compose.mainClock.advanceTimeBy(4_000)
        compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        // With the clock stopped, the press's state change reaches composition only once applied.
        compose.runOnUiThread { Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeBy(4_000)
        assertTrue("A press started the wait again", controls)
        compose.mainClock.advanceTimeBy(1_500)
        assertFalse(controls)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertTrue(shell.rootFocused)
    }

    @Test fun `the screen stays on only while asked`() {
        player()
        val view = compose.activity.window.decorView.findViewById<android.view.View>(android.R.id.content)
        assertTrue(anyKeepsScreenOn(view))
        playing = false
        compose.waitForIdle()
        assertFalse(anyKeepsScreenOn(view))
    }

    @Test fun `Back reaches the player by either path until it leaves`() {
        player()
        compose.onRoot().performKeyInput { pressKey(Key.Back) }
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(2, backs)

        compose.runOnUiThread { shell.leave() }
        compose.waitForIdle()
        val pressed = keys
        compose.onRoot().performKeyInput { pressKey(Key.Back) }
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        assertEquals("Leaving, Back belongs to the screen underneath", 2, backs)
        assertEquals("And so do the other keys", pressed, keys)
    }

    private fun anyKeepsScreenOn(view: android.view.View): Boolean =
        view.keepScreenOn || (view is android.view.ViewGroup && (0 until view.childCount).any { anyKeepsScreenOn(view.getChildAt(it)) })
}
