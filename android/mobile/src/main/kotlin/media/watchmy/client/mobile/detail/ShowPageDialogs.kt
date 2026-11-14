package media.watchmy.client.mobile.detail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import media.watchmy.client.mobile.components.GoldTextButton
import media.watchmy.client.model.CANT_DELETE_NOTE
import media.watchmy.client.model.DELETE_HISTORY_NOTE
import media.watchmy.client.model.EpisodeDetail
import media.watchmy.client.model.REPLACE_TITLE
import media.watchmy.client.model.ShowDetail
import media.watchmy.client.model.deleteEpisodeTitle
import media.watchmy.client.model.deleteEpisodeWhat
import media.watchmy.client.model.deleteWhere
import media.watchmy.client.model.episodeKicker
import media.watchmy.client.model.formatBytes
import media.watchmy.client.model.otherEpisodesLine
import media.watchmy.client.model.replaceBody
import media.watchmy.client.model.unreachableLines
import media.watchmy.client.presentation.detail.ShowPageViewModel
import media.watchmy.client.ui.Inter
import media.watchmy.client.ui.WmmColors

/**
 * Delete episode (EpisodeMenu.dc.html, confirm and cantDelete): what Sonarr
 * will do, as previewDelete says, with Cancel and Delete episode; or, for a
 * file no Radarr or Sonarr manages, that WatchMyMedia can't, with one OK.
 */
@Composable
internal fun DeleteDialog(show: ShowDetail, ep: EpisodeDetail, step: ShowPageViewModel.Deleting, onDelete: () -> Unit, onDismiss: () -> Unit, onRecordAgain: (Boolean) -> Unit = {}) {
    val confirm = step as? ShowPageViewModel.Deleting.Confirm
    val busy = confirm?.busy == true
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        modifier = DialogWidth,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        containerColor = WmmColors.Surface2,
        shape = RoundedCornerShape(28.dp),
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(deleteEpisodeTitle(ep.title), style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 24.sp, lineHeight = 30.sp, color = WmmColors.Text))
                Text(episodeKicker(show.title, ep.season, ep.number), style = TextStyle(fontFamily = Inter, fontSize = 14.sp, color = WmmColors.TextMuted))
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                when (step) {
                    ShowPageViewModel.Deleting.Checking -> Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(28.dp), color = WmmColors.Accent, strokeWidth = 3.dp)
                    }
                    ShowPageViewModel.Deleting.Cant -> DialogBody(CANT_DELETE_NOTE)
                    is ShowPageViewModel.Deleting.Confirm -> {
                        val apps = step.preview.reachable
                        ep.versions.firstOrNull()?.let { v -> FileLine("${v.label} · ${formatBytes(step.preview.sizeBytes.takeIf { it > 0 } ?: v.sizeBytes)}") }
                        if (step.preview.recording) {
                            DialogBody("WatchMyMedia will delete the files it recorded. This can’t be undone.")
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.material3.Checkbox(checked = !step.recordAgain, onCheckedChange = { onRecordAgain(!it) }, enabled = !step.busy)
                                Text("Don’t record it again", color = WmmColors.TextSoft)
                            }
                        } else DialogBody(listOf(deleteEpisodeWhat(apps), deleteWhere(apps)).filter { it.isNotEmpty() }.joinToString(" "))
                        otherEpisodesLine(apps)?.let { DialogBody(it) }
                        unreachableLines(step.preview.apps).forEach { DialogBody(it, WmmColors.Warn) }
                        DialogBody(DELETE_HISTORY_NOTE, WmmColors.TextMuted)
                    }
                }
            }
        },
        confirmButton = {
            when (step) {
                ShowPageViewModel.Deleting.Cant -> GoldTextButton("OK", onDismiss)
                else -> DangerButton("Delete episode", onDelete, enabled = confirm != null && (confirm.preview.recording || confirm.preview.reachable.isNotEmpty()), busy = busy)
            }
        },
        dismissButton = if (step == ShowPageViewModel.Deleting.Cant) null else ({ GoldTextButton("Cancel", onDismiss, enabled = !busy, color = WmmColors.TextSoft) }),
    )
}

/** Replace file, admins only: Sonarr blocks the release and searches again. */
@Composable
internal fun ReplaceDialog(app: String, file: String?, onReplace: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = DialogWidth,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        containerColor = WmmColors.Surface2,
        shape = RoundedCornerShape(28.dp),
        title = { Text(REPLACE_TITLE, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 24.sp, lineHeight = 30.sp, color = WmmColors.Text)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                DialogBody(replaceBody(app))
                file?.let { FileLine(it) }
            }
        },
        confirmButton = { DangerButton("Replace file", onReplace) },
        dismissButton = { GoldTextButton("Cancel", onDismiss, color = WmmColors.TextSoft) },
    )
}

@Composable
private fun DialogBody(text: String, color: Color = WmmColors.TextSoft) {
    Text(text, style = TextStyle(fontFamily = Inter, fontSize = 14.sp, lineHeight = 21.sp, color = color))
}

/** The file name and size (`.file`), monospaced. */
@Composable
private fun FileLine(text: String) {
    val shape = RoundedCornerShape(8.dp)
    Text(
        text,
        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp, color = WmmColors.TextSoft),
        modifier = Modifier.fillMaxWidth().background(WmmColors.Surface, shape).border(1.dp, WmmColors.Line2, shape).padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

/** The red pill (`.btn.danger`). */
@Composable
private fun DangerButton(text: String, onClick: () -> Unit, enabled: Boolean = true, busy: Boolean = false) {
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        modifier = Modifier.height(40.dp),
        shape = CircleShape,
        border = BorderStroke(1.dp, DangerRim),
        colors = ButtonDefaults.buttonColors(containerColor = Danger, contentColor = Color.White, disabledContainerColor = Danger.copy(alpha = 0.5f), disabledContentColor = Color.White.copy(alpha = 0.6f)),
        contentPadding = PaddingValues(horizontal = 20.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp))
    }
}

/** The board's dialog: the screen's width less 24 dp each side, as wide as M3 lets a dialog be on a tablet. */
private val DialogWidth = Modifier.padding(horizontal = 24.dp).widthIn(max = 560.dp).fillMaxWidth()
