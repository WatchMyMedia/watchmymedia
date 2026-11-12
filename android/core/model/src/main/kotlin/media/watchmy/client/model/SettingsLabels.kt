package media.watchmy.client.model

// Settings' skip rows and signed-in devices, the same on the phone and the
// TV, as the Apple apps' WMMModel Preferences.swift and Formats.swift and
// WMMFeatures AccountWording.swift word them.

/** The segment kinds Settings lists, in its order. */
val SKIP_KINDS: List<SegmentKind> = listOf(SegmentKind.Intro, SegmentKind.Recap, SegmentKind.Credits, SegmentKind.Preview, SegmentKind.Commercial)

/** "Intro", "Previews", "Ads": a skip row's name. */
val SegmentKind.settingsLabel: String
    get() = when (this) {
        SegmentKind.Intro -> "Intro"
        SegmentKind.Recap -> "Recap"
        SegmentKind.Credits -> "Credits"
        SegmentKind.Preview -> "Previews"
        SegmentKind.Commercial -> "Ads"
    }

/** "Never", "Ask", "Auto", "Auto after 3 s". */
val SkipMode.label: String
    get() = when (this) {
        SkipMode.None -> "Never"
        SkipMode.Ask -> "Ask"
        SkipMode.Auto -> "Auto"
        SkipMode.AutoDelay -> "Auto after 3 s"
    }

/** [label] for the phone's narrow segmented buttons, where Auto after 3 s is "Auto 3 s" (android/Settings.dc.html). */
val SkipMode.shortLabel: String get() = if (this == SkipMode.AutoDelay) "Auto 3 s" else label

/** "Android TV", "Web browser": what kind of device a session is. */
val DeviceSession.kindLabel: String
    get() = when {
        kind == DeviceSession.Kind.Browser -> "Web browser"
        platform == "androidtv" -> "Android TV"
        platform == "android" -> "Android"
        platform == "tvos" -> "Apple TV"
        platform == "ios" -> "iPhone or iPad"
        else -> product ?: "Device"
    }

/** This device first, then the most recently seen. */
fun sortSessions(list: List<DeviceSession>): List<DeviceSession> =
    list.sortedWith(compareByDescending<DeviceSession> { it.current }.thenByDescending { it.lastSeenAtMs })
