package media.watchmy.client.tv.search

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
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

    /**
     * With a microphone there are two hints, and on one line they ran from
     * under the keyboard out across the results, where a third row of
     * results (On Live TV) drew over them. Text here is measured far
     * narrower than on a TV, so the test looks at the lines, not the widths.
     */
    @Test
    fun `each key hint has a line of its own under the keyboard`() {
        installRecognizer()
        val keyboard = compose.onNodeWithText("Use the TV’s keyboard").fetchSemanticsNode().boundsInRoot
        val top = compose.onNodeWithText("From the top row: the search box, and ▶ from there the microphone").fetchSemanticsNode().boundsInRoot
        val bottom = compose.onNodeWithText("From the bottom row: the TV’s keyboard").fetchSemanticsNode().boundsInRoot
        assertTrue("the ▼ hint, from ${bottom.top}, is not under the ▲ hint, which ends at ${top.bottom}", bottom.top >= top.bottom)
        assertEquals("the hints' texts start in one column", top.left, bottom.left)
        for (hint in listOf(top, bottom)) {
            assertTrue("a hint runs from ${hint.left} to ${hint.right}, outside the keyboard's ${keyboard.left} to ${keyboard.right}", hint.left >= keyboard.left && hint.right <= keyboard.right)
        }
    }

    /**
     * The list scrolled only as far as the focused card's picture, so with On
     * Live TV as the last row the line under it, which says what OK and Hold
     * OK do, was cut off at the foot of the screen.
     */
    @Test
    fun `the focused Live TV card's key hints are on screen`() {
        focusLiveTvResult()
        val line = liveHints().first()
        val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val top = line.positionInRoot.y
        assertTrue("the line runs from $top to ${top + line.size.height}, off a screen ${screen.bottom} tall", top >= 0f && top + line.size.height <= screen.bottom)
        assertEquals("how much of the line's height shows", line.size.height.toFloat(), line.boundsInRoot.height, 1f)
    }

    /** The card clears its parts' semantics to read as one thing, and its focus went with them: TalkBack had a card that never said it was in focus. */
    @Test
    fun `a Live TV result in focus reads as focused`() {
        focusLiveTvResult()
        val spoken = focused()
        assertTrue("\"$spoken\" has focus, not an On Live TV card", spoken.startsWith("Watch ") || spoken.contains("Opens it in the guide"))
    }

    /** The line stayed under the row once a card had had focus, naming that card and its keys while OK did something else. */
    @Test
    fun `the Live TV row's key hints go when focus leaves the row and come back with it`() {
        focusLiveTvResult()
        press(Key.DirectionUp)
        assertEquals("the row above On Live TV", "Cold Harbour Road", focused())
        assertTrue("the key hints are still under On Live TV", liveHints().isEmpty())
        press(Key.DirectionDown)
        assertTrue("the key hints did not come back with focus", liveHints().isNotEmpty())
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

    /** Types "har", then goes onto the results and down them to an On Live TV card: the line of key hints under that row shows one has focus. */
    private fun focusLiveTvResult() {
        typeHar()
        repeat(7) { press(Key.DirectionRight) }
        assertEquals("Harbor Lights", focused())
        repeat(4) { if (liveHints().isEmpty()) press(Key.DirectionDown) }
        assertTrue("no On Live TV card took focus", liveHints().isNotEmpty())
    }

    /** What OK does for the On Live TV card in focus, in the line under its row. */
    private fun liveHints() = compose.onAllNodes(LIVE_HINT).fetchSemanticsNodes()

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

        /** An On Live TV card's OK: watch what is on now, or open what is on later in the guide. */
        val LIVE_HINT = hasText("Open it in the guide") or hasText("Watch ", substring = true)

        /** Quality and New badges come before a poster's title. */
        val BADGES = Regex("4K|1080p|720p|SD|HDR|HDR10|New|Dolby Vision", RegexOption.IGNORE_CASE)
    }
}
