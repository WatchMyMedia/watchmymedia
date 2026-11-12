package media.watchmy.client.core.cast

import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.CastGateway
import media.watchmy.client.model.ClientInfo
import media.watchmy.client.model.LiveCastPhase
import media.watchmy.client.model.LiveCastRemote
import media.watchmy.client.model.LiveCastState
import media.watchmy.client.model.LiveChannel
import media.watchmy.client.model.LiveProgramme
import media.watchmy.client.model.StreamQuality
import media.watchmy.client.model.forLiveTV

/**
 * A Live TV channel on the TV as a remote (PLAN-LIVE-TV.md §4.1, L10d): the
 * receiver holds the live session, heartbeats it and words its problems;
 * this turns the receiver's `state.live` and MEDIA_STATUS into
 * [LiveCastState] and the viewer's taps into LOADs and commands. One for the
 * app beside the file remote ([CastPlaybackController]); [attach] and
 * [detach] follow the Cast session.
 *
 * Everything runs on [dispatcher] (the main thread in the app, where the Cast
 * SDK calls back); server calls run on [io].
 */
class LiveCastController(
    private val gateway: CastGateway,
    /** This phone, named in LOAD as who cast. */
    private val client: () -> ClientInfo,
    private val elapsedMs: () -> Long = { System.nanoTime() / 1_000_000 },
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : LiveCastRemote {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val _state = MutableStateFlow(LiveCastState())
    override val state: StateFlow<LiveCastState> = _state.asStateFlow()

    private var link: ReceiverLink? = null
    private var remote: StateMessage? = null
    private var status: ReceiverStatus? = null
    private var automaticAvailable = false
    private val receiverAutomatic get() = automaticAvailable && remote?.features?.contains(CastProtocol.FEATURE_LIVE_AUTO) == true
    private var capabilityJob: Job? = null

    /** The channel asked for that the TV has not reported yet: the remote shows it tuning. */
    private var pending: LiveChannel? = null
    private var pendingQuality: StreamQuality? = null

    /** The channel this phone last cast, for words about a LOAD the TV refused. */
    private var cast: LiveChannel? = null
    private var loadError: String? = null
    private var loadJob: Job? = null
    private var lastInputAt = Long.MIN_VALUE / 2

    private val listener = object : ReceiverLink.Listener {
        override fun onStatus(status: ReceiverStatus) {
            this@LiveCastController.status = status
            render()
        }

        override fun onMessage(message: String) {
            CastProtocol.decodeState(message)?.let(::onReceiverState)
        }
    }

    /** A Cast session started or resumed. */
    fun attach(link: ReceiverLink) {
        if (this.link === link) return
        this.link?.setListener(null)
        this.link = link
        automaticAvailable = false
        remote = null
        status = link.status
        link.setListener(listener)
        link.send(CastCommand.GetState.encode())
        render()
        capabilityJob?.cancel()
        capabilityJob = scope.launch {
            val available = try { withContext(io) { gateway.liveAutomaticAvailable() } }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { false }
            if (this@LiveCastController.link === link) {
                automaticAvailable = available
                render()
            }
        }
    }

    /** The session ended: nothing is on a TV now. */
    fun detach() {
        loadJob?.cancel()
        capabilityJob?.cancel()
        automaticAvailable = false
        link?.setListener(null)
        link = null
        remote = null
        status = null
        pending = null
        pendingQuality = null
        cast = null
        loadError = null
        render()
    }

    // --- commands ---

    override fun cast(channel: LiveChannel, quality: StreamQuality) {
        val l = link ?: return
        val q = quality.forLiveTV(receiverAutomatic)
        pending = channel
        pendingQuality = q
        cast = channel
        loadError = null
        render()
        loadJob?.cancel()
        loadJob = scope.launch {
            try {
                val server = withContext(io) { gateway.castServer() } ?: throw ApiException(null, "No server is in use.")
                val token = withContext(io) { gateway.castToken() }
                if (link !== l) return@launch
                val data = LoadData(
                    server = LoadServer(server.id, (token.urls + server.baseUrl).distinct()),
                    token = token.token,
                    tokenExpiresAt = Instant.ofEpochMilli(token.expiresAtMs).toString(),
                    live = LiveLoad(channel.id, q.id),
                    quality = q.id,
                    device = LoadDevice(l.deviceId, l.deviceName),
                    sender = LoadSender(client().name),
                )
                // The receiver names the channel the same way once it plays; no private artwork in LOAD.
                l.load(LoadRequest(CastProtocol.liveContentId(channel.id), "${channel.number} ${channel.name}", null, null, data, live = true))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                pending = null
                loadError = (e as? ApiException)?.message?.let { "Couldn't cast: $it" } ?: "Couldn't cast to the TV."
                render()
            }
        }
    }

    override fun channel(channel: LiveChannel) {
        val l = link ?: return
        if (remote?.live == null) {
            cast(channel, current().quality)
            return
        }
        lastInputAt = elapsedMs()
        pending = channel
        pendingQuality = null
        loadError = null
        l.send(CastCommand.Channel(channel.id).encode())
        render()
    }

    override fun play() {
        noteUserInput()
        link?.play()
    }

    override fun pause() {
        noteUserInput()
        link?.pause()
    }

    override fun togglePlayPause() = if (current().phase == LiveCastPhase.Paused) play() else pause()

    override fun goLive() {
        noteUserInput()
        link?.goLive()
    }

    override fun selectQuality(quality: StreamQuality) {
        val l = link ?: return
        val q = quality.forLiveTV(receiverAutomatic)
        val on = remote?.live ?: return
        if (StreamQuality.byId(on.quality) == q) return
        lastInputAt = elapsedMs()
        // The TV tunes the channel again at the new quality.
        pending = LiveChannel(on.channelId, "", on.number, on.name)
        pendingQuality = q
        l.send(CastCommand.Quality(q.id).encode())
        render()
    }

    /** Throttled: the remote calls this on every touch. */
    override fun noteUserInput() {
        val now = elapsedMs()
        if (now - lastInputAt < CastPlaybackController.INPUT_EVERY_MS) return
        lastInputAt = now
        link?.send(CastCommand.Input.encode())
    }

    override fun answerStillWatching(keepWatching: Boolean) {
        lastInputAt = elapsedMs()
        link?.send(CastCommand.StillWatching(keepWatching).encode())
    }

    override fun stop() {
        loadJob?.cancel()
        pending = null
        pendingQuality = null
        loadError = null
        link?.stop()
        render()
    }

    override suspend fun stopAndWait() {
        stop()
        withTimeoutOrNull(STOP_WAIT_MS) { state.first { !it.onTv } }
    }

    // --- the receiver's side ---

    private fun onReceiverState(m: StateMessage) {
        remote = m
        val p = pending
        // The TV has the channel asked for (or gave up on it): its own state says the rest.
        val arrived = m.live?.channelId == p?.id && pendingQuality.let { it == null || m.live?.quality == it.id }
        if (p != null && (arrived || m.phase == StateMessage.ERROR || m.fileId != null)) {
            pending = null
            pendingQuality = null
        }
        if (m.fileId != null) cast = null
        render()
    }

    private fun current(): LiveCastState = _state.value

    private fun render() {
        val l = link
        val r = remote
        val live = r?.live
        val p = pending
        val failed = loadError != null || r != null && r.phase == StateMessage.ERROR && (live != null || cast != null) && p == null
        val phase = when {
            l == null -> LiveCastPhase.Idle
            failed -> LiveCastPhase.Failed
            p != null -> LiveCastPhase.Tuning
            live == null -> LiveCastPhase.Idle
            r.phase == StateMessage.STOPPED || live.reason == "stopped_by_admin" -> LiveCastPhase.Stopped
            r.phase == StateMessage.LOADING -> LiveCastPhase.Tuning
            r.phase == StateMessage.PLAYING -> if (live.paused == true || status?.phase == ReceiverPhase.Paused) LiveCastPhase.Paused else LiveCastPhase.Playing
            else -> LiveCastPhase.Idle
        }
        val shown = p ?: cast?.takeIf { live == null || it.id == live.channelId }
        _state.value = LiveCastState(
            deviceName = l?.deviceName,
            receiverLive = r?.let { it.features?.contains(CastProtocol.FEATURE_LIVE) == true },
            phase = phase,
            channelId = p?.id ?: live?.channelId ?: shown?.id,
            number = p?.number ?: live?.number?.takeIf { it.isNotBlank() } ?: shown?.number.orEmpty(),
            name = p?.name ?: live?.name?.takeIf { it.isNotBlank() } ?: shown?.name.orEmpty(),
            now = live?.now?.toModel().takeIf { p == null },
            next = live?.next?.toModel().takeIf { p == null },
            quality = pendingQuality ?: StreamQuality.byId(live?.quality)?.forLiveTV(true) ?: StreamQuality.FullHd,
            actualQuality = StreamQuality.byId(r?.autoStep),
            automaticAvailable = receiverAutomatic,
            pauseLeftSeconds = live?.pauseLeftSeconds.takeIf { phase == LiveCastPhase.Paused },
            atLiveEdge = phase == LiveCastPhase.Playing && (status?.behindLiveMs ?: 0) <= LIVE_EDGE_TOLERANCE_MS,
            askStillWatching = (phase == LiveCastPhase.Playing || phase == LiveCastPhase.Paused) && r?.stillWatching == true,
            reason = live?.reason.takeIf { phase == LiveCastPhase.Failed || phase == LiveCastPhase.Stopped },
            error = (loadError ?: r?.error).takeIf { phase == LiveCastPhase.Failed },
        )
    }

    companion object {
        /** How long [stopAndWait] waits for the TV to say it stopped. */
        const val STOP_WAIT_MS = 5_000L

        /** Further behind than this is behind live, as on the phone's own player. */
        const val LIVE_EDGE_TOLERANCE_MS = 10_000L

        internal fun LiveProgrammeInfo.toModel(): LiveProgramme? {
            val start = runCatching { Instant.parse(start).toEpochMilli() }.getOrNull() ?: return null
            val end = runCatching { Instant.parse(end).toEpochMilli() }.getOrNull()?.takeIf { it > start } ?: return null
            return LiveProgramme(id = "", startMs = start, endMs = end, title = title, episodeTitle = episodeTitle?.takeIf { it.isNotBlank() })
        }
    }
}
