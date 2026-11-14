package media.watchmy.client.tv.detail

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import media.watchmy.client.model.CANT_DELETE_NOTE
import media.watchmy.client.model.DELETE_HISTORY_NOTE
import media.watchmy.client.model.EpisodeDetail
import media.watchmy.client.model.ShowDetail
import media.watchmy.client.model.deleteEpisodeTitle
import media.watchmy.client.model.deleteEpisodeWhat
import media.watchmy.client.model.deleteWhere
import media.watchmy.client.model.episodeKicker
import media.watchmy.client.model.formatBytes
import media.watchmy.client.model.otherEpisodesLine
import media.watchmy.client.model.unreachableLines
import media.watchmy.client.presentation.detail.ShowPageViewModel
import media.watchmy.client.tv.components.FocusableBox
import media.watchmy.client.tv.components.InitialFocus
import media.watchmy.client.tv.components.TextBright
import media.watchmy.client.tv.components.TvButton
import media.watchmy.client.tv.components.TvButtonSize
import media.watchmy.client.tv.components.TvButtonStyle
import media.watchmy.client.tv.components.TvDialog
import media.watchmy.client.tv.components.inter
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.WmmIcons

/** One of More's rows beyond the episode's own (the movie page has none). */
internal class MoreItem(val label: String, val icon: ImageVector, val onClick: () -> Unit)

/**
 * More (`aside`): what the viewer may do with the episode or movie, then
 * [showItems] under [showTitle] (the episode page's show group), Delete
 * episode… last in red. Back closes it.
 */
@Composable
internal fun MorePanel(
    kicker: String,
    title: String,
    onReport: (() -> Unit)?,
    onReplace: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
    showTitle: String? = null,
    showItems: List<MoreItem> = emptyList(),
) {
    val first = remember { FocusRequester() }
    TvDialog(onDismiss) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.CenterEnd) {
            Column(
                Modifier
                    .width(600.dp)
                    .fillMaxHeight()
                    .background(WmmColors.Surface)
                    .border(1.dp, WmmColors.Line)
                    .padding(start = 40.dp, end = 40.dp, top = 64.dp, bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(32.dp),
            ) {
                Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(kicker.uppercase(), style = inter(17.sp, FontWeight.Bold, tracking = 0.1f), color = WmmColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(title, style = inter(34.sp, FontWeight.ExtraBold, lineHeight = 39.sp, tracking = -0.015f), color = WmmColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    var firstUsed = false
                    fun Modifier.firstOne(): Modifier = if (firstUsed) this else this.focusRequester(first).also { firstUsed = true }
                    onReport?.let { MenuRow("Report a problem", WmmIcons.Flag, it, Modifier.firstOne()) }
                    onReplace?.let { MenuRow("Replace file", WmmIcons.Replace, it, Modifier.firstOne()) }
                    if (showItems.isNotEmpty()) {
                        if (onReport != null || onReplace != null) MenuDivider()
                        showTitle?.let {
                            Text(
                                it.uppercase(),
                                style = inter(15.sp, FontWeight.Bold, tracking = 0.1f),
                                color = WmmColors.TextMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 2.dp),
                            )
                        }
                        showItems.forEach { item -> MenuRow(item.label, item.icon, item.onClick, Modifier.firstOne()) }
                    }
                    onDelete?.let {
                        if (onReport != null || onReplace != null || showItems.isNotEmpty()) MenuDivider()
                        MenuRow("Delete episode…", WmmIcons.Trash, it, Modifier.firstOne(), danger = true)
                    }
                }
                Box(Modifier.weight(1f))
                Text("Back closes this panel.", style = inter(17.sp), color = WmmColors.TextFaint, modifier = Modifier.padding(horizontal = 20.dp))
            }
        }
        InitialFocus(first)
    }
}

@Composable
private fun MenuDivider() {
    Box(Modifier.padding(horizontal = 20.dp, vertical = 4.dp).fillMaxWidth().height(1.dp).background(WmmColors.Line))
}

@Composable
private fun MenuRow(text: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, danger: Boolean = false) {
    FocusableBox(onClick = onClick, modifier = modifier.fillMaxWidth(), cornerRadius = 12.dp, focusedScale = 1f, ringOffset = (-4).dp) { focused ->
        Row(
            Modifier.fillMaxWidth().height(72.dp).background(if (focused) WmmColors.Surface3 else Color.Transparent, RoundedCornerShape(12.dp)).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Icon(icon, contentDescription = null, tint = if (danger) WmmColors.Error else WmmColors.TextMuted, modifier = Modifier.size(28.dp))
            Text(text, style = inter(22.sp, FontWeight.SemiBold), color = if (danger) WmmColors.Error else WmmColors.Text)
        }
    }
}

/**
 * Delete episode (EpisodeDelete.dc.html): what Sonarr will do, as
 * previewDelete says, with Cancel focused and Delete episode beside it; or,
 * for a file no Radarr or Sonarr manages, that WatchMyMedia can't, with OK.
 */
@Composable
internal fun DeleteDialog(show: ShowDetail, ep: EpisodeDetail, step: ShowPageViewModel.Deleting, onDelete: () -> Unit, onDismiss: () -> Unit, onRecordAgain: (Boolean) -> Unit = {}) {
    val confirm = step as? ShowPageViewModel.Deleting.Confirm
    val cant = step == ShowPageViewModel.Deleting.Cant
    val v = ep.versions.firstOrNull()
    val size = confirm?.preview?.sizeBytes?.takeIf { it > 0 } ?: v?.sizeBytes
    ConfirmDialog(
        title = deleteEpisodeTitle(ep.title),
        kicker = episodeKicker(show.title, ep.season, ep.number),
        file = v?.let { it.label to formatBytes(size ?: it.sizeBytes) },
        confirm = if (cant) null else "Delete episode",
        confirmIcon = WmmIcons.Trash,
        confirmEnabled = confirm != null && !confirm.busy && (confirm.preview.recording || confirm.preview.reachable.isNotEmpty()),
        dismiss = if (cant) "OK" else "Cancel",
        busy = confirm?.busy == true,
        onConfirm = onDelete,
        onDismiss = onDismiss,
    ) {
        when (step) {
            ShowPageViewModel.Deleting.Checking -> CircularProgressIndicator(color = WmmColors.Accent, trackColor = WmmColors.Line2, strokeWidth = 4.dp, modifier = Modifier.size(40.dp))
            ShowPageViewModel.Deleting.Cant -> Body(CANT_DELETE_NOTE)
            is ShowPageViewModel.Deleting.Confirm -> {
                val apps = step.preview.reachable
                if (step.preview.recording) {
                    Body("WatchMyMedia will delete the files it recorded. This can’t be undone.")
                    TvButton(if (step.recordAgain) "☐ Don’t record it again" else "☑ Don’t record it again", { onRecordAgain(!step.recordAgain) }, enabled = !step.busy, size = TvButtonSize.Medium)
                } else Body(listOf(deleteEpisodeWhat(apps), deleteWhere(apps)).filter { it.isNotEmpty() }.joinToString(" "))
                otherEpisodesLine(apps)?.let { Body(it) }
                unreachableLines(step.preview.apps).forEach { Body(it, WmmColors.Warn) }
                Body(DELETE_HISTORY_NOTE, WmmColors.TextSoft)
            }
        }
    }
}

/**
 * A centred confirmation over the page: [title], [kicker] and the [file],
 * then [body] and the buttons, focus starting on [dismiss] (Cancel, or OK
 * when there is nothing to confirm).
 */
@Composable
internal fun ConfirmDialog(
    title: String,
    kicker: String,
    file: Pair<String, String>?,
    confirm: String?,
    confirmIcon: ImageVector,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmEnabled: Boolean = true,
    dismiss: String = "Cancel",
    busy: Boolean = false,
    body: @Composable () -> Unit,
) {
    val cancel = remember { FocusRequester() }
    TvDialog(onDismiss = { if (!busy) onDismiss() }) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.66f)), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .width(1000.dp)
                    .background(WmmColors.Surface2, RoundedCornerShape(18.dp))
                    .border(1.dp, WmmColors.Line2, RoundedCornerShape(18.dp))
                    .padding(start = 56.dp, end = 56.dp, top = 48.dp, bottom = 52.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title, style = inter(44.sp, FontWeight.ExtraBold, lineHeight = 48.sp, tracking = -0.02f), color = WmmColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(kicker, style = inter(21.sp), color = WmmColors.TextMuted)
                }
                if (file != null) {
                    Row(
                        Modifier.fillMaxWidth().background(WmmColors.Ground, RoundedCornerShape(12.dp)).border(1.dp, WmmColors.Line2, RoundedCornerShape(12.dp)).padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Icon(WmmIcons.File, contentDescription = null, tint = WmmColors.TextMuted, modifier = Modifier.size(24.dp))
                        Text(file.first, style = inter(19.sp).copy(fontFamily = FontFamily.Monospace), color = WmmColors.TextSoft, modifier = Modifier.weight(1f))
                        Text(file.second, style = inter(19.sp), color = WmmColors.TextMuted)
                    }
                }
                body()
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    TvButton(dismiss, { if (!busy) onDismiss() }, Modifier.focusRequester(cancel), size = TvButtonSize.Large)
                    if (confirm != null) TvButton(confirm, onConfirm, style = TvButtonStyle.Danger, size = TvButtonSize.Large, icon = confirmIcon, enabled = confirmEnabled)
                }
            }
        }
        InitialFocus(cancel)
    }
}

@Composable
internal fun Body(text: String, color: Color = TextBright) {
    Text(text, style = inter(22.sp, lineHeight = 33.sp), color = color)
}
