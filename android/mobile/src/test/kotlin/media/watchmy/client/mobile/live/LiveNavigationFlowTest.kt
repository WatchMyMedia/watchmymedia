package media.watchmy.client.mobile.live

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.runBlocking
import media.watchmy.client.fake.FakeAppServices
import media.watchmy.client.fake.FakeLiveChannels
import media.watchmy.client.fake.FakeLivePlaybackController
import media.watchmy.client.fake.LivingRoom
import media.watchmy.client.fake.OfficeMini
import media.watchmy.client.fake.fakeLatencyMs
import media.watchmy.client.mobile.MobileApp
import media.watchmy.client.model.AppServices
import media.watchmy.client.model.LivePlaybackController
import media.watchmy.client.model.LivePlaybackControllerFactory
import media.watchmy.client.ui.LocalAppServices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port-xxhdpi")
class LiveNavigationFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val fake = FakeAppServices()
    private var released = 0
    private var created = 0
    private val services = object : AppServices by fake {
        override val livePlayback = LivePlaybackControllerFactory {
            created++
            val controller = FakeLivePlaybackController(latencyMs = 0)
            object : LivePlaybackController by controller {
                override fun release() { released++; controller.release() }
            }
        }
    }

    // These cover L6's navigation on the channel-only screens; LiveGuideScreenTest covers listings.
    @Before fun noLatency() {
        fakeLatencyMs = 0
        fake.live.listings = false
    }
    private fun open() {
        compose.setContent { CompositionLocalProvider(LocalAppServices provides services) { MobileApp() } }
        compose.waitForIdle()
    }
    private fun tab(text: String) = compose.onAllNodes(hasText(text) and hasClickAction()).onFirst()
    private fun play() {
        tab("Live TV").performClick()
        compose.onAllNodes(hasText(FakeLiveChannels.first().name) and hasClickAction()).onFirst().performClick()
        compose.waitForIdle()
        assertEquals(1, created)
    }

    @Test
    fun `older server keeps settings tab and has no live destination or Home row`() {
        runBlocking { fake.servers.select(LivingRoom.copy(apiLevel = 10)) }
        open()
        compose.onAllNodes(hasText("Live TV")).assertCountEquals(0)
        assertTrue(compose.onAllNodes(hasText("Settings") and hasClickAction()).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun `profile opens settings while live occupies the old settings tab`() {
        open()
        tab("Live TV").performClick()
        compose.onAllNodes(hasContentDescription("Settings") and hasClickAction()).onFirst().performClick()
        assertTrue(compose.onAllNodes(hasText("Settings")).fetchSemanticsNodes().isNotEmpty())
        assertTrue(compose.onAllNodes(hasText("Sign out") and hasClickAction()).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun `signout removes the live player and releases its controller`() {
        open()
        play()
        runBlocking { fake.auth.signOut() }
        compose.waitForIdle()
        assertEquals(1, released)
        assertTrue(compose.onAllNodes(hasText("Sign in")).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun `server change removes old live navigation and releases its controller`() {
        open()
        play()
        runBlocking { fake.servers.select(OfficeMini) }
        compose.waitForIdle()
        assertEquals(1, released)
        assertEquals(1, created)
        assertTrue(compose.onAllNodes(hasText("Home")).fetchSemanticsNodes().isNotEmpty())
    }
}
