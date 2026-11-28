package media.watchmy.client.tv.player

import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import media.watchmy.client.model.BURNED_IN_TIMING_NOTE
import media.watchmy.client.model.CHANGING_SOUND
import media.watchmy.client.model.PlayerSound
import media.watchmy.client.model.SAVED_FOR_EVERYONE
import media.watchmy.client.model.SAVE_FOR_EVERYONE
import media.watchmy.client.model.SAVE_FOR_EVERYONE_HELP
import media.watchmy.client.model.SET_FOR_EVERYONE
import media.watchmy.client.model.SOUND_HEADING
import media.watchmy.client.model.SOUND_NOTE
import media.watchmy.client.model.SUBTITLE_TIMING
import media.watchmy.client.model.SoundLevel
import media.watchmy.client.model.SoundSetting
import media.watchmy.client.model.SubtitleTimingState
import media.watchmy.client.model.TIMING_EARLIER
import media.watchmy.client.model.TIMING_LATER
import media.watchmy.client.model.TIMING_RESET
import media.watchmy.client.model.TIMING_SAVE_FAILED
import media.watchmy.client.model.TIMING_STEP_MS
import media.watchmy.client.model.soundFailedLine
import media.watchmy.client.tv.components.FocusGrowth
import media.watchmy.client.tv.components.FocusableBox
import media.watchmy.client.tv.components.OptionRow
import media.watchmy.client.tv.components.PanelGroup
import media.watchmy.client.tv.components.PanelRow
import media.watchmy.client.tv.components.TvPalette
import media.watchmy.client.tv.components.TvType
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.WmmIcons

/** What the panel's timing does: move it, Reset, Save for everyone, or try a failed save again. */
internal sealed interface TimingAction {
    data class Move(val deltaMs: Long) : TimingAction
    data object Reset : TimingAction
    data object SaveForEveryone : TimingAction
    data object Retry : TimingAction
}

/** Which of the timing's rows has focus, for what the keys' line says. */
internal enum class TimingFocus { Row, Reset, SaveForEveryone, Saved }

/** What Audio & subtitles' Sound and Subtitle timing call; [admin] offers Save for everyone. */
internal class TvPickerExtras(
    val onSound: (SoundSetting, SoundLevel) -> Unit = { _, _ -> },
    val onTiming: (TimingAction) -> Unit = {},
    val admin: Boolean = false,
)

private const val PRESS_OK_AGAIN = "Press OK to try again."

/**
 * Sound, under the audio tracks: Boost dialogue and Reduce loud sounds, a
 * row each that opens a small list of Off, Medium and High ([onOpen]). A
 * row says Changing the sound… while the play loads again at the same
 * place, and one that didn't save says so; OK tries it again.
 */
@Composable
internal fun ColumnScope.SoundRows(sound: PlayerSound, rows: Map<SoundSetting, FocusRequester>, onOpen: (SoundSetting) -> Unit, onSound: (SoundSetting, SoundLevel) -> Unit) {
    PanelGroup(SOUND_HEADING, Modifier.padding(bottom = GroupGap).semantics { heading() })
    for (setting in SoundSetting.entries) {
        val failure = sound.failed?.takeIf { it.setting == setting }
        val busy = sound.busy == setting
        PanelRow(
            text = setting.label,
            onClick = {
                when {
                    // One change at a time: OK waits while either row's goes through.
                    sound.busy != null -> Unit
                    failure != null -> onSound(setting, failure.wanted)
                    else -> onOpen(setting)
                }
            },
            modifier = Modifier.focusRequester(rows.getValue(setting)),
            description = when {
                busy -> CHANGING_SOUND
                failure != null -> "${soundFailedLine(failure.still)} $PRESS_OK_AGAIN"
                else -> null
            },
            trailing = sound.level(setting).label,
            chevron = !busy,
        )
    }
    PanelNote(SOUND_NOTE)
}

/**
 * The small list a sound row opens: its words, Off, Medium and High with the
 * one in use ticked, and the note. OK on one goes back to the panel's list,
 * whose row then changes the sound; Back goes back without a change.
 */
@Composable
internal fun ColumnScope.SoundChoices(setting: SoundSetting, current: SoundLevel, first: FocusRequester, onPick: (SoundLevel) -> Unit) {
    Text(setting.help, style = TvType.Second, color = TvPalette.Muted)
    Column(Modifier.padding(horizontal = 10.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (level in SoundLevel.entries) {
            OptionRow(
                text = level.label,
                selected = level == current,
                onClick = { onPick(level) },
                modifier = if (level == current) Modifier.focusRequester(first) else Modifier,
            )
        }
    }
    PanelNote(SOUND_NOTE)
}

/**
 * Subtitle timing, under the subtitle tracks while one is on: one row that
 * ◀ moves earlier and ▶ later, 0.1 s a press (holding repeats), with the
 * value between Earlier and Later; Reset when it would change something,
 * and Save for everyone for an admin, as rows under it. [onFocus] says
 * whether one of them has focus, so the panel lets the cues beside it show.
 */
@Composable
internal fun ColumnScope.TimingRows(
    timing: SubtitleTimingState,
    admin: Boolean,
    row: FocusRequester,
    onTiming: (TimingAction) -> Unit,
    onFocus: (TimingFocus) -> Unit,
) {
    PanelGroup(SUBTITLE_TIMING, Modifier.padding(bottom = GroupGap).semantics { heading() })
    TimingRow(timing, onTiming, Modifier.focusRequester(row).onFocusChanged { if (it.isFocused) onFocus(TimingFocus.Row) })
    if (timing.canReset) {
        PanelRow(TIMING_RESET, onClick = { onTiming(TimingAction.Reset) }, modifier = Modifier.onFocusChanged { if (it.isFocused) onFocus(TimingFocus.Reset) })
    }
    val saveAll = timing.offersSaveForEveryone(admin)
    val savedAll = admin && timing.savedForEveryone && !timing.own
    if (saveAll || savedAll) {
        PanelRow(
            SAVE_FOR_EVERYONE,
            onClick = { if (saveAll) onTiming(TimingAction.SaveForEveryone) },
            modifier = Modifier.onFocusChanged { if (it.isFocused) onFocus(if (savedAll) TimingFocus.Saved else TimingFocus.SaveForEveryone) },
            description = if (savedAll) SAVED_FOR_EVERYONE else SAVE_FOR_EVERYONE_HELP,
            chosen = savedAll,
        )
    }
    if (timing.burnedIn) PanelNote(BURNED_IN_TIMING_NOTE)
    PanelNote(timing.help)
}

@Composable
private fun TimingRow(timing: SubtitleTimingState, onTiming: (TimingAction) -> Unit, modifier: Modifier = Modifier) {
    // The end that is held, for its pressed look: true for Later.
    var held by remember { mutableStateOf<Boolean?>(null) }
    val words = listOfNotNull("$SUBTITLE_TIMING, ${timing.label}", SET_FOR_EVERYONE.takeIf { timing.showsSetForEveryone }).joinToString(", ") +
        ". Left for earlier, right for later."
    FocusableBox(
        onClick = { if (timing.saveFailed) onTiming(TimingAction.Retry) },
        modifier = modifier
            .fillMaxWidth()
            .onPreviewKeyEvent { event ->
                val code = event.nativeKeyEvent.keyCode
                if (code != NativeKeyEvent.KEYCODE_DPAD_LEFT && code != NativeKeyEvent.KEYCODE_DPAD_RIGHT) return@onPreviewKeyEvent false
                val later = code == NativeKeyEvent.KEYCODE_DPAD_RIGHT
                // A held key repeats its key-down: each is a step.
                when (event.type) {
                    KeyEventType.KeyDown -> {
                        held = later
                        onTiming(TimingAction.Move(if (later) TIMING_STEP_MS else -TIMING_STEP_MS))
                    }
                    KeyEventType.KeyUp -> held = null
                }
                true
            }
            .semantics { contentDescription = words },
        cornerRadius = 16.dp,
        focusedScale = FocusGrowth.ROW,
    ) { focused ->
        Column(
            Modifier
                .fillMaxWidth()
                .background(if (focused) TvPalette.Lift else Color.Transparent, RoundedCornerShape(16.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .clearAndSetSemantics { },
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(Modifier.fillMaxWidth().height(76.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TimingEnd(TIMING_EARLIER, before = true, focused = focused, held = held == false)
                Column(Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(timing.label, style = TvType.CardTitle.copy(fontFeatureSettings = "tnum"), color = WmmColors.Text, maxLines = 1, textAlign = TextAlign.Center)
                    if (timing.showsSetForEveryone) Text(SET_FOR_EVERYONE, style = TvType.Caption, color = TvPalette.Muted, maxLines = 1)
                }
                TimingEnd(TIMING_LATER, before = false, focused = focused, held = held == true)
            }
            if (timing.saveFailed) Text("$TIMING_SAVE_FAILED $PRESS_OK_AGAIN", Modifier.padding(start = 12.dp, end = 12.dp, bottom = 4.dp), style = TvType.Caption, color = FailRed)
        }
    }
}

/** Earlier or Later at the timing row's ends: a pill that lights while the row has focus, and sinks while its key is held. */
@Composable
private fun TimingEnd(text: String, before: Boolean, focused: Boolean, held: Boolean) {
    val fill = when {
        held -> WmmColors.Text
        focused -> Color.White.copy(alpha = 0.12f)
        else -> Color.White.copy(alpha = 0.08f)
    }
    val ink = when {
        held -> WmmColors.Ground
        focused -> WmmColors.Text
        else -> WmmColors.TextSoft
    }
    Row(
        Modifier.height(56.dp).scale(if (held) 0.97f else 1f).background(fill, RoundedCornerShape(28.dp)).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (before) Icon(WmmIcons.ChevronLeft, contentDescription = null, tint = ink, modifier = Modifier.size(26.dp))
        Text(text, style = TvType.Second.copy(fontWeight = FontWeight.SemiBold), color = ink, maxLines = 1)
        if (!before) Icon(WmmIcons.ChevronRight, contentDescription = null, tint = ink, modifier = Modifier.size(26.dp))
    }
}

private val FailRed = Color(0xFFFCA5A5)

/**
 * Room between a group's heading and its first row, and between a row and
 * a line under it: a focused row's ring (4 dp out, 4 dp thick) and growth
 * reach past its edge, and would cut through them.
 */
internal val GroupGap = 8.dp

/** A grey line under a group's rows, clear of the focused row above it. */
@Composable
internal fun PanelNote(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(start = 24.dp, end = 24.dp, top = GroupGap), style = TvType.Caption, color = TvPalette.Muted)
}

/** The keys' line at the panel's foot: on the timing row, how it moves; elsewhere what OK does and that Back closes. */
@Composable
internal fun PickerKeys(onTiming: Boolean, ok: String, back: String) {
    Row(Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (onTiming) {
            Key("◀")
            Key("▶")
            Text("0.1 s a press, hold to repeat", style = TvType.Caption, color = WmmColors.TextSoft, maxLines = 1)
        } else {
            Key("OK")
            Text(ok, style = TvType.Caption, color = WmmColors.TextSoft, maxLines = 1, modifier = Modifier.weight(1f))
            Key("Back")
            Text(back, style = TvType.Caption, color = WmmColors.TextSoft, maxLines = 1)
        }
    }
}

@Composable
private fun Key(text: String) {
    Text(
        text,
        Modifier
            .border(2.dp, Color.White.copy(alpha = 0.32f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 2.dp),
        style = TvType.Caption.copy(fontWeight = FontWeight.Bold),
        color = WmmColors.Text,
    )
}
