package media.watchmy.client.mobile.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import media.watchmy.client.mobile.components.GoldTextButton
import media.watchmy.client.ui.Inter
import media.watchmy.client.ui.WmmColors

/** A line of a confirmation: what [label] ("Stays", "Deleted from this phone") covers, or a plain sentence. */
@Immutable
internal data class ConfirmLine(val text: String, val label: String? = null)

/** Why the remove didn't happen, for the dialog's failed state. */
@Immutable
internal data class ConfirmFailure(val title: String, val body: String)

/**
 * A remove that asks first (PLAN-ANDROID-SETTINGS.md §4.6, the
 * SettingsConfirm board): the action and the thing as its title, what stays
 * and what is deleted, and two buttons that read alone. While it works both
 * buttons are off and it can't be put away; if it fails it says nothing was
 * lost and its button tries again.
 */
@Composable
internal fun ConfirmDialog(
    title: String,
    lines: List<ConfirmLine>,
    keep: String,
    go: String,
    working: String,
    busy: Boolean,
    failure: ConfirmFailure?,
    onKeep: () -> Unit,
    onGo: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onKeep() },
        properties = DialogProperties(dismissOnBackPress = !busy, dismissOnClickOutside = !busy),
        containerColor = WmmColors.Surface2,
        title = { Text(title, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 24.sp, lineHeight = 30.sp, color = WmmColors.Text)) },
        text = {
            // Scrolls at the largest font and held sideways, keeping the buttons in view (§8).
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                lines.forEach { line ->
                    Column {
                        if (line.label != null) {
                            Text(
                                line.label.uppercase(),
                                style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 0.88.sp, color = WmmColors.TextMuted),
                                modifier = Modifier.padding(bottom = 2.dp).semantics { heading() },
                            )
                        }
                        Text(line.text, style = TextStyle(fontFamily = Inter, fontSize = 14.sp, lineHeight = 21.sp, color = WmmColors.TextSoft))
                    }
                }
                if (failure != null) StatusCard(failure.title, failure.body, NoteTone.Error)
            }
        },
        dismissButton = { GoldTextButton(keep, onKeep, enabled = !busy, color = WmmColors.TextSoft) },
        confirmButton = {
            Button(
                onClick = onGo,
                enabled = !busy,
                shape = CircleShape,
                border = BorderStroke(1.dp, DangerRim),
                colors = ButtonDefaults.buttonColors(
                    containerColor = DangerFill, contentColor = Color.White,
                    disabledContainerColor = DangerFill.copy(alpha = 0.45f), disabledContentColor = Color.White.copy(alpha = 0.7f),
                ),
                modifier = Modifier.height(40.dp),
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    when {
                        busy -> working
                        failure != null -> "Try again"
                        else -> go
                    },
                    style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
                )
            }
        },
    )
}

private val DangerFill = Color(0xFFB42318)
private val DangerRim = Color(0xFFE5484D)
