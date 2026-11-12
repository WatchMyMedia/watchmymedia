package media.watchmy.client.core.playback

import androidx.media3.common.Player
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
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
    private val mutableState = MutableStateFlow(LivePlayerState(quality = preferences.qualityOn(network).forLiveTV()))
    override val state = mutableState.asStateFlow()
    private val mutablePlayer = MutableStateFlow<Player?>(null)
    override val player = mutablePlayer.asStateFlow()
    private var media: MediaPlayer? = null
    private var mediaSession: AutoCloseable? = null
    private var visibilityHandle: AutoCloseable? = null
    private var generation = 0L
    private var released = false
    private var foreground = true
    private var selected: LiveTarget? = null
    private var quality = preferences.qualityOn(network).forLiveTV()
    private var qualityPicked = false
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
    private var handledOutputGeneration = 0L
    private var pendingOutput: PendingOutput? = null
    private var acceptedOutputGeneration: Long? = null
    private var failedOutputGeneration: Long? = null
    private var tickJob: Job? = null
    private var pollJob: Job? = null
    /** The last stop's DELETE, which [stopAndRelease] waits for. */
    private var releaseJob: Job? = null
    private var lastPollAt = 0L
    private var lastBeatAt = 0L
    private var tokenRefreshAt = Long.MAX_VALUE
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
    private data class PendingOutput(val generation: Long, val previousUrl: String)

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
        if (!qualityPicked) quality = preferences.qualityOn(network).forLiveTV()
        noteUserInput()
        paused = false
        pauseAt = null
        behindWindowRecoveries = 0
        tuningAt = clock.elapsedMs()
        firstReady = false
        takeover = null
        handledOutputGeneration = 0
        pendingOutput = null
        acceptedOutputGeneration = null
        failedOutputGeneration = null
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
                    val capabilities = try { withContext(io) { gateway.liveCapabilities() } }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { LiveCapabilities() }
                    if (!current(currentGeneration)) return@withLock
                    val chosen = (if (qualityPicked) quality else preferences.qualityOn(network)).forLiveTV(capabilities.automatic)
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
        pendingOutput?.let { rejectOutput(state.value.session!!) }
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
        qualityPicked = true
        // Match ordinary playback's Default / On mobile data preference routing.
        val mobile = preferences.device.value.mobileQuality != null && network.metered()
        scope.launch { runCatching { if (mobile) preferences.setMobileQuality(quality) else preferences.setQuality(quality) } }
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
        tickJob?.cancel()
        visibilityHandle?.close()
        visibilityHandle = null
        mediaSession?.close()
        mediaSession = null
        mutablePlayer.value = null
        media?.setListener(null)
        media?.release()
        media = null
        // Already queued network work drains, including a late POST's DELETE.
        job.complete()
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
        pendingOutput = null
        acceptedOutputGeneration = null
        failedOutputGeneration = null
        detachMedia()
        tickJob?.cancel()
        tickJob = null
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
        pendingOutput = null
        detachMedia()
        tickJob?.cancel()
        tickJob = null
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
            renewToken()
            if (!current(currentGeneration)) return
            loadedSession = session.id
            loadedUrl = url
            media?.load(liveSource(session, url))
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
        if (pendingOutput != null || directive.generation <= maxOf(handledOutputGeneration, session.outputGeneration)) return
        handledOutputGeneration = directive.generation
        if (directive.url.isBlank()) {
            failedOutputGeneration = directive.generation
            poll(forceHeartbeat = true)
            return
        }
        val previous = loadedUrl ?: session.url ?: return
        pendingOutput = PendingOutput(directive.generation, previous)
        loadedUrl = directive.url
        try { media?.load(liveSource(session, directive.url)) }
        catch (_: Exception) { rejectOutput(session) }
    }

    private fun rejectOutput(session: LiveSession) {
        val pending = pendingOutput ?: return
        pendingOutput = null
        failedOutputGeneration = pending.generation
        loadedUrl = pending.previousUrl
        mutableState.update { it.copy(message = "Keeping the current Live TV quality after a switch failed.") }
        try { media?.load(liveSource(session, pending.previousUrl)) }
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

    private suspend fun renewToken() {
        val token = withContext(io) { gateway.mediaToken() }
        if (!gateway.isCurrent || released) return
        media?.updateMediaToken(token)
        // Small test tokens and ordinary six-hour tokens both renew before expiry.
        val remaining = (token.expiresAtMs - clock.wallMs()).coerceAtLeast(1_000)
        tokenRefreshAt = clock.elapsedMs() + (remaining - minOf(60_000, remaining / 5)).coerceAtLeast(1_000)
    }

    private fun startTicker() {
        if (tickJob?.isActive == true) return
        tickJob = scope.launch {
            while (true) {
                delay(TICK_MS)
                refresh()
                poll()
            }
        }
    }

    /** Status while tuning, heartbeats every ten seconds and immediately after controls. */
    private fun poll(forceHeartbeat: Boolean = false) {
        val session = state.value.session ?: return
        val currentGeneration = generation
        if (!playing(currentGeneration, session.id) || pollJob?.isActive == true) return
        val now = clock.elapsedMs()
        val beat = forceHeartbeat || paused != lastReportedPause || interaction != sentInteraction ||
            acceptedOutputGeneration != null || failedOutputGeneration != null || now - lastBeatAt >= HEARTBEAT_MS
        val status = session.state == LiveSessionState.Starting && now - lastPollAt >= STATUS_MS
        val renew = loadedSession != null && now >= tokenRefreshAt
        if (!beat && !status && !renew) return
        pollJob = scope.launch {
            lane.withLock {
                if (!playing(currentGeneration, session.id)) return@withLock
                try {
                    // Capture after waiting for the lane: pause/input may have changed.
                    val reportedInput = interaction
                    val reportedPause = paused
                    val accepted = acceptedOutputGeneration
                    val failed = failedOutputGeneration
                    val updated = withContext(io) {
                        if (!beat) gateway.status(session.id)
                        else if (session.automatic) {
                            val p = media
                            val transfer = p?.transfer
                            gateway.heartbeatMeasured(session.id, reportedPause, reportedInput != sentInteraction, LiveMeasurement(
                                state = if (reportedPause) LivePlaybackState.Paused else if (p?.phase == PlayerPhase.Ready) LivePlaybackState.Playing else LivePlaybackState.Buffering,
                                bufferedMs = p?.let { (it.bufferedPositionMs - it.positionMs).coerceAtLeast(0) },
                                throughputBps = transfer?.bps, throughputAgeMs = transfer?.ageMs,
                                stalls = p?.stalls?.count?.toLong(), switchGeneration = accepted, switchFailedGeneration = failed,
                            ))
                        } else gateway.heartbeat(session.id, reportedPause, reportedInput != sentInteraction)
                    }
                    lastPollAt = clock.elapsedMs()
                    if (beat) {
                        sentInteraction = reportedInput
                        lastReportedPause = reportedPause
                        lastBeatAt = clock.elapsedMs()
                        if (accepted == acceptedOutputGeneration) acceptedOutputGeneration = null
                        if (failed == failedOutputGeneration) failedOutputGeneration = null
                    }
                    if (!playing(currentGeneration, session.id)) return@withLock
                    require(updated.id == session.id) { "The server returned a different Live TV session." }
                    accept(updated, currentGeneration)
                    if (renew && playing(currentGeneration, session.id)) renewToken()
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
        if (media != null) return
        val instance = playerFactory()
        media = instance
        instance.setListener(object : MediaPlayer.Listener {
            override fun onChanged() { scope.launch { refresh() } }
            override fun onError(failure: PlayerFailure) {
                scope.launch {
                    if (state.value.session == null || released) return@launch
                    if (pendingOutput != null) { rejectOutput(state.value.session!!); return@launch }
                    if (failure.kind == PlayerFailure.Kind.BehindLiveWindow && behindWindowRecoveries++ < 2) {
                        instance.seekToLiveEdge()
                        if (!paused) instance.play()
                        mutableState.update { it.copy(message = "The buffer expired. Returning to live.") }
                    } else {
                        dueTakeover()?.let { endForRecording(it); return@launch }
                        end(when (failure.kind) {
                            PlayerFailure.Kind.Network -> "The connection to the server was lost. Try again when it is reachable."
                            PlayerFailure.Kind.Http -> if (failure.httpStatus in listOf(401, 403)) liveTVError("access_revoked") else liveTVError("signal_lost")
                            PlayerFailure.Kind.Decoder, PlayerFailure.Kind.Audio -> "This broadcast could not play on this device. Try a lower quality."
                            else -> liveTVError("signal_lost")
                        })
                    }
                }
            }
            override fun onFirstFrame() {
                // Capture at callback time. The first source can render
                // synchronously before a directive is loaded; its queued
                // handler must not acknowledge the next source's generation.
                val renderedGeneration = pendingOutput?.generation ?: return
                scope.launch {
                    val pending = pendingOutput ?: return@launch
                    if (pending.generation != renderedGeneration || state.value.session?.automatic != true || released) return@launch
                    pendingOutput = null
                    acceptedOutputGeneration = pending.generation
                    mutableState.update { it.copy(message = null) }
                    poll(forceHeartbeat = true)
                }
            }
        })
        instance.media3?.let {
            val wrapped = LiveSessionPlayer(it, this)
            mutablePlayer.value = wrapped
            mediaSession = sessions.open(wrapped)
        }
        visibilityHandle = visibility.observe { visible ->
            foreground = visible
            if (!visible && !released) end(null)
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
        const val TICK_MS = 250L
        const val STATUS_MS = 1_000L
        const val TUNE_TIMEOUT_MS = 35_000L
        const val HEARTBEAT_MS = 10_000L
        const val LIVE_EDGE_TOLERANCE_MS = 10_000L
        const val STILL_WATCHING_MS = 4 * 60 * 60_000L
        const val STILL_WATCHING_GRACE_MS = 60_000L
    }
}
