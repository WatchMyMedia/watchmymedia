package media.watchmy.client.tv.settings

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import media.watchmy.client.model.DIAGNOSTICS_NOTE
import media.watchmy.client.model.Diagnostics
import media.watchmy.client.model.NO_EXITS
import media.watchmy.client.model.ProcessExit
import media.watchmy.client.model.detail
import media.watchmy.client.model.diagnosticsText
import media.watchmy.client.model.headline
import media.watchmy.client.model.whenLabel
import media.watchmy.client.tv.components.InitialFocus
import media.watchmy.client.tv.components.TvButton
import media.watchmy.client.tv.components.TvButtonStyle
import media.watchmy.client.tv.components.TvDialog
import media.watchmy.client.tv.components.inter
import media.watchmy.client.ui.WmmColors

/**
 * Settings › About › Copy diagnostics:
 * the last times the app closed and why, as Android recorded them, and
 * Copy, which puts them on the TV's clipboard as plain text. Nothing is
 * sent anywhere. [app] is the app, its version, Android and the device.
 */
@Composable
internal fun DiagnosticsPanel(diagnostics: Diagnostics, app: String, onDismiss: () -> Unit, now: () -> Long = System::currentTimeMillis) {
    var exits by remember { mutableStateOf<List<ProcessExit>?>(null) }
    var copied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val copy = remember { FocusRequester() }
    LaunchedEffect(diagnostics) { exits = runCatching { diagnostics.pastExits() }.getOrDefault(emptyList()) }
    TvDialog(onDismiss) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.CenterEnd) {
            Column(
                Modifier
                    .width(760.dp)
                    .fillMaxHeight()
                    .background(WmmColors.Surface)
                    .border(1.dp, WmmColors.Line)
                    .padding(start = 48.dp, end = 48.dp, top = 64.dp, bottom = 44.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Text("Diagnostics", style = inter(30.sp, FontWeight.SemiBold), color = WmmColors.Text)
                Text(
                    "The last times WatchMyMedia closed, and why, as Android recorded them. Copying puts this on the TV’s clipboard as plain text. Nothing is sent anywhere.",
                    style = inter(18.sp, lineHeight = 26.sp),
                    color = WmmColors.TextMuted,
                )
                Text(app, style = inter(17.sp), color = WmmColors.TextFaint)
                val list = exits
                when {
                    list == null -> Text("Reading…", style = inter(19.sp), color = WmmColors.TextMuted)
                    list.isEmpty() -> Text(NO_EXITS, style = inter(19.sp, lineHeight = 27.sp), color = WmmColors.TextSoft)
                    else -> for (exit in list) {
                        Column(Modifier.padding(vertical = 2.dp)) {
                            Text(exit.headline(), style = inter(20.sp, FontWeight.SemiBold), color = WmmColors.Text)
                            Text("${exit.whenLabel(now())} · ${exit.detail()}", style = inter(17.sp), color = WmmColors.TextMuted)
                        }
                    }
                }
                Text(DIAGNOSTICS_NOTE, style = inter(17.sp, lineHeight = 24.sp), color = WmmColors.TextFaint)
                Box(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    TvButton(
                        if (copied) "Copied" else "Copy diagnostics",
                        {
                            val text = diagnosticsText(app, exits.orEmpty(), now())
                            scope.launch {
                                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("WatchMyMedia diagnostics", text)))
                                copied = true
                            }
                        },
                        Modifier.focusRequester(copy),
                        style = if (copied) TvButtonStyle.Ghost else TvButtonStyle.Primary,
                    )
                    if (copied) {
                        Text("On the clipboard as plain text", style = inter(18.sp), color = WmmColors.TextSoft, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    }
                }
                Text("Back closes this panel.", style = inter(17.sp), color = WmmColors.TextFaint)
            }
        }
        InitialFocus(copy)
    }
}
