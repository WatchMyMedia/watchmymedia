package media.watchmy.client.fake

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import media.watchmy.client.model.CastService
import media.watchmy.client.model.CastState
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.PlaybackController

/**
 * Casting on the fakes: a device called [deviceName] is around, connected
 * from the start when [connected]. The TV's play is [FakePlaybackController]
 * (or [player]), which the remote screens render as they would the real one;
 * a channel cast to it is [live]'s.
 */
class FakeCastService(
    private val deviceName: String = "Living room TV",
    connected: Boolean = false,
    player: PlaybackController = FakePlaybackController(),
    override val live: FakeLiveCastRemote = FakeLiveCastRemote(),
) : CastService {
    private val _state = MutableStateFlow<CastState>(if (connected) CastState.Connected(deviceName) else CastState.Available)
    override val state: StateFlow<CastState> = _state.asStateFlow()

    private val _nowPlaying = MutableStateFlow<PlayRequest?>(null)
    override val nowPlaying: StateFlow<PlayRequest?> = _nowPlaying.asStateFlow()

    /** Every request the remote was asked to play. */
    val started = mutableListOf<PlayRequest>()

    override val remote: PlaybackController = object : PlaybackController by player {
        override fun start(request: PlayRequest) {
            if (request.startMs == null && _nowPlaying.value?.fileId == request.fileId) return
            started += request
            _nowPlaying.value = request
            player.start(request)
        }

        override fun stop() {
            player.stop()
            _nowPlaying.value = null
        }

        // The cast service owns the remote; a screen's release must not end it.
        override fun release() {}
    }

    init {
        if (connected) live.connected(deviceName)
    }

    fun connect() {
        _state.value = CastState.Connected(deviceName)
        live.connected(deviceName)
    }

    override fun disconnect() {
        _state.value = CastState.Available
        _nowPlaying.value = null
        live.connected(null)
    }
}
