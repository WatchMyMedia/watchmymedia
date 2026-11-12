package media.watchmy.client.mobile.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import media.watchmy.client.ui.PlayerKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A keyboard on the phone player: Space and K play and pause, the arrows and J and L jump 10 s. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KeyboardKeysTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `the keys the player takes`() {
        assertEquals(KeyboardAction.TogglePlayPause, keyboardAction(PlayerKey.PlayPause))
        assertEquals(KeyboardAction.SeekBy(-10_000), keyboardAction(PlayerKey.Left))
        assertEquals(KeyboardAction.SeekBy(-10_000), keyboardAction(PlayerKey.Rewind))
        assertEquals(KeyboardAction.SeekBy(10_000), keyboardAction(PlayerKey.Right))
        assertEquals(KeyboardAction.SeekBy(10_000), keyboardAction(PlayerKey.FastForward))
        assertNull("Enter clicks the focused control", keyboardAction(PlayerKey.Centre))
        assertNull("Back leaves", keyboardAction(PlayerKey.Back))
        assertNull("Focus moves", keyboardAction(PlayerKey.Down))
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun `keys reach the player first, and not while a picker is open`() {
        val actions = mutableListOf<KeyboardAction>()
        var clicks = 0
        var enabled by mutableStateOf(true)
        compose.setContent {
            val focus = remember { FocusRequester() }
            LaunchedEffect(focus) { focus.requestFocus() }
            Box(Modifier.size(400.dp).playerKeyboard(focus, enabled) { actions += it }) {
                Button(onClick = { clicks++ }) { Text("Play") }
            }
        }
        compose.onRoot().performKeyInput {
            pressKey(Key.Spacebar)
            pressKey(Key.K)
            pressKey(Key.DirectionLeft)
            pressKey(Key.L)
        }
        compose.runOnIdle {
            assertEquals(listOf(KeyboardAction.TogglePlayPause, KeyboardAction.TogglePlayPause, KeyboardAction.SeekBy(-10_000), KeyboardAction.SeekBy(10_000)), actions)
            assertEquals(0, clicks)
            enabled = false
        }
        compose.onRoot().performKeyInput { pressKey(Key.Spacebar) }
        compose.runOnIdle { assertEquals(4, actions.size) }
    }
}
