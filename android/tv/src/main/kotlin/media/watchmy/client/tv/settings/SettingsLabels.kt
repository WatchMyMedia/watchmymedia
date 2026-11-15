package media.watchmy.client.tv.settings

import media.watchmy.client.model.MediaRequest
import media.watchmy.client.model.PlaybackPreferences
import media.watchmy.client.model.RequestStatus
import media.watchmy.client.model.RowLayoutEntry
import media.watchmy.client.model.SegmentKind
import media.watchmy.client.model.SubtitleChoice
import media.watchmy.client.model.subtitleLanguageLabel
import java.util.Locale

/** The note beside a skip row on the TV, which has room for it (android-tv/Settings.dc.html). */
fun skipNote(kind: SegmentKind): String? = if (kind == SegmentKind.Credits) "Hidden when the Next Episode card shows" else null

/** Under every choice on a failed row: how to try again with a remote. */
internal const val PRESS_OK_AGAIN = "Press OK to try again."

/**
 * Audio & subtitles' state line in the groups (android-tv/SettingsGroups):
 * the first language and how subtitles start, "English · for foreign audio".
 */
internal fun languagesSummary(playback: PlaybackPreferences, audioLanguages: Boolean, locale: Locale): String {
    val first = (if (audioLanguages) playback.audio.languages.firstOrNull() else null) ?: playback.subtitles.languages.firstOrNull()
    val language = first?.let { subtitleLanguageLabel(it, locale) } ?: "No languages yet"
    val subtitles = when (playback.subtitles.choice) {
        SubtitleChoice.Off -> "subtitles off"
        SubtitleChoice.On -> "subtitles on"
        SubtitleChoice.Forced -> "forced subtitles only"
        SubtitleChoice.ForeignAudio -> "for foreign audio"
    }
    return "$language · $subtitles"
}

/** Home rows' state line: "8 rows · 2 hidden". */
internal fun rowsSummary(entries: List<RowLayoutEntry>): String {
    val hidden = entries.count { it.hidden }
    val shown = entries.size - hidden
    val rows = if (shown == 1) "1 row" else "$shown rows"
    return if (hidden == 0) rows else "$rows · $hidden hidden"
}

/** Your requests' state line: how many are waiting, else how many there are. */
internal fun requestsSummary(mine: List<MediaRequest>?): String {
    if (mine == null) return "Where each one stands"
    val waiting = mine.count { it.status == RequestStatus.Pending }
    return when {
        waiting > 0 -> "$waiting waiting"
        mine.isEmpty() -> "None yet"
        mine.size == 1 -> "1 request"
        else -> "${mine.size} requests"
    }
}

/** The Inbox's state line: "3 to look at". */
internal fun inboxSummary(count: Int?): String = when (count) {
    null, 0 -> "Nothing to look at"
    else -> "$count to look at"
}

/** "Home’s", "TV Shows’": a screen's name as the owner of its rows. */
internal fun possessive(name: String): String = if (name.endsWith("s")) "$name’" else "$name’s"
