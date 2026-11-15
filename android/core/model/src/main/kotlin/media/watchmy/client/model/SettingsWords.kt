package media.watchmy.client.model

// Settings as groups (PLAN-ANDROID-SETTINGS.md §4–§5): the groups, their state
// lines and the sentences the phone, the tablet and the TV share, named and
// ordered as the web app's Settings tabs so the same setting is found in the
// same place on every platform.

/**
 * Settings' groups, in order. The first four are preferences; [Account],
 * [Requests] and [About] sit below a divider, as the web keeps them apart;
 * [Inbox] and [ServerSettings] are the Server group, for admins the server
 * allows. A screen leaves out the ones it can't show (Downloads on a TV, a
 * group the server is too old for).
 */
enum class SettingsGroup(val title: String) {
    Playback("Playback"),
    Languages("Audio & subtitles"),
    Downloads("Downloads"),
    HomeRows("Home rows"),
    Account("Account & devices"),
    Requests("Your requests"),
    About("About"),
    Inbox("Inbox"),
    ServerSettings("Server settings"),
    ;

    /** Below the divider that keeps account and app information apart from preferences. */
    val belowDivider: Boolean get() = this == Account || this == Requests || this == About

    /** In the Server group, for admins. */
    val server: Boolean get() = this == Inbox || this == ServerSettings
}

/** The heading over settings kept on this device: "On this phone", "On this TV". */
fun onThisDevice(word: String): String = "On this $word"

/** The heading over settings that follow the account. */
const val ON_EVERY_DEVICE = "Your account · on every device"

/** Playback's state line: "Automatic · next episode plays by itself". */
fun playbackSummary(quality: StreamQuality, autoplayNext: Boolean): String =
    quality.shortLabel + " · " + if (autoplayNext) "next episode plays by itself" else "next episode waits for you"

/** Account & devices' state line: "Sam · 4 devices signed in"; the name alone until the devices are known. */
fun accountSummary(name: String, devices: Int?): String = when (devices) {
    null -> name
    1 -> "$name · 1 device signed in"
    else -> "$name · $devices devices signed in"
}

/** About's state line: "App 1.5.0 · Living room 1.5.0". */
fun aboutSummary(appVersion: String, serverName: String?, serverVersion: String?): String =
    listOfNotNull("App $appVersion", serverName?.let { name -> listOfNotNull(name, serverVersion).joinToString(" ") }).joinToString(" · ")

/** Home rows' state line: "8 rows on Home · 2 hidden"; without "on Home" where the group's name says it ([onHome] false). */
fun rowsSummary(shown: Int, hidden: Int, onHome: Boolean = true): String =
    (if (shown == 1) "1 row" else "$shown rows") + (if (onHome) " on Home" else "") + (if (hidden > 0) " · $hidden hidden" else "")

/** Your requests' state line: "2 waiting · 1 downloading"; [short] keeps its first part, "2 waiting", where there is little room. */
fun requestsSummary(requests: List<MediaRequest>, short: Boolean = false): String {
    if (requests.isEmpty()) return if (short) "None" else "Nothing asked for yet"
    val waiting = requests.count { it.status == RequestStatus.Pending }
    val downloading = requests.count { it.download != null && it.status != RequestStatus.Available }
    val parts = listOfNotNull("$waiting waiting".takeIf { waiting > 0 }, "$downloading downloading".takeIf { downloading > 0 })
    val count = if (requests.size == 1) "1 request" else "${requests.size} requests"
    return when {
        parts.isEmpty() -> if (short) count else "$count · nothing waiting"
        short -> parts.first()
        else -> parts.joinToString(" · ")
    }
}

/** The settings a server with [features] is too old for, by the names people know them by; they are hidden, so About says so. */
fun settingsTheServerLacks(features: ServerFeatures): List<String> = listOfNotNull(
    "Audio & subtitles".takeIf { !features.subtitlePreferences },
    "Audio languages".takeIf { features.subtitlePreferences && !features.audioLanguages },
    "Home rows".takeIf { !features.rows },
)

/**
 * About's notice when the server is older than [missing] need (PLAN-ANDROID-SETTINGS.md §4.7):
 * which version it is on, what this app leaves out, and who can update it.
 */
fun olderServerNotice(serverName: String, version: String, missing: List<String>, admin: Boolean): SettingsNotice {
    val names = if (missing.size == 1) missing[0] else missing.dropLast(1).joinToString(", ") + " and " + missing.last()
    val who = if (admin) "You can update it where it runs." else "Whoever runs $serverName can update it."
    return SettingsNotice(
        "$serverName is on an older version",
        "It is on $version. $names need${if (missing.size == 1) "s" else ""} a newer server, so this app doesn’t show ${if (missing.size == 1) "it" else "them"}. $who",
    )
}

/** A skip row's name where each part is a row of its own (the TV): "Skip intro", "Skip ads". */
val SegmentKind.skipRowLabel: String get() = "Skip " + settingsLabel.lowercase()

/** What a skip mode does to [kind], under it in a list of the four: the web's sentence for it. */
fun SkipMode.explanation(kind: SegmentKind): String = when (this) {
    SkipMode.None -> "No button. " + when (kind) {
        SegmentKind.Intro -> "The intro plays."
        SegmentKind.Recap -> "The recap plays."
        SegmentKind.Credits -> "The credits play."
        SegmentKind.Preview -> "The previews play."
        SegmentKind.Commercial -> "The ads play."
    }
    SkipMode.Ask -> "Shows a Skip button."
    SkipMode.Auto -> "Skips after one second."
    SkipMode.AutoDelay -> "Shows the button filling up, then skips."
}

/** Under the skip choices: a part someone seeks into is never skipped. */
const val SKIP_SEEK_NOTE = "A part you seek into is never skipped for you."

/** A change that didn't save: the server's sentence, and whether the server couldn't be reached at all. */
data class SaveFailure(val reason: String, val unreachable: Boolean)

/**
 * The failed row's line, with what the control is back to: "Couldn't save, so
 * it's still On. Check your connection." The screen adds its own way to try
 * again (a button on the phone, "Press OK to try again." on the TV).
 */
fun SaveFailure.rowLine(still: String): String =
    "Couldn’t save, so it’s still $still. " + if (unreachable) "Check your connection." else reason

/** The same for a list that is back as it was: "Couldn't save, so your audio languages are as they were." */
fun SaveFailure.listLine(list: String): String =
    "Couldn’t save, so your ${list.lowercase()} are as they were. " + if (unreachable) "Check your connection." else reason

/** Signing this device out: the dialog's title. */
fun signOutTitle(deviceWord: String): String = "Sign out of this $deviceWord?"

/** What stays when this device signs out. */
fun signOutStays(serverName: String): String = "Your account on $serverName, with what you’ve watched and your settings."

/** Signing another device out: the dialog's title. */
fun revokeTitle(deviceName: String): String = "Sign out $deviceName?"

/** What signing another device out does. */
const val REVOKE_BODY = "It is signed out at once, and needs your password or a code to sign in again."

/** A notice on Settings after something was done, until the person leaves the page or puts it away. */
data class SettingsNotice(val title: String, val body: String)

/** After another device was signed out. */
fun revokedNotice(deviceName: String) = SettingsNotice("$deviceName is signed out", "It needs your password or a code to sign in again.")

/** After the password was changed, with how many other devices it signed out. */
fun passwordChangedNotice(signedOut: Int) = SettingsNotice(
    "Your password is changed",
    when (signedOut) {
        0 -> "No other device was signed in."
        1 -> "1 other device was signed out. It will ask for the new password, or a code from this device."
        else -> "$signedOut other devices were signed out. They will ask for the new password, or a code from this device."
    },
)

/** The web app's Settings on [baseUrl], which Server settings opens (PLAN-ANDROID-SETTINGS.md §5.8). */
fun serverSettingsUrl(baseUrl: String): String = baseUrl.trimEnd('/') + "/settings"

/** The web app's Account & devices on [baseUrl], where a TV sends someone to change their password. */
fun accountSettingsUrl(baseUrl: String): String = baseUrl.trimEnd('/') + "/settings/account"
