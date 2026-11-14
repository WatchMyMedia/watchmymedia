package media.watchmy.client.core.playback

import androidx.media3.common.Player
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import media.watchmy.client.core.playback.player.MediaPlayer
import media.watchmy.client.core.playback.player.PlayerFailure
import media.watchmy.client.core.playback.player.PlayerPhase
import media.watchmy.client.core.playback.player.PlayerSource
import media.watchmy.client.core.playback.player.TextSelection
import media.watchmy.client.core.playback.session.LiveSessionPlayer
import media.watchmy.client.core.playback.session.MediaSessionHost
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.ClientInfo
import media.watchmy.client.model.LIVE_QUALITIES
import media.watchmy.client.model.LiveCapabilities
import media.watchmy.client.model.LivePlaybackController
import media.watchmy.client.model.LivePlaybackGateway
import media.watchmy.client.model.takeoverDue
import media.watchmy.client.model.takeoverPlayRequest
import media.watchmy.client.model.takeoverRecording
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.LivePlayerState
import media.watchmy.client.model.LiveMeasurement
import media.watchmy.client.model.LivePlaybackState
import media.watchmy.client.model.LiveOutputDirective
import media.watchmy.client.model.LiveSession
import media.watchmy.client.model.LiveSessionState
import media.watchmy.client.model.LiveTarget
import media.watchmy.client.model.PlayerStatus
import media.watchmy.client.model.PlaybackProfile
import media.watchmy.client.model.PreferencesRepository
import media.watchmy.client.model.StreamQuality
import media.watchmy.client.model.forLiveTV
import media.watchmy.client.model.liveTVError

/**
 * One owner-bound Live TV player. It has no file gateway, progress reporter,
 * downloads, Cast, Watch Next or finite playback clock. Starts and stops share
 * one lane: a late start is released before the latest selection can tune.
 *
 * Network calls are intentionally not cancelled when a screen or selection
 * changes: cancelling a POST can hide an allocated tuner. Generation checks
 * keep its response off screen and the same lane cleans it up. A lost reply
 * is resolved with its original idempotency key before any new allocation.
 */
class RealLivePlaybackController(
    gateway: LivePlaybackGateway,
    private val preferences: PreferencesRepository,
    private val client: ClientInfo,
    private val playerFactory: () -> MediaPlayer,
    private val sessions: MediaSessionHost = MediaSessionHost.None,
    private val visibility: AppVisibility = AppVisibility.AlwaysVisible,
    private val network: MeteredNetwork = MeteredNetwork.Never,
    private val clock: Clock = DeviceClock,
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val requestId: () -> String = { UUID.randomUUID().toString() },
    private val profile: () -> PlaybackProfile = { PlaybackProfile("Android Live TV", listOf("mpegts"), listOf("h264"), listOf("aac"), maxHeight = 1080) },
) : LivePlaybackController {
    private val gateway = gateway.bind()
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + dispatcher)
    private val lane = Mutex()
    private val choice = QualityChoice(preferences, network)
    private val mutableState = MutableStateFlow(LivePlayerState(quality = choice.current().forLiveTV()))
    override val state = mutableState.asStateFlow()
    private val host = PlayerHost(scope, playerFactory, sessions, visibility, clock)
    private val media: MediaPlayer? get() = host.media
    override val player: StateFlow<Player?> = host.player
    private var generation = 0L
    private var released = false
    private var foreground = true
    private var selected: LiveTarget? = null
    private var quality = choice.current().forLiveTV()
    /** Ownership is retained after a failed DELETE, even though the video is detached. */
    private var owned: LiveSession? = null
    /**
     * The last view that named a recording taking this tuner. Kept past its
     * time, when the server stops sending it, so a stream that ends then is
     * put down to the recording rather than a lost signal.
     */
    private var takeover: LiveSession? = null
    private var unresolved: PendingStart? = null
    private var loadedSession: String? = null
    private var loadedUrl: String? = null

    /** Automatic's output switches; what a failed one goes back to is the URL before it. */
    private val switch = OutputSwitch<String>(keepFallbackUntilReported = false)
    private var pollJob: Job? = null
    /** The last stop's DELETE, which [stopAndRelease] waits for. */
    private var releaseJob: Job? = null
    private var lastPollAt = 0L
    private var lastBeatAt = 0L
    private val tokens = MediaTokenClock(clock)

    /** The server's Live TV capabilities as last read, and when; a channel change uses them rather than asking again. */
    private var capabilities: LiveCapabilities? = null
    private var capabilitiesAt = 0L
    private var capabilitiesJob: Job? = null
    private var interaction = 0L
    private var sentInteraction = 0L
    private var lastInputAt = clock.elapsedMs()
    private var pauseAt: Long? = null
    private var paused = false
    private var lastReportedPause = false
    private var sessionReceivedAt = 0L
    private var tuningAt = 0L
    private var firstReady = false
    private var behindWindowRecoveries = 0

    private data class PendingStart(val target: LiveTarget, val quality: StreamQuality, val id: String, val profile: PlaybackProfile)

    private val accountJob = scope.launch {
        gateway.active.collect { current ->
            if (!current && !released) end("Live TV stopped because the server or signed-in account changed.")
        }
    }

    override fun start(target: LiveTarget) {
        if (released || !foreground) return
        if (!gateway.isCurrent) {
            end("Live TV stopped because the server or signed-in account changed.")
            return
        }
        if (selected == target && state.value.status in listOf(PlayerStatus.Loading, PlayerStatus.Playing, PlayerStatus.Paused, PlayerStatus.Buffering)) return
        selected = target
        if (!choice.picked) quality = choice.current().forLiveTV()
        noteUserInput()
        paused = false
        pauseAt = null
        behindWindowRecoveries = 0
        tuningAt = clock.elapsedMs()
        firstReady = false
        takeover = null
        switch.reset()
        val currentGeneration = ++generation
        detachMedia()
        mutableState.value = LivePlayerState(target = target, quality = quality, automaticAvailable = state.value.automaticAvailable)
        ensurePlayer()
        startTicker()
        val nextId = requestId()
        scope.launch {
            lane.withLock {
                if (!current(currentGeneration)) return@withLock
                try {
                    val capabilities = capabilitiesForStart()
                    if (!current(currentGeneration)) return@withLock
                    val chosen = choice.current().forLiveTV(capabilities.automatic)
                    quality = chosen
                    mutableState.update { it.copy(quality = chosen, automaticAvailable = capabilities.automatic) }
                    val requested = PendingStart(target, chosen, nextId, profile())
                    // An ambiguous retry of the same channel reuses its request ID.
                    val pending = unresolved
                    if (pending?.target != target || pending.quality != chosen) releaseOwned()
                    if (!current(currentGeneration)) return@withLock
                    val session = allocate(unresolved ?: requested)
                    if (!current(currentGeneration)) {
                        releaseOwned()
                        return@withLock
                    }
                    accept(session, currentGeneration)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (current(currentGeneration)) {
                        fail(message(e), reasonOf(e))
                        if (owned != null) runCatching { releaseOwned() }
                    }
                }
            }
        }
    }

    override fun play() {
        if (released) return
        noteUserInput()
        if (state.value.status in listOf(PlayerStatus.Error, PlayerStatus.Ended)) { retry(); return }
        val session = state.value.session ?: return
        if (session.state != LiveSessionState.Playing) return
        val held = pauseAt?.let { clock.elapsedMs() - it } ?: 0
        val window = media?.liveWindowDurationMs
        val position = media?.positionMs ?: 0
        if (held >= session.bufferSeconds * 1_000L || window != null && position !in 0..window) {
            media?.seekToLiveEdge()
            mutableState.update { it.copy(message = "Your pause passed the buffer. Resuming at live.", atLiveEdge = true) }
        }
        paused = false
        pauseAt = null
        media?.play()
        poll(forceHeartbeat = true)
        refresh()
    }

    override fun pause() {
        if (released || state.value.session?.state != LiveSessionState.Playing) return
        noteUserInput()
        if (switch.pending != null) rejectOutput(state.value.session!!)
        if (!paused) pauseAt = clock.elapsedMs()
        paused = true
        media?.pause()
        poll(forceHeartbeat = true)
        refresh()
    }

    override fun togglePlayPause() {
        if (paused || state.value.status in listOf(PlayerStatus.Error, PlayerStatus.Ended)) play() else pause()
    }

    override fun goLive() {
        if (released) return
        if (state.value.status in listOf(PlayerStatus.Error, PlayerStatus.Ended)) { retry(); return }
        noteUserInput()
        media?.seekToLiveEdge()
        paused = false
        pauseAt = null
        media?.play()
        mutableState.update { it.copy(atLiveEdge = true, message = "Playing live.") }
        poll(forceHeartbeat = true)
        refresh()
    }

    override fun selectQuality(quality: StreamQuality) {
        if (released || quality !in LIVE_QUALITIES || quality == this.quality || quality == StreamQuality.Auto && !state.value.automaticAvailable) return
        this.quality = quality
        // Saved as ordinary playback saves it: the Default or the On mobile data quality.
        choice.pick(quality, scope)
        val target = selected
        selected = null
        mutableState.update { it.copy(quality = quality) }
        if (target != null) start(target)
    }

    override fun noteUserInput() {
        if (released) return
        interaction++
        lastInputAt = clock.elapsedMs()
        mutableState.update { it.copy(askStillWatching = false) }
    }

    override fun answerStillWatching(keepWatching: Boolean) {
        if (keepWatching) {
            noteUserInput()
            poll(forceHeartbeat = true)
        } else stop()
    }

    override fun retry() {
        val target = selected ?: state.value.target ?: return
        selected = null
        start(target)
    }

    override fun stop() { if (!released) end(null) }

    override suspend fun stopAndRelease() {
        stop()
        releaseJob?.join()
    }

    override fun release() {
        if (released) return
        end(null)
        released = true
        accountJob.cancel()
        host.release()
        // Already queued network work drains, including a late POST's DELETE.
        job.complete()
    }

    /**
     * The capabilities a start goes by: read once, then held, and read again
     * in the background once older than [CAPABILITIES_FRESH_MS], so a
     * channel change never waits for them. A failed read is not kept.
     */
    private suspend fun capabilitiesForStart(): LiveCapabilities {
        val held = capabilities ?: return readCapabilities() ?: LiveCapabilities()
        if (clock.elapsedMs() - capabilitiesAt >= CAPABILITIES_FRESH_MS && capabilitiesJob?.isActive != true) {
            capabilitiesJob = scope.launch {
                val fresh = readCapabilities() ?: return@launch
                if (!released) mutableState.update { it.copy(automaticAvailable = fresh.automatic) }
            }
        }
        return held
    }

    private suspend fun readCapabilities(): LiveCapabilities? = try {
        withContext(io) { gateway.liveCapabilities() }.also {
            capabilities = it
            capabilitiesAt = clock.elapsedMs()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun current(value: Long): Boolean = !released && generation == value && gateway.isCurrent

    private fun playing(value: Long, id: String): Boolean = current(value) && state.value.session?.id == id &&
        state.value.status !in listOf(PlayerStatus.Error, PlayerStatus.Ended)

    private suspend fun allocate(request: PendingStart): LiveSession {
        try {
            val session = withContext(io) { gateway.startNegotiated(request.target, request.quality, request.id, client, request.profile) }
            unresolved = null
            owned = session
            return session
        } catch (e: Exception) {
            // Only a definite 4xx refusal means no allocation. Http.call maps
            // transport failures to ApiException(null); proxy 5xx/408 and a
            // decoding failure can likewise hide a successful allocation.
            val status = (e as? ApiException)?.status
            if (status == null || status !in 400..499 || status == 408) unresolved = request
            throw e
        }
    }

    /** Called only in [lane]. A failed stop retains ownership and blocks new starts. */
    private suspend fun releaseOwned() {
        if (unresolved != null && gateway.isCurrent) allocate(unresolved!!)
        val session = owned ?: return
        withContext(io) { gateway.stop(session.id) }
        owned = null
    }

    private fun end(reason: String?, code: String? = null) {
        val currentGeneration = ++generation
        selected = null
        paused = false
        pauseAt = null
        switch.reset()
        detachMedia()
        host.stopTicker()
        mutableState.update { it.copy(status = PlayerStatus.Ended, session = null, askStillWatching = false, error = reason, reason = code, takeoverRecording = null) }
        releaseJob = scope.launch {
            lane.withLock {
                try { releaseOwned() } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    if (!released && currentGeneration == generation) {
                        mutableState.update { it.copy(message = "The server could not confirm the stop. It will release this tuner when its lease expires.") }
                    }
                }
            }
        }
    }

    private fun fail(error: String, reason: String? = null, takeoverRecording: PlayRequest? = null) {
        switch.reset()
        detachMedia()
        host.stopTicker()
        mutableState.update { it.copy(status = PlayerStatus.Error, session = null, askStillWatching = false, error = error, reason = reason, takeoverRecording = takeoverRecording) }
    }

    private suspend fun accept(session: LiveSession, currentGeneration: Long) {
        if (!current(currentGeneration)) return
        owned = session
        sessionReceivedAt = clock.elapsedMs()
        rememberTakeover(session)
        if (session.state.terminal) {
            val recording = session.takeoverPlayRequest() ?: takeover?.takeIf { session.reason == "recording" && it.id == session.id }?.takeoverRecording()
            fail(liveTVError(session.reason), session.reason, recording)
            // Stopping/unknown may still own resources. DELETE joins cleanup.
            releaseOwned()
            return
        }
        mutableState.update { it.copy(session = session, error = null) }
        if (session.state == LiveSessionState.Playing && loadedSession != session.id) {
            val url = session.url ?: throw IllegalStateException("The server did not provide the live stream.")
            ensureToken()
            if (!current(currentGeneration)) return
            loadedSession = session.id
            loadedUrl = url
            host.load(liveSource(session, url))
        }
        if (session.state == LiveSessionState.Playing && session.automatic && !paused) prepareOutput(session)
        refresh()
    }

    private fun liveSource(session: LiveSession, url: String) = PlayerSource(
        url = url, hls = true, live = true, startPositionMs = 0, playWhenReady = !paused,
        subtitles = emptyList(), text = TextSelection.Off, audioOrdinal = null,
        title = "${session.channel.number} · ${session.channel.name}", subtitle = "Live TV", atLiveEdge = true,
    )

    /** Reloads a prepared output of the same logical viewer. Only the first
     * rendered frame may promote it; a failed load reports the generation. */
    private fun prepareOutput(session: LiveSession) {
        val directive: LiveOutputDirective = session.directive ?: return
        if (switch.pending != null || !switch.take(directive.generation, floor = session.outputGeneration)) return
        if (directive.url.isBlank()) {
            switch.failed(directive.generation)
            poll(forceHeartbeat = true)
            return
        }
        val previous = loadedUrl ?: session.url ?: return
        switch.begin(directive.generation, previous, host.loads + 1)
        loadedUrl = directive.url
        try { host.load(liveSource(session, directive.url)) }
        catch (_: Exception) { rejectOutput(session) }
    }

    private fun rejectOutput(session: LiveSession) {
        val previous = switch.reject()?.fallback ?: return
        loadedUrl = previous
        mutableState.update { it.copy(message = "Keeping the current Live TV quality after a switch failed.") }
        try { host.load(liveSource(session, previous)) }
        catch (_: Exception) { end("The Live TV output could not be restored. Try the channel again.") }
        poll(forceHeartbeat = true)
    }

    private fun rememberTakeover(session: LiveSession) {
        if (session.takeoverAtMs != null && session.takeoverRecording() != null) takeover = session
        // Withdrawn before its time: Don't record it, or another viewer gives way instead.
        else if (!session.state.terminal && (takeover?.id != session.id || takeover?.takeoverDue(clock.wallMs()) != true)) takeover = null
    }

    /** The recording that has taken this tuner, once its time has come. */
    private fun dueTakeover(): PlayRequest? =
        takeover?.takeIf { it.id == owned?.id && it.takeoverDue(clock.wallMs()) }?.takeoverRecording()

    /** Once the server stops the session its playlist and segments go, and the player fails or ends: that is the takeover. */
    private fun endForRecording(recording: PlayRequest) {
        fail(liveTVError("recording"), "recording", recording)
        releaseJob = scope.launch {
            lane.withLock {
                try { releaseOwned() } catch (e: Exception) { if (e is CancellationException) throw e }
            }
        }
    }

    /** Puts a media token on the stream's requests: the one held while it is fresh, else a new one. */
    private suspend fun ensureToken() {
        val token = tokens.fresh() ?: withContext(io) { gateway.mediaToken() }.also {
            if (!gateway.isCurrent || released) return
            tokens.accept(it)
        }
        media?.updateMediaToken(token)
    }

    private fun startTicker() {
        host.startTicker {
            refresh()
            poll()
        }
    }

    /** Status while tuning, heartbeats every ten seconds and immediately after controls. */
    private fun poll(forceHeartbeat: Boolean = false) {
        val session = state.value.session ?: return
        val currentGeneration = generation
        if (!playing(currentGeneration, session.id) || pollJob?.isActive == true) return
        val now = clock.elapsedMs()
        val beat = forceHeartbeat || paused != lastReportedPause || interaction != sentInteraction ||
            switch.accepted != null || switch.failed != null || now - lastBeatAt >= HEARTBEAT_MS
        val status = session.state == LiveSessionState.Starting && now - lastPollAt >= STATUS_MS
        val renew = loadedSession != null && tokens.due
        if (!beat && !status && !renew) return
        pollJob = scope.launch {
            lane.withLock {
                if (!playing(currentGeneration, session.id)) return@withLock
                try {
                    // Capture after waiting for the lane: pause/input may have changed.
                    val reportedInput = interaction
                    val reportedPause = paused
                    val accepted = switch.accepted
                    val failed = switch.failed
                    // Measured here, on the player's thread: ExoPlayer throws when read from IO.
                    val measurement = if (beat && session.automatic) liveMeasurement(reportedPause) else null
                    val updated = withContext(io) {
                        when {
                            !beat -> gateway.status(session.id)
                            measurement != null -> gateway.heartbeatMeasured(session.id, reportedPause, reportedInput != sentInteraction, measurement)
                            else -> gateway.heartbeat(session.id, reportedPause, reportedInput != sentInteraction)
                        }
                    }
                    lastPollAt = clock.elapsedMs()
                    if (beat) {
                        sentInteraction = reportedInput
                        lastReportedPause = reportedPause
                        lastBeatAt = clock.elapsedMs()
                        switch.reported(accepted, failed)
                    }
                    if (!playing(currentGeneration, session.id)) return@withLock
                    require(updated.id == session.id) { "The server returned a different Live TV session." }
                    accept(updated, currentGeneration)
                    if (renew && playing(currentGeneration, session.id)) ensureToken()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!playing(currentGeneration, session.id)) return@withLock
                    val recording = dueTakeover()
                    if (recording != null) fail(liveTVError("recording"), "recording", recording) else fail(message(e), reasonOf(e))
                    // Stop media immediately so requests cannot indefinitely renew
                    // a tuner while the control connection is broken.
                    runCatching { releaseOwned() }
                }
            }
        }
    }

    /** Automatic's measurement for a heartbeat, as [measure] takes it for files too. Main thread only, as the player is. */
    private fun liveMeasurement(paused: Boolean): LiveMeasurement {
        val p = media
        val m = measure(p, p?.let { (it.bufferedPositionMs - it.positionMs).coerceAtLeast(0) }, switch)
        return LiveMeasurement(
            state = if (paused) LivePlaybackState.Paused else if (p?.phase == PlayerPhase.Ready) LivePlaybackState.Playing else LivePlaybackState.Buffering,
            bufferedMs = m.bufferedMs,
            throughputBps = m.transfer?.bps, throughputAgeMs = m.transfer?.ageMs,
            stalls = m.stalls?.count?.toLong(), switchGeneration = m.switchGeneration, switchFailedGeneration = m.switchFailedGeneration,
        )
    }

    private fun refresh() {
        val session = owned?.takeIf { it.id == state.value.session?.id } ?: return
        if (released || !gateway.isCurrent || session.state.terminal) return
        val now = clock.elapsedMs()
        val p = media
        if (loadedSession != null && p?.phase == PlayerPhase.Ready) firstReady = true
        // The server can have segments ready while this device is still
        // waiting for its first playable media. Bound both halves of startup.
        if (!firstReady && now - tuningAt >= TUNE_TIMEOUT_MS) {
            end("Tuning took too long. Try again, or choose another channel.")
            return
        }
        if (loadedSession != null && p != null) {
            // Audio focus/headset/system pause counts too, without pretending it
            // was viewer input (which must not extend unattended viewing).
            if (paused != !p.playWhenReady) {
                paused = !p.playWhenReady
                pauseAt = if (paused) now else null
            }
        }
        val idle = now - lastInputAt
        if (idle >= STILL_WATCHING_MS + STILL_WATCHING_GRACE_MS) { end(liveTVError("inactive"), "inactive"); return }
        val pausedFor = pauseAt?.let { now - it } ?: 0
        val serverRemaining = if (session.paused) session.pauseRemainingSeconds - ((now - sessionReceivedAt) / 1_000).toInt() else 300
        val remaining = minOf(serverRemaining, (300 - pausedFor / 1_000).toInt()).coerceAtLeast(0)
        if (paused && remaining == 0) { end(liveTVError("pause_expired"), "pause_expired"); return }
        val status = when {
            session.state == LiveSessionState.Starting -> PlayerStatus.Loading
            paused -> PlayerStatus.Paused
            p?.phase == PlayerPhase.Ready -> PlayerStatus.Playing
            else -> PlayerStatus.Buffering
        }
        if (p?.phase == PlayerPhase.Ended && loadedSession != null) {
            dueTakeover()?.let { endForRecording(it); return }
            end(liveTVError("signal_lost"), "signal_lost"); return
        }
        mutableState.update { it.copy(
            status = status,
            session = session.copy(pauseRemainingSeconds = remaining),
            atLiveEdge = !paused && (p?.liveOffsetMs ?: 0) <= LIVE_EDGE_TOLERANCE_MS,
            askStillWatching = idle >= STILL_WATCHING_MS,
        ) }
    }

    private fun ensurePlayer() {
        host.ensure(playerEvents) { LiveSessionPlayer(it, this) }
        host.followVisibility { visible ->
            foreground = visible
            if (!visible && !released) end(null)
        }
    }

    /** The player's callbacks, posted by [host]. */
    private val playerEvents = object : PlayerHost.Events {
        override fun onChanged() = refresh()

        override fun onError(failure: PlayerFailure) {
            val session = state.value.session
            if (session == null || released) return
            if (switch.pending != null) { rejectOutput(session); return }
            if (failure.kind == PlayerFailure.Kind.BehindLiveWindow && behindWindowRecoveries++ < 2) {
                media?.seekToLiveEdge()
                if (!paused) media?.play()
                mutableState.update { it.copy(message = "The buffer expired. Returning to live.") }
            } else {
                dueTakeover()?.let { endForRecording(it); return }
                end(when (failure.kind) {
                    PlayerFailure.Kind.Network -> "The connection to the server was lost. Try again when it is reachable."
                    PlayerFailure.Kind.Http -> if (failure.httpStatus in listOf(401, 403)) liveTVError("access_revoked") else liveTVError("signal_lost")
                    PlayerFailure.Kind.Decoder, PlayerFailure.Kind.Audio -> "This broadcast could not play on this device. Try a lower quality."
                    else -> liveTVError("signal_lost")
                })
            }
        }

        override fun onFirstFrame(load: Int) {
            // The frame names its load, taken when it rendered: the first source can render
            // before a directive's source loads, and must not acknowledge that one.
            if (state.value.session?.automatic != true || released) return
            switch.frame(load) ?: return
            mutableState.update { it.copy(message = null) }
            poll(forceHeartbeat = true)
        }
    }

    private fun detachMedia() {
        loadedSession = null
        loadedUrl = null
        media?.stop()
    }

    /** The server's reason in a refusal (tuners_busy…), which ApiException carries as its message. */
    private fun reasonOf(error: Exception): String? = (error as? ApiException)?.message?.takeIf { REASON.matches(it) }

    private fun message(error: Exception): String =
        (error as? ApiException)?.message?.let(::liveTVError)
            ?: "The connection to the server was lost. Try again when it is reachable."

    private companion object {
        val REASON = Regex("^[a-z_]+$")
        /** While the server tunes, its status is asked at every tick (one call at a time). */
        const val STATUS_MS = PlayerHost.TICK_MS
        const val CAPABILITIES_FRESH_MS = 5 * 60_000L
        const val TUNE_TIMEOUT_MS = 35_000L
        const val HEARTBEAT_MS = 10_000L
        const val LIVE_EDGE_TOLERANCE_MS = 10_000L
        const val STILL_WATCHING_MS = 4 * 60 * 60_000L
        const val STILL_WATCHING_GRACE_MS = 60_000L
    }
}
