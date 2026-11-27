package media.watchmy.client.mobile.player

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import media.watchmy.client.mobile.components.ChoiceSegments
import media.watchmy.client.mobile.components.GoldTextButton
import media.watchmy.client.model.BURNED_IN_TIMING_NOTE
import media.watchmy.client.model.CHANGING_SOUND
import media.watchmy.client.model.DOWNLOAD_SOUND_NOTE
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
import media.watchmy.client.model.formatClock
import media.watchmy.client.model.soundFailedLine
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.SubtitleCues
import media.watchmy.client.ui.components.WmmIcons

/** What the timing controls do: move it, Reset, Save for everyone, or try a failed save again. */
internal sealed interface TimingAction {
    data class Move(val deltaMs: Long) : TimingAction
    data object Reset : TimingAction
    data object SaveForEveryone : TimingAction
    data object Retry : TimingAction
}

/**
 * Sound, under the audio tracks: Boost dialogue and Reduce loud sounds, Off,
 * Medium or High each. A change keeps the panel open; its row is busy while
 * the play loads again at the same place ([tablet] says so in words beside
 * the name), and one that didn't save says so with Try again. A download
 * shows them greyed, with why.
 */
@Composable
internal fun ColumnScope.SoundSection(sound: PlayerSound, tablet: Boolean, onSound: (SoundSetting, SoundLevel) -> Unit) {
    Text(SOUND_HEADING, style = PlayerType.OptionGroup, modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 10.dp, bottom = 2.dp))
    for (setting in SoundSetting.entries) {
        val busy = sound.busy == setting
        Column(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(setting.label, style = PlayerType.Option, color = if (sound.download) WmmColors.TextFaint else WmmColors.Text)
                if (busy && tablet) {
                    Row(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        CircularProgressIndicator(Modifier.size(13.dp), color = WmmColors.Accent, strokeWidth = 2.dp)
                        Text(CHANGING_SOUND, style = PlayerType.OptionDetail)
                    }
                }
            }
            ChoiceSegments(
                SoundLevel.entries, sound.level(setting), label = { it.label }, onPick = { onSound(setting, it) }, name = setting.label,
                enabled = !sound.download && (sound.busy == null || busy), busy = busy, busyLabel = CHANGING_SOUND,
            )
            sound.failed?.takeIf { it.setting == setting }?.let { failure ->
                FailureRow(soundFailedLine(failure.still)) { onSound(setting, failure.wanted) }
            }
        }
    }
    if (sound.download) Text(DOWNLOAD_SOUND_NOTE, style = PlayerType.Footnote.copy(color = WmmColors.TextSoft), modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    else Text(SOUND_NOTE, style = PlayerType.Footnote, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
}

/**
 * Subtitle timing, under the subtitle tracks while one is on: Earlier and
 * Later (a press moves it 0.1 s, holding repeats), its value ("0.4 s later",
 * In step), Reset, and Save for everyone for an admin. The panel stays
 * open as it moves. [preview] is the phone's: the paused frame's cue, which
 * moves with it; the tablet's video plays on beside its panel.
 */
@Composable
internal fun ColumnScope.TimingSection(
    timing: SubtitleTimingState,
    admin: Boolean,
    tablet: Boolean,
    onTiming: (TimingAction) -> Unit,
    preview: (@Composable () -> Unit)?,
) {
    val saveAll = timing.offersSaveForEveryone(admin)
    if (tablet) {
        Text(SUBTITLE_TIMING, style = PlayerType.OptionGroup, modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 2.dp))
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TimingButton(TIMING_EARLIER, before = true, Modifier.padding(0.dp)) { onTiming(TimingAction.Move(-TIMING_STEP_MS)) }
            Column(Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(timing.label, style = PlayerType.Option.copy(fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, fontFeatureSettings = "tnum"), textAlign = TextAlign.Center)
                if (timing.showsSetForEveryone) Text(SET_FOR_EVERYONE, style = PlayerType.OptionDetail, textAlign = TextAlign.Center)
            }
            TimingButton(TIMING_LATER, before = false, Modifier.padding(0.dp)) { onTiming(TimingAction.Move(TIMING_STEP_MS)) }
        }
    } else {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 10.dp, bottom = 0.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(SUBTITLE_TIMING, style = PlayerType.OptionGroup)
            Text(timing.label, style = PlayerType.Time, textAlign = TextAlign.End, modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
        }
        if (timing.showsSetForEveryone) Text(SET_FOR_EVERYONE, style = PlayerType.OptionDetail, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp))
        if (!timing.burnedIn) preview?.invoke()
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TimingButton(TIMING_EARLIER, before = true, Modifier.weight(1f)) { onTiming(TimingAction.Move(-TIMING_STEP_MS)) }
            TimingButton(TIMING_LATER, before = false, Modifier.weight(1f)) { onTiming(TimingAction.Move(TIMING_STEP_MS)) }
        }
    }
    val savedAll = admin && timing.savedForEveryone && !timing.own
    if (timing.canReset || saveAll || savedAll) {
        Row(Modifier.padding(top = if (tablet) 4.dp else 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (timing.canReset) GoldTextButton(TIMING_RESET, { onTiming(TimingAction.Reset) })
            if (saveAll) GoldTextButton(SAVE_FOR_EVERYONE, { onTiming(TimingAction.SaveForEveryone) }, icon = WmmIcons.Person)
            if (savedAll) {
                Row(Modifier.heightIn(min = 36.dp).padding(horizontal = 8.dp).semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(WmmIcons.Check, contentDescription = null, tint = SavedGreen, modifier = Modifier.size(16.dp))
                    Text(SAVED_FOR_EVERYONE, style = PlayerType.Button.copy(color = SavedGreen))
                }
            }
        }
    }
    if (timing.saveFailed) FailureRow(TIMING_SAVE_FAILED) { onTiming(TimingAction.Retry) }
    if (timing.burnedIn) Text(BURNED_IN_TIMING_NOTE, style = PlayerType.Footnote.copy(color = WmmColors.TextSoft), modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 6.dp))
    Text(timing.help, style = PlayerType.Footnote, modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 4.dp))
    if (saveAll) Text(SAVE_FOR_EVERYONE_HELP, style = PlayerType.Footnote, modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 4.dp))
}

/** "Saved for everyone"'s green. */
private val SavedGreen = Color(0xFF86EFAC)

/** A failure in the panel: the alert, the line in red and Try again. */
@Composable
private fun FailureRow(text: String, onRetry: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(WmmIcons.Alert, contentDescription = null, tint = WmmColors.Error, modifier = Modifier.size(16.dp))
        Text(text, style = PlayerType.OptionDetail.copy(color = FailRed), modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Assertive })
        GoldTextButton("Try again", onRetry)
    }
}

private val FailRed = Color(0xFFFCA5A5)

/**
 * Earlier or Later: a 40 dp pill that moves the timing once a press, and
 * again every tenth of a second while held, after a moment.
 */
@Composable
private fun TimingButton(text: String, before: Boolean, modifier: Modifier, onStep: () -> Unit) {
    val step by rememberUpdatedState(onStep)
    Row(
        modifier
            .height(40.dp)
            .widthIn(min = 96.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(WmmColors.Line2)
            .semantics {
                role = Role.Button
                contentDescription = text
                onClick { step(); true }
            }
            .pointerInput(Unit) {
                val input = this
                coroutineScope {
                    val scope = this
                    input.awaitEachGesture {
                        awaitFirstDown()
                        step()
                        val repeating = scope.launch {
                            delay(HOLD_START_MS)
                            while (true) {
                                step()
                                delay(HOLD_REPEAT_MS)
                            }
                        }
                        waitForUpOrCancellation()
                        repeating.cancel()
                    }
                }
            }
            .padding(start = if (before) 12.dp else 16.dp, end = if (before) 16.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        if (before) Icon(WmmIcons.ChevronLeft, contentDescription = null, tint = WmmColors.Text, modifier = Modifier.size(18.dp))
        Text(text, style = PlayerType.Button)
        if (!before) Icon(WmmIcons.ChevronRight, contentDescription = null, tint = WmmColors.Text, modifier = Modifier.size(18.dp))
    }
}

private const val HOLD_START_MS = 400L
private const val HOLD_REPEAT_MS = 100L

/**
 * The phone's preview of the paused frame's subtitle: the cue Media3 shows
 * at [positionMs], which a timing change moves, small on the frame's dusk.
 */
@Composable
internal fun TimingPreview(player: Player, positionMs: Long) {
    Box(
        Modifier
            .padding(start = 8.dp, end = 8.dp, top = 4.dp)
            .fillMaxWidth()
            .height(60.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(PreviewFrame),
    ) {
        Text(formatClock(positionMs), style = PlayerType.MiniTime.copy(fontSize = 11.sp, color = WmmColors.TextSoft), modifier = Modifier.padding(start = 8.dp, top = 6.dp))
        SubtitleCues(player, fontSize = 14.sp, sidePadding = 8.dp, bottomPadding = 8.dp, topPadding = 8.dp)
    }
}

private val PreviewFrame = Brush.verticalGradient(
    0f to Color(0xFF1B3542),
    0.40f to Color(0xFF16303A),
    0.42f to Color(0xFF0A1216),
    1f to Color(0xFF06090B),
)
