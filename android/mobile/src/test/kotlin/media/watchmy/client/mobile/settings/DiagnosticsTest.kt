package media.watchmy.client.mobile.settings

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import media.watchmy.client.fake.FakeAppServices
import media.watchmy.client.fake.fakeLatencyMs
import media.watchmy.client.model.AppServices
import media.watchmy.client.model.Diagnostics
import media.watchmy.client.model.ProcessExit
import media.watchmy.client.model.SettingsGroup
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.WatchMyMediaPhoneTheme
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Copy diagnostics in Settings › About; its words are tested in core:model. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port-xxhdpi")
class DiagnosticsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun at(day: Int, hour: Int, minute: Int) = LocalDateTime.of(2026, 11, day, hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val now = at(13, 22, 0)
    private val crash = ProcessExit(at(12, 23, 2), "crash", "java.lang.IllegalStateException", "foreground", hasTrace = false)
    private val memory = ProcessExit(at(13, 21, 41), "low memory", null, "cached", hasTrace = false)
    private val stopped = ProcessExit(at(11, 20, 15), "stopped by the user", null, "cached", hasTrace = false)
    private var latency = 0L

    @Before fun noLatency() {
        latency = fakeLatencyMs
        fakeLatencyMs = 0
    }

    @After fun restoreLatency() {
        fakeLatencyMs = latency
    }

    @Test fun `the row opens the last exits, and Copy puts them on the clipboard`() {
        val services = object : AppServices by FakeAppServices() {
            override val diagnostics = object : Diagnostics {
                override suspend fun pastExits() = listOf(memory, crash, stopped)
            }
        }
        compose.setContent {
            CompositionLocalProvider(LocalAppServices provides services) {
                WatchMyMediaPhoneTheme { SettingsScreen(onSwitchServer = {}, onSignedOut = {}, onLinkTv = {}, onNavigate = {}, open = SettingsGroup.About) }
            }
        }
        compose.onNodeWithText("Copy diagnostics").performScrollTo().performClick()
        compose.onNodeWithText("Crashed").assertExists()
        compose.onNodeWithText("Closed by Android to free memory").assertExists()
        // The shared words (core:model), with the phone board's own for a force stop.
        compose.onNodeWithText("Stopped by you").assertExists()
        compose.onNodeWithText("Copy").performClick()
        compose.onNodeWithText("Copied").assertExists()
        val clip = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clip.primaryClip!!.getItemAt(0).text.toString()
        assertTrue(text, text.startsWith("WatchMyMedia "))
        assertTrue(text, text.contains("Crashed · on screen · java.lang.IllegalStateException"))
    }
}
