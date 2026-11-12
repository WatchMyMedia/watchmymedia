package media.watchmy.client.tv.settings

import media.watchmy.client.model.SegmentKind

/** The note beside a skip row on the TV, which has room for it (android-tv/Settings.dc.html). */
fun skipNote(kind: SegmentKind): String? = if (kind == SegmentKind.Credits) "Hidden when the Next Episode card shows" else null
