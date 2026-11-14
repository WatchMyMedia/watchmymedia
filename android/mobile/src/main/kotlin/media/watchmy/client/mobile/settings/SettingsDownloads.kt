package media.watchmy.client.mobile.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import media.watchmy.client.mobile.components.GoldTextButton
import media.watchmy.client.mobile.components.ListRow
import media.watchmy.client.mobile.components.RowValue
import media.watchmy.client.mobile.downloads.DangerText
import media.watchmy.client.mobile.downloads.usedSize
import media.watchmy.client.model.DownloadQuality
import media.watchmy.client.model.freeSize
import media.watchmy.client.model.plural
import media.watchmy.client.presentation.downloads.DownloadsViewModel
import media.watchmy.client.presentation.value
import media.watchmy.client.ui.Inter
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.WmmIcons

/**
 * Settings › Downloads (SettingsDownloads.dc.html): the quality new downloads
 * are made at, Wi-Fi only, the space they take (to Downloads), and Remove
 * all downloads.
 */
@Composable
internal fun DownloadSettings(
    downloads: DownloadsViewModel.State,
    device: String,
    onQuality: () -> Unit,
    onWifiOnly: (Boolean) -> Unit,
    onSpace: () -> Unit,
    onRemoveAll: () -> Unit,
) = Column {
    Section("Downloads")
    ListRow(
        "Quality",
        subtitle = "For new downloads; each can pick another",
        modifier = Modifier.clickable(onClick = onQuality),
        trailing = { RowValue(downloads.settings.quality.short) },
    )
    val wifiOnly = downloads.settings.wifiOnly
    ListRow(
        "Wi-Fi only",
        subtitle = if (wifiOnly) "Downloads wait for Wi-Fi" else "Downloads use mobile data too",
        modifier = Modifier.toggleable(value = wifiOnly, role = Role.Switch, onValueChange = onWifiOnly),
        trailing = { Switch(checked = wifiOnly, onCheckedChange = null, colors = SwitchColors) },
    )
    val held = downloads.all.size
    ListRow(
        "Space used",
        subtitle = if (held == 0) "${freeSize(downloads.storage.freeBytes)} free on this $device" else "${plural(held, "download")} · ${freeSize(downloads.storage.freeBytes)} free",
        modifier = Modifier.clickable(onClick = onSpace),
        trailing = { RowValue(usedSize(downloads.usedBytes)) },
    )
    if (held > 0) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onRemoveAll).padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(WmmIcons.Trash, contentDescription = null, tint = DangerText, modifier = Modifier.size(22.dp))
            Text("Remove all downloads", style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 16.sp, color = DangerText))
        }
    }
}

/** Download quality (SettingsDownloads.dc.html, dialog quality): Original, said in full, then the steps. */
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

/** Remove all downloads? (SettingsDownloads.dc.html, dialog removeAll). */
@Composable
internal fun RemoveAllDialog(downloads: DownloadsViewModel.State, device: String, onDismiss: () -> Unit, onRemove: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = WmmColors.Surface2,
        title = { Text("Remove all downloads?") },
        text = {
            Text(
                "${plural(downloads.all.size, "download")}, ${usedSize(downloads.usedBytes)}, are deleted from this $device. You can download them again.",
                style = TextStyle(fontFamily = Inter, fontSize = 14.sp, lineHeight = 21.sp, color = WmmColors.TextSoft),
            )
        },
        confirmButton = {
            Button(onClick = onRemove, shape = CircleShape, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB42318), contentColor = Color.White)) {
                Text("Remove all", style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp))
            }
        },
        dismissButton = { GoldTextButton("Cancel", onDismiss, color = WmmColors.TextSoft) },
    )
}
