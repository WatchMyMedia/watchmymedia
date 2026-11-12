package media.watchmy.client.core.cast

import androidx.media3.common.Player
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.AudioOption
import media.watchmy.client.model.CastGateway
import media.watchmy.client.model.ClientInfo
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.PlaybackController
import media.watchmy.client.model.PlaybackMode
import media.watchmy.client.model.PlaybackProgress
import media.watchmy.client.model.PlayerEvent
import media.watchmy.client.model.PlayerStatus
import media.watchmy.client.model.PlayerUiState
import media.watchmy.client.model.PreferencesRepository
import media.watchmy.client.model.Segment
import media.watchmy.client.model.SegmentKind
import media.watchmy.client.model.SkipPrompt
import media.watchmy.client.model.StreamQuality
import media.watchmy.client.model.SubtitleOption
import media.watchmy.client.model.UpNext
import media.watchmy.client.model.UpNextPrompt
import media.watchmy.client.model.audioTrackLabel
import media.watchmy.client.model.subtitleTrackLabel

/**
 * The TV's play as a remote (PLAN-CAST.md §5.2): the receiver runs the play
 * and its rules (heartbeats, Skip, Next Episode, Still watching); this only
 * turns its MEDIA_STATUS and `state` messages into [PlayerUiState] and the
 * viewer's taps into commands, so the phone's player screen renders it as
 * it renders the local player. One for the app; [attach] and [detach]
 * follow the Cast session.
 *
 * Everything runs on [dispatcher] (the main thread in the app, where the Cast
 * SDK calls back); server calls run on [io].
 */
class CastPlaybackController(
    private val gateway: CastGateway,
    private val preferences: PreferencesRepository,
    /** This phone, named in LOAD as who cast. */
    private val client: () -> ClientInfo,
    /** The current server's address for an artwork path (LibraryRepository.imageUrl at w1280). */
    private val imageUrl: (String?) -> String?,
    private val elapsedMs: () -> Long = { System.nanoTime() / 1_000_000 },
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : PlaybackController {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val _state = MutableStateFlow(PlayerUiState())
    override val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val _progress = MutableStateFlow(PlaybackProgress())
    override val progress: StateFlow<PlaybackProgress> = _progress.asStateFlow()

    private val _events = MutableSharedFlow<PlayerEvent>(extraBufferCapacity = 8)
    override val events: Flow<PlayerEvent> = _events.asSharedFlow()

    override val player: StateFlow<Player?> = MutableStateFlow<Player?>(null).asStateFlow()

    private val _nowPlaying = MutableStateFlow<PlayRequest?>(null)

    /** What the TV is playing, as a request that reopens it; null when nothing is. */
    val nowPlaying: StateFlow<PlayRequest?> = _nowPlaying.asStateFlow()

    private var link: ReceiverLink? = null
    private var request: PlayRequest? = null
    private var remote: StateMessage? = null
    private var status: ReceiverStatus? = null
    private var loading = false
    private var error: String? = null
    private var loadJob: Job? = null
    private var lastInputAt = Long.MIN_VALUE / 2

    /** A seek not yet reflected in MEDIA_STATUS: the scrubber shows it meanwhile. */
    private var pendingSeek: Long? = null

    /** Automatic's switch note while it shows, and what clears it. */
    private var note: String? = null
    private var noteJob: Job? = null

    private val listener = object : ReceiverLink.Listener {
        override fun onStatus(status: ReceiverStatus) = onReceiverStatus(status)

        override fun onMessage(message: String) {
            CastProtocol.decodeState(message)?.let(::onReceiverState)
        }
    }

    /** A Cast session started or resumed. */
    fun attach(link: ReceiverLink) {
        if (this.link === link) return
        this.link?.setListener(null)
        this.link = link
        link.setListener(listener)
        link.status?.let(::onReceiverStatus)
        link.send(CastCommand.GetState.encode())
        render()
    }

    /** The session ended: the last state stays (for "Continue on this phone"), and nothing is playing. */
    fun detach() {
        loadJob?.cancel()
        loading = false
        link?.setListener(null)
        link = null
        _nowPlaying.value = null
        render()
    }

    val connected: Boolean get() = link != null

    // --- commands ---

    override fun start(request: PlayRequest) {
        val l = link ?: run {
            error = "Not connected to a TV."
            render()
            return
        }
        // Already on the TV: open the remote on it rather than starting again.
        if (request.startMs == null && currentFileId() == request.fileId && remote?.phase != StateMessage.ENDED) {
            this.request = merged(request)
            _nowPlaying.value = this.request
            render()
            return
        }
        this.request = request
        remote = null
        status = null
        error = null
        pendingSeek = null
        loading = true
        _nowPlaying.value = request
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
                    fileId = request.fileId,
                    startMs = request.startMs,
                    audioIndex = request.audioIndex,
                    subtitleId = request.subtitleId,
                    subtitleMode = request.subtitleMode.id,
                    quality = preferences.quality.value.id,
                    device = LoadDevice(l.deviceId, l.deviceName),
                    sender = LoadSender(client().name),
                )
                // LOAD metadata is echoed in status by older receivers. Omit private artwork.
                val image: String? = null
                l.load(LoadRequest(request.fileId, request.title, request.subtitle, image, data))
                loading = false
                render()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                loading = false
                error = (e as? ApiException)?.message?.let { "Couldn't cast: $it" } ?: "Couldn't cast to the TV."
                render()
            }
        }
    }

    override fun play() {
        noteUserInput()
        link?.play()
    }

    override fun pause() {
        noteUserInput()
        link?.pause()
    }

    override fun togglePlayPause() {
        when (_state.value.status) {
            PlayerStatus.Playing, PlayerStatus.Buffering -> pause()
            else -> play()
        }
    }

    override fun seekTo(positionMs: Long) {
        val target = positionMs.coerceIn(0, _state.value.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)
        pendingSeek = target
        send(CastCommand.Seek(target))
        render()
    }

    override fun seekBy(deltaMs: Long) = seekTo(position() + deltaMs)

    override fun skip() = send(CastCommand.Skip)

    override fun playNext() = send(CastCommand.PlayNext)

    override fun watchCredits() = send(CastCommand.WatchCredits)

    override fun answerStillWatching(keepWatching: Boolean) = send(CastCommand.StillWatching(keepWatching))

    override fun selectAudio(index: Int) = send(CastCommand.Audio(index))

    override fun selectSubtitle(id: String?) = send(CastCommand.Subtitle(id))

    override fun selectQuality(quality: StreamQuality) {
        send(CastCommand.Quality(quality.id))
        // Remembered on this phone, as for its own player.
        scope.launch { runCatching { preferences.setQuality(quality) } }
    }

    /** Throttled: the player screen calls this on every touch. */
    override fun noteUserInput() {
        val now = elapsedMs()
        if (now - lastInputAt < INPUT_EVERY_MS) return
        lastInputAt = now
        link?.send(CastCommand.Input.encode())
    }

    /** Stops the TV's play; leaving the remote screen does not call this. */
    override fun stop() {
        link?.stop()
    }

    /** One remote for the app: the cast service owns it, so a screen's release does nothing. */
    override fun release() {}

    private fun send(command: CastCommand) {
        lastInputAt = elapsedMs()
        link?.send(command.encode())
    }

    // --- the receiver's side ---

    private fun onReceiverStatus(s: ReceiverStatus) {
        val before = status
        status = s
        if (pendingSeek != null && before != null && s.positionMs != before.positionMs) pendingSeek = null
        if (s.phase == ReceiverPhase.Idle && s.idleReason == IdleReason.Finished && before?.phase != ReceiverPhase.Idle && remote?.nextEpisode == null) {
            _events.tryEmit(PlayerEvent.Exit)
        }
        render()
    }

    private fun onReceiverState(m: StateMessage) {
        // A Live TV channel is the live remote's (LiveCastController): no file is on the TV.
        if (m.live != null) {
            remote = null
            request = null
            loading = false
            _nowPlaying.value = null
            render()
            return
        }
        val before = remote
        remote = m
        loading = false
        noteStepChange(before, m)
        if (m.phase == StateMessage.IDLE) _nowPlaying.value = null
        val fileId = m.fileId
        if (fileId != null && m.phase != StateMessage.IDLE) {
            val current = request
            val next = if (current?.fileId == fileId) merged(current) else PlayRequest(fileId, title = m.title.orEmpty(), subtitle = m.subtitle, backdropPath = current?.backdropPath)
            request = next
            _nowPlaying.value = next
            if (before?.fileId != null && before.fileId != fileId) {
                pendingSeek = null
                _events.tryEmit(PlayerEvent.StartedNext(fileId))
            }
        }
        if (m.phase != before?.phase && (m.phase == StateMessage.ENDED || m.phase == StateMessage.STOPPED)) {
            _events.tryEmit(PlayerEvent.Exit)
        }
        render()
    }

    /** Reflect the receiver's Automatic step change on the phone for a few seconds. */
    private fun noteStepChange(before: StateMessage?, m: StateMessage) {
        if (before == null || m.quality != StreamQuality.Auto.id || before.fileId != m.fileId) return
        val was = StreamQuality.byId(before.autoStep) ?: return
        val now = StreamQuality.byId(m.autoStep) ?: return
        if (now == was) return
        note = when {
            now.ordinal > was.ordinal -> "Lowered to ${now.label.substringBefore(" · ")} for your connection."
            now == StreamQuality.Original -> "Connection recovered; playing the original file."
            else -> "Raised to ${now.label.substringBefore(" · ")} for your connection."
        }
        noteJob?.cancel()
        noteJob = scope.launch {
            delay(NOTE_MS)
            note = null
            render()
        }
    }

    private fun merged(r: PlayRequest): PlayRequest {
        val m = remote ?: return r
        return r.copy(title = m.title?.takeIf { it.isNotBlank() } ?: r.title, subtitle = m.subtitle ?: r.subtitle)
    }

    private fun currentFileId(): Long? = remote?.fileId?.takeIf { remote?.phase != StateMessage.IDLE } ?: status?.fileId

    /** The file position now. */
    private fun position(): Long {
        pendingSeek?.let { return it }
        val s = status ?: return request?.startMs ?: 0
        return (remote?.offsetMs ?: 0) + s.positionMs
    }

    private fun render() {
        val r = remote
        val s = status
        val req = request
        val durationMs = r?.durationMs?.takeIf { it > 0 } ?: s?.durationMs ?: 0
        val quality = StreamQuality.byId(r?.quality) ?: preferences.quality.value
        val segments = r?.segments.orEmpty().mapNotNull { it.toModel() }
        val shownError = error ?: r?.error?.takeIf { r.phase == StateMessage.ERROR }
        val status = when {
            shownError != null -> PlayerStatus.Error
            loading -> PlayerStatus.Loading
            r?.phase == StateMessage.ENDED -> PlayerStatus.Ended
            r?.phase == StateMessage.LOADING -> PlayerStatus.Loading
            s == null -> PlayerStatus.Loading
            else -> when (s.phase) {
                ReceiverPhase.Playing -> PlayerStatus.Playing
                ReceiverPhase.Paused -> PlayerStatus.Paused
                ReceiverPhase.Buffering -> PlayerStatus.Buffering
                ReceiverPhase.Loading -> PlayerStatus.Loading
                ReceiverPhase.Idle -> when (s.idleReason) {
                    IdleReason.Finished -> PlayerStatus.Ended
                    IdleReason.Error -> PlayerStatus.Error
                    else -> if (r?.phase == StateMessage.PLAYING) PlayerStatus.Paused else PlayerStatus.Loading
                }
            }
        }
        _progress.value = PlaybackProgress(position().coerceIn(0, durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE), durationMs)
        _state.value = PlayerUiState(
            status = status,
            title = r?.title?.takeIf { it.isNotBlank() } ?: req?.title.orEmpty(),
            subtitle = r?.subtitle ?: req?.subtitle,
            backdropPath = req?.backdropPath,
            durationMs = durationMs,
            segments = segments,
            skip = r?.skip?.let { skip -> skipPrompt(skip, segments) },
            nextEpisode = r?.nextEpisode?.toModel(),
            upNext = r?.upNext?.let { UpNextPrompt(it.toModel(), countdownMs = it.secondsLeft?.let { sec -> sec * 1000L }) },
            askStillWatching = r?.stillWatching == true,
            audio = r?.audio.orEmpty().map { it.toModel() },
            selectedAudio = r?.selectedAudio,
            subtitles = r?.subtitles.orEmpty().map { it.toModel() },
            selectedSubtitle = r?.selectedSubtitle,
            quality = quality,
            qualities = StreamQuality.offered(preferences.autoQuality.value),
            autoStep = StreamQuality.byId(r?.autoStep)?.takeIf { quality == StreamQuality.Auto && it != StreamQuality.Auto },
            mode = r?.mode?.let(::playbackMode),
            note = note,
            stopMessage = r?.stopMessage?.takeIf { r.phase == StateMessage.STOPPED }
                ?: if (r?.phase == StateMessage.STOPPED) "Playback was stopped by the server's admin." else null,
            error = shownError ?: if (status == PlayerStatus.Error) "The TV couldn't play this." else null,
        )
    }

    companion object {
        /** Input reaches the receiver at most this often. */
        const val INPUT_EVERY_MS = 5_000L

        /** How long Automatic's switch note shows. */
        const val NOTE_MS = 5_000L

        internal fun withToken(url: String, token: String) = url + (if ('?' in url) "&" else "?") + "access_token=" + token

        internal fun segmentKind(kind: String): SegmentKind? = when (kind) {
            "intro" -> SegmentKind.Intro
            "recap" -> SegmentKind.Recap
            "credits" -> SegmentKind.Credits
            "preview" -> SegmentKind.Preview
            "commercial" -> SegmentKind.Commercial
            else -> null
        }

        internal fun SegmentKind.wireName(): String = name.lowercase()

        private fun skipLabel(kind: SegmentKind): String = when (kind) {
            SegmentKind.Intro -> "Skip Intro"
            SegmentKind.Recap -> "Skip Recap"
            SegmentKind.Credits -> "Skip Credits"
            SegmentKind.Preview -> "Skip Preview"
            SegmentKind.Commercial -> "Skip Ad"
        }

        private fun playbackMode(mode: String): PlaybackMode? = when (mode) {
            "direct" -> PlaybackMode.Direct
            "remux" -> PlaybackMode.Remux
            "audio" -> PlaybackMode.AudioTranscode
            "transcode" -> PlaybackMode.Transcode
            else -> null
        }

        private fun SegmentInfo.toModel() = segmentKind(kind)?.let { Segment(it, startMs, endMs, final == true) }

        private fun skipPrompt(skip: SkipInfo, segments: List<Segment>): SkipPrompt? {
            val kind = segmentKind(skip.kind) ?: return null
            val segment = segments.firstOrNull { it.kind == kind && it.endMs == skip.endMs } ?: Segment(kind, 0, skip.endMs)
            return SkipPrompt(segment, skipLabel(kind), autoProgress = null)
        }

        private fun UpNextInfo.toModel() = UpNext(
            fileId = fileId,
            episodeId = 0,
            showId = 0,
            showTitle = showTitle,
            season = seasonNumber,
            episode = episodeNumber,
            title = title,
            stillPath = stillPathOf(stillUrl),
        )

        /**
         * The artwork path in an image URL (".../api/images/w300/abc.jpg?access_token=…" → "/abc.jpg"),
         * so the screens size it as they do the server's own paths.
         */
        internal fun stillPathOf(url: String?): String? {
            val path = url?.substringBefore('?') ?: return null
            val at = path.indexOf("/api/images/").takeIf { it >= 0 } ?: return null
            val rest = path.substring(at + "/api/images/".length)
            return rest.substringAfter('/', "").takeIf { it.isNotBlank() }?.let { "/$it" }
        }

        // A receiver that names tracks itself (the TV app) sends the label as the title, with no codec.
        private fun AudioInfo.toModel() = AudioOption(
            index = index,
            label = if (codec.isBlank()) title ?: language ?: "Track ${index + 1}" else audioTrackLabel(language, codec, channels, title, default),
            language = language,
            isDefault = default,
        )

        private fun SubtitleInfo.toModel() = SubtitleOption(
            id = id,
            label = subtitleTrackLabel(language, title, forced == true),
            language = language,
            forced = forced == true,
            burnInOnly = burnInOnly == true || format == "image",
            format = if (format == "ass") "ASS" else null,
        )
    }
}
