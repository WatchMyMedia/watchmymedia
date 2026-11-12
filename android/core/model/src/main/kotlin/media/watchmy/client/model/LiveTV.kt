package media.watchmy.client.model

import androidx.media3.common.Player
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf

/** Channel browsing and live sessions were added at this level; the app minimum stays at 1. */
const val LIVE_TV_API_LEVEL = 11

/** A cast token may play Live TV from this level (PLAN-LIVE-TV.md §4.1, L10a). */
const val LIVE_CAST_API_LEVEL = 14

/** Favourite channels, search across the listings and reminders (PLAN-LIVE-TV-GUIDE.md §5.7, G6). */
const val LIVE_PERSONAL_API_LEVEL = 15
const val LIVE_PROFILE_API_LEVEL = 25
const val LIVE_AUTOMATIC_API_LEVEL = 26

private val NoReminders: StateFlow<LiveReminders?> = MutableStateFlow<LiveReminders?>(null).asStateFlow()

/** Automatic is offered only when the bound server advertises it. */
val LIVE_QUALITIES = listOf(StreamQuality.Auto, StreamQuality.FullHd, StreamQuality.Hd, StreamQuality.Sd, StreamQuality.Low)
fun StreamQuality.forLiveTV(automatic: Boolean = false): StreamQuality =
    takeIf { it in LIVE_QUALITIES && (it != StreamQuality.Auto || automatic) } ?: StreamQuality.FullHd

data class LiveCapabilities(
    val enabled: Boolean = false,
    val allowed: Boolean = false,
    val playback: Boolean = false,
    val bufferSeconds: Int = 60,
    val pauseSeconds: Int = 300,
    /** Listings are on and some channel has them; the app shows them only at [LIVE_GUIDE_API_LEVEL] and later. */
    val guide: Boolean = false,
    /** This account may cast a channel; only from [LIVE_CAST_API_LEVEL]. */
    val cast: Boolean = false,
    val recording: Boolean = false,
    val canRecord: Boolean = false,
    val automatic: Boolean = false,
) {
    val available: Boolean get() = enabled && allowed && playback
}

/**
 * A channel has its own identity, never a media-file ID or fabricated programme.
 * With listings ([LIVE_GUIDE_API_LEVEL]) it may carry the provider's [logo] (a
 * path for [LiveRepository.artUrl]) and what is on [now] and [next]; from
 * [LIVE_PERSONAL_API_LEVEL], whether the person signed in starred it ([favourite]).
 */
data class LiveChannel(
    val id: String,
    val sourceId: String,
    val number: String,
    val name: String,
    val available: Boolean = true,
    val reason: String? = null,
    val logo: String? = null,
    val now: LiveProgramme? = null,
    val next: LiveProgramme? = null,
    val favourite: Boolean = false,
)

data class LiveTarget(val channel: LiveChannel)

enum class LiveSessionState {
    Starting, Playing, Stopping, Stopped, Failed, Unknown;

    /** Unknown server states must not continue playing. */
    val terminal: Boolean get() = this in listOf(Stopping, Stopped, Failed, Unknown)
}

data class LiveSession(
    val id: String,
    val channel: LiveChannel,
    val state: LiveSessionState,
    val quality: StreamQuality,
    /** Same-server HLS URL without a token; null until the session can play. */
    val url: String?,
    val reason: String? = null,
    val paused: Boolean = false,
    val pauseRemainingSeconds: Int = 300,
    val bufferSeconds: Int = 60,
    val takeoverAtMs: Long? = null,
    val takeoverTitle: String? = null,
    val takeoverRecordingId: String? = null,
    val takeoverCanCancel: Boolean = false,
    val automatic: Boolean = false,
    val actualQuality: StreamQuality? = null,
    val outputGeneration: Long = 0,
    val directive: LiveOutputDirective? = null,
    val mode: String? = null,
    val modeReason: String? = null,
    val switchStatus: String? = null,
)

/** A prepared output of the same logical viewer and tuner. */
data class LiveOutputDirective(val generation: Long, val url: String, val from: String, val to: String, val reason: String)

enum class LivePlaybackState { Playing, Paused, Buffering }

data class LiveMeasurement(
    val state: LivePlaybackState,
    val bufferedMs: Long? = null,
    val throughputBps: Long? = null,
    val throughputAgeMs: Long? = null,
    val stalls: Long? = null,
    val switchGeneration: Long? = null,
    val switchFailedGeneration: Long? = null,
)

interface LiveRepository {
    /** Unavailable without requesting any Live TV route on servers below [LIVE_TV_API_LEVEL]. */
    suspend fun capabilities(): LiveCapabilities
    suspend fun channels(): List<LiveChannel>

    /**
     * Listings overlapping [startMs] to [endMs], from the server's windows of
     * [LIVE_GUIDE_WINDOW_HOURS] hours, the next of which is fetched ahead.
     * Null below [LIVE_GUIDE_API_LEVEL], without asking the server.
     */
    suspend fun guide(startMs: Long, endMs: Long): LiveGuide? = null

    /** A logo or programme picture's path as a URL the image loader signs in to, or null. */
    fun artUrl(path: String?): String? = null

    /**
     * Stars or unstars [channel] for the person signed in (PLAN-LIVE-TV-GUIDE.md
     * §5.7). Throws [ApiException]; does nothing below [LIVE_PERSONAL_API_LEVEL].
     */
    suspend fun setFavourite(channel: LiveChannel, favourite: Boolean) {}

    /**
     * Programmes on now and later whose title or episode title holds every
     * word of [query], soonest first, each with its channel. Empty below
     * [LIVE_PERSONAL_API_LEVEL] or under [LIVE_SEARCH_MIN_CHARS] characters,
     * without asking the server.
     */
    suspend fun search(query: String): List<LiveSearchHit> = emptyList()

    /**
     * The person's upcoming reminders and how long before each they come, as
     * last read ([refreshReminders]) and changed here; null until read, and
     * always below [LIVE_PERSONAL_API_LEVEL]. Cleared when the account changes.
     */
    val reminders: StateFlow<LiveReminders?> get() = NoReminders

    /** Reads the reminders again. Throws [ApiException]; null below [LIVE_PERSONAL_API_LEVEL]. */
    suspend fun refreshReminders(): LiveReminders? = null

    /** A reminder for [programme], which hasn't started. Throws [ApiException]. */
    suspend fun remind(programme: LiveProgramme): LiveReminder? = null

    /** Removes the reminder [reminderId]. Throws [ApiException]. */
    suspend fun forget(reminderId: String) {}

    /** How long before a programme its reminder comes: one of [LIVE_REMINDER_LEADS]. Throws [ApiException]. */
    suspend fun setReminderMinutes(minutes: Int) {}

    /**
     * The person's favourites or reminders changed, on this device or another
     * (the server's private events), or the event stream reconnected. Connected
     * only while collected.
     */
    val personalChanges: Flow<LivePersonalChange> get() = emptyFlow()

    suspend fun recordings(section: RecordingSection, cursor: String? = null): RecordingPage = throw ApiException(404, "Recordings need a newer server.")
    suspend fun record(programme: LiveProgramme, options: RecordingOptions = RecordingOptions()): LiveRecording? = throw ApiException(404, "Recordings need a newer server.")
    suspend fun recordManual(request: ManualRecording): LiveRecording? = throw ApiException(404, "Recordings need a newer server.")
    /** Padding, or [prefer] for Record this instead: with [over], that clashing recording gives way; without, every one not yet recording does. */
    suspend fun updateRecording(id: String, options: RecordingOptions? = null, prefer: Boolean = false, over: String? = null) {}
    suspend fun cancelRecording(id: String) {}
    suspend fun deleteRecordingFile(id: String, recordAgain: Boolean = false) {}
    suspend fun updateRecordingRule(id: String, options: RecordingOptions) {}
    suspend fun deleteRecordingRule(id: String) {}

    data object Unavailable : LiveRepository {
        override suspend fun capabilities() = LiveCapabilities()
        override suspend fun channels() = emptyList<LiveChannel>()
    }
}

/**
 * Live control is separate from file progress, history, downloads and Cast.
 * Bind once before starting. Cleanup always uses the original server/sign-in,
 * even after [active] becomes false; no new start may use an inactive binding.
 */
interface LivePlaybackGateway {
    fun bind(): LivePlaybackGateway = this
    val isCurrent: Boolean get() = true
    val active: Flow<Boolean> get() = flowOf(true)
    suspend fun liveCapabilities(): LiveCapabilities = LiveCapabilities()

    /** A deliberate retune uses a new request ID; a transport retry reuses its ID. */
    suspend fun start(target: LiveTarget, quality: StreamQuality, requestId: String, client: ClientInfo): LiveSession
    suspend fun startNegotiated(target: LiveTarget, quality: StreamQuality, requestId: String, client: ClientInfo, profile: PlaybackProfile): LiveSession =
        start(target, quality, requestId, client)
    suspend fun status(sessionId: String): LiveSession
    suspend fun heartbeat(sessionId: String, paused: Boolean, interacted: Boolean): LiveSession
    suspend fun heartbeatMeasured(sessionId: String, paused: Boolean, interacted: Boolean, measurement: LiveMeasurement): LiveSession =
        heartbeat(sessionId, paused, interacted)
    suspend fun stop(sessionId: String)
    suspend fun mediaToken(): MediaToken
}

data class LivePlayerState(
    val target: LiveTarget? = null,
    val status: PlayerStatus = PlayerStatus.Loading,
    val session: LiveSession? = null,
    val quality: StreamQuality = StreamQuality.FullHd,
    val atLiveEdge: Boolean = true,
    val askStillWatching: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    /** The server's reason for [error] (tuners_busy, stopped_by_admin…), for a cast's state; null when it has none. */
    val reason: String? = null,
    /** A tuner takeover offers playback even to someone who cannot manage recordings. */
    val takeoverRecording: PlayRequest? = null,
    val automaticAvailable: Boolean = false,
)

fun LivePlayerState.liveQualityLabel(): String =
    if (quality == StreamQuality.Auto) "Auto · ${session?.actualQuality?.shortLabel ?: "Starting"}" else quality.shortLabel

fun LivePlayerState.liveSwitchMessage(): String? = when (session?.switchStatus) {
    "preparing" -> "Preparing another quality while this channel keeps playing."
    "waiting_capacity" -> "Keeping this quality until the server has room to improve it."
    "source_wait" -> "Waiting for new broadcast segments."
    "failed" -> "Keeping the current quality after a switch could not finish."
    else -> null
}

/** No finite duration, seeking, watched progress, next episode or casting methods. */
interface LivePlaybackController {
    val state: StateFlow<LivePlayerState>
    val player: StateFlow<Player?>
    fun start(target: LiveTarget)
    fun play()
    fun pause()
    fun togglePlayPause()
    fun goLive()
    fun selectQuality(quality: StreamQuality)
    fun noteUserInput()
    fun answerStillWatching(keepWatching: Boolean)
    fun retry()
    fun stop()

    /**
     * Stops, then waits until the server has the tuner back (or could not be
     * told), so another device can tune it: casting stops this phone first.
     */
    suspend fun stopAndRelease() = stop()
    fun release()
}

fun interface LivePlaybackControllerFactory {
    fun create(): LivePlaybackController

    data object Unavailable : LivePlaybackControllerFactory {
        override fun create(): LivePlaybackController = UnavailableLivePlaybackController
    }
}

private object UnavailableLivePlaybackController : LivePlaybackController {
    override val state = MutableStateFlow(LivePlayerState(status = PlayerStatus.Error, error = "Live TV is not available on this server."))
    override val player = MutableStateFlow<Player?>(null)
    override fun start(target: LiveTarget) = Unit
    override fun play() = Unit
    override fun pause() = Unit
    override fun togglePlayPause() = Unit
    override fun goLive() = Unit
    override fun selectQuality(quality: StreamQuality) = Unit
    override fun noteUserInput() = Unit
    override fun answerStillWatching(keepWatching: Boolean) = Unit
    override fun retry() = Unit
    override fun stop() = Unit
    override fun release() = Unit
}

/**
 * The channel [direction] steps from [currentId] in the server's lineup
 * order, skipping unavailable ones and wrapping round; null when none can be
 * watched. Remotes, players and Cast Connect step alike.
 */
fun liveChannelAfter(channels: List<LiveChannel>, currentId: String, direction: Int): LiveChannel? {
    if (channels.isEmpty() || direction == 0) return null
    val step = if (direction > 0) 1 else -1
    val index = channels.indexOfFirst { it.id == currentId }.let { if (it < 0) (if (step > 0) -1 else 0) else it }
    for (offset in 1..channels.size) {
        val channel = channels[Math.floorMod(index + step * offset, channels.size)]
        if (channel.available) return channel
    }
    return null
}

/** Shared phone/TV wording for terminal session reasons and start failures. */
fun liveTVError(reason: String?): String = when (reason) {
    "tuners_busy" -> "All tuners are in use. Someone else is watching, here or in another app. Try again after a tuner becomes free."
    "server_busy" -> "The server is busy. All transcoding slots are in use. Try again in a moment."
    "no_signal" -> "No signal on this channel. Try another channel, or check the antenna connection and reception."
    "signal_lost", "stream_stalled" -> "The broadcast stopped. Try again, or choose a lower quality."
    "protected_channel" -> "This channel is protected. WatchMyMedia can play clear antenna channels only."
    "tuner_unavailable" -> "The tuner is not answering. Check its power and network connection, then try again."
    "device_changed" -> "A different tuner is at this address. An admin needs to check the tuner in the web app’s Live TV settings."
    "pause_expired" -> "Your tuner was released after a long pause. Resume starts at live and needs a free tuner."
    "stopped" -> "Live TV stopped and the tuner was released. Choose a channel to watch again."
    "server_stopped" -> "The server stopped. Reconnect, then start again at live."
    "unavailable", "channel_unavailable" -> "This channel is unavailable. Choose another channel or ask an admin to refresh the lineup."
    "lease_expired" -> "Your session ended because the server lost contact with this player. Start again at live."
    "inactive" -> "Your tuner was released after four hours without input. Resume to keep watching."
    "recording" -> "Stopped for a recording. You can watch it from the start in Live TV › Recordings."
    "stopped_by_admin" -> "Stopped by an admin. An admin ended this play and released its tuner."
    "access_changed", "access_revoked", "source_disabled", "source_changed", "source_removed", "channel_disabled" ->
        "Live TV access changed. Return to the channels to see what is available."
    "storage_limit", "storage_error" -> "The live buffer could not be kept. An admin needs to check the server’s transcoding disk space."
    "encoder_failed" -> "The broadcast could not be converted. Try again at a lower quality."
    else -> reason?.takeIf { it.isNotBlank() } ?: "Live TV could not start. Try again, or choose another channel."
}
