package media.watchmy.client.tv.search

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import media.watchmy.client.fake.fakeLatencyMs
import media.watchmy.client.tv.TvApp
import media.watchmy.client.ui.LocalAppServices
import android.content.ComponentName
import android.content.IntentFilter
import android.speech.RecognitionService
import org.robolectric.Shadows.shadowOf
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit
import media.watchmy.client.tv.quietTvFakes

/** Back from a search result puts focus back on it, not on the keyboard. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class SearchFocusTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun start() {
        fakeLatencyMs = 0
        // Without tonight's reminders, whose banner would take Back first (QuietFakes.kt).
        val services = quietTvFakes()
        compose.setContent {
            CompositionLocalProvider(LocalAppServices provides services) { TvApp() }
        }
        compose.waitForIdle()
        press(Key.DirectionLeft)
        repeat(6) { if (focused() != "Search") press(Key.DirectionUp) }
        assertEquals("Search", focused())
        press(Key.DirectionCenter)
        // Search takes focus onto its keyboard; if it stayed on the rail, move across.
        if (focused() == "Search") press(Key.DirectionRight)
    }

    @Test
    fun `Back from a result goes back to it`() {
        assertEquals("the keyboard's first letter", "q", focused())
        typeHar()

        // Onto the result: along the row from r to its last key, p, and once more.
        repeat(7) { press(Key.DirectionRight) }
        val result = focused()
        assertEquals("Harbor Lights", result)
        press(Key.DirectionCenter)
        assertNotEquals("the show page took focus", result, runCatching { focused() }.getOrNull())

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(result, focused())
    }

    /**
     * The results' list used to start under the keyboard's last column, so a
     * search to the right from 0, p, ' or - found nothing: only the bottom
     * row's wider keys reached the results.
     */
    @Test
    fun `right from the end of every keyboard row reaches the results, and left comes back`() {
        typeHar()
        val rows = listOf("1" to "0", "q" to "p", "a" to "'", "z" to "-", "⇧ abc" to "Clear", "Use the TV’s keyboard" to "Use the TV’s keyboard")
        val lastKeys = rows.map { it.second }
        for ((first, last) in rows) {
            compose.onAllNodesWithText(first).onFirst().performSemanticsAction(SemanticsActions.RequestFocus)
            compose.waitForIdle()
            repeat(10) { if (focused() != last) press(Key.DirectionRight) }
            assertEquals("the end of the row that starts at $first", last, focused())

            press(Key.DirectionRight)
            assertTrue("right from $last reached ${focused()}, not a result", focused() in RESULTS)

            press(Key.DirectionLeft)
            assertTrue("left from the results reached ${focused()}, not the keyboard's edge", focused() in lastKeys)
        }
    }

    @Test
    fun `right from the search box reaches the results`() {
        typeHar()
        compose.onAllNodesWithText("1").onFirst().performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        press(Key.DirectionUp)
        assertEquals("the search box, with what was typed", "har", focused())
        press(Key.DirectionRight)
        assertTrue("right from the search box reached ${focused()}, not a result", focused() in RESULTS)
    }

    @Test
    fun `right from the microphone reaches the results`() {
        installRecognizer()
        typeHar()
        compose.onAllNodesWithText("1").onFirst().performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        press(Key.DirectionUp)
        press(Key.DirectionRight)
        assertEquals("Voice typing", focused())
        press(Key.DirectionRight)
        assertTrue("right from the microphone reached ${focused()}, not a result", focused() in RESULTS)
    }

    @Test
    fun `up from the keyboard's top row reaches the search box, and right from there the microphone`() {
        installRecognizer()
        assertEquals("q", focused())
        press(Key.DirectionUp)
        assertEquals("the top row", "1", focused())
        press(Key.DirectionUp)
        assertTrue("${focused()} is the search box", focused().startsWith("Titles of shows"))
        press(Key.DirectionRight)
        assertEquals("Voice typing", focused())
    }

    /** A speech recognizer on the TV, for the microphone key; the screen is shown again to see it. */
    private fun installRecognizer() {
        val pm = shadowOf(compose.activity.packageManager)
        val service = ComponentName("com.example.speech", "com.example.speech.Recognizer")
        pm.addServiceIfNotPresent(service)
        pm.addIntentFilterForService(service, IntentFilter(RecognitionService.SERVICE_INTERFACE))
        // Search reads it when it is shown: leave for Home and come back.
        press(Key.DirectionLeft)
        repeat(6) { if (focused() != "Home") press(Key.DirectionDown) }
        press(Key.DirectionCenter)
        press(Key.DirectionLeft)
        repeat(6) { if (focused() != "Search") press(Key.DirectionUp) }
        press(Key.DirectionCenter)
        if (focused() == "Search") press(Key.DirectionRight)
    }

    /** Types "har" on the keyboard from q and waits for its results; focus is left on r. */
    private fun typeHar() {
        // h (down a row, five along), a, then r (up a row, three along).
        press(Key.DirectionDown)
        repeat(5) { press(Key.DirectionRight) }
        press(Key.DirectionCenter)
        repeat(5) { press(Key.DirectionLeft) }
        press(Key.DirectionCenter)
        press(Key.DirectionUp)
        repeat(3) { press(Key.DirectionRight) }
        press(Key.DirectionCenter)
        // The search waits a moment after the last key.
        ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS)
        compose.waitForIdle()
    }

    private fun focused(): String {
        val node = compose.onNode(isFocused()).fetchSemanticsNode()
        return node.config.getOrNull(SemanticsProperties.Text)?.firstOrNull { it.text.isNotBlank() && !BADGES.matches(it.text) }?.text
            ?: node.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
            ?: node.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text
            ?: error("The focused element has no text")
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private companion object {
        /** What the fakes have for "har": the show (and its episodes) and the movie. */
        val RESULTS = setOf("Harbor Lights", "Cold Harbour Road")

        /** Quality and New badges come before a poster's title. */
        val BADGES = Regex("4K|1080p|720p|SD|HDR|HDR10|New|Dolby Vision", RegexOption.IGNORE_CASE)
    }
}
