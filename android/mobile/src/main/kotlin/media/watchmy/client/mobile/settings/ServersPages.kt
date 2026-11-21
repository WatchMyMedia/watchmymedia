package media.watchmy.client.mobile.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import media.watchmy.client.mobile.components.FilledButton
import media.watchmy.client.mobile.components.GoldTextButton
import media.watchmy.client.mobile.components.IconTile
import media.watchmy.client.mobile.components.ListRow
import media.watchmy.client.mobile.components.RowValue
import media.watchmy.client.mobile.servers.CurrentBadge
import media.watchmy.client.model.ADD_A_SERVER
import media.watchmy.client.model.ADD_A_SERVER_LINE
import media.watchmy.client.model.REMOVE_SERVER_KEEPS
import media.watchmy.client.model.REMOVE_THIS_SERVER
import media.watchmy.client.model.SIGNED_OUT_HERE
import media.watchmy.client.model.SWITCHING
import media.watchmy.client.model.SWITCH_TO_THIS_SERVER
import media.watchmy.client.model.TOO_OLD_FOR_APP
import media.watchmy.client.model.downloadsFromIt
import media.watchmy.client.model.removeServerDoes
import media.watchmy.client.model.removeServerTitle
import media.watchmy.client.model.serversFull
import media.watchmy.client.model.serversLede
import media.watchmy.client.model.watchItsDownloads
import media.watchmy.client.presentation.servers.ServersViewModel
import media.watchmy.client.presentation.servers.ServersViewModel.RowState
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.WmmIcons

// Settings › Account & devices › Servers: the servers this device is signed
// in to, each opening its own page, where it is switched to or removed.

/** Which of Settings' Servers pages is open. */
sealed interface ServersPage {
    /** The list: a row per server, then Add a server. */
    data object List : ServersPage

    /** One server's page. */
    data class Server(val id: String) : ServersPage
}

/** Settings' Servers model, from the Settings route's entry, so the list and each server's page share it. */
@Composable
internal fun serversModel(): ServersViewModel {
    val services = LocalAppServices.current
    return viewModel(key = "settings-servers") { ServersViewModel(services.servers, services.downloads, services.cast) }
}

/** The Servers page: what being signed in to several means, a row per server, and Add a server. */
@Composable
internal fun ServersListPage(tablet: Boolean, onOpenServer: (String) -> Unit, onAdd: () -> Unit, modifier: Modifier = Modifier) {
    val state by serversModel().state.collectAsStateWithLifecycle()
    val device = deviceWord()
    Column(modifier) {
        Text(
            serversLede(device, state.rows.size),
            style = LedeStyle,
            modifier = Modifier.padding(start = if (tablet) 4.dp else 16.dp, end = if (tablet) 4.dp else 16.dp, top = 4.dp, bottom = 8.dp).widthIn(max = 700.dp),
        )
        SettingsRows(tablet) {
            state.rows.forEach { row ->
                ListRow(
                    row.name,
                    subtitle = when (row.state) {
                        RowState.SignedOut -> SIGNED_OUT_HERE
                        RowState.TooOld -> TOO_OLD_FOR_APP
                        RowState.Switching -> SWITCHING
                        RowState.Idle -> row.detail
                    },
                    subtitleColor = if (row.state == RowState.TooOld) WmmColors.Warn else WmmColors.TextMuted,
                    minHeight = 68.dp,
                    modifier = Modifier.clickable(role = Role.Button, onClickLabel = "Open") { onOpenServer(row.serverId) },
                    leading = {
                        if (row.current) IconTile(WmmIcons.Server) else IconTile(WmmIcons.Server, background = WmmColors.Surface2, tint = WmmColors.TextSoft)
                    },
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (row.current) CurrentBadge()
                            Chevron()
                        }
                    },
                )
            }
        }
        if (!tablet) Rule()
        SettingsRows(tablet, Modifier.padding(top = if (tablet) 12.dp else 0.dp)) {
            ListRow(
                ADD_A_SERVER,
                subtitle = if (state.full) serversFull(device) else ADD_A_SERVER_LINE,
                minHeight = 68.dp,
                modifier = if (state.full) Modifier else Modifier.clickable(role = Role.Button, onClick = onAdd),
                leading = { IconTile(WmmIcons.Plus, background = WmmColors.Line2, tint = WmmColors.Accent) },
                trailing = { if (!state.full) Chevron() },
            )
        }
    }
}

/**
 * One server's page: its address, version, who is signed in and what this
 * device holds from it; Switch to this server, unless it is the one in use;
 * and Remove this server…, apart at the bottom, which asks first. [onGone]
 * is called when the server is no longer registered (it was removed).
 */
@Composable
internal fun ServerPage(serverId: String, tablet: Boolean, onGone: () -> Unit, modifier: Modifier = Modifier) {
    val vm = serversModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val device = deviceWord()
    val gone by rememberUpdatedState(onGone)
    var asking by rememberSaveable { mutableStateOf(false) }
    // Removed: the question closes and the page gives way to the list. One removed while in use takes the app to another server.
    LaunchedEffect(vm) { vm.events.collect { if (it is ServersViewModel.Event.Removed) gone() } }
    val row = state.row(serverId) ?: return

    Column(modifier) {
        state.failure?.takeIf { it.serverId == serverId }?.let { failure ->
            StatusCard(failure.title, failure.body, NoteTone.Warn, Modifier.cardInset(tablet), action = "Try again", onAction = vm::retry)
            if (failure.downloads > 0) GoldTextButton(watchItsDownloads(failure.downloads), vm::watchDownloads, Modifier.padding(start = if (tablet) 0.dp else 12.dp))
        }
        SettingsRows(tablet, Modifier.padding(top = if (tablet) 12.dp else 0.dp)) {
            Fact("Address", row.address)
            Fact("Version", row.version)
            Fact("Signed in as", row.userName?.takeIf { row.state != RowState.SignedOut } ?: "No one")
            Fact("Downloads from it", downloadsFromIt(row.downloads))
        }
        if (!row.current && row.state != RowState.TooOld) {
            FilledButton(
                SWITCH_TO_THIS_SERVER,
                onClick = { vm.choose(serverId) },
                modifier = Modifier.padding(start = if (tablet) 0.dp else 16.dp, end = if (tablet) 0.dp else 16.dp, top = 18.dp, bottom = 6.dp).widthIn(max = 720.dp).fillMaxWidth(),
                large = true,
                busy = row.state == RowState.Switching,
            )
        }
        if (row.state == RowState.TooOld) FootNote("$TOO_OLD_FOR_APP. Update it to switch to it.", tablet = tablet)
        Rule(Modifier.padding(top = if (tablet) 6.dp else 18.dp))
        DangerRow(REMOVE_THIS_SERVER, WmmIcons.Trash, onClick = { asking = true }, modifier = Modifier.widthIn(max = 720.dp), enabled = state.removing == null)
    }

    if (asking) {
        val held = row.downloads?.count ?: 0
        ConfirmDialog(
            title = removeServerTitle(row.name, device),
            lines = listOf(ConfirmLine(removeServerDoes(device, held)), ConfirmLine(REMOVE_SERVER_KEEPS)),
            keep = "Keep",
            go = "Remove",
            working = "Removing…",
            busy = state.removing == serverId,
            failure = null,
            onKeep = { asking = false },
            onGo = { vm.remove(serverId) },
        )
    }
}

/** A fact about a server: what it is and its value at the row's end. */
@Composable
private fun Fact(label: String, value: String) {
    ListRow(label, minHeight = 52.dp, trailing = { RowValue(value, chevron = false) })
}

@Composable
private fun Chevron() = Icon(WmmIcons.ChevronRight, contentDescription = null, tint = WmmColors.TextMuted, modifier = Modifier.size(22.dp))
