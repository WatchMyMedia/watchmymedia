package media.watchmy.client.tv.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.key
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.focused
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import media.watchmy.client.model.LiveChannel
import media.watchmy.client.model.LiveClockFormat
import media.watchmy.client.model.LiveProgramme
import media.watchmy.client.model.at
import media.watchmy.client.model.categoryLine
import media.watchmy.client.model.episodeLine
import media.watchmy.client.model.liveMinutesLeft
import media.watchmy.client.model.liveStartsIn
import media.watchmy.client.model.canRemind
import media.watchmy.client.model.heroLine
import media.watchmy.client.model.menuLine
import media.watchmy.client.model.LIVE_REMINDERS_MENU_NOTE_TV
import media.watchmy.client.model.onAt
import media.watchmy.client.model.progressAt
import media.watchmy.client.model.tags
import media.watchmy.client.presentation.live.LiveGuideViewModel
import media.watchmy.client.tv.components.FocusableBox
import media.watchmy.client.tv.components.inter
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.ChannelLogo
import media.watchmy.client.ui.components.HeroFade
import media.watchmy.client.ui.components.LiveProgress
import media.watchmy.client.ui.components.ProgrammePicture
import media.watchmy.client.ui.components.ProgrammeTag
import media.watchmy.client.ui.components.WmmIcons

/** Hold OK on a programme (the guide's, or a search card's): Remind me or Remove reminder when it can have one, and Watch. */
@Composable
internal fun TvProgrammeMenu(
    channel: LiveChannel,
    programme: LiveProgramme,
    state: LiveGuideViewModel.State,
    now: Long,
    format: LiveClockFormat,
    extras: TvGuideExtras,
    onWatch: () -> Unit,
    onDismiss: () -> Unit,
) {
    val recorder = rememberTvRecordingActions()
    val recordingState by recorder.state.collectAsStateWithLifecycle()
    var choosingRecording by remember(programme.id) { mutableStateOf(false) }
    if (choosingRecording) { TvRecordingChoice(programme, recorder, onDismiss); return }
    val reminder = state.reminderFor(programme)
    val airing = programme.airingAt(now)
    val canRemind = !airing && (reminder != null || programme.canRemind(now, state.leadMinutes))
    val mark = programme.recording?.takeIf { it.state !in listOf("cancelled", "deleted") }
    TvLiveMenu(
        title = programme.title, subtitle = listOfNotNull(programme.menuLine(channel, format), mark?.label?.takeIf { recordingState.canRecord }).joinToString(" · "), logo = null,
        items = listOfNotNull(
            mark?.takeIf { recordingState.canRecord && programme.endMs > now && it.active }?.let { set ->
                // Set or under way: the menu cancels it, or only says so for someone else's.
                TvMenuItem(set.cancelAction ?: set.label, WmmIcons.Record, enabled = set.cancelAction != null) { recorder.cancel(set.id); onDismiss() }
            },
            if (recordingState.canRecord && programme.endMs > now && mark?.active != true) TvMenuItem(if (airing) "Record the rest" else if (programme.offersSeries) "Record…" else "Record", WmmIcons.Record) {
                // A film or special records straight away; a series asks which episodes.
                if (programme.offersSeries) choosingRecording = true else { recorder.record(programme); onDismiss() }
            } else null,
            if (canRemind) TvMenuItem(if (reminder != null) "Remove reminder" else "Remind me", if (reminder != null) WmmIcons.BellOff else WmmIcons.Bell) {
                onDismiss()
                if (reminder != null) extras.onForget(reminder) else extras.onRemind(programme)
            } else null,
            TvMenuItem(if (airing) "Watch ${channel.number}" else "Watch ${channel.number} now", WmmIcons.Play, enabled = channel.available, onClick = onWatch),
        ),
        note = LIVE_REMINDERS_MENU_NOTE_TV.takeIf { canRemind },
        onDismiss = onDismiss,
    )
}

/**
 * What Hold OK offers on the hero's programme, as its menu has it: Record
 * (Record the rest while it is on) only for people who may record, beside
 * the reminder's own action; a recording already set offers its Don't record
 * or Stop recording. Null when the menu has nothing but Watch.
 */
internal fun tvHoldHint(canRecord: Boolean, programme: LiveProgramme, now: Long, reminded: Boolean, canRemind: Boolean): String? {
    val mark = programme.recording?.takeIf { canRecord && it.active }
    val record = canRecord && programme.endMs > now && mark == null
    val remind = if (reminded) "Remove reminder" else "Remind me"
    return when {
        record && programme.airingAt(now) -> "Record the rest"
        record && canRemind -> if (reminded) "Record or remove reminder" else "Record or remind me"
        record -> "Record"
        canRemind -> remind
        else -> mark?.cancelAction
    }
}

/** The focused programme: its picture on the right, the channel, title, episode line, tags, times and description. */
@Composable
internal fun TvGuideHero(selection: LiveGuideViewModel.Selection?, channelOnly: LiveChannel?, now: Long, format: LiveClockFormat, modifier: Modifier = Modifier, state: LiveGuideViewModel.State? = null, canRecord: Boolean = false) {
    BoxWithConstraints(modifier.clipToBounds()) {
        val programme = selection?.programme
        val channel = channelOnly ?: selection?.channel
        if (channel != null) {
            // The picture fills the hero's height on the right and fades into the page.
            Box(Modifier.align(Alignment.TopEnd).width(maxHeight * 16f / 9f)) {
                ProgrammePicture(programme, channel, Modifier.fillMaxWidth(), preferStill = true, caption = false)
                Box(Modifier.matchParentSize().background(HeroFade))
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(.6f to Color.Transparent, 1f to WmmColors.Ground)))
                if (programme != null && (programme.still != null || programme.pictureFromTmdb && programme.picture != null)) {
                    Text(
                        "Picture: TMDB", style = inter(16.sp, FontWeight.SemiBold), color = WmmColors.TextSoft,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(18.dp).background(Color.Black.copy(alpha = .72f), RoundedCornerShape(5.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }
        Column(Modifier.padding(top = 44.dp, start = 8.dp).width(maxWidth * .58f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (channel == null) return@Column
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (channel.logo != null) ChannelLogo(channel, Modifier.width(56.dp))
                Text(channel.number, style = inter(22.sp, FontWeight.Bold), color = WmmColors.Text)
                if (state?.isFavourite(channel) == true) Icon(WmmIcons.StarFilled, contentDescription = "Favourite", tint = WmmColors.Accent, modifier = Modifier.size(22.dp))
                Text(channel.name, style = inter(22.sp), color = WmmColors.TextMuted, maxLines = 1)
                when {
                    programme == null -> Text("· Live", style = inter(22.sp, FontWeight.SemiBold), color = LiveRed)
                    programme.airingAt(now) -> Text("· On now", style = inter(22.sp, FontWeight.SemiBold), color = LiveRed)
                    format.dayName(programme.startMs, now) == "Today" -> Text("· ${liveStartsIn(now, programme.startMs)}", style = inter(22.sp), color = WmmColors.TextMuted)
                    else -> Text("· ${format.dayName(programme.startMs, now)}", style = inter(22.sp), color = WmmColors.TextMuted)
                }
                val reminder = programme?.let { state?.reminderFor(it) ?: state?.goneReminderAt(channel, it) }
                if (reminder != null && state != null) {
                    val gone = reminder.state == media.watchmy.client.model.LiveReminderState.Gone
                    Text("·", style = inter(22.sp), color = WmmColors.TextMuted)
                    Icon(if (gone) WmmIcons.BellOff else WmmIcons.BellFilled, contentDescription = null, tint = if (gone) WmmColors.TextMuted else WmmColors.Accent, modifier = Modifier.size(22.dp))
                    Text(reminder.heroLine(state.leadMinutes, format), style = inter(22.sp, FontWeight.SemiBold), color = if (gone) WmmColors.TextMuted else WmmColors.Accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (programme == null) {
                Text(channel.name, style = inter(54.sp, FontWeight.ExtraBold), color = WmmColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (channel.available) "No listings · Plays live" else "Unavailable", style = inter(22.sp), color = WmmColors.TextMuted)
                if (channel.available) TvOkHint("Watch ${channel.number}")
                return@Column
            }
            Text(programme.title, style = inter(54.sp, FontWeight.ExtraBold), color = WmmColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                programme.episodeLine()?.let { Text(it, style = inter(21.sp), color = WmmColors.TextSoft, maxLines = 1) }
                programme.tags().forEach { ProgrammeTag(it, fontSize = 15.sp, height = 26.dp) }
                programme.categoryLine()?.let { Text(it, style = inter(21.sp), color = WmmColors.TextMuted, maxLines = 1) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${format.weekday(programme.startMs)} ${format.span(programme.startMs, programme.endMs)}", style = inter(21.sp), color = WmmColors.TextSoft)
                if (programme.airingAt(now)) {
                    LiveProgress(programme.progressAt(now), Modifier.width(220.dp), height = 5.dp)
                    Text(liveMinutesLeft(now, programme.endMs), style = inter(21.sp), color = WmmColors.TextMuted)
                }
            }
            programme.description?.let { Text(it, style = inter(20.sp, lineHeight = 29.sp), color = WmmColors.TextSoft, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            if (programme.descriptionFromTmdb) Text("Description from TMDB", style = inter(16.sp), color = WmmColors.TextFaint)
            val airing = programme.airingAt(now)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!airing && state != null) Text("On ${channel.number}${if (format.dayName(programme.startMs, now) == "Today") "" else ", ${format.weekday(programme.startMs)}"} at ${format.time(programme.startMs)}", style = inter(21.sp, FontWeight.SemiBold), color = WmmColors.TextSoft)
                TvOkHint(if (airing) "Watch ${channel.number}" else "Watch ${channel.number} now")
                if (state != null) {
                    val reminder = state.reminderFor(programme)
                    val canRemind = !airing && (reminder != null || programme.canRemind(now, state.leadMinutes))
                    tvHoldHint(canRecord, programme, now, reminded = reminder != null, canRemind = canRemind)?.let { TvOkHint(it, key = "Hold OK") }
                }
            }
        }
    }
}

@Composable
internal fun TvOkHint(text: String, key: String = "OK") {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(key, style = inter(15.sp, FontWeight.Bold), color = WmmColors.Text, modifier = Modifier.border(1.dp, WmmColors.Line3, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp))
        Text(text, style = inter(21.sp, FontWeight.SemiBold), color = WmmColors.Text)
    }
}

/** Home's On now card on the TV: the picture with logo, number and LIVE, progress, then the title and minutes left. */
@Composable
internal fun TvOnNowCard(channel: LiveChannel, now: Long, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val on = channel.onAt(now)
    // The card reads as one thing; its focus state stays in the semantics it clears, so TalkBack (and the focus tests) see it.
    var isFocused by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FocusableBox(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().onFocusChanged { isFocused = it.isFocused }.clearAndSetSemantics {
                contentDescription = "${channel.number} ${channel.name}" + (on?.let { ", ${it.title}, ${liveMinutesLeft(now, it.endMs)}" } ?: ", No listings") + ", Watch live"
                focused = isFocused
            },
            cornerRadius = 12.dp,
        ) {
            Box(Modifier.fillMaxWidth().background(WmmColors.Surface2, RoundedCornerShape(12.dp))) {
                ProgrammePicture(on, channel, Modifier.fillMaxWidth())
                Row(Modifier.align(Alignment.TopStart).padding(10.dp).background(Color.Black.copy(alpha = .72f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (channel.logo != null) ChannelLogo(channel, Modifier.width(34.dp))
                    Text(channel.number, style = inter(20.sp, FontWeight.Bold), color = WmmColors.Text)
                }
                Row(Modifier.align(Alignment.TopEnd).padding(10.dp).background(Color(0xFFB42318), RoundedCornerShape(5.dp)).padding(horizontal = 8.dp, vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).background(Color.White, RoundedCornerShape(4.dp)))
                    Text("LIVE", style = inter(14.sp, FontWeight.Bold), color = Color.White)
                }
                if (on != null) LiveProgress(on.progressAt(now), Modifier.align(Alignment.BottomStart).fillMaxWidth(), height = 5.dp, track = Color.Transparent)
            }
        }
        Text(on?.title ?: channel.name, style = inter(20.sp, FontWeight.SemiBold), color = WmmColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(on?.let { liveMinutesLeft(now, it.endMs) } ?: "No listings", style = inter(17.sp), color = WmmColors.TextMuted, maxLines = 1)
    }
}
