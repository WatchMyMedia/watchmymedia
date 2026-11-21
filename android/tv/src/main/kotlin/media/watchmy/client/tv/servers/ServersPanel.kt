package media.watchmy.client.tv.servers

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import media.watchmy.client.model.ADD_A_SERVER
import media.watchmy.client.model.ADD_A_SERVER_LINE
import media.watchmy.client.model.CURRENT_SERVER
import media.watchmy.client.model.DeepLink
import media.watchmy.client.model.MANAGE_SERVERS
import media.watchmy.client.model.SERVERS
import media.watchmy.client.model.SIGNED_OUT_HERE
import media.watchmy.client.model.SWITCHING
import media.watchmy.client.model.TOO_OLD_FOR_APP
import media.watchmy.client.model.serversFull
import media.watchmy.client.model.switchingStopsCasting
import media.watchmy.client.presentation.servers.ServersActions
import media.watchmy.client.presentation.servers.ServersViewModel
import media.watchmy.client.presentation.servers.ServersViewModel.RowState
import media.watchmy.client.tv.components.FocusableBox
import media.watchmy.client.tv.components.TextBright
import media.watchmy.client.tv.components.TvButton
import media.watchmy.client.tv.components.TvButtonSize
import media.watchmy.client.tv.components.TvDialog
import media.watchmy.client.tv.components.inter
import media.watchmy.client.tv.components.tryFocus
import media.watchmy.client.ui.TestTags
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.WmmIcons

/**
 * What Servers needs from the app above the signed-in screens, which are
 * rebuilt on every switch: starting Add a server, opening a search result
 * that is on another server, and Manage servers, which opens Settings on
 * its Servers page ([manage], taken once by Settings).
 */
@Stable
class ServersHost(val onAdd: () -> Unit, val onOpenElsewhere: (serverId: String, query: String, page: DeepLink) -> Unit) {
    var manage by mutableStateOf(false)
}

/** Null where there is no app above (a screen on its own in a test): Add a server and Manage servers then do nothing. */
@Suppress("ComposeCompositionLocalUsage")
val LocalServersHost = staticCompositionLocalOf<ServersHost?> { null }

/** The rail's width: the Servers panel opens beside it, and the rail stays in view with the account button ringed. */
private val Rail = 96.dp

/**
 * Servers, the switcher, on the TV: a panel from the left over a dimmed
 * screen, opened by OK on the account button at the rail's foot. The servers
 * this TV is signed in to, the one in use first; Add a server; Manage
 * servers. Focus opens on the first server that isn't the one in use, on Add
 * a server when there is one server, and on Try again after a server didn't
 * answer. Back closes it, and focus goes back to the account button.
 */
@Composable
internal fun ServersPanel(state: ServersViewModel.State, actions: ServersActions, onAdd: () -> Unit, onManage: () -> Unit, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    val retry = remember { FocusRequester() }
    // Where focus opens: the first server that can be switched to; with none, Add a server.
    val entry = state.rows.firstOrNull { it.canChoose }?.serverId
    TvDialog(onDismiss) {
        Box(Modifier.fillMaxSize().padding(start = Rail).background(Color.Black.copy(alpha = 0.6f))) {
            Column(
                Modifier
                    .width(640.dp)
                    .fillMaxHeight()
                    .background(WmmColors.Surface)
                    .border(1.dp, WmmColors.Line)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 44.dp, end = 44.dp, top = 64.dp, bottom = 54.dp)
                    .semantics { paneTitle = SERVERS }
                    .testTag(TestTags.SERVERS),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text(SERVERS, style = inter(34.sp, FontWeight.ExtraBold, tracking = -0.015f), color = WmmColors.Text, modifier = Modifier.semantics { heading() })
                state.casting?.let { Text(switchingStopsCasting(it), style = inter(19.sp, lineHeight = 28.sp), color = WmmColors.TextMuted) }
                state.failure?.let { FailureNote(it, actions::retry, retry) }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    state.rows.forEach { row ->
                        ServerOption(row, row.line, onClick = { actions.choose(row.serverId) }, modifier = if (row.serverId == entry) Modifier.focusRequester(first) else Modifier)
                    }
                }
                PanelRule()
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    PanelOption(
                        ADD_A_SERVER, if (state.full) serversFull("TV") else ADD_A_SERVER_LINE, WmmIcons.Plus,
                        onClick = onAdd, enabled = !state.full,
                        modifier = if (entry == null && state.failure == null) Modifier.focusRequester(first) else Modifier,
                    )
                    PanelOption(MANAGE_SERVERS, null, WmmIcons.Sliders, onClick = onManage, minHeight = 68.dp) {
                        Icon(WmmIcons.ChevronRight, contentDescription = null, tint = WmmColors.TextMuted, modifier = Modifier.size(24.dp))
                    }
                }
                Box(Modifier.weight(1f))
                Text("Back closes this panel.", style = inter(17.sp), color = WmmColors.TextFaint)
            }
        }
        // Where focus opens, and where it goes when a server doesn't answer.
        LaunchedEffect(state.failure?.serverId) {
            withFrameNanos { }
            if (state.failure != null) retry.tryFocus() else first.tryFocus()
        }
    }
}

/** A server that didn't answer, over the list (`.tvnote`): which, where the TV still is, and Try again, which [retry] gives focus to. */
@Composable
internal fun FailureNote(failure: ServersViewModel.Failure, onRetry: () -> Unit, retry: FocusRequester, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier.fillMaxWidth().background(Color(0xFF1D160C), shape).border(1.dp, Color(0xFF7A4A12), shape).padding(start = 22.dp, end = 22.dp, top = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Assertive }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(failure.title, style = inter(22.sp, FontWeight.Bold), color = WmmColors.Text)
            Text(failure.body, style = inter(19.sp, lineHeight = 28.sp), color = WmmColors.TextSoft)
        }
        TvButton("Try again", onRetry, Modifier.focusRequester(retry).padding(start = 6.dp, top = 10.dp), size = TvButtonSize.Settings)
    }
}

/**
 * A registered server (`.opt`): its name over a line, with Current on the
 * one in use and Switching… while it is asked. [line] is who is signed in
 * and its address, with its version in Settings; a server signed out here
 * or too old says that instead.
 */
@Composable
internal fun ServerOption(row: ServersViewModel.Row, line: String, onClick: () -> Unit, modifier: Modifier = Modifier, chevron: Boolean = false, plainIcon: Boolean = !row.current) {
    val shown = when (row.state) {
        RowState.SignedOut -> SIGNED_OUT_HERE
        RowState.TooOld -> TOO_OLD_FOR_APP
        else -> line
    }
    PanelOption(
        row.name, shown, WmmIcons.Server,
        onClick = onClick,
        // The one in use and one too old keep their place in the path down the panel, and do nothing.
        enabled = chevron || row.canChoose,
        tileGround = if (plainIcon) WmmColors.Line2 else WmmColors.Brand,
        lineColor = if (row.state == RowState.TooOld) WmmColors.Warn else WmmColors.TextMuted,
        state = if (row.current) CURRENT_SERVER else null,
        modifier = modifier,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            when {
                row.current -> CurrentBadge()
                row.state == RowState.Switching -> {
                    CircularProgressIndicator(Modifier.size(22.dp), color = WmmColors.Accent, strokeWidth = 3.dp, trackColor = Color.White.copy(alpha = 0.22f))
                    Text(SWITCHING, style = inter(19.sp), color = WmmColors.TextSoft)
                }
            }
            if (chevron) Icon(WmmIcons.ChevronRight, contentDescription = null, tint = WmmColors.TextMuted, modifier = Modifier.size(24.dp))
        }
    }
}

/** One row of a Servers panel: a rounded tile, a title over an optional line, and what sits at its end. */
@Composable
internal fun PanelOption(
    title: String,
    line: String?,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tileGround: Color = WmmColors.Line2,
    lineColor: Color = WmmColors.TextMuted,
    minHeight: Dp = 84.dp,
    state: String? = null,
    trailing: @Composable () -> Unit = {},
) {
    FocusableBox(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().semantics { if (state != null) stateDescription = state },
        cornerRadius = 12.dp,
        focusedScale = 1f,
        ringOffset = (-4).dp,
        enabled = enabled,
        role = Role.Button,
    ) { focused ->
        Row(
            Modifier.fillMaxWidth().heightIn(min = minHeight).background(if (focused) WmmColors.Surface2 else Color.Transparent, RoundedCornerShape(12.dp)).padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(Modifier.size(48.dp).background(tileGround, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = WmmColors.Text, modifier = Modifier.size(24.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = inter(21.sp, FontWeight.SemiBold), color = WmmColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (line != null) Text(line, style = inter(17.sp, lineHeight = 23.sp), color = lineColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            trailing()
        }
    }
}

/** "Current", on gold: the server the TV is on. */
@Composable
internal fun CurrentBadge() {
    Text(
        CURRENT_SERVER.uppercase(),
        style = inter(13.sp, FontWeight.Bold, tracking = 0.05f),
        color = WmmColors.OnAccent,
        modifier = Modifier.background(WmmColors.Accent, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 4.dp).semantics { contentDescription = CURRENT_SERVER },
    )
}

@Composable
internal fun PanelRule(modifier: Modifier = Modifier) {
    Box(modifier.padding(horizontal = 18.dp).fillMaxWidth().height(1.dp).background(WmmColors.Line))
}

/**
 * The note after a switch, at the top right over the new server's Home:
 * "Now on Cabin server". It takes no focus and goes by itself.
 */
@Composable
fun SwitchBanner(text: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier.background(Color(0xF00C0C0D), shape).border(1.dp, Color.White.copy(alpha = 0.14f), shape).padding(start = 18.dp, end = 30.dp, top = 18.dp, bottom = 18.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Box(Modifier.size(56.dp).background(WmmColors.Brand, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
            Icon(WmmIcons.Server, contentDescription = null, tint = WmmColors.Text, modifier = Modifier.size(28.dp))
        }
        Text(text, style = inter(26.sp, FontWeight.Bold), color = TextBright, maxLines = 1)
    }
}
