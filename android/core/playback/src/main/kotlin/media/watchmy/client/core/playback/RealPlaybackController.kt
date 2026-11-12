package media.watchmy.client.core.playback

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Player
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import media.watchmy.client.core.playback.player.MediaPlayer
import media.watchmy.client.core.playback.player.PlayerFailure
import media.watchmy.client.core.playback.player.PlayerPhase
import media.watchmy.client.core.playback.player.PlayerSource
import media.watchmy.client.core.playback.player.SideloadedSubtitle
import media.watchmy.client.core.playback.player.TextSelection
import media.watchmy.client.core.playback.profile.ProfileProvider
import media.watchmy.client.core.playback.profile.decodedAudioCodec
import media.watchmy.client.core.playback.session.MediaSessionHost
import media.watchmy.client.core.playback.session.SessionControls
import media.watchmy.client.core.playback.session.SessionPlayer
import media.watchmy.client.core.playback.watchnext.WatchNext
import media.watchmy.client.core.playback.watchnext.WatchNextEntry
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.ClientInfo
import media.watchmy.client.model.DecisionRequest
import media.watchmy.client.model.DecisionSubtitle
import media.watchmy.client.model.MediaToken
import media.watchmy.client.model.OnlineSubtitles
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.PlayState
import media.watchmy.client.model.PlaybackController
import media.watchmy.client.model.PlaybackDecision
import media.watchmy.client.model.PlaybackGateway
import media.watchmy.client.model.PlaybackProfile
import media.watchmy.client.model.PlaybackProgress
import media.watchmy.client.model.PlayerEvent
import media.watchmy.client.model.PlayerStatus
import media.watchmy.client.model.PlayerUiState
import media.watchmy.client.model.PreferencesRepository
import media.watchmy.client.model.ProgressReply
import media.watchmy.client.model.ProgressReport
import media.watchmy.client.model.QualityChange
import media.watchmy.client.model.Segment
import media.watchmy.client.model.SkipPrompt
import media.watchmy.client.model.StreamProtocol
import media.watchmy.client.model.SubtitleRequestMode
import media.watchmy.client.model.SubtitleMode
import media.watchmy.client.model.requiresBurnIn
import media.watchmy.client.model.StreamQuality
import media.watchmy.client.model.SubtitleOption
import media.watchmy.client.model.SubtitleSource
import media.watchmy.client.model.UpNext
import media.watchmy.client.model.UpNextPrompt
import media.watchmy.client.model.episodeCode
import kotlin.math.abs

/**
 * The player behaviour of PLAN-CLIENTS.md §2.3 around a [MediaPlayer]:
 * asks the server how to play, maps player time to file time, reports
 * heartbeats, runs Skip, Next Episode and Still watching, and keeps Watch
 * Next and the media session current. [lookups] names a file the request
 * left untitled and fills in the next episode's runtime. [network] says
 * when the On mobile data quality applies.
 *
 * Everything runs on [dispatcher] (the main thread in the app; ExoPlayer
 * wants it), so there is no locking. Server calls run on [io]. Heartbeats
 * and session stops go out in order through one queue, which [release]
 * drains, so the last "stopped" report is sent even as the screen goes.
 *
 * [PlaybackEngine] builds these with the real pieces; tests pass fakes.
 */
class RealPlaybackController(
    gateway: PlaybackGateway,
    private val preferences: PreferencesRepository,
    private val client: ClientInfo,
    private val profileProvider: ProfileProvider,
    private val playerFactory: () -> MediaPlayer,
    /** One for the app: the counters live for the app session. */
    private val stillWatching: StillWatching,
    private val watchNext: WatchNext = WatchNext.None,
    private val sessions: MediaSessionHost = MediaSessionHost.None,
    private val visibility: AppVisibility = AppVisibility.AlwaysVisible,
    private val lookups: PlaybackLookups = PlaybackLookups.None,
    private val network: MeteredNetwork = MeteredNetwork.Never,
    private val clock: Clock = DeviceClock,
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : PlaybackController {
    private val gateway = gateway.bind()

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + dispatcher)

    private val _state = MutableStateFlow(PlayerUiState())
    override val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val _progress = MutableStateFlow(PlaybackProgress())
    override val progress: StateFlow<PlaybackProgress> = _progress.asStateFlow()

    private val _events = MutableSharedFlow<PlayerEvent>(extraBufferCapacity = 8)
    override val events: Flow<PlayerEvent> = _events.asSharedFlow()

    private var media: MediaPlayer? = null
    private var sessionHandle: AutoCloseable? = null
    private var visibilityHandle: AutoCloseable? = null

    private val _player = MutableStateFlow<Player?>(null)
    override val player: StateFlow<Player?> = _player.asStateFlow()

    private var viewing: Viewing? = null
    private var generation = 0
    /** What decisions ask for: the viewer's pick in this player, else the default for the network in use, read at each decision. */
    private var quality: StreamQuality = preferences.qualityOn(network)
    private var qualityPicked = false
    private var token: MediaToken? = null
    private var tokenRefreshAt = 0L
    private var backgrounded = false
    private var preferencesReady = false
    private var preferencesLoaded = false
    private var released = false

    private var tickJob: Job? = null
    private var loadJob: Job? = null
    private var lastTickAt = 0L

    private val outbox = Channel<Outgoing>(Channel.UNLIMITED)

    private sealed interface Outgoing {
        data class Report(val report: ProgressReport, val generation: Int) : Outgoing

        data class EndSession(val sessionId: String) : Outgoing
    }

    /** One file being watched: from [start] (or the next episode starting) until it stops. */
    private class Viewing(var request: PlayRequest, val generation: Int) {
        data class PendingSwitch(val previous: PlaybackDecision, val atMs: Long, val generation: Long)

        var decision: PlaybackDecision? = null
        var pendingSwitch: PendingSwitch? = null
        var awaitingFrameGeneration: Long? = null
        var acceptedGeneration: Long? = null
        var failedGeneration: Long? = null
        var handledGeneration = 0L

        /** The decision's subtitles as the pickers list them, mapped once per decision. */
        var subtitles: List<SubtitleOption> = emptyList()

        /** The decision's next episode, with what [lookups] added. */
        var next: UpNext? = null
        var streamClock = StreamClock.Zero
        var playId: String? = null
        var selectedAudio: Int? = null

        /** The viewer picked the audio track (in this player, or one handed over); an offline play keeps it. */
        var audioChosen = false

        /**
         * The player couldn't start a direct play on the decided track: this
         * viewing's decisions leave out selectsAudioTrack, so the server
         * remuxes a track other than the file's default (PLAN-LANGUAGES.md §5.2).
         */
        var playerSelectsAudio = true

        /** The profile the last decision was asked with, which an offline play's audio choice reads. */
        var profile: PlaybackProfile? = null
        var selectedSubtitle: String? = null
        var subtitleManuallyChosen = false
        var subtitleRevision = 0
        var attemptedAutomaticSubtitle = false
        var automaticSubtitleInFlight = false

        /** Subtitles found online, once the search has answered. */
        var online: OnlineSubtitles? = null
        var searchedOnline = false

        /** A decision is being fetched. */
        var loading = false

        /** While loading: the file position it will start at (the scrubber shows it meanwhile). */
        var pendingPositionMs: Long? = null
        var everReady = false
        val seekedInto = mutableSetOf<Segment>()
        var creditsDismissed = false
        var askStill = false
        var countdownMs: Long? = null
        var fillSegment: Segment? = null
        var fillMs = 0L

        /** Reached watchedAtMs, or the server said so. */
        var watched = false

        /** Moved on (next episode, or a skip to the end): reports give the end as the position. */
        var finished = false
        var stopped = false
        var exited = false
        var adminStopped = false
        var stopMessage: String? = null
        var error: String? = null
        var lastReported: PlayState? = null
        var lastBeatAt = 0L
        var recoveries = 0
        var recoveredAt = 0L

        /** When the decision playing (or being fetched) was asked for. */
        var askedAt = 0L

        /** From asking for this decision to its first frame, once it has one; reported once. */
        var startupMs: Long? = null
        var startupSent = false

        /** Automatic: the server's note for the step change it asked for, until the new decision comes. */
        var stepMessage: String? = null

        /** A note shown until [noteUntil]: a step change, or a connection too slow even at the lowest step. */
        var note: String? = null
        var noteUntil = 0L
        var floorNoted = false
    }

    init {
        scope.launch {
            for (message in outbox) deliver(message)
        }
    }

    // --- commands ---

    override fun start(request: PlayRequest) {
        if (released) return
        endViewing(PlayState.Stopped)
        stillWatching.notePlay()
        stillWatching.noteInput()
        begin(request)
    }

    override fun play() {
        stillWatching.noteInput()
        // Play from the notification or a headset while the app is away: reports start
        // again, and the server carries on the play that "closed" ended under a new ID.
        backgrounded = false
        val v = active() ?: return
        if (v.error != null && !v.loading) {
            // Play after an error tries again from where it stopped.
            v.error = null
            reload(v)
            return
        }
        media?.play()
    }

    override fun pause() {
        stillWatching.noteInput()
        active() ?: return
        media?.pause()
    }

    override fun togglePlayPause() {
        val p = media
        if (p != null && p.playWhenReady && p.phase != PlayerPhase.Ended) pause() else play()
    }

    override fun seekTo(positionMs: Long) {
        stillWatching.noteInput()
        val v = active() ?: return
        val d = v.decision ?: return
        val target = positionMs.coerceIn(0, d.growing?.seekLimitAt(clock.elapsedMs()) ?: duration(d))
        // Back before the credits: offer the Next Episode card again.
        if (d.upNextMs > 0 && target < d.upNextMs) v.creditsDismissed = false
        d.segments.filterTo(v.seekedInto) { target >= it.startMs && target < it.endOr(duration(d)) }
        seekWithin(v, target)
    }

    override fun seekBy(deltaMs: Long) = seekTo(_progress.value.positionMs + deltaMs)

    override fun skip() {
        stillWatching.noteInput()
        val v = active() ?: return
        val segment = _state.value.skip?.segment ?: return
        skipSegment(v, segment)
    }

    override fun playNext() {
        stillWatching.noteInput()
        val v = active() ?: return
        // The controls' button before the card leaves this episode where it was; from the card it is done.
        goNext(v, finished = v.watched || _state.value.upNext != null || media?.phase == PlayerPhase.Ended)
    }

    override fun watchCredits() {
        stillWatching.noteInput()
        val v = active() ?: return
        v.creditsDismissed = true
        v.countdownMs = null
        refresh(0)
    }

    override fun answerStillWatching(keepWatching: Boolean) {
        val v = active() ?: return
        if (!v.askStill) return
        v.askStill = false
        if (keepWatching) {
            stillWatching.noteInput()
            goNext(v, finished = true)
        } else {
            exit(v)
        }
    }

    override fun selectAudio(index: Int) {
        stillWatching.noteInput()
        val v = active() ?: return
        val d = v.decision ?: return
        if (index == v.selectedAudio) return
        val ordinal = d.audio.indexOfFirst { it.index == index }
        if (ordinal < 0) return
        v.selectedAudio = index
        v.audioChosen = true
        if (!v.subtitleManuallyChosen && d.isOffline && d.burnInSubtitleId == null) {
            val prefs = preferences.playback.value
            v.selectedSubtitle = preferredSubtitleId(d.subtitles.map { it.option }, d.audio[ordinal].language, prefs.audio.languages, prefs.subtitles)
            media?.selectText(textSelection(d, v.selectedSubtitle))
        }
        // Direct play switches the file's own track when this device plays it; otherwise the server sends it.
        val refreshDefault = d.subtitlePreferencesSupported && !v.subtitleManuallyChosen && !d.isOffline
        if (d.protocol == StreamProtocol.File && !refreshDefault && !v.loading && media?.selectAudio(ordinal) == true) {
            refresh(0)
            return
        }
        reload(v)
    }

    override fun selectSubtitle(id: String?) {
        stillWatching.noteInput()
        val v = active() ?: return
        val d = v.decision ?: return
        val track = id?.let { s -> d.subtitles.firstOrNull { it.option.id == s } }
        if (id != null && track == null) return
        manualSubtitleChoice(v)
        if (id == v.selectedSubtitle) return
        v.selectedSubtitle = id
        val burnIn = !d.isOffline && track != null && needsBurnIn(track)
        when {
            // Image subtitles are burned in by the server, which restarts the stream here.
            burnIn -> reload(v, subtitleId = id, burnIn = true)
            // Leaving a burned-in subtitle needs a stream without it.
            d.burnInSubtitleId != null -> reload(v, subtitleId = id, burnIn = false)
            // Text subtitles are already loaded (renditions or side-loaded files): switch here.
            else -> {
                if (!v.loading) media?.selectText(textSelection(d, id))
                refresh(0)
            }
        }
    }

    override fun downloadSubtitle(resultId: String) {
        stillWatching.noteInput()
        val v = active() ?: return
        val d = v.decision ?: return
        val online = v.online ?: return
        val result = online.results.firstOrNull { it.id == resultId } ?: return
        // Downloaded for this file before: it is one of the decision's tracks already.
        result.trackId?.takeIf { id -> d.subtitles.any { it.option.id == id } }?.let { id ->
            selectSubtitle(id)
            return
        }
        if (online.downloading != null) return
        manualSubtitleChoice(v)
        val subtitleRevision = v.subtitleRevision
        v.online = online.copy(downloading = resultId, error = null)
        refresh(0)
        scope.launch {
            try {
                val trackId = withContext(io) { gateway.downloadSubtitle(v.request.fileId, resultId) }
                if (viewing !== v || v.stopped) return@launch
                v.online = v.online?.let { o ->
                    o.copy(
                        downloading = null,
                        results = o.results.map { if (it.id == resultId) it.copy(trackId = trackId) else it },
                        remaining = o.remaining?.let { (it - 1).coerceAtLeast(0) },
                    )
                }
                // The new track comes in the next decision, as a side-loaded file or a rendition.
                if (v.subtitleRevision != subtitleRevision) return@launch
                v.selectedSubtitle = trackId
                reload(v, subtitleId = trackId, burnIn = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Subtitle download failed", e)
                if (viewing !== v) return@launch
                val quotaSpent = (e as? ApiException)?.status == 429
                v.online = v.online?.copy(
                    downloading = null,
                    error = e.message?.takeIf { it.isNotBlank() } ?: "Could not download the subtitle.",
                    remaining = if (quotaSpent) 0 else v.online?.remaining,
                )
                refresh(0)
            }
        }
    }

    /**
     * A pick in the player: Automatic, or a fixed step that ends Automatic
     * for this play. It holds for this player whatever the network does,
     * and becomes the device's default: the On mobile data quality when
     * that is the one in force, else the Default quality.
     */
    override fun selectQuality(quality: StreamQuality) {
        stillWatching.noteInput()
        if (active()?.decision?.isOffline == true) return
        // A sender's Automatic (Cast Connect) where the server can't do it plays as Original.
        val q = if (quality == StreamQuality.Auto && !preferences.autoQuality.value) StreamQuality.Original else quality
        if (q == this.quality) return
        this.quality = q
        qualityPicked = true
        val mobile = preferences.device.value.mobileQuality != null && network.metered()
        scope.launch { attempt("save quality") { if (mobile) preferences.setMobileQuality(q) else preferences.setQuality(q) } }
        _state.update { it.copy(quality = q) }
        val v = active() ?: return
        if (v.decision != null) reload(v)
    }

    override fun noteUserInput() {
        stillWatching.noteInput()
    }

    override fun stop() {
        endViewing(PlayState.Stopped)
    }

    override fun release() {
        if (released) return
        endViewing(PlayState.Stopped)
        released = true
        viewing = null
        tickJob?.cancel()
        loadJob?.cancel()
        visibilityHandle?.close()
        visibilityHandle = null
        sessionHandle?.close()
        sessionHandle = null
        _player.value = null
        media?.setListener(null)
        media?.release()
        media = null
        // The queue drains (the last report goes out), then the scope ends.
        outbox.close()
        job.complete()
    }

    // --- viewing lifecycle ---

    private fun active(): Viewing? = viewing?.takeIf { !it.stopped && !it.adminStopped }

    private fun begin(request: PlayRequest) {
        val v = Viewing(request, ++generation)
        // A play handed over from a TV keeps its tracks.
        v.selectedAudio = request.audioIndex
        v.audioChosen = request.audioIndex != null
        v.selectedSubtitle = request.subtitleId
        v.subtitleManuallyChosen = request.subtitleId != null || request.subtitleMode == SubtitleRequestMode.Off
        viewing = v
        ensurePlayer()
        if (visibilityHandle == null) visibilityHandle = visibility.observe(::onVisibility)
        if (!preferencesLoaded) {
            preferencesLoaded = true
            scope.launch { attempt("load preferences") { preferences.refresh() }; preferencesReady = true }
        }
        // Every play gets a fresh media token (PLAN-CLIENTS.md §2.2).
        token = null
        if (!qualityPicked) quality = preferences.qualityOn(network)
        _state.value = PlayerUiState(
            status = PlayerStatus.Loading,
            title = request.title,
            subtitle = request.subtitle,
            backdropPath = request.backdropPath,
            canCast = request.recordingId == null,
            quality = quality,
            qualities = StreamQuality.offered(preferences.autoQuality.value),
        )
        _progress.value = PlaybackProgress(positionMs = request.startMs ?: 0)
        startTicker()
        v.loading = true
        loadJob?.cancel()
        loadJob = scope.launch {
            try {
                // A bare play link: name the file while the server decides.
                val naming = if (request.title.isBlank()) async { attempt("name the file") { withContext(io) { lookups.name(request) } } } else null
                var d = decide(v, startMs = request.startMs, playId = null)
                var start = request.startMs ?: 0
                // No explicit start: pick up where the viewer left off. A VOD
                // playlist (or the file) just starts there; an event playlist
                // is restarted there, as the web does.
                if (request.startMs == null && d.resumeMs > RESUME_MIN_MS) {
                    start = d.resumeMs
                    if (StreamClock.of(d).event) {
                        val first = d
                        d = decide(v, startMs = start, playId = d.playId)
                        endReplacedSession(first, d)
                    }
                }
                naming?.await()?.let { rename(v, it) }
                apply(v, d, start)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(v, e)
            }
        }
    }

    private fun rename(v: Viewing, named: PlayRequest) {
        if (viewing !== v) return
        v.request = v.request.copy(title = named.title, subtitle = named.subtitle, backdropPath = v.request.backdropPath ?: named.backdropPath)
        _state.update { it.copy(title = v.request.title, subtitle = v.request.subtitle, backdropPath = v.request.backdropPath) }
    }

    /**
     * Re-requests the decision for the same play: track and quality changes,
     * Automatic's step changes, event seeks, a new token, recoveries. The
     * stream playing goes on (playing or buffering) until the new one loads,
     * which then starts where it has got to; [seekMs] is a seek's target
     * instead, which the scrubber shows meanwhile.
     */
    private fun reload(
        v: Viewing,
        seekMs: Long? = null,
        subtitleId: String? = v.selectedSubtitle.takeIf { v.subtitleManuallyChosen },
        burnIn: Boolean = burnsIn(v, subtitleId),
        newToken: Boolean = false,
        qualitySwitch: QualityChange? = null,
    ) {
        val atMs = seekMs ?: position(v)
        v.loading = true
        if (qualitySwitch == null) {
            v.pendingSwitch?.previous?.sessionId?.let { outbox.trySend(Outgoing.EndSession(it)) }
            v.pendingSwitch = null
            v.awaitingFrameGeneration = null
            v.acceptedGeneration = null
        }
        if (seekMs != null) v.pendingPositionMs = seekMs
        if (newToken) token = null
        loadJob?.cancel()
        loadJob = scope.launch {
            try {
                val d = decide(v, startMs = atMs, playId = v.playId, subtitleId = subtitleId, burnIn = burnIn,
                    switchGeneration = qualitySwitch?.generation)
                // Playing or paused as the viewer has it now: the pickers pause while open
                // and resume on closing, which is usually while the decision is out.
                apply(v, d, atMs, playWhenReady = media?.playWhenReady ?: true, qualitySwitch = qualitySwitch)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val prior = v.decision
                val switchGeneration = qualitySwitch?.generation
                val pending = v.pendingSwitch
                if (switchGeneration != null && pending?.generation == switchGeneration && viewing === v) {
                    restorePrevious(v, pending)
                } else if (switchGeneration != null && prior != null && viewing === v) {
                    v.loading = false
                    v.failedGeneration = switchGeneration
                    v.stepMessage = null
                    showNote(v, "Still playing at ${stepName(prior.autoStep ?: StreamQuality.Original)}. Quality will retry later.")
                    refresh(0)
                } else fail(v, e)
            }
        }
        refresh(0)
    }

    private suspend fun decide(
        v: Viewing,
        startMs: Long?,
        playId: String?,
        subtitleId: String? = v.selectedSubtitle.takeIf { v.subtitleManuallyChosen },
        burnIn: Boolean = burnsIn(v, subtitleId),
        switchGeneration: Long? = null,
    ): PlaybackDecision {
        // Onto mobile data or off it since the last decision: this one takes that network's default.
        if (!qualityPicked) quality = preferences.qualityOn(network)
        // Automatic has no caps: the device's own profile, and the server picks the step.
        val device = withContext(io) { profileProvider.current() }.capped(quality)
        val profile = if (v.playerSelectsAudio) device else device.copy(selectsAudioTrack = null)
        v.profile = profile
        val request = DecisionRequest(
            fileId = v.request.fileId,
            profile = profile,
            client = client,
            startMs = startMs,
            audioIndex = v.selectedAudio,
            subtitleId = subtitleId,
            burnIn = burnIn,
            quality = quality,
            playId = playId,
            vod = v.request.recordingId == null,
            recordingId = v.request.recordingId,
            subtitleMode = if (v.subtitleManuallyChosen && subtitleId == null) SubtitleRequestMode.Off else SubtitleRequestMode.Auto,
            autoRecovery = quality == StreamQuality.Auto && v.request.recordingId == null,
            switchGeneration = switchGeneration,
        )
        v.askedAt = clock.elapsedMs()
        // The token after the decision: a downloaded item plays from the device and needs none,
        // which the gateway knows only once it has decided (PLAN-DOWNLOADS.md §10.4).
        var decision = withContext(io) { gateway.decide(request) }
        // An automatic ASS default needs the same faithful server renderer as a manual choice.
        val selected = subtitleId ?: decision.subtitleId
        val track = decision.subtitles.firstOrNull { it.option.id == selected }
        if (!decision.isOffline && !burnIn && track != null && needsBurnIn(track) && decision.burnInSubtitleId != selected) {
            val first = decision
            try {
                decision = withContext(io) { gateway.decide(request.copy(playId = first.playId, subtitleId = selected, burnIn = true)) }
            } catch (e: Exception) {
                first.sessionId?.takeIf { it != v.decision?.sessionId }?.let { outbox.trySend(Outgoing.EndSession(it)) }
                throw e
            }
            endReplacedSession(first, decision)
        }
        ensureToken()
        return decision
    }

    private suspend fun ensureToken() {
        if (token != null && clock.elapsedMs() < tokenRefreshAt) return
        val fresh = withContext(io) { gateway.mediaToken() }
        token = fresh
        // Timed on this device's clock, with a floor, so a clock that
        // disagrees with the server's cannot cause a reload loop.
        val lifetime = (fresh.expiresAtMs - clock.wallMs()).coerceAtLeast(TOKEN_MIN_LIFETIME_MS)
        tokenRefreshAt = clock.elapsedMs() + lifetime - TOKEN_MARGIN_MS
    }

    private fun apply(v: Viewing, decided: PlaybackDecision, requestedStartMs: Long, playWhenReady: Boolean = true,
                      qualitySwitch: QualityChange? = null) {
        if (viewing !== v || v.stopped || v.adminStopped) return
        // A growing recording counts on from what the server had recorded, by this device's monotonic clock.
        val d = decided.growing?.let { decided.copy(growing = it.anchored(decided.durationMs, clock.elapsedMs())) } ?: decided
        val previous = v.decision
        val switchGeneration = qualitySwitch?.generation
        val negotiated = switchGeneration != null && previous != null
        val seekedWhileLoading = v.pendingPositionMs.let { it != null && it != requestedStartMs }
        // A reload's old stream played on meanwhile: the new one starts where it got to.
        val start = (v.pendingPositionMs ?: if (previous != null) position(v) else requestedStartMs).coerceIn(0, duration(d))
        val streamClock = StreamClock.of(d)
        v.decision = d
        v.subtitles = d.subtitles.map { it.option }
        v.streamClock = streamClock
        v.playId = d.playId
        if (d.next?.fileId != v.next?.fileId) {
            v.next = d.next
            d.next?.let { describeNext(v, it) }
        }
        val prefs = preferences.playback.value
        v.selectedAudio = when {
            !d.isOffline -> d.selectedAudio ?: v.selectedAudio
            // From the device: a pick stays; otherwise an original file, which has every track,
            // starts in the person's audio languages, as the server would choose (PLAN-LANGUAGES.md §5.5).
            v.audioChosen && d.audio.any { it.index == v.selectedAudio } -> v.selectedAudio
            d.protocol == StreamProtocol.File -> preferredAudioIndex(d.audio, v.profile, prefs.audio.languages) ?: d.selectedAudio
            else -> d.selectedAudio
        }
        if (!v.subtitleManuallyChosen) {
            v.selectedSubtitle = if (d.isOffline) preferredSubtitleId(d.subtitles.map { it.option }, audioLanguage(d, v), prefs.audio.languages, prefs.subtitles)
            else d.subtitleId
        }
        if (d.burnInSubtitleId != null) v.selectedSubtitle = d.burnInSubtitleId
        if (previous != null && !negotiated) endReplacedSession(previous, d)
        if (switchGeneration != null && previous != null) {
            v.pendingSwitch = Viewing.PendingSwitch(previous, start, switchGeneration)
        }
        v.awaitingFrameGeneration = qualitySwitch?.generation
        // A seek while an event session was being fetched went somewhere else: fetch one there.
        if (streamClock.event && seekedWhileLoading && abs(start - d.startMs) > EVENT_START_TOLERANCE_MS) {
            reload(v, start)
            return
        }
        v.loading = false
        v.pendingPositionMs = null
        v.error = null
        v.startupMs = null
        v.startupSent = false
        if (qualitySwitch?.generation == null) noteStepChange(v, previous, d)
        val token = token
        media?.load(
            PlayerSource(
                url = token?.let { withToken(d.url, it) } ?: d.url,
                hls = d.protocol == StreamProtocol.Hls,
                live = streamClock.event,
                startPositionMs = streamClock.playerPosition(start),
                playWhenReady = playWhenReady,
                // Direct play reads the file's own text subtitles from the file: the server's copy of
                // one reads the whole video first, which a long film on a slow disk can't do in time.
                subtitles = if (d.hasRenditions()) emptyList() else d.subtitles.filterNot { needsBurnIn(it) || readInFile(d, it) }.mapNotNull { sideload(it, token) },
                embeddedSubtitles = d.embeddedSubtitles(),
                text = textSelection(d, v.selectedSubtitle),
                audioOrdinal = if (d.protocol == StreamProtocol.File) d.audio.indexOfFirst { it.index == v.selectedAudio }.takeIf { it >= 0 } else null,
                audioTracks = d.audio.size.takeIf { d.protocol == StreamProtocol.File },
                title = v.request.title,
                subtitle = v.request.subtitle,
                artworkUrl = lookups.artworkUrl(v.request.backdropPath),
            ),
        )
        refresh(0)
    }

    /**
     * Looks for subtitles online once the video has started, so starting it
     * never waits on the provider; the server keeps the answer for a day.
     */
    private fun searchOnline(v: Viewing) {
        v.searchedOnline = true
        scope.launch {
            val found = attempt("search subtitles online") { withContext(io) { gateway.searchSubtitles(v.request.fileId) } } ?: return@launch
            if (viewing !== v || v.stopped) return@launch
            v.online = found
            refresh(0)
        }
    }

    /** Fills in the next episode's runtime and season finale flag when they come. */
    private fun describeNext(v: Viewing, next: UpNext) {
        scope.launch {
            val described = attempt("look up the next episode") { withContext(io) { lookups.describe(next) } } ?: return@launch
            if (viewing !== v || v.next?.fileId != next.fileId) return@launch
            v.next = described
            refresh(0)
        }
    }

    private fun fail(v: Viewing, e: Exception) {
        if (viewing !== v) return
        Log.w(TAG, "Playback request failed", e)
        v.loading = false
        v.error = e.message?.takeIf { it.isNotBlank() } ?: "Could not start playback."
        refresh(0)
    }

    /** Ends the viewing: reports [state], keeps Watch Next current and stops the server's session. */
    private fun endViewing(state: PlayState) {
        val v = viewing ?: return
        if (v.stopped) return
        loadJob?.cancel()
        v.loading = false
        val d = v.decision
        if (d != null) {
            val pos = position(v)
            report(v, state, pos, 0)
            leftPartway(v, pos)
            d.sessionId?.let { outbox.trySend(Outgoing.EndSession(it)) }
            v.pendingSwitch?.previous?.sessionId?.takeIf { it != d.sessionId }?.let { outbox.trySend(Outgoing.EndSession(it)) }
        }
        v.stopped = true
        tickJob?.cancel()
        media?.stop()
    }

    /** Leaves the player. */
    private fun exit(v: Viewing) {
        if (v.exited) return
        v.exited = true
        endViewing(PlayState.Stopped)
        _events.tryEmit(PlayerEvent.Exit)
    }

    /** Starts the next episode in place; [finished] reports this one as watched to its end. */
    private fun goNext(v: Viewing, finished: Boolean) {
        v.decision ?: return
        val next = v.next ?: return
        if (finished) {
            v.finished = true
            markWatched(v)
        }
        endViewing(PlayState.Stopped)
        _events.tryEmit(PlayerEvent.StartedNext(next.fileId))
        begin(PlayRequest(next.fileId, null, next.showTitle, "${episodeCode(next.season, next.episode)} · ${next.title}", v.request.backdropPath))
    }

    private fun autoAdvance(v: Viewing) {
        if (stillWatching.shouldAsk()) {
            v.askStill = true
            v.countdownMs = null
            refresh(0)
            return
        }
        stillWatching.noteAutoAdvance()
        goNext(v, finished = true)
    }

    private fun skipSegment(v: Viewing, segment: Segment) {
        val d = v.decision ?: return
        val end = segment.endOr(duration(d))
        if (end < duration(d) - END_SLACK_MS) {
            seekWithin(v, end)
            return
        }
        // Skipping to the end finishes the item: watched, and leave.
        v.finished = true
        markWatched(v)
        exit(v)
    }

    private fun seekWithin(v: Viewing, target: Long) {
        val p = media ?: return
        when {
            v.loading -> v.pendingPositionMs = target
            v.streamClock.canSeekLocally(target, p.bufferedPositionMs) -> p.seekTo(v.streamClock.playerPosition(target))
            // Outside an event session's buffer: a new session there, same play.
            else -> {
                reload(v, target)
                return
            }
        }
        refresh(0)
    }

    private fun adminStop(v: Viewing, message: String) {
        if (v.adminStopped || v.stopped) return
        val pos = position(v)
        v.adminStopped = true
        v.stopMessage = message
        loadJob?.cancel()
        v.loading = false
        media?.stop()
        tickJob?.cancel()
        leftPartway(v, pos)
        _state.update { it.copy(status = PlayerStatus.Paused, stopMessage = message, skip = null, upNext = null, askStillWatching = false) }
    }

    private fun onVisibility(visible: Boolean) {
        if (visible) {
            backgrounded = false
            refresh(0)
            return
        }
        if (backgrounded) return
        backgrounded = true
        val v = active() ?: return
        if (v.decision == null) return
        media?.pause()
        val pos = position(v)
        report(v, PlayState.Closed, pos, 0)
        leftPartway(v, pos)
    }

    /**
     * The player couldn't start a direct play on the decided audio track and
     * plays the file's default. A stream asks again, naming the track and
     * without selectsAudioTrack, so the server remuxes it with that track
     * alone; once a viewing. An offline play shows the default as playing.
     */
    private fun audioUnavailable() {
        val v = active() ?: return
        val d = v.decision ?: return
        if (v.loading || d.protocol != StreamProtocol.File) return
        val default = d.audio.firstOrNull { it.isDefault }?.index ?: d.audio.firstOrNull()?.index
        if (v.selectedAudio == null || v.selectedAudio == default) return
        if (d.isOffline || !v.playerSelectsAudio) {
            Log.w(TAG, "The player can't select audio track ${v.selectedAudio}; playing the default")
            v.selectedAudio = default
            refresh(0)
            return
        }
        Log.w(TAG, "The player can't select audio track ${v.selectedAudio}; asking the server for it")
        v.playerSelectsAudio = false
        reload(v)
    }

    private fun onPlayerError(failure: PlayerFailure) {
        val v = active() ?: return
        if (v.decision == null || v.loading) return
        if (failure.kind == PlayerFailure.Kind.Subtitle) {
            val d = v.decision ?: return
            val id = failure.subtitleId ?: return
            if (d.subtitles.none { it.option.id == id }) return
            if (v.selectedSubtitle == id) {
                manualSubtitleChoice(v)
                v.selectedSubtitle = null
            }
            val retained = d.copy(subtitles = d.subtitles.filterNot { it.option.id == id }, subtitleId = d.subtitleId?.takeUnless { it == id })
            v.decision = retained
            v.subtitles = retained.subtitles.map { it.option }
            if (v.selectedSubtitle == null) media?.selectText(TextSelection.Off)
            showNote(v, "Couldn’t load a subtitle. The video will keep playing.")
            refresh(0)
            return
        }
        Log.w(TAG, "Player error: ${failure.detail}")
        v.pendingSwitch?.let { pending ->
            restorePrevious(v, pending)
            return
        }
        v.awaitingFrameGeneration?.let {
            v.failedGeneration = it
            v.awaitingFrameGeneration = null
            v.stepMessage = null
        }
        // A decoder the device lists but that fails (a Pixel's Dolby E-AC-3): ask again
        // without it, so the server converts the audio. Once per codec, then as any error.
        val codec = failure.audioMimeType?.takeIf { failure.kind == PlayerFailure.Kind.Decoder }?.let(::decodedAudioCodec)
        if (codec != null && profileProvider.decoderFailed(codec)) {
            reload(v)
            return
        }
        if (failure.recoverable() && v.recoveries < MAX_RECOVERIES) {
            v.recoveries++
            v.recoveredAt = clock.elapsedMs()
            reload(v, newToken = failure.httpStatus == 401 || failure.httpStatus == 403)
            return
        }
        v.error = errorMessage(failure)
        refresh(0)
    }

    private fun restorePrevious(v: Viewing, pending: Viewing.PendingSwitch) {
        v.pendingSwitch = null
        v.awaitingFrameGeneration = null
        v.acceptedGeneration = null
        v.failedGeneration = pending.generation
        v.pendingPositionMs = pending.atMs
        v.stepMessage = "Still playing at ${stepName(pending.previous.autoStep ?: StreamQuality.Original)}. Quality will retry later."
        apply(v, pending.previous, pending.atMs, playWhenReady = true)
        v.stepMessage?.let { showNote(v, it) }
        v.stepMessage = null
        refresh(0)
    }

    // --- the tick ---

    private fun startTicker() {
        if (tickJob?.isActive == true) return
        lastTickAt = clock.elapsedMs()
        tickJob = scope.launch {
            while (true) {
                delay(TICK_MS)
                val now = clock.elapsedMs()
                val dt = now - lastTickAt
                lastTickAt = now
                refresh(dt)
            }
        }
    }

    /**
     * Brings [state] and [progress] up to date and acts on them: heartbeats,
     * the prompts and their timers (advanced by [dtMs]), the end of the file,
     * the token. [state] changes only when something besides the position does.
     */
    private fun refresh(dtMs: Long) {
        val v = viewing ?: return
        if (v.stopped || v.adminStopped) return
        val initial = v.decision
        val p = media
        if (initial == null || p == null) {
            _state.update { it.copy(status = if (v.error != null) PlayerStatus.Error else PlayerStatus.Loading, error = v.error) }
            return
        }
        val d = settleGrowing(v, initial, p)
        val now = clock.elapsedMs()
        val pos = position(v)
        val phase = p.phase
        // While a reload is out the old stream plays on, unless there is none, it ended, or the scrubber shows a seek.
        val replacing = phase == PlayerPhase.Idle || v.loading && (phase == PlayerPhase.Ended || v.pendingPositionMs != null)
        val buffered = if (replacing) 0 else (v.streamClock.filePosition(p.bufferedPositionMs) - pos).coerceAtLeast(0)
        if (!v.loading && phase == PlayerPhase.Ready) {
            v.everReady = true
            if (v.startupMs == null) v.startupMs = now - v.askedAt
        }
        if (v.request.recordingId == null && v.everReady && !v.searchedOnline && !d.isOffline) searchOnline(v)
        if (v.request.recordingId == null && v.everReady && preferencesReady && !v.attemptedAutomaticSubtitle) downloadAutomaticSubtitle(v)
        val waiting = if (v.everReady) PlayerStatus.Buffering else PlayerStatus.Loading
        val status = when {
            v.error != null -> PlayerStatus.Error
            replacing -> waiting
            phase == PlayerPhase.Ended -> PlayerStatus.Ended
            !p.playWhenReady -> PlayerStatus.Paused
            phase == PlayerPhase.Buffering -> waiting
            else -> PlayerStatus.Playing
        }
        val ended = status == PlayerStatus.Ended
        if (status == PlayerStatus.Playing && v.recoveries > 0 && now - v.recoveredAt >= RECOVERY_RESET_MS) v.recoveries = 0

        var forceBeat = false
        if (!v.watched && d.watchedAtMs > 0 && pos >= d.watchedAtMs) {
            markWatched(v)
            forceBeat = true
        }

        // Next Episode: from upNextMs, or at the end even after Watch credits.
        val prefs = preferences.playback.value
        val next = v.next
        val nextOffered = next != null && d.upNextMs > 0
        val showNext = nextOffered && !v.askStill && status != PlayerStatus.Error && (ended || (pos >= d.upNextMs && !v.creditsDismissed))
        v.countdownMs = when {
            !showNext || !prefs.autoplayNext -> null
            v.countdownMs == null -> NEXT_COUNTDOWN_MS
            // Holds while paused; runs while playing, buffering and at the end.
            status == PlayerStatus.Paused -> v.countdownMs
            else -> (v.countdownMs!! - dtMs).coerceAtLeast(0)
        }

        // Skip: the playing segment and what the viewer's setting does with it.
        // Automatic at its lowest step and still stalling: say so, once.
        if (quality == StreamQuality.Auto && d.autoStep == StreamQuality.entries.last() && !v.loading && !v.floorNoted && p.stalls.count > 0) {
            v.floorNoted = true
            showNote(v, FLOOR_NOTE)
        }

        val segment = if (v.loading || ended || status == PlayerStatus.Error || showNext) null else activeSegment(d, pos, nextOffered)
        val action = segment?.let { skipAction(it, duration(d), prefs.skipMode(it.kind), it in v.seekedInto) } ?: SkipAction.None
        if (action == SkipAction.AutoDelay && segment == v.fillSegment) {
            if (status == PlayerStatus.Playing) v.fillMs += dtMs
        } else {
            v.fillSegment = if (action == SkipAction.AutoDelay) segment else null
            v.fillMs = 0
        }
        val skip = when (action) {
            SkipAction.Button -> SkipPrompt(segment!!, skipLabel(segment.kind), null)
            SkipAction.AutoDelay -> SkipPrompt(segment!!, skipLabel(segment.kind), (v.fillMs.toFloat() / AUTO_DELAY_MS).coerceIn(0f, 1f))
            else -> null
        }

        _progress.value = PlaybackProgress(pos, duration(d), buffered)
        _state.update {
            it.copy(
                status = status,
                // The planned extent is stable; the scrubber’s progress flow carries the growing end.
                // Updating the whole player state each tick would recompose all controls.
                durationMs = d.growing?.let { (it.endsAtMs - it.startedAtMs).coerceAtLeast(0) } ?: duration(d),
                growing = d.growing,
                segments = d.segments,
                skip = skip,
                nextEpisode = next,
                upNext = if (showNext && next != null) UpNextPrompt(next, v.countdownMs, NEXT_COUNTDOWN_MS) else null,
                askStillWatching = v.askStill,
                audio = d.audio,
                selectedAudio = v.selectedAudio,
                subtitles = v.subtitles,
                selectedSubtitle = v.selectedSubtitle,
                online = v.online,
                quality = quality,
                autoStep = d.autoStep.takeIf { quality == StreamQuality.Auto },
                bitrate = d.bitrate,
                mode = d.mode,
                video = d.video,
                reasons = d.reasons,
                frameRate = p.frameRate,
                note = v.note?.takeIf { now < v.noteUntil },
                stopMessage = null,
                error = v.error,
                trickplay = d.trickplay,
                isOffline = d.isOffline,
                qualities = if (d.isOffline) emptyList() else StreamQuality.offered(preferences.autoQuality.value),
            )
        }

        heartbeat(v, status, pos, buffered, forceBeat)

        // Actions last: each may start a new load or a new viewing.
        when {
            ended && !nextOffered -> {
                v.finished = true
                markWatched(v)
                exit(v)
            }
            action == SkipAction.AutoNow -> skipSegment(v, segment!!)
            action == SkipAction.AutoDelay && v.fillMs >= AUTO_DELAY_MS -> skipSegment(v, segment!!)
            v.countdownMs == 0L -> autoAdvance(v)
            !v.loading && token != null && now >= tokenRefreshAt && status != PlayerStatus.Ended && status != PlayerStatus.Error ->
                // The play outlived its media token: a new one, and a new session here.
                reload(v)
        }
    }

    // --- heartbeats ---

    private fun heartbeat(v: Viewing, status: PlayerStatus, pos: Long, buffered: Long, force: Boolean) {
        // None while a reload is out: the server takes the first "playing" after a decision as its first frame.
        if (v.loading) return
        val state = when (status) {
            PlayerStatus.Playing -> PlayState.Playing
            PlayerStatus.Loading, PlayerStatus.Buffering -> PlayState.Buffering
            PlayerStatus.Paused, PlayerStatus.Ended, PlayerStatus.Error -> PlayState.Paused
        }
        // Automatic reports sooner while the stream runs, so the server can step down before it stalls.
        val interval = when {
            quality == StreamQuality.Auto && state != PlayState.Paused -> BEAT_AUTO_MS
            state == PlayState.Playing -> BEAT_PLAYING_MS
            else -> BEAT_IDLE_MS
        }
        val changed = state != v.lastReported
        if (!force && !changed && clock.elapsedMs() - v.lastBeatAt < interval) return
        if (!report(v, state, pos, buffered)) return
        // A pause partway goes on the Continue watching row.
        if (changed && status == PlayerStatus.Paused) leftPartway(v, pos)
    }

    /** Queues a heartbeat; false when none may be sent. */
    private fun report(v: Viewing, state: PlayState, pos: Long, buffered: Long): Boolean {
        val d = v.decision ?: return false
        val playId = v.playId ?: return false
        if (v.stopped || v.adminStopped) return false
        // After "closed", nothing but the final "stopped" until the app is back.
        if (backgrounded && state != PlayState.Closed && state != PlayState.Stopped) return false
        v.lastReported = state
        v.lastBeatAt = clock.elapsedMs()
        val position = if (v.finished) duration(d) else pos
        val p = media
        val stalls = p?.stalls
        val transfer = p?.transfer
        val startup = v.startupMs?.takeIf { !v.startupSent }?.also { v.startupSent = true }
        val report = ProgressReport(
            v.request.fileId, position, duration(d), playId, state, buffered,
            throughputBps = transfer?.bps ?: p?.throughputBps,
            throughputAgeMs = transfer?.ageMs,
            switchGeneration = v.acceptedGeneration.takeIf { state == PlayState.Playing },
            switchFailedGeneration = v.failedGeneration,
            stalls = stalls?.count,
            stallMs = stalls?.ms,
            startupMs = startup,
            recordingId = v.request.recordingId,
        )
        outbox.trySend(Outgoing.Report(report, v.generation))
        return true
    }

    private suspend fun deliver(message: Outgoing) {
        when (message) {
            is Outgoing.Report -> {
                val reply = attempt("heartbeat") { withContext(io) { gateway.reportProgress(message.report) } } ?: return
                onReply(message.generation, message.report, reply)
            }
            is Outgoing.EndSession -> attempt("stop session") { withContext(io) { gateway.stopSession(message.sessionId) } }
        }
    }

    private fun onReply(generation: Int, report: ProgressReport, reply: ProgressReply) {
        val v = viewing?.takeIf { it.generation == generation } ?: return
        if (report.switchGeneration != null && v.acceptedGeneration == report.switchGeneration) {
            v.acceptedGeneration = null
            v.pendingSwitch = null
            v.stepMessage?.let { showNote(v, it) }
            v.stepMessage = null
            refresh(0)
        }
        if (report.switchFailedGeneration != null && v.failedGeneration == report.switchFailedGeneration) v.failedGeneration = null
        if (reply.completed) markWatched(v)
        if (v.stopped) return
        // The play had ended (timed out, server restarted): the server carries on with a new one.
        reply.newPlayId?.let { v.playId = it }
        reply.stopMessage?.let { adminStop(v, it) }
        reply.qualityChange?.let { changeStep(v, it) }
    }

    /**
     * Automatic: the server wants another step. Ask again here, in the same
     * play, and the server sends it; the device's default stays as it is. A
     * resend while that is out, or of the step playing, changes nothing.
     */
    private fun changeStep(v: Viewing, change: QualityChange) {
        if (quality != StreamQuality.Auto || v.adminStopped) return
        val d = v.decision ?: return
        if (v.loading || d.autoStep == change.step) return
        val generation = change.generation
        if (generation != null) {
            if (generation <= v.handledGeneration) return
            v.handledGeneration = generation
        }
        val currentStep = d.autoStep
        v.stepMessage = change.message ?: if (currentStep != null && change.step.ordinal < currentStep.ordinal) {
            if (change.step == StreamQuality.Original) "Connection recovered; playing the original file."
            else "Raised to ${stepName(change.step)} for your connection."
        } else "Lowered to ${stepName(change.step)} for your connection."
        reload(v, qualitySwitch = change)
    }

    /** A new decision at a lower step than the last, in Automatic: the note says so. */
    private fun noteStepChange(v: Viewing, previous: PlaybackDecision?, d: PlaybackDecision) {
        val was = previous?.autoStep
        val now = d.autoStep
        val message = v.stepMessage
        v.stepMessage = null
        if (quality != StreamQuality.Auto || was == null || now == null || now.ordinal <= was.ordinal) return
        showNote(v, message ?: "Lowered to ${stepName(now)} for your connection.")
    }

    private fun showNote(v: Viewing, text: String) {
        v.note = text
        v.noteUntil = clock.elapsedMs() + NOTE_MS
    }

    /** Provider work never delays startup; manual choices and account-scoped gateway bindings win. */
    private fun downloadAutomaticSubtitle(v: Viewing) {
        v.attemptedAutomaticSubtitle = true
        val playback = preferences.playback.value
        val prefs = playback.subtitles
        val d = v.decision ?: return
        val playId = v.playId ?: return
        // On, or subtitles for foreign audio that this audio is (PLAN-LANGUAGES.md §5.3).
        val starts = startingSubtitles(audioLanguage(d, v), playback.audio.languages, prefs).first == SubtitleMode.On
        if (d.isOffline || !starts || !prefs.automaticDownload || v.subtitleManuallyChosen || v.selectedSubtitle != null) return
        val revision = v.subtitleRevision
        v.automaticSubtitleInFlight = true
        scope.launch {
            try {
                val track = attempt("download an automatic subtitle") { withContext(io) { gateway.automaticSubtitle(v.request.fileId, playId) } } ?: return@launch
                if (viewing !== v || v.stopped || v.adminStopped || v.playId != playId || v.subtitleRevision != revision || v.subtitleManuallyChosen || v.selectedSubtitle != null) return@launch
                v.selectedSubtitle = track.option.id
                reload(v, subtitleId = track.option.id, burnIn = needsBurnIn(track))
            } finally {
                v.automaticSubtitleInFlight = false
            }
        }
    }

    private fun audioLanguage(d: PlaybackDecision, v: Viewing): String? = d.audio.firstOrNull { it.index == v.selectedAudio }?.language

    private fun manualSubtitleChoice(v: Viewing) {
        v.subtitleManuallyChosen = true
        v.subtitleRevision++
        val playId = v.playId ?: return
        if (v.automaticSubtitleInFlight) scope.launch {
            attempt("cancel an automatic subtitle") { withContext(io) { gateway.cancelAutomaticSubtitle(v.request.fileId, playId) } }
        }
    }

    // --- helpers ---

    private fun ensurePlayer() {
        if (media != null) return
        val p = playerFactory()
        media = p
        p.setListener(object : MediaPlayer.Listener {
            // Posted, so a callback inside a player call never re-enters the machine.
            override fun onChanged() {
                scope.launch { refresh(0) }
            }

            override fun onError(failure: PlayerFailure) {
                scope.launch { onPlayerError(failure) }
            }

            override fun onFirstFrame() {
                scope.launch {
                    val v = active() ?: return@launch
                    v.awaitingFrameGeneration?.let {
                        v.awaitingFrameGeneration = null
                        v.acceptedGeneration = it
                        refresh(0)
                    }
                }
            }

            override fun onAudioUnavailable() {
                scope.launch { audioUnavailable() }
            }
        })
        val media3 = p.media3 ?: return
        val wrapped = SessionPlayer(media3, controls)
        _player.value = wrapped
        sessionHandle = sessions.open(wrapped)
    }

    private val controls = object : SessionControls {
        override fun play() = this@RealPlaybackController.play()

        override fun pause() = this@RealPlaybackController.pause()

        override fun seekTo(positionMs: Long) = this@RealPlaybackController.seekTo(positionMs)

        override fun seekBy(deltaMs: Long) = this@RealPlaybackController.seekBy(deltaMs)

        override val filePositionMs: Long get() = viewing?.let { position(it) } ?: 0

        override val fileDurationMs: Long get() = viewing?.decision?.durationMs ?: C.TIME_UNSET

        override val fileBufferedPositionMs: Long get() = filePositionMs + _progress.value.bufferedMs
    }

    private fun duration(d: PlaybackDecision): Long = d.growing?.durationAt(clock.elapsedMs()) ?: d.durationMs

    /**
     * A recording that stops growing becomes an ordinary event play: the
     * Recording label and Go to now go and it plays on to the end. That is
     * when its playlist gets the end tag (it finished, or stopped early), the
     * player reaches the end, or its planned end passes. The playlist's own
     * length, once it has one, is the length from then on.
     */
    private fun settleGrowing(v: Viewing, d: PlaybackDecision, p: MediaPlayer): PlaybackDecision {
        if (v.request.recordingId == null || v.loading) return d
        val growing = d.growing
        val listed = p.endedEventDurationMs?.let { v.streamClock.filePosition(it) }
        val settled = when {
            listed != null && (growing != null || listed != d.durationMs) -> d.copy(durationMs = listed.coerceAtLeast(d.startMs), growing = null)
            growing == null -> d
            p.phase == PlayerPhase.Ended -> d.copy(durationMs = v.streamClock.filePosition(p.positionMs).coerceAtLeast(d.startMs), growing = null)
            growing.endedAt(clock.elapsedMs()) -> d.copy(durationMs = growing.durationAt(clock.elapsedMs()), growing = null)
            else -> d
        }
        if (settled !== d) v.decision = settled
        return settled
    }

    /** The file position now: where a load will start, else the player's time mapped to the file. */
    private fun position(v: Viewing): Long {
        val d = v.decision ?: return v.pendingPositionMs ?: v.request.startMs ?: 0
        val raw = v.pendingPositionMs ?: media?.let { v.streamClock.filePosition(it.positionMs) } ?: 0
        return raw.coerceIn(0, duration(d))
    }

    /** Finished: off the launcher's row, and the episode after it on as the one to watch next. */
    private fun markWatched(v: Viewing) {
        if (v.watched) return
        v.watched = true
        // A recording has no launcher row of its own, but it is still watched: without the flag every tick forces a beat.
        if (v.request.recordingId != null) return
        watchNext.remove(v.request.fileId)
        v.next?.let { n ->
            watchNext.update(
                WatchNextEntry(
                    fileId = n.fileId,
                    title = n.showTitle,
                    subtitle = "${episodeCode(n.season, n.episode)} · ${n.title}",
                    episode = true,
                    positionMs = 0,
                    durationMs = n.runtimeMs ?: 0,
                    engagedAtMs = clock.wallMs(),
                    next = true,
                    itemId = n.episodeId,
                    showId = n.showId,
                    season = n.season,
                ),
            )
        }
    }

    private fun leftPartway(v: Viewing, pos: Long) {
        if (v.request.recordingId != null) return
        val d = v.decision ?: return
        if (v.watched || v.finished || pos < RESUME_MIN_MS) return
        watchNext.update(
            WatchNextEntry(
                fileId = v.request.fileId,
                title = v.request.title,
                subtitle = v.request.subtitle,
                episode = d.next != null || v.request.subtitle != null,
                positionMs = pos,
                durationMs = duration(d),
                engagedAtMs = clock.wallMs(),
            ),
        )
    }

    private fun endReplacedSession(old: PlaybackDecision, new: PlaybackDecision) {
        val id = old.sessionId ?: return
        if (id != new.sessionId) outbox.trySend(Outgoing.EndSession(id))
    }

    private fun burnsIn(v: Viewing, subtitleId: String?): Boolean {
        val d = v.decision ?: return false
        if (subtitleId == null) return false
        val track = d.subtitles.firstOrNull { it.option.id == subtitleId } ?: return false
        return (!d.isOffline && needsBurnIn(track)) || d.burnInSubtitleId == subtitleId
    }

    private fun readInFile(d: PlaybackDecision, track: DecisionSubtitle) = d.readsSubtitlesInFile() && track.option.source == SubtitleSource.Embedded

    private fun needsBurnIn(track: DecisionSubtitle) = track.option.requiresBurnIn || track.format in setOf("image", "ass")

    private fun sideload(t: DecisionSubtitle, token: MediaToken?): SideloadedSubtitle? {
        val url = t.url ?: return null
        val mime = when (t.format) {
            "webvtt" -> "text/vtt"
            "ass" -> "text/x-ssa"
            else -> return null
        }
        return SideloadedSubtitle(t.option.id, token?.let { withToken(url, it) } ?: url, mime, t.option.label, t.option.language)
    }

    private inline fun <T> attempt(what: String, block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Could not $what", e)
        null
    }

    private companion object {
        const val TAG = "WmmPlayback"
        const val TICK_MS = 250L
        const val NOTE_MS = 5_000L
        const val FLOOR_NOTE = "Your connection is too slow to play this smoothly."
        const val MAX_RECOVERIES = 2
        const val RECOVERY_RESET_MS = 60_000L
        const val EVENT_START_TOLERANCE_MS = 15_000L
    }
}
