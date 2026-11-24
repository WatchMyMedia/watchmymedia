package media.watchmy.client.tv.live

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import kotlinx.coroutines.runBlocking
import media.watchmy.client.fake.FakeAppServices
import media.watchmy.client.fake.fakeLatencyMs
import media.watchmy.client.model.LiveCapabilities
import media.watchmy.client.presentation.live.LiveChannelsViewModel
import media.watchmy.client.tv.TvTheme
import media.watchmy.client.tv.components.LocalLiveTVAvailable
import media.watchmy.client.ui.LocalAppServices
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Moving focus through the TV guide changes its selection, and with it the
 * hero, and nothing else: no row and no programme cell composes again.
 * Counted with the Compose runtime's
 * tracer, which a composable reports to when it runs, not when it skips.
 */
@OptIn(ExperimentalTestApi::class, InternalComposeTracingApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class GuideRecompositionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val runs = Runs()
    private var latency = 0L

    @Before fun start() {
        latency = fakeLatencyMs
        fakeLatencyMs = 0
    }

    @After fun stop() {
        fakeLatencyMs = latency
        Composer.setTracer(Runs(tracing = false))
    }

    @Test fun `moving through the guide recomposes the hero, not the rows`() {
        // No favourites, so the guide opens on All channels and every channel is in it.
        val services = FakeAppServices(tv = true).also { it.live.favourites = emptySet() }
        val listed = runBlocking { services.live.channels() }
        compose.setContent {
            CompositionLocalProvider(LocalAppServices provides services, LocalLiveTVAvailable provides true) {
                TvTheme {
                    LiveChannelsScreen(
                        LiveChannelsViewModel.State(supported = true, guideLevel = true, capabilities = LiveCapabilities(true, true, true, guide = true), channels = listed, selectedId = "live-2-2"),
                        onSelect = {}, onWatch = {}, onRefresh = {}, onNavigate = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Watch 2.2").assertExists()
        Composer.setTracer(runs)

        // Down and up the channels: the selection and the hero change; the window stays (◀ ▶ at its edge would move it,
        // and every row with it, as it should).
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        press(Key.DirectionUp)
        compose.onNodeWithText("Watch 2.3", substring = true).assertExists()

        assertTrue("The hero follows focus", runs["TvGuideHero"] > 0)
        assertEquals("Rows composed again", 0, runs["TvGuideRow"])
        assertEquals("Programme cells composed again", 0, runs["TvGuideCell"])
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    /** How many times each composable ran, by name. */
    private class Runs(private val tracing: Boolean = true) : CompositionTracer {
        private val counts = mutableMapOf<String, Int>()

        operator fun get(name: String) = synchronized(counts) { counts[name] ?: 0 }

        override fun isTraceInProgress() = tracing

        override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
            val name = info.substringBefore(" (").substringAfterLast('.')
            synchronized(counts) { counts[name] = (counts[name] ?: 0) + 1 }
        }

        override fun traceEventEnd() = Unit
    }
}
