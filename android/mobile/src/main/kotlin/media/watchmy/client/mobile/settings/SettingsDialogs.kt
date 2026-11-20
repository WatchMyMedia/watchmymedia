package media.watchmy.client.mobile.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import media.watchmy.client.mobile.components.FieldText
import media.watchmy.client.mobile.components.GoldTextButton
import media.watchmy.client.mobile.components.fieldColors
import media.watchmy.client.model.StreamQuality
import media.watchmy.client.presentation.value
import media.watchmy.client.ui.Inter
import media.watchmy.client.ui.WmmColors

@Composable
internal fun RenameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = WmmColors.Surface2,
        title = { Text("Device name") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("The name people see in Signed-in devices and in the server's Activity.", style = TextStyle(fontFamily = Inter, fontSize = 14.sp, color = WmmColors.TextMuted))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(60) },
                    singleLine = true,
                    textStyle = FieldText,
                    colors = fieldColors(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) onSave(name) }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { GoldTextButton("Save", { onSave(name) }, enabled = name.isNotBlank()) },
        dismissButton = { GoldTextButton("Cancel", onDismiss, color = WmmColors.TextSoft) },
    )
}

/** Default quality: Automatic first (where the server can do it), with what it does under it. */
@Composable
internal fun QualityDialog(current: StreamQuality, options: List<StreamQuality>, onDismiss: () -> Unit, onPick: (StreamQuality) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = WmmColors.Surface2,
        title = { Text("Default quality") },
        text = {
            // Scrolls where the dialog is shorter than its choices (a phone held sideways).
            Column(Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { q ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = q == current, role = Role.RadioButton, onClick = { onPick(q) }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = q == current, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = WmmColors.Accent, unselectedColor = WmmColors.TextMuted))
                        Column(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp)) {
                            Text(q.label, style = TextStyle(fontFamily = Inter, fontSize = 15.sp, color = WmmColors.Text))
                            if (q == StreamQuality.Auto) {
                                Text(AUTO_DETAIL, style = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = WmmColors.TextMuted))
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
 * On mobile data: Same as on Wi-Fi first, with the Default
 * quality under it, then the qualities as Default quality lists them.
 */
@Composable
internal fun MobileQualityDialog(
    current: StreamQuality?,
    wifi: StreamQuality,
    options: List<StreamQuality>,
    device: String,
    onDismiss: () -> Unit,
    onPick: (StreamQuality?) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = WmmColors.Surface2,
        title = { Text("On mobile data") },
        text = {
            Column {
                Text(
                    "Used when this $device isn’t on Wi-Fi.",
                    style = TextStyle(fontFamily = Inter, fontSize = 13.sp, lineHeight = 19.sp, color = WmmColors.TextMuted),
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
                    (listOf(null) + options).forEach { q ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = q == current, role = Role.RadioButton, onClick = { onPick(q) }),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = q == current, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = WmmColors.Accent, unselectedColor = WmmColors.TextMuted))
                            Column(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp)) {
                                Text(q?.label ?: SAME_AS_WIFI, style = TextStyle(fontFamily = Inter, fontSize = 15.sp, color = WmmColors.Text))
                                val detail = when (q) {
                                    null -> "Follows Default quality · ${wifi.label}"
                                    StreamQuality.Auto -> AUTO_DETAIL
                                    else -> null
                                }
                                if (detail != null) Text(detail, style = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = WmmColors.TextMuted))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { GoldTextButton("Close", onDismiss, color = WmmColors.TextSoft) },
    )
}

@Composable
internal fun LicencesDialog(onDismiss: () -> Unit) {
    val entries = listOf(
        "Inter" to "SIL Open Font License 1.1",
        "AndroidX, Jetpack Compose, Material 3, Media3" to "Apache License 2.0",
        "Kotlin, kotlinx.coroutines, kotlinx.serialization" to "Apache License 2.0",
        "Coil" to "Apache License 2.0",
        "OkHttp" to "Apache License 2.0",
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = WmmColors.Surface2,
        title = { Text("Open-source licences") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                entries.forEach { (name, licence) ->
                    Column {
                        Text(name, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = WmmColors.Text))
                        Text(licence, style = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = WmmColors.TextMuted))
                    }
                }
            }
        },
        confirmButton = { GoldTextButton("Close", onDismiss) },
    )
}
