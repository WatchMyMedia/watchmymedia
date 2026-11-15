package media.watchmy.client.tv.settings

import media.watchmy.client.model.SegmentKind

/** The note beside a skip row on the TV, which has room for it (android-tv/Settings.dc.html). */
fun skipNote(kind: SegmentKind): String? = if (kind == SegmentKind.Credits) "Hidden when the Next Episode card shows" else null

/** Under every choice on a failed row: how to try again with a remote. */
internal const val PRESS_OK_AGAIN = "Press OK to try again."

/** The Inbox's state line: "3 to look at". */
internal fun inboxSummary(count: Int?): String = when (count) {
    null, 0 -> "Nothing to look at"
    else -> "$count to look at"
}

/** "Home’s", "TV Shows’": a screen's name as the owner of its rows. */
internal fun possessive(name: String): String = if (name.endsWith("s")) "$name’" else "$name’s"
