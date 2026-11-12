package media.watchmy.client.core.playback

import media.watchmy.client.core.playback.player.EmbeddedSubtitle
import media.watchmy.client.core.playback.player.PlayerFailure
import media.watchmy.client.core.playback.player.TextSelection
import media.watchmy.client.model.MediaToken
import media.watchmy.client.model.PlaybackDecision
import media.watchmy.client.model.PlaybackProfile
import media.watchmy.client.model.Segment
import media.watchmy.client.model.SegmentKind
import media.watchmy.client.model.SkipMode
import media.watchmy.client.model.StreamProtocol
import media.watchmy.client.model.StreamQuality
import media.watchmy.client.model.SubtitleSource
import media.watchmy.client.model.UP_NEXT_COUNTDOWN_MS
import media.watchmy.client.model.isOnServer
import java.net.URLEncoder

// The numbers of PLAN-CLIENTS.md §2.3, which are the web player's.

/** Without an explicit start, resume only past this. */
internal const val RESUME_MIN_MS = 5_000L

/** Heartbeats while playing, and while paused or buffering. */
internal const val BEAT_PLAYING_MS = 10_000L
internal const val BEAT_IDLE_MS = 30_000L

/** Automatic's heartbeats while playing or buffering (PLAN-AUTO-QUALITY.md §2). */
internal const val BEAT_AUTO_MS = 5_000L

/** The Skip button needs a segment this long; skipping by itself, 1 s. */
internal const val SKIP_BUTTON_MIN_MS = 3_000L
internal const val SKIP_AUTO_MIN_MS = 1_000L

/** A segment stops counting this close to its end, so a skip does not land in it again. */
internal const val SEGMENT_END_GUARD_MS = 500L

/** How long auto-delay fills the Skip button before skipping. */
internal const val AUTO_DELAY_MS = 3_000L

/** The Next Episode card's countdown. */
internal const val NEXT_COUNTDOWN_MS = UP_NEXT_COUNTDOWN_MS

/** A skip that lands this close to the end has reached the end. */
internal const val END_SLACK_MS = 1_000L

/** Refresh the media token this long before it runs out. */
internal const val TOKEN_MARGIN_MS = 60_000L

/** However the clocks disagree, a token is trusted this long before a refresh. */
internal const val TOKEN_MIN_LIFETIME_MS = 30 * 60_000L

/** Event playlists: a seek this far past the buffer still plays in the session. */
internal const val EVENT_SEEK_SLACK_MS = 500L

/**
 * How the player's clock maps onto the file. VOD playlists and direct play
 * start at 0, so file position = player time; an event playlist starts at
 * the decision's startMs.
 */
internal data class StreamClock(val offsetMs: Long, val event: Boolean) {
    fun filePosition(playerMs: Long): Long = offsetMs + playerMs

    fun playerPosition(fileMs: Long): Long = (fileMs - offsetMs).coerceAtLeast(0)

    /** Whether a seek to [fileMs] stays in this session: always, except past an event playlist's buffer or before its start. */
    fun canSeekLocally(fileMs: Long, bufferedPlayerMs: Long): Boolean =
        !event || (fileMs - offsetMs) in 0..bufferedPlayerMs + EVENT_SEEK_SLACK_MS

    companion object {
        val Zero = StreamClock(0, event = false)

        fun of(d: PlaybackDecision): StreamClock {
            val event = d.protocol == StreamProtocol.Hls && !d.vod
            return StreamClock(if (event) d.startMs else 0, event)
        }
    }
}

/** [url] with the media token appended (`?access_token=` or `&access_token=`) when it is on the token's server; any other URL as it is. */
internal fun withToken(url: String, token: MediaToken): String {
    val server = token.serverUrl ?: return url
    if (!isOnServer(server, url)) return url
    val hash = url.indexOf('#')
    val base = if (hash >= 0) url.substring(0, hash) else url
    val fragment = if (hash >= 0) url.substring(hash) else ""
    val sep = if ('?' in base) "&" else "?"
    return base + sep + token.param + "=" + URLEncoder.encode(token.token, "UTF-8") + fragment
}

/** Whether the decision's text subtitles arrive as renditions in the master playlist rather than as files. */
internal fun PlaybackDecision.hasRenditions(): Boolean = protocol == StreamProtocol.Hls && vod

/**
 * Direct play from the server reads the file's own text subtitles from the
 * file, and side-loads only sidecar and downloaded ones. A download keeps
 * side-loading what it stored beside the file, as its list may leave tracks out.
 */
internal fun PlaybackDecision.readsSubtitlesInFile(): Boolean = protocol == StreamProtocol.File && !isOffline

/** The file's own subtitle tracks, in its order, when the player reads them from the file ([readsSubtitlesInFile]). */
internal fun PlaybackDecision.embeddedSubtitles(): List<EmbeddedSubtitle> =
    if (!readsSubtitlesInFile()) emptyList()
    else subtitles.filter { it.option.source == SubtitleSource.Embedded }.map { EmbeddedSubtitle(it.option.id, it.option.language) }

/** How to show the subtitle [id] in this decision's stream. */
internal fun textSelection(d: PlaybackDecision, id: String?): TextSelection {
    if (id == null || id == d.burnInSubtitleId) return TextSelection.Off
    val track = d.subtitles.firstOrNull { it.option.id == id } ?: return TextSelection.Off
    if (track.format == "image") return TextSelection.Off
    if (d.readsSubtitlesInFile() && track.option.source == SubtitleSource.Embedded) return TextSelection.Embedded(id)
    if (d.hasRenditions()) {
        // The master playlist lists every text subtitle except one burned in, in the decision's order.
        val ordinal = d.subtitles.filter { it.format != "image" && it.option.id != d.burnInSubtitleId }.indexOfFirst { it.option.id == id }
        return if (ordinal >= 0) TextSelection.Rendition(ordinal) else TextSelection.Off
    }
    return if (track.url != null) TextSelection.Sideloaded(id) else TextSelection.Off
}

/** The profile narrowed by the viewer's quality pick. */
internal fun PlaybackProfile.capped(quality: StreamQuality): PlaybackProfile = copy(
    maxWidth = minOfNullable(maxWidth, quality.maxWidth),
    maxHeight = minOfNullable(maxHeight, quality.maxHeight),
    maxBitrate = minOfNullable(maxBitrate, quality.maxBitrate),
)

/** A step as the switch note names it: "4K", "720p". */
internal fun stepName(quality: StreamQuality): String = quality.label.substringBefore(" · ")

private fun <T : Comparable<T>> minOfNullable(a: T?, b: T?): T? = when {
    a == null -> b
    b == null -> a
    else -> minOf(a, b)
}

internal fun skipLabel(kind: SegmentKind): String = when (kind) {
    SegmentKind.Intro -> "Skip Intro"
    SegmentKind.Recap -> "Skip Recap"
    SegmentKind.Credits -> "Skip Credits"
    SegmentKind.Preview -> "Skip Preview"
    SegmentKind.Commercial -> "Skip Ad"
}

internal fun Segment.endOr(durationMs: Long): Long = endMs ?: durationMs

/**
 * The segment playing at [positionMs]: at least 1 s long, not in its last
 * half second, and not the final credits (the server's `final`, or the
 * credits at creditsMs) when the Next Episode card takes their place.
 */
internal fun activeSegment(d: PlaybackDecision, positionMs: Long, nextOffered: Boolean): Segment? = d.segments.firstOrNull { s ->
    val end = s.endOr(d.durationMs)
    val finalCredits = s.kind == SegmentKind.Credits && (s.final || (d.creditsMs > 0 && s.startMs == d.creditsMs))
    end - s.startMs >= SKIP_AUTO_MIN_MS && positionMs >= s.startMs && positionMs < end - SEGMENT_END_GUARD_MS && !(finalCredits && nextOffered)
}

/** What the Skip control does for the playing segment. */
internal sealed interface SkipAction {
    data object None : SkipAction

    /** A Skip button the viewer presses. */
    data object Button : SkipAction

    /** A Skip button that fills over 3 s, then skips. */
    data object AutoDelay : SkipAction

    /** Skip at once, without a button. */
    data object AutoNow : SkipAction
}

/**
 * The viewer's setting for the segment's kind decides: a button needs 3 s of
 * segment, skipping by itself 1 s, and a segment the viewer seeked into is
 * never skipped by itself (it gets a plain button instead).
 */
internal fun skipAction(segment: Segment, durationMs: Long, mode: SkipMode, seekedInto: Boolean): SkipAction {
    val long = segment.endOr(durationMs) - segment.startMs >= SKIP_BUTTON_MIN_MS
    return when {
        mode == SkipMode.None -> SkipAction.None
        mode == SkipMode.Auto && !seekedInto -> SkipAction.AutoNow
        !long -> SkipAction.None
        mode == SkipMode.AutoDelay && !seekedInto -> SkipAction.AutoDelay
        else -> SkipAction.Button
    }
}

/** The message for [PlayerUiState.error][media.watchmy.client.model.PlayerUiState.error]. */
internal fun errorMessage(failure: PlayerFailure): String = when (failure.kind) {
    PlayerFailure.Kind.Network -> "Lost the connection to the server."
    PlayerFailure.Kind.Http -> when (failure.httpStatus) {
        401, 403 -> "The server refused the stream. Try signing in again."
        404, 410 -> "The server no longer has this stream."
        null -> "The server refused the stream."
        else -> "The server answered with HTTP ${failure.httpStatus}."
    }
    PlayerFailure.Kind.Decoder -> "This device could not decode the stream."
    PlayerFailure.Kind.Audio -> "This device could not play the audio."
    PlayerFailure.Kind.Subtitle -> "Couldn’t load a subtitle. The video will keep playing."
    PlayerFailure.Kind.Source -> "The stream could not be read."
    PlayerFailure.Kind.BehindLiveWindow -> "The stream fell behind."
    PlayerFailure.Kind.Other -> "Playback failed (${failure.detail})."
}

/** Failures worth one more try at the current position: an expired token, a session the server dropped, a network hiccup. */
internal fun PlayerFailure.recoverable(): Boolean = when (kind) {
    PlayerFailure.Kind.Network, PlayerFailure.Kind.BehindLiveWindow -> true
    PlayerFailure.Kind.Http -> httpStatus in setOf(401, 403, 404, 410)
    else -> false
}
