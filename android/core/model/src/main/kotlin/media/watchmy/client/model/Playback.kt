package media.watchmy.client.model

import androidx.media3.common.Player
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** The season containing a playing file, independent of its display title. */
data class EpisodeContext(val showId: Long, val season: Int)

/** What to play. */
data class PlayRequest(
    val fileId: Long,
    /** File position to start at; null resumes where the user left off. */
    val startMs: Long? = null,
    val title: String,
    /** "S03 E07 · The Long Dark". */
    val subtitle: String? = null,
    val backdropPath: String? = null,
    /** The audio track to start with (ffprobe index), for a play handed over between this phone and a TV; null is the default. */
    val audioIndex: Int? = null,
    /** The subtitle to start with, likewise; null is none. */
    val subtitleId: String? = null,
    /** Optional on older/local callers; the tablet player resolves it through LibraryRepository. */
    val episodeContext: EpisodeContext? = null,
    val subtitleMode: SubtitleRequestMode = SubtitleRequestMode.Auto,
    val recordingId: String? = null,
)

/**
 * A skippable part of the file. [endMs] null runs to the end. [final] marks
 * the last credits block, after which nothing but previews follows.
 */
data class Segment(val kind: SegmentKind, val startMs: Long, val endMs: Long?, val final: Boolean = false)

/** The episode after this one, for the Next Episode card and the controls' Next episode button. */
data class UpNext(
    val fileId: Long,
    val episodeId: Long,
    val showId: Long,
    val showTitle: String,
    val season: Int,
    val episode: Int,
    val title: String,
    val stillPath: String?,
    /** The episode's length (its file's, else TMDB's runtime); null until looked up. */
    val runtimeMs: Long? = null,
    /** The last episode of its season. */
    val seasonFinale: Boolean = false,
)

data class AudioOption(
    /** ffprobe stream index, sent back as audioIndex. */
    val index: Int,
    /** "English · TrueHD 7.1 (Default)". */
    val label: String,
    val language: String?,
    val isDefault: Boolean,
    /** "aac", "eac3": what the offline choice reads to prefer a track this device plays as it is. */
    val codec: String? = null,
    val channels: Int = 0,
    /** The file's own title for the track ("Director's Commentary"); null when it has none. */
    val title: String? = null,
)

data class SubtitleOption(
    /** "s3" (embedded), "x0" (sidecar file) or "d12" (downloaded from OpenSubtitles). */
    val id: String,
    val label: String,
    val language: String?,
    val forced: Boolean,
    /** Image subtitles (PGS) are burned in, which restarts the stream. */
    val burnInOnly: Boolean,
    /** "SRT", "ASS", "PGS"; null when unknown. */
    val format: String? = null,
    val source: SubtitleSource = SubtitleSource.Embedded,
    /** A download's release name ("Harbor.Lights.S03E07.1080p.WEB"). */
    val release: String? = null,
    val hearingImpaired: Boolean = false,
)

/** Where a subtitle track comes from: the file itself, a file next to it, or OpenSubtitles. */
enum class SubtitleSource { Embedded, Sidecar, Online }

/** What the server must show where subtitles from OpenSubtitles are picked, when it does not say. */
const val OPEN_SUBTITLES_CREDIT = "Subtitles provided by OpenSubtitles.com"

/** A subtitle found online for the file playing, not downloaded yet unless [trackId] is set. */
data class OnlineSubtitle(
    /** What to download it by. */
    val id: String,
    val language: String?,
    /** "English", "Portuguese (Brazil)". */
    val label: String,
    /** The release it was made for. */
    val release: String,
    /** Made for this exact file, so in sync. */
    val hashMatch: Boolean,
    val hearingImpaired: Boolean,
    /** Only the foreign-language parts. */
    val forced: Boolean,
    val aiTranslated: Boolean,
    val downloads: Long,
    /** The subtitle track to select when it was downloaded for this file before. */
    val trackId: String? = null,
)

/**
 * The online subtitles for the file playing (GET
 * /api/files/{id}/subtitles/search), and what this player is doing with them.
 */
data class OnlineSubtitles(
    val results: List<OnlineSubtitle> = emptyList(),
    /** The credit to show under them. */
    val attribution: String = OPEN_SUBTITLES_CREDIT,
    /** This person may download (a server can keep downloads to admins). */
    val canDownload: Boolean = true,
    /** Downloads left today; null when not known. */
    val remaining: Int? = null,
    /** When downloads resume, epoch ms; null when not known. */
    val resetAtMs: Long? = null,
    /** The result being downloaded. */
    val downloading: String? = null,
    /** Why the search or the last download failed. */
    val error: String? = null,
)

/** How the server is sending the file. */
enum class PlaybackMode { Direct, Remux, AudioTranscode, Transcode }

/**
 * The video as the server sends it: the file's own in direct play and remux,
 * the transcode's size otherwise.
 */
data class StreamVideo(
    val width: Int,
    val height: Int,
    /** "HDR10", "HLG", "Dolby Vision"; null for SDR, or HDR mapped to SDR. */
    val hdr: String?,
) {
    /** "4K", "1080p", "720p" or "SD". */
    val quality: QualityLabel get() = qualityFor(width, height)
}

/**
 * The server's labels for a frame (4K from 2000 lines, 1080p from 1000,
 * 720p from 700), except that a wide one counts by its width, so a
 * 3840×1600 scope film is 4K.
 */
fun qualityFor(width: Int, height: Int): QualityLabel = when {
    height >= 2000 || width >= 3200 -> "4K"
    height >= 1000 || width >= 1800 -> "1080p"
    height >= 700 || width >= 1200 -> "720p"
    else -> "SD"
}

enum class PlayerStatus { Loading, Playing, Paused, Buffering, Ended, Error }

/** The Skip button: the segment being offered and, in auto-delay mode, how far its 3 s fill has run. */
data class SkipPrompt(val segment: Segment, val label: String, val autoProgress: Float?)

/**
 * The Next Episode card. [countdownMs] counts down from [totalMs] and holds
 * while paused; null means autoplay is off and the card waits for a choice.
 */
data class UpNextPrompt(val next: UpNext, val countdownMs: Long?, val totalMs: Long = UP_NEXT_COUNTDOWN_MS) {
    /** How far the countdown has run, 0–1; 0 when autoplay is off. */
    val countdownProgress: Float get() = countdownMs?.let { (1f - it.toFloat() / totalMs.coerceAtLeast(1)).coerceIn(0f, 1f) } ?: 0f
}

/** The Next Episode card's countdown. */
const val UP_NEXT_COUNTDOWN_MS = 10_000L

/**
 * What the player shows, apart from where it has got to ([PlaybackProgress]).
 * It changes when something on screen should: the status, a prompt, a track,
 * the stream; not with every tick of the position.
 */
data class PlayerUiState(
    val status: PlayerStatus = PlayerStatus.Loading,
    val title: String = "",
    val subtitle: String? = null,
    val backdropPath: String? = null,
    /** The file's length, ms; also in [PlaybackProgress]. */
    val durationMs: Long = 0,
    /** Drawn as faint bands on the scrubber (intro, credits…). */
    val segments: List<Segment> = emptyList(),
    val skip: SkipPrompt? = null,
    /** The episode after this one, from the start (the card shows only near the end). */
    val nextEpisode: UpNext? = null,
    val upNext: UpNextPrompt? = null,
    /** Ask "Are you still watching?" before auto-advancing. */
    val askStillWatching: Boolean = false,
    val audio: List<AudioOption> = emptyList(),
    val selectedAudio: Int? = null,
    val subtitles: List<SubtitleOption> = emptyList(),
    /** Null: subtitles off. */
    val selectedSubtitle: String? = null,
    /** Subtitles found online; null while not known, or when the server has no provider. */
    val online: OnlineSubtitles? = null,
    /** The viewer's pick: [StreamQuality.Auto] or a fixed step. */
    val quality: StreamQuality = StreamQuality.Original,
    /** What the quality picker offers: Automatic first when the server can do it. */
    val qualities: List<StreamQuality> = StreamQuality.offered(auto = false),
    /** Automatic: the step the server is sending ([StreamQuality.Original] included); null when not Automatic, or until it says. */
    val autoStep: StreamQuality? = null,
    /** About how many bits per second the stream needs; 0 when unknown. */
    val bitrate: Long = 0,
    val mode: PlaybackMode? = null,
    /** What the server is sending, for the stream pill ("Direct play · 4K HDR"); null until it answers. */
    val video: StreamVideo? = null,
    /** Why the server is not playing the original as is, for an info line. */
    val reasons: List<String> = emptyList(),
    /** The video's frames a second as the player reads them (23.976, 25…); null until it knows. The TV's Match frame rate follows it. */
    val frameRate: Float? = null,
    /**
     * A short note to show for a few seconds, over the video and never
     * focused: "Lowered to 720p for your connection."
     */
    val note: String? = null,
    /** An admin stopped this play; show the message, then leave. */
    val stopMessage: String? = null,
    val error: String? = null,
    /** The file's seek thumbnails, for the scrubber; null where there are none, and the scrubber shows the time alone. */
    val trickplay: Trickplay? = null,
    val isOffline: Boolean = false,
    val growing: GrowingRecording? = null,
    val canCast: Boolean = true,
)

/** The Audio, Subtitles and Quality pickers' part of [PlayerUiState]. */
val PlayerUiState.tracks: PlayerTracks
    get() = PlayerTracks(audio, selectedAudio, subtitles, selectedSubtitle, online, quality, qualities, autoStep, bitrate, mode, video, reasons, isOffline)

/**
 * What the Audio, Subtitles and Quality pickers show ([PlayerUiState.tracks]).
 * It changes only when a track, a choice or the stream does, so the pickers
 * keep their rows while the Next Episode countdown and the Skip button run.
 */
data class PlayerTracks(
    val audio: List<AudioOption> = emptyList(),
    val selectedAudio: Int? = null,
    val subtitles: List<SubtitleOption> = emptyList(),
    /** Null: subtitles off. */
    val selectedSubtitle: String? = null,
    val online: OnlineSubtitles? = null,
    val quality: StreamQuality = StreamQuality.Original,
    val qualities: List<StreamQuality> = StreamQuality.offered(auto = false),
    val autoStep: StreamQuality? = null,
    val bitrate: Long = 0,
    val mode: PlaybackMode? = null,
    val video: StreamVideo? = null,
    val reasons: List<String> = emptyList(),
    val isOffline: Boolean = false,
)

/**
 * Where the play has got to. A player updates it about four times a second,
 * so it is kept apart from [PlayerUiState]: only the scrubber, the clocks and
 * the time labels read it, and the rest of the screen stays as it is.
 */
data class PlaybackProgress(
    /** File position (not the HLS session's), ms. */
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    /** How far ahead of [positionMs] is buffered. */
    val bufferedMs: Long = 0,
)

sealed interface PlayerEvent {
    /** Leave the player (the file ended without a next episode, a skip reached the end, or the play was stopped). */
    data object Exit : PlayerEvent

    /** The next episode started playing in place. */
    data class StartedNext(val fileId: Long) : PlayerEvent
}

/**
 * One viewing at a time: asks the server how to play, drives the player,
 * reports progress and runs Skip, Next Episode and Still watching, as
 * PLAN-CLIENTS.md §2.3 describes. The UI only renders [state] and
 * [progress], shows [player]'s video, and calls the methods below.
 */
interface PlaybackController {
    val state: StateFlow<PlayerUiState>

    /** The position, length and buffer, every tick; read only where they are drawn. */
    val progress: StateFlow<PlaybackProgress>

    val events: Flow<PlayerEvent>

    /** The Media3 player to render video from: null in fakes and until [start] makes one, and again after [release]. */
    val player: StateFlow<Player?>

    fun start(request: PlayRequest)

    fun play()

    fun pause()

    fun togglePlayPause()

    /** Seeks to a file position. */
    fun seekTo(positionMs: Long)

    fun seekBy(deltaMs: Long)

    /** Skips the segment the Skip button offers. */
    fun skip()

    /** Plays [PlayerUiState.nextEpisode] now, with or without the Next Episode card. */
    fun playNext()

    /** Hides the Next Episode card until the end, or a seek back before it. */
    fun watchCredits()

    fun answerStillWatching(keepWatching: Boolean)

    fun selectAudio(index: Int)

    /** Null turns subtitles off. */
    fun selectSubtitle(id: String?)

    /**
     * Downloads an online result ([PlayerUiState.online]) for the file and
     * turns it on; one downloaded before is just turned on. Players that
     * show no online results (a cast's remote) ignore it.
     */
    fun downloadSubtitle(resultId: String) = Unit

    fun selectQuality(quality: StreamQuality)

    /** Any key press, tap or seek: resets the Still watching counters. */
    fun noteUserInput()

    /** The viewer left the player: reports "stopped" and releases the session. */
    fun stop()

    fun release()
}

fun interface PlaybackControllerFactory {
    fun create(): PlaybackController

    /**
     * How each file would play on this device at the default quality, for
     * version lists ("Direct play", "Remux to fMP4 · audio to AAC"), by file
     * ID. Starts nothing; empty when unknown (fakes, older servers).
     */
    suspend fun plans(fileIds: List<Long>): Map<Long, PlaybackPlan> = emptyMap()
}
