package media.watchmy.client.model

/*
 * Favourite channels, search across the listings and reminders
 * (PLAN-LIVE-TV-GUIDE.md §5.7 and §6.5, G8), from [LIVE_PERSONAL_API_LEVEL].
 * The wording here is the boards' (LiveGuideExtras, LiveSearch,
 * LiveReminders, TabLiveExtras and the TV's LiveReminder), shared by the
 * phone, the tablet and the TV, and matches the Apple apps' WMMModel LiveWording.swift.
 */

/** The notification channel reminders come on (phones and tablets; TVs show a banner instead). */
const val LIVE_REMINDER_CHANNEL = "live-tv-reminders"
const val LIVE_REMINDER_CHANNEL_NAME = "Live TV reminders"

/** A search needs this many characters, as the server does. */
const val LIVE_SEARCH_MIN_CHARS = 2

/** How long before a programme its reminder may come, in minutes; [LIVE_REMINDER_DEFAULT_LEAD] unless the person picks another. */
val LIVE_REMINDER_LEADS = listOf(0, 1, 5, 10, 15)
const val LIVE_REMINDER_DEFAULT_LEAD = 5

/** What changed for the person signed in: their favourites, or their reminders. */
enum class LivePersonalChange { Favourites, Reminders, Recordings }

/** A programme that matched a search, with its channel. */
data class LiveSearchHit(val channel: LiveChannel, val programme: LiveProgramme)

enum class LiveReminderState {
    /** As set. */
    Set,

    /** A listings refresh moved the programme to [LiveReminder.startMs]; the reminder moved with it. */
    Moved,

    /** No longer in the listings: nothing notifies. */
    Gone,
}

/**
 * One reminder, with its own copy of the programme, as the server keeps it
 * (the listing it was set from may change or go). [channelId] is the
 * person's channel with [number], null when none is available now.
 */
data class LiveReminder(
    val id: String,
    val channelId: String?,
    val number: String,
    val channelName: String?,
    val programmeId: String,
    val startMs: Long,
    val endMs: Long,
    val title: String,
    val episodeTitle: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val state: LiveReminderState = LiveReminderState.Set,
) {
    /** "2.1 HRBR-HD", or the number alone. */
    val channelLine: String get() = listOfNotNull(number, channelName?.takeIf { it.isNotBlank() }).joinToString(" ")

    /** "S3 E14 · Double or Nothing", as a programme's episode line. */
    fun episodeLine(): String? = LiveProgramme(programmeId, startMs, endMs, title, episodeTitle, season, episode).episodeLine()

    /** Notifies: not gone, and the time to come hasn't passed by more than [graceMs]. */
    fun notifies(leadMinutes: Int, nowMs: Long, graceMs: Long = LIVE_REMINDER_GRACE_MS): Boolean =
        state != LiveReminderState.Gone && alertAtMs(leadMinutes) > nowMs - graceMs && startMs + graceMs > nowMs

    /** When it comes: [leadMinutes] before the programme starts. */
    fun alertAtMs(leadMinutes: Int): Long = startMs - leadMinutes * 60_000L
}

/** A reminder still comes this long after its time, so one late alarm (Doze) isn't lost. */
const val LIVE_REMINDER_GRACE_MS = 10 * 60_000L

data class LiveReminders(val items: List<LiveReminder>, val leadMinutes: Int = LIVE_REMINDER_DEFAULT_LEAD) {
    /** The reminder for [programmeId] that is still for that listing (not gone). */
    fun forProgramme(programmeId: String): LiveReminder? = items.firstOrNull { it.programmeId == programmeId && it.state != LiveReminderState.Gone }
}

/** Remind me shows for a programme that starts more than [leadMinutes] ahead of [nowMs]. */
fun LiveProgramme.canRemind(nowMs: Long, leadMinutes: Int): Boolean = startMs - nowMs > leadMinutes * 60_000L

// --- favourites ---

/** The star's accessible name: "Add 4.1 NFLD-HD to favourites", "Remove 4.1 NFLD-HD from favourites". */
fun liveFavouriteLabel(channel: LiveChannel, favourite: Boolean): String =
    if (favourite) "Remove ${channel.number} ${channel.name} from favourites" else "Add ${channel.number} ${channel.name} to favourites"

/** The TV's channel menu: its action and what the channel is now. */
fun liveFavouriteAction(favourite: Boolean): String = if (favourite) "Remove from favourites" else "Add to favourites"
fun liveFavouriteState(favourite: Boolean): String = if (favourite) "A favourite" else "Not a favourite"

// --- lead time ---

/** Settings' choice on the phone: "At start", "5 min". */
fun liveLeadShort(minutes: Int): String = if (minutes == 0) "At start" else "$minutes min"

/** The TV's choice and the menus' value: "When it starts", "5 min before". */
fun liveLeadBefore(minutes: Int): String = if (minutes == 0) "When it starts" else "$minutes min before"

/** The TV panel's note under a choice: the default says so. */
fun liveLeadNote(minutes: Int): String? = "The default".takeIf { minutes == LIVE_REMINDER_DEFAULT_LEAD }

/** The phone's note under the choice: "A reminder comes 5 min before the programme starts, the default." */
fun liveLeadSentence(minutes: Int): String = when (minutes) {
    0 -> "A reminder comes as the programme starts."
    LIVE_REMINDER_DEFAULT_LEAD -> "A reminder comes $minutes min before the programme starts, the default."
    else -> "A reminder comes $minutes min before the programme starts."
}

/** What a reminder says: "Starting in 5 min", or "Starting now" when it comes at the start. */
fun liveReminderHeadline(minutes: Int): String = if (minutes == 0) "Starting now" else "Starting in $minutes min"

/** The notification's text: "Dollar Dash · 2.1 HRBR-HD". */
fun LiveReminder.notificationText(): String = "$title · $channelLine"

/** The TV banner's line: "4.1 NFLD-HD · 9:00 PM · Ridgeway at Lakeport". */
fun LiveReminder.bannerLine(format: LiveClockFormat): String = listOfNotNull(channelLine, format.time(startMs), episodeLine()).joinToString(" · ")

/** The TV banner's accessible name: "Reminder: starting in 5 minutes, Conference Football, 4.1 NFLD-HD". */
fun LiveReminder.bannerSpoken(minutes: Int): String =
    "Reminder: ${if (minutes == 0) "starting now" else "starting in $minutes minutes"}, $title, $channelLine"

// --- the details' reminder status ---

/** Where the reminders come, in the boards' words: this phone, this tablet. */
enum class LiveDevice(val word: String) { Phone("phone"), Tablet("tablet") }

/** The details' status line for a reminder: its title and the line under it. */
data class LiveReminderStatus(val title: String, val detail: String, val gone: Boolean)

fun LiveReminder.status(minutes: Int, device: LiveDevice, format: LiveClockFormat): LiveReminderStatus = when (state) {
    LiveReminderState.Set -> LiveReminderStatus("Reminder set · ${liveLeadMinutes(minutes)}", "It comes on this ${device.word}, and may be up to 10 minutes late.", gone = false)
    LiveReminderState.Moved -> LiveReminderStatus("Moved to ${format.time(startMs)}", "The listings changed, and your reminder moved with it.", gone = false)
    LiveReminderState.Gone -> LiveReminderStatus("No longer in the listings", "It isn’t on $number any more, so no reminder will come.", gone = true)
}

/** "5 min before", "when it starts", in a sentence. */
private fun liveLeadMinutes(minutes: Int): String = if (minutes == 0) "when it starts" else "$minutes min before"

/** The TV hero's line: "Reminder set · 5 min before", "Moved to 9:30 PM · Reminder 5 min before", "Conference Football · No longer in the listings". */
fun LiveReminder.heroLine(minutes: Int, format: LiveClockFormat): String = when (state) {
    LiveReminderState.Set -> "Reminder set · ${liveLeadMinutes(minutes)}"
    LiveReminderState.Moved -> "Moved to ${format.time(startMs)} · Reminder ${liveLeadMinutes(minutes)}"
    LiveReminderState.Gone -> "$title · No longer in the listings"
}

/** A gone reminder's time in the details: "Was Mon 8:30–9:00 PM". */
fun LiveReminder.wasLine(format: LiveClockFormat): String = "Was ${format.weekday(startMs)} ${format.span(startMs, endMs)}"

/** The details' day and times: "Mon 8:00–9:00 PM". */
fun LiveProgramme.dayLine(format: LiveClockFormat): String = "${format.weekday(startMs)} ${format.span(startMs, endMs)}"

// --- search ---

/** A result's when: "Now on 2.1 · 48 min left", "9:00 PM on 7.1", "Tue 7:00 PM on 2.1". */
fun LiveSearchHit.whenLine(nowMs: Long, format: LiveClockFormat): String {
    val p = programme
    if (p.airingAt(nowMs)) return "Now on ${channel.number} · ${liveMinutesLeft(nowMs, p.endMs)}"
    return "${liveDayPrefix(p.startMs, nowMs, format)}${format.time(p.startMs)} on ${channel.number}"
}

/** A result's times on the phone: "Mon 9:00–10:00 PM · LKSD-HD". */
fun LiveSearchHit.timeLine(format: LiveClockFormat): String = "${programme.dayLine(format)} · ${channel.name}"

/** "Tue " for another day than [nowMs]'s; "" for today. */
private fun liveDayPrefix(ms: Long, nowMs: Long, format: LiveClockFormat): String =
    if (sameDay(ms, nowMs, format)) "" else "${format.weekday(ms)} "

private fun sameDay(a: Long, b: Long, format: LiveClockFormat) = format.zoned(a).toLocalDate() == format.zoned(b).toLocalDate()

/** A result's accessible name: "Station 9, S4 E12, The Harbor Fire, 9:00 PM on 7.1, open in the guide". */
fun LiveSearchHit.spokenName(nowMs: Long, format: LiveClockFormat, action: String = "open in the guide"): String =
    listOfNotNull(programme.title, programme.episodeLine()?.replace(" · ", ", "), whenLine(nowMs, format), action).joinToString(", ")

fun LiveSearchHit.remindLabel(nowMs: Long, format: LiveClockFormat): String = "Remind me: ${programme.title}, ${whenLine(nowMs, format)}"
fun LiveSearchHit.reminderSetLabel(): String = "Reminder set for ${programme.title}. Remove it"

/** "Wed 7 PM" where listings end, for the search's notes; null when the server didn't say. */
private fun listedThrough(listedThroughMs: Long?, format: LiveClockFormat): String? = listedThroughMs?.let(format::dayHour)

/** The note under the phone's results. */
fun liveSearchNote(listedThroughMs: Long?, format: LiveClockFormat): String =
    "Search looks at titles and episode titles in the listings, from now until they end${listedThrough(listedThroughMs, format)?.let { " $it" }.orEmpty()}. Choose one to see it in the guide."

/** The note under the tablet's results. */
fun liveSearchNoteShort(listedThroughMs: Long?, format: LiveClockFormat): String =
    "Titles and episode titles, from now until listings end${listedThrough(listedThroughMs, format)?.let { " $it" }.orEmpty()}. Choose one to see it in the guide."

const val LIVE_SEARCH_NONE_TITLE = "Nothing on Live TV matches"

/** The phone's no results: "No programme now or later is called “lighthouse”. …" */
fun liveSearchNone(query: String, listedThroughMs: Long?, format: LiveClockFormat): String =
    "No programme now or later is called “${query.trim()}”. Search looks at titles and episode titles until listings end${listedThrough(listedThroughMs, format)?.let { " $it" }.orEmpty()}, not descriptions."

/** The TV's no results in its On Live TV row. */
fun liveSearchNoneTv(query: String, listedThroughMs: Long?, format: LiveClockFormat): String =
    "Nothing on now or later matches “${query.trim()}”." + (listedThrough(listedThroughMs, format)?.let { " The listings run to $it." } ?: "")

/** The TV's when, which also says a reminder is set: "10:00 PM on 2.1 · Reminder set". */
fun LiveSearchHit.whenLineTv(nowMs: Long, format: LiveClockFormat, reminded: Boolean): String =
    whenLine(nowMs, format) + if (reminded && !programme.airingAt(nowMs)) " · Reminder set" else ""

// --- the reminders list ---

/** The day a reminder is listed under: "Today", "Tomorrow", "Wed". */
fun LiveReminder.dayLabel(nowMs: Long, format: LiveClockFormat): String = format.dayName(startMs, nowMs)

/**
 * The phone's when: "8:30–9:00 PM on 2.1 · in 18 min" within half an hour,
 * "9:00–10:00 PM on 7.1" later today, "Tue 8:30–9:30 PM on 4.1" on another
 * day; a gone one "Was Tue 10:00 PM on 11.1".
 */
fun LiveReminder.whenLine(nowMs: Long, format: LiveClockFormat): String {
    if (state == LiveReminderState.Gone) return "Was ${format.weekday(startMs)} ${format.time(startMs)} on $number"
    val today = sameDay(startMs, nowMs, format)
    val base = "${if (today) "" else "${format.weekday(startMs)} "}${format.span(startMs, endMs)} on $number"
    val soon = startMs - nowMs
    return if (today && soon in 1..SOON_MS) "$base · in ${(soon + 59_999) / 60_000} min" else base
}

/** The note a moved or gone reminder adds: "Moved to 8:30 PM", "No longer in the listings". */
fun LiveReminder.note(format: LiveClockFormat): String? = when (state) {
    LiveReminderState.Set -> null
    LiveReminderState.Moved -> "Moved to ${format.time(startMs)}"
    LiveReminderState.Gone -> "No longer in the listings"
}

/** A row's accessible name: "Dollar Dash, S3 E14 · Double or Nothing, 8:30–9:00 PM on 2.1 · in 18 min, open in the guide". */
fun LiveReminder.spokenName(nowMs: Long, format: LiveClockFormat, action: String? = "open in the guide"): String =
    listOfNotNull(title, episodeLine(), whenLine(nowMs, format), note(format), action).joinToString(", ")

fun LiveReminder.removeLabel(): String = "Remove the reminder for $title"

const val LIVE_REMINDERS_EMPTY_TITLE = "No reminders"
const val LIVE_REMINDERS_EMPTY = "Open a later programme in the guide and choose Remind me."

/** The note under the phone's and tablet's list. */
fun liveRemindersNote(minutes: Int, device: LiveDevice): String =
    "Each reminder comes ${if (minutes == 0) "as the programme starts" else "$minutes min before"}, on this ${device.word}, even when the app is closed. " +
        "Android may deliver one up to 10 minutes late, or later while the ${device.word} is idle. Reminders you set on another device arrive here the next time this app opens."

/** The note under Settings' choice on the phone. */
fun liveReminderSettingsNote(minutes: Int): String =
    "${liveLeadSentence(minutes)} Reminders come from this phone, even when the app is closed, and Android may deliver one up to 10 minutes late, or later while the phone is idle. " +
        "A reminder you set on another device reaches this phone the next time the app opens."

/** The TV's list: "5 reminders · soonest first · each 5 min before it starts". */
fun liveRemindersCountTv(count: Int, minutes: Int): String =
    "${if (count == 1) "1 reminder" else "$count reminders"} · soonest first · each ${if (minutes == 0) "as it starts" else "$minutes min before it starts"}"

/** A TV row's line under the title: "S3 E14 · Bargain Bins · 2.1 HRBR-HD". */
fun LiveReminder.tvLine(): String = listOfNotNull(episodeLine(), channelLine).joinToString(" · ")

/** A TV row's status: "In 18 min", "Tomorrow", "Moved to 9:30 PM", "No longer in the listings". */
fun LiveReminder.tvStatus(nowMs: Long, format: LiveClockFormat): String = when (state) {
    LiveReminderState.Moved -> "Moved to ${format.time(startMs)}"
    LiveReminderState.Gone -> "No longer in the listings"
    LiveReminderState.Set -> if (sameDay(startMs, nowMs, format)) liveIn(nowMs, startMs) else format.dayName(startMs, nowMs)
}

/** A TV row's when: "Mon 8:30 PM"; a gone one "Was Tue 8:30 PM". */
fun LiveReminder.tvWhen(format: LiveClockFormat): String =
    (if (state == LiveReminderState.Gone) "Was " else "") + "${format.weekday(startMs)} ${format.time(startMs)}"

fun LiveReminder.tvRemoveLabel(format: LiveClockFormat): String = "Remove the reminder for $title, ${tvWhen(format)}"

const val LIVE_REMINDERS_EMPTY_TV = "No reminders. Hold OK on a later programme in the guide and choose Remind me."
const val LIVE_REMINDERS_NOTE_TV = "Reminders show on this TV while WatchMyMedia is open. One set on another device reaches this TV the next time the app opens here."
const val LIVE_REMINDERS_MENU_NOTE_TV = "Reminders show while WatchMyMedia is open on this TV."

/** "In 18 min", "In 1 h 48 min". */
fun liveIn(nowMs: Long, startMs: Long): String = liveStartsIn(nowMs, startMs).replaceFirst("Starts in", "In")

/** The TV programme menu's line: "Mon 9:00 PM on 4.1". */
fun LiveProgramme.menuLine(channel: LiveChannel, format: LiveClockFormat): String = "${format.weekday(startMs)} ${format.time(startMs)} on ${channel.number}"

// --- asking for notifications (phone and tablet) ---

fun liveNotifyAskTitle(device: LiveDevice): String = "Get reminders on this ${device.word}?"

fun liveNotifyAskWhat(programme: LiveProgramme, channel: LiveChannel, minutes: Int): String =
    "WatchMyMedia sends a notification ${if (minutes == 0) "when" else "$minutes min before"} ${programme.title} starts, with Watch to open ${channel.number}. Android asks next whether to allow it."

fun liveNotifyAskMore(device: LiveDevice): String =
    "It may come up to 10 minutes late, or later while the ${device.word} is idle. Reminders you set on another device arrive here the next time this app opens. With Not now, the reminder is still saved for your other devices."

/** The list says "in 18 min" for a reminder this close. */
private const val SOON_MS = 30 * 60_000L

// --- when reminders come (phones' alarms, the TV's banner) ---

/** A reminder to come at [atMs]. */
data class LiveReminderAlarm(val reminder: LiveReminder, val atMs: Long, val leadMinutes: Int)

/**
 * The reminders still to come, at their lead time before the start: not
 * gone, not long past ([LIVE_REMINDER_GRACE_MS]); one already due comes now.
 */
fun LiveReminders.alarms(nowMs: Long): List<LiveReminderAlarm> = items
    .filter { it.notifies(leadMinutes, nowMs) }
    .map { LiveReminderAlarm(it, it.alertAtMs(leadMinutes).coerceAtLeast(nowMs), leadMinutes) }
    .sortedBy { it.atMs }

/** The reminders due at [nowMs] (their time has come, within the grace), for the TV's banner. */
fun LiveReminders.due(nowMs: Long): List<LiveReminder> = items.filter { it.notifies(leadMinutes, nowMs) && it.alertAtMs(leadMinutes) <= nowMs }

/** A reminder's key for having come once: its id and its start, so a moved one comes again. */
fun LiveReminder.comeKey(): String = "$id@$startMs"
