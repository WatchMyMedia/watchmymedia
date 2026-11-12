package media.watchmy.client.model

/** The API level that reads [PlaybackProfile.hdrTypes] and the dovi keys (PLAN-HDR-PROFILE.md). */
const val HDR_PROFILE_API_LEVEL = 8

/**
 * What this device can play, sent with every playback request
 * (PLAN-CLIENTS.md §5.4; the server's `Profile`).
 */
data class PlaybackProfile(
    val name: String,
    /** mkv, mp4, webm, mpegts. */
    val containers: List<String>,
    /**
     * h264, hevc, hevc10, av1, vp9, and dovi5, dovi7, dovi8: the Dolby Vision
     * profiles the device decodes and its screen shows.
     */
    val videoCodecs: List<String>,
    /** aac, mp3, opus, flac, vorbis, ac3, eac3, dts, truehd. */
    val audioCodecs: List<String>,
    val maxWidth: Int? = null,
    val maxHeight: Int? = null,
    /** Bits per second; null is no limit. */
    val maxBitrate: Long? = null,
    /** Null is any. */
    val maxAudioChannels: Int? = null,
    val hdr: Boolean = false,
    /** webvtt, ass. */
    val subtitleFormats: List<String> = listOf("webvtt", "ass"),
    /**
     * The HDR the screen shows: hdr10, hlg, dovi; empty for an SDR screen.
     * Sent, even empty, it tells the server to copy HDR only to a screen that
     * shows it. Null leaves it out.
     */
    val hdrTypes: List<String>? = null,
    /**
     * The player chooses among a direct-play file's audio tracks itself and
     * starts on the decision's, so a track other than the file's default
     * stays direct play. Null leaves it out, as servers before
     * [AUDIO_LANGUAGES_API_LEVEL] refuse it.
     */
    val selectsAudioTrack: Boolean? = null,
) {
    /**
     * The profile for a server at [level]. Below [AUDIO_LANGUAGES_API_LEVEL]
     * it has no [selectsAudioTrack]. Below [HDR_PROFILE_API_LEVEL] it is
     * the one apps sent before: no [hdrTypes], which such a server refuses, no
     * dovi keys, and hevc10 only for an HDR10 screen, since such a server
     * copies 10-bit HEVC to any profile that has it, HDR or not.
     */
    fun forApiLevel(level: Int): PlaybackProfile {
        if (level < AUDIO_LANGUAGES_API_LEVEL && selectsAudioTrack != null) return copy(selectsAudioTrack = null).forApiLevel(level)
        val types = hdrTypes
        if (level >= HDR_PROFILE_API_LEVEL || types == null) return this
        return copy(
            videoCodecs = videoCodecs.filter { !it.startsWith(DOVI_KEY) && (it != "hevc10" || "hdr10" in types) },
            hdrTypes = null,
        )
    }

    private companion object {
        const val DOVI_KEY = "dovi"
    }
}

/** A request for POST /api/files/{id}/playback. */
data class DecisionRequest(
    val fileId: Long,
    val profile: PlaybackProfile,
    val client: ClientInfo,
    /** Where the server starts ffmpeg; with VOD the player still seeks itself. */
    val startMs: Long? = null,
    val audioIndex: Int? = null,
    val subtitleId: String? = null,
    val burnIn: Boolean = false,
    val quality: StreamQuality = StreamQuality.Original,
    /** The play this request continues (track or quality change); null starts a new one. */
    val playId: String? = null,
    /** Ask for a VOD playlist (the whole file); the server may still answer with an event playlist. */
    val vod: Boolean = true,
    val subtitleMode: SubtitleRequestMode = SubtitleRequestMode.Auto,
    val recordingId: String? = null,
    /** API 24 recovery is gated again by the gateway's server level. */
    val autoRecovery: Boolean = false,
    /** The negotiated quality directive this replacement accepts. */
    val switchGeneration: Long? = null,
)

enum class StreamProtocol { File, Hls }

/** A subtitle track as the decision lists it, with where to fetch it. */
data class DecisionSubtitle(
    val option: SubtitleOption,
    /** webvtt, ass or image. */
    val format: String,
    /** Absolute URL without the media token; null for image formats (burn-in only). */
    val url: String?,
)

/** The server's answer: how to play the file, and what the player needs to know about it. */
data class PlaybackDecision(
    val mode: PlaybackMode,
    val reasons: List<String>,
    val protocol: StreamProtocol,
    /** True for a VOD playlist: file position = player time. False (event): file position = [startMs] + player time. */
    val vod: Boolean,
    /** Absolute URL to play, without the media token. */
    val url: String,
    val mimeType: String?,
    val playId: String,
    val sessionId: String?,
    val durationMs: Long,
    val startMs: Long,
    /** The user's saved position, if partway through. */
    val resumeMs: Long,
    val audio: List<AudioOption>,
    val selectedAudio: Int?,
    val subtitles: List<DecisionSubtitle>,
    /** The subtitle being burned in, if any. */
    val burnInSubtitleId: String?,
    val segments: List<Segment>,
    /** Where the final credits start; 0 when unknown. */
    val creditsMs: Long,
    /** Past this position the item counts as watched. */
    val watchedAtMs: Long,
    /** When to offer the next episode; 0 means do not. */
    val upNextMs: Long,
    val next: UpNext?,
    /** The video being sent; null when the server did not say. */
    val video: StreamVideo? = null,
    /** Automatic: the step the server picked for the stream ([StreamQuality.Original] included); null when the play is not Automatic. */
    val autoStep: StreamQuality? = null,
    /** About how many bits per second the stream needs; 0 when unknown. */
    val bitrate: Long = 0,
    /** The file's seek thumbnails; null until the server has made them, and from servers before [TRICKPLAY_API_LEVEL]. */
    val trickplay: Trickplay? = null,
    /** The selected local default, only from API 18; distinct from burn-in. */
    val subtitleId: String? = null,
    val isOffline: Boolean = false,
    val subtitlePreferencesSupported: Boolean = false,
    val growing: GrowingRecording? = null,
)

/**
 * How a file would play on this device at the chosen quality (POST
 * /api/playback/plans): the decision playing it would get, with its default
 * audio and no subtitles, made without starting anything.
 */
data class PlaybackPlan(
    val fileId: Long,
    val mode: PlaybackMode,
    /** The video as it would be sent; null when the server did not say. */
    val video: StreamVideo?,
    /** What the video would be encoded to ("HEVC", "H.264"); null when it is sent as it is. */
    val videoCodec: String?,
    /** What the audio would be converted to ("AAC"); null when it is sent as it is. */
    val audioCodec: String?,
    val audioChannels: Int?,
    val reasons: List<String> = emptyList(),
) {
    /**
     * "Direct play", "Remux to fMP4", "Remux to fMP4 · audio to AAC 5.1",
     * "Transcode to 1080p HEVC · audio to AAC".
     */
    val label: String
        get() {
            val audio = audioCodec?.let { listOfNotNull("audio to", it, audioChannels?.let(::channelsLabel)).joinToString(" ") }
            val first = when (mode) {
                PlaybackMode.Direct -> return "Direct play"
                PlaybackMode.Remux, PlaybackMode.AudioTranscode -> "Remux to fMP4"
                PlaybackMode.Transcode -> listOfNotNull("Transcode to", video?.quality, videoCodec).joinToString(" ")
            }
            return listOfNotNull(first, audio).joinToString(" · ")
        }
}

/** "Stereo", "5.1", "7.1"; null for mono and odd counts. */
private fun channelsLabel(channels: Int): String? = when (channels) {
    2 -> "Stereo"
    6 -> "5.1"
    8 -> "7.1"
    else -> null
}

enum class PlayState { Playing, Paused, Buffering, Stopped, Closed }

/**
 * A heartbeat for PUT /api/files/{id}/progress, with what the player
 * measured of its stream (PLAN-AUTO-QUALITY.md §3.1): null where it has no
 * measurement.
 */
data class ProgressReport(
    val fileId: Long,
    val positionMs: Long,
    val durationMs: Long,
    val playId: String?,
    val state: PlayState,
    val bufferedMs: Long,
    /** The player's estimate of its link to the server, from its own downloads. */
    val throughputBps: Long? = null,
    /** Age of that completed HLS media transfer; null without one. */
    val throughputAgeMs: Long? = null,
    /** First rendered frame of a negotiated replacement. */
    val switchGeneration: Long? = null,
    /** A negotiated replacement that failed to prepare or play. */
    val switchFailedGeneration: Long? = null,
    /** Rebuffers since this decision: buffering after the first frame that no seek caused. */
    val stalls: Int? = null,
    /** The time those rebuffers took. */
    val stallMs: Long? = null,
    /** From asking for this decision to its first frame; sent once. */
    val startupMs: Long? = null,
    val recordingId: String? = null,
)

data class ProgressReply(
    val completed: Boolean,
    /** An admin stopped this play: show the message and stop. */
    val stopMessage: String?,
    /** The play had ended and a new one carries on: send this from now on. */
    val newPlayId: String?,
    /** Automatic: the server wants the stream at another step. */
    val qualityChange: QualityChange? = null,
)

/**
 * The server's `quality` directive to an Automatic play: ask for the decision
 * again here, with the same play, and the server sends [step]. [message] is
 * the note to show ("Lowered to 720p for your connection.").
 */
data class QualityChange(val step: StreamQuality, val message: String?, val generation: Long? = null)

/**
 * A short-lived token for media URLs (`?access_token=`); HLS playlists pass
 * it on to their segments. It goes only on URLs on [serverUrl], the server
 * that gave it ([isOnServer]), never on an absolute URL elsewhere that a
 * decision names; null puts it on none.
 */
data class MediaToken(val token: String, val expiresAtMs: Long, val param: String = "access_token", val serverUrl: String? = null)

/**
 * The server calls the player makes. core:data implements it; core:playback
 * uses it (and fakes it in tests). Methods throw [ApiException].
 */
interface PlaybackGateway {
    /** One immutable server/account context for the lifetime of a player. */
    fun bind(): PlaybackGateway = this

    suspend fun decide(request: DecisionRequest): PlaybackDecision

    suspend fun reportProgress(report: ProgressReport): ProgressReply

    suspend fun mediaToken(): MediaToken

    /** Ends an HLS session (DELETE /api/playback/{session}). */
    suspend fun stopSession(sessionId: String)

    /** How each file would play with [profile] at [quality] (its caps applied as for [decide]); empty from servers that cannot say. */
    suspend fun plans(profile: PlaybackProfile, quality: StreamQuality, fileIds: List<Long>): List<PlaybackPlan> = emptyList()

    /**
     * Subtitles found online for the file, in the server's languages; null
     * when the server has no provider, is too old to have one, or this
     * player may not search (a cast).
     */
    suspend fun searchSubtitles(fileId: Long): OnlineSubtitles? = null

    /** Downloads an online result and keeps it for the file: the new subtitle track's ID. */
    suspend fun downloadSubtitle(fileId: Long, resultId: String): String = throw ApiException(503, "Online subtitles are not available here.")

    /** Optional, once-per-play provider work after the first frame; unavailable to cast tokens and offline plays. */
    suspend fun automaticSubtitle(fileId: Long, playId: String): DecisionSubtitle? = null

    suspend fun cancelAutomaticSubtitle(fileId: Long, playId: String) = Unit
}
