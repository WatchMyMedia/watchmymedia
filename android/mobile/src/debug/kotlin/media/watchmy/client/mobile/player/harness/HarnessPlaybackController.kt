package media.watchmy.client.mobile.player.harness

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

/** What the harness can show: each starts the fake episode somewhere and scripts what the fake does not do by itself. */
enum class Scenario(val title: String, val startMs: Long) {
    Controls("Controls with Skip Intro", 62_000),
    AutoSkip("Skip Intro, auto after 3 s", 62_000),
    Next("Next Episode card", 49 * 60_000L + 26_000),
    StillWatching("Are you still watching?", 20 * 60_000L),
    Stopped("Stopped by an admin", 20 * 60_000L),
    Failed("Playback error", 20 * 60_000L),
    Casting("Casting: the TV's remote", 62_000),
    Handover("Casting starts while playing", 62_000),
    Auto("Automatic lowers the quality", 12 * 60_000L + 30_000),
    AutoRecovery("Automatic lowers then recovers", 12 * 60_000L + 30_000),

    // Live TV cast to a TV (PLAN-LIVE-TV.md §4.1): the live player as the TV's remote.
    LiveCasting("Live TV: the TV's remote", 0),
    LiveHandover("Live TV: a TV turns up while playing", 0),
    LiveNotLive("Live TV: a TV that can't play it", 0),
    LiveBusy("Live TV: all tuners in use", 0),
    LiveStopped("Live TV: stopped from Activity", 0),
    ;

    /** A Live TV scenario: the live player, not the file player. */
    val live: Boolean get() = name.startsWith("Live")

    companion object {
        fun from(name: String?): Scenario? = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}

/**
 * [FakePlaybackController] with the [scenario]'s script on top: the Skip
 * button's auto-delay fill, the Still watching question, an admin's stop
 * and an error, which the plain fake never produces.
 */
class HarnessPlaybackController(
    private val scenario: Scenario,
    private val fake: FakePlaybackController = FakePlaybackController(),
) : PlaybackController by fake {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val overlay = MutableStateFlow(Overlay())
    private val extraEvents = MutableSharedFlow<PlayerEvent>(extraBufferCapacity = 4)
    private var scripted = false

    override val state: StateFlow<PlayerUiState> =
        combine(fake.state, overlay) { s, o -> o.applyTo(s) }.stateIn(scope, SharingStarted.Eagerly, PlayerUiState())

    override val events: Flow<PlayerEvent> = merge(fake.events, extraEvents)

    override fun start(request: PlayRequest) {
        overlay.value = Overlay()
        fake.start(request)
        if (scripted) return
        scripted = true
        scope.launch { script() }
    }

    private suspend fun script() {
        when (scenario) {
            Scenario.Controls, Scenario.Next, Scenario.Casting, Scenario.Handover -> Unit
            // The Live TV scenarios run the live player, not this one.
            Scenario.LiveCasting, Scenario.LiveHandover, Scenario.LiveNotLive, Scenario.LiveBusy, Scenario.LiveStopped -> Unit
            Scenario.AutoSkip -> {
                var run = 0L
                while (scope.isActive) {
                    delay(100)
                    val s = fake.state.value
                    run = if (s.skip == null) 0 else if (s.status == PlayerStatus.Playing) run + 100 else run
                    overlay.update { it.copy(autoSkip = if (s.skip == null) null else (run / 3_000f).coerceAtMost(1f)) }
                    if (run >= 3_000) {
                        fake.skip()
                        run = 0
                    }
                }
            }
            Scenario.StillWatching -> {
                delay(2_000)
                overlay.update { it.copy(askStillWatching = true) }
            }
            Scenario.Stopped -> {
                delay(3_000)
                fake.pause()
                overlay.update { it.copy(stopMessage = "Sam stopped this stream from the server's Activity page.") }
                extraEvents.emit(PlayerEvent.Exit)
            }
            Scenario.Failed -> {
                delay(2_000)
                fake.pause()
                overlay.update { it.copy(error = "The server could not start the stream (ffmpeg exited with code 1).") }
            }
            Scenario.Auto -> {
                delay(3_000)
                fake.lowerTo(StreamQuality.Hd)
                delay(5_000)
                fake.clearNote()
            }
            Scenario.AutoRecovery -> {
                delay(3_000)
                fake.lowerTo(StreamQuality.Hd)
                delay(5_000)
                fake.raiseTo(StreamQuality.Original)
                delay(5_000)
                fake.clearNote()
            }
        }
    }

    override fun answerStillWatching(keepWatching: Boolean) {
        overlay.update { it.copy(askStillWatching = false) }
        fake.answerStillWatching(keepWatching)
    }

    override fun release() {
        scope.cancel()
        fake.release()
    }

    private data class Overlay(
        val autoSkip: Float? = null,
        val askStillWatching: Boolean = false,
        val stopMessage: String? = null,
        val error: String? = null,
    ) {
        fun applyTo(s: PlayerUiState) = s.copy(
            skip = s.skip?.let { if (autoSkip != null) it.copy(autoProgress = autoSkip) else it },
            askStillWatching = s.askStillWatching || askStillWatching,
            stopMessage = stopMessage ?: s.stopMessage,
            error = error ?: s.error,
        )
    }
}
