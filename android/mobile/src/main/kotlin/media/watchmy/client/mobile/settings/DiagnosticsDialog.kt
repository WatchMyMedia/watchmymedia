package media.watchmy.client.mobile.settings

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import media.watchmy.client.mobile.components.FilledButton
import media.watchmy.client.mobile.components.GoldTextButton
import media.watchmy.client.mobile.components.TonalButton
import media.watchmy.client.model.DIAGNOSTICS_NOTE
import media.watchmy.client.model.NO_EXITS
import media.watchmy.client.model.ProcessExit
import media.watchmy.client.model.STOPPED_BY_YOU
import media.watchmy.client.model.detail
import media.watchmy.client.model.diagnosticsText
import media.watchmy.client.model.headline
import media.watchmy.client.model.whenLabel
import media.watchmy.client.ui.Inter
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.WmmColors

/*
 * Copy diagnostics (PLAN-ANDROID-REVIEW §6.4, the Diagnostics board): the
 * last times the app's process ended, as Android recorded them, for a bug
 * report. Nothing is sent anywhere; Copy puts the text on the clipboard.
 * The words are the TV's too (core:model DiagnosticsWords), but for a force
 * stop, which the phone board words as the person's own: [STOPPED_BY_YOU].
 */

@Composable
internal fun DiagnosticsDialog(onDismiss: () -> Unit) {
    val services = LocalAppServices.current
    val about = "WatchMyMedia ${services.client.version} · Android ${Build.VERSION.RELEASE}" + services.client.model.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
    var exits by remember { mutableStateOf<List<ProcessExit>?>(null) }
    LaunchedEffect(services) { exits = services.diagnostics.pastExits() }
    var copied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val now = remember { System.currentTimeMillis() }
    val body = TextStyle(fontFamily = Inter, fontSize = 14.sp, lineHeight = 20.sp, color = WmmColors.TextMuted)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = WmmColors.Surface2,
        title = { Text("Diagnostics") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("The last times WatchMyMedia closed, and why, as Android recorded them. Copy puts this on the clipboard as plain text; nothing is sent anywhere.", style = body)
                Text(about, style = body.copy(fontSize = 13.sp, color = WmmColors.Text))
                val list = exits
                when {
                    list == null -> Unit
                    list.isEmpty() -> Text(NO_EXITS, style = body)
                    else -> list.forEach { exit ->
                        Column {
                            Text(exit.headline(STOPPED_BY_YOU), style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = WmmColors.Text))
                            Text("${exit.whenLabel(now)} · ${exit.detail()}", style = body.copy(fontSize = 13.sp))
                        }
                    }
                }
                Text(DIAGNOSTICS_NOTE, style = body.copy(fontSize = 12.sp, color = WmmColors.TextFaint))
            }
        },
        dismissButton = { GoldTextButton("Close", onDismiss) },
        confirmButton = {
            val copy = {
                clipboard.setText(AnnotatedString(diagnosticsText(about, exits.orEmpty(), now, stoppedByUser = STOPPED_BY_YOU)))
                copied = true
            }
            if (copied) TonalButton("Copied", copy) else FilledButton("Copy", copy)
        },
    )
}
