package media.watchmy.client.mobile.player

import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import kotlinx.coroutines.flow.MutableStateFlow
import media.watchmy.client.fake.FakeAppServices
import media.watchmy.client.mobile.ComposableRuns
import media.watchmy.client.model.AudioOption
import media.watchmy.client.model.PlaybackProgress
import media.watchmy.client.model.PlayerStatus
import media.watchmy.client.model.PlayerUiState
import media.watchmy.client.model.UpNext
import media.watchmy.client.model.UpNextPrompt
import media.watchmy.client.ui.ProvideAppServices
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The controls over a playing video: the position's four ticks a second
 * recompose the scrubber row and nothing else ([ComposableRuns] counts).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlayerRecompositionTest {
    @get:Rule val compose = createComposeRule()

    @get:Rule val runs = ComposableRuns()

    private val state = PlayerUiState(
        status = PlayerStatus.Playing,
        title = "Harbor Lights",
        subtitle = "S03 E07 · The Long Dark",
        durationMs = 3_120_000,
        audio = listOf(AudioOption(1, "English · EAC3 5.1 (Default)", "en", true)),
        selectedAudio = 1,
        nextEpisode = UpNext(9102, 1308, 100, "Harbor Lights", 3, 8, "Squall Line", null),
    )
    private val actions = ControlsActions({}, {}, {}, {}, {}, {}, null, {}, {})

    @Test fun `the position's ticks recompose only the scrubber row`() {
        val progress = MutableStateFlow(PlaybackProgress(60_000, 3_120_000, 30_000))
        compose.setContent {
            ProvideAppServices(FakeAppServices()) {
                val p = progress.collectAsState()
                PlayerControls(state, progress = { p.value }, rotationLocked = false, actions = actions)
            }
        }
        compose.waitForIdle()
        runs.clear()

        repeat(TICKS) { tick ->
            progress.value = progress.value.copy(positionMs = 60_000 + (tick + 1) * 250L)
            compose.waitForIdle()
        }

        compose.onNodeWithText("1:02").assertExists()
        assertEquals(TICKS, runs["ScrubberRow"])
        assertEquals(0, runs["PlayerControls"])
        assertEquals(0, runs["TopBar"])
        assertEquals(0, runs["Transport"])
        assertEquals(0, runs["BottomBar"])
    }

    @Test fun `the Next Episode countdown leaves the scrubber alone`() {
        val shown = MutableStateFlow(state)
        val next = state.nextEpisode!!
        compose.setContent {
            ProvideAppServices(FakeAppServices()) {
                val s = shown.collectAsState()
                PlayerControls(s.value, progress = { PlaybackProgress(3_000_000, 3_120_000) }, rotationLocked = false, actions = actions)
            }
        }
        compose.waitForIdle()
        runs.clear()

        repeat(TICKS) { tick ->
            shown.value = state.copy(upNext = UpNextPrompt(next, countdownMs = 10_000L - tick * 250))
            compose.waitForIdle()
        }

        assertEquals(0, runs["ScrubberRow"])
        assertEquals(0, runs["TopBar"])
        assertEquals(0, runs["Transport"])
    }

    private companion object {
        const val TICKS = 8
    }
}
