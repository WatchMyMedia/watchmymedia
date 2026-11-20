package media.watchmy.client.tv.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import media.watchmy.client.model.ON_EVERY_DEVICE
import media.watchmy.client.model.SaveFailure
import media.watchmy.client.model.listLine
import media.watchmy.client.model.subtitleLanguageLabel
import media.watchmy.client.presentation.settings.LanguageList
import media.watchmy.client.presentation.settings.SettingKey
import media.watchmy.client.presentation.settings.SettingsViewModel
import media.watchmy.client.tv.components.tryFocus
import media.watchmy.client.ui.WmmColors
import java.util.Locale

/**
 * Where focus goes in Audio & subtitles when a change takes away the row
 * that had it (a language added from Use English goes to the list, a
 * removed one to its Add language), and which row a list's change was made
 * on, for its Saving… and its failure.
 */
@Stable
internal class LanguageFocus {
    val audioFirst = FocusRequester()
    val audioAdd = FocusRequester()
    val subtitleFirst = FocusRequester()
    val subtitleAdd = FocusRequester()

    /** Focused once it is on screen. */
    var refocus by mutableStateOf<FocusRequester?>(null)

    /** The list and language last changed; a null language is the list's Add language row. */
    var changed by mutableStateOf<Pair<LanguageList, String?>?>(null)
}

private val LanguageList.key: SettingKey get() = if (this == LanguageList.Audio) SettingKey.AudioLanguages else SettingKey.SubtitleLanguages

/**
 * Audio & subtitles,
 * its content as approved, under the account's heading: each change
 * saves at once, Saving… shows on the row that changed, and a failure stays
 * on it until OK tries again. The rows wait
 * for the account's settings to load.
 */
@Composable
internal fun ColumnScope.LanguagesPane(
    state: SettingsViewModel.State,
    locale: Locale,
    focus: LanguageFocus,
    notLoadedDetail: String,
    retrying: Boolean,
    first: FocusRequester,
    calls: SettingsCalls,
) {
    LaunchedEffect(focus.refocus, state.playback) {
        val target = focus.refocus ?: return@LaunchedEffect
        // Not there until the change shows; this runs again when it does.
        if (target.tryFocus()) focus.refocus = null
    }
    val notLoaded = state.prefsError != null || retrying
    AccountNotLoaded(state, retrying, notLoadedDetail, calls, Modifier.focusRequester(first))
    val ready = state.accountReady
    val subtitles = state.playback.subtitles
    val audio = state.playback.audio.languages
    // Speech has no script or region to choose: the TV's language alone.
    val suggested = locale.language.takeIf { it.isNotBlank() && it != "und" }
    // The pane's top row, which ▶ from the groups lands on.
    val top = when {
        notLoaded -> null
        !state.audioLanguagesAvailable -> "subtitles:start"
        audio.isEmpty() && suggested != null -> "audio:use"
        audio.isNotEmpty() -> "audio:${audio.first()}"
        else -> "audio:add"
    }
    fun Modifier.top(key: String): Modifier = if (key == top) focusRequester(first) else this

    /**
     * A list row's status: the list's Saving… or failure shows on the row its
     * change was made on, or on its Add language row (tag null) when that row
     * isn't there (a language added that didn't save, one removed on its way).
     */
    fun listStatus(list: LanguageList, tag: String?): RowStatus {
        val (changedList, changedTag) = focus.changed ?: return RowStatus.Idle
        if (changedList != list) return RowStatus.Idle
        val languages = if (list == LanguageList.Audio) audio else subtitles.languages
        if (tag != changedTag?.takeIf { it in languages }) return RowStatus.Idle
        val key = list.key
        if (key in state.saving) return RowStatus.Saving
        val failure: SaveFailure = state.failed[key] ?: return RowStatus.Idle
        return RowStatus.Failed(failure.listLine(list.title) + " " + PRESS_OK_AGAIN)
    }

    fun ok(list: LanguageList, action: () -> Unit): () -> Unit = { if (list.key in state.failed) calls.retry(list.key) else action() }

    PaneHeading(ON_EVERY_DEVICE)
    if (state.audioLanguagesAvailable) {
        SubHeading("Audio languages", top = 8.dp)
        Note("Videos start in the first of these languages they have. If they have none, they play the file’s default.")
        if (audio.isEmpty()) {
            Note("No audio language yet. Videos play the file’s default audio.", Modifier.padding(top = 6.dp))
            if (suggested != null) {
                val name = subtitleLanguageLabel(suggested, locale)
                SettingRow(
                    "Use $name",
                    onClick = ok(LanguageList.Audio) {
                        focus.changed = LanguageList.Audio to suggested
                        calls.addLanguage(LanguageList.Audio, suggested)
                        focus.refocus = focus.audioFirst
                    },
                    description = "Your TV uses $name.",
                    modifier = Modifier.top("audio:use"),
                    focusKey = "audio:use",
                    enabled = ready,
                ) {}
            }
        }
        audio.forEachIndexed { index, tag ->
            SettingRow(
                "${index + 1}. ${subtitleLanguageLabel(tag, locale)}",
                onClick = ok(LanguageList.Audio) { calls.editLanguage(LanguageList.Audio, tag) },
                modifier = (if (index == 0) Modifier.focusRequester(focus.audioFirst) else Modifier).top("audio:$tag"),
                focusKey = "audio:$tag",
                status = listStatus(LanguageList.Audio, tag),
                enabled = ready,
            ) { Value("Options", chevron = true) }
        }
        SettingRow(
            "Add language",
            onClick = ok(LanguageList.Audio) { calls.addTo(LanguageList.Audio) },
            modifier = Modifier.focusRequester(focus.audioAdd).top("audio:add"),
            focusKey = "audio:add",
            status = listStatus(LanguageList.Audio, null),
            enabled = ready,
        ) { Value("", chevron = true) }
        Note("Commentary and audio description are never chosen for you. You can pick them in the player.", color = WmmColors.TextFaint)
    }
    SettingRow(
        "Start subtitles",
        onClick = state.onOk(SettingKey.SubtitleChoice, calls) { calls.show(Overlay.SubtitleChoice) },
        description = "When a video starts",
        modifier = Modifier.padding(top = if (state.audioLanguagesAvailable) 16.dp else 0.dp).top("subtitles:start"),
        focusKey = "subtitles:start",
        status = state.status(SettingKey.SubtitleChoice, subtitles.choice.shortLabel),
        enabled = ready,
    ) { Value(subtitles.choice.shortLabel, chevron = true) }
    // Inline, not the notice pill: that lasts 2.6 s, too short to read this.
    if (state.showForeignAudioNotice) ForeignAudioNotice()

    SubHeading("Subtitle languages", top = 16.dp)
    Note("Use the first of these languages that has a subtitle.")
    if (state.subtitlesFollowAudio && state.audioLanguagesAvailable) Note("Until you add one, subtitles use your audio languages.", Modifier.padding(top = 6.dp))
    if (subtitles.languages.isEmpty()) {
        val tag = locale.toLanguageTag()
        if (tag != "und") {
            SettingRow(
                "Use device language",
                onClick = ok(LanguageList.Subtitles) {
                    focus.changed = LanguageList.Subtitles to tag
                    calls.addLanguage(LanguageList.Subtitles, tag)
                    focus.refocus = focus.subtitleFirst
                },
                enabled = ready,
            ) { Value(subtitleLanguageLabel(tag, locale)) }
        }
    }
    subtitles.languages.forEachIndexed { index, tag ->
        SettingRow(
            "${index + 1}. ${subtitleLanguageLabel(tag, locale)}",
            onClick = ok(LanguageList.Subtitles) { calls.editLanguage(LanguageList.Subtitles, tag) },
            modifier = if (index == 0) Modifier.focusRequester(focus.subtitleFirst) else Modifier,
            focusKey = "subtitles:$tag",
            status = listStatus(LanguageList.Subtitles, tag),
            enabled = ready,
        ) { Value("Options", chevron = true) }
    }
    SettingRow(
        "Add language",
        onClick = ok(LanguageList.Subtitles) { calls.addTo(LanguageList.Subtitles) },
        modifier = Modifier.focusRequester(focus.subtitleAdd),
        focusKey = "subtitles:add",
        status = listStatus(LanguageList.Subtitles, null),
        enabled = ready,
    ) { Value("", chevron = true) }
    val automatic = subtitles.automaticAvailable
    val on = automatic && subtitles.automaticDownload
    SettingRow(
        "Automatic download",
        onClick = state.onOk(SettingKey.AutomaticSubtitles, calls) { if (automatic) calls.setAutomaticSubtitles(!subtitles.automaticDownload) },
        description = if (automatic) {
            "When subtitles would start and no local track matches, try one exact-file match from OpenSubtitles.com after the video starts. Uses the server’s download allowance."
        } else {
            state.automaticBlockedNote
        },
        stacked = true, labelColor = if (automatic) WmmColors.Text else WmmColors.TextMuted,
        modifier = Modifier.switch(on, enabled = automatic && ready),
        status = state.status(SettingKey.AutomaticSubtitles, if (on) "On" else "Off"),
        enabled = ready,
    ) { Toggle(on, Modifier.alpha(if (automatic) 1f else 0.45f)) }
}

