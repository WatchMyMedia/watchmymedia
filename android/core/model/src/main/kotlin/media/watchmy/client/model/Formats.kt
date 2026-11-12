package media.watchmy.client.model

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAccessor
import java.util.Locale
import kotlin.math.roundToLong

/** "S03 E07". */
fun episodeCode(season: Int, episode: Int): String = String.format(Locale.ROOT, "S%02d E%02d", season, episode)

/** "1:02:03" or "52:10" for a player clock. */
fun formatClock(ms: Long): String {
    val total = (ms.coerceAtLeast(0) / 1000)
    val h = total / 3600
    val m = total / 60 % 60
    val s = total % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
}

/** "1 h 12 min" or "23 min". */
fun formatDuration(ms: Long): String {
    val minutes = (ms.coerceAtLeast(0) / 60_000.0).roundToLong()
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h > 0 && m > 0 -> "$h h $m min"
        h > 0 -> "$h h"
        else -> "$m min"
    }
}

/** "23 min left". */
fun formatRemaining(ms: Long): String = "${formatDuration(ms)} left"

/** "18.4 TB", "740 GB". */
fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB", "PB")
    var v = bytes.toDouble()
    var i = -1
    while (v >= 1024 && i < units.lastIndex) {
        v /= 1024
        i++
    }
    return if (v >= 100) String.format(Locale.ROOT, "%.0f %s", v, units[i]) else String.format(Locale.ROOT, "%.1f %s", v, units[i])
}

/** "just now", "12 min ago", "3 h ago", "yesterday", "4 days ago", "2 weeks ago". */
fun formatAgo(thenMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val diff = (nowMs - thenMs).coerceAtLeast(0) / 1000
    return when {
        diff < 60 -> "just now"
        diff < 3600 -> "${diff / 60} min ago"
        diff < 86_400 -> "${diff / 3600} h ago"
        diff < 2 * 86_400 -> "yesterday"
        diff < 14 * 86_400 -> "${diff / 86_400} days ago"
        diff < 60 * 86_400 -> "${diff / (7 * 86_400)} weeks ago"
        else -> "${diff / (30 * 86_400)} months ago"
    }
}

/*
 * Dates and counts sit in the app's English sentences ("Aired Oct 21"), so
 * they are written in English whatever the device's language, with the
 * formatters made once.
 */
private val MONTH_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)
private val FULL_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH)

/** An ISO date ("2026-10-28", or the start of a date-time) as a [LocalDate]; null when it is not one. */
fun parseDate(iso: String?): LocalDate? =
    iso?.takeIf { it.length >= 10 }?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }

/** "Oct 17, 2026". */
fun formatDate(iso: String?): String? = parseDate(iso)?.format(FULL_DATE)

/** "Nov 4". */
fun monthDay(iso: String?): String? = parseDate(iso)?.format(MONTH_DAY)

/** [date] as "Oct 12", in English. */
fun monthDay(date: TemporalAccessor): String = MONTH_DAY.format(date)

/**
 * A day relative to [today]: "today", "yesterday", "3 days ago" in the last
 * week, otherwise "Oct 21" (with the year when it is not this year). Future
 * days read the same way: "Nov 4".
 */
fun formatDay(iso: String?, today: LocalDate = LocalDate.now()): String? {
    val date = parseDate(iso) ?: return null
    val days = ChronoUnit.DAYS.between(date, today)
    return when {
        days == 0L -> "today"
        days == 1L -> "yesterday"
        days in 2..6 -> "$days days ago"
        date.year == today.year -> date.format(MONTH_DAY)
        else -> date.format(FULL_DATE)
    }
}

/** "Aired yesterday", "Aired Oct 21" or "Airs Nov 4"; null without a date. */
fun airedLabel(iso: String?, today: LocalDate = LocalDate.now()): String? {
    val date = parseDate(iso) ?: return null
    val day = formatDay(iso, today) ?: return null
    return if (date.isAfter(today)) "Airs $day" else "Aired $day"
}

/** "3,418". */
fun formatCount(n: Int): String = String.format(Locale.US, "%,d", n)

/** "1 season", "3 seasons", "3,418 episodes". */
fun plural(n: Int, one: String, many: String = one + "s"): String = "${formatCount(n)} ${if (n == 1) one else many}"

/** "★ 8.1"; null for no rating. */
fun formatRating(rating: Double?): String? = rating?.takeIf { it > 0 }?.let { String.format(Locale.ROOT, "★ %.1f", it) }
