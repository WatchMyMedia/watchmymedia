package media.watchmy.client.presentation.player

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.PlaybackController
import media.watchmy.client.model.PlaybackControllerFactory
import media.watchmy.client.model.PlayerEvent
import media.watchmy.client.model.PlayerStatus
import media.watchmy.client.model.SubtitleRequestMode

/** An explicit play command; selecting the same request again is still a new command. */
data class PlayerSelection(val request: PlayRequest, val generation: Long = 0) {
    fun select(request: PlayRequest) = PlayerSelection(request, generation + 1)
}

/**
 * A player screen's play, held on the player's nav entry so it outlives the
 * composition: the local [controller], the
 * [selection] that started it, the file playing now ([fileId]: the
 * request's, or a later episode's once the play moved on by itself), and
 * whether the play is on a TV ([remote]; [onTv] when it opens while
 * casting).
 *
 * A recreated activity (a font size change in picture-in-picture) finds the
 * same controller still playing, and [start] doesn't start it again. After
 * process death the entry's [saved] state brings back the file, the
 * position, the tracks and the title, and the first [start] picks the play
 * up there rather than at the route's first file.
 */
class PlayerViewModel(
    private val playback: PlaybackControllerFactory,
    private val saved: SavedStateHandle,
    request: PlayRequest,
    onTv: Boolean = false,
) : ViewModel() {
    private val _selection = MutableStateFlow(PlayerSelection(resumed(request) ?: request))
    val selection: StateFlow<PlayerSelection> = _selection.asStateFlow()

    private val _fileId = MutableStateFlow(_selection.value.request.fileId)
    val fileId: StateFlow<Long> = _fileId.asStateFlow()

    private val _remote = MutableStateFlow(saved.get<Boolean>(REMOTE) ?: onTv)
    val remote: StateFlow<Boolean> = _remote.asStateFlow()

    private var local: PlaybackController? = null
    private var following: Job? = null

    init {
        // The file the entry plays, from its first start: what process death comes back to.
        if (!saved.contains(FILE)) saved[FILE] = _selection.value.request.fileId
    }

    /** The controller and the selection generation last started on it, so neither recomposition nor recreation starts it twice. */
    private var started: Pair<PlaybackController, Long>? = null

    /** The controller playing on this device, made at first use and kept until the play moves to a TV or the entry goes. */
    fun controller(): PlaybackController = local ?: playback.create().also {
        local = it
        follow(it)
    }

    /**
     * Starts [selection] on [controller] unless it already has it: this
     * device's own, a TV's remote while casting, or the player a cast to this
     * TV brought.
     */
    fun start(controller: PlaybackController) {
        val generation = _selection.value.generation
        val last = started
        if (last != null && last.first === controller && last.second == generation) return
        started = controller to generation
        controller.start(_selection.value.request)
    }

    /** A new play the viewer chose: an episode from the list, the same one again, or a play carried here from a TV. */
    fun select(request: PlayRequest) {
        _selection.value = _selection.value.select(request)
        _fileId.value = request.fileId
        remember(request.fileId, request.startMs ?: 0L)
    }

    /**
     * The play moves to a TV as [request] (from [handOver]): this device's
     * controller is released, which reports the play stopped here.
     */
    fun castTo(request: PlayRequest) {
        releaseLocal()
        _remote.value = true
        saved[REMOTE] = true
        select(request)
    }

    /** The cast ended and the viewer carries on here, as [request]. */
    fun continueHere(request: PlayRequest) {
        _remote.value = false
        saved[REMOTE] = false
        select(request)
    }

    private fun releaseLocal() {
        following?.cancel()
        following = null
        local?.let { if (started?.first === it) started = null }
        local?.release()
        local = null
    }

    /** Keeps the file, position, tracks and title in the entry's saved state as the local play goes on. */
    private fun follow(controller: PlaybackController) {
        following = viewModelScope.launch {
            launch {
                controller.events.collect { event ->
                    if (event is PlayerEvent.StartedNext) {
                        _fileId.value = event.fileId
                        remember(event.fileId, 0L)
                    }
                }
            }
            launch { controller.progress.collect { saved[POSITION] = it.positionMs } }
            launch {
                controller.state.collect { state ->
                    // The tracks once the file has loaded: before then none is chosen yet, which isn't "Off".
                    if (state.status == PlayerStatus.Loading) return@collect
                    saved[TITLE] = state.title.takeIf { it.isNotEmpty() }
                    saved[SUBTITLE] = state.subtitle
                    saved[AUDIO] = state.selectedAudio
                    saved[SUBTITLE_TRACK] = state.selectedSubtitle
                    saved[TRACKS_KNOWN] = true
                }
            }
        }
    }

    private fun remember(fileId: Long, positionMs: Long) {
        saved[FILE] = fileId
        saved[POSITION] = positionMs
    }

    /** The play as the entry's saved state left it, after process death; null on a first start. */
    private fun resumed(request: PlayRequest): PlayRequest? {
        val fileId = saved.get<Long>(FILE) ?: return null
        val same = fileId == request.fileId
        val subtitleId = saved.get<String>(SUBTITLE_TRACK)
        val tracksKnown = saved.get<Boolean>(TRACKS_KNOWN) == true
        return request.copy(
            fileId = fileId,
            startMs = saved.get<Long>(POSITION)?.takeIf { it > 0 } ?: request.startMs.takeIf { same },
            title = saved.get<String>(TITLE) ?: request.title,
            subtitle = saved.get<String>(SUBTITLE) ?: request.subtitle.takeIf { same },
            audioIndex = saved.get<Int>(AUDIO) ?: request.audioIndex,
            subtitleId = subtitleId ?: request.subtitleId.takeIf { same },
            subtitleMode = if (tracksKnown && subtitleId == null) SubtitleRequestMode.Off else request.subtitleMode,
            episodeContext = request.episodeContext.takeIf { same },
        )
    }

    override fun onCleared() {
        local?.release()
        local = null
    }

    private companion object {
        const val FILE = "player.fileId"
        const val POSITION = "player.positionMs"
        const val TITLE = "player.title"
        const val SUBTITLE = "player.subtitle"
        const val AUDIO = "player.audio"
        const val SUBTITLE_TRACK = "player.subtitleTrack"
        const val TRACKS_KNOWN = "player.tracksKnown"
        const val REMOTE = "player.remote"
    }
}
