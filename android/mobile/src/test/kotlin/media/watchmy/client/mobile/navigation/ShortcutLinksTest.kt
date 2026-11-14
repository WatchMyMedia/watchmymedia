package media.watchmy.client.mobile.navigation

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasClickAction
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import media.watchmy.client.fake.FakeAppServices
import media.watchmy.client.fake.fakeLatencyMs
import media.watchmy.client.model.DeepLink
import media.watchmy.client.ui.LocalAppServices
import androidx.navigation.NavDestination.Companion.hasRoute
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The phone launcher's shortcuts' links open Search and Downloads, and a
 * keyboard's Ctrl+F, / or Search key opens Search (PLAN-ANDROID-REVIEW §6.3).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port-xxhdpi")
class ShortcutLinksTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var latency = 0L

    @Before fun noLatency() {
        latency = fakeLatencyMs
        fakeLatencyMs = 0
    }

    @After fun restoreLatency() {
        fakeLatencyMs = latency
    }

    private val shortcuts = KeyboardShortcuts()

    private fun open(link: DeepLink?): NavHostController {
        lateinit var nav: NavHostController
        compose.setContent {
            CompositionLocalProvider(LocalAppServices provides FakeAppServices(), LocalKeyboardShortcuts provides shortcuts) {
                nav = rememberNavController()
                MobileNavHost(onSwitchServer = {}, onSignedOut = {}, nav = nav, deepLink = link)
            }
        }
        compose.waitForIdle()
        return nav
    }

    @Test fun `the Search shortcut opens Search, ready to type`() {
        val nav = open(DeepLink.Search)
        assertTrue(nav.currentDestination!!.hasRoute<MobileRoutes.Search>())
        assertTrue(compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun `Ctrl+F, slash and the Search key are Search, and F or Ctrl+slash aren't`() {
        assertTrue(KeyboardShortcuts.isSearch(KeyEvent.KEYCODE_F, ctrl = true, otherModifier = false))
        assertTrue(KeyboardShortcuts.isSearch(KeyEvent.KEYCODE_SLASH, ctrl = false, otherModifier = false))
        assertTrue(KeyboardShortcuts.isSearch(KeyEvent.KEYCODE_SEARCH, ctrl = false, otherModifier = false))
        assertFalse(KeyboardShortcuts.isSearch(KeyEvent.KEYCODE_F, ctrl = false, otherModifier = false))
        assertFalse(KeyboardShortcuts.isSearch(KeyEvent.KEYCODE_SLASH, ctrl = true, otherModifier = false))
        assertFalse(KeyboardShortcuts.isSearch(KeyEvent.KEYCODE_F, ctrl = true, otherModifier = true))
    }

    @Test fun `Ctrl+F no screen took opens Search`() {
        val nav = open(null)
        val ctrlF = KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_F, 0, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)
        compose.runOnUiThread { assertTrue(shortcuts.onKeyUp(KeyEvent.KEYCODE_F, ctrlF)) }
        compose.waitForIdle()
        assertTrue(nav.currentDestination!!.hasRoute<MobileRoutes.Search>())
    }

    @Test fun `the Live TV shortcut opens the guide, not the page last opened from it`() {
        val link = mutableStateOf<DeepLink?>(null)
        lateinit var nav: NavHostController
        compose.setContent {
            CompositionLocalProvider(LocalAppServices provides FakeAppServices(), LocalKeyboardShortcuts provides shortcuts) {
                nav = rememberNavController()
                MobileNavHost(onSwitchServer = {}, onSignedOut = {}, nav = nav, deepLink = link.value)
            }
        }
        compose.waitForIdle()
        // A show page opened from the Live TV tab, then Home.
        compose.onAllNodes(hasText("Live TV") and hasClickAction()).onFirst().performClick()
        compose.runOnIdle {
            nav.navigate(MobileRoutes.Show(100))
            // To Home as a tab tap goes (the bar hides on a show page), keeping Live TV's pages for its tab.
            nav.navigate(MobileRoutes.Home) {
                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
            }
        }
        compose.runOnIdle { link.value = DeepLink.LiveGuide }
        compose.waitForIdle()
        assertTrue(nav.currentDestination!!.hasRoute<MobileRoutes.LiveTV>())
    }

    @Test fun `the Downloads shortcut opens Downloads`() {
        val nav = open(DeepLink.Downloads)
        assertTrue(nav.currentDestination!!.hasRoute<MobileRoutes.Downloads>())
    }
}
