package media.watchmy.client.model

/**
 * The server level that lists a movie's trailers on getMovie
 * (PLAN-TRAILERS.md §5.3). Older servers send none, so their movies show no
 * Trailer button.
 */
const val TRAILERS_API_LEVEL = 20

/** One of a movie's trailers, as the server lists them: best first, files before YouTube. */
sealed interface Trailer {
    /** TMDB's name ("Official Trailer"), or the file's ("Trailer"). */
    val name: String

    /** A trailer file in the library: it plays and casts like any other file. */
    data class File(override val name: String, val fileId: Long, val durationMs: Long? = null) : Trailer

    /**
     * A YouTube video, played by YouTube's own player in the app (phones,
     * tablets and Android TV) or by the YouTube app (an Android TV without a
     * usable WebView). [key] is its video id.
     */
    data class YouTube(override val name: String, val key: String) : Trailer {
        /** The watch link, which the YouTube app opens where it is installed. */
        val watchUrl: String get() = "https://www.youtube.com/watch?v=$key"
    }
}

/**
 * Whether [key] is safe to put in a URL or a page as a YouTube video id:
 * what the server keeps (letters, digits, - and _). Anything else is left out.
 */
fun isYouTubeKey(key: String): Boolean = YOUTUBE_KEY.matches(key)

private val YOUTUBE_KEY = Regex("[A-Za-z0-9_-]{1,64}")

/**
 * The trailer the Trailer button plays: the first the server lists that this
 * device can play. [youTube] is false where a YouTube trailer can't play
 * (a TV with neither a usable WebView nor an app for YouTube's links); the
 * button then shows only for a file.
 */
fun MovieDetail.trailer(youTube: Boolean = true): Trailer? =
    trailers.firstOrNull { it is Trailer.File || (youTube && it is Trailer.YouTube) }

/** The movie's YouTube trailers, best first, from [first]: the ones the trailer screen tries in turn. */
fun MovieDetail.youTubeTrailers(first: Trailer.YouTube): List<Trailer.YouTube> {
    val all = trailers.filterIsInstance<Trailer.YouTube>()
    return all.drop(all.indexOf(first).coerceAtLeast(0))
}

/**
 * Plays a trailer file from its start, as "<movie> · Trailer". The server
 * keeps no progress for it, so it never resumes.
 */
fun MovieDetail.trailerRequest(trailer: Trailer.File): PlayRequest =
    PlayRequest(trailer.fileId, startMs = 0, title = title, subtitle = trailer.name, backdropPath = backdropPath)

/** Trailers' wording, the same in the Apple apps (PLAN-TRAILERS.md §6). */
object TrailerText {
    const val BUTTON = "Trailer"

    /** Under the TV's button row, for a YouTube trailer handed to the YouTube app (no usable WebView). */
    const val OPENS_YOUTUBE = "Opens the YouTube app"

    /** The trailer screen's top bar. */
    const val PLAYS_FROM_YOUTUBE = "Plays from YouTube"
    const val YOUTUBE_TERMS = "YouTube Terms of Service"
    const val YOUTUBE_TERMS_URL = "https://www.youtube.com/t/terms"

    /** When no trailer may play outside YouTube (errors 101 and 150). */
    const val REFUSED_TITLE = "This trailer can’t play here"
    const val REFUSED_BODY = "Its owner only lets it play on YouTube."
    const val OPEN_IN_YOUTUBE = "Open in YouTube"
    const val COULD_NOT_OPEN = "Couldn’t open YouTube."

    /** When YouTube's player doesn't load (no connection). */
    const val UNREACHABLE_TITLE = "Couldn’t reach YouTube"
    const val UNREACHABLE_BODY = "Check the connection and try again."

    /** Leaves the trailer screen: the phone's back button, the TV's button beside those messages. */
    const val BACK_TO_MOVIE = "Back to the movie"

    /** The TV's pause screen, in the player's place while a trailer is paused. */
    const val PAUSED = "Paused"

    /** "Paused · 0:13 / 7:45", or "Paused · 0:13" while the length isn't known. */
    fun paused(positionMs: Long, durationMs: Long): String =
        if (durationMs > 0) "$PAUSED · ${formatClock(positionMs)} / ${formatClock(durationMs)}" else "$PAUSED · ${formatClock(positionMs)}"

    /**
     * The pause screen's hint, each remote key with what it does there:
     * "Select to play · ◀ ▶ 10 s · Back to the movie". The TV draws the keys
     * bolder ([pausedKeysLine] is the plain text).
     */
    val PAUSED_KEYS: List<Pair<String, String>> = listOf("Select" to "to play", "◀ ▶" to "10 s", "Back" to "to the movie")

    /** [PAUSED_KEYS] as one line. */
    val pausedKeysLine: String get() = PAUSED_KEYS.joinToString(" · ") { (key, does) -> "$key $does" }
}
