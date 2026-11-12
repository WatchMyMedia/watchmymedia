package media.watchmy.client.mobile.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import media.watchmy.client.mobile.components.GoldTextButton
import media.watchmy.client.mobile.components.ListRow
import media.watchmy.client.mobile.components.RowValue
import media.watchmy.client.mobile.components.SectionLabel
import media.watchmy.client.mobile.components.TonalButton
import media.watchmy.client.model.SubtitleChoice
import media.watchmy.client.model.subtitleLanguageLabel
import media.watchmy.client.model.subtitleLanguageOptions
import media.watchmy.client.presentation.settings.SettingsViewModel
import media.watchmy.client.ui.Inter
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.WmmIcons

/** Which list a language dialog adds to or edits. */
private enum class LanguageList { Audio, Subtitles }

private val Heading = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = WmmColors.Text)
private val Description = TextStyle(fontFamily = Inter, fontSize = 13.sp, lineHeight = 19.sp, color = WmmColors.TextMuted)
private val Foot = TextStyle(fontFamily = Inter, fontSize = 12.sp, lineHeight = 18.sp, color = WmmColors.TextFaint)

/**
 * Settings › Audio & subtitles (PLAN-LANGUAGES.md §5.6): the audio languages,
 * how subtitles start, the subtitle languages and automatic download, each
 * saved as it changes. The phone stacks them and opens the four choices in a
 * dialog (android/SettingsLanguages.dc.html); a tablet sets them two by two
 * in panels and lists the choices in place (android-tablet/TabSettingsLanguages.dc.html).
 * A server before audio languages gets the subtitle sections alone.
 */
@Composable
internal fun LanguageSettings(state: SettingsViewModel.State, vm: SettingsViewModel, tablet: Boolean, modifier: Modifier = Modifier) {
    if (!state.subtitlePreferencesAvailable) return
    var adding by rememberSaveable { mutableStateOf<LanguageList?>(null) }
    var editing by rememberSaveable { mutableStateOf<Pair<LanguageList, String>?>(null) }
    var choosing by rememberSaveable { mutableStateOf(false) }
    val displayLocale = LocalConfiguration.current.locales[0]
    val device = if (LocalConfiguration.current.smallestScreenWidthDp >= 600) "tablet" else "phone"
    val audio = state.audioLanguagesAvailable

    val audioSection: @Composable ColumnScope.() -> Unit = {
        AudioLanguages(state.playback.audio.languages, displayLocale, device, tablet, onAdd = { adding = LanguageList.Audio }, onOptions = { editing = LanguageList.Audio to it }, onUse = vm::addAudioLanguage)
        if (state.showForeignAudioNotice) ForeignAudioNotice(tablet)
    }
    val startSection: @Composable ColumnScope.() -> Unit = {
        StartSubtitles(state.playback.subtitles.choice, state.subtitleChoices, tablet, onOpen = { choosing = true }, onPick = vm::setSubtitleChoice)
    }
    val subtitleSection: @Composable ColumnScope.() -> Unit = {
        SubtitleLanguages(state, displayLocale, tablet, onAdd = { adding = LanguageList.Subtitles }, onOptions = { editing = LanguageList.Subtitles to it }, onUse = vm::addSubtitleLanguage)
    }
    val onlineSection: @Composable ColumnScope.() -> Unit = {
        OnlineSubtitles(state, tablet, vm::setAutomaticSubtitles)
    }

    Column(modifier) {
        SectionLabel(state.languagesTitle, Modifier.padding(top = 16.dp, bottom = 4.dp))
        Text("Your settings follow your account on this server.", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = Description)
        if (tablet) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                PanelRow(if (audio) audioSection else startSection, if (audio) startSection else onlineSection)
                PanelRow(subtitleSection, if (audio) onlineSection else null)
            }
        } else {
            if (audio) Column(content = audioSection)
            Column(content = startSection)
            Column(content = subtitleSection)
            Column(content = onlineSection)
        }
        if (state.subtitleSaving) Text("Saving…", Modifier.padding(16.dp), color = WmmColors.TextMuted)
    }

    adding?.let { list ->
        AddLanguageDialog(
            chosen = if (list == LanguageList.Audio) state.playback.audio.languages else state.playback.subtitles.languages,
            displayLocale = displayLocale,
            onDismiss = { adding = null },
            onPick = { tag ->
                if (list == LanguageList.Audio) vm.addAudioLanguage(tag) else vm.addSubtitleLanguage(tag)
                adding = null
            },
        )
    }
    editing?.let { (list, tag) ->
        val languages = if (list == LanguageList.Audio) state.playback.audio.languages else state.playback.subtitles.languages
        LanguageActionsDialog(
            name = subtitleLanguageLabel(tag, displayLocale),
            index = languages.indexOf(tag),
            last = languages.lastIndex,
            onDismiss = { editing = null },
            onMove = { by ->
                if (list == LanguageList.Audio) vm.moveAudioLanguage(tag, by) else vm.moveSubtitleLanguage(tag, by)
                editing = null
            },
            onRemove = {
                if (list == LanguageList.Audio) vm.removeAudioLanguage(tag) else vm.removeSubtitleLanguage(tag)
                editing = null
            },
        )
    }
    if (choosing && !tablet) ChoiceDialog(state.playback.subtitles.choice, state.subtitleChoices, onDismiss = { choosing = false }, onPick = {
        vm.setSubtitleChoice(it)
        choosing = false
    })
}

/** Two sections side by side on a tablet, tops aligned; the second may be empty. */
@Composable
private fun PanelRow(start: @Composable ColumnScope.() -> Unit, end: (@Composable ColumnScope.() -> Unit)?) {
    Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f), content = start)
        Column(Modifier.weight(1f)) { end?.invoke(this) }
    }
}

/** A section's heading and the line under it; the phone indents them, a tablet's panels sit flush. */
@Composable
private fun ColumnScope.Head(title: String, line: String?, tablet: Boolean, top: Boolean = false) {
    val inset = if (tablet) 0.dp else 16.dp
    Text(title, style = Heading, modifier = Modifier.padding(start = inset, end = inset, top = when { tablet -> 0.dp; top -> 12.dp; else -> 20.dp }, bottom = 2.dp).semantics { heading() })
    if (line != null) Text(line, style = Description, modifier = Modifier.padding(start = inset, end = inset, top = 3.dp, bottom = if (tablet) 10.dp else 6.dp))
}

/** The rows of a section: in a rounded panel on a tablet, straight on the page on a phone. */
@Composable
private fun Rows(tablet: Boolean, content: @Composable ColumnScope.() -> Unit) {
    if (!tablet) Column(content = content) else Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(WmmColors.Surface).border(1.dp, WmmColors.Line, RoundedCornerShape(16.dp)).padding(4.dp),
        content = content,
    )
}

@Composable
private fun Footnote(text: String, tablet: Boolean, color: Color = WmmColors.TextFaint) {
    Text(text, style = Foot.copy(color = color), modifier = Modifier.padding(horizontal = if (tablet) 4.dp else 16.dp, vertical = if (tablet) 8.dp else 4.dp))
}

@Composable
private fun ColumnScope.LanguageRows(languages: List<String>, displayLocale: Locale, tablet: Boolean, onOptions: (String) -> Unit) {
    languages.forEachIndexed { index, tag ->
        ListRow("${index + 1}. ${subtitleLanguageLabel(tag, displayLocale)}", minHeight = if (tablet) 48.dp else 56.dp, modifier = Modifier.clickable(onClickLabel = "Options") { onOptions(tag) }, trailing = { RowValue("Options") })
    }
}

@Composable
private fun AddLanguageRow(tablet: Boolean, onAdd: () -> Unit) {
    ListRow("Add language", minHeight = if (tablet) 48.dp else 56.dp, modifier = Modifier.clickable(onClick = onAdd), trailing = { RowValue("", icon = WmmIcons.Plus) })
}

@Composable
private fun ColumnScope.AudioLanguages(
    languages: List<String>,
    displayLocale: Locale,
    device: String,
    tablet: Boolean,
    onAdd: () -> Unit,
    onOptions: (String) -> Unit,
    onUse: (String) -> Unit,
) {
    Head("Audio languages", "Videos start in the first of these languages they have. If they have none, they play the file’s default.", tablet, top = true)
    Rows(tablet) {
        LanguageRows(languages, displayLocale, tablet, onOptions)
        if (languages.isEmpty()) {
            Text("No audio language yet. Videos play the file’s default audio.", style = Description.copy(fontSize = 14.sp, color = WmmColors.TextSoft), modifier = Modifier.padding(horizontal = if (tablet) 12.dp else 16.dp, vertical = 6.dp))
            // Speech has no script or region to choose: the device's language alone.
            val suggested = displayLocale.language.takeIf { it.isNotBlank() && it != "und" }
            if (suggested != null) {
                val name = subtitleLanguageLabel(suggested, displayLocale)
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = if (tablet) 12.dp else 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Your $device uses $name.", style = Description, modifier = Modifier.weight(1f))
                    TonalButton("Use $name", { onUse(suggested) }, compact = true)
                }
            }
        }
        AddLanguageRow(tablet, onAdd)
    }
    Footnote("Commentary and audio description are never chosen for you. You can pick them in the player.", tablet)
}

/** Said once, after the server turned subtitles for foreign audio on with the first audio languages. */
@Composable
private fun ForeignAudioNotice(tablet: Boolean) {
    Row(
        Modifier
            .padding(start = if (tablet) 0.dp else 16.dp, end = if (tablet) 0.dp else 16.dp, top = if (tablet) 12.dp else 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(WmmColors.Accent.copy(alpha = 0.10f))
            .border(1.dp, WmmColors.Accent.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(WmmIcons.Info, contentDescription = null, tint = WmmColors.Accent, modifier = Modifier.padding(top = 1.dp).size(18.dp))
        Text(FOREIGN_AUDIO_NOTICE, style = TextStyle(fontFamily = Inter, fontSize = 13.sp, lineHeight = 19.sp, color = WmmColors.Text))
    }
}

@Composable
private fun ColumnScope.StartSubtitles(current: SubtitleChoice, choices: List<SubtitleChoice>, tablet: Boolean, onOpen: () -> Unit, onPick: (SubtitleChoice) -> Unit) {
    if (tablet) {
        Head("Start subtitles", "When a video starts", tablet)
        Rows(tablet) {
            Column(Modifier.selectableGroup()) {
                choices.forEach { c -> ChoiceRow(c, c == current, inPanel = true) { onPick(c) } }
            }
        }
    } else {
        Head("Start subtitles", null, tablet)
        ListRow("When a video starts", modifier = Modifier.clickable(onClick = onOpen), trailing = { RowValue(current.shortLabel) })
        Text(current.detail, style = Description, modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 6.dp))
    }
    Footnote("A track you choose in the player always wins.", tablet)
}

/** One of the four ways subtitles start, with its line; a tablet tints the chosen one. */
@Composable
private fun ChoiceRow(choice: SubtitleChoice, selected: Boolean, inPanel: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (inPanel && selected) WmmColors.Accent.copy(alpha = 0.10f) else Color.Transparent)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = if (inPanel) 4.dp else 0.dp, vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = WmmColors.Accent, unselectedColor = WmmColors.TextMuted), modifier = Modifier.padding(top = 2.dp))
        Column(Modifier.padding(start = 8.dp, top = 8.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(choice.label, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp, color = WmmColors.Text))
            Text(choice.detail, style = TextStyle(fontFamily = Inter, fontSize = if (inPanel) 12.sp else 13.sp, lineHeight = 18.sp, color = WmmColors.TextMuted))
        }
    }
}

@Composable
private fun ColumnScope.SubtitleLanguages(
    state: SettingsViewModel.State,
    displayLocale: Locale,
    tablet: Boolean,
    onAdd: () -> Unit,
    onOptions: (String) -> Unit,
    onUse: (String) -> Unit,
) {
    val languages = state.playback.subtitles.languages
    Head("Subtitle languages", "Use the first of these languages that has a subtitle.", tablet)
    if (state.subtitlesFollowAudio && state.audioLanguagesAvailable) {
        Text("Until you add one, subtitles use your audio languages.", style = Description, modifier = Modifier.padding(horizontal = if (tablet) 0.dp else 16.dp).padding(bottom = 10.dp))
    }
    Rows(tablet) {
        LanguageRows(languages, displayLocale, tablet, onOptions)
        if (languages.isEmpty()) {
            val tag = displayLocale.toLanguageTag()
            if (tag != "und") ListRow("Use device language", minHeight = if (tablet) 48.dp else 56.dp, modifier = Modifier.clickable { onUse(tag) }, trailing = { RowValue(subtitleLanguageLabel(tag, displayLocale)) })
        }
        AddLanguageRow(tablet, onAdd)
    }
}

@Composable
private fun ColumnScope.OnlineSubtitles(state: SettingsViewModel.State, tablet: Boolean, onChange: (Boolean) -> Unit) {
    val prefs = state.playback.subtitles
    val available = prefs.automaticAvailable
    val on = available && prefs.automaticDownload
    Head("Online subtitles", null, tablet)
    Rows(tablet) {
        ListRow(
            "Automatic download",
            modifier = Modifier.toggleable(on, enabled = available, role = Role.Switch, onValueChange = onChange),
            trailing = {
                Switch(
                    on, onCheckedChange = null, enabled = available,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = WmmColors.Ground, checkedTrackColor = WmmColors.Accent, checkedBorderColor = WmmColors.Accent,
                        uncheckedThumbColor = WmmColors.TextMuted, uncheckedTrackColor = WmmColors.Surface3, uncheckedBorderColor = WmmColors.FieldOutline,
                    ),
                )
            },
        )
    }
    Footnote("When subtitles would start and no local track matches, try one exact-file match from OpenSubtitles.com after the video starts. Uses the server’s download allowance.", tablet)
    if (!available) Footnote(state.automaticBlockedNote, tablet, color = WmmColors.TextSoft)
}

/** The phone's Start subtitles dialog: the four choices, each with its line (android/SettingsLanguages.dc.html, sheet "choice"). */
@Composable
private fun ChoiceDialog(current: SubtitleChoice, choices: List<SubtitleChoice>, onDismiss: () -> Unit, onPick: (SubtitleChoice) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = WmmColors.Surface2,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Start subtitles")
                Text("When a video starts", style = Description)
            }
        },
        text = {
            // Scrolls where the dialog is shorter than its choices (a phone held sideways).
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Column(Modifier.selectableGroup()) {
                    choices.forEach { c -> ChoiceRow(c, c == current, inPanel = false) { onPick(c) } }
                }
                Text("A track you choose in the player always wins.", style = Foot, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { GoldTextButton("Close", onDismiss, color = WmmColors.TextSoft) },
    )
}

@Composable
private fun AddLanguageDialog(chosen: List<String>, displayLocale: Locale, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val languages = remember(displayLocale) { subtitleLanguageOptions(displayLocale) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add language") },
        text = {
            Column {
                OutlinedTextField(query, onValueChange = { query = it }, label = { Text("Find a language") }, singleLine = true)
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(languages.filter { choice -> choice.tag !in chosen && (choice.label.contains(query, true) || choice.tag.contains(query, true)) }, key = { it.tag }) { choice ->
                        ListRow(choice.label, modifier = Modifier.clickable { onPick(choice.tag) })
                    }
                }
            }
        },
        confirmButton = { GoldTextButton("Cancel", onDismiss) },
    )
}

@Composable
private fun LanguageActionsDialog(name: String, index: Int, last: Int, onDismiss: () -> Unit, onMove: (Int) -> Unit, onRemove: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(name) },
        text = {
            Column {
                if (index > 0) ListRow("Move up", modifier = Modifier.clickable { onMove(-1) })
                if (index in 0 until last) ListRow("Move down", modifier = Modifier.clickable { onMove(1) })
                ListRow("Remove language", modifier = Modifier.clickable(onClick = onRemove))
            }
        },
        confirmButton = { GoldTextButton("Cancel", onDismiss) },
    )
}

private const val FOREIGN_AUDIO_NOTICE = "Subtitles now start when the audio isn’t in your languages. You can change this under Start subtitles."
