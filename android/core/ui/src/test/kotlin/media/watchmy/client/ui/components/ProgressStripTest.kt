package media.watchmy.client.ui.components

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The progress strip reads out how far it is watched, as the Apple apps' does, not a bare percentage. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProgressStripTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `the strip says how much is watched`() {
        compose.setContent { ProgressStrip(0.447f) }
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "45% watched")).assertExists()
    }
}
