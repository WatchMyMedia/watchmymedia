package media.watchmy.client.tv.library

import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import media.watchmy.client.fake.FakeData
import media.watchmy.client.fake.fakeLatencyMs
import media.watchmy.client.tv.TvApp
import media.watchmy.client.ui.LocalAppServices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import media.watchmy.client.tv.quietTvFakes
import org.robolectric.shadows.ShadowDialog

/**
 * Focus on the TV Shows page: All and Unwatched are one press, everything
 * else is in the Sort and filter panel, which opens on its rows, goes into a
 * row's choices and back with Back, and leaves focus on the control that
 * opened it; Back from a show puts focus on the show's card.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class LibraryFocusTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val shows = FakeData.shows.map { it.title }.toSet()

    @Before
    fun start() {
        fakeLatencyMs = 0
        // Without tonight's reminders, whose banner would take Back first (QuietFakes.kt).
        // One server: these rows and grids are the Living room server's own, as another server's titles would join them.
        val services = quietTvFakes(several = false)
        compose.setContent {
            CompositionLocalProvider(LocalAppServices provides services) { TvApp() }
        }
        compose.waitForIdle()
        // Home, then the rail's TV Shows.
        press(Key.DirectionLeft)
        repeat(6) { if (focused() != "TV Shows") press(Key.DirectionDown) }
        assertEquals("TV Shows", focused())
        press(Key.DirectionCenter)
        press(Key.DirectionRight)
    }

    @Test
    fun `a filter chosen in the panel applies at once, Back goes back a step, and focus returns to Filter`() {
        toControls()
        repeat(2) { press(Key.DirectionRight) }
        assertEquals("Filter", focused())
        press(Key.DirectionCenter)
        compose.onNodeWithText("Sort and filter").assertExists()
        assertEquals("the panel opens on its first row", "Show", focused())
        repeat(6) { if (focused() != "Status") press(Key.DirectionDown) }
        assertEquals("Status", focused())
        press(Key.DirectionCenter)
        assertEquals("a row's choices open on the one chosen", "Any", focused())
        repeat(3) { if (focused() != "Ended") press(Key.DirectionDown) }
        press(Key.DirectionCenter)
        assertEquals("a choice applies and the list stays", "Ended", focused())

        back()
        assertEquals("Back goes from the choices to the row that opened them", "Status", focused())
        compose.onNodeWithText("Ended").assertExists()
        back()
        compose.onNodeWithText("Sort and filter").assertDoesNotExist()
        assertEquals("Back closes the panel onto Filter, which counts what is on", "Filter · 1", focused())
        press(Key.DirectionLeft)
        press(Key.DirectionLeft)
        assertEquals("All", focused())
        press(Key.DirectionCenter)
        press(Key.DirectionRight)
        press(Key.DirectionRight)
        assertEquals("All clears the filter", "Filter", focused())
    }

    @Test
    fun `Back from a show puts focus back on its card in the grid`() {
        toControls()
        press(Key.DirectionDown)
        val card = focused()
        assertTrue("$card is a show in the grid", card in shows)
        press(Key.DirectionRight)
        val next = focused()
        assertTrue("$next is a show in the grid", next in shows)

        press(Key.DirectionCenter)
        assertNotEquals("the show page took focus", next, runCatching { focused() }.getOrNull())
        back()
        assertEquals(next, focused())
    }

    /** Down the page until the grid's controls, on All. */
    private fun toControls() {
        repeat(20) { if (focused() !in CONTROLS) press(Key.DirectionDown) }
        repeat(4) { if (focused() != "All") press(Key.DirectionLeft) }
        assertEquals("All", focused())
    }

    /** The focused element: in the panel while one is open (its window has a focus of its own), else on the page. */
    private fun focused(): String {
        val inDialog = compose.onAllNodes(isFocused() and hasAnyAncestor(isDialog())).fetchSemanticsNodes()
        val node = inDialog.firstOrNull() ?: compose.onNode(isFocused() and !hasAnyAncestor(isDialog())).fetchSemanticsNode()
        return node.config.getOrNull(SemanticsProperties.Text)?.firstOrNull { it.text.isNotBlank() && !BADGES.matches(it.text) }?.text
            ?: node.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
            // A chip that reads as a badge (4K, HDR).
            ?: node.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text
            ?: error("The focused element has no text")
    }

    /** A key to what has focus: in the panel while one is open (a window of its own), else on the page. */
    private fun press(key: Key) {
        val inDialog = compose.onAllNodes(isFocused() and hasAnyAncestor(isDialog())).fetchSemanticsNodes().isNotEmpty()
        val target = if (inDialog) compose.onNode(isFocused() and hasAnyAncestor(isDialog())) else compose.onAllNodes(isRoot()).onFirst()
        target.performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    /** Back in the open panel, which has a window of its own, else on the page. */
    private fun back() {
        val dialog = ShadowDialog.getLatestDialog() as? ComponentDialog
        if (dialog != null && dialog.isShowing) {
            compose.runOnUiThread { dialog.onBackPressedDispatcher.onBackPressed() }
        } else {
            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        }
        compose.waitForIdle()
    }

    private companion object {
        val CONTROLS = setOf("All", "Unwatched", "Filter", "Sort: Date added")

        /** Quality and New badges come before a poster's title. */
        val BADGES = Regex("4K|1080p|720p|SD|HDR|HDR10|New|Dolby Vision", RegexOption.IGNORE_CASE)
    }
}
