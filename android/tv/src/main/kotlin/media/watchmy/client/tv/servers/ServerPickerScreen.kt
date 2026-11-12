package media.watchmy.client.tv.servers

import android.os.Build
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.rememberViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import media.watchmy.client.model.MIN_SERVER_VERSION
import media.watchmy.client.model.needsHttpsNudge
import media.watchmy.client.tv.components.FocusableBox
import media.watchmy.client.tv.components.Kicker
import media.watchmy.client.tv.components.KeyboardDialog
import media.watchmy.client.tv.components.KeyboardField
import media.watchmy.client.tv.components.TvButton
import media.watchmy.client.tv.components.TvButtonStyle
import media.watchmy.client.tv.components.inter
import media.watchmy.client.tv.components.radialGlow
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.BrandLockup
import media.watchmy.client.ui.components.WmmIcons

private const val HTTP_NOTE =
    "An IP address or a .local name works over http on your own network. A hostname needs https; Tailscale Serve or a reverse proxy gives it one."

/**
 * Choose a server (mockup: design/mockups/android-tv/ServerPicker.dc.html):
 * servers on this network, remembered ones, or a typed address. [onBack],
 * when given, leaves without changing the server (picking again from
 * sign-in or Settings).
 */
@Composable
fun ServerPickerScreen(onServerChosen: () -> Unit, onBack: (() -> Unit)? = null) {
    val services = LocalAppServices.current
    // Its own for each visit, not the Activity's: a second visit starts afresh, and looking for servers stops when it goes.
    val vm = viewModel(rememberViewModelStoreOwner()) { ServerPickerViewModel(services.servers) }
    val rows by vm.rows.collectAsStateWithLifecycle()
    val form by vm.form.collectAsStateWithLifecycle()
    val needsHttps by vm.addressNeedsHttps.collectAsStateWithLifecycle()
    val deviceName by services.auth.deviceName.collectAsStateWithLifecycle()
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
        BrandLockup(markSize = 44.dp, fontSize = 26.sp, modifier = Modifier.padding(start = 58.dp, top = 48.dp))

        Column(Modifier.padding(start = 160.dp, top = 200.dp).width(820.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
            Kicker("Servers")
            Text("Choose a server", style = inter(60.sp, FontWeight.Bold, lineHeight = 65.sp, tracking = -0.02f), color = WmmColors.Text)
            Text("Servers on this network appear by themselves.", style = inter(24.sp), color = WmmColors.TextMuted)
            val list = rows
            when {
                list == null -> Text("Looking for servers…", style = inter(22.sp), color = WmmColors.TextMuted)
                list.isEmpty() -> Text(
                    "No servers found on this network yet. Enter an address instead.",
                    style = inter(22.sp),
                    color = WmmColors.TextMuted,
                )
                // 12 dp wider on each side than the column, so the focus ring is not clipped.
                else -> LazyColumn(
                    Modifier.requiredWidth(844.dp).heightIn(max = 560.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    contentPadding = PaddingValues(top = 16.dp, bottom = 16.dp, start = 12.dp, end = 12.dp),
                ) {
                    items(list, key = { it.id }) { row ->
                        val isFirst = row == (list.firstOrNull { it.current } ?: list.first())
                        ServerRow(
                            row = row,
                            connecting = form.connecting == row.id,
                            onClick = { vm.choose(row) },
                            modifier = if (isFirst) Modifier.focusRequester(firstRow) else Modifier,
                        )
                    }
                }
            }
            form.rowError?.let { Text(it, style = inter(20.sp, lineHeight = 30.sp), color = WmmColors.Error) }
        }

        Column(Modifier.padding(start = 1100.dp, top = 380.dp).width(660.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            Text("Or enter an address", style = inter(30.sp, FontWeight.SemiBold), color = WmmColors.Text)
            Text("Server address", style = inter(20.sp), color = WmmColors.TextMuted)
            FocusableBox(onClick = { typing = true }, modifier = Modifier.focusRequester(addressField), cornerRadius = 14.dp, focusedScale = 1.02f) {
                KeyboardField(form.address, placeholder = "192.168.1.20 or https://media.example.net")
            }
            Text(HTTP_NOTE, style = inter(18.sp, lineHeight = 27.sp), color = if (needsHttps) WmmColors.Warn else WmmColors.TextFaint)
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                TvButton(
                    if (form.connecting == ServerPickerViewModel.ADDRESS) "Connecting…" else "Connect",
                    vm::connectAddress,
                    style = TvButtonStyle.Primary,
                    enabled = form.connecting == null,
                )
                if (onBack != null) TvButton("Cancel", onBack)
            }
            form.addressError?.let { Text(it, style = inter(20.sp, lineHeight = 30.sp), color = WmmColors.Error) }
        }

        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 58.dp, end = 58.dp, bottom = 44.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Needs server $MIN_SERVER_VERSION or newer", style = inter(18.sp), color = WmmColors.TextFaint)
            Text("This device: $deviceName · ${Build.MODEL}", style = inter(18.sp), color = WmmColors.TextFaint)
        }
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
                vm.connectAddress()
            },
        )
    }

    val hasRows = rows?.isNotEmpty() == true
    LaunchedEffect(rows != null) {
        if (rows == null) return@LaunchedEffect
        withFrameNanos { }
        if (hasRows) firstRow.requestFocus() else addressField.requestFocus()
    }
}

/** `.tag.warn` (ServerNotEncrypted.dc.html): plain http to a server beyond this network. */
@Composable
private fun NotEncryptedTag() {
    Row(
        Modifier.border(2.dp, WmmColors.Warn, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(WmmIcons.LockOpen, contentDescription = null, tint = WmmColors.Warn, modifier = Modifier.size(20.dp))
        Text("NOT ENCRYPTED", style = inter(17.sp, FontWeight.SemiBold, tracking = 0.04f), color = WmmColors.Warn)
    }
}

@Composable
private fun ServerRow(row: ServerPickerViewModel.Row, connecting: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(18.dp)
    FocusableBox(onClick = onClick, modifier = modifier.fillMaxWidth(), cornerRadius = 18.dp, focusedScale = 1.02f) { focused ->
        Row(
            Modifier
                .fillMaxWidth()
                .height(120.dp)
                .background(if (focused) WmmColors.Surface3 else WmmColors.Surface2, shape)
                .border(1.dp, WmmColors.Line2, shape)
                .padding(horizontal = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Box(
                Modifier.size(64.dp).background(if (row.onNetwork) WmmColors.Brand else WmmColors.Line2, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (row.onNetwork) WmmIcons.Server else WmmIcons.Globe, contentDescription = null, tint = WmmColors.Text, modifier = Modifier.size(34.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(row.name, style = inter(26.sp, FontWeight.SemiBold), color = WmmColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (connecting) "Connecting…" else row.detail,
                    style = inter(19.sp),
                    color = if (row.tooOld) WmmColors.Warn else WmmColors.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (row.onNetwork) {
                Text(
                    "ON THIS NETWORK",
                    modifier = Modifier.background(WmmColors.Accent, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
                    style = inter(17.sp, FontWeight.SemiBold, tracking = 0.04f),
                    color = WmmColors.OnAccent,
                )
            }
            if (row.notEncrypted) NotEncryptedTag()
        }
    }
}
