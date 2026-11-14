package media.watchmy.client.mobile

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import media.watchmy.client.fake.FakeAppServices
import media.watchmy.client.fake.fakeLatencyMs
import media.watchmy.client.mobile.player.PlayerScreen
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.ui.LocalAppServices
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Home, the show page, the guide and the player's controls at the sizes
 * phones and tablets come in (PLAN-ANDROID-REVIEW §3.3): a phone upright and
 * held sideways (the phone layouts, not the tablet's), a 1280 × 800 tablet,
 * and a phone at font size 2.0, where no text may be cut off.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScreenSizesTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val fake = FakeAppServices()
    private var latency = 0L

    @Before fun noLatency() {
        latency = fakeLatencyMs
        fakeLatencyMs = 0
    }

    @After fun restoreLatency() {
        fakeLatencyMs = latency
    }

    /** What the one composition shows; each screen comes up afresh under its own key. */
    private var screen by mutableStateOf<Pair<String, @Composable () -> Unit>?>(null)

    private fun show(name: String, content: @Composable () -> Unit) {
        if (screen == null) {
            compose.setContent {
                CompositionLocalProvider(LocalAppServices provides fake) { screen?.let { (key, draw) -> key(key) { draw() } } }
            }
        }
        compose.runOnIdle { screen = name to content }
        compose.waitForIdle()
    }

    private fun open(deepLink: String? = null) = show("app $deepLink") { MobileApp(deepLink) }

    private fun player() {
        show("player") { PlayerScreen(PlayRequest(9101, title = "Harbor Lights", subtitle = "S03 E07 · The Long Dark"), onExit = {}) }
        // The fake player starts after 600 ms; its controls show from the start.
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    private fun shown(text: String) =
        compose.onAllNodes(hasText(text, substring = true) or hasContentDescription(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    /**
     * Texts whose lines don't fit the height they were given: a fixed height
     * around text that grows with the font size. Lines left out for maxLines
     * (an ellipsis) are not counted.
     */
    private fun cutOff(): List<String> = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
        .fetchSemanticsNodes().mapNotNull { node ->
            val results = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
            results.firstOrNull()?.takeIf { it.size.height + 1 < it.multiParagraph.height }?.layoutInput?.text?.text
        }

    /** Where the TV Shows tab is: the phone's bottom bar, or the tablet's rail at the left. */
    private fun tabAtBottom(): Boolean {
        val tab = compose.onAllNodes(hasText("TV Shows") and hasClickAction()).onFirst().fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        return tab.top > root.height * 0.7f
    }

    private fun tabletHome() = compose.onAllNodes(hasContentDescription("Screen 1 of", substring = true)).fetchSemanticsNodes().isNotEmpty()

    @Test @Config(qualifiers = "w411dp-h891dp-port-xxhdpi")
    fun `phone upright gets the phone layouts, every screen drawn`() {
        open()
        assertTrue("bottom bar", tabAtBottom())
        assertTrue("Continue watching", shown("Continue watching"))
        open("watchmymedia://shows/100")
        assertTrue("Harbor Lights", shown("Harbor Lights"))
        open("watchmymedia://live")
        assertTrue("All channels", shown("All channels"))
        player()
        assertTrue("Subtitles", shown("Subtitles"))
    }

    @Test @Config(qualifiers = "w891dp-h411dp-land-xxhdpi")
    fun `phone held sideways keeps the phone layouts`() {
        open()
        // 891 dp wide, but 411 tall: not a tablet (§2.7), so the bottom bar and the scrolling Home.
        assertTrue("bottom bar", tabAtBottom())
        assertTrue("phone Home", !tabletHome())
        assertTrue("Harbor Lights", shown("Harbor Lights"))
        open("watchmymedia://shows/100")
        assertTrue("Harbor Lights", shown("Harbor Lights"))
        open("watchmymedia://live")
        assertTrue("All channels", shown("All channels"))
        player()
        assertTrue("Subtitles", shown("Subtitles"))
    }

    @Test @Config(qualifiers = "w1280dp-h800dp-land-xhdpi")
    fun `tablet at 1280 by 800 gets the rail and the tablet Home`() {
        open()
        assertTrue("rail", !tabAtBottom())
        assertTrue("tablet Home", tabletHome())
        open("watchmymedia://shows/100")
        assertTrue("Harbor Lights", shown("Harbor Lights"))
        open("watchmymedia://live")
        assertTrue("All channels", shown("All channels"))
        player()
        assertTrue("Subtitles", shown("Subtitles"))
    }

    @Test @Config(qualifiers = "w411dp-h891dp-port-xxhdpi", fontScale = 2.0f)
    fun `font size 2 cuts no text off on Home, the show page, the guide or the player`() {
        open()
        assertEquals("Home", emptyList<String>(), cutOff())
        open("watchmymedia://shows/100")
        assertEquals("Show page", emptyList<String>(), cutOff())
        open("watchmymedia://live")
        assertEquals("Guide", emptyList<String>(), cutOff())
        player()
        assertEquals("Player", emptyList<String>(), cutOff())
    }
}
