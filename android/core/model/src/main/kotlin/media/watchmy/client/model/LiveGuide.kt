package media.watchmy.client.model

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Programme listings (PLAN-LIVE-TV-GUIDE.md): the guide window, now and next, logos and pictures. */
const val LIVE_GUIDE_API_LEVEL = 13

/** The server answers at most this much of the guide at once; the app keeps the next window fetched too. */
const val LIVE_GUIDE_WINDOW_HOURS = 6

/**
 * One listing. Pictures are paths on the server, made loadable by
 * [LiveRepository.artUrl] as library artwork is by `imageUrl`: [picture] is
 * the provider's series card, [still] a TMDB still matched to this programme,
 * which only the wide details panel and the TVs' hero show first.
 */
data class LiveProgramme(
    val id: String,
    val startMs: Long,
    val endMs: Long,
    val title: String,
    val episodeTitle: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeLabel: String? = null,
    /** YYYY-MM-DD, or a movie's year. */
    val airDate: String? = null,
    /** episode, show or movie; others may follow. */
    val kind: String? = null,
    val new: Boolean = false,
    val live: Boolean = false,
    val repeat: Boolean = false,
    val hd: Boolean = false,
    val categories: List<String> = emptyList(),
    val description: String? = null,
    val descriptionFromTmdb: Boolean = false,
    val picture: String? = null,
    /** [picture] is TMDB's, filling a programme the provider has no picture for. */
    val pictureFromTmdb: Boolean = false,
    val still: String? = null,
    /** The person signed in has a reminder for it ([LIVE_PERSONAL_API_LEVEL]). */
    val reminder: Boolean = false,
    val recording: RecordingMark? = null,
    /** The listing's series id: a programme with one can record every episode, one without (a film, a special) records straight away. */
    val seriesKey: String? = null,
) {
    fun airingAt(timeMs: Long) = timeMs in startMs until endMs

    /** Record asks Just this episode or Every new episode only for a programme of a series. */
    val offersSeries: Boolean get() = !seriesKey.isNullOrBlank()
}

data class LiveGuideState(
    val enabled: Boolean = false,
    val updatedAtMs: Long? = null,
    val stale: Boolean = false,
    val listedThroughMs: Long? = null,
)

/** A channel's track: [listings] false shows "No listings", and the channel still plays. */
data class LiveGuideRow(val channel: LiveChannel, val listings: Boolean, val programmes: List<LiveProgramme>)

data class LiveGuide(val startMs: Long, val endMs: Long, val state: LiveGuideState, val rows: List<LiveGuideRow>)

/** What is on [channel] at [timeMs], moving to next at the programme's end without asking the server. */
fun LiveChannel.onAt(timeMs: Long): LiveProgramme? = listOfNotNull(now, next).firstOrNull { it.airingAt(timeMs) }

/** What follows [onAt]; null once next is on until the server's next answer. */
fun LiveChannel.nextAt(timeMs: Long): LiveProgramme? = next?.takeIf { it.startMs > timeMs && now?.airingAt(timeMs) == true }

/** The programme on at [timeMs] in [programmes], which are in start order. */
fun List<LiveProgramme>.at(timeMs: Long): LiveProgramme? = firstOrNull { it.airingAt(timeMs) }

/** A wordmark for the channel's logo while it loads, or on the fakes: its callsign or its initials. */
fun LiveChannel.wordmark(): String {
    Regex("^([A-Z][A-Z0-9]{1,4})(?:[-\\s]|$)").find(name.trim())?.let { return it.groupValues[1] }
    val words = name.split(' ', '-').filter { it.firstOrNull()?.isLetterOrDigit() == true }
    return words.take(4).joinToString("") { it.first().uppercase() }.ifEmpty { number }
}

/** "S2 E4 · The Low Tide"; a season numbered by year reads "2026 E14"; a movie "Movie · 1958". */
fun LiveProgramme.episodeLine(): String? {
    val code = when {
        season != null && episode != null -> if (season >= 1000) "$season E$episode" else "S$season E$episode"
        episode != null -> "E$episode"
        else -> episodeLabel?.takeIf { it.isNotBlank() }
    }
    if (kind == "movie" && code == null && episodeTitle.isNullOrBlank()) {
        return airDate?.take(4)?.takeIf { it.length == 4 }?.let { "Movie · $it" } ?: "Movie"
    }
    return listOfNotNull(code, episodeTitle?.takeIf { it.isNotBlank() }).joinToString(" · ").ifEmpty { null }
}

/** The tags a cell shows after its title, in the boards' order. */
fun LiveProgramme.tags(): List<String> = listOfNotNull("New".takeIf { new }, "Live".takeIf { live }, "HD".takeIf { hd })

/** "Series · Crime drama" */
fun LiveProgramme.categoryLine(): String? = categories.filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { null }

/** Times in the device's zone, with its 12- or 24-hour setting. */
class LiveClockFormat(private val zone: ZoneId = ZoneId.systemDefault(), private val twentyFourHour: Boolean = false, private val locale: Locale = Locale.getDefault()) {
    private fun at(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone)
    private fun pattern(p: String) = DateTimeFormatter.ofPattern(p, locale)

    /** "8:00 PM", or "20:00". */
    fun time(ms: Long): String = at(ms).format(pattern(if (twentyFourHour) "H:mm" else "h:mm a")).normalizeMeridiem()

    /** "8:00" in a 12-hour cell, where the header already says PM. */
    fun shortTime(ms: Long): String = at(ms).format(pattern(if (twentyFourHour) "H:mm" else "h:mm"))

    /** "8:00–9:00 PM", or "11:30 PM–12:30 AM" across noon or midnight. */
    fun span(startMs: Long, endMs: Long): String {
        if (twentyFourHour) return "${time(startMs)}–${time(endMs)}"
        val sameHalf = at(startMs).hour / 12 == at(endMs).hour / 12 && ChronoUnit.HOURS.between(at(startMs), at(endMs)) < 12
        return if (sameHalf) "${shortTime(startMs)}–${time(endMs)}" else "${time(startMs)}–${time(endMs)}"
    }

    /** "8:00 to 9:00 PM", for a cell's accessible name. */
    fun spoken(startMs: Long, endMs: Long): String {
        if (twentyFourHour) return "${time(startMs)} to ${time(endMs)}"
        val sameHalf = at(startMs).hour / 12 == at(endMs).hour / 12 && ChronoUnit.HOURS.between(at(startMs), at(endMs)) < 12
        return if (sameHalf) "${shortTime(startMs)} to ${time(endMs)}" else "${time(startMs)} to ${time(endMs)}"
    }

    /** "Mon" */
    fun weekday(ms: Long): String = at(ms).dayOfWeek.getDisplayName(TextStyle.SHORT, locale)

    /** "Today", "Tomorrow", then "Wed", for the days listed. */
    fun dayName(ms: Long, nowMs: Long): String = when (ChronoUnit.DAYS.between(at(nowMs).toLocalDate(), at(ms).toLocalDate())) {
        0L -> "Today"
        1L -> "Tomorrow"
        else -> weekday(ms)
    }

    /** "Sat 7:40 PM", when listings were last updated. */
    fun dayTime(ms: Long): String = "${weekday(ms)} ${time(ms)}"

    /** "Wed 7 PM", where a channel's listings end. */
    fun dayHour(ms: Long): String {
        val t = at(ms)
        val hour = if (twentyFourHour) t.format(pattern(if (t.minute == 0) "H:00" else "H:mm")) else t.format(pattern(if (t.minute == 0) "h a" else "h:mm a")).normalizeMeridiem()
        return "${weekday(ms)} $hour"
    }

    fun zoned(ms: Long): ZonedDateTime = at(ms)

    private fun String.normalizeMeridiem() = replace(' ', ' ').replace("am", "AM").replace("pm", "PM")
}

/** "48 min left", "1 h 18 min left" */
fun liveMinutesLeft(nowMs: Long, endMs: Long): String {
    val minutes = ((endMs - nowMs + 59_999) / 60_000).coerceAtLeast(0).toInt()
    return if (minutes < 60) "$minutes min left" else "${minutes / 60} h${if (minutes % 60 != 0) " ${minutes % 60} min" else ""} left"
}

/** "Starts in 48 min", "Starts in 2 h 15 min" */
fun liveStartsIn(nowMs: Long, startMs: Long): String {
    val minutes = ((startMs - nowMs + 59_999) / 60_000).coerceAtLeast(0).toInt()
    return if (minutes < 60) "Starts in $minutes min" else "Starts in ${minutes / 60} h${if (minutes % 60 != 0) " ${minutes % 60} min" else ""}"
}

/** How far through [programme] [nowMs] is, from 0 to 1. */
fun LiveProgramme.progressAt(nowMs: Long): Float =
    if (endMs <= startMs) 0f else ((nowMs - startMs).toFloat() / (endMs - startMs)).coerceIn(0f, 1f)

/** "8:00 to 9:00 PM, The Sleuth Files, season 2 episode 4, New" (PLAN-LIVE-TV-GUIDE.md §6.1, Accessibility). */
fun LiveProgramme.spokenName(format: LiveClockFormat): String {
    val episodeWords = when {
        season != null && episode != null -> if (season >= 1000) "$season episode $episode" else "season $season episode $episode"
        else -> null
    }
    val detail = listOfNotNull(episodeWords, episodeTitle?.takeIf { it.isNotBlank() && episodeWords == null }).firstOrNull()
    return listOfNotNull(format.spoken(startMs, endMs), title, detail, "New".takeIf { new }, "Live".takeIf { live }).joinToString(", ")
}

/** "Next: 9:00 Station 9" */
fun liveNextLine(next: LiveProgramme, format: LiveClockFormat): String = "Next: ${format.shortTime(next.startMs)} ${next.title}"

/**
 * The guide's filters: the person's [Favourites] (from [LIVE_PERSONAL_API_LEVEL],
 * offered once they have one), then the categories of what is on at the time
 * the guide shows.
 */
enum class LiveGuideFilter(val label: String) {
    Favourites("Favourites"), All("All channels"), Movies("Movies"), Sports("Sports"), News("News"), Kids("Kids");

    /**
     * The one rule every client keeps (PLAN-LIVE-TV-GUIDE.md §6.5): under
     * [Favourites] a channel shows when it is a [favourite], with or without
     * listings; a category needs listings whose programme at [timeMs]
     * matches; All shows every channel. The guide keeps channel order.
     */
    fun keeps(row: LiveGuideRow, favourite: Boolean, timeMs: Long): Boolean = when (this) {
        All -> true
        Favourites -> favourite
        else -> row.listings && matches(row.programmes.at(timeMs))
    }

    fun matches(programme: LiveProgramme?): Boolean {
        if (this == All) return true
        val categories = programme?.categories.orEmpty().map { it.lowercase(Locale.ROOT) }
        return when (this) {
            All, Favourites -> true
            Movies -> programme?.kind == "movie" || "movie" in categories
            Sports -> categories.any { it.startsWith("sport") || it in SPORTS }
            News -> categories.any { it == "news" || it == "weather" }
            Kids -> categories.any { it == "kids" || it.startsWith("children") || it == "animated" }
        }
    }

    companion object {
        /** The chips the guide offers: Favourites first once the person has one. */
        fun offered(hasFavourites: Boolean): List<LiveGuideFilter> = if (hasFavourites) entries else entries - Favourites

        private val SPORTS = setOf("football", "soccer", "hockey", "basketball", "baseball", "golf", "tennis", "racing", "auto racing", "boxing", "wrestling")
    }
}
