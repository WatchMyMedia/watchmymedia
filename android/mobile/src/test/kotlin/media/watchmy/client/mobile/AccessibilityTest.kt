package media.watchmy.client.mobile

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
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import media.watchmy.client.fake.FakeAppServices
import media.watchmy.client.fake.FakeData
import media.watchmy.client.fake.fakeLatencyMs
import media.watchmy.client.mobile.components.HomeSkeleton
import media.watchmy.client.mobile.components.PosterCard
import media.watchmy.client.mobile.player.ScrubberRow
import media.watchmy.client.mobile.settings.SettingsScreen
import media.watchmy.client.model.PlaybackProgress
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.components.Sheen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What TalkBack hears on the phone: cards, the seek bar and Settings' switch and pickers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AccessibilityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun noLatency() {
        fakeLatencyMs = 0
    }

    @Test
    fun `a poster card reads its title once, and how far it is watched`() {
        val item = FakeData.movies.first().copy(watched = false, progress = 0.45f)
        compose.setContent { PosterCard(item, "https://images.example/poster.jpg", "2024", onClick = {}, onSetWatched = null) }
        val card = compose.onNode(hasClickAction()).fetchSemanticsNode().config
        val texts = card.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty()
        assertEquals("the title once, after the badges: $texts", 1, texts.count { it == item.title })
        assertNull("the artwork has no description of its own", card.getOrNull(SemanticsProperties.ContentDescription))
        assertEquals("45% watched", card.getOrNull(SemanticsProperties.StateDescription))
    }

    @Test
    fun `a loading skeleton says what is loading, and nothing of its shapes`() {
        compose.setContent { HomeSkeleton(sheen = Sheen.Still) }
        compose.onNodeWithContentDescription("Loading Home")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            .assert(SemanticsMatcher("has no children") { it.children.isEmpty() })
    }

    @Test
    fun `the seek bar reads the time, as the TV's does`() {
        compose.setContent { ScrubberRow(progress = { PlaybackProgress(positionMs = 754_000, durationMs = 2_700_000) }, segments = emptyList(), onSeek = {}, onScrubbing = {}) }
        compose.onNodeWithContentDescription("Seek").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "12:34 of 45:00"))
    }

    @Test
    fun `the autoplay row is one switch, and On mobile data picks a quality`() {
        compose.setContent {
            CompositionLocalProvider(LocalAppServices provides FakeAppServices()) {
                SettingsScreen(onSwitchServer = {}, onSignedOut = {}, onLinkTv = {}, onNavigate = {})
            }
        }
        val autoplay = compose.onNode(hasText("Autoplay next episode") and isToggleable())
        autoplay.performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
            .performClick()
        compose.onNode(hasText("Autoplay next episode") and isToggleable())
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))

        compose.onNode(hasText("On mobile data") and hasText("Same as on Wi-Fi")).performScrollTo().performClick()
        compose.onNode(hasText("Same as on Wi-Fi") and hasText("Follows Default quality · Automatic"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        compose.onNodeWithText("720p · 4 Mbps").performScrollTo().performClick()
        compose.onNode(hasText("On mobile data") and hasText("720p")).assertExists()
    }
}
