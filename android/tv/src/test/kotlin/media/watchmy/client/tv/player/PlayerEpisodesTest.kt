package media.watchmy.client.tv.player

import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import kotlinx.coroutines.runBlocking
import media.watchmy.client.fake.FakeAppServices
import media.watchmy.client.fake.FakeLibraryRepository
import media.watchmy.client.fake.fakeLatencyMs
import media.watchmy.client.model.EpisodeContext
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.episodeCode
import media.watchmy.client.tv.TvTheme
import media.watchmy.client.ui.ProvideAppServices
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
 * The TV player's episode list (android-tv/TvPlayerEpisodes), on the fakes:
 * Episodes, before Audio, opens it on the playing episode; OK on another
 * plays it; the remote's Previous plays the one before; Back closes it and
 * puts focus back on Episodes.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class PlayerEpisodesTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private var latency = 0L

    @Before fun start() {
        latency = fakeLatencyMs
        fakeLatencyMs = 0
    }

    @After fun stop() {
        fakeLatencyMs = latency
    }

    @Test fun `Episodes opens the list on the playing episode, and OK on another plays it`() {
        val episodes = runBlocking { FakeLibraryRepository().season(HARBOR_LIGHTS, 3).episodes.filter { it.versions.isNotEmpty() } }
        val playing = episodes[2]
        val next = episodes[3]
        val request = PlayRequest(
            playing.versions.first().fileId, title = "Harbor Lights", subtitle = "${episodeCode(3, playing.number)} · ${playing.title}",
            episodeContext = EpisodeContext(HARBOR_LIGHTS, 3),
        )
        compose.setContent { ProvideAppServices(FakeAppServices(tv = true)) { TvTheme { PlayerScreen(request, onExit = {}) } } }
        compose.waitForIdle()

        compose.onNode(hasText("Episodes") and hasClickAction()).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("PLAYING").assertExists()
        assertTrue("The playing episode has focus: ${focused()}", focused().startsWith("Episode ${playing.number}, "))

        press(Key.DirectionDown)
        assertTrue(focused().startsWith("Episode ${next.number}, "))
        press(Key.DirectionCenter)
        compose.onNodeWithText("PLAYING").assertDoesNotExist()
        compose.onNodeWithText("${episodeCode(3, next.number)} · ${next.title}").assertExists()

        // The remote's Previous plays the episode before.
        compose.onRoot().performKeyInput { pressKey(Key.MediaPrevious) }
        compose.waitForIdle()
        compose.onNodeWithText("${episodeCode(3, playing.number)} · ${playing.title}").assertExists()

        // Open it again: the list follows what plays now, and Back puts focus on Episodes.
        compose.onNode(hasText("Episodes") and hasClickAction()).performClick()
        compose.waitForIdle()
        assertTrue(focused().startsWith("Episode ${playing.number}, "))
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithText("PLAYING").assertDoesNotExist()
        assertEquals("Episodes", focused())
    }

    @Test fun `Episodes opens a list that arrived after the player`() {
        val episode = runBlocking { FakeLibraryRepository().season(HARBOR_LIGHTS, 3).episodes.first { it.versions.isNotEmpty() } }
        // Only the file: the player asks the server for the show, so the list comes a moment after the first frame.
        val request = PlayRequest(episode.versions.first().fileId, title = "Harbor Lights")
        fakeLatencyMs = 200
        compose.setContent { ProvideAppServices(FakeAppServices(tv = true)) { TvTheme { PlayerScreen(request, onExit = {}) } } }
        // Episodes shows once the list is in.
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Episodes") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }

        compose.onNode(hasText("Episodes") and hasClickAction()).performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("PLAYING")).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun focused(): String {
        val node = compose.onNode(isFocused()).fetchSemanticsNode()
        return node.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
            ?: node.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text
            ?: ""
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private companion object {
        const val HARBOR_LIGHTS = 100L
    }
}
