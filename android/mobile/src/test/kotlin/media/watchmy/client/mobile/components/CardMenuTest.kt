package media.watchmy.client.mobile.components

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.unit.dp
import media.watchmy.client.fake.FakeData
import media.watchmy.client.ui.WatchMyMediaPhoneTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A card's menu with a mouse or a keyboard:
 * a right-click opens it rather than the card,
 * and so does the Menu key on the focused card.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-land-xhdpi")
class CardMenuTest {
    @get:Rule val compose = createComposeRule()
    private val show = FakeData.shows.first { !it.watched }
    private val opened = mutableListOf<Long>()
    private val marked = mutableListOf<Boolean>()

    private fun card(withMenu: Boolean = true) = compose.setContent {
        WatchMyMediaPhoneTheme {
            PosterCard(show, null, "2022", onClick = { opened += show.id }, onSetWatched = if (withMenu) ({ marked += it }) else null, modifier = Modifier.width(160.dp))
        }
    }

    @Test fun `a right-click opens the card's menu, not the card`() {
        card()
        compose.onNodeWithText(show.title).performMouseInput { rightClick(center) }
        compose.onNodeWithText("Mark as watched").assertExists().performClickSafely()
        assertEquals(emptyList<Long>(), opened)
        assertEquals(listOf(true), marked)
    }

    @Test fun `the Menu key opens the focused card's menu`() {
        card()
        compose.onNodeWithText(show.title).requestFocus()
        compose.onNodeWithText(show.title).performKeyInput { pressKey(Key.Menu) }
        compose.onNodeWithText("Mark as watched").assertExists()
        assertEquals(emptyList<Long>(), opened)
    }

    @Test fun `a card with no menu ignores the right-click`() {
        card(withMenu = false)
        compose.onNodeWithText(show.title).performMouseInput { rightClick(center) }
        compose.onNodeWithText("Mark as watched").assertDoesNotExist()
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.performClickSafely() = also {
        performMouseInput { click(center) }
        compose.waitForIdle()
    }
}
