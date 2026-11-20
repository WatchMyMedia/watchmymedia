package media.watchmy.client.presentation.player

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import media.watchmy.client.fake.FakePlaybackController
import media.watchmy.client.model.EpisodeContext
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.PlaybackController
import media.watchmy.client.model.PlaybackControllerFactory
import media.watchmy.client.model.PlaybackProgress
import media.watchmy.client.model.PlayerEvent
import media.watchmy.client.model.PlayerStatus
import media.watchmy.client.model.PlayerUiState
import media.watchmy.client.model.SubtitleRequestMode
import media.watchmy.client.presentation.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The player's play kept on its nav entry: a
 * recreated activity keeps the controller and doesn't restart it, and
 * process death picks the play up at the same file and place.
 */
class PlayerViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val episodeOne = PlayRequest(9101, title = "Harbor Lights", subtitle = "S03 E07 · The Long Dark", episodeContext = EpisodeContext(100, 3))
    private val made = mutableListOf<TestController>()
    private val factory = PlaybackControllerFactory { TestController().also { made += it } }

    private fun model(saved: SavedStateHandle = SavedStateHandle(), request: PlayRequest = episodeOne) =
        PlayerViewModel(factory, saved, request)

    @Test fun `a recreated screen gets the same controller, still playing, and doesn't start it again`() = runTest(main.dispatcher) {
        val vm = model()
        val first = vm.controller()
        vm.start(first)
        // The activity is recreated: a new composition asks again.
        val again = vm.controller()
        vm.start(again)
        assertSame(first, again)
        assertEquals(listOf(9101L), made[0].starts.map { it.fileId })
        assertEquals(1, made.size)
    }

    @Test fun `choosing a play, even the same one again, starts it`() = runTest(main.dispatcher) {
        val vm = model()
        val controller = vm.controller()
        vm.start(controller)
        runCurrent()
        made[0].events.emit(PlayerEvent.StartedNext(9102))
        runCurrent()
        assertEquals(9102L, vm.fileId.value)
        vm.select(episodeOne)
        vm.start(controller)
        assertEquals(listOf(9101L, 9101L), made[0].starts.map { it.fileId })
        assertEquals(9101L, vm.fileId.value)
    }

    @Test fun `after process death the play starts again at the same file and place`() = runTest(main.dispatcher) {
        val saved = SavedStateHandle()
        val vm = model(saved)
        vm.start(vm.controller())
        runCurrent()
        val c = made[0]
        // It moved on to the next episode, with Spanish audio and English subtitles, and reached 12:34.
        c.events.emit(PlayerEvent.StartedNext(9102))
        c.state.value = PlayerUiState(PlayerStatus.Playing, title = "Harbor Lights", subtitle = "S03 E08 · Squall Line", selectedAudio = 3, selectedSubtitle = "s4")
        c.progress.value = PlaybackProgress(positionMs = 754_000)
        runCurrent()

        // The process dies; the nav entry comes back with its saved state and its route's request.
        val restored = model(SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) }))
        restored.start(restored.controller())
        val request = made[1].starts.single()
        assertEquals(9102L, request.fileId)
        assertEquals(754_000L, request.startMs)
        assertEquals("S03 E08 · Squall Line", request.subtitle)
        assertEquals(3, request.audioIndex)
        assertEquals("s4", request.subtitleId)
        // The route's season was the first episode's; the next one's is looked up again.
        assertNull(request.episodeContext)
        assertEquals(9102L, restored.fileId.value)
    }

    @Test fun `subtitles turned off stay off after process death, and an unloaded play keeps the request's choice`() = runTest(main.dispatcher) {
        val saved = SavedStateHandle()
        val vm = model(saved)
        vm.start(vm.controller())
        runCurrent()
        // Still loading: no track chosen yet is not "off".
        made[0].state.value = PlayerUiState(PlayerStatus.Loading)
        runCurrent()
        assertEquals(SubtitleRequestMode.Auto, model(saved).selection.value.request.subtitleMode)
        made[0].state.value = PlayerUiState(PlayerStatus.Playing, selectedSubtitle = null)
        runCurrent()
        assertEquals(SubtitleRequestMode.Off, model(saved).selection.value.request.subtitleMode)
    }

    @Test fun `casting releases the controller here, and carrying on here makes a new one`() = runTest(main.dispatcher) {
        val vm = model()
        val local = vm.controller()
        vm.start(local)
        val toTv = episodeOne.copy(startMs = 60_000)
        vm.castTo(toTv)
        assertTrue(made[0].released)
        assertTrue(vm.remote.value)
        val remote = TestController()
        vm.start(remote)
        vm.start(remote)
        assertEquals(listOf(60_000L), remote.starts.map { it.startMs })

        vm.continueHere(episodeOne.copy(startMs = 90_000))
        assertFalse(vm.remote.value)
        val here = vm.controller()
        assertNotSame(local, here)
        vm.start(here)
        assertEquals(listOf(90_000L), made[1].starts.map { it.startMs })
    }

    @Test fun `the entry going away releases the controller`() = runTest(main.dispatcher) {
        val owner = ViewModelStore()
        val vm = ViewModelProvider.create(owner, viewModelFactory { initializer { model() } })[PlayerViewModel::class]
        vm.controller()
        owner.clear()
        assertTrue(made[0].released)
    }

    private class TestController : PlaybackController by FakePlaybackController() {
        val starts = mutableListOf<PlayRequest>()
        var released = false
        override val state = MutableStateFlow(PlayerUiState())
        override val progress = MutableStateFlow(PlaybackProgress())
        override val events = MutableSharedFlow<PlayerEvent>(extraBufferCapacity = 4)

        override fun start(request: PlayRequest) {
            starts += request
        }

        override fun release() {
            released = true
        }
    }
}
