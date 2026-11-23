package media.watchmy.client.model

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.format.TextStyle
import java.util.Locale

/*
 * What a card says about a title beyond its name, from servers at
 * [REDESIGN_API_LEVEL]: the same words on the phone, the tablet and the TV,
 * as the Apple apps word them.
 */

/** "4 episodes left"; "Watched" once none are; null when the server doesn't say. */
fun BrowseItem.episodesLeftLine(): String? {
    if (kind != ItemKind.Show) return null
    val left = unwatchedCount ?: return null
    return if (left == 0) "Watched" else plural(left, "episode") + " left"
}

/**
 * When the show's next episode airs: "New episode today", "New episode
 * tomorrow", "New episode Thursday" within the week, else "New episode
 * Dec 3"; null without a date, or for one already past.
 */
fun nextEpisodeLine(iso: String?, today: LocalDate = LocalDate.now()): String? {
    val date = parseDate(iso) ?: return null
    val days = ChronoUnit.DAYS.between(today, date)
    return when {
        days < 0 -> null
        days == 0L -> "New episode today"
        days == 1L -> "New episode tomorrow"
        days in 2..6 -> "New episode " + date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        else -> "New episode " + monthDay(date)
    }
}

/** "3 seasons", or the episodes left when they are known: what a show's poster says under its title. */
fun BrowseItem.showLine(): String? = episodesLeftLine() ?: seasonCount?.let { plural(it, "season") }

/** "Watched Nov 12": when a title was watched to the end; null when it isn't, or the server doesn't say. */
fun WatchProgress.watchedLine(today: LocalDate = LocalDate.now()): String? {
    if (!completed) return null
    val at = updatedAtMs ?: return null
    val date = java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    return "Watched " + when (ChronoUnit.DAYS.between(date, today)) {
        0L -> "today"
        1L -> "yesterday"
        else -> if (date.year == today.year) monthDay(date) else formatDate(date.toString())
    }
}

/** "Nov 3, 2026": when a file came into the library, for File details; null when the server doesn't say. */
fun MediaVersion.addedLine(): String? = addedAtMs?.let { formatDate(java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()) }
