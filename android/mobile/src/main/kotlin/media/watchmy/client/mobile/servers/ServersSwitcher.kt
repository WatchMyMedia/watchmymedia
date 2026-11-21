package media.watchmy.client.mobile.servers

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import media.watchmy.client.mobile.components.GoldTextButton
import media.watchmy.client.model.ADD_A_SERVER
import media.watchmy.client.model.ADD_A_SERVER_LINE
import media.watchmy.client.model.CURRENT_SERVER
import media.watchmy.client.model.MANAGE_SERVERS
import media.watchmy.client.model.SERVERS
import media.watchmy.client.model.SIGNED_OUT_HERE
import media.watchmy.client.model.SWITCHING
import media.watchmy.client.model.TOO_OLD_FOR_APP
import media.watchmy.client.model.serversFull
import media.watchmy.client.model.switchingStopsCasting
import media.watchmy.client.model.watchItsDownloads
import media.watchmy.client.presentation.servers.ServersActions
import media.watchmy.client.presentation.servers.ServersViewModel
import media.watchmy.client.presentation.servers.ServersViewModel.RowState
import media.watchmy.client.ui.Inter
import media.watchmy.client.ui.TestTags
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.WmmIcons

// Servers, the switcher the account button opens: a sheet from the bottom on
// a phone, a popover beside the rail's account button on a tablet. The
// servers this device is signed in to, the one in use first; then Add a
// server and Manage servers.

private val SheetGround = Color(0xFF1C1C1F)
private val RowPressed = Color(0xFF26262A)
private val SheetLine = Color(0xFF2E2E33)

/** Servers on a phone: a sheet from the bottom, with Close. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ServersSheet(
    state: ServersViewModel.State,
    actions: ServersActions,
    device: String,
    onAdd: () -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
    onSettings: (() -> Unit)? = null,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SheetGround,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            Box(Modifier.padding(top = 10.dp, bottom = 12.dp).size(32.dp, 4.dp).background(Color(0xFF5A5A5F), RoundedCornerShape(2.dp)))
        },
    ) {
        Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 12.dp).testTag(TestTags.SERVERS)) {
            Row(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    SERVERS,
                    style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, color = WmmColors.Text),
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp).background(WmmColors.Line2, CircleShape)) {
                    Icon(WmmIcons.Close, contentDescription = "Close", tint = WmmColors.Text, modifier = Modifier.size(20.dp))
                }
            }
            ServersList(state, actions, device, onAdd, onManage, onSettings)
        }
    }
}

/**
 * Servers on a tablet: a popover beside the account button at the rail's
 * foot ([railWidth] from the window's left edge), put away by a tap beside
 * it or Back.
 */
@Composable
internal fun ServersPopover(
    state: ServersViewModel.State,
    actions: ServersActions,
    device: String,
    railWidth: Dp,
    onAdd: () -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
    onSettings: (() -> Unit)? = null,
) {
    Popup(
        alignment = Alignment.BottomStart,
        offset = IntOffset(0, 0),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        val shape = RoundedCornerShape(20.dp)
        Column(
            Modifier
                .navigationBarsPadding()
                .padding(start = railWidth + 12.dp, bottom = 16.dp)
                .width(384.dp)
                .shadow(24.dp, shape)
                .clip(shape)
                .background(SheetGround)
                .border(1.dp, SheetLine, shape)
                .verticalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 12.dp)
                .semantics { paneTitle = SERVERS }
                .testTag(TestTags.SERVERS),
        ) {
            Text(
                SERVERS,
                style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, color = WmmColors.Text),
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 10.dp).semantics { heading() },
            )
            ServersList(state, actions, device, onAdd, onManage, onSettings)
        }
    }
}

/**
 * What Servers holds under its title: the casting line, a server that didn't
 * answer, the servers, then Add a server and Manage servers. [onSettings]
 * adds Settings at the end, where Settings has no tab of its own: with Live
 * TV in the bar, the account button was the way to it.
 */
@Composable
private fun ColumnScope.ServersList(state: ServersViewModel.State, actions: ServersActions, device: String, onAdd: () -> Unit, onManage: () -> Unit, onSettings: (() -> Unit)?) {
    state.casting?.let { tv ->
        Text(
            switchingStopsCasting(tv),
            style = TextStyle(fontFamily = Inter, fontSize = 13.sp, lineHeight = 19.sp, color = WmmColors.TextMuted),
            modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
        )
    }
    state.failure?.let { FailureNote(it, actions) }
    state.rows.forEach { row -> ServerRow(row, onClick = { actions.choose(row.serverId) }) }
    Box(Modifier.padding(8.dp).fillMaxWidth().height(1.dp).background(SheetLine))
    SwitcherRow(
        title = ADD_A_SERVER,
        line = if (state.full) serversFull(device) else ADD_A_SERVER_LINE,
        icon = WmmIcons.Plus,
        tileGround = WmmColors.Accent.copy(alpha = 0.16f),
        tileTint = WmmColors.Accent,
        onClick = onAdd.takeIf { !state.full },
        lineLimit = 3,
    )
    SwitcherRow(title = MANAGE_SERVERS, line = null, icon = WmmIcons.Sliders, onClick = onManage, minHeight = 60.dp) {
        Icon(WmmIcons.ChevronRight, contentDescription = null, tint = WmmColors.TextMuted, modifier = Modifier.padding(end = 4.dp).size(22.dp))
    }
    if (onSettings != null) {
        SwitcherRow(title = "Settings", line = null, icon = WmmIcons.Settings, onClick = onSettings, minHeight = 60.dp) {
            Icon(WmmIcons.ChevronRight, contentDescription = null, tint = WmmColors.TextMuted, modifier = Modifier.padding(end = 4.dp).size(22.dp))
        }
    }
}

/** The server that didn't answer, over the list: which, where the app still is, and what to do. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FailureNote(failure: ServersViewModel.Failure, actions: ServersActions) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.padding(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 10.dp).fillMaxWidth().clip(shape).background(Color(0xFF1D160C)).border(1.dp, Color(0xFF7A4A12), shape)
            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(WmmIcons.Alert, contentDescription = null, tint = WmmColors.Warn, modifier = Modifier.padding(top = 1.dp).size(20.dp))
        Column(Modifier.weight(1f)) {
            Column(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Assertive }) {
                Text(failure.title, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp, color = WmmColors.Text))
                Text(failure.body, style = TextStyle(fontFamily = Inter, fontSize = 13.sp, lineHeight = 19.sp, color = WmmColors.TextSoft), modifier = Modifier.padding(top = 2.dp))
            }
            FlowRow(Modifier.padding(top = 6.dp).offset(x = (-12).dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                GoldTextButton("Try again", actions::retry)
                if (failure.downloads > 0) GoldTextButton(watchItsDownloads(failure.downloads), actions::watchDownloads)
            }
        }
    }
}

/** A registered server: its name, who is signed in there and its address, and Current, Switching… or why it can't be switched to. */
@Composable
private fun ServerRow(row: ServersViewModel.Row, onClick: () -> Unit) {
    val line = when (row.state) {
        RowState.Switching -> SWITCHING
        RowState.SignedOut -> SIGNED_OUT_HERE
        RowState.TooOld -> TOO_OLD_FOR_APP
        RowState.Idle -> row.line
    }
    SwitcherRow(
        title = row.name,
        line = line,
        icon = WmmIcons.Server,
        tileGround = if (row.current) WmmColors.Brand else WmmColors.Line2,
        tileTint = if (row.current) WmmColors.Text else WmmColors.TextSoft,
        lineColor = when (row.state) {
            RowState.Switching -> WmmColors.TextSoft
            RowState.TooOld -> WmmColors.Warn
            else -> WmmColors.TextMuted
        },
        ground = if (row.state == RowState.Switching) RowPressed else Color.Transparent,
        onClick = onClick.takeIf { row.canChoose },
        state = if (row.current) CURRENT_SERVER else null,
    ) {
        when {
            row.current -> CurrentBadge()
            row.state == RowState.Switching -> CircularProgressIndicator(Modifier.padding(end = 6.dp).size(20.dp), color = WmmColors.Accent, strokeWidth = 3.dp, trackColor = Color.White.copy(alpha = 0.2f))
        }
    }
}

/** One row of the switcher (`.sr`): a rounded tile, a title with a line under it, and what sits at its end. */
@Composable
private fun SwitcherRow(
    title: String,
    line: String?,
    icon: ImageVector,
    onClick: (() -> Unit)?,
    tileGround: Color = WmmColors.Line2,
    tileTint: Color = WmmColors.TextSoft,
    lineColor: Color = WmmColors.TextMuted,
    ground: Color = Color.Transparent,
    minHeight: Dp = 68.dp,
    lineLimit: Int = 1,
    state: String? = null,
    trailing: @Composable () -> Unit = {},
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = minHeight).clip(shape).background(ground)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) { if (state != null) stateDescription = state }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(44.dp).background(tileGround, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = tileTint, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 16.sp, color = WmmColors.Text), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (line != null) Text(line, style = TextStyle(fontFamily = Inter, fontSize = 13.sp, lineHeight = 18.sp, color = lineColor), maxLines = lineLimit, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

/** "Current", on gold: the server the app is on. */
@Composable
internal fun CurrentBadge(modifier: Modifier = Modifier) {
    Text(
        CURRENT_SERVER.uppercase(),
        style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 0.5.sp, color = WmmColors.OnAccent),
        modifier = modifier.background(WmmColors.Accent, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 3.dp).semantics { contentDescription = CURRENT_SERVER },
    )
}
