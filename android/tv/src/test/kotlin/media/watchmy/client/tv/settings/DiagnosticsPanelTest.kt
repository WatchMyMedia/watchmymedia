package media.watchmy.client.tv.settings

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import media.watchmy.client.model.Diagnostics
import media.watchmy.client.model.NO_EXITS
import media.watchmy.client.model.ProcessExit
import media.watchmy.client.tv.TvTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings' Copy diagnostics panel (android-tv/TvDiagnostics): the app's
 * last exits in words, and Copy, which puts them on the clipboard and says
 * so.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class DiagnosticsPanelTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val now = 1_800_000_000_000L

    private class Exits(private val exits: List<ProcessExit>) : Diagnostics {
        override suspend fun pastExits() = exits
    }

    @Test fun `lists the last exits, and Copy puts them on the clipboard`() {
        val crash = ProcessExit(now - 60_000, "crash", "java.lang.IllegalStateException", "foreground", hasTrace = false)
        compose.setContent { TvTheme { DiagnosticsPanel(Exits(listOf(crash)), "WatchMyMedia for Android TV 1.4.3 · Android 14 · Streamer", onDismiss = {}, now = { now }) } }
        compose.waitForIdle()
        compose.onNodeWithText("Crashed").assertExists()
        compose.onNodeWithText("on screen · java.lang.IllegalStateException", substring = true).assertExists()
        val copy = compose.onNode(hasText("Copy diagnostics") and hasClickAction())
        copy.assertIsFocused().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("On the clipboard as plain text").assertExists()
        compose.onNode(hasText("Copied") and hasClickAction()).assertExists()
        val clip = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clip.primaryClip?.getItemAt(0)?.text.toString()
        assertTrue(text, text.startsWith("WatchMyMedia for Android TV 1.4.3 · Android 14 · Streamer") && "Crashed · on screen" in text)
    }

    @Test fun `says when Android has recorded no exits`() {
        compose.setContent { TvTheme { DiagnosticsPanel(Diagnostics.None, "App", onDismiss = {}) } }
        compose.waitForIdle()
        compose.onNodeWithText(NO_EXITS).assertExists()
    }
}
