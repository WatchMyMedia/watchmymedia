package media.watchmy.client.core.playback

import androidx.media3.common.Player
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import media.watchmy.client.core.playback.player.MediaPlayer
import media.watchmy.client.core.playback.player.PlayerFailure
import media.watchmy.client.core.playback.player.PlayerPhase
import media.watchmy.client.core.playback.player.PlayerSource
import media.watchmy.client.core.playback.player.Stalls
import media.watchmy.client.core.playback.player.TextSelection
import media.watchmy.client.core.playback.profile.ProfileProvider
import media.watchmy.client.core.playback.session.MediaSessionHost
import media.watchmy.client.core.playback.watchnext.WatchNext
import media.watchmy.client.core.playback.watchnext.WatchNextEntry
import media.watchmy.client.model.AudioOption
import media.watchmy.client.model.AudioPassthrough
import media.watchmy.client.model.ClientInfo
import media.watchmy.client.model.DecisionRequest
import media.watchmy.client.model.DecisionSubtitle
import media.watchmy.client.model.DevicePlayback
import media.watchmy.client.model.MediaToken
import media.watchmy.client.model.OnlineSubtitles
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.PlayState
import media.watchmy.client.model.PlaybackDecision
import media.watchmy.client.model.PlaybackGateway
import media.watchmy.client.model.PlaybackMode
import media.watchmy.client.model.PlaybackPreferences
import media.watchmy.client.model.PlaybackProfile
import media.watchmy.client.model.PlaybackProgress
import media.watchmy.client.model.PlayerEvent
import media.watchmy.client.model.PlayerUiState
import media.watchmy.client.model.PreferencesRepository
import media.watchmy.client.model.ProgressReply
import media.watchmy.client.model.ProgressReport
import media.watchmy.client.model.SegmentKind
import media.watchmy.client.model.Segment
import media.watchmy.client.model.SkipMode
import media.watchmy.client.model.StreamProtocol
import media.watchmy.client.model.StreamQuality
import media.watchmy.client.model.StreamVideo
import media.watchmy.client.model.SubtitleSize
import media.watchmy.client.model.Trickplay
import media.watchmy.client.model.UpNext

/** Virtual time from the test scheduler. */
@OptIn(ExperimentalCoroutinesApi::class)
class TestClock(private val scheduler: TestCoroutineScheduler) : Clock {
    override fun elapsedMs(): Long = scheduler.currentTime

    override fun wallMs(): Long = WALL_START + scheduler.currentTime

    companion object {
        const val WALL_START = 1_800_000_000_000L
    }
}

class FakeGateway(private val clock: Clock) : PlaybackGateway {
    val decisions = mutableListOf<DecisionRequest>()
    var decide: (DecisionRequest) -> PlaybackDecision = { error("no decision set") }
    val reports = mutableListOf<ProgressReport>()
    var reply: (ProgressReport) -> ProgressReply = { ProgressReply(completed = false, stopMessage = null, newPlayId = null) }
    var tokenLifetimeMs = 6 * 3_600_000L
    var tokens = 0
    val stoppedSessions = mutableListOf<String>()

    /** Set, decisions wait for it: a request still out. */
    var hold: CompletableDeferred<Unit>? = null

    override suspend fun decide(request: DecisionRequest): PlaybackDecision {
        decisions += request
        hold?.await()
        return decide.invoke(request)
    }

    override suspend fun reportProgress(report: ProgressReport): ProgressReply {
        reports += report
        return reply(report)
    }

    override suspend fun mediaToken(): MediaToken = MediaToken("tok${++tokens}", clock.wallMs() + tokenLifetimeMs, serverUrl = "http://srv")

    override suspend fun stopSession(sessionId: String) {
        stoppedSessions += sessionId
    }

    /** What a subtitle search finds; null is a server without a provider. */
    var online: OnlineSubtitles? = null
    val searches = mutableListOf<Long>()
    val downloads = mutableListOf<String>()
    var download: (String) -> String = { error("no download set") }
    val automaticCalls = mutableListOf<String>()
    val automaticCancellations = mutableListOf<String>()
    var automatic: suspend () -> DecisionSubtitle? = { null }
    override suspend fun automaticSubtitle(fileId: Long, playId: String): DecisionSubtitle? {
        automaticCalls += playId
        return automatic()
    }
    override suspend fun cancelAutomaticSubtitle(fileId: Long, playId: String) { automaticCancellations += playId }

    override suspend fun searchSubtitles(fileId: Long): OnlineSubtitles? {
        searches += fileId
        return online
    }

    override suspend fun downloadSubtitle(fileId: Long, resultId: String): String {
        downloads += resultId
        return download(resultId)
    }
}

/**
 * A player whose position runs with virtual time while it is ready and
 * playing. Tests move it between phases with [buffer], [ready] and [end].
 */
class FakeMediaPlayer(private val clock: Clock) : MediaPlayer {
    override val media3: Player? = null
    private var listener: MediaPlayer.Listener? = null
    val loads = mutableListOf<PlayerSource>()
    val seeks = mutableListOf<Long>()
    var text: TextSelection = TextSelection.Off
    var audioOrdinal: Int? = null
    var playsAudio: (Int) -> Boolean = { true }
    var bufferAheadMs = 30_000L
    var readyOnLoad = true
    var renderOnReady = true
    /** Allows an initial frame without a frame from a prepared replacement. */
    var renderOnLoad: (Int) -> Boolean = { renderOnReady }
    var failNextLoad: Exception? = null
    var released = false
    var liveSeeks = 0
    val liveTokens = mutableListOf<MediaToken>()
    override var liveOffsetMs: Long? = null
    override var liveWindowDurationMs: Long? = null
    override var endedEventDurationMs: Long? = null
    override fun seekToLiveEdge() { liveSeeks++; change { anchorPos = 0 } }
    override fun updateMediaToken(token: MediaToken) { liveTokens += token }

    override var phase = PlayerPhase.Idle
        private set
    override var playWhenReady = false
        private set
    private var anchorPos = 0L
    private var anchorAt = 0L

    override val positionMs: Long
        get() = if (phase == PlayerPhase.Ready && playWhenReady) anchorPos + (clock.elapsedMs() - anchorAt) else anchorPos

    override val bufferedPositionMs: Long get() = positionMs + bufferAheadMs

    override var throughputBps: Long? = null
    override var transfer: media.watchmy.client.core.playback.player.TransferSample? = null

    /** What the player counted since its last load; tests set it. */
    override var stalls = Stalls()

    /** The video's frame rate as the player read it; tests set it with [readFrameRate]. */
    override var frameRate: Float? = null
        private set

    private fun change(block: () -> Unit) {
        anchorPos = positionMs
        anchorAt = clock.elapsedMs()
        block()
        listener?.onChanged()
    }

    override fun setListener(listener: MediaPlayer.Listener?) {
        this.listener = listener
    }

    override fun load(source: PlayerSource) {
        failNextLoad?.let { failure ->
            failNextLoad = null
            throw failure
        }
        loads += source
        text = source.text
        audioOrdinal = source.audioOrdinal
        stalls = Stalls()
        change {
            anchorPos = source.startPositionMs
            playWhenReady = source.playWhenReady
            phase = if (readyOnLoad) PlayerPhase.Ready else PlayerPhase.Buffering
        }
        if (readyOnLoad && renderOnLoad(loads.size)) listener?.onFirstFrame()
    }

    override fun play() = change { playWhenReady = true }

    override fun pause() = change { playWhenReady = false }

    override fun seekTo(positionMs: Long) {
        seeks += positionMs
        change {
            anchorPos = positionMs
            if (phase == PlayerPhase.Ended) phase = PlayerPhase.Ready
        }
    }

    override fun selectText(selection: TextSelection) {
        text = selection
    }

    override fun selectAudio(ordinal: Int): Boolean = playsAudio(ordinal).also { if (it) audioOrdinal = ordinal }

    override fun stop() = change { phase = PlayerPhase.Idle }

    override fun release() {
        released = true
    }

    fun buffer() = change { phase = PlayerPhase.Buffering }

    fun ready() {
        change { phase = PlayerPhase.Ready }
        if (renderOnReady) listener?.onFirstFrame()
    }

    fun renderFrame() { listener?.onFirstFrame() }

    fun end() = change { phase = PlayerPhase.Ended }

    fun readFrameRate(rate: Float?) = change { frameRate = rate }

    fun fail(failure: PlayerFailure) {
        listener?.onError(failure)
    }

    /** The load's audio ordinal couldn't be selected (ExoMediaPlayer's tracks didn't line up). */
    fun audioUnavailable() {
        listener?.onAudioUnavailable()
    }
}

class FakePreferences(initial: PlaybackPreferences = PlaybackPreferences()) : PreferencesRepository {
    override val playback = MutableStateFlow(initial)
    override val quality = MutableStateFlow(StreamQuality.Original)
    override val autoQuality = MutableStateFlow(true)

    override suspend fun refresh() = Unit

    override suspend fun setSkip(kind: SegmentKind, mode: SkipMode) = playback.update { it.copy(skip = it.skip + (kind to mode)) }

    override suspend fun setAutoplayNext(enabled: Boolean) = playback.update { it.copy(autoplayNext = enabled) }

    override suspend fun setQuality(quality: StreamQuality) {
        this.quality.value = quality
    }

    override val device = MutableStateFlow(DevicePlayback())

    override suspend fun setMobileQuality(quality: StreamQuality?) = device.update { it.copy(mobileQuality = quality) }

    override suspend fun setMatchFrameRate(enabled: Boolean) = device.update { it.copy(matchFrameRate = enabled) }

    override suspend fun setAudioPassthrough(mode: AudioPassthrough) = device.update { it.copy(audioPassthrough = mode) }

    override suspend fun setSubtitleSize(size: SubtitleSize) = device.update { it.copy(subtitleSize = size) }
}

class FakeWatchNext : WatchNext {
    val updates = mutableListOf<WatchNextEntry>()
    val removals = mutableListOf<Long>()

    override fun update(entry: WatchNextEntry) {
        updates += entry
    }

    override fun remove(fileId: Long) {
        removals += fileId
    }
}

class FakeVisibility : AppVisibility {
    private var listener: ((Boolean) -> Unit)? = null

    override fun observe(onChange: (visible: Boolean) -> Unit): AutoCloseable {
        listener = onChange
        return AutoCloseable { listener = null }
    }

    fun set(visible: Boolean) = listener?.invoke(visible)
}

val CLIENT = ClientInfo("device-1", "Living room", "WatchMyMedia for Android TV", "androidtv", "0.1.0")

val TV_PROFILE = PlaybackProfile(
    name = "androidtv",
    containers = listOf("mkv", "mp4", "webm", "mpegts"),
    videoCodecs = listOf("h264", "hevc"),
    audioCodecs = listOf("aac", "mp3", "opus", "flac", "vorbis", "ac3", "eac3"),
    maxWidth = 3840,
    maxHeight = 2160,
    hdr = true,
)

const val MIN = 60_000L

val NEXT = UpNext(fileId = 2, episodeId = 12, showId = 100, showTitle = "Harbor Lights", season = 3, episode = 8, title = "Squall Line", stillPath = null)

val ENGLISH = AudioOption(1, "English · EAC3 5.1 (Default)", "en", true)
val COMMENTARY = AudioOption(2, "English · AAC 2.0 · Commentary", "en", false)

/** Its source from the ID, as the server gives them: "s3" embedded, "x0" a sidecar, "d12" downloaded. */
fun textSub(id: String, format: String = "webvtt", url: String? = "http://srv/api/files/1/subtitles/$id.vtt", language: String? = "en") =
    DecisionSubtitle(
        media.watchmy.client.model.SubtitleOption(
            id, "Sub $id", language, false, burnInOnly = format == "image",
            source = when (id.first()) {
                'x' -> media.watchmy.client.model.SubtitleSource.Sidecar
                'd' -> media.watchmy.client.model.SubtitleSource.Online
                else -> media.watchmy.client.model.SubtitleSource.Embedded
            },
        ),
        format, if (format == "image") null else url,
    )

/** A 50 minute file in a VOD playlist unless told otherwise. */
fun decision(
    playId: String = "a1",
    sessionId: String? = "s1",
    protocol: StreamProtocol = StreamProtocol.Hls,
    vod: Boolean = true,
    url: String = if (protocol == StreamProtocol.File) "http://srv/api/files/1/stream?play=$playId" else "http://srv/api/playback/$sessionId/master.m3u8",
    startMs: Long = 0,
    durationMs: Long = 50 * MIN,
    resumeMs: Long = 0,
    segments: List<Segment> = emptyList(),
    creditsMs: Long = 0,
    watchedAtMs: Long = 46 * MIN,
    upNextMs: Long = 0,
    next: UpNext? = null,
    subtitles: List<DecisionSubtitle> = emptyList(),
    burnInSubtitleId: String? = null,
    audio: List<AudioOption> = listOf(ENGLISH, COMMENTARY),
    selectedAudio: Int? = 1,
    mode: PlaybackMode = if (protocol == StreamProtocol.File) PlaybackMode.Direct else PlaybackMode.Remux,
    video: StreamVideo? = null,
    autoStep: StreamQuality? = null,
    bitrate: Long = 0,
    trickplay: Trickplay? = null,
) = PlaybackDecision(
    mode = mode,
    reasons = emptyList(),
    protocol = protocol,
    vod = vod,
    url = url,
    mimeType = if (protocol == StreamProtocol.Hls) "application/vnd.apple.mpegurl" else "video/x-matroska",
    playId = playId,
    sessionId = if (protocol == StreamProtocol.File) null else sessionId,
    durationMs = durationMs,
    startMs = startMs,
    resumeMs = resumeMs,
    audio = audio,
    selectedAudio = selectedAudio,
    subtitles = subtitles,
    burnInSubtitleId = burnInSubtitleId,
    segments = segments,
    creditsMs = creditsMs,
    watchedAtMs = watchedAtMs,
    upNextMs = upNextMs,
    next = next,
    video = video,
    autoStep = autoStep,
    bitrate = bitrate,
    trickplay = trickplay,
)

/** A controller on fakes and virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class Harness(
    preferences: PlaybackPreferences = PlaybackPreferences(),
    lookups: PlaybackLookups = PlaybackLookups.None,
    profileProvider: ProfileProvider = ProfileProvider { TV_PROFILE },
    quality: StreamQuality = StreamQuality.Original,
) {
    val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    val scope = TestScope(dispatcher)
    val clock = TestClock(scheduler)
    val gateway = FakeGateway(clock)
    val prefs = FakePreferences(preferences).also { it.quality.value = quality }
    val player = FakeMediaPlayer(clock)
    val watchNext = FakeWatchNext()
    val visibility = FakeVisibility()
    val stillWatching = StillWatching(clock)

    /** On mobile data (a metered network). */
    var metered = false
    val controller = RealPlaybackController(
        gateway = gateway,
        preferences = prefs,
        client = CLIENT,
        profileProvider = profileProvider,
        playerFactory = { player },
        stillWatching = stillWatching,
        watchNext = watchNext,
        sessions = MediaSessionHost.None,
        visibility = visibility,
        lookups = lookups,
        network = { metered },
        clock = clock,
        dispatcher = dispatcher,
        io = dispatcher,
    )
    val events = mutableListOf<PlayerEvent>()

    init {
        scope.backgroundScope.launch { controller.events.collect { events += it } }
        scope.runCurrent()
    }

    val state: PlayerUiState get() = controller.state.value

    val progress: PlaybackProgress get() = controller.progress.value

    val states: List<PlayState> get() = gateway.reports.map { it.state }

    fun run() = scope.runCurrent()

    fun advance(ms: Long) {
        scope.advanceTimeBy(ms)
        scope.runCurrent()
    }

    /** Starts [request] with every decision request answered by [answer]. */
    fun start(request: PlayRequest = PlayRequest(1, null, "Harbor Lights", "S03 E07 · The Long Dark"), answer: (DecisionRequest) -> PlaybackDecision) {
        gateway.decide = answer
        controller.start(request)
        run()
    }

    fun start(request: PlayRequest = PlayRequest(1, null, "Harbor Lights", "S03 E07 · The Long Dark"), d: PlaybackDecision) = start(request) { d }

    fun release() {
        controller.release()
        run()
    }
}
