package media.watchmy.client.mobile.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import media.watchmy.client.mobile.components.GoldTextButton
import media.watchmy.client.mobile.components.ListRow
import media.watchmy.client.mobile.components.RowValue
import media.watchmy.client.mobile.downloads.usedSize
import media.watchmy.client.model.DownloadQuality
import media.watchmy.client.model.freeSize
import media.watchmy.client.model.onThisDevice
import media.watchmy.client.model.plural
import media.watchmy.client.presentation.downloads.DownloadsViewModel
import media.watchmy.client.ui.Inter
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.WmmIcons

/** The downloads' model, shared by the overview, Downloads and the sign-out dialog. */
@Composable
internal fun downloadsModel(): DownloadsViewModel {
    val services = LocalAppServices.current
    return viewModel { DownloadsViewModel(services.downloads) }
}

/** "7 downloads, 66.1 GB." for a dialog's Deleted line. */
internal fun downloadsHeld(downloads: DownloadsViewModel.State): String = "${plural(downloads.all.size, "download")}, ${usedSize(downloads.usedBytes)}."

/**
 * Settings › Downloads:
 * the state in a line, the quality new downloads are made at, Wi-Fi only,
 * the space they take (to Downloads), and Remove all downloads apart at the
 * bottom, which asks first.
 */
@Composable
internal fun DownloadsPage(tablet: Boolean, onSpace: () -> Unit, modifier: Modifier = Modifier) {
    val vm = downloadsModel()
    val downloads by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refreshStorage() }
    val device = deviceWord()
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    val held = downloads.all.size
    val free = freeSize(downloads.storage.freeBytes)

    Column(modifier) {
        Text(
            if (held > 0) "${plural(held, "download")} · ${usedSize(downloads.usedBytes)} used · $free free on this $device"
            else "Nothing downloaded yet. Download a movie or an episode from its page to watch it without a connection.",
            style = LedeStyle,
            modifier = Modifier.padding(horizontal = if (tablet) 4.dp else 16.dp).padding(top = 4.dp, bottom = 2.dp),
        )
        PageHeading(onThisDevice(device), tablet, Modifier.padding(top = 0.dp))
        SettingsRows(tablet) {
            ListRow(
                "Quality",
                subtitle = "For new downloads; each can pick another",
                modifier = Modifier.clickable { dialog = DIALOG_QUALITY },
                trailing = { RowValue(downloads.settings.quality.short) },
            )
            val wifiOnly = downloads.settings.wifiOnly
            ListRow(
                "Wi-Fi only",
                subtitle = if (wifiOnly) "Downloads wait for Wi-Fi" else "Downloads use mobile data too",
                modifier = Modifier.toggleable(value = wifiOnly, role = Role.Switch, onValueChange = vm::setWifiOnly),
                trailing = { Switch(checked = wifiOnly, onCheckedChange = null, colors = SettingSwitchColors) },
            )
            ListRow(
                "Space used",
                subtitle = if (held == 0) "$free free on this $device" else "${plural(held, "download")} · $free free",
                modifier = Modifier.clickable(onClick = onSpace),
                trailing = { RowValue(usedSize(downloads.usedBytes)) },
            )
        }
        if (held > 0) {
            Rule(Modifier.padding(top = 4.dp))
            DangerRow("Remove all downloads…", WmmIcons.Trash, onClick = { dialog = DIALOG_REMOVE_ALL })
        }
        FootNote("Downloads belong to your account on this $device. Signing out deletes them.", Modifier.padding(top = 6.dp), tablet = tablet)
    }

    when (dialog) {
        DIALOG_QUALITY -> DownloadQualityDialog(downloads.settings.quality, device, onDismiss = { dialog = null }) {
            vm.setQuality(it)
            dialog = null
        }
        DIALOG_REMOVE_ALL -> RemoveAllDialog(downloads, device, onRemove = vm::removeAll, onClose = { dialog = null })
    }
}

/** Download quality: Original, said in full, then the steps. */
@Composable
internal fun DownloadQualityDialog(current: DownloadQuality, device: String, onDismiss: () -> Unit, onPick: (DownloadQuality) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = WmmColors.Surface2,
        title = { Text("Download quality") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Used for new downloads. The download sheet shows each size before you start.",
                    style = TextStyle(fontFamily = Inter, fontSize = 13.sp, lineHeight = 19.sp, color = WmmColors.TextMuted),
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                Column(Modifier.selectableGroup()) {
                    DownloadQuality.entries.forEach { q ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = q == current, role = Role.RadioButton, onClick = { onPick(q) }),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = q == current, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = WmmColors.Accent, unselectedColor = WmmColors.TextMuted))
                            Column(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp)) {
                                Text(q.label, style = TextStyle(fontFamily = Inter, fontSize = 15.sp, color = WmmColors.Text))
                                if (q == DownloadQuality.Original) {
                                    Text("Full quality: the file itself when this $device can play it", style = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = WmmColors.TextMuted))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { GoldTextButton("Close", onDismiss, color = WmmColors.TextSoft) },
    )
}

/**
 * "Remove all downloads?": what is
 * deleted and what stays. Removing works on the files one by one; when some
 * are still here a few seconds later it says how many went, and tries again.
 */
@Composable
private fun RemoveAllDialog(downloads: DownloadsViewModel.State, device: String, onRemove: () -> Unit, onClose: () -> Unit) {
    val count by rememberUpdatedState(downloads.all.size)
    // How many there were when Remove all was pressed, while it works; then how many are left.
    var removing by remember { mutableStateOf<Int?>(null) }
    var stopped by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    LaunchedEffect(removing) {
        val total = removing ?: return@LaunchedEffect
        val gone = withTimeoutOrNull(REMOVE_WAIT_MS) { snapshotFlow { count }.first { it == 0 } } != null
        removing = null
        if (gone) onClose() else stopped = (total - count) to total
    }
    ConfirmDialog(
        title = "Remove all downloads?",
        lines = listOf(
            ConfirmLine(downloadsHeld(downloads), label = "Deleted from this $device"),
            ConfirmLine("What you’ve watched, and everything in your library. You can download them again.", label = "Stays"),
        ),
        keep = "Keep downloads",
        go = "Remove all",
        working = "Removing…",
        busy = removing != null,
        failure = stopped?.let { (done, total) -> ConfirmFailure("Couldn’t remove all of them", "$done of $total were removed. Try again to remove the rest.") },
        onKeep = onClose,
        onGo = {
            removing = count
            onRemove()
        },
    )
}

private const val REMOVE_WAIT_MS = 5_000L
private const val DIALOG_QUALITY = "download-quality"
private const val DIALOG_REMOVE_ALL = "remove-all"
