package media.watchmy.client.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.time.format.TextStyle
import java.util.Locale

/** DVR is additive; servers below this level never receive recording requests. */
const val LIVE_RECORDING_API_LEVEL = 19
val RECORDING_PADDING = listOf(0, 1, 2, 5, 15, 30, 60)

enum class RecordingSection(val id: String, val title: String) {
    Now("now", "Recording now"), Conflicts("conflicts", "Won’t record"),
    Upcoming("upcoming", "Upcoming"), Series("series", "Series"), Recorded("recorded", "Recorded"),
}

/** How the guide marks a programme: nothing for one that won't record for any reason but a clash. */
enum class RecordingGlyph {
    /** A red dot: it will record. */
    Dot,
    /** A ringed red dot: a series or a repeating recording will record it. */
    Ringed,
    /** The red REC tag: it is recording now. */
    Rec,
    /** A hollow orange dot: it won't record because the tuners are taken. */
    Clash,
}

/** States whose mark stands for a recording still to come or under way; any other offers Record again. */
private val LIVE_RECORDING_STATES = setOf("scheduled", "recording", "conflict")

data class RecordingMark(val id: String, val state: String, val series: Boolean = false, val canCancel: Boolean = false, val reason: String? = null) {
    val label: String get() = recordingStateLabel(state, series, reason)
    val cancelAction: String? get() = if (canCancel && state in listOf("scheduled", "recording", "conflict", "gone")) {
        if (state == "recording") "Stop recording" else "Don’t record"
    } else null

    /** The guide's mark, or null where it draws none (skipped, gone, done, partial, failed…). */
    val glyph: RecordingGlyph? get() = when (state) {
        "scheduled" -> if (series) RecordingGlyph.Ringed else RecordingGlyph.Dot
        "recording" -> RecordingGlyph.Rec
        "conflict" -> RecordingGlyph.Clash
        else -> null
    }

    /** This mark stands for a recording set or under way, so the programme offers no Record. */
    val active: Boolean get() = state in LIVE_RECORDING_STATES

    /** What a guide cell's accessible name ends with; nothing where no mark is drawn. */
    val spoken: String get() = when (glyph) {
        RecordingGlyph.Dot -> ", will record"
        RecordingGlyph.Ringed -> ", will record, series"
        RecordingGlyph.Rec -> ", recording"
        RecordingGlyph.Clash -> ", " + label.replaceFirstChar { it.lowercase() }
        null -> ""
    }
}

data class RecordingGap(val startMs: Long, val endMs: Long, val reason: String) {
    /** "Missing 9:12–9:14 PM: the signal dropped" */
    fun label(format: LiveClockFormat): String {
        val span = format.span(startMs, endMs)
        return recordingReasonText(reason)?.let { "Missing $span: ${it.replaceFirstChar { c -> c.lowercase() }}" } ?: "Missing part · $span"
    }
}

/** A recording that holds the tuners another one needs (`conflictsWith`). One already [running] can't give way. */
data class RecordingConflict(
    val id: String,
    val title: String,
    val number: String,
    val channelName: String,
    val startMs: Long,
    val endMs: Long,
    val userName: String = "",
    val running: Boolean = false,
)

data class LiveRecording(
    val id: String,
    val title: String,
    val channelId: String,
    val number: String,
    val channelName: String,
    val startMs: Long,
    val endMs: Long,
    val state: String,
    val ruleId: String? = null,
    val userName: String = "",
    val sourceId: String = "",
    val programmeId: String? = null,
    val episodeTitle: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val series: Boolean = false,
    val reason: String? = null,
    val startedAtMs: Long? = null,
    val endedAtMs: Long? = null,
    val padBefore: Int = 1,
    val padAfter: Int = 2,
    val bytes: Long = 0,
    val gaps: List<RecordingGap> = emptyList(),
    val mediaFileId: Long? = null,
    val canCancel: Boolean = false,
    val picture: String? = null,
    val canDelete: Boolean = false,
    /** On a clash: the recordings holding the tuners. */
    val conflictsWith: List<RecordingConflict> = emptyList(),
) {
    val active: Boolean get() = state == "recording"
    val playable: Boolean get() = active || (state in listOf("done", "partial") && mediaFileId != null)
    val label: String get() = recordingStateLabel(state, series, reason, conflictsWith.map { it.title })

    /** The clashing recordings that can still give way to this one. */
    val yielding: List<RecordingConflict> get() = conflictsWith.filter { !it.running }
    fun elapsedMs(nowMs: Long): Long = (nowMs.coerceAtMost(endedAtMs ?: endMs) - (startedAtMs ?: startMs)).coerceAtLeast(0)
    fun progressAt(nowMs: Long): Float = ((nowMs - startMs).toDouble() / (endMs - startMs).coerceAtLeast(1)).coerceIn(0.0, 1.0).toFloat()
    /** Watch from the start (0, not resuming earlier progress) while it records; Watch, resuming, once it is a file. */
    fun playRequest(): PlayRequest? = when {
        active -> PlayRequest(0, startMs = 0, title = title, subtitle = episodeTitle, recordingId = id)
        state in listOf("done", "partial") && mediaFileId != null -> PlayRequest(mediaFileId, title = title, subtitle = episodeTitle)
        else -> null
    }
}

data class LiveRecordingRule(
    val id: String,
    val title: String,
    val kind: String,
    val newOnly: Boolean = true,
    val anyChannel: Boolean = false,
    val keep: Int = 0,
    val padBefore: Int = 1,
    val padAfter: Int = 2,
    val userName: String = "",
    val channelId: String? = null,
    val number: String? = null,
    val repeat: String? = null,
    val canCancel: Boolean = false,
    /** A manual rule's first start and its zone, which name a weekly rule's day. */
    val startMs: Long? = null,
    val zone: String? = null,
    /** A manual rule's days as the server keeps them: bit 0 Sunday to bit 6 Saturday. */
    val days: Int? = null,
) {
    val label: String get() = if (kind == "manual") when (repeat) {
        "daily", "weekdays" -> manualRepeatLabel(repeat, 0)
        "weekly" -> startMs?.let { manualRepeatLabel("weekly", it, zone?.let { z -> runCatching { ZoneId.of(z) }.getOrNull() } ?: ZoneId.systemDefault()) }
            ?: days?.takeIf { Integer.bitCount(it) == 1 }?.let { "Every " + weekdayName(DayOfWeek.SUNDAY.plus(Integer.numberOfTrailingZeros(it).toLong())) }
            ?: "Every week"
        else -> "Repeating recording"
    } else listOf(if (newOnly) "New episodes only" else "Every episode", if (anyChannel) "Any channel" else number?.let { "Channel $it" } ?: "This channel", if (keep == 0) "Keep all" else "Keep the latest $keep").joinToString(" · ")
    val options: RecordingOptions get() = RecordingOptions(series = kind == "series", newOnly = newOnly, anyChannel = anyChannel, keep = keep, padBefore = padBefore, padAfter = padAfter)
}

data class RecordingPage(val items: List<LiveRecording> = emptyList(), val rules: List<LiveRecordingRule> = emptyList(), val nextCursor: String? = null, val tuners: Int? = null, val freeBytes: Long? = null)

data class RecordingOptions(val series: Boolean = false, val newOnly: Boolean = true, val anyChannel: Boolean = false, val keep: Int = 0, val padBefore: Int = 1, val padAfter: Int = 2, val customPadding: Boolean = padBefore != 1 || padAfter != 2) {
    val valid: Boolean get() = keep in 0..10 && padBefore in RECORDING_PADDING && padAfter in RECORDING_PADDING
}

/** The longest manual recording (PLAN-LIVE-TV-DVR.md §3.1, New recording). */
const val MANUAL_RECORDING_MAX_MS = 12 * 3_600_000L

/** The form's repeat choices: "Once", "Every day", "Weekdays", "Every Tuesday" (the start's day). */
fun manualRepeatLabel(repeat: String, startMs: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String = when (repeat) {
    "daily" -> "Every day"
    "weekdays" -> "Weekdays"
    "weekly" -> "Every " + weekdayName(Instant.ofEpochMilli(startMs).atZone(zone).dayOfWeek, locale)
    else -> "Once"
}

private fun weekdayName(day: DayOfWeek, locale: Locale = Locale.getDefault()) = day.getDisplayName(TextStyle.FULL, locale)

/** Instants are sent with an explicit zone for repeating wall-clock times across DST. */
data class ManualRecording(val channelId: String, val name: String, val startMs: Long, val endMs: Long, val repeat: String = "once", val zone: String = ZoneId.systemDefault().id) {
    /** Shown before Save, which stays off while it says something: an end before the start, or more than 12 hours. */
    val timingProblem: String? get() = when {
        endMs <= startMs -> "The end must be after the start."
        endMs - startMs > MANUAL_RECORDING_MAX_MS -> "A manual recording can be at most 12 hours."
        else -> null
    }

    fun validation(nowMs: Long): String? = when {
        channelId.isBlank() -> "Choose a channel."
        name.isBlank() -> "Give the recording a name."
        name.length > 200 -> "Use a name of 200 characters or fewer."
        timingProblem != null -> timingProblem
        endMs <= nowMs -> "Choose a time that hasn’t ended."
        repeat !in listOf("once", "daily", "weekdays", "weekly") -> "Choose how often to record."
        runCatching { ZoneId.of(zone) }.isFailure -> "Choose a valid time zone."
        else -> null
    }
}

/**
 * The words for the server's reason codes, as the web and Apple apps say them.
 * Null for a code this app doesn't know: callers fall back to a general line,
 * never the code.
 */
fun recordingReasonText(reason: String?): String? = when (reason) {
    "already_recorded" -> "Already recorded"
    "already_scheduled" -> "Already set to record"
    "in_library" -> "Already in your library"
    "not_new" -> "Not a new episode"
    "rule_filtered" -> "Left out by the series options"
    "listing_gone" -> "No longer in the listings"
    "tuners_busy" -> "Won’t record: the tuners are busy"
    "no_tuners" -> "Won’t record: no tuner is free for recordings"
    "missed" -> "Missed: the server wasn’t running"
    "disk_full" -> "Stopped: the disk is nearly full"
    "folder_unavailable" -> "The recordings folder isn’t available"
    "output_changed" -> "Something else changed the recording’s file"
    "output_missing" -> "The recording’s file is missing"
    "server_restarted" -> "The server restarted during it"
    "signal_lost" -> "The signal dropped"
    "stopped" -> "Stopped early"
    "stop_requested" -> "Stopping…"
    "finish_pending" -> "Finishing…"
    "started_late" -> "Started late"
    else -> null
}

/** "Won’t record: Inspector Vane and Coastline Rescue have the tuners" */
fun recordingClashLabel(reason: String?, holders: List<String> = emptyList()): String {
    if (reason == "no_tuners") return recordingReasonText(reason)!!
    val names = holders.filter { it.isNotBlank() }.distinct()
    return when (names.size) {
        0 -> recordingReasonText("tuners_busy")!!
        1 -> "Won’t record: ${names[0]} has the tuner"
        else -> "Won’t record: ${namesList(names)} have the tuners"
    }
}

/** "Coastline Rescue", "Coastline Rescue and Harbor Evening News", "A, B and C" */
private fun namesList(names: List<String>): String = if (names.size <= 1) names.joinToString() else names.dropLast(1).joinToString(", ") + " and " + names.last()

// The clash a Record or a Won't record row shows, to choose which recording gives way (§3.1).

/** "The tuners are busy at 9:00 PM" */
fun LiveRecording.clashTitle(format: LiveClockFormat): String =
    if (reason == "no_tuners") "No tuner is free for recordings" else "The tuners are busy at ${format.time(startMs)}"

fun LiveRecording.clashIntro(): String = when {
    reason == "no_tuners" -> "Every tuner is kept for watching live, so $title won’t record. An admin can change that in the web app’s Live TV settings."
    conflictsWith.isEmpty() -> "Other recordings have the tuners then, so $title won’t record unless it goes ahead of them."
    yielding.isEmpty() -> "They’re already recording, so none can give way. $title stays under Won’t record."
    else -> "To record $title on $number, choose one that won’t record."
}

/** The choice on each holder that can give way. */
fun LiveRecording.clashInstead(): String = "Record $title instead"

/** The TV's choice, one row per holder: "Record this instead of Conference Football" */
fun LiveRecording.clashInsteadOf(holder: RecordingConflict): String = "Record this instead of ${holder.title}"

/** Leaving the clash as it is: "Keep Conference Football and Harbor Evening News" */
fun LiveRecording.clashKeep(): String = conflictsWith.map { it.title }.filter { it.isNotBlank() }.distinct().takeIf { it.isNotEmpty() }?.let { "Keep ${namesList(it)}" } ?: "Keep it as it is"

/** What keeping the holders means for this one. */
fun LiveRecording.clashKeepNote(): String = "$title stays under Won’t record in Recordings."

/** "4.1 · 9:00–10:30 PM" */
fun RecordingConflict.line(format: LiveClockFormat): String = "$number · ${format.span(startMs, endMs)}"

/** "Set by Alex" */
fun RecordingConflict.setBy(): String? = userName.takeIf { it.isNotBlank() }?.let { "Set by $it" }

/** A holder already recording can't give way, and says so. */
const val RECORDING_CLASH_RUNNING = "Recording now"

/** These labels are shared by list rows, details, cells and their accessible names. */
fun recordingStateLabel(state: String, series: Boolean = false, reason: String? = null, holders: List<String> = emptyList()): String = when (state) {
    "scheduled" -> if (series) "Will record · Series" else "Will record"
    "recording" -> if (reason in listOf("stop_requested", "finish_pending")) recordingReasonText(reason)!! else "Recording now"
    "conflict" -> recordingClashLabel(reason, holders)
    "skipped" -> recordingReasonText(reason) ?: "Won’t record"
    "gone" -> recordingReasonText("listing_gone")!!
    "partial" -> if (reason == "finish_pending") recordingReasonText(reason)!! else "Partly recorded" + (recordingReasonText(reason)?.let { " · $it" } ?: "")
    "failed" -> recordingFailure(reason) ?: "Didn’t record"
    "cancelled" -> "Cancelled"
    "deleted" -> "Deleted"
    "done" -> if (reason == "finish_pending") recordingReasonText(reason)!! else "Recorded"
    else -> "Recording status unavailable"
}

/** Why a recording failed; a failed one's disk_full means it never had the space to start. */
fun recordingFailure(reason: String?): String? = if (reason == "disk_full") "Not enough free space" else recordingReasonText(reason)

fun LiveProgramme.recordingSpoken(): String = recording?.spoken.orEmpty()

fun recordingTime(text: String): Long? = try { Instant.parse(text).toEpochMilli() } catch (_: DateTimeParseException) { null }

/**
 * A recording still recording, from the decision's `growing`. Its recorded
 * length grows from what the server had when it decided ([recordedMs], the
 * decision's duration) by this device's monotonic time since ([anchoredAtMs],
 * set by the player), so a device clock that disagrees with the server's
 * can't stretch or shrink it; it never grows past the planned [startedAtMs]–[endsAtMs].
 * Nothing polls, and seeks never go past it.
 */
data class GrowingRecording(val startedAtMs: Long, val endsAtMs: Long, val recordedMs: Long = 0, val anchoredAtMs: Long = 0) {
    /** The most it can record, from the server's start and end. */
    val plannedMs: Long get() = (endsAtMs - startedAtMs).coerceAtLeast(0)

    /** Anchored on what the server had recorded, at [elapsedMs] on the monotonic clock. */
    fun anchored(recordedMs: Long, elapsedMs: Long) = copy(recordedMs = recordedMs.coerceAtLeast(0), anchoredAtMs = elapsedMs)

    /** The recorded length at [elapsedMs] on the same monotonic clock as [anchoredAtMs]. */
    fun durationAt(elapsedMs: Long): Long = (recordedMs + (elapsedMs - anchoredAtMs).coerceAtLeast(0)).coerceAtMost(maxOf(plannedMs, recordedMs))

    fun seekLimitAt(elapsedMs: Long): Long = (durationAt(elapsedMs) - GROWING_EDGE_MARGIN_MS).coerceAtLeast(0)

    /** Its end has come: it grows no more, so the Recording label and Go to now go. */
    fun endedAt(elapsedMs: Long): Boolean = durationAt(elapsedMs) >= plannedMs
}

/** How far before the recorded edge seeks and Go to now land, so the player has something to play. */
const val GROWING_EDGE_MARGIN_MS = 3_000L

/** Go to now shows once the play is this far behind what has been recorded. */
const val GO_TO_NOW_BEHIND_MS = 10_000L

/** Behind a growing recording's edge by more than [GO_TO_NOW_BEHIND_MS]. */
fun PlaybackProgress.behindRecording(): Boolean = durationMs - positionMs > GO_TO_NOW_BEHIND_MS

/** Where Go to now goes: just before the recorded edge. */
fun PlaybackProgress.recordingEdgeMs(): Long = (durationMs - GROWING_EDGE_MARGIN_MS).coerceAtLeast(0)

/** The live player's warning before a recording takes its tuner: "At 9:00 PM a recording needs this tuner. Watching stops then." */
fun LiveSession.takeoverMessage(format: LiveClockFormat): String? =
    takeoverAtMs?.let { "At ${format.time(it)} a recording needs this tuner. Watching stops then." }

/** One warning: OK hides it until another recording or time is named. */
fun LiveSession.takeoverKey(): String? = takeoverAtMs?.let { "${takeoverRecordingId.orEmpty()}@$it" }

/** The warning's Don't record it, for whoever set the recording and for admins. */
const val LIVE_TAKEOVER_DONT_RECORD = "Don’t record it"

/** Finished recordings remain ordinary library files with a small optional source label. */
data class RecordingProvenance(val channel: String, val number: String, val startedAtMs: Long, val gaps: Int = 0, val adBreaks: String = "none") {
    fun label(format: LiveClockFormat): String = "Recorded from $number on ${format.dayTime(startedAtMs)}" + if (gaps > 0) " · Partly recorded" else ""
}

/** The server retains this recording identity after releasing a live viewer’s tuner. */
fun LiveSession.takeoverPlayRequest(): PlayRequest? = if (reason != "recording" || !state.terminal) null else takeoverRecording()

/** The recording named to take this tuner, whatever the session's state says yet. */
fun LiveSession.takeoverRecording(): PlayRequest? {
    val recording = takeoverRecordingId?.takeIf { it.isNotBlank() && it.length <= 64 } ?: return null
    // Watch it from the start: from 0, not where an earlier play of it stopped.
    return PlayRequest(0, startMs = 0, title = takeoverTitle?.takeIf { it.isNotBlank() } ?: "Live TV recording", recordingId = recording)
}

/** How far this device's clock may run behind the server's when judging a takeover due. */
const val LIVE_TAKEOVER_CLOCK_SLACK_MS = 10_000L

/** The recording's start, when the server takes this tuner, has come. */
fun LiveSession.takeoverDue(nowMs: Long): Boolean = takeoverAtMs?.let { nowMs >= it - LIVE_TAKEOVER_CLOCK_SLACK_MS } == true
