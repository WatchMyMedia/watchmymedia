package media.watchmy.client.tv.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import kotlinx.coroutines.flow.MutableStateFlow
import media.watchmy.client.fake.FakeAppServices
import media.watchmy.client.fake.FakeData
import media.watchmy.client.model.AudioOption
import media.watchmy.client.model.PlaybackProgress
import media.watchmy.client.model.PlayerStatus
import media.watchmy.client.model.PlayerUiState
import media.watchmy.client.model.tracks
import media.watchmy.client.ui.ProvideAppServices
import media.watchmy.client.ui.components.SEEK_THUMBNAIL_TAG
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The TV's controls over a playing video: the position's four ticks a
 * second recompose the scrubber row and nothing else, and the picker's rows
 * stay as they are. Counted with the Compose runtime's tracer, which a
 * composable reports to when it runs (not when it is skipped).
 */
@OptIn(InternalComposeTracingApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class PlayerRecompositionTest {
    @get:Rule val compose = createComposeRule()

    private val runs = Runs()

    private val state = PlayerUiState(
        status = PlayerStatus.Playing,
        title = "Harbor Lights",
        subtitle = "S03 E07 · The Long Dark",
        durationMs = 3_120_000,
        audio = listOf(AudioOption(1, "English · EAC3 5.1 (Default)", "en", true), AudioOption(2, "Spanish · AAC 2.0", "es", false)),
        selectedAudio = 1,
    )
    private val actions = ChromeActions({}, {}, {}, {}, {}, {})

    @Before fun trace() = Composer.setTracer(runs)

    @After fun stopTracing() = Composer.setTracer(Runs(tracing = false))

    @Test fun `the position's ticks recompose only the scrubber row`() {
        val progress = MutableStateFlow(PlaybackProgress(60_000, 3_120_000, 30_000))
        compose.setContent {
            val p = progress.collectAsState()
            val focus = remember {
                ChromeFocus(FocusRequester(), null, FocusRequester(), FocusRequester(), PickerSection.entries.associateWith { FocusRequester() })
            }
            Box(Modifier.fillMaxSize()) {
                PlayerChrome(state, positionMs = { p.value.positionMs }, progress = { p.value }, focus = focus, actions = actions)
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
        assertEquals(0, runs["PlayerChrome"])
        assertEquals(0, runs["TitleBlock"])
        assertEquals(0, runs["TransportRow"])
    }

    @Test fun `a held seek recomposes the scrubber row and the seek thumbnail's time, and nothing else`() {
        val seek = SeekState()
        val seeking = state.copy(trickplay = FakeData.trickplay)
        compose.setContent {
            val focus = remember {
                ChromeFocus(FocusRequester(), null, FocusRequester(), FocusRequester(), PickerSection.entries.associateWith { FocusRequester() })
            }
            ProvideAppServices(FakeAppServices(tv = true)) {
                Box(Modifier.fillMaxSize()) {
                    PlayerChrome(seeking, positionMs = { seek.displayed(60_000) }, progress = { PlaybackProgress(60_000, 3_120_000) }, focus = focus, actions = actions, seek = seek)
                }
            }
        }
        compose.runOnIdle { seek.press(forward = true, positionMs = 60_000, durationMs = 3_120_000, nowMs = 0, repeat = false) }
        compose.onNodeWithTag(SEEK_THUMBNAIL_TAG).assertExists()
        runs.clear()

        // The key held: a step every 300 ms, growing.
        repeat(TICKS) { tick ->
            compose.runOnIdle { seek.press(forward = true, positionMs = 60_000, durationMs = 3_120_000, nowMs = 300L * (tick + 1), repeat = true) }
            compose.waitForIdle()
        }

        assertEquals(TICKS, runs["ScrubberRow"])
        assertEquals(TICKS, runs["SeekTime"])
        assertEquals(0, runs["SeekThumbnail"])
        assertEquals(0, runs["PlayerChrome"])
        assertEquals(0, runs["TitleBlock"])
        assertEquals(0, runs["TransportRow"])
    }

    @Test fun `the picker keeps its rows while the rest of the state changes`() {
        val shown = MutableStateFlow(state)
        compose.setContent {
            val s = shown.collectAsState()
            val focus = remember { PickerSection.entries.associateWith { FocusRequester() } }
            PickerSheet(s.value.tracks, focus, onChoose = {})
        }
        compose.waitForIdle()
        runs.clear()

        // The Skip button filling, then a pause: the tracks are the same.
        repeat(TICKS) { tick -> shown.value = state.copy(status = if (tick % 2 == 0) PlayerStatus.Paused else PlayerStatus.Playing); compose.waitForIdle() }
        assertEquals(0, runs["PickerSheet"])
        assertEquals(0, runs["PickerOption"])

        shown.value = state.copy(selectedAudio = 2)
        compose.waitForIdle()
        assertEquals(1, runs["PickerSheet"])
    }

    /** How many times each composable ran, by name. */
    private class Runs(private val tracing: Boolean = true) : CompositionTracer {
        private val counts = mutableMapOf<String, Int>()

        operator fun get(name: String) = synchronized(counts) { counts[name] ?: 0 }

        fun clear() = synchronized(counts) { counts.clear() }

        override fun isTraceInProgress() = tracing

        // info is "media.watchmy.client.tv.player.ScrubberRow (PlayerChrome.kt:138)".
        override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
            val name = info.substringBefore(" (").substringAfterLast('.')
            synchronized(counts) { counts[name] = (counts[name] ?: 0) + 1 }
        }

        override fun traceEventEnd() = Unit
    }

    private companion object {
        const val TICKS = 8
    }
}
