package media.watchmy.client.ui

import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [onBack] takes Back as the remote's key and as a back callback (Android 16
 * and later send TV apps only the callback), once a press either way, and
 * the overlay enabled last takes it first.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OnBackTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val calls = mutableListOf<String>()
    private var overlay by mutableStateOf(false)
    private var screen by mutableStateOf(true)
    private var fallback = 0

    private fun content() {
        compose.runOnUiThread {
            compose.activity.onBackPressedDispatcher.addCallback(
                object : OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() {
                        fallback++
                    }
                },
            )
        }
        compose.setContent {
            val focus = remember { FocusRequester() }
            Box(Modifier.onBack(enabled = overlay) { calls += "overlay" }) {
                Box(Modifier.onBack(enabled = screen) { calls += "screen" }) {
                    Box(Modifier.size(10.dp).testTag("item").focusRequester(focus).focusable())
                }
            }
            androidx.compose.runtime.LaunchedEffect(Unit) { focus.requestFocus() }
        }
        compose.waitForIdle()
    }

    private fun pressBackKey() = compose.onNodeWithTag("item").performKeyInput { pressKey(Key.Back) }

    private fun backCallback() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test
    fun `the key and the callback each reach the screen once`() {
        content()
        pressBackKey()
        assertEquals(listOf("screen"), calls)
        backCallback()
        assertEquals(listOf("screen", "screen"), calls)
        assertEquals("Nothing behind the screen saw either press", 0, fallback)
    }

    @Test
    fun `an overlay that opens takes Back first, by either path`() {
        content()
        overlay = true
        compose.waitForIdle()
        pressBackKey()
        backCallback()
        assertEquals(listOf("overlay", "overlay"), calls)
    }

    @Test
    fun `disabled, Back goes past it`() {
        content()
        screen = false
        compose.waitForIdle()
        backCallback()
        assertEquals(emptyList<String>(), calls)
        assertEquals(1, fallback)
    }
}
