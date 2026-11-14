package media.watchmy.client.tv.settings

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import media.watchmy.client.fake.fakeLatencyMs
import media.watchmy.client.tv.TvApp
import media.watchmy.client.tv.quietTvFakes
import media.watchmy.client.ui.LocalAppServices
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings on the D-pad, on the fakes at a 1080p TV's size: back from a page
 * it opened, focus is on the row that had it, not on the first
 * (PLAN-ANDROID-REVIEW.md §3.2.3).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class SettingsFocusTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun start() {
        fakeLatencyMs = 0
        val services = quietTvFakes()
        compose.setContent {
            CompositionLocalProvider(LocalAppServices provides services) { TvApp() }
        }
        compose.waitForIdle()
        openSettings()
        assertEquals("Settings opens on its first row", "Default quality", focused())
    }

    @Test
    fun `Back from Your requests puts focus back on Your requests`() {
        press(Key.DirectionRight)
        assertEquals("Your requests", focused())
        press(Key.DirectionCenter)
        compose.onNodeWithText("Default quality").assertDoesNotExist()

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithText("Default quality").assertExists()
        assertEquals("Back in Settings, Your requests has focus again", "Your requests", focused())
    }

    /** Opens the rail, goes down it to Settings and opens it. */
    private fun openSettings() {
        press(Key.DirectionLeft)
        repeat(8) { if (focused() != "Settings") press(Key.DirectionDown) }
        assertEquals("Settings", focused())
        press(Key.DirectionCenter)
    }

    /** The focused element's title: a row's label, a button's or a rail item's name (not the rail's request badge). */
    private fun focused(): String {
        val node = compose.onNode(isFocused()).fetchSemanticsNode()
        return node.config.getOrNull(SemanticsProperties.Text)?.firstOrNull { it.text.isNotBlank() && it.text.any(Char::isLetter) }?.text
            ?: node.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
            ?: error("The focused element has no text")
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }
}
