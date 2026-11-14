package media.watchmy.client.mobile.navigation

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import media.watchmy.client.fake.FakeAppServices
import media.watchmy.client.fake.fakeLatencyMs
import media.watchmy.client.model.DeepLink
import media.watchmy.client.ui.LocalAppServices
import androidx.navigation.NavDestination.Companion.hasRoute
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The phone launcher's shortcuts' links open Search and Downloads (PLAN-ANDROID-REVIEW §6.3). */
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

    private fun open(link: DeepLink): NavHostController {
        lateinit var nav: NavHostController
        compose.setContent {
            CompositionLocalProvider(LocalAppServices provides FakeAppServices()) {
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

    @Test fun `the Downloads shortcut opens Downloads`() {
        val nav = open(DeepLink.Downloads)
        assertTrue(nav.currentDestination!!.hasRoute<MobileRoutes.Downloads>())
    }
}
