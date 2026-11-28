package media.watchmy.client.fake

import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import media.watchmy.client.model.AudioOption
import media.watchmy.client.model.loweredNote
import media.watchmy.client.model.raisedNote
import media.watchmy.client.model.skipLabel
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.PlaybackController
import media.watchmy.client.model.PlaybackMode
import media.watchmy.client.model.PlaybackProgress
import media.watchmy.client.model.PlayerEvent
import media.watchmy.client.model.PlayerStatus
import media.watchmy.client.model.PlayerUiState
import media.watchmy.client.model.Segment
import media.watchmy.client.model.SegmentKind
import media.watchmy.client.model.SkipPrompt
import media.watchmy.client.model.StreamQuality
import media.watchmy.client.model.GrowingRecording
import media.watchmy.client.model.StreamVideo
import media.watchmy.client.model.SubtitleOption
import media.watchmy.client.model.SubtitleSource
import media.watchmy.client.model.Trickplay
import media.watchmy.client.model.UpNext
import media.watchmy.client.model.UpNextPrompt
import media.watchmy.client.model.UP_NEXT_COUNTDOWN_MS
import media.watchmy.client.model.episodeCode
import media.watchmy.client.model.PlayerSound
import media.watchmy.client.model.PreferencesRepository
import media.watchmy.client.model.SoundFailure
import media.watchmy.client.model.SoundLevel
import media.watchmy.client.model.SoundSetting
import media.watchmy.client.model.SubtitleTimingState
import media.watchmy.client.model.TIMING_SETTLE_MS
import media.watchmy.client.model.movedTiming

/**
 * A pretend player for building the player screens without a server: a 52
 * minute episode with an intro from 1:00 to 1:45 and final credits from
 * 49:30, ticking in real time (or faster, with [speed]). It offers Skip
 * Intro, then the Next Episode card (the season finale) with its 10 s
 * countdown, and seek thumbnails ([trickplay]; null for a server without
 * them). No video: [player] stays null, so screens draw the backdrop
 * instead. A request without a title plays as Harbor Lights S03 E07.
 */
class FakePlaybackController(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val speed: Float = 1f,
    private val trickplay: Trickplay? = FakeData.trickplay,
    /** The person's sound settings, which Sound saves to; null keeps them in the fake, Off to start. */
    private val preferences: PreferencesRepository? = null,
    /** The server can apply the sound and save subtitle timings ([SOUND_API_LEVEL]). */
    private val soundAndTiming: Boolean = true,
) : PlaybackController {
    private val durationMs = 52 * 60_000L
    private val intro = Segment(SegmentKind.Intro, 60_000, 105_000)
    private val credits = Segment(SegmentKind.Credits, 49 * 60_000L + 30_000, null, final = true)
    private val next = UpNext(9102, 1308, 100, "Harbor Lights", 3, 8, "Squall Line", null, runtimeMs = 48 * 60_000L, seasonFinale = true)

    private val _state = MutableStateFlow(PlayerUiState())
    override val state: StateFlow<PlayerUiState> = _state.asStateFlow()
    private val _progress = MutableStateFlow(PlaybackProgress())
    override val progress: StateFlow<PlaybackProgress> = _progress.asStateFlow()
    private val _events = MutableSharedFlow<PlayerEvent>(extraBufferCapacity = 4)
    override val events: SharedFlow<PlayerEvent> = _events
    override val player: StateFlow<Player?> = MutableStateFlow(null)

    private var ticker: Job? = null
    private var creditsDismissed = false
    private var growing: GrowingRecording? = null
    private fun currentDuration() = growing?.durationAt(System.currentTimeMillis()) ?: durationMs

    override fun start(request: PlayRequest) {
        val named = request.title.isNotBlank()
        growing = request.recordingId?.let { GrowingRecording(System.currentTimeMillis() - 23 * 60_000, System.currentTimeMillis() + 37 * 60_000).anchored(23 * 60_000, System.currentTimeMillis()) }
        _state.value = PlayerUiState(
            status = PlayerStatus.Loading,
            title = if (named) request.title else "Harbor Lights",
            subtitle = if (named) request.subtitle else "S03 E07 · The Long Dark",
            backdropPath = request.backdropPath,
            nextEpisode = next.takeIf { request.recordingId == null && request.fileId != next.fileId },
            growing = growing,
            canCast = request.recordingId == null,
            durationMs = currentDuration(), segments = listOf(if (growing != null) intro.copy(kind = SegmentKind.Commercial) else intro, credits),
            audio = listOf(
                AudioOption(1, "English · EAC3 5.1 (Default)", "en", true),
                AudioOption(2, "English · AAC 2.0 · Commentary", "en", false),
                AudioOption(3, "Spanish · AAC 2.0", "es", false),
            ),
            selectedAudio = 1,
            subtitles = listOf(
                SubtitleOption("s4", "English", "en", false, false, "SRT"),
                SubtitleOption("s5", "English (SDH)", "en", false, false, "SRT"),
                SubtitleOption("s6", "Spanish", "es", false, false, "ASS"),
                SubtitleOption("s7", "English", "en", false, true, "PGS"),
            ),
            mode = PlaybackMode.Remux, reasons = listOf("client cannot play the mkv container"),
            video = StreamVideo(3840, 2160, "HDR10"),
            // A device that never picked a quality, on a server that does Automatic, at the original.
            quality = StreamQuality.Auto, qualities = StreamQuality.offered(auto = true),
            autoStep = StreamQuality.Original, bitrate = 48_000_000,
            trickplay = trickplay,
            sound = sound().takeIf { soundAndTiming },
        )
        timings.clear()
        timings.putAll(STARTING_TIMINGS)
        _progress.value = PlaybackProgress(request.startMs ?: if (growing != null) 0 else 50_000, currentDuration())
        creditsDismissed = false
        ticker?.cancel()
        ticker = scope.launch {
            delay(600)
            _state.update { it.copy(status = PlayerStatus.Playing) }
            // The online search answers once the video plays, as with a server.
            launch {
                delay(800)
                _state.update { it.copy(online = FakeData.onlineSubtitles) }
            }
            while (isActive) {
                delay(250)
                val s = _state.value
                if (s.status == PlayerStatus.Playing) advance((250 * speed).toLong())
                updatePrompts()
            }
        }
    }

    private fun advance(ms: Long) {
        val duration = currentDuration()
        // Its end passed: the Recording label and Go to now go, and it plays on.
        if (growing?.endedAt(System.currentTimeMillis()) == true) growing = null
        val pos = (_progress.value.positionMs + ms).coerceAtMost(duration)
        _progress.value = PlaybackProgress(pos, duration, bufferedMs = 30_000)
        _state.update { it.copy(durationMs = duration, growing = growing) }
        if (pos >= duration) _state.update { it.copy(status = PlayerStatus.Ended) }
    }

    private fun updatePrompts() {
        val pos = _progress.value.positionMs
        _state.update { s ->
            val inIntro = pos in intro.startMs until (intro.endMs ?: Long.MAX_VALUE) && (intro.endMs!! - pos) >= 3_000
            val segment = if (growing != null) intro.copy(kind = SegmentKind.Commercial) else intro
            val skip = if (inIntro) SkipPrompt(segment, skipLabel(segment.kind), null) else null
            val showNext = s.nextEpisode != null && !creditsDismissed && pos >= credits.startMs
            val prevCountdown = s.upNext?.countdownMs ?: UP_NEXT_COUNTDOWN_MS
            val countdown = if (s.status == PlayerStatus.Playing) (prevCountdown - (250 * speed).toLong()).coerceAtLeast(0) else prevCountdown
            s.copy(skip = skip, upNext = if (showNext) UpNextPrompt(s.nextEpisode!!, countdown, UP_NEXT_COUNTDOWN_MS) else null)
        }
        val s = _state.value
        if (s.upNext?.countdownMs == 0L) playNext()
        if (s.status == PlayerStatus.Ended && s.upNext == null) _events.tryEmit(PlayerEvent.Exit)
    }

    override fun play() = _state.update { it.copy(status = PlayerStatus.Playing) }

    override fun pause() = _state.update { it.copy(status = PlayerStatus.Paused) }

    override fun togglePlayPause() = if (_state.value.status == PlayerStatus.Playing) pause() else play()

    override fun seekTo(positionMs: Long) {
        if (positionMs < credits.startMs) creditsDismissed = false
        _progress.update { it.copy(positionMs = positionMs.coerceIn(0, durationMs)) }
        updatePrompts()
    }

    override fun seekBy(deltaMs: Long) = seekTo(_progress.value.positionMs + deltaMs)

    override fun skip() {
        val end = _state.value.skip?.segment?.endMs ?: return
        seekTo(end)
    }

    override fun playNext() {
        val n = _state.value.nextEpisode ?: return
        _events.tryEmit(PlayerEvent.StartedNext(n.fileId))
        start(PlayRequest(n.fileId, 0, n.showTitle, "${episodeCode(n.season, n.episode)} · ${n.title}"))
    }

    override fun watchCredits() {
        creditsDismissed = true
        _state.update { it.copy(upNext = null) }
    }

    override fun answerStillWatching(keepWatching: Boolean) {
        _state.update { it.copy(askStillWatching = false) }
        if (!keepWatching) _events.tryEmit(PlayerEvent.Exit)
    }

    override fun selectAudio(index: Int) = _state.update { it.copy(selectedAudio = index) }

    override fun selectSubtitle(id: String?) = _state.update { it.copy(selectedSubtitle = id, timing = timingOf(id)) }

    // --- sound and subtitle timing, as the server would answer ---

    private var levels = mapOf(SoundSetting.DialogueBoost to SoundLevel.Off, SoundSetting.ReduceLoudSounds to SoundLevel.Off)
    private var soundBusy: SoundSetting? = null
    private var soundFailure: SoundFailure? = null

    private fun sound(): PlayerSound {
        val audio = preferences?.playback?.value?.audio
        val dialogue = audio?.dialogueBoost ?: levels.getValue(SoundSetting.DialogueBoost)
        val loud = audio?.reduceLoudSounds ?: levels.getValue(SoundSetting.ReduceLoudSounds)
        return PlayerSound(dialogue, loud, busy = soundBusy, failed = soundFailure)
    }

    override fun setSound(setting: SoundSetting, level: SoundLevel) {
        if (!soundAndTiming || soundBusy != null) return
        val still = sound().level(setting)
        if (still == level && soundFailure?.setting != setting) return
        soundBusy = setting
        soundFailure = null
        levels = levels + (setting to level)
        _state.update { it.copy(sound = sound()) }
        scope.launch {
            val saved = runCatching { preferences?.setSound(setting, level) }.isSuccess
            if (!saved) {
                levels = levels + (setting to still)
                soundBusy = null
                soundFailure = SoundFailure(setting, level, still)
                _state.update { it.copy(sound = sound()) }
                return@launch
            }
            // The play loads again at the same place with the new sound.
            _state.update { it.copy(sound = sound(), status = PlayerStatus.Buffering) }
            delay(900)
            soundBusy = null
            _state.update { it.copy(sound = sound(), status = PlayerStatus.Playing) }
        }
    }

    /** Each track's timing: its ms, whether it is the shared one, the person's own, and the shared one behind it. */
    private data class FakeTiming(val ms: Long, val shared: Boolean = false, val own: Boolean = false, val sharedMs: Long = 0, val savedAll: Boolean = false)

    private val timings = mutableMapOf<String, FakeTiming>()
    private var timingSave: Job? = null

    private fun timingOf(id: String?): SubtitleTimingState? {
        if (!soundAndTiming || id == null) return null
        val t = timings[id] ?: FakeTiming(0)
        return SubtitleTimingState(
            trackId = id, ms = t.ms, shared = t.shared, own = t.own, canReset = t.own && t.ms != t.sharedMs,
            savedForEveryone = t.savedAll, burnedIn = _state.value.subtitles.firstOrNull { it.id == id }?.burnInOnly == true,
        )
    }

    private fun showTiming() = _state.update { it.copy(timing = timingOf(it.selectedSubtitle)) }

    override fun moveSubtitleTiming(deltaMs: Long) {
        val id = _state.value.selectedSubtitle ?: return
        val t = timings[id] ?: FakeTiming(0)
        val ms = movedTiming(t.ms, deltaMs)
        if (ms == t.ms) return
        timings[id] = t.copy(ms = ms, shared = false, own = true, savedAll = false)
        showTiming()
        timingSave?.cancel()
        timingSave = scope.launch { delay(TIMING_SETTLE_MS) }
    }

    override fun resetSubtitleTiming() {
        val id = _state.value.selectedSubtitle ?: return
        val t = timings[id] ?: return
        timings[id] = FakeTiming(t.sharedMs, shared = t.sharedMs != 0L, sharedMs = t.sharedMs)
        showTiming()
    }

    override fun saveSubtitleTimingForEveryone() {
        val id = _state.value.selectedSubtitle ?: return
        val t = timings[id] ?: return
        timings[id] = FakeTiming(t.ms, shared = t.ms != 0L, sharedMs = t.ms, savedAll = true)
        showTiming()
    }

    override fun showSubtitleTiming(timingMs: Long) {
        val id = _state.value.selectedSubtitle ?: return
        timings[id] = (timings[id] ?: FakeTiming(0)).copy(ms = timingMs, own = true, shared = false)
        showTiming()
    }

    override fun downloadSubtitle(resultId: String) {
        val online = _state.value.online ?: return
        val result = online.results.firstOrNull { it.id == resultId } ?: return
        result.trackId?.let { return selectSubtitle(it) }
        if (online.downloading != null) return
        _state.update { it.copy(online = online.copy(downloading = resultId, error = null)) }
        scope.launch {
            delay(1_200)
            val id = "d$resultId"
            val track = SubtitleOption(id, result.label, result.language, result.forced, false, "SRT", SubtitleSource.Online, result.release, result.hearingImpaired)
            _state.update { s ->
                val o = s.online ?: online
                s.copy(
                    subtitles = s.subtitles + track,
                    selectedSubtitle = id,
                    online = o.copy(
                        downloading = null,
                        results = o.results.map { if (it.id == resultId) it.copy(trackId = id) else it },
                        remaining = o.remaining?.let { (it - 1).coerceAtLeast(0) },
                    ),
                )
            }
        }
    }

    override fun selectQuality(quality: StreamQuality) = _state.update {
        val original = quality == StreamQuality.Original || quality == StreamQuality.Auto
        it.copy(
            quality = quality,
            autoStep = StreamQuality.Original.takeIf { quality == StreamQuality.Auto },
            mode = if (original) PlaybackMode.Remux else PlaybackMode.Transcode,
            video = if (original) StreamVideo(3840, 2160, "HDR10") else null,
        )
    }

    /**
     * What Automatic does when the connection can't keep up, for the harnesses:
     * the server's step down and the note that comes with it.
     */
    fun lowerTo(step: StreamQuality) = _state.update {
        if (it.quality != StreamQuality.Auto) return@update it
        it.copy(
            autoStep = step, bitrate = step.maxBitrate ?: 0, mode = PlaybackMode.Transcode,
            video = StreamVideo(step.maxWidth ?: 1280, step.maxHeight ?: 720, null),
            note = loweredNote(step),
        )
    }

    /** A recovered link raises an Automatic play without changing the saved pick. */
    fun raiseTo(step: StreamQuality) = _state.update {
        if (it.quality != StreamQuality.Auto) return@update it
        val original = step == StreamQuality.Original
        it.copy(
            autoStep = step,
            bitrate = if (original) 48_000_000 else step.maxBitrate ?: 0,
            mode = if (original) PlaybackMode.Direct else PlaybackMode.Transcode,
            video = if (original) StreamVideo(3840, 2160, "HDR10")
                else StreamVideo(step.maxWidth ?: 1280, step.maxHeight ?: 720, null),
            note = raisedNote(step),
        )
    }

    /** Clears [lowerTo]'s note. */
    fun clearNote() = _state.update { it.copy(note = null) }

    override fun noteUserInput() = Unit

    private companion object {
        /** The English SDH track has a timing an admin saved for everyone. */
        val STARTING_TIMINGS = mapOf("s5" to FakeTiming(800, shared = true, sharedMs = 800))
    }

    override fun stop() {
        ticker?.cancel()
    }

    override fun release() {
        stop()
        scope.cancel()
    }
}
