package media.watchmy.client.tv.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import media.watchmy.client.tv.TvTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The TV's on-screen keyboard, driven with the D-pad as a remote would. */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class KeyboardTest {
    @get:Rule val compose = createComposeRule()

    private var text by mutableStateOf("")
    private var done = 0

    private fun keyboard() {
        compose.setContent {
            TvTheme {
                val first = remember { FocusRequester() }
                OnScreenKeyboard(onText = { text += it }, onBackspace = { text = text.dropLast(1) }, onDone = { done++ }, doneLabel = "Search", firstKey = first)
                InitialFocus(first)
            }
        }
        compose.waitForIdle()
    }

    private fun press(keys: List<Key>) = compose.onRoot().performKeyInput { keys.forEach { pressKey(it) } }

    private fun press(key: Key) = press(listOf(key))

    @Test
    fun `focus starts on q and the D-pad types a word`() {
        keyboard()
        compose.onNodeWithText("q").assertIsFocused()
        // q → right twice → e; down → d (the row below); up, left → w.
        press(listOf(Key.DirectionCenter, Key.DirectionRight, Key.DirectionRight, Key.DirectionCenter, Key.DirectionDown, Key.DirectionCenter))
        assertEquals("qed", text)
        press(listOf(Key.DirectionUp, Key.DirectionLeft, Key.DirectionCenter))
        assertEquals("qedw", text)
    }

    @Test
    fun `shift types one capital, symbols swap the letters, and space, delete and done work`() {
        keyboard()
        compose.onNodeWithText("⇧ abc").performClickByKey()
        compose.onNodeWithText("H").performClickByKey()
        compose.onNodeWithText("i").performClickByKey()
        assertEquals("Shift lasts one letter", "Hi", text)
        compose.onNodeWithText("#+=").performClickByKey()
        compose.onNodeWithText("!").performClickByKey()
        compose.onNodeWithText("abc").performClickByKey()
        compose.onNodeWithText("Space").performClickByKey()
        compose.onNodeWithText("2").performClickByKey()
        assertEquals("Hi! 2", text)
        compose.onNodeWithText("⌫").performClickByKey()
        assertEquals("Hi! ", text)
        compose.onNodeWithText("Search").performClickByKey()
        assertEquals(1, done)
    }

    @Test
    fun `a remote's keyboard types straight in while a key has focus`() {
        keyboard()
        press(listOf(Key.N, Key.A, Key.S))
        assertEquals("nas", text)
        press(Key.Backspace)
        assertEquals("na", text)
        press(Key.DirectionCenter)
        assertEquals("Centre still presses the focused key", "naq", text)
    }

    /** Focuses the key and presses centre on it, as the remote does. */
    private fun SemanticsNodeInteraction.performClickByKey() {
        performSemanticsAction(SemanticsActions.RequestFocus)
        performKeyInput { pressKey(Key.DirectionCenter) }
    }
}
