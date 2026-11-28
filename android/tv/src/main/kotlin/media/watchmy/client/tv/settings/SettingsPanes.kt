package media.watchmy.client.tv.settings

import android.os.Build
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import media.watchmy.client.model.SERVERS
import media.watchmy.client.model.SUBTITLE_SIZE_MOVED
import media.watchmy.client.model.subtitleSizeMovedFrom
import media.watchmy.client.model.DeviceSession
import media.watchmy.client.model.ON_EVERY_DEVICE
import media.watchmy.client.model.SKIP_KINDS
import media.watchmy.client.model.SegmentKind
import media.watchmy.client.model.TMDB_NOTICE
import media.watchmy.client.model.label
import media.watchmy.client.model.liveLeadBefore
import media.watchmy.client.model.onThisDevice
import media.watchmy.client.model.rowLine
import media.watchmy.client.model.skipRowLabel
import media.watchmy.client.presentation.Load
import media.watchmy.client.presentation.settings.LanguageList
import media.watchmy.client.presentation.settings.SettingKey
import media.watchmy.client.presentation.settings.SettingsViewModel
import media.watchmy.client.ui.PRIVACY_POLICY_URL
import media.watchmy.client.ui.WmmColors

/** What the panes call: the view model's changes, and the screen's panels and pages. */
@Stable
internal interface SettingsCalls {
    fun show(overlay: Overlay)
    fun skip(kind: SegmentKind)
    fun revoke(session: DeviceSession)
    fun retry(key: SettingKey)
    fun reloadAccount()
    fun loadSessions()
    fun setAutoplayNext(enabled: Boolean)
    fun setMatchFrameRate(enabled: Boolean)
    fun setAutomaticSubtitles(enabled: Boolean)
    fun addLanguage(list: LanguageList, tag: String)
    fun editLanguage(list: LanguageList, tag: String)
    fun addTo(list: LanguageList)
    fun openRequests(inbox: Boolean)
}

/** [key]'s row as it stands: Saving…, or failed with what it is still ([still]) and how to try again. */
internal fun SettingsViewModel.State.status(key: SettingKey, still: String): RowStatus = when {
    key in saving -> RowStatus.Saving
    else -> failed[key]?.let { RowStatus.Failed(it.rowLine(still) + " " + PRESS_OK_AGAIN) } ?: RowStatus.Idle
}

/** OK on [key]'s row: tries a failed change again, else [action]. */
internal fun SettingsViewModel.State.onOk(key: SettingKey, calls: SettingsCalls, action: () -> Unit): () -> Unit =
    { if (key in failed) calls.retry(key) else action() }

private fun onOff(on: Boolean) = if (on) "On" else "Off"

/**
 * The row at the top of a pane with account settings when they didn't load:
 * shown while they are Failed, and while they load again after OK.
 */
@Composable
internal fun AccountNotLoaded(state: SettingsViewModel.State, retrying: Boolean, detail: String, calls: SettingsCalls, modifier: Modifier = Modifier) {
    val failed = state.prefsError != null
    if (failed || retrying) NotLoadedRow(detail, retrying = retrying && !failed, onRetry = calls::reloadAccount, modifier = modifier)
}

/**
 * Playback: this TV's settings,
 * then the account's, each under its heading. The five skip rows are one
 * focus stop each and open the side panel, in place of a four-way control
 * a row. [reminderLead] is the Live TV reminders row's value, where
 * reminders exist. [first] goes on the top row.
 */
@Composable
internal fun ColumnScope.PlaybackPane(
    state: SettingsViewModel.State,
    reminderLead: Int?,
    notLoadedDetail: String,
    retrying: Boolean,
    first: FocusRequester,
    calls: SettingsCalls,
) {
    val notLoaded = state.prefsError != null || retrying
    AccountNotLoaded(state, retrying, notLoadedDetail, calls, Modifier.focusRequester(first))
    PaneHeading(onThisDevice("TV"))
    SettingRow(
        "Default quality",
        onClick = state.onOk(SettingKey.Quality, calls) { calls.show(Overlay.Quality) },
        description = qualityDescription(state.quality),
        modifier = if (notLoaded) Modifier else Modifier.focusRequester(first),
        status = state.status(SettingKey.Quality, state.quality.label),
    ) { Value(state.quality.label, chevron = true) }
    SettingRow(
        "Match frame rate",
        onClick = state.onOk(SettingKey.MatchFrameRate, calls) { calls.setMatchFrameRate(!state.device.matchFrameRate) },
        description = "Switches the TV to the video’s frame rate",
        modifier = Modifier.switch(state.device.matchFrameRate),
        status = state.status(SettingKey.MatchFrameRate, onOff(state.device.matchFrameRate)),
    ) { Toggle(state.device.matchFrameRate) }
    SettingRow(
        "Audio passthrough",
        onClick = state.onOk(SettingKey.AudioPassthrough, calls) { calls.show(Overlay.Passthrough) },
        description = "Dolby and DTS as they are, when the TV or receiver can decode them",
        status = state.status(SettingKey.AudioPassthrough, state.device.audioPassthrough.label),
    ) { Value(state.device.audioPassthrough.label, chevron = true) }
    if (state.soundAvailable) {
        // The size is the person's now, in Audio & subtitles.
        Note(if (state.subtitleSizeFromThisTv) subtitleSizeMovedFrom(state.device.subtitleSize) else SUBTITLE_SIZE_MOVED, Modifier.padding(top = 6.dp, bottom = 4.dp))
    } else {
        SettingRow(
            "Subtitle size",
            onClick = state.onOk(SettingKey.SubtitleSize, calls) { calls.show(Overlay.SubtitleSize) },
            status = state.status(SettingKey.SubtitleSize, state.device.subtitleSize.label),
        ) { Value(state.device.subtitleSize.label, chevron = true) }
    }

    // Held until the account's settings load, so a default is never saved over someone's choice.
    val ready = state.accountReady
    PaneHeading(ON_EVERY_DEVICE)
    val autoplay = state.playback.autoplayNext
    SettingRow(
        "Autoplay next episode",
        onClick = state.onOk(SettingKey.Autoplay, calls) { calls.setAutoplayNext(!autoplay) },
        description = "Counts down 10 s from the credits",
        modifier = Modifier.switch(autoplay, enabled = ready),
        status = state.status(SettingKey.Autoplay, onOff(autoplay)),
        enabled = ready,
    ) { Toggle(autoplay) }
    for (kind in SKIP_KINDS) {
        val mode = state.playback.skipMode(kind)
        val skipKey = SettingKey.Skip(kind)
        SettingRow(
            kind.skipRowLabel,
            onClick = state.onOk(skipKey, calls) { calls.skip(kind) },
            description = skipNote(kind),
            status = state.status(skipKey, mode.label),
            enabled = ready,
        ) { Value(mode.label, chevron = true) }
    }
    if (reminderLead != null) {
        SettingRow(
            "Live TV reminders",
            onClick = { calls.show(Overlay.ReminderLead) },
            description = "The same on all your devices",
            enabled = ready,
        ) { Value(liveLeadBefore(reminderLead), chevron = true) }
    }
}

/**
 * Account & devices: this TV's
 * name, the signed-in devices (each other one a row whose OK asks before it
 * is signed out), Change password and Servers (the servers this TV is signed
 * in to, [serversLine]: "Living room and 2 more"), and Sign out of this TV
 * apart at the foot. When a device that had focus is signed out, focus goes
 * to the next one, else the one before, else Change password.
 */
@Composable
internal fun ColumnScope.AccountPane(state: SettingsViewModel.State, serversLine: String?, first: FocusRequester, calls: SettingsCalls) {
    PaneHeading("This TV")
    SettingRow(
        "Device name",
        onClick = state.onOk(SettingKey.DeviceName, calls) { calls.show(Overlay.DeviceName) },
        description = "${Build.MODEL} · Android ${Build.VERSION.RELEASE}",
        modifier = Modifier.focusRequester(first),
        status = state.status(SettingKey.DeviceName, state.deviceName),
    ) { Value(state.deviceName, chevron = true) }

    PaneHeading("Signed-in devices")
    when (val s = state.sessions) {
        Load.Loading -> Note("Loading…", Modifier.padding(vertical = 12.dp))
        is Load.Failed -> SettingRow("Couldn’t load the list", onClick = calls::loadSessions, description = s.message, focusKey = "devices-failed") { Value("Try again") }
        is Load.Ready -> {
            val others = s.data.filterNot { it.current }
            for (d in s.data) {
                key(d.id) {
                    if (d.current) {
                        ThisDeviceRow(d)
                    } else {
                        val busy = state.revoking == d.id
                        SettingRow(d.name, onClick = { calls.revoke(d) }, description = d.line(), focusKey = deviceKey(d.id)) {
                            Value(if (busy) "Signing out…" else "Sign out", chevron = !busy)
                        }
                    }
                }
            }
            if (others.isEmpty()) Note("No other devices are signed in.", Modifier.padding(vertical = 8.dp))
            FocusAfterSignOut(others)
        }
    }

    PaneHeading("Account")
    SettingRow("Change password", onClick = { calls.show(Overlay.ChangePassword) }, description = "On your phone or computer", focusKey = CHANGE_PASSWORD) { Value("", chevron = true) }
    SettingRow(SERVERS, onClick = { calls.show(Overlay.Servers) }, description = serversLine, focusKey = SERVERS_ROW) { Value("", chevron = true) }
    PaneDivider()
    SignOutRow("Sign out of this TV…", onClick = { calls.show(Overlay.SignOut) })
}

private const val CHANGE_PASSWORD = "Change password"

/** The Servers row's key in Settings' focus memory: where focus goes back to when its panel closes. */
internal const val SERVERS_ROW = "Servers"

/** A signed-in device's key in Settings' focus memory. */
internal fun deviceKey(id: String) = "device:$id"

/**
 * Moves focus on when the device that had it leaves [others]: to the next,
 * else the one before, else Change password. In the same frame the row goes
 * (a [SideEffect]): a frame later, focus has already been put somewhere else.
 */
@Composable
private fun FocusAfterSignOut(others: List<DeviceSession>) {
    val memory = LocalSettingsFocus.current ?: return
    // The list as it was last composed; a plain holder, read and replaced only here.
    val before = remember { arrayOf(others.map { it.id }) }
    val now = others.map { it.id }
    val previous = before[0]
    if (previous == now) return
    before[0] = now
    val had = previous.filter { it !in now }.firstOrNull { memory.key == deviceKey(it) } ?: return
    val next = now.getOrNull(previous.indexOf(had)) ?: now.lastOrNull()
    SideEffect { if (next == null || !memory.focus(deviceKey(next))) memory.focus(CHANGE_PASSWORD) }
}

/** Your requests: where each one stands, on their own page. */
@Composable
internal fun ColumnScope.RequestsPane(summary: String, first: FocusRequester, calls: SettingsCalls) {
    Note("Each title you’ve asked for, and where it stands.", Modifier.padding(top = 4.dp, bottom = 6.dp))
    SettingRow("See your requests", onClick = { calls.openRequests(false) }, description = "Cancel one that is still waiting", modifier = Modifier.focusRequester(first)) {
        Value(summary, chevron = true)
    }
}

/** The admins' Inbox: requests to approve and problems people report, on their own page. */
@Composable
internal fun ColumnScope.InboxPane(count: Int?, first: FocusRequester, calls: SettingsCalls) {
    Note("Requests that need your approval, and problems people report.", Modifier.padding(top = 4.dp, bottom = 6.dp))
    SettingRow("Open the Inbox", onClick = { calls.openRequests(true) }, description = "Approve, decline or send again", modifier = Modifier.focusRequester(first)) {
        Value(if (count != null && count > 0) "$count waiting" else "", chevron = true)
    }
}

/**
 * About: the server's and the app's versions, a line when the server is
 * older than some settings need, Copy diagnostics, the privacy policy
 * and licences. Admin tools moved to Server settings.
 */
@Composable
internal fun ColumnScope.AboutPane(serverLine: String, appLine: String, olderServer: String?, first: FocusRequester, calls: SettingsCalls) {
    AboutRow("Server", serverLine)
    AboutRow("App", appLine)
    if (olderServer != null) Note(olderServer, Modifier.padding(top = 2.dp, bottom = 8.dp))
    // The app's last exits, for a bug report (android-tv/TvDiagnostics).
    SettingRow(
        "Copy diagnostics",
        onClick = { calls.show(Overlay.Diagnostics) },
        description = "The app’s last exits and why, for a bug report",
        modifier = Modifier.focusRequester(first),
    ) { Value("", chevron = true) }
    SettingRow("Privacy policy", onClick = { calls.show(Overlay.Privacy) }) { Value(PRIVACY_POLICY_URL.removePrefix("https://"), chevron = true) }
    SettingRow("Open-source licences", onClick = { calls.show(Overlay.Licences) }) { Value("", chevron = true) }
    // TMDB's terms ask apps that use its API to say so.
    Note(TMDB_NOTICE, Modifier.padding(top = 6.dp), color = WmmColors.TextFaint)
}
