package media.watchmy.client.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/*
 * New episodes' words, as the boards have them,
 * shared by the phone, the tablet and the TV. Episode codes here are not
 * padded ("S3 E7", "S0 E3"), unlike [episodeCode], so every platform reads
 * alike.
 */

/** "S3 E7"; specials are season 0, "S0 E3". */
fun shortEpisodeCode(season: Int, number: Int): String = "S$season E$number"

// --- the notification (phones and tablets) ---

const val NEW_EPISODES_CHANNEL_DESCRIPTION = "When a show you follow gets a new episode"

/** "New episode · The Sleuth Files", "3 new episodes · The Sleuth Files". */
fun NewEpisodeGroup.noticeTitle(): String = "${if (count == 1) "New episode" else "$count new episodes"} · $title"

/**
 * "S3 E7 · The Long Night" for one (just "S3 E7" without a title); "S3 E7 to
 * E9" for several in a season, "S2 E10 to S3 E2" across two. The feed lists
 * the first five, earliest first: with more than were listed, the last
 * listed is not the last, so it says where they start, "From S3 E1".
 */
fun NewEpisodeGroup.noticeText(): String {
    val first = episodes.firstOrNull() ?: return shortEpisodeCode(open.season, open.number)
    if (count <= 1) {
        return listOfNotNull(shortEpisodeCode(first.season, first.number), first.title?.takeIf { it.isNotBlank() }).joinToString(" · ")
    }
    if (count > episodes.size) return "From ${shortEpisodeCode(first.season, first.number)}"
    val last = episodes.last()
    return when {
        last == first -> shortEpisodeCode(first.season, first.number)
        last.season == first.season -> "${shortEpisodeCode(first.season, first.number)} to E${last.number}"
        else -> "${shortEpisodeCode(first.season, first.number)} to ${shortEpisodeCode(last.season, last.number)}"
    }
}

/** The notification for the shows past the first five, which opens Home: "4 more shows have new episodes". It has no text. */
fun newEpisodesMoreTitle(more: Int): String = if (more == 1) "1 more show has new episodes" else "$more more shows have new episodes"

// --- the bell (phone, tablet, TV) ---

fun followLabel(following: Boolean): String = if (following) "Notifications on" else "Notify me"

/** TalkBack's name for the bell, which carries its state. */
fun followSpoken(following: Boolean, show: String): String =
    if (following) "Notifications on for $show. Turn them off" else "Notify me of new episodes of $show"

/** The page's message when the server refused: following ([on]) or stopping. */
fun followRefused(on: Boolean, show: String): String =
    "Couldn’t turn ${if (on) "on" else "off"} notifications for $show. Check your connection and try again."

/** At the limit of 500 follows (the server's 409): the bell stays off. */
const val FOLLOW_LIMIT = "You follow 500 shows, the most you can. Choose Stop notifying for one in Settings › Notifications first."

/** The TV's line under the buttons while the bell has focus, and its Settings pane's line. */
const val FOLLOW_TV_LINE = "Your phone or tablet notifies you. This TV shows new episodes on Home."

// --- the explainer before Android asks (phone and tablet) ---

const val FOLLOW_ASK_TITLE = "Get notified of new episodes"

fun followAskBody(show: String, device: LiveDevice): String =
    "When a new episode of $show reaches the library, this ${device.word} shows a notification."

/** The limits: under the explainer, and Settings' footnote. */
fun newEpisodesLimits(device: LiveDevice): String =
    "Notifications come about once an hour, and later while the ${device.word} is idle. The ${device.word} has to be able to reach your server."

fun followAskNotNow(show: String): String =
    "Not now still follows $show. Its new episodes show on Home, and your other devices still notify you."

// --- Settings › Notifications ---

const val NOTIFICATIONS_LEDE = "The shows you follow are the same on every device. Each device has its own switch."
const val NEW_EPISODES_SWITCH = "New episodes"
const val NEW_EPISODES_SWITCH_LINE = "A notification when a show you follow gets a new episode"
const val NEW_EPISODES_ROW_LINE = "Opens Android’s settings for new episodes"
const val LIVE_REMINDERS_ROW_LINE = "Opens Android’s settings for Live TV reminders"

/** "Live TV reminders are in Playback.", with Playback a link. */
const val REMINDERS_ARE_IN = "Live TV reminders are in "
const val REMINDERS_ARE_IN_LINK = "Playback"

const val SHOWS_YOU_FOLLOW = "Shows you follow"
const val FOLLOWS_EMPTY = "You don’t follow any shows yet. Choose Notify me on a show’s page."
const val STOP_NOTIFYING = "Stop notifying"
const val GO_TO_SHOW = "Go to show"

fun stopNotifyingSpoken(show: String): String = "Stop notifying me of new episodes of $show"

/**
 * A followed show's line: its latest arrival and when, "S3 E7 · 2 days ago";
 * "No new episodes yet" until one arrives after the person followed it (the
 * server's latest counts arrivals from before); "Not in the library now" for
 * a show that has left it (in the warning colour).
 */
fun FollowedShow.line(today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): String {
    if (gone) return FOLLOW_GONE
    val latest = latest?.takeIf { l -> l.arrivedAtMs == null || followedAtMs == null || l.arrivedAtMs >= followedAtMs } ?: return NO_NEW_EPISODES_YET
    val day = latest.arrivedAtMs?.let { formatDay(Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toString(), today) }
    return listOfNotNull(shortEpisodeCode(latest.season, latest.number), day).joinToString(" · ")
}

const val FOLLOW_GONE = "Not in the library now"
const val NO_NEW_EPISODES_YET = "No new episodes yet"

/** TalkBack reads a show as one item: "The Sleuth Files, S3 E7 · 2 days ago". */
fun FollowedShow.spoken(line: String): String = "$title, $line"

/** A stop the server refused, in the row: the screen adds Try again (a button, or the TV's "Press OK to try again."). */
fun stopRefused(show: String, tv: Boolean = false): String =
    "Couldn’t stop notifying, so you still follow $show. " + if (tv) "Check your connection." else "Check your connection and try again."

const val FOLLOWS_FAILED_TITLE = "Couldn’t load the shows you follow"
const val FOLLOWS_FAILED_UNREACHABLE = "The server didn’t answer."

/** The list's failure line: the server's sentence, or that it didn't answer, then what to do. */
fun followsFailedLine(reason: String?): String = "${reason?.trim()?.trimEnd('.')?.plus(".") ?: FOLLOWS_FAILED_UNREACHABLE} Check your connection and try again."

// --- the Settings overview and lists ---

/** The phone's line: "5 shows you follow · this phone notifies you". */
fun notificationsSummary(follows: Int, notifyHere: Boolean, device: String): String = when (follows) {
    0 -> "You don’t follow any shows yet"
    else -> "${followsCount(follows)} · this $device ${if (notifyHere) "notifies you" else "doesn’t notify you"}"
}

/** The tablet's value: "5 shows", "5 shows · off", "None". */
fun notificationsShort(follows: Int, notifyHere: Boolean): String = when (follows) {
    0 -> "None"
    else -> plural(follows, "show") + if (notifyHere) "" else " · off"
}

/** The TV's line: "5 shows you follow", "None". */
fun notificationsTvSummary(follows: Int): String = if (follows == 0) "None" else followsCount(follows)

private fun followsCount(n: Int): String = "${plural(n, "show")} you follow"

/** The overview's line and the tablet's value while Android won't show them. */
fun newEpisodesBlocked(device: String): String = "New episodes can’t show on this $device"
const val NEW_EPISODES_BLOCKED_SHORT = "Can’t show"

// --- when Android won't show them: the card (phone and tablet) ---

/** Which channels Android has off for what is on here. */
data class NotificationsBlocked(
    /** The app's notifications are off: every channel is. */
    val app: Boolean,
    /** Live TV reminders are on here and can't show. */
    val reminders: Boolean,
    /** New episodes are on here (a show followed, the switch on) and can't show. */
    val newEpisodes: Boolean,
) {
    val any: Boolean get() = reminders || newEpisodes
}

/** The overview's card title: what can't show. */
fun NotificationsBlocked.title(device: String): String = when {
    reminders && newEpisodes -> "Live TV reminders and new episodes can’t show on this $device"
    newEpisodes -> newEpisodesBlocked(device)
    else -> "Live TV reminders can’t show on this $device"
}

/** The card's text: the app's notifications off, or which channels are. */
fun NotificationsBlocked.text(): String = when {
    app -> "Notifications are off for WatchMyMedia in Android’s settings."
    reminders && newEpisodes -> "“$LIVE_REMINDER_CHANNEL_NAME” and “$NEW_EPISODES_CHANNEL_NAME” notifications are off for WatchMyMedia in Android’s settings."
    newEpisodes -> "“$NEW_EPISODES_CHANNEL_NAME” notifications are off for WatchMyMedia in Android’s settings."
    else -> "“$LIVE_REMINDER_CHANNEL_NAME” notifications are off for WatchMyMedia in Android’s settings."
}

/** A page's card keeps the overview's text and adds this. */
const val OTHER_DEVICES_STILL = "Your other devices still get them."

/** Playback's card title, as before. */
fun remindersBlockedTitle(device: String): String = "Reminders can’t show on this $device"

const val TURN_ON_NOTIFICATIONS = "Turn on notifications"
