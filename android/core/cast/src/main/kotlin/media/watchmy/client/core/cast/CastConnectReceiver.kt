package media.watchmy.client.core.cast

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.android.gms.cast.MediaError
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.RequestData
import com.google.android.gms.cast.tv.CastReceiverContext
import com.google.android.gms.cast.tv.CastReceiverOptions
import com.google.android.gms.cast.tv.media.MediaCommandCallback
import com.google.android.gms.cast.tv.media.MediaException
import com.google.android.gms.cast.tv.media.MediaLoadCommandCallback
import com.google.android.gms.cast.tv.media.SeekRequestData
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import media.watchmy.client.model.CastReceiver
import media.watchmy.client.model.CastedLive
import media.watchmy.client.model.CastedPlay
import media.watchmy.client.model.LIVE_QUALITIES
import media.watchmy.client.model.LiveCastSender
import media.watchmy.client.model.LiveChannel
import media.watchmy.client.model.LivePlaybackController
import media.watchmy.client.model.LiveRepository
import media.watchmy.client.model.LiveTarget
import media.watchmy.client.model.liveChannelAfter
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.PlaybackController
import media.watchmy.client.model.PlayerEvent
import media.watchmy.client.model.PlayerStatus
import media.watchmy.client.model.PlayerUiState
import media.watchmy.client.model.StreamQuality
import org.json.JSONObject

/** What [CastConnectReceiver] plays a cast with: a player on the caster's token, and their server's artwork URLs. */
class CastConnectPlay(val controller: PlaybackController, val imageUrl: (path: String?) -> String?)

/**
 * What [CastConnectReceiver] plays a cast channel with (PLAN-LIVE-TV.md §4.1):
 * a live player on the caster's token, and their channels and pictures.
 */
class CastConnectLive(val controller: LivePlaybackController, val live: LiveRepository)

/**
 * Plays cast to this Android TV app (Cast Connect, PLAN-CAST.md §6) instead
 * of the web receiver. A sender's LOAD carries [LoadData]; [open] finds the
 * caster's server from here and builds a player that plays as them, which
 * [incoming] hands to the TV's player screen. Senders get MEDIA_STATUS from
 * [CastReceiverContext]'s MediaManager and our `state` messages, and their
 * commands drive the player, so they cannot tell this from the web receiver.
 * Build it once on a TV, on the main thread, before the activity handles intents.
 */
class CastConnectReceiver internal constructor(
    /** This TV's name, for senders. */
    private val deviceName: () -> String,
    /** Null when none of the sender's addresses reaches its server from here. Throws on server errors. */
    private val open: suspend (LoadData) -> CastConnectPlay?,
    private val receiver: CastReceiverSink,
    private val scope: CoroutineScope,
    /** A Live TV channel's player as the caster; null when none of the sender's addresses reaches its server. Throws on server errors. */
    private val openLive: suspend (LoadData) -> CastConnectLive? = { null },
    /** The wall clock, for what is on a channel now. */
    private val wallMs: () -> Long = System::currentTimeMillis,
    /** Milliseconds on a monotonic clock, for spacing MEDIA_STATUS. */
    private val nowMs: () -> Long,
) : CastReceiver {
    constructor(
        context: Context,
        deviceName: () -> String,
        open: suspend (LoadData) -> CastConnectPlay?,
        openLive: suspend (LoadData) -> CastConnectLive?,
    ) : this(deviceName, open, SdkReceiverSink(context), CoroutineScope(SupervisorJob() + Dispatchers.Main), openLive, nowMs = { System.nanoTime() / 1_000_000 })

    private val _incoming = MutableStateFlow<CastedPlay?>(null)
    override val incoming: StateFlow<CastedPlay?> = _incoming.asStateFlow()

    private val _incomingLive = MutableStateFlow<CastedLive?>(null)
    override val incomingLive: StateFlow<CastedLive?> = _incomingLive.asStateFlow()

    private var nextId = 1L
    private var current: Running? = null
    private var currentLive: RunningLive? = null

    /** The channel being shown, its caster's channel list, and what senders were last told. */
    private inner class RunningLive(val live: CastedLive, val channels: MutableStateFlow<List<LiveChannel>>, val sender: MutableStateFlow<LiveCastSender>) {
        var lastSent: String? = null
        var sentStatus: PlayerStatus? = null
        var job: Job? = null
    }

    /** The play being shown, and what senders were last told about it. */
    private inner class Running(val play: CastedPlay, val imageUrl: (String?) -> String?) {
        var fileId = play.request.fileId
        var lastSent: String? = null
        var sentStatus: PlayerStatus? = null
        var sentPositionMs = 0L
        var sentAtMs = 0L
        var job: Job? = null
    }

    init {
        receiver.attach(Loads(), Commands(), ::onMessage)
    }

    /** Hands a Cast Connect LOAD intent to the Cast SDK; true when it was one. */
    fun onNewIntent(intent: Intent?): Boolean = intent != null && receiver.onNewIntent(intent)

    override fun finished(live: CastedLive) {
        val r = currentLive?.takeIf { it.live === live } ?: return
        r.job?.cancel()
        currentLive = null
        if (_incomingLive.value === live) _incomingLive.value = null
        receiver.status(MediaStatus.PLAYER_STATE_IDLE, MediaStatus.IDLE_REASON_FINISHED)
        broadcast(idleState())
    }

    private fun idleState() = CastProtocol.encodeState(StateMessage(features = CAST_CONNECT_FEATURES, phase = StateMessage.IDLE, deviceName = deviceName()))

    override fun finished(play: CastedPlay) {
        val r = current?.takeIf { it.play === play } ?: return
        r.job?.cancel()
        current = null
        if (_incoming.value === play) _incoming.value = null
        receiver.status(MediaStatus.PLAYER_STATE_IDLE, MediaStatus.IDLE_REASON_FINISHED)
        broadcast(idleState())
    }

    private inner class Loads : MediaLoadCommandCallback() {
        override fun onLoad(senderId: String?, data: MediaLoadRequestData): Task<MediaLoadRequestData> {
            val result = TaskCompletionSource<MediaLoadRequestData>()
            scope.launch {
                val load = data.mediaInfo?.customData?.toString()?.let(CastProtocol::decodeLoad)
                    // A bare token in credentials is not enough: the sender must say which server and file.
                    ?: return@launch result.setException(loadFailed("No WatchMyMedia play in the request."))
                if (load.live != null) return@launch loadLive(data, load, result)
                val fileId = load.fileId ?: return@launch result.setException(loadFailed("No WatchMyMedia play in the request."))
                val opened = try {
                    open(load)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "cast load failed", e)
                    null
                } ?: return@launch result.setException(loadFailed("This TV can't reach the server."))
                val meta = data.mediaInfo?.metadata
                val request = PlayRequest(
                    fileId = fileId,
                    startMs = load.startMs,
                    title = meta?.getString(MediaMetadata.KEY_TITLE).orEmpty(),
                    subtitle = meta?.getString(MediaMetadata.KEY_SUBTITLE),
                    audioIndex = load.audioIndex,
                    subtitleId = load.subtitleId,
                    subtitleMode = if (load.subtitleMode == "auto" || load.subtitleId != null) media.watchmy.client.model.SubtitleRequestMode.Auto else media.watchmy.client.model.SubtitleRequestMode.Off,
                )
                current?.job?.cancel()
                endLive()
                val running = Running(CastedPlay(nextId++, request, opened.controller), opened.imageUrl)
                current = running
                running.job = scope.launch { watch(running) }
                _incoming.value = running.play
                val publicData = publicCastLoad(data, fileId)
                receiver.loaded(publicData)
                result.setResult(publicData)
            }
            return result.task
        }
    }

    private inner class Commands : MediaCommandCallback() {
        override fun onPlay(senderId: String?, data: RequestData): Task<Void> = command(onLive = {
            sender.value = sender.value.copy(pausedThere = false)
            live.controller.play()
        }) { play() }

        override fun onPause(senderId: String?, data: RequestData): Task<Void> = command(onLive = {
            sender.value = sender.value.copy(pausedThere = true)
            live.controller.pause()
        }) { pause() }

        override fun onSeek(senderId: String?, data: SeekRequestData): Task<Void> = command(onLive = {
            // A live stream seeks only to its live edge (a seek to infinity).
            if (data.currentTime == SeekRequestData.SEEK_POSITION_INFINITE_MS) live.controller.goLive()
        }) {
            data.currentTime?.let(::seekTo) ?: data.relativeTime?.let(::seekBy)
        }

        override fun onStop(senderId: String?, data: RequestData): Task<Void> = command(onLive = { stoppedBySender(this) }) {
            current?.play?.let { play ->
                stop()
                finished(play)
            }
        }

        private fun command(onLive: RunningLive.() -> Unit, block: PlaybackController.() -> Unit): Task<Void> {
            val l = currentLive
            if (l != null) l.onLive() else current?.play?.controller?.block()
            return Tasks.forResult(null)
        }

    }

    private fun onMessage(senderId: String?, message: String) {
        currentLive?.let { return onLiveMessage(it, senderId, message) }
        val r = current
        val c = r?.play?.controller
        when (val command = CastCommand.decode(message)) {
            CastCommand.GetState -> if (senderId != null) send(senderId, r?.lastSent ?: idleState())
            is CastCommand.Seek -> c?.seekTo(command.positionMs)
            CastCommand.Skip -> c?.skip()
            CastCommand.PlayNext -> c?.playNext()
            CastCommand.WatchCredits -> c?.watchCredits()
            is CastCommand.StillWatching -> c?.answerStillWatching(command.keep)
            is CastCommand.Audio -> c?.selectAudio(command.index)
            is CastCommand.Subtitle -> c?.selectSubtitle(command.id)
            is CastCommand.Quality -> StreamQuality.byId(command.id)?.let { c?.selectQuality(it) }
            CastCommand.Input -> c?.noteUserInput()
            // A channel command with no channel cast here: nothing to change.
            is CastCommand.Channel, is CastCommand.ChannelStep -> Unit
            null -> Unit
        }
    }

    // --- Live TV (PLAN-LIVE-TV.md §4.1) ---

    /** A channel's LOAD: the caster's server and channel list, then the TV's live player on their token. */
    private suspend fun loadLive(data: MediaLoadRequestData, load: LoadData, result: TaskCompletionSource<MediaLoadRequestData>) {
        val want = load.live ?: return
        // One live session at a time: the one playing gives its tuner back before another starts.
        endLive(wait = true)
        val opened = try {
            openLive(load)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "cast live load failed", e)
            null
        } ?: return result.setException(loadFailed("This TV can't reach the server."))
        val channels = try {
            opened.live.channels()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "cast live channels failed", e)
            opened.controller.release()
            return result.setException(loadFailed("This TV can't reach the server."))
        }
        val channel = channels.firstOrNull { it.id == want.channelId } ?: run {
            opened.controller.release()
            return result.setException(loadFailed("This channel isn't available to you."))
        }
        val listings = try {
            opened.live.capabilities().guide
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        current?.let { r ->
            // A file playing here gives way: its screen goes, which stops and releases it.
            r.job?.cancel()
            current = null
            _incoming.value = null
        }
        val list = MutableStateFlow(channels)
        val sender = MutableStateFlow(LiveCastSender(load.sender?.name?.takeIf { it.isNotBlank() } ?: DEFAULT_SENDER))
        val running = RunningLive(CastedLive(nextId++, LiveTarget(channel), opened.controller, opened.live, list.asStateFlow(), listings, sender.asStateFlow()), list, sender)
        currentLive = running
        running.job = scope.launch { watchLive(running) }
        _incomingLive.value = running.live
        val publicData = publicCastLoad(data, CastProtocol.liveContentId(channel.id), live = true)
        receiver.loaded(publicData)
        result.setResult(publicData)
    }

    /** The channel cast here ends: its screen goes, and with [wait] its tuner is back first. */
    private suspend fun endLive(wait: Boolean = false) {
        val r = currentLive ?: return
        r.job?.cancel()
        currentLive = null
        if (_incomingLive.value === r.live) _incomingLive.value = null
        if (wait) withTimeoutOrNull(STOP_WAIT_MS) { r.live.controller.stopAndRelease() } else r.live.controller.stop()
    }

    /** The phone stopped casting: the channel stops, and the TV says so for a moment ([LiveCastSender.stopped]). */
    private fun stoppedBySender(r: RunningLive) {
        r.live.controller.stop()
        r.sender.value = r.sender.value.copy(stopped = true)
    }

    private fun onLiveMessage(r: RunningLive, senderId: String?, message: String) {
        val c = r.live.controller
        fun tune(channel: LiveChannel?) {
            channel ?: return
            r.sender.value = r.sender.value.copy(pausedThere = false)
            c.start(LiveTarget(channel))
        }
        val on = c.state.value.target?.channel?.id ?: r.live.target.channel.id
        when (val command = CastCommand.decode(message)) {
            CastCommand.GetState -> if (senderId != null) send(senderId, r.lastSent ?: idleState())
            is CastCommand.Channel -> if (command.channelId != on) tune(r.channels.value.firstOrNull { it.id == command.channelId && it.available })
            is CastCommand.ChannelStep -> tune(liveChannelAfter(r.channels.value, on, command.by))
            is CastCommand.Quality -> StreamQuality.byId(command.id)?.takeIf { it in LIVE_QUALITIES }?.let(c::selectQuality)
            is CastCommand.StillWatching -> if (command.keep) c.answerStillWatching(true) else stoppedBySender(r)
            CastCommand.Input -> c.noteUserInput()
            // File commands mean nothing to a channel.
            is CastCommand.Seek, CastCommand.Skip, CastCommand.PlayNext, CastCommand.WatchCredits,
            is CastCommand.Audio, is CastCommand.Subtitle, null -> Unit
        }
    }

    /**
     * Keeps senders up to date with the channel: MEDIA_STATUS for play state,
     * our `state` for the rest, and the caster's channel list asked again
     * when what is on ends, so Now and Next stay right. Ends when the play does.
     */
    private suspend fun watchLive(r: RunningLive): Unit = coroutineScope {
        val c = r.live.controller
        launch {
            while (true) {
                val now = wallMs()
                val end = r.channels.value.mapNotNull { it.now?.endMs }.filter { it > now }.minOrNull()
                delay(((end ?: (now + CHANNELS_EVERY_MS)) - now + CHANNELS_GRACE_MS).coerceIn(CHANNELS_GRACE_MS, CHANNELS_EVERY_MS))
                try {
                    r.channels.value = r.live.live.channels()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "cast live channels refresh failed", e)
                }
            }
        }
        combine(c.state, r.channels) { s, list -> s to list }.collect { (s, list) ->
            if (s.status != r.sentStatus) {
                r.sentStatus = s.status
                when (s.status) {
                    PlayerStatus.Playing -> receiver.status(MediaStatus.PLAYER_STATE_PLAYING)
                    PlayerStatus.Paused -> receiver.status(MediaStatus.PLAYER_STATE_PAUSED)
                    PlayerStatus.Loading, PlayerStatus.Buffering -> receiver.status(MediaStatus.PLAYER_STATE_BUFFERING)
                    PlayerStatus.Ended -> receiver.status(MediaStatus.PLAYER_STATE_IDLE, MediaStatus.IDLE_REASON_FINISHED)
                    PlayerStatus.Error -> receiver.status(MediaStatus.PLAYER_STATE_IDLE, MediaStatus.IDLE_REASON_ERROR)
                }
            }
            val target = s.target?.channel ?: r.live.target.channel
            val channel = list.firstOrNull { it.id == target.id } ?: target
            val message = CastProtocol.encodeState(liveConnectState(s, channel, wallMs(), deviceName()))
            if (message != r.lastSent) {
                r.lastSent = message
                broadcast(message)
            }
        }
    }

    /**
     * Keeps senders up to date with the player: MEDIA_STATUS for play state
     * and position (the state and the progress), our `state` for the rest
     * (the state only). Ends when the play does.
     */
    private suspend fun watch(r: Running): Unit = coroutineScope {
        val c = r.play.controller
        launch {
            c.events.collect { if (it is PlayerEvent.StartedNext) r.fileId = it.fileId }
        }
        launch {
            c.progress.collect { publishStatus(r, c.state.value.status, it.positionMs) }
        }
        c.state.collect { s ->
            publishStatus(r, s.status, c.progress.value.positionMs)
            val message = CastProtocol.encodeState(stateMessage(r, s))
            if (message != r.lastSent) {
                r.lastSent = message
                broadcast(message)
            }
        }
    }

    private fun publishStatus(r: Running, status: PlayerStatus, positionMs: Long) {
        val now = nowMs()
        val playing = status == PlayerStatus.Playing
        val expected = r.sentPositionMs + if (r.sentStatus == PlayerStatus.Playing) now - r.sentAtMs else 0
        val jumped = abs(positionMs - expected) > POSITION_TOLERANCE_MS
        if (status == r.sentStatus && !jumped && !(playing && now - r.sentAtMs > RESYNC_EVERY_MS)) return
        when (status) {
            PlayerStatus.Playing -> receiver.status(MediaStatus.PLAYER_STATE_PLAYING, positionMs = positionMs)
            PlayerStatus.Paused -> receiver.status(MediaStatus.PLAYER_STATE_PAUSED, positionMs = positionMs)
            PlayerStatus.Loading, PlayerStatus.Buffering -> receiver.status(MediaStatus.PLAYER_STATE_BUFFERING, positionMs = positionMs)
            PlayerStatus.Ended -> receiver.status(MediaStatus.PLAYER_STATE_IDLE, MediaStatus.IDLE_REASON_FINISHED, positionMs)
            PlayerStatus.Error -> receiver.status(MediaStatus.PLAYER_STATE_IDLE, MediaStatus.IDLE_REASON_ERROR, positionMs)
        }
        r.sentStatus = status
        r.sentPositionMs = positionMs
        r.sentAtMs = now
    }

    private fun stateMessage(r: Running, s: PlayerUiState) = castConnectState(r.fileId, s, r.imageUrl, deviceName())

    private fun broadcast(message: String) {
        receiver.senderIds().forEach { send(it, message) }
    }

    private fun send(senderId: String, message: String) {
        runCatching { receiver.send(senderId, message) }
            .onFailure { Log.w(TAG, "could not message a sender", it) }
    }

    companion object {
        private const val TAG = "WmmCastConnect"

        /** Who cast, when the sender doesn't say. */
        internal const val DEFAULT_SENDER = "your phone"

        /** How long a new channel's LOAD waits for the last one's tuner to come back. */
        private const val STOP_WAIT_MS = 5_000L

        /** The caster's channel list is asked again at least this often while a channel plays... */
        private const val CHANNELS_EVERY_MS = 5 * 60_000L

        /** ...and this long after what is on ends, when the server knows what follows. */
        private const val CHANNELS_GRACE_MS = 5_000L

        /** MEDIA_STATUS goes out again when the position strays this far from what senders work out themselves. */
        private const val POSITION_TOLERANCE_MS = 2_000L

        /** ...and every so often while playing, to correct drift. */
        private const val RESYNC_EVERY_MS = 15_000L

        private fun loadFailed(reason: String) = MediaException(
            MediaError.Builder()
                .setType(MediaError.ERROR_TYPE_LOAD_FAILED)
                .setDetailedErrorCode(MediaError.DetailedErrorCode.LOAD_FAILED)
                .setCustomData(JSONObject().put("message", reason))
                .build(),
        )

        /** The receiver options, for the app's ReceiverOptionsProvider. */
        fun options(context: Context, appId: String): CastReceiverOptions = CastReceiverOptions.Builder(context)
            .setCastAppId(appId)
            .setCustomNamespaces(listOf(CastProtocol.NAMESPACE))
            .setLaunchRequestChecker { com.google.android.gms.tasks.Tasks.forResult(true) }
            .build()
    }
}

/**
 * The Cast SDK's receiver as [CastConnectReceiver] uses it: [CastReceiverContext]
 * in the app ([SdkReceiverSink]), a recording in tests.
 */
internal interface CastReceiverSink {
    /** Sends the SDK's LOADs, media commands and our namespace's messages to the receiver. */
    fun attach(loads: MediaLoadCommandCallback, commands: MediaCommandCallback, onMessage: (senderId: String?, message: String) -> Unit)

    fun onNewIntent(intent: Intent): Boolean

    /** Takes a LOAD's media as what is playing, and tells senders. */
    fun loaded(data: MediaLoadRequestData)

    /** MEDIA_STATUS to every sender, with the idle reason and position when given. */
    fun status(playerState: Int, idleReason: Int? = null, positionMs: Long? = null)

    fun senderIds(): List<String>

    /** A message on [CastProtocol.NAMESPACE]. */
    fun send(senderId: String, message: String)
}

/** [CastReceiverSink] on [CastReceiverContext], up while the app is in the foreground, as Google asks. */
private class SdkReceiverSink(context: Context) : CastReceiverSink {
    private val receiver: CastReceiverContext

    init {
        CastReceiverContext.initInstance(context.applicationContext)
        receiver = CastReceiverContext.getInstance()
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> receiver.start()
                    Lifecycle.Event.ON_STOP -> receiver.stop()
                    else -> Unit
                }
            },
        )
    }

    override fun attach(loads: MediaLoadCommandCallback, commands: MediaCommandCallback, onMessage: (String?, String) -> Unit) {
        receiver.mediaManager.setMediaLoadCommandCallback(loads)
        receiver.mediaManager.setMediaCommandCallback(commands)
        receiver.setMessageReceivedListener(CastProtocol.NAMESPACE) { _, senderId, message -> onMessage(senderId, message) }
    }

    override fun onNewIntent(intent: Intent): Boolean = receiver.mediaManager.onNewIntent(intent)

    override fun loaded(data: MediaLoadRequestData) {
        receiver.mediaManager.setDataFromLoad(data)
        receiver.mediaManager.broadcastMediaStatus()
    }

    override fun status(playerState: Int, idleReason: Int?, positionMs: Long?) {
        val modifier = receiver.mediaManager.mediaStatusModifier
        modifier.setPlayerState(playerState)
        idleReason?.let(modifier::setIdleReason)
        positionMs?.let(modifier::setStreamPosition)
        receiver.mediaManager.broadcastMediaStatus()
    }

    override fun senderIds(): List<String> = receiver.senders.map { it.senderId }

    override fun send(senderId: String, message: String) = receiver.sendMessage(CastProtocol.NAMESPACE, senderId, message)
}
