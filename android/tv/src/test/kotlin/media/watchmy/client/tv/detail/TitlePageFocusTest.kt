package media.watchmy.client.tv.detail

import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import media.watchmy.client.fake.FakeAppServices
import media.watchmy.client.fake.fakeLatencyMs
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.ShowArrival
import media.watchmy.client.tv.TvTheme
import media.watchmy.client.ui.LocalAppServices
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * The movie and show pages' keys on the TV, on the fakes: where focus
 * starts, what ▲ ▼ reach, More and the panels it opens, and Back closing
 * them in turn.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class TitlePageFocusTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var latency = 0L
    private val played = mutableListOf<PlayRequest>()

    @Before fun start() {
        latency = fakeLatencyMs
        fakeLatencyMs = 0
    }

    @After fun stop() {
        fakeLatencyMs = latency
    }

    private fun movie(id: Long) {
        compose.setContent {
            CompositionLocalProvider(LocalAppServices provides FakeAppServices(tv = true)) {
                TvTheme { MovieScreen(id, onPlay = { played += it }, onBack = {}) }
            }
        }
        compose.waitForIdle()
    }

    private fun show(id: Long = 100) {
        compose.setContent {
            CompositionLocalProvider(LocalAppServices provides FakeAppServices(tv = true)) {
                TvTheme { ShowPageScreen(id, ShowArrival.Show, onPlay = { played += it }) }
            }
        }
        compose.waitForIdle()
    }

    @Test fun `a movie partway through opens on Resume, with how long is left and when it ends`() {
        movie(501)
        assertEquals("Resume · 38 min left", focused())
        assertTrue("it says when it would end", exists("ends "))
        press(Key.DirectionUp)
        assertEquals("▲ from the buttons stops", "Resume · 38 min left", focused())
    }

    @Test fun `along the buttons to More, and the cast is one press down, then back up to that button`() {
        movie(501)
        press(Key.DirectionRight)
        assertEquals("From the start", focused())
        repeat(6) { press(Key.DirectionRight) }
        assertEquals("▶ stops at More", "More", focused())
        press(Key.DirectionLeft)
        val before = focused()
        press(Key.DirectionDown)
        assertTrue("${focused()} is in the cast", focused() in listOf("Hollis Kade", "Mira Okonjo"))
        press(Key.DirectionUp)
        assertEquals("▲ from the cast goes back to the button focus came from", before, focused())
    }

    @Test fun `More holds Mark watched and File details, which opens on the version and goes back to More`() {
        movie(500)
        repeat(6) { press(Key.DirectionRight) }
        assertEquals("More", focused())
        press(Key.DirectionCenter)
        assertTrue("More is open", inDialog())
        assertEquals("the first row has focus", "Version", focused())
        assertTrue(exists("Mark watched"))
        repeat(6) { if (focused() != "File details") press(Key.DirectionDown) }
        assertEquals("File details", focused())
        press(Key.DirectionCenter)
        assertTrue("File details is open", exists("File details") && inDialog())
        assertTrue("it says how the file plays here", listOf("Plays on this TV", "The picture plays", "the server converts", "worked out").any(::exists))
        backInDialog()
        assertEquals("Back from File details is More, on File details", "File details", focused())
        backInDialog()
        assertEquals("Back from More puts focus on More", "More", focused())
    }

    @Test fun `OK on a version in File details plays that version`() {
        movie(500)
        repeat(6) { press(Key.DirectionRight) }
        press(Key.DirectionCenter)
        repeat(6) { if (focused() != "File details") press(Key.DirectionDown) }
        press(Key.DirectionCenter)
        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        assertEquals("the second version played", 1, played.size)
    }

    @Test fun `the show opens on Play, down to the season's tab, OK to its episodes, OK back up to Play`() {
        show()
        val play = focused()
        assertTrue("$play is Play or Resume", play.startsWith("Play") || play.startsWith("Resume"))
        press(Key.DirectionDown)
        assertTrue("${focused()} is the season showing", focused().startsWith("Season"))
        press(Key.DirectionCenter)
        val episode = focused()
        assertTrue("$episode is an episode card", episode.first().isDigit())
        press(Key.DirectionRight)
        assertTrue("the next episode", focused() != episode)
        press(Key.DirectionCenter)
        assertTrue("OK on an episode goes up to its Play: ${focused()}", focused().startsWith("Play") || focused().startsWith("Resume"))
        assertTrue("and never plays", played.isEmpty())
    }

    @Test fun `up from the season tabs goes back to the button focus was on`() {
        show()
        press(Key.DirectionRight)
        val button = focused()
        press(Key.DirectionDown)
        assertTrue(focused().startsWith("Season"))
        press(Key.DirectionUp)
        assertEquals(button, focused())
    }

    private fun exists(text: String): Boolean = compose.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun inDialog(): Boolean = compose.onAllNodes(isFocused() and hasAnyAncestor(isDialog())).fetchSemanticsNodes().isNotEmpty()

    /** The focused element's words: a panel's when one is open, since the page under it keeps its own focus. */
    private fun focused(): String {
        val inDialog = compose.onAllNodes(isFocused() and hasAnyAncestor(isDialog())).fetchSemanticsNodes()
        val node = inDialog.firstOrNull() ?: compose.onNode(isFocused() and !hasAnyAncestor(isDialog())).fetchSemanticsNode()
        return node.config.getOrNull(SemanticsProperties.Text)?.firstOrNull { it.text.isNotBlank() && !it.text.equals("New", ignoreCase = true) }?.text
            ?: node.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
            ?: error("The focused element has no text")
    }

    /** A remote key, to the panel when one has focus, else the page. */
    private fun press(key: Key) {
        val target = if (inDialog()) compose.onNode(isFocused() and hasAnyAncestor(isDialog())) else compose.onAllNodes(isRoot()).onFirst()
        target.performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    /** Back in the open panel, which has a window of its own. */
    private fun backInDialog() {
        val dialog = ShadowDialog.getLatestDialog() as? ComponentDialog
        assertNotNull("a panel is open", dialog)
        compose.runOnUiThread { dialog!!.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }
}
