package media.watchmy.client.mobile.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale
import media.watchmy.client.mobile.components.ChoiceSegments
import media.watchmy.client.mobile.components.GoldTextButton
import media.watchmy.client.mobile.components.ListRow
import media.watchmy.client.mobile.components.RowValue
import media.watchmy.client.mobile.components.TonalButton
import media.watchmy.client.model.ON_EVERY_DEVICE
import media.watchmy.client.model.SaveFailure
import media.watchmy.client.model.SOUND_HEADING
import media.watchmy.client.model.SOUND_NOTE
import media.watchmy.client.model.SUBTITLE_SIZE_FOLLOWS_DEVICE
import media.watchmy.client.model.SUBTITLE_SIZE_PICTURE_NOTE
import media.watchmy.client.model.SoundLevel
import media.watchmy.client.model.SoundSetting
import media.watchmy.client.model.SubtitleChoice
import media.watchmy.client.model.SubtitleSize
import media.watchmy.client.model.listLine
import media.watchmy.client.model.subtitleLanguageLabel
import media.watchmy.client.model.subtitleLanguageOptions
import media.watchmy.client.presentation.settings.SettingKey
import media.watchmy.client.presentation.settings.SettingsViewModel
import media.watchmy.client.ui.Inter
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.WmmIcons
import media.watchmy.client.ui.components.rememberSystemCaptionLook
import media.watchmy.client.presentation.settings.LanguageList

private val Heading = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = WmmColors.Text)
private val Description = TextStyle(fontFamily = Inter, fontSize = 13.sp, lineHeight = 19.sp, color = WmmColors.TextMuted)
private val Foot = TextStyle(fontFamily = Inter, fontSize = 12.sp, lineHeight = 18.sp, color = WmmColors.TextFaint)

/**
 * Settings › Audio & subtitles (its own page):
 * the audio languages, how subtitles start,
 * the subtitle languages and automatic download, each saved as it changes,
 * with Saving… and a failure on the part that changed. The phone stacks
 * them and opens the four choices in a dialog;
 * a tablet sets them two by two in panels and lists the choices in place.
 * A server before audio
 * languages gets the subtitle sections alone. They follow the account, so
 * they are held until its settings have loaded.
 */
@Composable
internal fun LanguageSettings(tablet: Boolean, online: Boolean, modifier: Modifier = Modifier) {
    val vm = settingsModel()
    val state by vm.state.collectAsStateWithLifecycle()
    if (!state.subtitlePreferencesAvailable) return
    var adding by rememberSaveable { mutableStateOf<LanguageList?>(null) }
    var editing by rememberSaveable { mutableStateOf<Pair<LanguageList, String>?>(null) }
    var choosing by rememberSaveable { mutableStateOf(false) }
    val displayLocale = LocalConfiguration.current.locales[0]
    val device = if (LocalConfiguration.current.smallestScreenWidthDp >= 600) "tablet" else "phone"
    val audio = state.audioLanguagesAvailable
    val ready = state.accountReady
    val save = { key: SettingKey -> SaveLine(key in state.saving, state.failed[key]) }

    val audioSection: @Composable ColumnScope.() -> Unit = {
        AudioLanguages(state.playback.audio.languages, displayLocale, device, tablet, ready, save(SettingKey.AudioLanguages), onRetry = { vm.retry(SettingKey.AudioLanguages) }, onAdd = { adding = LanguageList.Audio }, onOptions = { editing = LanguageList.Audio to it }, onUse = vm::addAudioLanguage)
        if (state.showForeignAudioNotice) ForeignAudioNotice(tablet)
    }
    val startSection: @Composable ColumnScope.() -> Unit = {
        StartSubtitles(state.playback.subtitles.choice, state.subtitleChoices, tablet, ready, save(SettingKey.SubtitleChoice), onRetry = { vm.retry(SettingKey.SubtitleChoice) }, onOpen = { choosing = true }, onPick = vm::setSubtitleChoice)
    }
    val subtitleSection: @Composable ColumnScope.() -> Unit = {
        SubtitleLanguages(state, displayLocale, tablet, ready, save(SettingKey.SubtitleLanguages), onRetry = { vm.retry(SettingKey.SubtitleLanguages) }, onAdd = { adding = LanguageList.Subtitles }, onOptions = { editing = LanguageList.Subtitles to it }, onUse = vm::addSubtitleLanguage)
    }
    val onlineSection: @Composable ColumnScope.() -> Unit = {
        OnlineSubtitles(state, tablet, ready, save(SettingKey.AutomaticSubtitles), onRetry = { vm.retry(SettingKey.AutomaticSubtitles) }, vm::setAutomaticSubtitles)
    }
    // Servers that apply the person's sound and keep their subtitle size.
    val sound = state.soundAvailable && audio
    val soundSection: @Composable ColumnScope.() -> Unit = {
        Sound(state.playback.audio.dialogueBoost, state.playback.audio.reduceLoudSounds, tablet, ready, { save(SettingKey.Sound(it)) }, onRetry = { vm.retry(SettingKey.Sound(it)) }, onPick = vm::setSound)
    }
    val sizeSection: @Composable ColumnScope.() -> Unit = {
        SubtitleSizeSection(state.playback.subtitles.size, tablet, ready, save(SettingKey.AccountSubtitleSize), onRetry = { vm.retry(SettingKey.AccountSubtitleSize) }, onPick = vm::setAccountSubtitleSize)
    }

    Column(modifier) {
        AccountLoadCard(state, online, tablet, onRetry = vm::reloadAccount, held = "your audio and subtitle settings can’t be changed yet.")
        PageHeading(ON_EVERY_DEVICE, tablet)
        AccountRows(ready) {
            if (tablet && sound) {
                // Two by two, in the page's order: each language list beside what follows it.
                Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    PanelRow(audioSection, soundSection)
                    PanelRow(startSection, subtitleSection)
                    PanelRow(sizeSection, onlineSection)
                }
            } else if (tablet) {
                Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    PanelRow(if (audio) audioSection else startSection, if (audio) startSection else onlineSection)
                    PanelRow(subtitleSection, if (audio) onlineSection else null)
                }
            } else {
                if (audio) Column(content = audioSection)
                if (sound) Column(content = soundSection)
                Column(content = startSection)
                Column(content = subtitleSection)
                if (sound) Column(content = sizeSection)
                Column(content = onlineSection)
            }
        }
        SettingKey.LANGUAGES.forEach { SavedAnnouncement(state, it) }
    }

    adding?.let { list ->
        AddLanguageDialog(
            chosen = vm.languages(list),
            displayLocale = displayLocale,
            onDismiss = { adding = null },
            onPick = { tag ->
                vm.addLanguage(list, tag)
                adding = null
            },
        )
    }
    editing?.let { (list, tag) ->
        val languages = vm.languages(list)
        LanguageActionsDialog(
            name = subtitleLanguageLabel(tag, displayLocale),
            index = languages.indexOf(tag),
            last = languages.lastIndex,
            onDismiss = { editing = null },
            onMove = { by ->
                vm.moveLanguage(list, tag, by)
                editing = null
            },
            onRemove = {
                vm.removeLanguage(list, tag)
                editing = null
            },
        )
    }
    if (choosing && !tablet) ChoiceDialog(state.playback.subtitles.choice, state.subtitleChoices, onDismiss = { choosing = false }, onPick = {
        vm.setSubtitleChoice(it)
        choosing = false
    })
}

/** A part's save state: Saving… while it goes, and its failure until tried again. */
private data class SaveLine(val saving: Boolean, val failure: SaveFailure?)

/** Saving… under a part's heading while it goes. */
@Composable
private fun Saving(line: SaveLine, tablet: Boolean) {
    if (line.saving) SavingLine(Modifier.padding(start = if (tablet) 0.dp else 16.dp, bottom = 6.dp))
}

/** A list's failure: the list is back as it was. */
@Composable
private fun ListFailure(line: SaveLine, list: LanguageList, onRetry: () -> Unit) {
    line.failure?.let { FailureLine(it.listLine(list.title), onRetry) }
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
private fun ColumnScope.LanguageRows(languages: List<String>, displayLocale: Locale, tablet: Boolean, enabled: Boolean, onOptions: (String) -> Unit) {
    languages.forEachIndexed { index, tag ->
        ListRow("${index + 1}. ${subtitleLanguageLabel(tag, displayLocale)}", minHeight = if (tablet) 48.dp else 56.dp, modifier = Modifier.clickable(enabled = enabled, onClickLabel = "Options") { onOptions(tag) }, trailing = { RowValue("Options") })
    }
}

@Composable
private fun AddLanguageRow(tablet: Boolean, enabled: Boolean, onAdd: () -> Unit) {
    ListRow("Add language", minHeight = if (tablet) 48.dp else 56.dp, modifier = Modifier.clickable(enabled = enabled, onClick = onAdd), trailing = { RowValue("", icon = WmmIcons.Plus) })
}

@Composable
private fun ColumnScope.AudioLanguages(
    languages: List<String>,
    displayLocale: Locale,
    device: String,
    tablet: Boolean,
    enabled: Boolean,
    save: SaveLine,
    onRetry: () -> Unit,
    onAdd: () -> Unit,
    onOptions: (String) -> Unit,
    onUse: (String) -> Unit,
) {
    Head("Audio languages", "Videos start in the first of these languages they have. If they have none, they play the file’s default.", tablet, top = true)
    Saving(save, tablet)
    Rows(tablet) {
        LanguageRows(languages, displayLocale, tablet, enabled, onOptions)
        if (languages.isEmpty()) {
            Text("No audio language yet. Videos play the file’s default audio.", style = Description.copy(fontSize = 14.sp, color = WmmColors.TextSoft), modifier = Modifier.padding(horizontal = if (tablet) 12.dp else 16.dp, vertical = 6.dp))
            // Speech has no script or region to choose: the device's language alone.
            val suggested = displayLocale.language.takeIf { it.isNotBlank() && it != "und" }
            if (suggested != null) {
                val name = subtitleLanguageLabel(suggested, displayLocale)
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = if (tablet) 12.dp else 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Your $device uses $name.", style = Description, modifier = Modifier.weight(1f))
                    TonalButton("Use $name", { onUse(suggested) }, compact = true, enabled = enabled)
                }
            }
        }
        AddLanguageRow(tablet, enabled, onAdd)
    }
    ListFailure(save, LanguageList.Audio, onRetry)
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
private fun ColumnScope.StartSubtitles(
    current: SubtitleChoice,
    choices: List<SubtitleChoice>,
    tablet: Boolean,
    enabled: Boolean,
    save: SaveLine,
    onRetry: () -> Unit,
    onOpen: () -> Unit,
    onPick: (SubtitleChoice) -> Unit,
) {
    if (tablet) {
        Head("Start subtitles", "When a video starts", tablet)
        Saving(save, tablet)
        Rows(tablet) {
            Column(Modifier.selectableGroup()) {
                choices.forEach { c -> ChoiceRow(c, c == current, inPanel = true, enabled = enabled) { onPick(c) } }
            }
        }
    } else {
        Head("Start subtitles", null, tablet)
        ListRow(
            "When a video starts",
            below = if (save.saving) ({ SavingLine() }) else null,
            modifier = Modifier.clickable(enabled = enabled, onClick = onOpen),
            trailing = { RowValue(current.shortLabel) },
        )
        Text(current.detail, style = Description, modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 6.dp))
    }
    RowFailure(save.failure, current.shortLabel, onRetry)
    Footnote("A track you choose in the player always wins.", tablet)
}

/** One of the four ways subtitles start, with its line; a tablet tints the chosen one. */
@Composable
private fun ChoiceRow(choice: SubtitleChoice, selected: Boolean, inPanel: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (inPanel && selected) WmmColors.Accent.copy(alpha = 0.10f) else Color.Transparent)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
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
    enabled: Boolean,
    save: SaveLine,
    onRetry: () -> Unit,
    onAdd: () -> Unit,
    onOptions: (String) -> Unit,
    onUse: (String) -> Unit,
) {
    val languages = state.playback.subtitles.languages
    Head("Subtitle languages", "Use the first of these languages that has a subtitle.", tablet)
    Saving(save, tablet)
    if (state.subtitlesFollowAudio && state.audioLanguagesAvailable) {
        Text("Until you add one, subtitles use your audio languages.", style = Description, modifier = Modifier.padding(horizontal = if (tablet) 0.dp else 16.dp).padding(bottom = 10.dp))
    }
    Rows(tablet) {
        LanguageRows(languages, displayLocale, tablet, enabled, onOptions)
        if (languages.isEmpty()) {
            val tag = displayLocale.toLanguageTag()
            if (tag != "und") ListRow("Use device language", minHeight = if (tablet) 48.dp else 56.dp, modifier = Modifier.clickable(enabled = enabled) { onUse(tag) }, trailing = { RowValue(subtitleLanguageLabel(tag, displayLocale)) })
        }
        AddLanguageRow(tablet, enabled, onAdd)
    }
    ListFailure(save, LanguageList.Subtitles, onRetry)
}

@Composable
private fun ColumnScope.OnlineSubtitles(state: SettingsViewModel.State, tablet: Boolean, enabled: Boolean, save: SaveLine, onRetry: () -> Unit, onChange: (Boolean) -> Unit) {
    val prefs = state.playback.subtitles
    val available = prefs.automaticAvailable
    val on = available && prefs.automaticDownload
    Head("Online subtitles", null, tablet)
    Rows(tablet) {
        ListRow(
            "Automatic download",
            below = if (save.saving) ({ SavingLine() }) else null,
            modifier = Modifier.toggleable(on, enabled = available && enabled, role = Role.Switch, onValueChange = onChange),
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
        RowFailure(save.failure, if (on) "on" else "off", onRetry)
    }
    Footnote("When subtitles would start and no local track matches, try one exact-file match from OpenSubtitles.com after the video starts. Uses the server’s download allowance.", tablet)
    if (!available) Footnote(state.automaticBlockedNote, tablet, color = WmmColors.TextSoft)
}

/**
 * Sound: Boost dialogue and Reduce loud sounds, Off, Medium or High in
 * segmented buttons, each with its line, saved as it changes with Saving…
 * and its failure under it; the note on what they do beneath.
 */
@Composable
private fun ColumnScope.Sound(
    dialogue: SoundLevel,
    loud: SoundLevel,
    tablet: Boolean,
    enabled: Boolean,
    save: (SoundSetting) -> SaveLine,
    onRetry: (SoundSetting) -> Unit,
    onPick: (SoundSetting, SoundLevel) -> Unit,
) {
    Head(SOUND_HEADING, null, tablet)
    if (tablet) Spacer(8)
    Rows(tablet) {
        SoundChoice(SoundSetting.DialogueBoost, dialogue, tablet, enabled, save(SoundSetting.DialogueBoost), onRetry, onPick)
        if (tablet) Box(Modifier.padding(horizontal = 12.dp).fillMaxWidth().height(1.dp).background(WmmColors.Line))
        SoundChoice(SoundSetting.ReduceLoudSounds, loud, tablet, enabled, save(SoundSetting.ReduceLoudSounds), onRetry, onPick)
    }
    Footnote(SOUND_NOTE, tablet)
}

@Composable
private fun SoundChoice(
    setting: SoundSetting,
    level: SoundLevel,
    tablet: Boolean,
    enabled: Boolean,
    save: SaveLine,
    onRetry: (SoundSetting) -> Unit,
    onPick: (SoundSetting, SoundLevel) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = if (tablet) 12.dp else 16.dp, vertical = if (tablet) 10.dp else 8.dp)) {
        Text(setting.label, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = if (tablet) 15.sp else 16.sp, lineHeight = if (tablet) 20.sp else 22.sp, color = WmmColors.Text))
        Text(setting.help, style = Description.copy(fontSize = if (tablet) 12.sp else 13.sp, lineHeight = if (tablet) 17.sp else 19.sp), modifier = Modifier.padding(top = 2.dp))
        ChoiceSegments(
            SoundLevel.entries, level, label = { it.label }, onPick = { onPick(setting, it) }, name = setting.label,
            modifier = Modifier.padding(top = 10.dp), enabled = enabled,
        )
        if (save.saving) SavingLine(Modifier.padding(top = 8.dp))
    }
    RowFailure(save.failure, level.label) { onRetry(setting) }
}

/**
 * Subtitle size: a preview of a cue at the chosen size (on top of the
 * system's caption size, as the players draw it) over the four sizes, saved
 * as it changes.
 */
@Composable
private fun ColumnScope.SubtitleSizeSection(
    size: SubtitleSize,
    tablet: Boolean,
    enabled: Boolean,
    save: SaveLine,
    onRetry: () -> Unit,
    onPick: (SubtitleSize) -> Unit,
) {
    Head("Subtitle size", null, tablet)
    if (tablet) Spacer(8)
    Rows(tablet) {
        Column(Modifier.fillMaxWidth().padding(horizontal = if (tablet) 12.dp else 16.dp, vertical = if (tablet) 8.dp else 0.dp)) {
            SubtitleSizePreview(size, Modifier.padding(top = if (tablet) 0.dp else 10.dp).height(if (tablet) 150.dp else 168.dp))
            ChoiceSegments(
                SubtitleSize.entries, size, label = { it.label }, onPick = onPick, name = "Subtitle size",
                modifier = Modifier.padding(top = if (tablet) 10.dp else 14.dp), enabled = enabled,
            )
            if (save.saving) SavingLine(Modifier.padding(top = 8.dp))
        }
        RowFailure(save.failure, size.label, onRetry)
    }
    Text(SUBTITLE_SIZE_FOLLOWS_DEVICE, style = Description, modifier = Modifier.padding(horizontal = if (tablet) 4.dp else 16.dp).padding(top = 8.dp))
    Footnote(SUBTITLE_SIZE_PICTURE_NOTE, tablet)
}

/** A harbour at dusk with a two-line cue on its plate, drawn as the players draw it at [size]. */
@Composable
private fun SubtitleSizePreview(chosen: SubtitleSize, modifier: Modifier = Modifier) {
    val look = rememberSystemCaptionLook()
    val px = 18f * look.scale * chosen.scale
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(PreviewScene)
            .drawBehind {
                drawRect(Brush.radialGradient(listOf(PreviewGlow, Color.Transparent), center = Offset(size.width * 0.64f, size.height * 0.38f), radius = size.width * 0.5f))
            }
            .semantics { contentDescription = "Preview: a subtitle at ${chosen.label} size" },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Text(
            "Who left the lamp on\nin the signal box?",
            style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = px.sp, lineHeight = (px * 1.25f).sp, color = WmmColors.Text, textAlign = TextAlign.Center),
            modifier = Modifier
                .padding(start = 16.dp, end = 16.dp, bottom = 14.dp)
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                .padding(horizontal = (px * 0.32f).dp, vertical = (px * 0.09f).dp)
                .clearAndSetSemantics {},
        )
    }
}

/** The preview's harbour at dusk, and its lamp's glow. */
private val PreviewScene = Brush.verticalGradient(
    0f to Color(0xFF0C1A22),
    0.46f to Color(0xFF1B3542),
    0.48f to Color(0xFF0A1216),
    1f to Color(0xFF06090B),
)
private val PreviewGlow = Color(0xFFFFCD78).copy(alpha = 0.18f)

@Composable
private fun Spacer(height: Int) = androidx.compose.foundation.layout.Spacer(Modifier.height(height.dp))

/** The phone's Start subtitles dialog: the four choices, each with its line. */
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
