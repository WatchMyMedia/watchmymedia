package media.watchmy.client.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Casting from the phone to a Google Cast device (PLAN-CAST.md §5.2). The
 * TV runs the play itself; the phone is a remote.
 */
sealed interface CastState {
    /** No casting here: no receiver App ID in this build, no Google Play services, a TV, or a server that cannot issue cast tokens. Show no Cast UI. */
    data object Unavailable : CastState

    /**
     * Casting works, but no Cast device has been found yet: the Cast SDK only
     * looks now and then, and on Android 17 it finds none without local
     * network access. The Cast button still shows; tapping it looks again.
     */
    data object NoDevices : CastState

    /** Cast devices are around and none is connected. */
    data object Available : CastState

    data class Connecting(val deviceName: String?) : CastState

    data class Connected(val deviceName: String) : CastState
}

/**
 * Whether the Cast button shows: whenever this app can cast, devices found or
 * not. Google's Cast checklist asks Android senders to keep it up, so someone
 * whose phone has not found their TV (or may not look on the local network)
 * still has the button that looks for it.
 */
val CastState.showsButton: Boolean get() = this != CastState.Unavailable

/** The device being cast to, connected or connecting. */
val CastState.deviceName: String? get() = when (this) {
    is CastState.Connected -> deviceName
    is CastState.Connecting -> deviceName
    else -> null
}

interface CastService {
    val state: StateFlow<CastState>

    /**
     * The TV's play as a remote: the same commands and state as the phone's
     * own player, so the player screen renders it unchanged. It lives as
     * long as the app (never release it); [PlaybackController.player] is
     * always null, and commands do nothing while no device is connected.
     * [PlaybackController.start] with the file the TV is already playing and
     * no start position only attaches to it.
     */
    val remote: PlaybackController

    /** What the TV is playing, for the mini controller; null when nothing is. */
    val nowPlaying: StateFlow<PlayRequest?>

    /** Ends the cast session, which stops the TV. */
    fun disconnect()

    /** A Live TV channel on the TV as a remote (PLAN-LIVE-TV.md §4.1); [LiveCastRemote.None] where there is none. */
    val live: LiveCastRemote get() = LiveCastRemote.None

    companion object {
        /** No casting (fakes, TVs, builds without a receiver App ID). */
        val None: CastService = object : CastService {
            override val state: StateFlow<CastState> = MutableStateFlow(CastState.Unavailable).asStateFlow()
            override val remote: PlaybackController get() = error("casting is unavailable")
            override val nowPlaying: StateFlow<PlayRequest?> = MutableStateFlow<PlayRequest?>(null).asStateFlow()
            override fun disconnect() {}
        }
    }
}

/** Where a channel cast to the TV is, from the receiver's `state` (PLAN-LIVE-TV.md §4.1). */
enum class LiveCastPhase {
    /** No channel on the TV. */
    Idle,

    /** The TV is tuning [LiveCastState.channelId]. */
    Tuning,
    Playing,

    /** Paused on the TV, which keeps the tuner for [LiveCastState.pauseLeftSeconds]. */
    Paused,

    /** The channel couldn't start or stopped: [LiveCastState.reason] and [LiveCastState.error] say why. */
    Failed,

    /** An admin stopped it from Activity. */
    Stopped,
}

/**
 * A channel on the TV as the phone's remote shows it. The TV reports the
 * channel by its ID, number and name; its logo, pictures and listings come
 * from the phone's own channel list, which its sign-in can fetch.
 */
data class LiveCastState(
    /** The connected TV; null while none is. */
    val deviceName: String? = null,
    /** Whether the connected TV's receiver plays Live TV (its state lists `live`); null until it has said. */
    val receiverLive: Boolean? = null,
    val phase: LiveCastPhase = LiveCastPhase.Idle,
    val channelId: String? = null,
    val number: String = "",
    val name: String = "",
    /** What the TV says is on, by title and times, for a channel the phone's list doesn't have. */
    val now: LiveProgramme? = null,
    val next: LiveProgramme? = null,
    val quality: StreamQuality = StreamQuality.FullHd,
    val actualQuality: StreamQuality? = null,
    val automaticAvailable: Boolean = false,
    /** While paused: seconds until the tuner goes back. */
    val pauseLeftSeconds: Int? = null,
    val atLiveEdge: Boolean = true,
    val askStillWatching: Boolean = false,
    /** The server's reason the channel stopped or couldn't start (tuners_busy…). */
    val reason: String? = null,
    /** The TV's own words for a failure, when it gives no [reason]. */
    val error: String? = null,
) {
    /** A channel is on the TV, or starting there. */
    val onTv: Boolean get() = phase == LiveCastPhase.Tuning || phase == LiveCastPhase.Playing || phase == LiveCastPhase.Paused

    /** A TV is connected whose receiver can't play Live TV. */
    val notLive: Boolean get() = deviceName != null && receiverLive == false
}

fun LiveCastState.liveQualityLabel(): String =
    if (quality == StreamQuality.Auto) "Auto · ${actualQuality?.shortLabel ?: "adapting"}" else quality.shortLabel

/**
 * The TV's channel as a remote: the receiver runs the live session (its
 * heartbeats, pause allowance and Still watching), and this sends it the
 * viewer's choices. Commands do nothing while no TV is connected.
 */
interface LiveCastRemote {
    val state: StateFlow<LiveCastState>

    /** Plays [channel] on the TV at [quality] (a LOAD), replacing what it plays. Stop this phone's own play first. */
    fun cast(channel: LiveChannel, quality: StreamQuality)

    /** Changes the TV to [channel]. */
    fun channel(channel: LiveChannel)
    fun play()
    fun pause()
    fun togglePlayPause()

    /** Back to live after a pause. */
    fun goLive()

    /** Tunes the channel again on the TV at [quality], at live. */
    fun selectQuality(quality: StreamQuality)

    /** Someone touched the remote: counts as input for Still watching. */
    fun noteUserInput()
    fun answerStillWatching(keepWatching: Boolean)

    /** Stops the channel on the TV, which gives its tuner back; the Cast session stays. */
    fun stop()

    /** [stop], then waits (a few seconds at most) until the TV says it has stopped, so this phone can tune. */
    suspend fun stopAndWait() = stop()

    companion object {
        val None: LiveCastRemote = object : LiveCastRemote {
            override val state: StateFlow<LiveCastState> = MutableStateFlow(LiveCastState()).asStateFlow()
            override fun cast(channel: LiveChannel, quality: StreamQuality) {}
            override fun channel(channel: LiveChannel) {}
            override fun play() {}
            override fun pause() {}
            override fun togglePlayPause() {}
            override fun goLive() {}
            override fun selectQuality(quality: StreamQuality) {}
            override fun noteUserInput() {}
            override fun answerStillWatching(keepWatching: Boolean) {}
            override fun stop() {}
        }
    }
}

/** The server in use as casting sees it: its ID and address, and whether it issues cast tokens. */
data class CastServer(val id: String, val baseUrl: String, val casting: Boolean)

/** A token the TV plays with (POST /api/auth/cast-token), and where it may reach the server, best first. */
data class CastToken(val token: String, val expiresAtMs: Long, val urls: List<String>)

/** The server calls a sender makes. Methods throw [ApiException]. */
interface CastGateway {
    /** GET /api/server for the server in use; null when none is. Casting needs API level 2 and the server's `casting`. */
    suspend fun castServer(): CastServer?
    suspend fun liveAutomaticAvailable(): Boolean = false

    /** POST /api/auth/cast-token with this device's sign-in. */
    suspend fun castToken(): CastToken
}

/**
 * A play cast to this TV app (Cast Connect, PLAN-CAST.md §6): [controller]
 * plays as the person who cast, with their cast token, whatever this TV is
 * signed in as. The player screen takes it over: it starts [request] and
 * stops and releases the controller when it leaves.
 */
class CastedPlay(val id: Long, val request: PlayRequest, val controller: PlaybackController)

/** Who cast a channel to this TV, and what they did there, for the TV's words ("Paused on Pixel 9"). */
data class LiveCastSender(
    val name: String,
    /** The last pause came from the phone, not this TV's remote. */
    val pausedThere: Boolean = false,
    /** The phone stopped casting: the TV says so for a moment, then goes back. */
    val stopped: Boolean = false,
)

/**
 * A Live TV channel cast to this TV app (Cast Connect, PLAN-LIVE-TV.md §4.1):
 * [controller] plays as the person who cast, with their cast token, and
 * [live] reads their channels and pictures. The TV's live player takes it
 * over as the file player takes a [CastedPlay]: it starts [target] and stops
 * and releases the controller when it leaves.
 */
class CastedLive(
    val id: Long,
    val target: LiveTarget,
    val controller: LivePlaybackController,
    val live: LiveRepository,
    val channels: StateFlow<List<LiveChannel>>,
    /** The caster's server has listings ([LiveCapabilities.guide]). */
    val listings: Boolean,
    val sender: StateFlow<LiveCastSender>,
)

/** Plays cast to this TV app. */
interface CastReceiver {
    /** The play to show now; null when none. */
    val incoming: StateFlow<CastedPlay?>

    /** The player screen for [play] has gone. */
    fun finished(play: CastedPlay)

    /** The channel to show now; null when none. */
    val incomingLive: StateFlow<CastedLive?> get() = NoLive

    /** The live player for [live] has gone. */
    fun finished(live: CastedLive) {}

    companion object {
        private val NoLive: StateFlow<CastedLive?> = MutableStateFlow<CastedLive?>(null).asStateFlow()

        val None: CastReceiver = object : CastReceiver {
            override val incoming: StateFlow<CastedPlay?> = MutableStateFlow<CastedPlay?>(null).asStateFlow()
            override fun finished(play: CastedPlay) {}
        }
    }
}
