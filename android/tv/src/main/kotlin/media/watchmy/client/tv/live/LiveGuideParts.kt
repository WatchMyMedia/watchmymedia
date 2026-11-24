package media.watchmy.client.tv.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.focused
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import media.watchmy.client.model.LiveChannel
import media.watchmy.client.model.LiveClockFormat
import media.watchmy.client.model.LiveProgramme
import media.watchmy.client.model.LiveReminder
import media.watchmy.client.model.RecordingGlyph
import media.watchmy.client.model.RecordingOptions
import media.watchmy.client.model.canRemind
import media.watchmy.client.model.episodeLine
import media.watchmy.client.model.liveMinutesLeft
import media.watchmy.client.model.liveStartsIn
import media.watchmy.client.model.nextAt
import media.watchmy.client.model.onAt
import media.watchmy.client.model.progressAt
import media.watchmy.client.presentation.live.LiveGuideViewModel
import media.watchmy.client.presentation.live.LiveRecordingsViewModel
import media.watchmy.client.tv.components.FocusableBox
import media.watchmy.client.tv.components.FocusedArt
import media.watchmy.client.tv.components.InitialFocus
import media.watchmy.client.tv.components.Kicker
import media.watchmy.client.tv.components.PanelRow
import media.watchmy.client.tv.components.TvDialog
import media.watchmy.client.tv.components.TvPalette
import media.watchmy.client.tv.components.TvSidePanel
import media.watchmy.client.tv.components.TvTag
import media.watchmy.client.tv.components.TvType
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.LiveProgress
import media.watchmy.client.ui.components.ProgrammePicture

/** What a programme's choices panel can do. */
internal enum class ChoiceKind { Remind, RecordEpisode, RecordSeries, Watch, Favourite }

/** One row of a programme's choices: its words, a second line, and a gold tick once it is done. */
@Immutable
internal data class ProgrammeChoice(val kind: ChoiceKind, val label: String, val description: String?, val chosen: Boolean = false, val enabled: Boolean = true)

/**
 * A programme's choices, in the order the panel lists them. A later one
 * leads with Remind me, then Record this episode, Record every new episode
 * and Watch <channel> now; one on now leads with Watch. Favourites come last.
 * A choice already made says so ("Reminder set", "Will record this
 * episode") with a tick, and OK on it undoes it.
 *
 * [personal]: the server keeps reminders and favourites for this person;
 * [canRecord]: this person may record; [onNow] is what the channel has on,
 * for Watch's second line.
 */
internal fun programmeChoices(
    channel: LiveChannel,
    programme: LiveProgramme,
    now: Long,
    format: LiveClockFormat,
    canRecord: Boolean,
    personal: Boolean,
    reminder: LiveReminder?,
    leadMinutes: Int,
    favourite: Boolean,
    onNow: LiveProgramme? = null,
): List<ProgrammeChoice> {
    val airing = programme.airingAt(now)
    val mark = programme.recording?.takeIf { it.active }
    val remind = if (personal && !airing && (reminder != null || programme.canRemind(now, leadMinutes))) {
        val at = programme.startMs - leadMinutes * 60_000L
        val bannerAt = if (leadMinutes == 0) "when it starts" else "at ${format.time(at)}"
        if (reminder != null) ProgrammeChoice(ChoiceKind.Remind, "Reminder set", "A banner here $bannerAt. OK takes it off.", chosen = true)
        else ProgrammeChoice(ChoiceKind.Remind, "Remind me", "A banner on this TV $bannerAt")
    } else null
    val record = if (canRecord && programme.endMs > now) {
        when {
            mark == null -> ProgrammeChoice(ChoiceKind.RecordEpisode, if (airing) "Record the rest" else if (programme.episodeLine() != null || programme.offersSeries) "Record this episode" else "Record this programme", if (airing) "From now until ${format.time(programme.endMs)}" else null)
            mark.glyph == RecordingGlyph.Clash -> ProgrammeChoice(ChoiceKind.RecordEpisode, "Won’t record", if (mark.canCancel) "The tuners are taken then. OK cancels it." else "The tuners are taken then", enabled = mark.canCancel)
            mark.state == "recording" -> ProgrammeChoice(ChoiceKind.RecordEpisode, "Recording", if (mark.canCancel) "OK stops it and keeps what’s recorded" else "Set by someone else", chosen = true, enabled = mark.canCancel)
            else -> ProgrammeChoice(ChoiceKind.RecordEpisode, "Will record this episode", if (mark.canCancel) "OK cancels the recording" else "Set by someone else", chosen = true, enabled = mark.canCancel)
        }
    } else null
    val series = if (canRecord && programme.offersSeries && programme.endMs > now) {
        if (mark?.series == true) ProgrammeChoice(ChoiceKind.RecordSeries, "Records every new episode", "Change or stop it in Recordings, under Series", chosen = true)
        else ProgrammeChoice(ChoiceKind.RecordSeries, "Record every new episode", "New episodes of the series on ${channel.number}")
    } else null
    val watch = ProgrammeChoice(
        ChoiceKind.Watch,
        if (airing) "Watch ${channel.number}" else "Watch ${channel.number} now",
        when {
            !channel.available -> "This channel can’t play right now"
            airing -> liveMinutesLeft(now, programme.endMs)
            onNow != null -> "${onNow.title} is on, ${liveMinutesLeft(now, onNow.endMs)}"
            else -> null
        },
        enabled = channel.available,
    )
    val star = if (personal) ProgrammeChoice(ChoiceKind.Favourite, if (favourite) "Remove ${channel.number} from favourites" else "Add ${channel.number} to favourites", null) else null
    return if (airing) listOfNotNull(watch, record, series, star) else listOfNotNull(remind, record, series, watch, star)
}

/**
 * What OK does on a programme, for the hint under the hero: "Watch 4.1"
 * while it is on; for a later one, its choices in a phrase: "Remind me,
 * record, or watch 4.1 now".
 */
internal fun guideOkHint(choices: List<ProgrammeChoice>, airing: Boolean, number: String): String {
    if (airing) return "Watch $number"
    val words = buildList {
        choices.firstOrNull { it.kind == ChoiceKind.Remind }?.let { add(if (it.chosen) "take off the reminder" else "remind me") }
        choices.firstOrNull { it.kind == ChoiceKind.RecordEpisode && !it.chosen && it.enabled }?.let { add("record") }
        add("watch $number now")
    }
    val phrase = when (words.size) {
        1 -> words[0]
        2 -> "${words[0]} or ${words[1]}"
        else -> words.dropLast(1).joinToString(", ") + ", or " + words.last()
    }
    return phrase.replaceFirstChar { it.uppercase() }
}

/**
 * The focused programme above the grid: when and where it is on (a red
 * dot while it is on now), its title, its facts and two lines about it, with
 * what OK and Hold OK do; its picture on the right. For a channel without
 * listings, the channel alone.
 */
@Composable
internal fun TvGuideHero(
    selection: LiveGuideViewModel.Selection?,
    channelOnly: LiveChannel?,
    now: Long,
    format: LiveClockFormat,
    modifier: Modifier = Modifier,
    state: LiveGuideViewModel.State? = null,
    canRecord: Boolean = false,
) {
    val programme = selection?.programme.takeIf { channelOnly == null }
    val channel = channelOnly ?: selection?.channel ?: return
    Box(modifier) {
        ProgrammePicture(programme, channel, Modifier.align(Alignment.TopEnd).padding(top = 8.dp).width(420.dp).clip(RoundedCornerShape(14.dp)), preferStill = true, caption = false)
        Column(Modifier.width(1050.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val airing = programme == null || programme.airingAt(now)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (airing && channel.available) RecDot()
                val day = when {
                    programme == null -> "Live"
                    airing -> "On now"
                    else -> programmeDay(programme.startMs, now, format)
                }
                Kicker("$day · ${channel.number} ${channel.name}")
            }
            if (programme == null) {
                HeroTitle(channel.name)
                Text(if (channel.available) "No listings · Plays live" else "Unavailable", style = TvType.Body, color = WmmColors.TextSoft)
                if (channel.available) KeyHints(listOf(KeyHint("OK", "Watch ${channel.number}")), Modifier.padding(top = 6.dp))
                return@Column
            }
            HeroTitle(programme.title)
            val reminder = state?.reminderFor(programme)
            FactsRow(
                // When it is on and the tags first; the episode last, where it gives way to them on a long line.
                buildList {
                    add { Text(format.span(programme.startMs, programme.endMs), style = TvType.Body, color = WmmColors.TextSoft, maxLines = 1) }
                    val left = if (airing) liveMinutesLeft(now, programme.endMs) else liveStartsIn(now, programme.startMs).replaceFirstChar { it.lowercase() }
                    add { Text(left, style = TvType.BodyStrong, color = WmmColors.Text, maxLines = 1) }
                    if (programme.new || programme.recording?.active == true || reminder != null) add {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (programme.new) TvTag("New", gold = true)
                            when (programme.recording?.takeIf { it.active }?.glyph) {
                                RecordingGlyph.Rec -> RecTag("Recording")
                                RecordingGlyph.Dot, RecordingGlyph.Ringed -> RecTag("Will record")
                                RecordingGlyph.Clash -> WarnTag("Won’t record")
                                null -> Unit
                            }
                            if (reminder != null) TvTag("Reminder set", gold = true)
                        }
                    }
                    programme.episodeLine()?.let { add { Text(it, style = TvType.Body, color = WmmColors.TextSoft, maxLines = 1, overflow = TextOverflow.Ellipsis) } }
                },
            )
            programme.description?.let {
                Text(it, style = TvType.Body, color = WmmColors.TextSoft, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 900.dp))
            }
            val choices = if (state != null) {
                val row = state.rows.firstOrNull { it.channel.id == channel.id }
                programmeChoices(channel, programme, now, format, canRecord, state.personal, reminder, state.leadMinutes, state.isFavourite(channel), row?.programmes?.firstOrNull { it.airingAt(now) })
            } else listOf(ProgrammeChoice(ChoiceKind.Watch, "Watch", null))
            val hints = buildList {
                add(KeyHint("OK", guideOkHint(choices, airing, channel.number)))
                if (choices.size > 1 || !airing) add(KeyHint("Hold OK", "The same choices from any programme"))
            }
            KeyHints(hints, Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun HeroTitle(text: String) {
    Text(text, style = TvType.Title, color = WmmColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** Facts in a line with a grey dot between each (`.facts`); the last one gives way (ellipsized) where the line runs out. */
@Composable
internal fun FactsRow(items: List<@Composable () -> Unit>, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        items.forEachIndexed { i, item ->
            if (i > 0) Text("·", style = TvType.Body, color = TvPalette.Muted)
            item()
        }
    }
}

/** What the choices panel does with each row. */
internal class ProgrammeChoiceActions(
    val onRemind: (LiveProgramme) -> Unit,
    val onForget: (LiveReminder) -> Unit,
    val onStar: (LiveChannel) -> Unit,
    val onWatch: (LiveChannel) -> Unit,
    val onRecordings: () -> Unit,
)

/**
 * A programme's choices (OK on a later programme, or Hold OK on any): a
 * panel from the right with when and where it is on, its title and facts,
 * then the rows [programmeChoices] gives. A row does its thing at once and
 * the panel stays, saying so; Back closes it and focus returns to the
 * programme. Focus opens on the first row.
 */
@Composable
internal fun TvProgrammeChoices(
    channel: LiveChannel,
    programme: LiveProgramme,
    state: LiveGuideViewModel.State,
    now: Long,
    format: LiveClockFormat,
    recorder: LiveRecordingsViewModel,
    actions: ProgrammeChoiceActions,
    onDismiss: () -> Unit,
) {
    val recording by recorder.state.collectAsStateWithLifecycle()
    // The programme as the guide has it now: its recording mark and reminder change while the panel is open.
    val current = state.rows.firstOrNull { it.channel.id == channel.id }?.programmes?.firstOrNull { it.id == programme.id } ?: programme
    val reminder = state.reminderFor(current)
    val onNow = state.rows.firstOrNull { it.channel.id == channel.id }?.programmes?.firstOrNull { it.airingAt(now) }
    val choices = programmeChoices(channel, current, now, format, recording.canRecord, state.personal, reminder, state.leadMinutes, state.isFavourite(channel), onNow)
    val first = remember { FocusRequester() }
    TvDialog(onDismiss) {
        TvSidePanel(title = null) {
            Text(
                "${channel.number} ${channel.name} · ${if (current.airingAt(now)) "on now" else programmeDay(current.startMs, now, format).lowercase()}",
                style = TvType.Second, color = TvPalette.Muted, maxLines = 1,
            )
            Text(current.title, style = TvType.Heading, color = WmmColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            // The episode on a line of its own: beside the times it pushed the New tag off the panel's edge.
            current.episodeLine()?.let { Text(it, style = TvType.Second, color = WmmColors.TextSoft, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            FactsRow(
                buildList {
                    add { Text(format.span(current.startMs, current.endMs), style = TvType.Second, color = WmmColors.TextSoft, maxLines = 1) }
                    if (current.new) add { TvTag("New", gold = true) }
                },
            )
            current.description?.let { Text(it, style = TvType.Second, color = WmmColors.TextSoft, maxLines = 3, overflow = TextOverflow.Ellipsis) }
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                choices.forEachIndexed { i, choice -> key(choice.kind) {
                    PanelRow(
                        text = if (recording.busy && choice.kind in listOf(ChoiceKind.RecordEpisode, ChoiceKind.RecordSeries) && !choice.chosen) "Setting it…" else choice.label,
                        description = choice.description,
                        chosen = choice.chosen,
                        enabled = choice.enabled,
                        onClick = {
                            when (choice.kind) {
                                ChoiceKind.Remind -> if (reminder != null) actions.onForget(reminder) else actions.onRemind(current)
                                ChoiceKind.RecordEpisode -> {
                                    val mark = current.recording?.takeIf { it.active }
                                    if (mark == null) recorder.record(current) else if (mark.canCancel) recorder.cancel(mark.id)
                                }
                                ChoiceKind.RecordSeries -> if (current.recording?.series == true) { onDismiss(); actions.onRecordings() } else recorder.record(current, RecordingOptions(series = true))
                                ChoiceKind.Watch -> { onDismiss(); actions.onWatch(channel) }
                                ChoiceKind.Favourite -> actions.onStar(channel)
                            }
                        },
                        modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                    )
                } }
            }
            recording.error?.let { Text(it, style = TvType.Second, color = WmmColors.Warn) }
            Spacer(Modifier.weight(1f))
            KeyHints(listOf(KeyHint("Back", "The guide")))
        }
        InitialFocus(first)
    }
}

/**
 * Home's On now card: the programme's picture, the channel in a dark flag,
 * REC in red while it records, and how far it has got along the
 * bottom; then the title and "48 min left · then Night Ferry". Focus grows
 * the picture, as a card's does. The width is the caller's.
 */
@Composable
fun TvOnNowCard(channel: LiveChannel, now: Long, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val on = channel.onAt(now)
    val next = channel.nextAt(now)
    // The card reads as one thing; its focus state stays in the semantics it clears, so TalkBack (and the focus tests) see it.
    var isFocused by remember { mutableStateOf(false) }
    FocusableBox(
        onClick = onClick,
        modifier = modifier.onFocusChanged { isFocused = it.isFocused }.clearAndSetSemantics {
            contentDescription = "${channel.number} ${channel.name}" + (on?.let { ", ${it.title}, ${liveMinutesLeft(now, it.endMs)}" } ?: ", No listings") + ", Watch live"
            focused = isFocused
        },
        cornerRadius = 14.dp,
        ring = false,
    ) { focused ->
        Column {
            FocusedArt(focused) {
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))) {
                    ProgrammePicture(on, channel, Modifier.fillMaxWidth())
                    PictureFlag("${channel.number} ${channel.name}", Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = 16.dp))
                    if (on?.recording?.glyph == RecordingGlyph.Rec) PictureFlag("REC", Modifier.align(Alignment.TopStart).padding(10.dp), rec = true, spoken = "Recording")
                    if (on != null) LiveProgress(on.progressAt(now), Modifier.align(Alignment.BottomStart).fillMaxWidth(), height = 8.dp, track = WmmColors.Ground.copy(alpha = .62f))
                }
            }
            Text(
                on?.title ?: channel.name,
                modifier = Modifier.padding(top = if (focused) 26.dp else 18.dp),
                style = TvType.BodyStrong, color = WmmColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                when {
                    on == null -> "No listings · plays live"
                    on.live && next == null -> "Live · until ${rememberClock().time(on.endMs)}"
                    next != null -> "${liveMinutesLeft(now, on.endMs)} · then ${next.title}"
                    else -> liveMinutesLeft(now, on.endMs)
                },
                style = TvType.Caption, color = TvPalette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun rememberClock(): LiveClockFormat = media.watchmy.client.ui.components.rememberLiveClockFormat()

/** The height of a guide's hero above the grid. */
internal val GuideHeroHeight = 296.dp
