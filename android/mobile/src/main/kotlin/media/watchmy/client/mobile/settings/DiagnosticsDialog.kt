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
import media.watchmy.client.model.ProcessExit
import media.watchmy.client.ui.Inter
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.WmmColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Copy diagnostics (PLAN-ANDROID-REVIEW §6.4, the Diagnostics board): the
 * last times the app's process ended, as Android recorded them, for a bug
 * report. Nothing is sent anywhere; Copy puts the text on the clipboard.
 * No account, server address or titles are in it.
 */

/** The process exit in the board's words: "Crashed", "Closed by Android to free memory". */
internal fun exitHeadline(exit: ProcessExit): String = when (exit.reason) {
    "low memory" -> "Closed by Android to free memory"
    "crash", "native crash" -> "Crashed"
    "anr" -> "Stopped responding"
    "app updated" -> "Updated"
    "stopped by the user", "user stopped" -> "Stopped by you"
    "exited" -> "Closed itself"
    "excessive resource use" -> "Stopped by Android for using too much"
    "permission change" -> "Closed when a permission changed"
    "freezer" -> "Frozen by Android"
    else -> exit.reason.replaceFirstChar { it.uppercase() }
}

/** Where the app was and what Android said: "on screen · java.lang.IllegalStateException". */
internal fun exitDetail(exit: ProcessExit): String = listOfNotNull(
    if (exit.importance in listOf("foreground", "visible")) "on screen" else "in the background",
    exit.description,
    "trace kept".takeIf { exit.hasTrace },
).joinToString(" · ")

/** "Today, 9:41 PM", "Yesterday, 11:02 PM" or "Wed 11 Nov, 8:15 PM". */
internal fun exitWhen(atMs: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val at = Instant.ofEpochMilli(atMs).atZone(zone)
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val time = at.format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))
    return when (at.toLocalDate()) {
        today -> "Today, $time"
        today.minusDays(1) -> "Yesterday, $time"
        else -> at.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.US)) + ", " + time
    }
}

/** What Copy puts on the clipboard: the app and device line, then each exit on its own line. */
internal fun diagnosticsText(about: String, exits: List<ProcessExit>, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    buildString {
        appendLine(about)
        if (exits.isEmpty()) appendLine(NO_EXITS)
        exits.forEach { appendLine("${exitWhen(it.atMs, nowMs, zone)} · ${exitHeadline(it)} · ${exitDetail(it)}") }
    }.trimEnd()

private const val NO_EXITS = "Android hasn’t recorded any exits for this app yet."

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
                            Text(exitHeadline(exit), style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = WmmColors.Text))
                            Text("${exitWhen(exit.atMs, now)} · ${exitDetail(exit)}", style = body.copy(fontSize = 13.sp))
                        }
                    }
                }
                Text("Up to the last five. No account, server address or titles are included.", style = body.copy(fontSize = 12.sp, color = WmmColors.TextFaint))
            }
        },
        dismissButton = { GoldTextButton("Close", onDismiss) },
        confirmButton = {
            val copy = {
                clipboard.setText(AnnotatedString(diagnosticsText(about, exits.orEmpty(), now)))
                copied = true
            }
            if (copied) TonalButton("Copied", copy) else FilledButton("Copy", copy)
        },
    )
}
