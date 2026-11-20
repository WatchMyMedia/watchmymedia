package media.watchmy.client.tv.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import media.watchmy.client.model.ADD_A_SERVER
import media.watchmy.client.model.ADD_A_SERVER_LINE
import media.watchmy.client.model.REMOVE_THIS_SERVER
import media.watchmy.client.model.SERVERS
import media.watchmy.client.model.SWITCHING
import media.watchmy.client.model.SWITCH_TO_THIS_SERVER
import media.watchmy.client.model.TOO_OLD_FOR_APP
import media.watchmy.client.model.serversFull
import media.watchmy.client.model.serversLede
import media.watchmy.client.presentation.servers.ServersViewModel
import media.watchmy.client.presentation.servers.ServersViewModel.RowState
import media.watchmy.client.tv.components.FocusableBox
import media.watchmy.client.tv.components.TextBright
import media.watchmy.client.tv.components.TvDialog
import media.watchmy.client.tv.components.inter
import media.watchmy.client.tv.components.tryFocus
import media.watchmy.client.tv.servers.FailureNote
import media.watchmy.client.tv.servers.PanelOption
import media.watchmy.client.tv.servers.PanelRule
import media.watchmy.client.tv.servers.ServerOption
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.WmmIcons

// Settings › Account & devices › Servers on the TV: the Servers page and a
// server's page, each in the side panel a row opens here, and the question
// Remove this server… asks.

/** The side panel a Settings row opens: 700 wide at the right, over the dimmed screen. Back closes it. */
@Composable
private fun SidePanel(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    TvDialog(onDismiss) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.CenterEnd) {
            Column(
                Modifier
                    .width(700.dp)
                    .fillMaxHeight()
                    .background(WmmColors.Surface)
                    .border(1.dp, WmmColors.Line)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 44.dp, end = 44.dp, top = 64.dp, bottom = 54.dp)
                    .semantics { paneTitle = title },
                verticalArrangement = Arrangement.spacedBy(20.dp),
                content = content,
            )
        }
    }
}

/**
 * The Servers page: what being signed in to several means, a row per server
 * (each opens its page) and Add a server. Focus opens on [focusOn], the
 * server whose page was just left, else on the first that isn't in use.
 */
@Composable
internal fun ServersListPanel(state: ServersViewModel.State, focusOn: String?, onOpen: (String) -> Unit, onAdd: () -> Unit, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    val entry = state.row(focusOn)?.serverId ?: state.rows.firstOrNull { !it.current }?.serverId ?: state.rows.firstOrNull()?.serverId
    SidePanel(SERVERS, onDismiss) {
        Text(SERVERS, style = inter(34.sp, FontWeight.ExtraBold, tracking = -0.015f), color = WmmColors.Text, modifier = Modifier.semantics { heading() })
        Text(serversLede("TV", state.rows.size), style = inter(20.sp, lineHeight = 30.sp), color = TextBright)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            state.rows.forEach { row ->
                ServerOption(
                    row, row.detail,
                    onClick = { onOpen(row.serverId) },
                    modifier = if (row.serverId == entry) Modifier.focusRequester(first) else Modifier,
                    chevron = true,
                    plainIcon = false,
                )
            }
        }
        PanelRule()
        PanelOption(ADD_A_SERVER, if (state.full) serversFull("TV") else ADD_A_SERVER_LINE, WmmIcons.Plus, onClick = onAdd, enabled = !state.full)
        Box(Modifier.weight(1f))
        Text("Back closes this panel.", style = inter(17.sp), color = WmmColors.TextFaint)
        // In the panel's own composition, so its rows are there to take the focus.
        LaunchedEffect(entry) {
            withFrameNanos { }
            first.tryFocus()
        }
    }
}

/**
 * One server's page: its address, version and who is signed in; Switch to
 * this server, unless it is the one in use; and Remove this server…, apart
 * at the foot. Focus opens on Switch to this server, or on Try again when
 * it didn't answer. Back returns to the Servers page.
 */
@Composable
internal fun ServerPagePanel(
    row: ServersViewModel.Row,
    failure: ServersViewModel.Failure?,
    onSwitch: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val first = remember { FocusRequester() }
    val retry = remember { FocusRequester() }
    val canSwitch = !row.current && row.state != RowState.TooOld
    SidePanel(row.name, onDismiss) {
        Column {
            Text(SERVERS.uppercase(), style = inter(15.sp, FontWeight.Bold, tracking = 0.1f), color = WmmColors.TextMuted, modifier = Modifier.padding(bottom = 4.dp))
            Text(row.name, style = inter(34.sp, FontWeight.ExtraBold, tracking = -0.015f), color = WmmColors.Text, modifier = Modifier.semantics { heading() })
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Fact("Address", row.address)
            Fact("Version", "WatchMyMedia ${row.version}")
            Fact("Signed in as", row.userName?.takeIf { row.state != RowState.SignedOut } ?: "No one")
        }
        PanelRule()
        if (failure != null) FailureNote(failure, onRetry, Modifier.focusRequester(retry))
        if (canSwitch) {
            ActionRow(
                SWITCH_TO_THIS_SERVER, WmmIcons.Replace, onSwitch,
                modifier = Modifier.focusRequester(first),
                value = SWITCHING.takeIf { row.state == RowState.Switching },
            )
        } else if (row.state == RowState.TooOld) {
            Text("$TOO_OLD_FOR_APP. Update it to switch to it.", style = inter(19.sp, lineHeight = 28.sp), color = WmmColors.TextMuted, modifier = Modifier.padding(horizontal = 18.dp))
        }
        Box(Modifier.weight(1f))
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            PanelRule()
            ActionRow(REMOVE_THIS_SERVER, WmmIcons.Trash, onRemove, modifier = if (canSwitch) Modifier else Modifier.focusRequester(first), color = FailRed)
            Text("Back returns to Servers.", style = inter(17.sp), color = WmmColors.TextFaint, modifier = Modifier.padding(top = 6.dp))
        }
        // In the panel's own composition, so its rows are there to take the focus.
        LaunchedEffect(failure?.serverId) {
            withFrameNanos { }
            if (failure != null) retry.tryFocus() else first.tryFocus()
        }
    }
}

/** A fact about a server (`.row`): its label and its value at the row's end. It takes no focus. */
@Composable
private fun Fact(label: String, value: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 62.dp).padding(horizontal = 18.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = inter(21.sp, FontWeight.SemiBold), color = WmmColors.Text, maxLines = 1)
        Box(Modifier.weight(1f))
        Text(value, style = inter(19.sp), color = WmmColors.TextSoft, maxLines = 1)
    }
}

/** A row that does something, with its icon: Switch to this server, and Remove this server… in the danger colour. */
@Composable
private fun ActionRow(label: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = WmmColors.Text, value: String? = null) {
    FocusableBox(onClick = onClick, modifier = modifier.fillMaxWidth(), cornerRadius = 12.dp, focusedScale = 1f, ringOffset = (-4).dp, enabled = value == null, role = Role.Button) { focused ->
        Row(
            Modifier.fillMaxWidth().heightIn(min = 62.dp).background(if (focused) WmmColors.Surface2 else Color.Transparent, RoundedCornerShape(12.dp)).padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(26.dp))
            Text(label, style = inter(21.sp, FontWeight.SemiBold), color = color, maxLines = 1, modifier = Modifier.weight(1f))
            if (value != null) Text(value, style = inter(19.sp), color = WmmColors.TextSoft)
        }
    }
}
