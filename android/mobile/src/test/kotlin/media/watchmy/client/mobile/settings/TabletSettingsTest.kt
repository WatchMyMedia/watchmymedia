package media.watchmy.client.mobile.settings

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import media.watchmy.client.fake.FakeAppServices
import media.watchmy.client.fake.fakeLatencyMs
import media.watchmy.client.model.SettingsGroup
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.WatchMyMediaPhoneTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The tablet's list beside its page. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-land-mdpi")
class TabletSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var latency = 0L

    @Before fun noLatency() {
        latency = fakeLatencyMs
        fakeLatencyMs = 0
    }

    @After fun restoreLatency() {
        fakeLatencyMs = latency
    }

    @Test fun `the list sits beside Playback's page, and a group opens beside it`() {
        var opened: Pair<SettingsGroup, Boolean>? = null
        compose.setContent {
            CompositionLocalProvider(LocalAppServices provides FakeAppServices()) {
                WatchMyMediaPhoneTheme {
                    SettingsScreen(onAddServer = {}, onSignedOut = {}, onLinkTv = {}, onNavigate = {}, onOpenPage = { group, beside -> opened = group to beside })
                }
            }
        }
        compose.waitForIdle()
        compose.onNode(hasText("Playback") and isSelectable()).assertIsSelected()
        // The page's own rows, at once, beside the list.
        compose.onNodeWithText("Default quality").assertExists()
        compose.onNodeWithText("ON THIS TABLET").assertExists()
        compose.onNode(hasText("Account & devices") and isSelectable()).performClick()
        assertEquals(SettingsGroup.Account to true, opened)
    }

    @Test fun `Account & devices opens beside the list, at most 720 wide`() {
        compose.setContent {
            CompositionLocalProvider(LocalAppServices provides FakeAppServices()) {
                WatchMyMediaPhoneTheme {
                    SettingsScreen(onAddServer = {}, onSignedOut = {}, onLinkTv = {}, onNavigate = {}, open = SettingsGroup.Account)
                }
            }
        }
        compose.waitForIdle()
        compose.onNode(hasText("Account & devices") and isSelectable()).assertIsSelected()
        val row = compose.onNodeWithText("Change password", substring = true).getUnclippedBoundsInRoot()
        assertTrue("capped at 720: ${row.right - row.left}", row.right - row.left <= 720.dp)
        compose.onNodeWithText("Sign out of this tablet…").assertExists()
    }
}
