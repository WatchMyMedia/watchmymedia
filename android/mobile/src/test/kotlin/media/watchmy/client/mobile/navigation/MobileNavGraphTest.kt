package media.watchmy.client.mobile.navigation

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.runBlocking
import media.watchmy.client.fake.FakeAppServices
import media.watchmy.client.fake.FakeCastService
import media.watchmy.client.fake.fakeLatencyMs
import media.watchmy.client.ui.LocalAppServices
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The phone's navigation graph is built once (PLAN-ANDROID-REVIEW §4.3.5):
 * the account, Live TV and a TV connecting change what the pages show, not
 * the graph, which Navigation rebuilds whenever its builder changes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port-xxhdpi")
class MobileNavGraphTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var latency = 0L

    @Before fun noLatency() {
        latency = fakeLatencyMs
        fakeLatencyMs = 0
    }

    @After fun restoreLatency() {
        fakeLatencyMs = latency
    }

    @Test fun `the account and a TV connecting don't rebuild the graph`() {
        val fake = FakeAppServices(cast = true)
        lateinit var nav: NavHostController
        compose.setContent {
            CompositionLocalProvider(LocalAppServices provides fake) {
                nav = rememberNavController()
                MobileNavHost(onSwitchServer = {}, onSignedOut = {}, nav = nav)
            }
        }
        compose.waitForIdle()
        // Rebuilding swaps every destination for a new one, even when the graph is equal.
        val home = nav.graph.findStartDestination()

        runBlocking { fake.auth.signIn("Alex", "password") }
        compose.waitForIdle()
        assertSame("after the account changed", home, nav.graph.findStartDestination())

        (fake.cast as FakeCastService).disconnect()
        compose.waitForIdle()
        assertSame("after the TV disconnected", home, nav.graph.findStartDestination())
    }
}
