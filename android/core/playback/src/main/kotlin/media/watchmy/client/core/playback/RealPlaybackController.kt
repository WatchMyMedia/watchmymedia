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
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
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
import media.watchmy.client.model.PlaybackMode
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
import media.watchmy.client.model.PlayerSound
import media.watchmy.client.model.SoundFailure
import media.watchmy.client.model.SoundLevel
import media.watchmy.client.model.SoundSetting
import media.watchmy.client.model.SubtitleTimingState
import media.watchmy.client.model.TIMING_LIMIT_MS
import media.watchmy.client.model.TIMING_SETTLE_MS
import media.watchmy.client.model.TrackTiming
import media.watchmy.client.model.StreamProtocol
import media.watchmy.client.model.SubtitleRequestMode
import media.watchmy.client.model.SubtitleMode
import media.watchmy.client.model.requiresBurnIn
import media.watchmy.client.model.SLOW_CONNECTION_NOTE
import media.watchmy.client.model.automaticStepNote
import media.watchmy.client.model.capped
import media.watchmy.client.model.loweredNote
import media.watchmy.client.model.signing
import media.watchmy.client.model.skipLabel
import media.watchmy.client.model.stillPlayingNote
import media.watchmy.client.model.StreamQuality
import media.watchmy.client.model.SubtitleOption
import media.watchmy.client.model.SubtitleSource
import media.watchmy.client.model.UpNext
import media.watchmy.client.model.UpNextPrompt
import media.watchmy.client.model.episodeCode
import kotlin.math.abs

/**
 * The player behaviour around a [MediaPlayer]:
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
    /**
     * On a TV: the app leaving stops the stream and ends the server's
     * session, rather than holding the decoder and a transcode until the
     * server's idle timeout, and coming back loads it again where it was,
     * paused. Phones and tablets pause, for picture-in-picture and the
     * notification's controls.
     */
    private val stopInBackground: Boolean = false,
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

    private val host = PlayerHost(scope, playerFactory, sessions, visibility, clock)
    private val media: MediaPlayer? get() = host.media

    override val player: StateFlow<Player?> = host.player

    private var viewing: Viewing? = null
    private var generation = 0
    private val choice = QualityChoice(preferences, network)

    /** What decisions ask for ([choice]), as of the last one. */
    private var quality: StreamQuality = choice.current()
    private val tokens = MediaTokenClock(clock)
    private var backgrounded = false
    private var preferencesReady = false
    private var preferencesLoaded = false
    private var released = false

    private var loadJob: Job? = null
    private var renewing: Job? = null

    /** A failed renewal waits until then before the next try. */
    private var renewAfter = 0L

    private val outbox = Channel<Outgoing>(Channel.UNLIMITED)

    private sealed interface Outgoing {
        data class Report(val report: ProgressReport, val generation: Int) : Outgoing

        data class EndSession(val sessionId: String) : Outgoing
    }

    /** One file being watched: from [start] (or the next episode starting) until it stops. */
    private class Viewing(var request: PlayRequest, val generation: Int) {
        /** What an Automatic step change goes back to if it fails: the decision before it, and where it had got to. */
        data class Previous(val decision: PlaybackDecision, val atMs: Long)

        var decision: PlaybackDecision? = null

        /** Automatic's step changes; the old stream stays until the server takes the new one. */
        val switch = OutputSwitch<Previous>(keepFallbackUntilReported = true)

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
         * remuxes a track other than the file's default.
         */
        var playerSelectsAudio = true

        /**
         * A video decoder failed on this viewing's stream: its decisions
         * leave that codec out, or ask for a picture the decoder takes.
         * Once a viewing, as [playerSelectsAudio] is.
         */
        var videoNarrowing: VideoNarrowing? = null

        /** Stopped while the app was away ([stopInBackground]): where to load it again on return. */
        var parkedAtMs: Long? = null

        /** The profile the last decision was asked with, which an offline play's audio choice reads. */
        var profile: PlaybackProfile? = null
        var selectedSubtitle: String? = null
        var subtitleManuallyChosen = false
        var subtitleRevision = 0
        var attemptedAutomaticSubtitle = false
        var automaticSubtitleInFlight = false

        /** Each subtitle track's timing, by id: from the first decision that lists it, then this viewing's changes. */
        val timings = HashMap<String, TrackTiming>()

        /**
         * Tracks that came as a rendition or from the file and now play from
         * the server's copy of them, which a timing can move: they moved the
         * first time their timing wasn't 0.
         */
        val sideloaded = HashSet<String>()

        /** Puts a moved timing on the player, a moment after the press (held presses come quickly). */
        var timingApply: Job? = null

        /** Saves a moved timing once the presses stop. */
        var timingSave: Job? = null

        /** What a timing that didn't save tries again. */
        var timingRetry: (() -> Unit)? = null

        /** The sound row whose change is reloading the play, until the load it made has its first frame ([soundLoad]). */
        var soundBusy: SoundSetting? = null
        var soundLoad: Int? = null
        var soundFailure: SoundFailure? = null

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

        /** A seek has landed since the last report: the next tick sends one. */
        var seekLanded = false
        var recoveries = 0
        var recoveredAt = 0L

        /** The load playing has been ready at least once: buffering from here on is a stall, not its start. */
        var loadReady = false

        /** How long the player has sat buffering with plenty in hand, at [stuckAtMs] (player time), and not moved. */
        var stuckMs = 0L
        var stuckAtMs = 0L

        /** Steps taken to get a stuck player going ([unstick]) since it last played on for a while. */
        var unsticks = 0
        var unstuckAt = 0L

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
        // Stopped while away: load it again, playing.
        unpark(v)
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
            media?.selectText(textSelection(d, v.selectedSubtitle, v.sideloaded))
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
            // Text subtitles are already loaded (renditions or side-loaded files): switch here,
            // unless the track's saved timing needs the server's copy of it, which the stream doesn't have yet.
            else -> {
                if (!v.loading) {
                    if (sideloadForTiming(v, d, id)) reloadPlayer(v) else media?.selectText(textSelection(d, id, v.sideloaded))
                }
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
        choice.pick(q, scope)
        _state.update { it.copy(quality = q) }
        val v = active() ?: return
        if (v.decision != null) reload(v)
    }

    override fun setSound(setting: SoundSetting, level: SoundLevel) {
        stillWatching.noteInput()
        val v = active() ?: return
        val d = v.decision ?: return
        if (soundState(v, d) == null || d.isOffline || v.soundBusy != null) return
        val still = preferences.playback.value.audio.level(setting)
        if (still == level && v.soundFailure?.setting != setting) return
        v.soundBusy = setting
        v.soundFailure = null
        refresh(0)
        scope.launch {
            try {
                preferences.setSound(setting, level)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not save the sound", e)
                if (viewing !== v) return@launch
                v.soundBusy = null
                v.soundFailure = SoundFailure(setting, level, still)
                refresh(0)
                return@launch
            }
            if (viewing !== v || v.stopped) return@launch
            // The server applies the sound when it decides: the same play, from where it is.
            v.soundLoad = host.loads + 1
            reload(v)
        }
    }

    override fun soundChanged() {
        val v = active() ?: return
        if (v.decision?.isOffline == false) reload(v)
    }

    override fun moveSubtitleTiming(deltaMs: Long) {
        stillWatching.noteInput()
        val v = active() ?: return
        val d = v.decision ?: return
        val state = timingState(v, d)?.takeIf { it.changeable } ?: return
        val id = state.trackId
        val before = timing(v, d, id) ?: return
        val after = before.moved(deltaMs)
        if (after.ms == before.ms) return
        v.timings[id] = after
        v.timingRetry = null
        showTiming(v, d, id)
        // A download from the device keeps it for this play.
        if (!d.isOffline) {
            v.timingSave?.cancel()
            v.timingSave = scope.launch {
                delay(TIMING_SETTLE_MS)
                saveTiming(v, id)
            }
        }
        refresh(0)
    }

    override fun resetSubtitleTiming() {
        stillWatching.noteInput()
        val v = active() ?: return
        val d = v.decision ?: return
        val state = timingState(v, d)?.takeIf { it.changeable && it.canReset } ?: return
        val id = state.trackId
        v.timingSave?.cancel()
        if (d.isOffline) {
            v.timings[id] = TrackTiming(0)
            showTiming(v, d, id)
            refresh(0)
            return
        }
        timingCall(v, id, retry = { resetSubtitleTiming() }) { before ->
            before.reset(gateway.resetSubtitleTiming(v.request.fileId, id))
        }
    }

    override fun saveSubtitleTimingForEveryone() {
        stillWatching.noteInput()
        val v = active() ?: return
        val d = v.decision ?: return
        val state = timingState(v, d)?.takeIf { it.changeable && !it.playOnly } ?: return
        val id = state.trackId
        v.timingSave?.cancel()
        timingCall(v, id, retry = { saveSubtitleTimingForEveryone() }) { before ->
            before.savedForEveryone(gateway.setSubtitleTiming(v.request.fileId, id, before.ms, everyone = true))
        }
    }

    override fun retrySubtitleTiming() {
        val v = active() ?: return
        val retry = v.timingRetry ?: return
        v.timingRetry = null
        retry()
    }

    override fun showSubtitleTiming(timingMs: Long) {
        val v = active() ?: return
        val d = v.decision ?: return
        val id = v.selectedSubtitle ?: return
        val before = timing(v, d, id) ?: return
        val ms = timingMs.coerceIn(-TIMING_LIMIT_MS, TIMING_LIMIT_MS)
        if (ms == before.ms) return
        v.timings[id] = before.copy(ms = ms, own = true, shared = false, savedForEveryone = false)
        showTiming(v, d, id, burnedInSettleMs = CAST_TIMING_SETTLE_MS)
        refresh(0)
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
        loadJob?.cancel()
        host.release()
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
        host.followVisibility(::onVisibility)
        if (!preferencesLoaded) {
            preferencesLoaded = true
            scope.launch { attempt("load preferences") { preferences.refresh() }; preferencesReady = true }
        }
        // Every play gets a fresh media token.
        renewing?.cancel()
        tokens.forget()
        quality = choice.current()
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
        host.startTicker(::refresh)
        v.loading = true
        loadJob?.cancel()
        loadJob = scope.launch {
            try {
                // A bare play link: name the file while the server decides.
                val naming = if (request.title.isBlank()) async { attempt("name the file") { withContext(io) { lookups.name(request) } } } else null
                var d = decide(v, startMs = request.startMs, playId = null)
                var start = request.startMs ?: 0
                // No explicit start: pick up where the server resumes, 5 s
                // before where the viewer left off. A VOD playlist (or the
                // file) just starts there. An event session from a newer
                // server starts there already; one an older
                // server started at 0 is restarted there, as the web does.
                if (request.startMs == null && d.resumeMs > RESUME_MIN_MS) {
                    start = d.resumeMs
                    // A server that starts the session there already needs no second request.
                    if (StreamClock.of(d).event && !d.startsAt(start)) {
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
            v.switch.abandon()?.fallback?.decision?.sessionId?.let { outbox.trySend(Outgoing.EndSession(it)) }
        }
        if (seekMs != null) v.pendingPositionMs = seekMs
        if (newToken) tokens.forget()
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
                if (switchGeneration != null && v.switch.pending?.generation == switchGeneration && viewing === v && restorePrevious(v)) {
                    // Back on the stream before the switch.
                } else if (switchGeneration != null && prior != null && viewing === v) {
                    v.loading = false
                    v.switch.failed(switchGeneration)
                    v.stepMessage = null
                    val seek = v.pendingPositionMs
                    v.pendingPositionMs = null
                    if (media?.phase == PlayerPhase.Idle) {
                        // The stream it was to replace failed meanwhile, its error set aside for this load:
                        // with nothing left playing, a load as before, where the viewer is.
                        reload(v, seekMs = seek)
                    } else {
                        showNote(v, stillPlayingNote(prior.autoStep ?: StreamQuality.Original))
                        // A seek made while the switch was out goes to the stream still playing.
                        if (seek != null) seekWithin(v, seek) else refresh(0)
                    }
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
        quality = choice.current()
        // Automatic has no caps: the device's own profile, and the server picks the step.
        val device = withContext(io) { profileProvider.current() }.capped(quality).narrowed(v.videoNarrowing)
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
            withMediaToken = tokens.fresh() == null,
        )
        v.askedAt = clock.elapsedMs()
        // The token comes with the decision, from the server or asked for beside it. A downloaded
        // item plays from the device and brings none: it needs none.
        var decision = withContext(io) { gateway.decide(request) }
        decision.mediaToken?.let(tokens::accept)
        // An automatic ASS default needs the same faithful server renderer as a manual choice.
        val selected = subtitleId ?: decision.subtitleId
        val track = decision.subtitles.firstOrNull { it.option.id == selected }
        if (!decision.isOffline && !burnIn && track != null && needsBurnIn(track) && decision.burnInSubtitleId != selected) {
            val first = decision
            try {
                decision = withContext(io) {
                    gateway.decide(request.copy(playId = first.playId, subtitleId = selected, burnIn = true, withMediaToken = tokens.fresh() == null))
                }
                decision.mediaToken?.let(tokens::accept)
            } catch (e: Exception) {
                first.sessionId?.takeIf { it != v.decision?.sessionId }?.let { outbox.trySend(Outgoing.EndSession(it)) }
                throw e
            }
            endReplacedSession(first, decision)
        }
        ensureToken()
        return decision
    }

    /** A token for the stream when none came with the decision: an older server's failed one, or a play from the device. */
    private suspend fun ensureToken() {
        if (tokens.fresh() != null) return
        tokens.accept(withContext(io) { gateway.mediaToken() })
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
        // Each track's saved timing, unless this viewing has already moved it.
        for (t in d.subtitles) v.timings.getOrPut(t.option.id) { TrackTiming.of(t) }
        val prefs = preferences.playback.value
        v.selectedAudio = when {
            !d.isOffline -> d.selectedAudio ?: v.selectedAudio
            // From the device: a pick stays; otherwise an original file, which has every track,
            // starts in the person's audio languages, as the server would choose.
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
        // The load below is the switch's: its first frame accepts it.
        if (switchGeneration != null) v.switch.begin(switchGeneration, previous?.let { Viewing.Previous(it, start) }, host.loads + 1)
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
        v.loadReady = false
        v.stuckMs = 0
        if (qualitySwitch?.generation == null) noteStepChange(v, previous, d)
        val token = tokens.token
        // The stream's further requests carry this token, and a renewed one after it.
        token?.let { media?.updateMediaToken(it) }
        host.load(playerSource(v, d, start, playWhenReady))
        refresh(0)
    }

    /** What the player loads for decision [d], from file position [start]. */
    private fun playerSource(v: Viewing, d: PlaybackDecision, start: Long, playWhenReady: Boolean): PlayerSource {
        val token = tokens.token
        sideloadForTiming(v, d, v.selectedSubtitle)
        // Direct play reads the file's own text subtitles from the file: the server's copy of
        // one reads the whole video first, which a long film on a slow disk can't do in time.
        val usual = if (d.hasRenditions()) emptyList() else d.subtitles.filterNot { needsBurnIn(it) || readInFile(d, it) }
        val moved = d.subtitles.filter { it.option.id in v.sideloaded && it !in usual && !needsBurnIn(it) }
        return PlayerSource(
            url = token?.signing(d.url) ?: d.url,
            hls = d.protocol == StreamProtocol.Hls,
            live = v.streamClock.event,
            startPositionMs = v.streamClock.playerPosition(start),
            playWhenReady = playWhenReady,
            subtitles = (usual + moved).mapNotNull { sideload(it, token, playerTiming(v, d, it.option.id)) },
            embeddedSubtitles = d.embeddedSubtitles(),
            text = textSelection(d, v.selectedSubtitle, v.sideloaded),
            audioOrdinal = if (d.protocol == StreamProtocol.File) d.audio.indexOfFirst { it.index == v.selectedAudio }.takeIf { it >= 0 } else null,
            audioTracks = d.audio.size.takeIf { d.protocol == StreamProtocol.File },
            title = v.request.title,
            subtitle = v.request.subtitle,
            artworkUrl = lookups.artworkUrl(v.request.backdropPath),
        )
    }

    /**
     * Loads the decision playing again where it has got to, playing or
     * paused as it is: a track moved to the server's copy of it for its
     * timing. The server's session carries on.
     */
    private fun reloadPlayer(v: Viewing) {
        val d = v.decision ?: return
        if (v.loading) return
        v.loadReady = false
        v.stuckMs = 0
        host.load(playerSource(v, d, position(v), media?.playWhenReady ?: true))
        refresh(0)
    }

    // --- subtitle timing and sound ---

    /** Track [id]'s timing this viewing; null for a track the decision doesn't list. */
    private fun timing(v: Viewing, d: PlaybackDecision, id: String): TrackTiming? {
        v.timings[id]?.let { return it }
        val track = d.subtitles.firstOrNull { it.option.id == id } ?: return null
        return TrackTiming.of(track).also { v.timings[id] = it }
    }

    /** The timing the player puts on side-loaded track [id]; a burned-in track's is in the picture. */
    private fun playerTiming(v: Viewing, d: PlaybackDecision, id: String): Long =
        if (id == d.burnInSubtitleId) 0 else timing(v, d, id)?.ms ?: 0

    /**
     * Moves the selected track [id] to the server's copy of it when its
     * timing isn't 0 and it came as a rendition or from the file: Media3
     * moves only side-loaded tracks' cues. True when it moved (the player
     * needs loading again).
     */
    private fun sideloadForTiming(v: Viewing, d: PlaybackDecision, id: String?): Boolean {
        if (id == null || id in v.sideloaded || id == d.burnInSubtitleId) return false
        val track = d.subtitles.firstOrNull { it.option.id == id } ?: return false
        if (!movable(d, track) || !d.hasRenditions() && !readInFile(d, track)) return false
        if ((v.timings[id]?.ms ?: track.timingMs) == 0L) return false
        v.sideloaded += id
        return true
    }

    /**
     * [track] can be moved to the server's copy of it: one there is, as text
     * the player draws. A download's renditions are inside it, with no copy
     * on the device.
     */
    private fun movable(d: PlaybackDecision, track: DecisionSubtitle) = track.url != null && !needsBurnIn(track) && !(d.isOffline && d.hasRenditions())

    /**
     * Puts track [id]'s timing on the player: the cues move a moment after
     * the press, the first time once the track plays from the server's copy.
     * A burned-in track's is the server's to draw, in a new stream once the
     * presses settle ([burnedInSettleMs]; the save comes first).
     */
    private fun showTiming(v: Viewing, d: PlaybackDecision, id: String, burnedInSettleMs: Long? = null) {
        if (id == d.burnInSubtitleId) {
            if (burnedInSettleMs != null) {
                v.timingApply?.cancel()
                v.timingApply = scope.launch {
                    delay(burnedInSettleMs)
                    if (viewing === v) reload(v)
                }
            }
            return
        }
        if (v.timingApply?.isActive == true) return
        v.timingApply = scope.launch {
            delay(TIMING_APPLY_MS)
            if (viewing !== v || v.loading) return@launch
            val decision = v.decision ?: return@launch
            if (decision.burnInSubtitleId == id || v.selectedSubtitle != id) return@launch
            if (sideloadForTiming(v, decision, id)) reloadPlayer(v)
            else media?.setSubtitleTiming(id, playerTiming(v, decision, id))
        }
    }

    /** Saves the person's own timing for track [id], once the presses have stopped. */
    private suspend fun saveTiming(v: Viewing, id: String) {
        val sent = v.timings[id] ?: return
        try {
            val reply = withContext(io) { gateway.setSubtitleTiming(v.request.fileId, id, sent.ms) }
            if (viewing !== v) return
            val now = v.timings[id] ?: return
            // A press since the save went out: its own save follows.
            if (now.ms == sent.ms) v.timings[id] = now.saved(reply)
            // The server burns it in at the new timing, in a new stream from here.
            if (v.decision?.burnInSubtitleId == id && now.ms == sent.ms) reload(v)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not save the subtitle timing", e)
            if (viewing !== v) return
            val now = v.timings[id] ?: return
            val d = v.decision
            // A burned-in track keeps the timing it was burned in with.
            val shown = d?.subtitles?.firstOrNull { it.option.id == id }?.takeIf { d.burnInSubtitleId == id }?.timingMs ?: now.ms
            v.timings[id] = now.copy(ms = shown, failed = true)
            v.timingRetry = { scope.launch { saveTiming(v, id) } }
        }
        refresh(0)
    }

    /** A Reset or a Save for everyone for track [id]: [call] gives the timing afterwards; a failure keeps [retry] for Try again. */
    private fun timingCall(v: Viewing, id: String, retry: () -> Unit, call: suspend (TrackTiming) -> TrackTiming) {
        val before = v.timings[id] ?: return
        v.timingRetry = null
        scope.launch {
            try {
                val after = withContext(io) { call(before) }
                if (viewing !== v) return@launch
                v.timings[id] = after
                v.decision?.let { d -> if (d.burnInSubtitleId == id) { if (after.ms != before.ms) reload(v) } else showTiming(v, d, id) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not save the subtitle timing", e)
                if (viewing !== v) return@launch
                v.timings[id] = (v.timings[id] ?: before).copy(failed = true)
                v.timingRetry = retry
            }
            refresh(0)
        }
    }

    /** The selected track's timing as the player shows it; null with subtitles off, below the server's level, or for a track it can't move. */
    private fun timingState(v: Viewing, d: PlaybackDecision): SubtitleTimingState? {
        if (!d.soundSupported) return null
        val id = v.selectedSubtitle ?: return null
        val track = d.subtitles.firstOrNull { it.option.id == id } ?: return null
        val burnedIn = d.burnInSubtitleId == id
        val playable = burnedIn || id in v.sideloaded || movable(d, track) ||
            !d.hasRenditions() && !readInFile(d, track) && track.url != null && !needsBurnIn(track)
        if (!playable) return null
        val t = v.timings[id] ?: TrackTiming.of(track)
        return SubtitleTimingState(
            trackId = id,
            ms = t.ms,
            shared = t.shared,
            own = t.own,
            canReset = if (d.isOffline) t.ms != 0L else t.canReset,
            resetsToShared = !d.isOffline && t.resetsToShared,
            savedForEveryone = t.savedForEveryone,
            burnedIn = burnedIn,
            saveFailed = t.failed,
            playOnly = d.isOffline,
            // A play cast to this TV: the phone that cast it moves and saves its timing.
            changeable = gateway.savesForPerson,
        )
    }

    /** Boost dialogue and Reduce loud sounds as the player shows them; null where the server can't apply them or this player can't save them. */
    private fun soundState(v: Viewing, d: PlaybackDecision): PlayerSound? {
        if (!d.soundSupported || !gateway.savesForPerson) return null
        val audio = preferences.playback.value.audio
        return PlayerSound(audio.dialogueBoost, audio.reduceLoudSounds, busy = v.soundBusy, failed = v.soundFailure, download = d.isOffline)
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
        v.soundBusy = null
        v.soundLoad = null
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
            v.switch.pending?.fallback?.decision?.sessionId?.takeIf { it != d.sessionId }?.let { outbox.trySend(Outgoing.EndSession(it)) }
        }
        v.stopped = true
        host.stopTicker()
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
        v.seekLanded = true
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
        host.stopTicker()
        leftPartway(v, pos)
        _state.update { it.copy(status = PlayerStatus.Paused, stopMessage = message, skip = null, upNext = null, askStillWatching = false) }
    }

    private fun onVisibility(visible: Boolean) {
        if (visible) {
            backgrounded = false
            active()?.let(::unpark)
            refresh(0)
            return
        }
        if (backgrounded) return
        backgrounded = true
        val v = active() ?: return
        val d = v.decision ?: return
        media?.pause()
        val pos = position(v)
        report(v, PlayState.Closed, pos, 0)
        leftPartway(v, pos)
        if (stopInBackground) park(v, d, pos)
    }

    /** A TV's app left: the stream stops, its server session ends, and the position is kept for the return. */
    private fun park(v: Viewing, d: PlaybackDecision, pos: Long) {
        loadJob?.cancel()
        v.loading = false
        v.switch.abandon()?.fallback?.decision?.sessionId?.takeIf { it != d.sessionId }?.let { outbox.trySend(Outgoing.EndSession(it)) }
        d.sessionId?.let { outbox.trySend(Outgoing.EndSession(it)) }
        // Ended here: the next decision doesn't end it again.
        v.decision = d.copy(sessionId = null)
        v.parkedAtMs = pos
        v.pendingPositionMs = pos
        media?.stop()
    }

    /** Back from [park]: a new decision where it was, playing or paused as the player has it. */
    private fun unpark(v: Viewing) {
        val at = v.parkedAtMs ?: return
        v.parkedAtMs = null
        reload(v, seekMs = at)
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
        if (restorePrevious(v)) return
        // A switch with nothing to go back to: it failed, and the error is handled as any other.
        if (v.switch.awaitingFrame) {
            v.switch.reject()
            v.stepMessage = null
        }
        // A video decoder that can't take this stream (a picture larger than it decodes, a profile it
        // lists but fails on): ask once more, for this viewing, at a size it decodes or without the codec.
        val video = failure.video?.takeIf { failure.kind == PlayerFailure.Kind.Decoder && v.videoNarrowing == null }
        val d = v.decision
        val narrowing = if (video != null && d != null && !d.isOffline) {
            videoNarrowing(video, profileProvider.videoLimit(video.mimeType), copied = d.mode != PlaybackMode.Transcode)
        } else null
        if (narrowing != null) {
            Log.w(TAG, "The video decoder failed on ${video?.mimeType} ${video?.width}x${video?.height}; asking again with $narrowing")
            v.videoNarrowing = narrowing
            reload(v)
            return
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

    /** Back to the stream before a failed step change, where it had got to; false when there is none to go back to. */
    private fun restorePrevious(v: Viewing): Boolean {
        val previous = v.switch.pending?.fallback ?: return false
        v.switch.reject()
        v.pendingPositionMs = previous.atMs
        v.stepMessage = stillPlayingNote(previous.decision.autoStep ?: StreamQuality.Original)
        apply(v, previous.decision, previous.atMs, playWhenReady = true)
        v.stepMessage?.let { showNote(v, it) }
        v.stepMessage = null
        refresh(0)
        return true
    }

    /**
     * Whether the player has sat buffering for [STUCK_MS] with at least
     * [STUCK_BUFFER_MS] in hand and its position not moving: waiting on
     * nothing it is fetching, so it won't get going by itself. Media3 1.11
     * did that after two quick seeks on a passthrough audio track (a Dolby
     * bitstream to a TV or receiver), until the app left the player. A load
     * that hasn't been ready yet is starting, and a slow decoder still moves.
     */
    private fun stuck(v: Viewing, p: MediaPlayer, status: PlayerStatus, dtMs: Long): Boolean {
        val pos = p.positionMs
        val waiting = status == PlayerStatus.Buffering && !v.loading && v.loadReady && p.playWhenReady &&
            p.phase == PlayerPhase.Buffering && p.bufferedPositionMs - pos >= STUCK_BUFFER_MS
        if (!waiting || pos != v.stuckAtMs) {
            v.stuckMs = 0
            v.stuckAtMs = pos
            return false
        }
        v.stuckMs += dtMs
        return v.stuckMs >= STUCK_MS
    }

    /** Gets a stuck player going: a seek where it is, then a new load there, then an error that Play retries. */
    private fun unstick(v: Viewing, p: MediaPlayer) {
        v.stuckMs = 0
        v.unsticks++
        v.unstuckAt = clock.elapsedMs()
        when (v.unsticks) {
            1 -> {
                Log.w(TAG, "The player has buffered ${p.bufferedPositionMs - p.positionMs} ms ahead and not moved for $STUCK_MS ms; seeking where it is")
                p.seekTo(p.positionMs)
                refresh(0)
            }
            2 -> {
                Log.w(TAG, "The player is stuck again; loading the stream again")
                reload(v)
            }
            else -> {
                Log.w(TAG, "The player is still stuck after a new load; giving up")
                // Stopped, not paused: Play's new load keeps playing as the viewer had it.
                p.stop()
                v.error = STUCK_MESSAGE
                refresh(0)
            }
        }
    }

    // --- the tick ---

    /**
     * Brings [state] and [progress] up to date and acts on them: heartbeats,
     * the prompts and their timers (advanced by [dtMs]), the end of the file,
     * the token. [state] changes only when something besides the position does.
     */
    private fun refresh(dtMs: Long) {
        val v = viewing ?: return
        // Parked, nothing runs: no countdown, no skip, no token renewal until the app is back.
        if (v.stopped || v.adminStopped || v.parkedAtMs != null) return
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
            v.loadReady = true
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
        if (status == PlayerStatus.Playing && v.unsticks > 0 && now - v.unstuckAt >= RECOVERY_RESET_MS) v.unsticks = 0
        val stuck = stuck(v, p, status, dtMs)

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
            showNote(v, SLOW_CONNECTION_NOTE)
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
                sound = soundState(v, d),
                timing = timingState(v, d),
                subtitleSize = v.request.subtitleSize,
            )
        }

        heartbeat(v, status, pos, buffered, forceBeat)

        // Actions last: each may start a new load or a new viewing.
        when {
            stuck -> unstick(v, p)
            ended && !nextOffered -> {
                v.finished = true
                markWatched(v)
                exit(v)
            }
            action == SkipAction.AutoNow -> skipSegment(v, segment!!)
            action == SkipAction.AutoDelay && v.fillMs >= AUTO_DELAY_MS -> skipSegment(v, segment!!)
            v.countdownMs == 0L -> autoAdvance(v)
            !v.loading && tokens.due && status != PlayerStatus.Ended && status != PlayerStatus.Error ->
                // The play is outliving its media token: a new one on the stream's further requests, without a reload.
                renewToken()
        }
    }

    /**
     * Renews the media token in place, as Live TV does: the player puts the
     * new one on the playlists, segments and file ranges it fetches from now
     * on, so the stream plays on in the same session. A failure tries again
     * after [TOKEN_RETRY_MS]; a stream refused meanwhile recovers as any 401.
     */
    private fun renewToken() {
        if (renewing?.isActive == true || clock.elapsedMs() < renewAfter) return
        renewing = scope.launch {
            val fresh = attempt("renew the media token") { withContext(io) { gateway.mediaToken() } }
            if (fresh == null) {
                renewAfter = clock.elapsedMs() + TOKEN_RETRY_MS
                return@launch
            }
            if (released) return@launch
            tokens.accept(fresh)
            media?.updateMediaToken(fresh)
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
        val sinceBeat = clock.elapsedMs() - v.lastBeatAt
        val seekDue = v.seekLanded && sinceBeat >= SEEK_BEAT_GAP_MS
        if (!force && !changed && !seekDue && sinceBeat < interval) return
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
        v.seekLanded = false
        val position = if (v.finished) duration(d) else pos
        // Measured here, on the player's thread, before the report goes to the queue.
        val m = measure(media, buffered, v.switch)
        val startup = v.startupMs?.takeIf { !v.startupSent }?.also { v.startupSent = true }
        val report = ProgressReport(
            v.request.fileId, position, duration(d), playId, state, buffered,
            throughputBps = m.transfer?.bps ?: m.estimateBps,
            throughputAgeMs = m.transfer?.ageMs,
            switchGeneration = m.switchGeneration.takeIf { state == PlayState.Playing },
            switchFailedGeneration = m.switchFailedGeneration,
            stalls = m.stalls?.count,
            stallMs = m.stalls?.ms,
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
        if (v.switch.reported(report.switchGeneration, report.switchFailedGeneration)) {
            v.stepMessage?.let { showNote(v, it) }
            v.stepMessage = null
            refresh(0)
        }
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
        if (generation != null && !v.switch.take(generation)) return
        v.stepMessage = change.message ?: automaticStepNote(d.autoStep, change.step)
        reload(v, qualitySwitch = change)
    }

    /** A new decision at a lower step than the last, in Automatic: the note says so. */
    private fun noteStepChange(v: Viewing, previous: PlaybackDecision?, d: PlaybackDecision) {
        val was = previous?.autoStep
        val now = d.autoStep
        val message = v.stepMessage
        v.stepMessage = null
        if (quality != StreamQuality.Auto || was == null || now == null || now.ordinal <= was.ordinal) return
        showNote(v, message ?: loweredNote(now))
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
        // On, or subtitles for foreign audio that this audio is.
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
        host.ensure(playerEvents) { SessionPlayer(it, controls) }
    }

    /** The player's callbacks, posted by [host], so one inside a player call never re-enters the machine. */
    private val playerEvents = object : PlayerHost.Events {
        override fun onChanged() = refresh(0)

        override fun onError(failure: PlayerFailure) = onPlayerError(failure)

        override fun onFirstFrame(load: Int) {
            val v = active() ?: return
            // The new sound plays: its row settles.
            val sound = v.soundLoad?.takeIf { load >= it }?.also {
                v.soundBusy = null
                v.soundLoad = null
            }
            // The new step's own first frame: the next heartbeat says it plays.
            if (v.switch.frame(load) != null || sound != null) refresh(0)
        }

        override fun onAudioUnavailable() = audioUnavailable()
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
                    artPath = n.stillPath,
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
                artPath = v.request.backdropPath,
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

    private fun sideload(t: DecisionSubtitle, token: MediaToken?, timingMs: Long): SideloadedSubtitle? {
        val url = t.url ?: return null
        val mime = when (t.format) {
            "webvtt" -> "text/vtt"
            "ass" -> "text/x-ssa"
            else -> return null
        }
        return SideloadedSubtitle(t.option.id, token?.signing(url) ?: url, mime, t.option.label, t.option.language, timingMs)
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
        const val NOTE_MS = 5_000L
        const val TOKEN_RETRY_MS = 30_000L
        const val MAX_RECOVERIES = 2
        const val RECOVERY_RESET_MS = 60_000L

        /** A moved timing reaches the player this long after the press, so held presses move it a few times a second. */
        const val TIMING_APPLY_MS = 120L

        /**
         * A burned-in track moved by a phone casting here: the stream starts
         * again this long after its last move, once the phone has saved it.
         */
        const val CAST_TIMING_SETTLE_MS = 2_500L
    }
}
