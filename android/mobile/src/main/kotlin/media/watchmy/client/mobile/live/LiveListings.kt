package media.watchmy.client.mobile.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import media.watchmy.client.mobile.components.FilledButton
import media.watchmy.client.mobile.components.TonalButton
import media.watchmy.client.model.LiveChannel
import media.watchmy.client.model.LiveClockFormat
import media.watchmy.client.model.LiveGuideFilter
import media.watchmy.client.model.LiveGuideRow
import media.watchmy.client.ui.components.RecordingMarkBadge
import media.watchmy.client.model.recordingSpoken
import media.watchmy.client.model.LiveReminder
import media.watchmy.client.model.canRemind
import media.watchmy.client.model.dayLine
import media.watchmy.client.model.status
import media.watchmy.client.model.wasLine
import media.watchmy.client.model.LiveProgramme
import media.watchmy.client.model.categoryLine
import media.watchmy.client.model.episodeLine
import media.watchmy.client.model.liveMinutesLeft
import media.watchmy.client.model.liveNextLine
import media.watchmy.client.model.liveStartsIn
import media.watchmy.client.model.nextAt
import media.watchmy.client.model.onAt
import media.watchmy.client.model.progressAt
import media.watchmy.client.model.spokenName
import media.watchmy.client.model.tags
import media.watchmy.client.presentation.live.LiveGuideViewModel
import media.watchmy.client.ui.Inter
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.ChannelLogo
import media.watchmy.client.ui.components.ChannelNumberMark
import media.watchmy.client.ui.components.LiveProgress
import media.watchmy.client.ui.components.ProgrammePicture
import media.watchmy.client.ui.components.ProgrammeTag
import media.watchmy.client.ui.components.WmmIcons

private val LiveRed = Color(0xFFFCA5A5)

/** "Listings last updated Sat 7:40 PM and may be out of date" */
@Composable
internal fun StaleListingsNote(updatedAtMs: Long?, format: LiveClockFormat, modifier: Modifier = Modifier) {
    Row(modifier.semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Icon(WmmIcons.Clock, contentDescription = null, tint = WmmColors.TextMuted, modifier = Modifier.size(16.dp).padding(top = 1.dp))
        Text(
            updatedAtMs?.let { "Listings last updated ${format.dayTime(it)} and may be out of date" } ?: "Listings may be out of date",
            color = WmmColors.TextMuted, fontSize = 13.sp, lineHeight = 19.sp,
        )
    }
}

/** A channel's identity in a grid or list: its logo over its number, or its number and name without one. */
@Composable
internal fun ChannelCell(channel: LiveChannel, modifier: Modifier = Modifier, logoWidth: Dp = 44.dp) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)) {
        if (channel.logo != null) {
            ChannelLogo(channel, Modifier.width(logoWidth))
            Text(channel.number, color = if (channel.available) WmmColors.TextSoft else WmmColors.TextFaint, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        } else {
            Column(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(channel.number, color = if (channel.available) WmmColors.Text else WmmColors.TextFaint, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(channel.name, color = if (channel.available) WmmColors.TextMuted else WmmColors.TextFaint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Tags after a title. */
@Composable
internal fun ProgrammeTags(programme: LiveProgramme, modifier: Modifier = Modifier, small: Boolean = false) {
    Row(modifier.clearAndSetSemantics {}, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        programme.tags().forEach { ProgrammeTag(it, fontSize = if (small) 10.sp else 11.sp, height = if (small) 15.dp else 18.dp) }
    }
}

/**
 * "Watch 4.1", or "Watch 4.1 on TV" while Watch casts to [castDevice] (a TV
 * that plays Live TV, connected, with a server that lets this account cast:
 * PLAN-LIVE-TV.md §4.1); [later] adds "now", for a programme still to come.
 */
internal fun watchLabel(channel: LiveChannel, castDevice: String?, later: Boolean = false): String =
    "Watch ${channel.number}" + (if (later) " now" else "") + (if (castDevice != null) " on TV" else "")

/** Spoken as "Watch 4.1 on Living Room TV" while Watch casts. */
internal fun Modifier.watchDescription(channel: LiveChannel, castDevice: String?): Modifier =
    if (castDevice == null) this else semantics { contentDescription = "Watch ${channel.number} on $castDevice" }

/** The phone's selected programme above the grid: picture, channel, title, episode line, times and Watch. */
@Composable
internal fun ProgrammeSummary(
    selection: LiveGuideViewModel.Selection,
    now: Long,
    format: LiveClockFormat,
    onWatch: () -> Unit,
    onDetails: () -> Unit,
    modifier: Modifier = Modifier,
    reminded: Boolean = false,
    castDevice: String? = null,
) {
    val (channel, programme) = selection
    val airing = programme.airingAt(now)
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(WmmColors.Surface).border(1.dp, WmmColors.Line, RoundedCornerShape(12.dp)).padding(12.dp).semantics { contentDescription = "Selected programme" }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ProgrammePicture(programme, channel, Modifier.width(128.dp).clip(RoundedCornerShape(8.dp)))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(channel.number, color = WmmColors.Text, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text(channel.name, color = WmmColors.TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Text("·", color = WmmColors.TextMuted, fontSize = 12.sp)
                    Text(if (airing) "On now" else whenLabel(programme, now, format), color = if (airing) LiveRed else WmmColors.TextMuted, fontSize = 12.sp, maxLines = 1)
                    if (reminded) ReminderBell()
                    programme.recording?.let { RecordingMarkBadge(it) }
                }
                Text(programme.title, color = WmmColors.Text, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                    programme.episodeLine()?.let { Text(it, color = WmmColors.TextSoft, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)) }
                    ProgrammeTags(programme)
                }
                Text(format.span(programme.startMs, programme.endMs) + if (airing) " · ${liveMinutesLeft(now, programme.endMs)}" else "", color = WmmColors.TextMuted, fontSize = 12.sp)
                if (airing) LiveProgress(programme.progressAt(now), Modifier.fillMaxWidth().padding(top = 3.dp))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (airing) FilledButton(watchLabel(channel, castDevice), onWatch, Modifier.watchDescription(channel, castDevice), icon = WmmIcons.Play, enabled = channel.available)
            else TonalButton(watchLabel(channel, castDevice, later = true), onWatch, Modifier.watchDescription(channel, castDevice), icon = WmmIcons.Play, enabled = channel.available)
            TextButton(onClick = onDetails) { Text("Details", color = WmmColors.Accent, fontWeight = FontWeight.SemiBold) }
        }
    }
}

/** "Starts in 48 min" today, else the day: what a later programme says beside its channel. */
private fun whenLabel(programme: LiveProgramme, now: Long, format: LiveClockFormat): String {
    val today = format.dayName(programme.startMs, now) == "Today"
    return if (today) liveStartsIn(now, programme.startMs) else format.dayName(programme.startMs, now)
}

/**
 * Programme details (PLAN-LIVE-TV-GUIDE.md §6.1): the phone's bottom sheet and
 * the tablet's panel beside the grid. Watch 4.1 while it is on; a later one says
 * when it is on and offers Watch 4.1 now. No recording. With [extras]
 * (§6.5), the channel's star, and for a later programme Remind me beside
 * Watch when it starts more than the lead time ahead, or the reminder's
 * status with Remove; [gone] is a reminder whose programme left this slot,
 * shown in its own words until it is removed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProgrammeDetails(
    selection: LiveGuideViewModel.Selection,
    now: Long,
    format: LiveClockFormat,
    onWatch: () -> Unit,
    modifier: Modifier = Modifier,
    picture: (@Composable () -> Unit)? = null,
    extras: GuideExtras? = null,
    personal: PersonalDetails = PersonalDetails(),
    castDevice: String? = null,
) {
    val channel = selection.channel
    val gone = personal.gone?.takeIf { extras != null && personal.reminder == null }
    // A gone reminder keeps its own copy of the programme; the feed's picture went with the listing.
    val programme = gone?.let { LiveProgramme(it.programmeId, it.startMs, it.endMs, it.title, it.episodeTitle, it.season, it.episode) } ?: selection.programme
    val airing = gone == null && programme.airingAt(now)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        picture?.invoke() ?: ProgrammePicture(programme.takeIf { gone == null }, channel, Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)), preferStill = true)
        Row(Modifier.heightIn(min = 30.dp).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (channel.logo != null) ChannelLogo(channel, Modifier.width(40.dp))
            Text(channel.number, color = WmmColors.Text, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(channel.name, color = WmmColors.TextSoft, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (airing) {
                Text("·", color = WmmColors.TextSoft, fontSize = 13.sp)
                Text("On now", color = LiveRed, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
            if (extras != null) StarButton(channel, personal.favourite, extras.onStar)
        }
        Text(programme.title, color = WmmColors.Text, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp, modifier = Modifier.semantics { heading() })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            programme.episodeLine()?.let { Text(it, color = WmmColors.TextSoft, fontSize = 14.sp) }
            programme.tags().forEach { ProgrammeTag(it) }
        }
        Text(gone?.wasLine(format) ?: programme.dayLine(format), color = WmmColors.TextSoft, fontSize = 14.sp)
        if (airing) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LiveProgress(programme.progressAt(now), Modifier.weight(1f))
            Text(liveMinutesLeft(now, programme.endMs), color = WmmColors.TextMuted, fontSize = 13.sp)
        } else if (gone == null && programme.startMs > now && format.dayName(programme.startMs, now) == "Today") {
            Text(liveStartsIn(now, programme.startMs), color = WmmColors.TextMuted, fontSize = 13.sp)
        }
        if (gone == null) {
            programme.categoryLine()?.let { Text(it, color = WmmColors.TextMuted, fontSize = 13.sp) }
            programme.description?.let { Text(it, color = WmmColors.TextSoft, fontSize = 14.sp, lineHeight = 22.sp, modifier = Modifier.padding(top = 4.dp)) }
            if (programme.descriptionFromTmdb) Text("Description from TMDB", color = WmmColors.TextFaint, fontSize = 12.sp)
        }
        RecordControls(programme, Modifier.fillMaxWidth())
        Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val reminder = personal.reminder.takeIf { extras != null }
            when {
                airing -> FilledButton(watchLabel(channel, castDevice), onWatch, Modifier.fillMaxWidth().watchDescription(channel, castDevice), large = true, icon = WmmIcons.Play, enabled = channel.available)
                extras != null && gone != null -> {
                    ReminderStatusRow(gone.status(personal.leadMinutes, extras.device, format), onRemove = { extras.onForget(gone) })
                    TonalButton(watchLabel(channel, castDevice, later = true), onWatch, Modifier.fillMaxWidth().watchDescription(channel, castDevice), large = true, icon = WmmIcons.Play, enabled = channel.available)
                }
                else -> {
                    Text(onAtLine(channel, programme, now, format), color = WmmColors.Text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    if (extras != null && reminder != null) {
                        ReminderStatusRow(reminder.status(personal.leadMinutes, extras.device, format), onRemove = { extras.onForget(reminder) })
                    }
                    if (extras != null && reminder == null && programme.canRemind(now, personal.leadMinutes) && castDevice != null) {
                        // "Watch 11.1 now on TV" doesn't fit half the row on a phone, so while casting the two stack.
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            FilledButton("Remind me", { extras.onRemind(channel, programme) }, Modifier.fillMaxWidth(), large = true, icon = WmmIcons.Bell)
                            TonalButton(watchLabel(channel, castDevice, later = true), onWatch, Modifier.fillMaxWidth().watchDescription(channel, castDevice), large = true, icon = WmmIcons.Play, enabled = channel.available)
                        }
                    } else if (extras != null && reminder == null && programme.canRemind(now, personal.leadMinutes)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            FilledButton("Remind me", { extras.onRemind(channel, programme) }, Modifier.weight(1f), large = true, icon = WmmIcons.Bell)
                            TonalButton(watchLabel(channel, castDevice, later = true), onWatch, Modifier.weight(1f).watchDescription(channel, castDevice), large = true, icon = WmmIcons.Play, enabled = channel.available)
                        }
                    } else {
                        TonalButton(watchLabel(channel, castDevice, later = true), onWatch, Modifier.fillMaxWidth().watchDescription(channel, castDevice), large = true, icon = WmmIcons.Play, enabled = channel.available)
                    }
                }
            }
        }
    }
}

/** What the details know of the person's favourites and reminders for the selected programme. */
@androidx.compose.runtime.Immutable
internal data class PersonalDetails(
    val favourite: Boolean = false,
    val reminder: LiveReminder? = null,
    val gone: LiveReminder? = null,
    val leadMinutes: Int = 5,
)

/** The selected programme's favourite and reminder as [state] keeps them. */
internal fun LiveGuideViewModel.State.personalFor(selection: LiveGuideViewModel.Selection) = PersonalDetails(
    favourite = isFavourite(selection.channel),
    reminder = reminderFor(selection.programme),
    gone = goneReminderAt(selection.channel, selection.programme),
    leadMinutes = leadMinutes,
)

/** "On 4.1 at 9:00 PM", or "On 4.1, Tue at 9:00 PM" on another day. */
internal fun onAtLine(channel: LiveChannel, programme: LiveProgramme, now: Long, format: LiveClockFormat): String {
    val today = format.dayName(programme.startMs, now) == "Today"
    return "On ${channel.number}${if (today) "" else ", ${format.weekday(programme.startMs)}"} at ${format.time(programme.startMs)}"
}

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProgrammeSheet(
    selection: LiveGuideViewModel.Selection,
    now: Long,
    format: LiveClockFormat,
    onWatch: () -> Unit,
    onClose: () -> Unit,
    extras: GuideExtras? = null,
    personal: PersonalDetails = PersonalDetails(),
    castDevice: String? = null,
) {
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = WmmColors.Surface2) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
            Box {
                val gone = extras != null && personal.reminder == null && personal.gone != null
                ProgrammePicture(selection.programme.takeIf { !gone }, selection.channel, Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)), preferStill = true)
                IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).clip(RoundedCornerShape(24.dp)).background(Color.Black.copy(alpha = .55f))) {
                    Icon(WmmIcons.Close, contentDescription = "Close details", tint = WmmColors.Text)
                }
            }
            ProgrammeDetails(selection, now, format, onWatch, Modifier.padding(horizontal = 4.dp), picture = {}, extras = extras, personal = personal, castDevice = castDevice)
        }
    }
}

/** Today, Tomorrow, Wed, and Now. */
@Composable
internal fun GuideDays(state: LiveGuideViewModel.State, onDay: (Int) -> Unit, onNow: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
            state.days.forEachIndexed { index, day ->
                SegmentedButton(
                    selected = index == state.dayIndex,
                    onClick = { onDay(index) },
                    shape = SegmentedButtonDefaults.itemShape(index, state.days.size),
                    icon = {},
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = WmmColors.Accent.copy(alpha = .18f), activeContentColor = WmmColors.Accent, activeBorderColor = WmmColors.TextFaint,
                        inactiveContainerColor = Color.Transparent, inactiveContentColor = WmmColors.TextSoft, inactiveBorderColor = WmmColors.TextFaint,
                    ),
                ) { Text(day.label, fontSize = 13.sp, maxLines = 1, softWrap = false) }
            }
        }
        TonalButton("Now", onNow, compact = true)
    }
}

/** Favourites (once there is one), All channels, Movies, Sports, News, Kids: by what is on at the time the guide shows. */
@Composable
internal fun GuideFilters(filter: LiveGuideFilter, onFilter: (LiveGuideFilter) -> Unit, modifier: Modifier = Modifier, offered: List<LiveGuideFilter> = LiveGuideFilter.offered(false)) {
    Row(modifier.horizontalScroll(rememberScrollState()).semantics { contentDescription = "Show channels with" }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        offered.forEach { option ->
            FilterChip(
                selected = option == filter,
                onClick = { onFilter(option) },
                label = { Text(option.label, fontSize = 13.sp) },
                leadingIcon = if (option == LiveGuideFilter.Favourites) {
                    { Icon(if (option == filter) WmmIcons.StarFilled else WmmIcons.Star, contentDescription = null, modifier = Modifier.size(16.dp)) }
                } else null,
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = WmmColors.Accent.copy(alpha = .18f), selectedLabelColor = WmmColors.Accent,
                    labelColor = WmmColors.TextSoft, containerColor = Color.Transparent,
                    iconColor = WmmColors.TextSoft, selectedLeadingIconColor = WmmColors.Accent,
                ),
                border = FilterChipDefaults.filterChipBorder(enabled = true, selected = option == filter, borderColor = WmmColors.TextFaint, selectedBorderColor = WmmColors.Accent.copy(alpha = .18f)),
            )
        }
    }
}

/** The grid's time header: earlier, the half hours, later. */
@Composable
internal fun GuideTimeHeader(state: LiveGuideViewModel.State, format: LiveClockFormat, onEarlier: () -> Unit, onLater: () -> Unit, modifier: Modifier = Modifier, channelWidth: Dp = 84.dp) {
    Row(modifier.fillMaxWidth().height(44.dp).background(WmmColors.Surface), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(channelWidth).fillMaxHeight(), contentAlignment = Alignment.Center) {
            IconButton(onClick = onEarlier, enabled = state.canGoEarlier) {
                Icon(WmmIcons.ChevronLeft, contentDescription = "Earlier, from ${format.time(state.windowStartMs - 30 * 60_000L)}", tint = if (state.canGoEarlier) WmmColors.Text else WmmColors.TextFaint)
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            Row(Modifier.fillMaxSize()) {
                state.slots.forEach { slot ->
                    Box(Modifier.weight(1f).fillMaxHeight().border(width = 0.5.dp, color = WmmColors.Line), contentAlignment = Alignment.CenterStart) {
                        Text(format.time(slot), color = WmmColors.TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 10.dp))
                    }
                }
            }
            IconButton(onClick = onLater, enabled = state.canGoLater, modifier = Modifier.align(Alignment.CenterEnd).background(WmmColors.Surface)) {
                Icon(WmmIcons.ChevronRight, contentDescription = "Later, from ${format.time(state.windowStartMs + 30 * 60_000L)}", tint = if (state.canGoLater) WmmColors.Text else WmmColors.TextFaint)
            }
        }
    }
}

/**
 * One channel's track across the window: text cells sized by duration, gaps
 * left as gaps, the progress of what is on now, the end of listings, or "No
 * listings" for a channel the feed doesn't list (which still plays).
 */
@Composable
internal fun GuideRow(
    row: LiveGuideRow,
    state: LiveGuideViewModel.State,
    now: Long,
    format: LiveClockFormat,
    onPick: (LiveChannel, LiveProgramme) -> Unit,
    onWatch: (LiveChannel) -> Unit,
    modifier: Modifier = Modifier,
    channelWidth: Dp = 84.dp,
    height: Dp = 64.dp,
    extras: GuideExtras? = null,
) {
    val channel = row.channel
    val favourite = extras != null && state.isFavourite(channel)
    Row(modifier.fillMaxWidth().height(height).border(0.5.dp, WmmColors.Line)) {
        // The star column: the channel's column grows by its width (the boards' choice).
        if (extras != null) {
            if (channel.available || row.listings) StarCell(channel, favourite, extras.onStar) else StarCellNone()
        }
        ChannelCell(
            channel,
            Modifier.width(channelWidth).fillMaxHeight().background(WmmColors.Surface).clickable(enabled = channel.available, role = Role.Button) { onWatch(channel) }
                .semantics { contentDescription = "Watch ${channel.number} ${channel.name}" + (row.programmes.firstOrNull { it.airingAt(now) }?.let { ", ${it.title}" } ?: if (!row.listings) ", no listings" else "") + if (favourite) ", favourite" else "" }
                .padding(horizontal = 8.dp),
        )
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
            val span = (state.windowEndMs - state.windowStartMs).toFloat()
            fun x(ms: Long) = maxWidth * ((ms.coerceIn(state.windowStartMs, state.windowEndMs) - state.windowStartMs) / span)
            when {
                !channel.available -> GuideCellText("Unavailable", channel.reason?.let { "Not in the tuner’s last channel list".takeIf { _ -> it == "unavailable" } }, Modifier.fillMaxSize().background(WmmColors.Ground))
                !row.listings -> GuideCellText("No listings", null, Modifier.fillMaxSize().background(Color(0xFF131315)).clickable(role = Role.Button) { onWatch(channel) }.semantics { contentDescription = "Watch ${channel.number}, no listings" })
                else -> {
                    row.programmes.forEach { programme ->
                        val start = maxOf(programme.startMs, state.windowStartMs)
                        val end = minOf(programme.endMs, state.windowEndMs)
                        GuideCell(
                            programme, now, format,
                            selected = state.selection?.programme?.id == programme.id,
                            clippedStart = programme.startMs < state.windowStartMs,
                            reminded = extras != null && state.reminderFor(programme) != null,
                            onClick = { onPick(channel, programme) },
                            modifier = Modifier.offset(x = x(start)).width(x(end) - x(start)).fillMaxHeight(),
                        )
                    }
                    val listedThrough = state.listedThroughMs
                    val lastEnd = row.programmes.lastOrNull()?.endMs ?: listedThrough
                    if (listedThrough != null && lastEnd != null && lastEnd < state.windowEndMs && listedThrough < state.windowEndMs) {
                        val start = maxOf(lastEnd, state.windowStartMs)
                        GuideCellText("No listings after ${format.dayHour(listedThrough)}", null, Modifier.offset(x = x(start)).width(x(state.windowEndMs) - x(start)).fillMaxHeight().background(WmmColors.Ground))
                    }
                }
            }
            if (state.showsNow) Box(Modifier.offset(x = x(now) - 1.dp).width(2.dp).fillMaxHeight().background(WmmColors.Accent))
        }
    }
}

@Composable
private fun GuideCell(programme: LiveProgramme, now: Long, format: LiveClockFormat, selected: Boolean, clippedStart: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, reminded: Boolean = false) {
    val airing = programme.airingAt(now)
    Box(
        modifier.border(0.5.dp, WmmColors.Line).background(if (airing) Color(0xFF1E1E21) else WmmColors.Surface)
            .then(if (selected) Modifier.border(2.dp, WmmColors.Accent) else Modifier)
            .clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics { contentDescription = programme.spokenName(format) + (if (reminded) ", reminder set" else "") + programme.recordingSpoken() },
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                programme.recording?.let { RecordingMarkBadge(it) }
                Text((if (clippedStart) "‹ " else "") + programme.title, color = WmmColors.Text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                ProgrammeTags(programme, small = true)
                if (reminded) ReminderBell(size = 12.dp)
            }
            Text(programme.episodeLine() ?: "${format.shortTime(programme.startMs)}–${format.shortTime(programme.endMs)}", color = WmmColors.TextMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (airing) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(programme.progressAt(now)).height(3.dp).background(WmmColors.Accent))
    }
}

@Composable
private fun GuideCellText(title: String, subtitle: String?, modifier: Modifier = Modifier) {
    Column(modifier.border(0.5.dp, WmmColors.Line).padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically)) {
        Text(title, color = WmmColors.TextMuted, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        subtitle?.let { Text(it, color = WmmColors.TextFaint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

/**
 * A channel with what is on (the watch screen's and player panel's On now, and
 * the tablet's channel list): the picture with the logo and number over it, the
 * title, minutes left, Next, and progress. Without listings, number and name.
 */
@Composable
internal fun OnNowRow(
    channel: LiveChannel,
    now: Long,
    format: LiveClockFormat,
    watching: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    pictureWidth: Dp = 108.dp,
    watchingLabel: String = "Watching",
    description: String? = null,
) {
    val on = channel.onAt(now)
    val next = channel.nextAt(now)
    Row(
        modifier.fillMaxWidth().background(if (watching) WmmColors.Surface2 else Color.Transparent)
            .then(if (watching) Modifier.border(width = 0.dp, color = Color.Transparent) else Modifier)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { description?.let { contentDescription = it } }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        if (watching) Box(Modifier.width(3.dp).height(48.dp).background(WmmColors.Accent))
        Box(Modifier.width(pictureWidth).clip(RoundedCornerShape(6.dp))) {
            ProgrammePicture(on, channel, Modifier.fillMaxWidth())
            if (on != null) ChannelBadge(channel, Modifier.align(Alignment.TopStart).padding(5.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(on?.title ?: channel.name, color = if (channel.available) WmmColors.Text else WmmColors.TextFaint, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val detail = when {
                !channel.available -> null
                on == null -> "No listings"
                else -> listOfNotNull(liveMinutesLeft(now, on.endMs), next?.let { "Next ${format.shortTime(it.startMs)} ${it.title}" }).joinToString(" · ")
            }
            detail?.let { Text(it, color = WmmColors.TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            if (on != null) LiveProgress(on.progressAt(now), Modifier.fillMaxWidth().padding(top = 2.dp))
        }
        if (watching) Text(watchingLabel, color = WmmColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** The logo and number on a picture's corner. */
@Composable
internal fun ChannelBadge(channel: LiveChannel, modifier: Modifier = Modifier, numberSize: Int = 11) {
    Row(modifier.background(Color.Black.copy(alpha = .72f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (channel.logo != null) ChannelLogo(channel, Modifier.width((numberSize * 2).dp))
        Text(channel.number, color = WmmColors.Text, fontWeight = FontWeight.Bold, fontSize = numberSize.sp)
        if (channel.logo == null) Text(channel.name, color = WmmColors.TextSoft, fontSize = (numberSize - 1).sp, maxLines = 1)
    }
}

/**
 * What is on under the video and in the landscape player's card: the picture,
 * title and tags, episode line, times, progress and minutes left, and Next.
 */
@Composable
internal fun LiveProgrammeBanner(channel: LiveChannel, now: Long, format: LiveClockFormat, modifier: Modifier = Modifier, pictureWidth: Dp = 124.dp) {
    val on = channel.onAt(now)
    val next = channel.nextAt(now)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ProgrammePicture(on, channel, Modifier.width(pictureWidth).clip(RoundedCornerShape(8.dp)))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                if (on == null) {
                    Text(channel.name, color = WmmColors.Text, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("No listings for this channel", color = WmmColors.TextMuted, fontSize = 13.sp)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(on.title, color = WmmColors.Text, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        ProgrammeTags(on)
                    }
                    on.episodeLine()?.let { Text(it, color = WmmColors.TextSoft, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    Text("${format.span(on.startMs, on.endMs)} · ${liveMinutesLeft(now, on.endMs)}", color = WmmColors.TextMuted, fontSize = 12.sp)
                    LiveProgress(on.progressAt(now), Modifier.fillMaxWidth().padding(top = 3.dp))
                }
            }
        }
        next?.let { Text(liveNextLine(it, format), color = WmmColors.TextMuted, fontSize = 13.sp) }
    }
}

/** The phone's top line in landscape: "Valley Bake-Off · 48 min left". */
internal fun onNowLine(channel: LiveChannel, now: Long): String? = channel.onAt(now)?.let { "${it.title} · ${liveMinutesLeft(now, it.endMs)}" }

/** Home's On now card: the programme's picture with the logo and number, LIVE, progress, title and minutes left. */
@Composable
internal fun OnNowCard(channel: LiveChannel, now: Long, onWatch: () -> Unit, modifier: Modifier = Modifier, outlined: Boolean = false) {
    val on = channel.onAt(now)
    Column(modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onWatch).semantics(mergeDescendants = true) {
        contentDescription = "Watch ${channel.number} ${channel.name}" + (on?.let { ", ${it.title}, ${liveMinutesLeft(now, it.endMs)}" } ?: "")
    }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // The gold ring marks the leading card of the tablet Home's Live row (TabHomeRow.dc.html).
        val ring = if (outlined) Modifier.border(2.dp, WmmColors.Accent, RoundedCornerShape(8.dp)) else Modifier
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).then(ring)) {
            ProgrammePicture(on, channel, Modifier.fillMaxWidth())
            ChannelBadge(channel, Modifier.align(Alignment.TopStart).padding(8.dp), numberSize = 13)
            Row(Modifier.align(Alignment.TopEnd).padding(8.dp).background(Color(0xFFB42318), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(5.dp).background(Color.White, RoundedCornerShape(3.dp)))
                Text("LIVE", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            if (on != null) LiveProgress(on.progressAt(now), Modifier.align(Alignment.BottomStart).fillMaxWidth(), height = 4.dp, track = Color.Transparent)
        }
        Text(on?.title ?: channel.name, color = WmmColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(on?.let { liveMinutesLeft(now, it.endMs) } ?: "No listings", color = WmmColors.TextMuted, fontSize = 12.sp, maxLines = 1)
    }
}
