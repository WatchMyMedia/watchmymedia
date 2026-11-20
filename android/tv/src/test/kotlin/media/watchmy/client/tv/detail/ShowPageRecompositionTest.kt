package media.watchmy.client.tv.detail

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import media.watchmy.client.fake.FakeAppServices
import media.watchmy.client.fake.fakeLatencyMs
import media.watchmy.client.model.ShowArrival
import media.watchmy.client.tv.TvTheme
import media.watchmy.client.ui.LocalAppServices
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Moving along the TV show page's episode row puts each episode on top,
 * which redraws the top of the page and nothing else: the season tabs and
 * About the show stay as they are. Counted
 * with the Compose runtime's tracer.
 */
@OptIn(ExperimentalTestApi::class, InternalComposeTracingApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class ShowPageRecompositionTest {
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

    @Test fun `moving along the episode row redraws the top, not the season tabs or About`() {
        compose.setContent {
            CompositionLocalProvider(LocalAppServices provides FakeAppServices(tv = true)) {
                TvTheme { ShowPageScreen(showId = HARBOR_LIGHTS, arrival = ShowArrival.Show, onPlay = {}) }
            }
        }
        compose.waitForIdle()
        press(Key.DirectionDown) // The season tabs.
        press(Key.DirectionDown) // The episode row.
        val first = focused()
        Composer.setTracer(runs)

        repeat(3) { press(Key.DirectionRight) }
        assertNotEquals("Focus moved along the row", first, focused())

        assertTrue("The episode on top follows focus", runs["Top"] > 0)
        assertEquals("Season tabs composed again", 0, runs["SeasonTabs"])
        assertEquals("About composed again", 0, runs["About"])
    }

    private fun focused(): String {
        val node = compose.onNode(isFocused()).fetchSemanticsNode()
        return node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
            ?: node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString().orEmpty()
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

    private companion object {
        /** The fakes' Harbor Lights: three seasons, the hero's episode in the third. */
        const val HARBOR_LIGHTS = 100L
    }
}
