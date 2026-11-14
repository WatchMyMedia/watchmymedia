package media.watchmy.client.model

import java.net.URLEncoder

// What the local player, the Cast remotes, the downloads, the data layer and
// the fakes all say or send, kept here once so the copies can't drift: the
// player's wording, the server's wire names, the user agent, the media token
// in a URL, the quality cap and the watched rule.

/** The Skip button's words for a segment of [kind]. */
fun skipLabel(kind: SegmentKind): String = when (kind) {
    SegmentKind.Intro -> "Skip Intro"
    SegmentKind.Recap -> "Skip Recap"
    SegmentKind.Credits -> "Skip Credits"
    SegmentKind.Preview -> "Skip Preview"
    SegmentKind.Commercial -> "Skip Ad"
}

/** A quality step as Automatic's notes name it: "4K", "720p", "Original quality". */
fun stepName(quality: StreamQuality): String = quality.label.substringBefore(" · ")

/** Automatic stepped down to [step]. */
fun loweredNote(step: StreamQuality): String = "Lowered to ${stepName(step)} for your connection."

/** Automatic stepped up to [step]: back to the file itself, or a higher step. */
fun raisedNote(step: StreamQuality): String =
    if (step == StreamQuality.Original) "Connection recovered; playing the original file." else "Raised to ${stepName(step)} for your connection."

/**
 * Automatic's note for a move from [was] to [now] when the server sent no
 * words of its own: lower (or from no known step) says lowered, else raised.
 */
fun automaticStepNote(was: StreamQuality?, now: StreamQuality): String =
    if (was == null || now.ordinal > was.ordinal) loweredNote(now) else raisedNote(now)

/** A step change that failed: the stream at [step] goes on. */
fun stillPlayingNote(step: StreamQuality): String = "Still playing at ${stepName(step)}. Quality will retry later."

/** Automatic at its lowest step, and still stalling. */
const val SLOW_CONNECTION_NOTE = "Your connection is too slow to play this smoothly."

/** An admin stopped the play and the server gave no message of its own. */
const val STOPPED_BY_ADMIN = "Playback was stopped by the server's admin."

/** The server's name for a segment kind: "intro", "recap", "credits", "preview", "commercial". */
val SegmentKind.wireName: String get() = name.lowercase()

/** The segment kind the server calls [wire]; null for one this app doesn't know. */
fun segmentKindOf(wire: String?): SegmentKind? = SegmentKind.entries.firstOrNull { it.wireName == wire }

/** The server's name for how it sends a file: "direct", "remux", "audio", "transcode". */
val PlaybackMode.wireName: String
    get() = when (this) {
        PlaybackMode.Direct -> "direct"
        PlaybackMode.Remux -> "remux"
        PlaybackMode.AudioTranscode -> "audio"
        PlaybackMode.Transcode -> "transcode"
    }

/** The mode the server calls [wire]; null for one this app doesn't know. */
fun playbackModeOf(wire: String?): PlaybackMode? = PlaybackMode.entries.firstOrNull { it.wireName == wire }

/** "WatchMyMedia/1.4.3 (androidtv; Android 14)": what every request from this app says it is, API and media alike. */
fun userAgent(client: ClientInfo, androidRelease: String?): String =
    "WatchMyMedia/${client.version} (${client.platform}; Android ${androidRelease.orEmpty()})"

/**
 * [url] with [token] as its only [param] query value: any it had are taken
 * out and the new one goes last, before a fragment. Other query values are
 * left byte for byte as they were. A space is %20, which every decoder reads
 * as a space; a `+` only form decoding does.
 */
fun withMediaToken(url: String, param: String, token: String): String {
    val hash = url.indexOf('#')
    val beforeFragment = if (hash >= 0) url.substring(0, hash) else url
    val fragment = if (hash >= 0) url.substring(hash) else ""
    val q = beforeFragment.indexOf('?')
    val path = if (q >= 0) beforeFragment.substring(0, q) else beforeFragment
    val kept = if (q >= 0) beforeFragment.substring(q + 1).split('&').filter { it.isNotEmpty() && it.substringBefore('=') != param } else emptyList()
    val query = (kept + "$param=${URLEncoder.encode(token, "UTF-8").replace("+", "%20")}").joinToString("&")
    return "$path?$query$fragment"
}

/**
 * [url] signed with this token when it is on the server that gave it
 * ([isOnServer]); any other URL as it is, so the token never leaves its server.
 */
fun MediaToken.signing(url: String): String {
    val server = serverUrl ?: return url
    return if (isOnServer(server, url)) withMediaToken(url, param, token) else url
}

/** The profile narrowed by a quality pick's caps; Automatic and Original have none. */
fun PlaybackProfile.capped(quality: StreamQuality): PlaybackProfile = copy(
    maxWidth = tighter(maxWidth, quality.maxWidth),
    maxHeight = tighter(maxHeight, quality.maxHeight),
    maxBitrate = tighter(maxBitrate, quality.maxBitrate),
)

private fun <T : Comparable<T>> tighter(a: T?, b: T?): T? = if (a != null && b != null) minOf(a, b) else a ?: b

/** How far into a file it counts as watched without a credits mark, as the server counts it. */
const val WATCHED_FRACTION = 0.92

/** Where a file [durationMs] long counts as watched: the server's mark ([watchedAtMs]) when it gave one, else [WATCHED_FRACTION] of the way. */
fun watchedFromMs(durationMs: Long, watchedAtMs: Long?): Long = watchedAtMs?.takeIf { it > 0 } ?: (durationMs * WATCHED_FRACTION).toLong()
