package media.watchmy.client.mobile.settings

import androidx.compose.runtime.Immutable
import media.watchmy.client.mobile.downloads.usedSize
import media.watchmy.client.model.MediaRequest
import media.watchmy.client.model.RowLayoutEntry
import media.watchmy.client.model.SettingsGroup
import media.watchmy.client.model.StreamQuality
import media.watchmy.client.model.aboutSummary
import media.watchmy.client.model.accountSummary
import media.watchmy.client.model.playbackSummary
import media.watchmy.client.model.plural
import media.watchmy.client.model.requestsSummary
import media.watchmy.client.model.rowsSummary
import media.watchmy.client.model.shortLabel
import media.watchmy.client.presentation.Load
import media.watchmy.client.presentation.settings.AccountLoad

// What each group's row says is chosen (PLAN-ANDROID-SETTINGS.md §4.1–§4.2),
// in the phone's line under the name and the tablet's shorter value beside it.

/** A group's row: [long] under its name on a phone, [short] beside it on a tablet, [warn] with a warning mark. */
@Immutable
internal data class GroupLine(val long: String?, val short: String?, val warn: Boolean = false)

/** What the overview knows when it draws the groups' lines. */
@Immutable
internal data class OverviewFacts(
    val device: String,
    val quality: StreamQuality,
    val autoplayNext: Boolean,
    val account: AccountLoad,
    val online: Boolean,
    /** Audio & subtitles' summary ("English · For foreign audio"), or null where the server has none. */
    val languages: String?,
    val downloads: Int,
    val downloadBytes: Long,
    val wifiOnly: Boolean,
    /** Home's rows, or null where the server has none. */
    val rows: Load<List<RowLayoutEntry>>?,
    val userName: String,
    val devices: Int?,
    /** The person's requests, or null where requests aren't possible. */
    val requests: Load<List<MediaRequest>>?,
    val inbox: Int?,
    val appVersion: String,
    val serverName: String?,
    val serverVersion: String?,
    /** Live TV reminders are on here, and Android won't show them. */
    val remindersBlocked: Boolean,
)

/** Each group's line, from [facts]; a group the overview leaves out may be missing. */
internal fun groupLines(facts: OverviewFacts): Map<SettingsGroup, GroupLine> {
    val failed = facts.account is AccountLoad.Failed
    val gone = if (!facts.online) "Needs a connection" else "Didn’t load"
    val quality = facts.quality.shortLabel
    val lines = mutableMapOf<SettingsGroup, GroupLine>()

    lines[SettingsGroup.Playback] = when {
        facts.remindersBlocked -> GroupLine("Live TV reminders can’t show on this ${facts.device}", "Reminders can’t show", warn = true)
        failed -> GroupLine("$quality on this ${facts.device} · the rest ${if (facts.online) "didn’t load" else "needs a connection"}", "$quality · the rest ${if (facts.online) "didn’t load" else "needs a connection"}")
        else -> GroupLine(playbackSummary(facts.quality, facts.autoplayNext), "$quality · autoplay ${if (facts.autoplayNext) "on" else "off"}")
    }
    facts.languages?.let { lines[SettingsGroup.Languages] = if (failed) GroupLine(gone, gone) else GroupLine(it, it) }
    lines[SettingsGroup.Downloads] = if (facts.downloads == 0) GroupLine(
        "Nothing downloaded · ${if (facts.wifiOnly) "Wi-Fi only" else "on mobile data too"}",
        "None",
    ) else GroupLine(
        "${plural(facts.downloads, "download")} · ${usedSize(facts.downloadBytes)} · ${if (facts.wifiOnly) "Wi-Fi only" else "on mobile data too"}",
        "${facts.downloads} · ${usedSize(facts.downloadBytes)}",
    )
    facts.rows?.let { rows ->
        lines[SettingsGroup.HomeRows] = when (rows) {
            is Load.Ready -> {
                val shown = rows.data.count { !it.hidden }
                val hidden = rows.data.count { it.hidden }
                GroupLine(rowsSummary(shown, hidden), rowsSummary(shown, hidden, onHome = false))
            }
            is Load.Failed -> GroupLine(gone, gone)
            Load.Loading -> GroupLine(if (failed) gone else "Order, hide and add the rows on Home", null)
        }
    }
    lines[SettingsGroup.Account] = when {
        !facts.online && facts.devices == null -> GroupLine("${facts.userName} · devices need a connection", "Needs a connection")
        else -> GroupLine(accountSummary(facts.userName, facts.devices), facts.devices?.let { if (it == 1) "1 device signed in" else "$it devices signed in" })
    }
    facts.requests?.let { requests ->
        lines[SettingsGroup.Requests] = when (requests) {
            is Load.Ready -> GroupLine(requestsSummary(requests.data), requestsSummary(requests.data, short = true))
            is Load.Failed -> if (!facts.online) GroupLine(gone, gone) else GroupLine("What you asked for, and where it stands", null)
            Load.Loading -> GroupLine("What you asked for, and where it stands", null)
        }
    }
    lines[SettingsGroup.About] = GroupLine(aboutSummary(facts.appVersion, facts.serverName, facts.serverVersion), "App ${facts.appVersion}")
    facts.inbox?.let { count ->
        lines[SettingsGroup.Inbox] = GroupLine(if (count == 0) "Nothing to look at" else "Requests to approve and problems people reported", null)
    }
    lines[SettingsGroup.ServerSettings] = GroupLine("Opens ${facts.serverName ?: "the server"}’s web app in your browser", "Opens the web app")
    return lines
}

