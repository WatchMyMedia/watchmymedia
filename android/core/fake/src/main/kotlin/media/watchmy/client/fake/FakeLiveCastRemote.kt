package media.watchmy.client.fake

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import media.watchmy.client.model.LiveCastPhase
import media.watchmy.client.model.LiveCastRemote
import media.watchmy.client.model.LiveCastState
import media.watchmy.client.model.LiveChannel
import media.watchmy.client.model.StreamQuality
import media.watchmy.client.model.forLiveTV

/**
 * A TV that plays Live TV on the fakes (PLAN-LIVE-TV.md §4.1): a channel cast
 * to it tunes for [tuneMs], then plays; a pause gives the tuner back after
 * five minutes. [receiverLive] false is a TV whose app can't play Live TV,
 * and a channel in [busy] finds every tuner in use. [cast]s are recorded.
 */
class FakeLiveCastRemote(
    deviceName: String? = null,
    receiverLive: Boolean = true,
    private val tuneMs: Long = 1_500,
    var busy: Set<String> = emptySet(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : LiveCastRemote {
    private val _state = MutableStateFlow(LiveCastState(deviceName = deviceName, receiverLive = receiverLive.takeIf { deviceName != null }, automaticAvailable = true))
    override val state: StateFlow<LiveCastState> = _state.asStateFlow()
    private var receiverPlaysLive = receiverLive
    private var tuning: Job? = null

    /** Every channel cast, with its quality. */
    val casts = mutableListOf<Pair<String, StreamQuality>>()

    /** Commands the remote sent, for tests. */
    val commands = mutableListOf<String>()

    /** A TV turned up (true) or went (false). */
    fun connected(deviceName: String?) {
        tuning?.cancel()
        _state.value = LiveCastState(deviceName = deviceName, receiverLive = receiverPlaysLive.takeIf { deviceName != null }, automaticAvailable = true)
    }

    override fun cast(channel: LiveChannel, quality: StreamQuality) {
        if (_state.value.deviceName == null || !receiverPlaysLive) return
        casts += channel.id to quality.forLiveTV(true)
        tune(channel, quality.forLiveTV(true))
    }

    override fun channel(channel: LiveChannel) {
        commands += "channel ${channel.id}"
        if (_state.value.deviceName == null) return
        tune(channel, _state.value.quality)
    }

    private fun tune(channel: LiveChannel, quality: StreamQuality) {
        tuning?.cancel()
        _state.update { it.copy(phase = LiveCastPhase.Tuning, channelId = channel.id, number = channel.number, name = channel.name, now = channel.now, next = channel.next, quality = quality,
            actualQuality = StreamQuality.FullHd.takeIf { quality == StreamQuality.Auto }, reason = null, error = null, pauseLeftSeconds = null, atLiveEdge = true) }
        tuning = scope.launch {
            delay(tuneMs)
            _state.update {
                if (channel.id in busy) it.copy(phase = LiveCastPhase.Failed, reason = "tuners_busy")
                else it.copy(phase = LiveCastPhase.Playing)
            }
        }
    }

    override fun play() {
        commands += "play"
        _state.update { if (it.phase == LiveCastPhase.Paused) it.copy(phase = LiveCastPhase.Playing, pauseLeftSeconds = null, atLiveEdge = false) else it }
    }

    override fun pause() {
        commands += "pause"
        _state.update { if (it.phase == LiveCastPhase.Playing) it.copy(phase = LiveCastPhase.Paused, pauseLeftSeconds = 240) else it }
    }

    override fun togglePlayPause() = if (_state.value.phase == LiveCastPhase.Paused) play() else pause()

    override fun goLive() {
        commands += "goLive"
        _state.update { if (it.onTv) it.copy(phase = LiveCastPhase.Playing, pauseLeftSeconds = null, atLiveEdge = true) else it }
    }

    override fun selectQuality(quality: StreamQuality) {
        commands += "quality ${quality.id}"
        val s = _state.value
        val id = s.channelId ?: return
        tune(LiveChannel(id, "", s.number, s.name, now = s.now, next = s.next), quality.forLiveTV(true))
    }

    override fun noteUserInput() {
        commands += "input"
        _state.update { it.copy(askStillWatching = false) }
    }

    override fun answerStillWatching(keepWatching: Boolean) {
        commands += "stillWatching $keepWatching"
        if (keepWatching) _state.update { it.copy(askStillWatching = false) } else stop()
    }

    override fun stop() {
        commands += "stop"
        tuning?.cancel()
        _state.update { LiveCastState(deviceName = it.deviceName, receiverLive = it.receiverLive, automaticAvailable = it.automaticAvailable) }
    }

    /** The scripted states the boards draw: an admin's stop, and Still watching. */
    fun adminStop() = _state.update { it.copy(phase = LiveCastPhase.Stopped, reason = "stopped_by_admin") }

    fun askStillWatching() = _state.update { it.copy(askStillWatching = true) }

    /** Script a fictional link improvement or slowdown on the current channel. */
    fun automaticStep(actual: StreamQuality) = _state.update {
        if (it.quality == StreamQuality.Auto && it.phase == LiveCastPhase.Playing) it.copy(actualQuality = actual) else it
    }
}
