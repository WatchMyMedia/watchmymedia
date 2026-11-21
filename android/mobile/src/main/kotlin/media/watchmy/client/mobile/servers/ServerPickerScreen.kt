package media.watchmy.client.mobile.servers

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import media.watchmy.client.mobile.components.BackTopBar
import media.watchmy.client.mobile.components.FieldText
import media.watchmy.client.mobile.components.FilledButton
import media.watchmy.client.mobile.components.IconTile
import media.watchmy.client.mobile.components.ListRow
import media.watchmy.client.mobile.components.NotEncryptedMark
import media.watchmy.client.mobile.components.SectionLabel
import media.watchmy.client.mobile.components.fieldColors
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import media.watchmy.client.mobile.settings.NoteTone
import media.watchmy.client.mobile.settings.StatusCard
import media.watchmy.client.model.AddServerStep
import media.watchmy.client.model.MAX_SERVERS
import media.watchmy.client.model.MIN_SERVER_VERSION
import media.watchmy.client.model.alreadyAdded
import media.watchmy.client.model.plural
import media.watchmy.client.model.serversFull
import media.watchmy.client.model.needsHttpsNudge
import media.watchmy.client.ui.Inter
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.TestTags
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.WmmIcons
import media.watchmy.client.presentation.servers.ServerPickerViewModel
import androidx.compose.foundation.text.input.TextFieldLineLimits
import media.watchmy.client.ui.rememberModelTextField
import media.watchmy.client.ui.rememberLocalNetworkAccess
import media.watchmy.client.ui.LocalNetworkAccess
import media.watchmy.client.presentation.servers.isLocalAddress
import media.watchmy.client.presentation.servers.localNetworkOffMessage
import androidx.compose.foundation.border

/**
 * Choosing a server: on this network, remembered, or
 * a typed address. [onBack] is null when there is nowhere to go back to (no
 * server chosen yet).
 *
 * [adding] is Add a server's first step: the same lists without the servers
 * already registered, which are named under them, since choosing one of
 * those is a switch, made in Servers.
 */
@Composable
fun ServerPickerScreen(onServerChosen: () -> Unit, onBack: (() -> Unit)? = null, adding: Boolean = false) {
    val services = LocalAppServices.current
    val device = if (LocalConfiguration.current.smallestScreenWidthDp >= 600) "tablet" else "phone"
    // Add a server's own model: the picker's lives as long as the app, and lists every server.
    val vm = if (adding) viewModel(key = "add-server") { ServerPickerViewModel(services.servers, adding = true, device = device) } else viewModel { ServerPickerViewModel(services.servers) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.chosen.collect { onServerChosen() } }
    // The field holds its text; the model hears it as it is typed.
    val address = rememberModelTextField(state.address, state.revision, vm::setAddress)
    // Android 17's local network access, offered again here when nothing is found.
    val access = rememberLocalNetworkAccess()
    val offline = access.needed && state.addressError != null && isLocalAddress(address.text.toString())
    val connect = {
        vm.setAddress(address.text.toString())
        vm.connectToAddress()
    }
    if (onBack != null) BackHandler(onBack = onBack)

    Box(Modifier.fillMaxSize().background(WmmColors.Ground), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
            if (adding) {
                AddServerBar(onBack ?: {})
            } else if (onBack != null) {
                BackTopBar("Servers", onBack)
            } else {
                Row(Modifier.statusBarsPadding().height(64.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Servers", style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, color = WmmColors.Text))
                }
            }
            BoxWithConstraints(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
                Column(Modifier.verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(top = if (adding) 0.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (adding) {
                        AddServerSteps(AddServerStep.Choose)
                        Text(
                            "Which server?",
                            style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp, color = WmmColors.Text),
                            modifier = Modifier.padding(horizontal = 16.dp).semantics { heading() },
                        )
                        if (state.full) StatusCard("${plural(MAX_SERVERS, "server")} are added", serversFull(device), NoteTone.Warn, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    }
                    SectionLabel("On this network")
                    val found = state.discovered
                    when {
                        found == null -> Hint("Looking for servers…", busy = true)
                        found.isEmpty() -> {
                            Hint(if (adding && state.alreadyAdded.isNotEmpty()) "No other servers found on this network. Enter an address below." else "No servers found on this network. Enter an address below.")
                            if (access.needed) LocalNetworkCard(access)
                        }
                        else -> found.forEach { row -> ServerRow(row, connecting = state.connecting == row.key, remembered = false, onClick = { vm.choose(row) }, onForget = {}) }
                    }
                    // The ones this device is signed in to already aren't offered again.
                    alreadyAdded(state.alreadyAdded)?.let { line ->
                        Text(line, style = TextStyle(fontFamily = Inter, fontSize = 13.sp, lineHeight = 19.5.sp, color = WmmColors.TextMuted), modifier = Modifier.padding(horizontal = 16.dp))
                    }
                    state.error?.let { ErrorNote(it, vm::dismissError) }

                    if (state.remembered.isNotEmpty()) {
                        SectionLabel("Remembered", Modifier.padding(top = 12.dp))
                        state.remembered.forEach { row -> ServerRow(row, connecting = state.connecting == row.key, remembered = true, onClick = { vm.choose(row) }, onForget = { vm.forget(row) }) }
                    }

                    SectionLabel("Enter an address", Modifier.padding(top = 12.dp))
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        OutlinedTextField(
                            state = address,
                            label = { Text("Server address") },
                            placeholder = { Text("192.168.1.20 or https://home.example.net") },
                            lineLimits = TextFieldLineLimits.SingleLine,
                            isError = state.addressError != null,
                            supportingText = (if (offline) "${localNetworkOffMessage(address.text.toString(), "phone")} ${if (access.stopped) "Open settings" else "Allow"}, above, turns it on." else state.addressError)?.let { e -> { Text(e) } }
                                ?: if (needsHttpsNudge(address.text.toString())) {
                                    { Text("Plain http sends your password unencrypted. Use https for a hostname.", color = WmmColors.Warn) }
                                } else {
                                    null
                                },
                            textStyle = FieldText,
                            colors = fieldColors(),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                            onKeyboardAction = { connect() },
                            modifier = Modifier.fillMaxWidth().testTag(TestTags.SERVER_ADDRESS),
                        )
                        Text(
                            "An IP address or a .local name works over http on your own network. A hostname needs https; Tailscale Serve or a reverse proxy gives it one.",
                            style = TextStyle(fontFamily = Inter, fontSize = 13.sp, lineHeight = 19.5.sp, color = WmmColors.TextFaint),
                        )
                        FilledButton(
                            "Connect",
                            onClick = connect,
                            busy = state.connecting == ServerPickerViewModel.ADDRESS,
                            modifier = Modifier.align(Alignment.End).testTag(TestTags.SERVER_CONNECT),
                        )
                    }
                    Spacer(Modifier.weight(1f).heightIn(min = 24.dp))
                    Text(
                        "Needs server $MIN_SERVER_VERSION or newer",
                        style = TextStyle(fontFamily = Inter, fontSize = 12.sp, color = WmmColors.TextFaint),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ServerRow(row: ServerPickerViewModel.Row, connecting: Boolean, remembered: Boolean, onClick: () -> Unit, onForget: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        ListRow(
            title = row.name,
            subtitle = row.detail,
            subtitleColor = if (row.tooOld) WmmColors.Warn else WmmColors.TextMuted,
            subtitleSize = 14,
            minHeight = 72.dp,
            modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = if (remembered) ({ menu = true }) else null, onLongClickLabel = if (remembered) "Forget" else null)
                .padding(vertical = 2.dp),
            leading = {
                if (remembered) IconTile(WmmIcons.Globe, background = WmmColors.Line2) else IconTile(WmmIcons.Server)
            },
            below = if (row.notEncrypted) ({ NotEncryptedMark() }) else null,
            trailing = {
                when {
                    connecting -> CircularProgressIndicator(Modifier.size(22.dp), color = WmmColors.Accent, strokeWidth = 2.dp)
                    row.current -> Icon(WmmIcons.Check, contentDescription = "In use", tint = WmmColors.Accent, modifier = Modifier.size(22.dp))
                    else -> Icon(WmmIcons.ChevronRight, contentDescription = null, tint = WmmColors.TextMuted, modifier = Modifier.size(22.dp))
                }
            },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = WmmColors.Surface3) {
            DropdownMenuItem(text = { Text("Forget this server") }, onClick = {
                menu = false
                onForget()
            })
        }
    }
}

@Composable
private fun Hint(text: String, busy: Boolean = false) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = WmmColors.Accent, strokeWidth = 2.dp)
        Text(text, style = TextStyle(fontFamily = Inter, fontSize = 14.sp, color = WmmColors.TextMuted))
    }
}

/** Why the last server tapped could not be used (unreachable, too old). */
@Composable
private fun ErrorNote(message: String, onDismiss: () -> Unit) {
    Row(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(WmmColors.Error.copy(alpha = 0.12f))
            .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, style = TextStyle(fontFamily = Inter, fontSize = 14.sp, lineHeight = 20.sp, color = WmmColors.Error), modifier = Modifier.weight(1f).padding(vertical = 8.dp))
        IconButton(onClick = onDismiss) { Icon(WmmIcons.Close, contentDescription = "Dismiss", tint = WmmColors.Error, modifier = Modifier.size(20.dp)) }
    }
}

/** "Allow local network access", or "Open settings" once Android has stopped asking. */
@Composable
private fun LocalNetworkCard(access: LocalNetworkAccess) {
    val stopped = access.stopped
    Column(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().background(WmmColors.Surface, RoundedCornerShape(16.dp)).border(1.dp, WmmColors.Line2, RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier.size(40.dp).background(if (stopped) WmmColors.Line2 else WmmColors.Accent.copy(alpha = 0.16f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon(WmmIcons.Server, contentDescription = null, tint = if (stopped) WmmColors.TextSoft else WmmColors.Accent, modifier = Modifier.size(22.dp)) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (stopped) "Local network access is off" else "Allow local network access", style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = WmmColors.Text))
                Text(
                    if (stopped) {
                        "Android has stopped asking. In WatchMyMedia’s settings, open Permissions › Nearby devices and allow it, then come back."
                    } else {
                        "WatchMyMedia needs it to find servers on this network and reach them at their local address. Servers over the internet work without it."
                    },
                    style = TextStyle(fontFamily = Inter, fontSize = 14.sp, lineHeight = 20.sp, color = WmmColors.TextMuted),
                )
            }
        }
        FilledButton(if (stopped) "Open settings" else "Allow", onClick = access::ask, modifier = Modifier.align(Alignment.End))
    }
}
