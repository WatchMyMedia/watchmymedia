package media.watchmy.client.tv.servers

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.rememberViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import media.watchmy.client.model.TOO_OLD_FOR_APP
import media.watchmy.client.model.alreadyAdded
import media.watchmy.client.model.serversFull
import media.watchmy.client.model.needsHttpsNudge
import media.watchmy.client.tv.components.Kicker
import media.watchmy.client.tv.components.KeyboardDialog
import media.watchmy.client.tv.components.PanelRow
import media.watchmy.client.tv.components.SafeX
import media.watchmy.client.tv.components.SafeY
import media.watchmy.client.tv.components.TvPalette
import media.watchmy.client.tv.components.TvType
import media.watchmy.client.tv.components.radialGlow
import media.watchmy.client.tv.home.KeyHint
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.BrandLockup
import media.watchmy.client.ui.components.WmmIcons
import media.watchmy.client.presentation.servers.ServerPickerViewModel
import media.watchmy.client.ui.rememberLocalNetworkAccess
import media.watchmy.client.ui.LocalNetworkAccess
import media.watchmy.client.presentation.servers.isLocalAddress
import media.watchmy.client.presentation.servers.localNetworkOffMessage

private const val HTTP_NOTE =
    "An IP address or a .local name works over http on your own network. A hostname needs https; Tailscale Serve or a reverse proxy gives it one."

/**
 * Choose a server: one list of the servers found on this network and the
 * ones remembered, each saying where it is in words, with "Type its
 * address" as the last row (the address, http and https are explained in
 * the keyboard that opens). [onBack],
 * when given, leaves without changing the server (picking again from
 * sign-in or Settings).
 *
 * [adding] is Add a server: the same screen without the servers this TV is
 * signed in to already, which are named under the list, since choosing one
 * of those is a switch, made in Servers.
 */
@Composable
fun ServerPickerScreen(onServerChosen: () -> Unit, onBack: (() -> Unit)? = null, adding: Boolean = false) {
    val services = LocalAppServices.current
    // Its own for each visit, not the Activity's: a second visit starts afresh, and looking for servers stops when it goes.
    val vm = viewModel(rememberViewModelStoreOwner()) { ServerPickerViewModel(services.servers, adding = adding, device = "TV") }
    val form by vm.state.collectAsStateWithLifecycle()
    // One list on the TV: found on this network, then remembered.
    val rows = form.discovered?.let { it + form.remembered }
    // Android 17's local network access, offered again in the list's place when nothing is found.
    val access = rememberLocalNetworkAccess()
    val showAccess = access.needed && form.discovered?.isEmpty() == true
    val accessRow = remember { FocusRequester() }
    val offline = access.needed && form.addressError != null && isLocalAddress(form.address)
    var typing by remember { mutableStateOf(false) }
    val firstRow = remember { FocusRequester() }
    val addressField = remember { FocusRequester() }

    LaunchedEffect(vm) { vm.chosen.collect { onServerChosen() } }
    if (onBack != null) BackHandler(onBack = onBack)

    Box(
        Modifier
            .fillMaxSize()
            .background(WmmColors.Ground)
            .radialGlow(WmmColors.Brand.copy(alpha = 0.35f), 0.9f, 0.9f, 1000.dp, 640.dp),
    ) {
        BrandLockup(markSize = 36.dp, fontSize = 24.sp, modifier = Modifier.padding(start = SafeX, top = SafeY))
        Column(Modifier.padding(start = SafeX, top = 150.dp).width(1000.dp)) {
            Kicker(if (adding) "Add a server · step 1 of 2" else "Welcome · step 1 of 2")
            Text("Choose a server", modifier = Modifier.padding(top = 14.dp), style = TvType.Display, color = WmmColors.Text)
            val list = rows
            Text(
                when {
                    showAccess -> "This TV needs your OK before it can look on your home network."
                    list == null || list.isNotEmpty() -> "Found on your home network. Pick one, then sign in with your phone."
                    else -> "None found on this network yet. Check the server is on, or type its address."
                },
                modifier = Modifier.padding(top = 16.dp).widthIn(max = 820.dp),
                style = TvType.Body,
                color = WmmColors.TextSoft,
            )
            if (adding && form.full) Text(serversFull("TV"), modifier = Modifier.padding(top = 16.dp), style = TvType.Second, color = WmmColors.Warn)
            // 10 dp wider on each side than the column, so a focused row's ring and growth aren't clipped.
            LazyColumn(
                Modifier.padding(top = 30.dp).requiredWidth(1020.dp).heightIn(max = 600.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(10.dp),
            ) {
                if (showAccess) item(key = "local-network") { AccessRow(access, Modifier.focusRequester(accessRow)) }
                if (list != null) items(list, key = { it.key }) { row ->
                    val isFirst = row == (list.firstOrNull { it.current } ?: list.first())
                    ServerRow(
                        row = row,
                        connecting = form.connecting == row.key,
                        onClick = { vm.choose(row) },
                        modifier = if (isFirst) Modifier.focusRequester(firstRow) else Modifier,
                    )
                }
                item(key = "address") {
                    PanelRow(
                        if (form.connecting == ServerPickerViewModel.ADDRESS) "Connecting to ${form.address}…" else "Type its address",
                        onClick = { typing = true },
                        modifier = Modifier.focusRequester(addressField),
                        description = form.address.takeIf { it.isNotBlank() } ?: "For a server on another network, or one that isn’t found",
                        icon = WmmIcons.Globe,
                        chevron = true,
                    )
                }
            }
            if (list == null || list.isNotEmpty() || !showAccess) {
                Row(Modifier.padding(top = 28.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(16.dp).background(WmmColors.Accent, CircleShape))
                    Text("Still looking. Servers on this network appear here by themselves.", style = TvType.Second, color = TvPalette.Muted)
                }
            }
            // The ones this TV is signed in to already aren't offered again.
            alreadyAdded(form.alreadyAdded)?.let { Text(it, modifier = Modifier.padding(top = 16.dp), style = TvType.Second, color = TvPalette.Muted) }
            val addressError = if (offline) {
                "${localNetworkOffMessage(form.address, "TV")} Focus moved to ${if (access.stopped) "Local network access is off" else "Allow local network access"}, above."
            } else {
                form.addressError
            }
            (form.error ?: addressError)?.let { Text(it, modifier = Modifier.padding(top = 16.dp).widthIn(max = 900.dp), style = TvType.Second, color = WmmColors.Warn) }
        }
        KeyHint("Back", if (onBack != null) "Servers" else "Leaves WatchMyMedia", Modifier.align(Alignment.BottomStart).padding(start = SafeX, bottom = SafeY))
    }
    if (typing) {
        KeyboardDialog(
            title = "Server address",
            initial = form.address,
            placeholder = "192.168.1.20",
            note = HTTP_NOTE,
            submitLabel = "Connect",
            extraKeys = listOf("https://", "http://", ".local", ":27587"),
            noteWarns = ::needsHttpsNudge,
            onDismiss = { typing = false },
            onSubmit = {
                typing = false
                vm.setAddress(it)
                vm.connectToAddress()
            },
        )
    }

    val hasRows = rows?.isNotEmpty() == true
    LaunchedEffect(rows != null, showAccess) {
        if (rows == null) return@LaunchedEffect
        withFrameNanos { }
        when {
            showAccess -> accessRow.requestFocus()
            hasRows -> firstRow.requestFocus()
            else -> addressField.requestFocus()
        }
    }
    // A local address that failed for want of local network access: focus goes to the row that allows it.
    LaunchedEffect(offline, showAccess) {
        if (!offline || !showAccess) return@LaunchedEffect
        withFrameNanos { }
        runCatching { accessRow.requestFocus() }
    }
}

/**
 * "Allow local network access" in the list's place:
 * OK shows the TV's own question, or opens WatchMyMedia's page in the TV's
 * settings once the TV has stopped asking.
 */
@Composable
private fun AccessRow(access: LocalNetworkAccess, modifier: Modifier = Modifier) {
    val stopped = access.stopped
    PanelRow(
        if (stopped) "Local network access is off" else "Let this TV look for servers",
        onClick = access::ask,
        modifier = modifier,
        description = if (stopped) {
            "The TV has stopped asking. In its settings, open Permissions › Nearby devices and allow it for WatchMyMedia, then come back."
        } else {
            "The TV asks once for access to your home network. Servers you reach over the internet work without it."
        },
        icon = WmmIcons.Server,
        iconTint = WmmColors.Accent,
    )
}

/** `.tag.warn`: plain http to a server beyond this network. */
@Composable
private fun NotEncryptedTag(modifier: Modifier = Modifier) {
    Row(
        modifier.border(2.dp, WmmColors.Warn, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(WmmIcons.LockOpen, contentDescription = null, tint = WmmColors.Warn, modifier = Modifier.size(24.dp))
        Text("Not encrypted", style = TvType.Caption.copy(fontWeight = FontWeight.SemiBold), color = WmmColors.Warn)
    }
}

@Composable
private fun ServerRow(row: ServerPickerViewModel.Row, connecting: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val line = when {
        connecting -> "Connecting…"
        row.tooOld -> TOO_OLD_FOR_APP
        row.onNetwork -> "On this network"
        else -> "You signed in here before"
    }
    Box(modifier.fillMaxWidth()) {
        PanelRow(row.name, onClick = onClick, description = line, icon = if (row.onNetwork) WmmIcons.Server else WmmIcons.Globe, enabled = !row.tooOld)
        // A server reached over plain http beyond this network: a warning, in words, at the row's end.
        if (row.notEncrypted) NotEncryptedTag(Modifier.align(Alignment.CenterEnd).padding(end = 24.dp))
    }
}
