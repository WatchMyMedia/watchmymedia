package media.watchmy.client.tv.player

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import media.watchmy.client.fake.FakeAppServices
import media.watchmy.client.fake.FakePlaybackController
import media.watchmy.client.fake.fakeLatencyMs
import media.watchmy.client.model.AppServices
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.PlaybackController
import media.watchmy.client.model.PlaybackControllerFactory
import media.watchmy.client.tv.TvTheme
import media.watchmy.client.ui.ProvideAppServices
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The TV player's play is held on its nav entry:
 * the screen leaving composition while the entry stays, as in a
 * recreated activity, keeps the play going; leaving the player reports the
 * stop once, and the entry going releases the player.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class PlayerModelTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private var latency = 0L

    /** A fake player that counts what the screen asks of it. */
    private class Counting(private val inner: FakePlaybackController = FakePlaybackController()) : PlaybackController by inner {
        var starts = 0
        var stops = 0
        var releases = 0

        override fun start(request: PlayRequest) {
            starts++
            inner.start(request)
        }

        override fun stop() {
            stops++
            inner.stop()
        }

        override fun release() {
            releases++
            inner.release()
        }
    }

    private val made = mutableListOf<Counting>()

    private fun services(): AppServices {
        val base = FakeAppServices(tv = true)
        return object : AppServices by base {
            override val playback = PlaybackControllerFactory { Counting().also(made::add) }
        }
    }

    private val request = PlayRequest(9101, title = "Harbor Lights")

    @Before fun start() {
        latency = fakeLatencyMs
        fakeLatencyMs = 0
    }

    @After fun stop() {
        fakeLatencyMs = latency
    }

    @Test fun `the play outlives the screen while its entry stays`() {
        var shown by mutableStateOf(true)
        val services = services()
        compose.setContent { ProvideAppServices(services) { TvTheme { if (shown) PlayerScreen(request, onExit = {}) } } }
        compose.waitForIdle()
        val playing = made.single()
        assertEquals(1, playing.starts)

        shown = false
        compose.waitForIdle()
        shown = true
        compose.waitForIdle()
        assertSame("The same player carries on", playing, made.single())
        assertEquals("and isn't started again", 1, playing.starts)
        assertEquals(0, playing.stops)
        assertEquals(0, playing.releases)
    }

    @Test fun `leaving reports the stop once, and the entry going releases the player`() {
        var exits = 0
        val services = services()
        compose.setContent { ProvideAppServices(services) { TvTheme { PlayerScreen(request, onExit = { exits++ }) } } }
        compose.waitForIdle()
        // The controls show at first; Back hides them, then Back leaves.
        compose.onRoot().performKeyInput { pressKey(Key.Back) }
        compose.onRoot().performKeyInput { pressKey(Key.Back) }
        compose.waitForIdle()
        val playing = made.single()
        assertEquals(1, exits)
        assertEquals(1, playing.stops)
        assertEquals("The entry is still there", 0, playing.releases)

        compose.runOnUiThread { compose.activity.viewModelStore.clear() }
        assertEquals(1, playing.releases)
    }
}
