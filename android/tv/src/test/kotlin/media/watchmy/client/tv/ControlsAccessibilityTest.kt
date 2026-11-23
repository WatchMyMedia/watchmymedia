package media.watchmy.client.tv

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import media.watchmy.client.tv.components.TvButton
import media.watchmy.client.tv.components.TvChip
import media.watchmy.client.tv.player.ControlButton
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The TV's controls to TalkBack: buttons and
 * the player's controls are buttons, and a chip keeps its own role. (Their
 * growing with the TV's text size is checked on an emulator: Robolectric
 * measures text the same at every font scale.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class ControlsAccessibilityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `buttons and the player's controls are buttons, and a chip is still a checkbox`() {
        compose.setContent {
            TvTheme {
                Column {
                    TvButton("Sign in", {})
                    ControlButton({}, text = "Quality", detail = "Automatic")
                    TvChip("Movies", selected = false, onClick = {})
                }
            }
        }
        compose.onNode(hasText("Sign in") and hasClickAction()).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        compose.onNode(hasText("Quality · Automatic") and hasClickAction(), useUnmergedTree = false).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        compose.onNode(hasText("Movies") and hasClickAction()).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
    }
}
