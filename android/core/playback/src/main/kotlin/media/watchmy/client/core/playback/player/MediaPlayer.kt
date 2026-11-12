package media.watchmy.client.core.playback.player

import androidx.media3.common.Player
import media.watchmy.client.model.MediaToken

/** Where the player is, as ExoPlayer's playback states. */
enum class PlayerPhase { Idle, Buffering, Ready, Ended }

/** Rebuffers since a [MediaPlayer.load], and the time they took (one under way included). */
data class Stalls(val count: Int = 0, val ms: Long = 0)

/** One completed HLS media segment, with its age on the device's monotonic clock. */
data class TransferSample(val bps: Long, val ageMs: Long)

/** A WebVTT or ASS file loaded beside the stream (event playlists and direct play). */
data class SideloadedSubtitle(
    /** The decision's subtitle ID ("s3", "x0"). */
    val id: String,
    /** With the media token. */
    val url: String,
    val mimeType: String,
    val label: String,
    val language: String?,
)

/**
 * A subtitle track inside a direct-play file, as the decision lists it. The
 * player reads it from the file, as Media3 can for SRT, ASS, WebVTT and MP4
 * text, rather than side-loading the server's copy: the server makes that
 * copy by reading the whole video, which on a long film from a spinning disk
 * took longer than the player waits and fought playback for the disk.
 */
data class EmbeddedSubtitle(
    /** The decision's subtitle ID ("s3"). */
    val id: String,
    /** As the file tags it ("eng"); null when it doesn't. */
    val language: String?,
)

/** Which text track to show. */
sealed interface TextSelection {
    data object Off : TextSelection

    /** The [ordinal]th subtitle rendition of a VOD master playlist. */
    data class Rendition(val ordinal: Int) : TextSelection

    /** A [SideloadedSubtitle] by its ID. */
    data class Sideloaded(val id: String) : TextSelection

    /** An [EmbeddedSubtitle] by its ID, read from the file itself. */
    data class Embedded(val id: String) : TextSelection
}

/** What to load. */
data class PlayerSource(
    /** With the media token. */
    val url: String,
    val hls: Boolean,
    /** An event playlist, which ExoPlayer treats as live; it must play at normal speed. */
    val live: Boolean,
    /** Player time to start at. */
    val startPositionMs: Long,
    val playWhenReady: Boolean,
    val subtitles: List<SideloadedSubtitle>,
    val text: TextSelection,
    /** Direct play: the file's audio track to play, by its order among the file's audio tracks. */
    val audioOrdinal: Int?,
    /** For the media session and its notification. */
    val title: String,
    val subtitle: String?,
    /** The notification's and lock screen's picture: a URL on the signed-in server, which the session's bitmap loader fetches signed in. */
    val artworkUrl: String? = null,
    /** A rolling Live TV stream starts at Media3’s default live position, never at file time zero. */
    val atLiveEdge: Boolean = false,
    /**
     * With [audioOrdinal]: how many audio tracks the decision lists. The
     * player's must be as many for an ordinal to name the same track; when
     * they aren't, it leaves the audio alone and says so.
     */
    val audioTracks: Int? = null,
    /**
     * Direct play: every subtitle track inside the file, image ones too, in
     * the file's order, as the decision lists them. The player finds a
     * [TextSelection.Embedded] among its own text tracks by this order, or
     * by language when it lists a different number of tracks.
     */
    val embeddedSubtitles: List<EmbeddedSubtitle> = emptyList(),
)

/**
 * Why the player stopped. [detail] is for logs and the fallback message.
 * [audioMimeType] is the format the audio renderer was playing when it was
 * the one that failed ("audio/eac3"), and null for any other failure.
 */
data class PlayerFailure(val kind: Kind, val detail: String, val httpStatus: Int? = null, val audioMimeType: String? = null, val subtitleId: String? = null) {
    enum class Kind { Network, Http, Decoder, Audio, Source, Subtitle, BehindLiveWindow, Other }
}

/**
 * What the controller needs from a player: ExoPlayer in the app
 * ([ExoMediaPlayer]), a fake in tests, so the §2.3 machine runs on the JVM.
 * Positions are player time; the controller maps them to file positions.
 * Called on one thread (the main thread in the app).
 */
interface MediaPlayer {
    /** The Media3 player to render video from and to put in the media session; null in fakes. */
    val media3: Player?

    val phase: PlayerPhase

    /** False when paused, by the viewer or by the system (audio focus, headphones unplugged). */
    val playWhenReady: Boolean

    val positionMs: Long

    val bufferedPositionMs: Long

    /** The link's speed in bits per second as the player measured it from its recent downloads; null without a recent measurement. */
    val throughputBps: Long?

    /** Fresh completed HLS segment bytes, excluding manifests and direct-file pacing. Cold segment preparation can lower this estimate. */
    val transfer: TransferSample? get() = null

    /** Buffering since the last [load], after it was first ready, that no seek caused (PLAN-AUTO-QUALITY.md §3.1). */
    val stalls: Stalls

    /** The playing video's frames a second, from its format; null until the tracks or the decoder say. A change calls [Listener.onChanged]. */
    val frameRate: Float? get() = null

    /** Current distance from the broadcast edge; null for a file or until the timeline arrives. */
    val liveOffsetMs: Long? get() = null

    /** The rolling window’s duration, used to detect a paused position that expired. */
    val liveWindowDurationMs: Long? get() = null

    /**
     * An event playlist's length in player time once it has its end tag (a
     * recording that finished or stopped early); null while it may still grow,
     * before the timeline arrives, and for a file.
     */
    val endedEventDurationMs: Long? get() = null

    /** Renew credentials for future live manifest/segment reads without retuning or discarding the pause. */
    fun updateMediaToken(token: MediaToken) = Unit

    /** Seek to the broadcast edge; file controllers never call this. */
    fun seekToLiveEdge() = Unit

    fun setListener(listener: Listener?)

    fun load(source: PlayerSource)

    fun play()

    fun pause()

    fun seekTo(positionMs: Long)

    fun selectText(selection: TextSelection)

    /** Direct play: switches to the file's [ordinal]th audio track. False when this device cannot play it, or the tracks are not known yet. */
    fun selectAudio(ordinal: Int): Boolean

    /** Stops loading and playing; [load] starts again. */
    fun stop()

    fun release()

    interface Listener {
        /** [phase] or [playWhenReady] changed. */
        fun onChanged()

        fun onError(failure: PlayerFailure)

        /** The first video frame of the most recent load reached the renderer. */
        fun onFirstFrame() = Unit

        /**
         * A load's [PlayerSource.audioOrdinal] could not be selected: its
         * tracks don't line up with the decision's, or this device can't
         * play that one. The default track plays meanwhile.
         */
        fun onAudioUnavailable() = Unit
    }
}
