package media.watchmy.client.tv.player

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import media.watchmy.client.fake.FakePlaybackController
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.PlaybackController
import media.watchmy.client.model.PlayerEvent
import media.watchmy.client.model.PlayerStatus
import media.watchmy.client.model.PlayerUiState
import media.watchmy.client.model.StreamQuality

/** What the harness plays on top of the fake's episode. */
internal enum class HarnessScene {
    /** The fake as it is: Skip Intro, then the Next Episode card. */
    Plain,

    /** Skip Intro in auto-delay mode: the 3 s fill runs, then it skips by itself. */
    AutoSkip,

    /** "Are you still watching?" four seconds in. */
    Still,

    /** An admin stops the play five seconds in, with a message. */
    Stopped,

    /** The play fails three seconds in. */
    Error,

    /** A non-fatal error three seconds in (a track change that failed). */
    Banner,

    /** Automatic lowers the quality to 720p three seconds in, with its note. */
    Auto,

    /** Automatic lowers then returns to Original and Direct play. */
    AutoRecovery,
    ;

    companion object {
        fun from(name: String?): HarnessScene = entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: Plain
    }
}

/**
 * The fake controller plus the [HarnessScene]'s extra: an auto-delay skip,
 * Still watching, an admin's stop or an error, none of which the fake plays
 * by itself.
 */
internal class HarnessPlaybackController(
    private val inner: FakePlaybackController,
    private val scene: HarnessScene,
    /** How long the auto-delay fill runs; the real controller's is 3 s. */
    private val autoDelayMs: Long = 3_000,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : PlaybackController by inner {
    private data class Extra(
        val autoProgress: Float? = null,
        val askStillWatching: Boolean = false,
        val stopMessage: String? = null,
        val error: String? = null,
        val failed: Boolean = false,
    )

    private val extra = MutableStateFlow(Extra())
    private val ownEvents = MutableSharedFlow<PlayerEvent>(extraBufferCapacity = 4)
    private var sceneJob: Job? = null

    override val state: StateFlow<PlayerUiState> = combine(inner.state, extra) { s, e ->
        s.copy(
            skip = s.skip?.let { if (scene == HarnessScene.AutoSkip) it.copy(autoProgress = e.autoProgress ?: 0f) else it },
            askStillWatching = e.askStillWatching,
            stopMessage = e.stopMessage,
            error = e.error,
            status = if (e.failed) PlayerStatus.Error else s.status,
        )
    }.stateIn(scope, SharingStarted.Eagerly, inner.state.value)

    override val events: Flow<PlayerEvent> = merge(inner.events, ownEvents)

    override fun start(request: PlayRequest) {
        inner.start(request)
        extra.value = Extra()
        sceneJob?.cancel()
        sceneJob = scope.launch { runScene() }
    }

    private suspend fun runScene() {
        when (scene) {
            HarnessScene.Plain -> Unit
            HarnessScene.AutoSkip -> {
                var since = -1L
                while (scope.isActive) {
                    delay(100)
                    if (inner.state.value.skip == null) {
                        since = -1L
                        extra.update { it.copy(autoProgress = null) }
                        continue
                    }
                    if (inner.state.value.status != PlayerStatus.Playing) continue
                    val now = SystemClock.uptimeMillis()
                    if (since < 0) since = now
                    val progress = ((now - since) / autoDelayMs.toFloat()).coerceAtMost(1f)
                    extra.update { it.copy(autoProgress = progress) }
                    if (progress >= 1f) inner.skip()
                }
            }
            HarnessScene.Still -> {
                delay(4_000)
                inner.pause()
                extra.update { it.copy(askStillWatching = true) }
            }
            HarnessScene.Stopped -> {
                delay(5_000)
                inner.pause()
                extra.update { it.copy(stopMessage = "Your admin stopped this stream: the server restarts for an update at 22:00.") }
                ownEvents.emit(PlayerEvent.Exit)
            }
            HarnessScene.Error -> {
                delay(3_000)
                inner.pause()
                extra.update { it.copy(failed = true, error = "The server could not start a stream for this file (ffmpeg exited with status 1).") }
            }
            HarnessScene.Banner -> {
                delay(3_000)
                extra.update { it.copy(error = "Couldn't switch the audio track; still playing the previous one.") }
                delay(6_000)
                extra.update { it.copy(error = null) }
            }
            HarnessScene.Auto -> {
                delay(3_000)
                inner.lowerTo(StreamQuality.Hd)
                delay(5_000)
                inner.clearNote()
            }
            HarnessScene.AutoRecovery -> {
                delay(3_000)
                inner.lowerTo(StreamQuality.Hd)
                delay(5_000)
                inner.raiseTo(StreamQuality.Original)
                delay(5_000)
                inner.clearNote()
            }
        }
    }

    override fun answerStillWatching(keepWatching: Boolean) {
        extra.update { it.copy(askStillWatching = false) }
        if (keepWatching) inner.play() else ownEvents.tryEmit(PlayerEvent.Exit)
    }

    override fun release() {
        scope.cancel()
        inner.release()
    }
}
