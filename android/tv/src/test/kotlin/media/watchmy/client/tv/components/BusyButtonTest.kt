package media.watchmy.client.tv.components

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A button that disables itself while it works (Sign in, Connect, Mark
 * season watched) keeps focus: OK does nothing until it is done, and
 * TalkBack hears it as disabled (PLAN-ANDROID-REVIEW.md §3.2.1).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class BusyButtonTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `a busy button keeps focus and ignores OK until it is done`() {
        var busy by mutableStateOf(false)
        var clicks = 0
        compose.setContent {
            val focus = remember { FocusRequester() }
            TvButton(if (busy) "Signing in…" else "Sign in", { clicks++; busy = true }, Modifier.focusRequester(focus), enabled = !busy)
            LaunchedEffect(Unit) { focus.requestFocus() }
        }
        compose.waitForIdle()
        press(Key.DirectionCenter)
        assertEquals(1, clicks)
        compose.onNodeWithText("Signing in…").assertIsFocused().assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Disabled))

        press(Key.DirectionCenter)
        assertEquals("OK on the busy button does nothing", 1, clicks)
        busy = false
        compose.waitForIdle()
        compose.onNodeWithText("Sign in").assertIsFocused().assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Disabled))
        press(Key.DirectionCenter)
        assertEquals(2, clicks)
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }
}
