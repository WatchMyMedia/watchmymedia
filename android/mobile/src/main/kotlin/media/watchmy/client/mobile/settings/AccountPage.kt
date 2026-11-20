package media.watchmy.client.mobile.settings

import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import media.watchmy.client.mobile.components.Avatar
import media.watchmy.client.mobile.components.GoldTextButton
import media.watchmy.client.mobile.components.IconTile
import media.watchmy.client.mobile.components.ListRow
import media.watchmy.client.mobile.components.PhoneIcons
import media.watchmy.client.mobile.downloads.DangerText
import media.watchmy.client.model.DeviceSession
import media.watchmy.client.model.REVOKE_BODY
import media.watchmy.client.model.displayAddress
import media.watchmy.client.model.formatAgo
import media.watchmy.client.model.kindLabel
import media.watchmy.client.model.plural
import media.watchmy.client.model.revokeTitle
import media.watchmy.client.model.signOutStays
import media.watchmy.client.model.signOutTitle
import media.watchmy.client.presentation.Load
import media.watchmy.client.presentation.settings.SettingKey
import media.watchmy.client.presentation.settings.SettingsViewModel
import media.watchmy.client.ui.Inter
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.Badge
import media.watchmy.client.ui.components.BadgeStyle
import media.watchmy.client.ui.components.WmmIcons
import media.watchmy.client.ui.components.loadingSemantics
import media.watchmy.client.ui.components.rememberSheen
import media.watchmy.client.ui.components.skeletonLine

/**
 * Settings › Account & devices, in the web's order: who and
 * where, Change password, this device's name, the signed-in devices, Link a
 * TV, the server, and signing out apart at the bottom. Signing a device or
 * this one out asks first. What was just done shows as a card at the top
 * until it is put away or the page is left.
 */
@Composable
internal fun AccountPage(
    tablet: Boolean,
    onChangePassword: () -> Unit,
    onLinkTv: () -> Unit,
    onSwitchServer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm = settingsModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val downloads by downloadsModel().state.collectAsStateWithLifecycle()
    val services = LocalAppServices.current
    val device = deviceWord()
    val server = state.server?.server
    val serverName = server?.name ?: "The server"
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    // The device a Sign out dialog is for, kept while it is open so it can say so after the list drops it.
    var revokeId by rememberSaveable { mutableStateOf<String?>(null) }
    var revokeName by rememberSaveable { mutableStateOf("") }
    // The list again each time the page is opened: a TV linked, a password changed.
    LaunchedEffect(Unit) { vm.loadSessions() }
    // Leaving the page puts the card away; turning the screen doesn't.
    val activity = LocalActivity.current
    DisposableEffect(vm) { onDispose { if (activity?.isChangingConfigurations != true) vm.noticeShown() } }

    Column(modifier) {
        state.notice?.let { notice ->
            StatusCard(notice.title, notice.body, NoteTone.Ok, Modifier.cardInset(tablet), onDismiss = vm::noticeShown)
        }
        // Who and where; a tablet's list says it above the groups.
        if (!tablet) state.user?.let { user ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(horizontal = if (tablet) 4.dp else 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Avatar(user.name, size = 44.dp)
                Column {
                    Text(user.name, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = WmmColors.Text))
                    Text(listOfNotNull(if (user.isAdmin) "Admin" else "Member", server?.name).joinToString(" · "), style = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = WmmColors.TextMuted))
                }
            }
        }
        SettingsRows(tablet, Modifier.padding(top = if (tablet) 12.dp else 0.dp)) {
            ListRow(
                "Change password",
                subtitle = "Signs your other devices out",
                minHeight = 60.dp,
                modifier = Modifier.clickable(onClick = onChangePassword),
                leading = { IconTile(PhoneIcons.Lock, background = WmmColors.Surface2, tint = WmmColors.TextSoft) },
                trailing = { Chevron() },
            )
        }

        PageHeading("This $device", tablet)
        SettingsRows(tablet) {
            ValueRow(
                "Device name",
                line = listOfNotNull(Build.MODEL, Build.VERSION.RELEASE?.let { "Android $it" }, "app ${services.client.version}").joinToString(" · "),
                value = state.deviceName,
                state = state, key = SettingKey.DeviceName, still = state.deviceName, onRetry = vm::retry,
                onClick = { dialog = DIALOG_NAME },
            )
        }

        PageHeading("Signed-in devices", tablet)
        val refreshFailure = state.message
        if (refreshFailure != null && state.sessions is Load.Ready) {
            FailureLine("Couldn’t refresh your devices. $refreshFailure", onRetry = {
                vm.messageShown()
                vm.loadSessions()
            })
        }
        val sessions = state.sessions
        if (sessions is Load.Failed) {
            StatusCard(
                "Couldn’t load your devices",
                sessions.message.trimEnd('.').ifBlank { "$serverName didn’t answer" } + ". Nothing was changed.",
                NoteTone.Error,
                Modifier.cardInset(tablet),
                action = "Try again",
                onAction = vm::loadSessions,
            )
        }
        SettingsRows(tablet) {
            when (sessions) {
                is Load.Ready -> sessions.data.forEach { d ->
                    DeviceRow(d, device, revoking = state.revoking == d.id, onSignOut = {
                        vm.clearConfirmation()
                        revokeId = d.id
                        revokeName = d.name
                    })
                }
                Load.Loading -> DevicePlaceholders()
                is Load.Failed -> Unit
            }
            ListRow(
                "Link a TV",
                subtitle = "Enter the code shown on your TV",
                minHeight = 64.dp,
                modifier = Modifier.clickable(onClick = onLinkTv),
                leading = { IconTile(WmmIcons.Tv, background = WmmColors.Line2, tint = WmmColors.Accent) },
                trailing = { Chevron() },
            )
        }

        val heldLine = if (downloads.all.isEmpty()) null else "Deletes the ${plural(downloads.all.size, "download")} on this $device"
        if (tablet) {
            // The tablet board's foot: Switch server beside Sign out, apart from the panels.
            Row(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onSwitchServer) {
                    Text("Switch server", style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = WmmColors.TextSoft))
                    if (server != null) Text(" · ${server.name}", style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = WmmColors.TextMuted))
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { dialog = DIALOG_SIGN_OUT }) {
                    Icon(PhoneIcons.SignOut, contentDescription = null, tint = DangerText, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Sign out of this $device…", style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = DangerText))
                }
            }
        } else {
            PageHeading("Server", tablet = false)
            ListRow(
                "Switch server",
                subtitle = server?.let { "${it.name} · ${displayAddress(it.baseUrl)}" },
                minHeight = 60.dp,
                modifier = Modifier.clickable(onClick = onSwitchServer),
                leading = { IconTile(WmmIcons.Server, background = WmmColors.Surface2, tint = WmmColors.TextSoft) },
                trailing = { Chevron() },
            )
            Rule(Modifier.padding(top = 4.dp))
            DangerRow("Sign out of this $device…", PhoneIcons.SignOut, onClick = { dialog = DIALOG_SIGN_OUT }, subtitle = heldLine)
        }
    }

    when (dialog) {
        DIALOG_NAME -> RenameDialog(state.deviceName, onDismiss = { dialog = null }) {
            vm.rename(it)
            dialog = null
        }
        DIALOG_SIGN_OUT -> {
            val held = downloads.all.isNotEmpty()
            ConfirmDialog(
                title = signOutTitle(device),
                lines = listOfNotNull(
                    ConfirmLine(signOutStays(serverName), label = "Stays"),
                    if (held) ConfirmLine(downloadsHeld(downloads), label = "Deleted from this $device") else ConfirmLine("Nothing is deleted from this $device."),
                ),
                keep = "Stay signed in",
                go = "Sign out",
                working = "Signing out…",
                busy = state.signingOut,
                failure = state.signOutFailure?.let {
                    ConfirmFailure("Couldn’t sign out", if (held) "You’re still signed in and nothing was deleted. Try again." else "You’re still signed in. Try again.")
                },
                onKeep = {
                    vm.clearConfirmation()
                    dialog = null
                },
                onGo = vm::signOut,
            )
        }
    }
    revokeId?.let { id ->
        RevokeDialog(state, id, revokeName, onRevoke = vm::revoke, onKeep = vm::clearConfirmation, onClose = { revokeId = null })
    }
}

/** "Sign out another device?": working while it goes, closed once the list has dropped it, or failed with Try again. */
@Composable
private fun RevokeDialog(state: SettingsViewModel.State, id: String, name: String, onRevoke: (DeviceSession) -> Unit, onKeep: () -> Unit, onClose: () -> Unit) {
    val session = (state.sessions as? Load.Ready)?.data?.firstOrNull { it.id == id }
    val busy = state.revoking == id
    val failure = state.revokeFailure?.takeIf { it.sessionId == id }
    LaunchedEffect(session, busy) { if (session == null && !busy) onClose() }
    ConfirmDialog(
        title = revokeTitle(name),
        lines = listOf(ConfirmLine(REVOKE_BODY)),
        keep = "Keep it signed in",
        go = "Sign out $name",
        working = "Signing out…",
        busy = busy,
        failure = failure?.let { ConfirmFailure("Couldn’t sign out $name", "It is still signed in. Try again.") },
        onKeep = {
            onKeep()
            onClose()
        },
        onGo = { session?.let(onRevoke) },
    )
}

/** A signed-in device: its name, model and when it was last seen; this one marked, the others with Sign out. */
@Composable
private fun DeviceRow(d: DeviceSession, device: String, revoking: Boolean, onSignOut: () -> Unit) {
    ListRow(
        d.name,
        subtitle = "${d.model ?: d.kindLabel} · ${if (d.current) "now" else formatAgo(d.lastSeenAtMs)}",
        titleSize = 15,
        subtitleSize = 12,
        minHeight = 56.dp,
        trailing = {
            when {
                d.current -> Badge("This $device", style = BadgeStyle.Gold, horizontalPadding = 8.dp)
                else -> GoldTextButton(if (revoking) "Signing out…" else "Sign out", onSignOut, enabled = !revoking)
            }
        },
    )
}

/** The devices list while it loads: its rows' shapes in their places. */
@Composable
private fun DevicePlaceholders() {
    val sheen = rememberSheen(1_600)
    Column(Modifier.loadingSemantics("Loading your devices")) {
        listOf(150.dp to 200.dp, 120.dp to 170.dp, 170.dp to 150.dp).forEach { (first, second) ->
            Column(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.skeletonLine(sheen, first, 14.dp, radius = 6.dp))
                Box(Modifier.skeletonLine(sheen, second, 10.dp, radius = 6.dp))
            }
        }
    }
}

@Composable
private fun Chevron() = Icon(WmmIcons.ChevronRight, contentDescription = null, tint = WmmColors.TextMuted, modifier = Modifier.size(22.dp))

private const val DIALOG_NAME = "name"
private const val DIALOG_SIGN_OUT = "sign-out"
