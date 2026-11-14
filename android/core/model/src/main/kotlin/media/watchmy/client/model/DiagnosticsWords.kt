package media.watchmy.client.model

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * How a [ProcessExit] reads in Settings' diagnostics (the TvDiagnostics and
 * Diagnostics boards), for every UI alike: why, in plain words.
 */
fun ProcessExit.headline(): String = when (reason) {
    "low memory" -> "Closed by Android to free memory"
    "crash", "native crash" -> "Crashed"
    "anr" -> "Stopped responding"
    "app updated" -> "Updated"
    "stopped by the user", "user stopped" -> "Force-stopped"
    "exited" -> "Closed itself"
    "signal" -> "Stopped by the system"
    "excessive resource use" -> "Closed for using too much"
    else -> reason.replaceFirstChar { it.titlecase(Locale.ROOT) }
}

/** What else Android recorded: whether the app was on screen, the system's own words, and whether it kept a trace. */
fun ProcessExit.detail(): String = listOfNotNull(
    if (importance in listOf("foreground", "visible")) "on screen" else "in the background",
    description,
    "trace kept".takeIf { hasTrace },
).joinToString(" · ")

/** When: "Today, 9:41 PM", "Yesterday, 11:02 PM", else "Wed 11 Nov, 8:15 PM". */
fun ProcessExit.whenLabel(nowMs: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault(), twentyFour: Boolean = false): String {
    val at = Instant.ofEpochMilli(atMs).atZone(zone)
    val days = ChronoUnit.DAYS.between(at.toLocalDate(), Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate())
    val time = at.format(DateTimeFormatter.ofPattern(if (twentyFour) "HH:mm" else "h:mm a", locale))
    val day = when (days) {
        0L -> "Today"
        1L -> "Yesterday"
        else -> at.format(DateTimeFormatter.ofPattern("EEE d MMM", locale))
    }
    return "$day, $time"
}

/** Under the list of exits: what the copy holds, and doesn't. */
const val DIAGNOSTICS_NOTE = "Up to the last five. No account, server address or titles are included."

/** With no exits recorded. */
const val NO_EXITS = "Android hasn’t recorded any exits for this app yet."

/**
 * What "Copy diagnostics" puts on the clipboard: [app] (the app, its version,
 * Android and the device), then each exit, newest first, as plain text.
 */
fun diagnosticsText(app: String, exits: List<ProcessExit>, nowMs: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String = buildString {
    appendLine(app)
    appendLine()
    if (exits.isEmpty()) appendLine(NO_EXITS)
    for (exit in exits) appendLine("${exit.whenLabel(nowMs, zone, locale)} · ${exit.headline()} · ${exit.detail()}")
}.trimEnd()
