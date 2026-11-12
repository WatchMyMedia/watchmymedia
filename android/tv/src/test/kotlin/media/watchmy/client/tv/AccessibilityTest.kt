package media.watchmy.client.tv

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import media.watchmy.client.fake.FakeAppServices
import media.watchmy.client.fake.fakeLatencyMs
import media.watchmy.client.model.REPORT_SENT
import media.watchmy.client.tv.components.ArtProgress
import media.watchmy.client.tv.components.MediaCard
import media.watchmy.client.tv.components.TvChip
import media.watchmy.client.tv.detail.MovieScreen
import media.watchmy.client.tv.home.HomeSkeleton
import media.watchmy.client.tv.settings.SettingsScreen
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.components.Sheen
import media.watchmy.client.ui.components.WmmIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What TalkBack hears on the TV (cards, filter chips and Settings' switches), and the movie page's More. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class AccessibilityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun noLatency() {
        fakeLatencyMs = 0
    }

    @Test
    fun `a card reads its title once, and how far it is watched`() {
        compose.setContent {
            TvTheme {
                MediaCard("Dead Reckoning Bay", "2026", "https://images.example/poster.jpg", 160.dp, 240.dp, onClick = {}) { ArtProgress(0.3f) }
            }
        }
        val card = compose.onNode(hasClickAction()).fetchSemanticsNode().config
        assertEquals("the title once", listOf("Dead Reckoning Bay", "2026"), card.getOrNull(SemanticsProperties.Text)?.map { it.text })
        assertNull("the artwork has no description of its own", card.getOrNull(SemanticsProperties.ContentDescription))
        assertEquals("30% watched", card.getOrNull(SemanticsProperties.StateDescription))
    }

    @Test
    fun `Home's loading skeleton says what is loading, and nothing of its shapes`() {
        compose.setContent { TvTheme { HomeSkeleton(sheen = Sheen.Still) } }
        compose.onNodeWithContentDescription("Loading Home")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            .assert(SemanticsMatcher("has no children") { it.children.isEmpty() })
    }

    @Test
    fun `filter chips say what they are and whether they are selected`() {
        compose.setContent {
            TvTheme {
                androidx.compose.foundation.layout.Row {
                    TvChip("Unwatched", selected = true, onClick = {})
                    TvChip("Drama", selected = false, onClick = {})
                    TvChip("Decade", selected = false, onClick = {}, trailingIcon = WmmIcons.ChevronDown)
                }
            }
        }
        compose.onNode(hasText("Unwatched"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        compose.onNode(hasText("Drama")).assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false))
        compose.onNode(hasText("Decade")).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.DropdownList))
    }

    @Test
    fun `the movie page's More reports a problem, as the episode page's does`() {
        compose.setContent {
            CompositionLocalProvider(LocalAppServices provides FakeAppServices(tv = true)) {
                TvTheme { MovieScreen(500, onPlay = {}, onBack = {}) }
            }
        }
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNode(hasText("Report a problem") and hasClickAction()).performClick()
        compose.onNode(hasText("Audio") and hasClickAction()).performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(REPORT_SENT, substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `Settings' switch rows say on or off, and the passthrough panel's choices are radio buttons`() {
        compose.setContent {
            CompositionLocalProvider(LocalAppServices provides FakeAppServices(tv = true)) {
                TvTheme { SettingsScreen(onSwitchServer = {}, onSignedOut = {}, onNavigate = {}) }
            }
        }
        compose.onNode(hasText("Autoplay next episode"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
        val frameRate = compose.onNode(hasText("Match frame rate"))
        frameRate.performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
            .performClick()
        compose.onNode(hasText("Match frame rate")).assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))

        compose.onNode(hasText("Audio passthrough") and hasText("Auto")).performScrollTo().performClick()
        compose.onNode(hasText("Off") and hasText("Stereo or surround as PCM"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false))
            .performClick()
        compose.onNode(hasText("Audio passthrough") and hasText("Off")).assertExists()
    }
}
