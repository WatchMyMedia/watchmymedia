package media.watchmy.client.tv.live

import android.view.KeyEvent as AndroidKey
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.focused
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import media.watchmy.client.model.LiveChannel
import media.watchmy.client.model.LiveClockFormat
import media.watchmy.client.model.LiveGuideFilter
import media.watchmy.client.model.LiveGuideRow
import media.watchmy.client.model.LiveProgramme
import media.watchmy.client.model.at
import media.watchmy.client.model.categoryLine
import media.watchmy.client.model.episodeLine
import media.watchmy.client.model.liveMinutesLeft
import media.watchmy.client.model.liveStartsIn
import media.watchmy.client.ui.components.RecordingMarkBadge
import media.watchmy.client.model.recordingSpoken
import media.watchmy.client.model.LiveReminder
import media.watchmy.client.model.canRemind
import media.watchmy.client.model.heroLine
import media.watchmy.client.model.liveFavouriteAction
import media.watchmy.client.model.liveFavouriteState
import media.watchmy.client.model.menuLine
import media.watchmy.client.model.LIVE_REMINDERS_MENU_NOTE_TV
import media.watchmy.client.model.onAt
import media.watchmy.client.model.progressAt
import media.watchmy.client.model.spokenName
import media.watchmy.client.model.tags
import media.watchmy.client.presentation.live.LiveGuideViewModel
import media.watchmy.client.tv.components.FocusMemory
import media.watchmy.client.tv.components.FocusableBox
import media.watchmy.client.tv.components.TvButton
import media.watchmy.client.tv.components.TvButtonSize
import media.watchmy.client.tv.components.TvChip
import media.watchmy.client.tv.components.focusMemory
import media.watchmy.client.tv.components.inter
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.ChannelLogo
import media.watchmy.client.ui.components.HeroFade
import media.watchmy.client.ui.components.LiveProgress
import media.watchmy.client.ui.components.ProgrammePicture
import media.watchmy.client.ui.components.ProgrammeTag
import media.watchmy.client.ui.components.WmmIcons

private val LiveRed = Color(0xFFFCA5A5)
private val ChannelColumn = 220.dp
private val RowHeight = 72.dp

/**
 * What the TV guide can do with the person's favourites and reminders
 * (PLAN-LIVE-TV-GUIDE.md §6.5, android-tv/LiveGuideExtras): Hold OK on a
 * channel stars it, on a programme opens Remind me and Watch.
 */
internal class TvGuideExtras(
    val onStar: (LiveChannel) -> Unit,
    val onRemind: (LiveProgramme) -> Unit,
    val onForget: (LiveReminder) -> Unit,
    val onReminders: () -> Unit,
    val onRecordings: () -> Unit = {},
    val canRecord: Boolean = false,
)

/** How the TV guide moves: through time, between days, and by category. */
internal class TvGuideMoves(
    val onEarlier: () -> Unit,
    val onLater: () -> Unit,
    val onDay: (Int) -> Unit,
    val onFilter: (LiveGuideFilter) -> Unit,
    val onFocus: (LiveChannel, LiveProgramme) -> Unit,
)

/**
 * LiveGuide on Android TV with listings: the focused programme's hero above,
 * then filters, days and Go to channel, then two hours across. Left and Right
 * past the window's edge move it half an hour; ▲ ▼ move between channels; OK
 * watches the channel. A channel without listings still plays from its cell.
 */
@Composable
internal fun LiveTvGuide(
    state: LiveGuideViewModel.State,
    rows: List<LiveGuideRow>,
    now: Long,
    format: LiveClockFormat,
    memory: FocusMemory,
    moves: TvGuideMoves,
    onWatch: (LiveChannel) -> Unit,
    onGoTo: () -> Unit,
    modifier: Modifier = Modifier,
    extras: TvGuideExtras? = null,
) {
    var heroChannel by remember { mutableStateOf<LiveChannel?>(null) }
    var channelMenu by remember { mutableStateOf<LiveChannel?>(null) }
    var programmeMenu by remember { mutableStateOf<Pair<LiveChannel, LiveProgramme>?>(null) }
    var pending by remember { mutableStateOf<Pair<String, Long>?>(null) }
    // The time ▲ and ▼ keep to, as cable guides do: where the focused programme starts in view.
    var cursor by remember { mutableLongStateOf(0L) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    /**
     * ▲ ▼ go to the row above or below, to the programme on at [time]. Compose's
     * own search weighs the distance between centres, so it would pass over a
     * long programme or a channel without listings for a short cell further on.
     */
    fun vertical(from: Int, time: Long?, step: Int): Boolean {
        val index = from + step
        val target = rows.getOrNull(index) ?: return false
        val at = time ?: cursor.coerceIn(state.windowStartMs, state.windowEndMs - 1)
        val programme = target.programmes.at(at) ?: target.programmes.minByOrNull { minOf(kotlin.math.abs(it.startMs - at), kotlin.math.abs(it.endMs - at)) }
        val key = programme?.let { cellKey(target.channel.id, it.id) } ?: channelKey(target.channel.id)
        scope.launch {
            val visible = list.layoutInfo.visibleItemsInfo.map { it.index }
            if (visible.isNotEmpty() && index !in visible.drop(1).dropLast(1)) {
                list.scrollToItem(if (step > 0) (index - visible.size + 2).coerceAtLeast(0) else index)
            }
            withFrameNanos { }
            runCatching { memory.requester(key).requestFocus() }
        }
        return true
    }
    LaunchedEffect(state.windowStartMs, rows, state.loading) {
        val (channelId, time) = pending ?: return@LaunchedEffect
        val programme = rows.firstOrNull { it.channel.id == channelId }?.programmes?.at(time)
        if (programme == null) {
            // Still loading the half hour that came into view: try again when it has.
            if (!state.loading) pending = null
            return@LaunchedEffect
        }
        pending = null
        withFrameNanos { }
        memory.requester(cellKey(channelId, programme.id)).requestFocus()
    }
    Column(modifier.fillMaxSize()) {
        TvGuideHero(state.selection.takeIf { heroChannel == null }, heroChannel, now, format, Modifier.fillMaxWidth().height(380.dp), state = state.takeIf { extras != null }, canRecord = extras?.canRecord == true)
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 58.dp, top = 8.dp, bottom = 20.dp).focusGroup(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            val filters = if (extras != null) state.filters else LiveGuideFilter.offered(false)
            filters.forEach { option ->
                TvChip(option.label, option == state.shownFilter, onClick = { moves.onFilter(option) }, leadingIcon = if (option == LiveGuideFilter.Favourites) WmmIcons.StarFilled else null)
            }
            Box(Modifier.width(10.dp))
            state.days.forEachIndexed { index, day -> TvChip(day.label, index == state.dayIndex, onClick = { moves.onDay(index) }) }
            Box(Modifier.weight(1f))
            if (extras?.canRecord == true) TvChip("Recordings", false, onClick = extras.onRecordings, leadingIcon = WmmIcons.Record, role = androidx.compose.ui.semantics.Role.Button)
            if (extras != null) TvChip("Reminders", false, onClick = extras.onReminders, leadingIcon = WmmIcons.Bell, role = androidx.compose.ui.semantics.Role.Button)
            TvButton("Go to channel", onGoTo, size = TvButtonSize.Medium)
        }
        if (state.stale) Text(
            state.updatedAtMs?.let { "Listings last updated ${format.dayTime(it)} and may be out of date" } ?: "Listings may be out of date",
            style = inter(18.sp), color = WmmColors.TextMuted, modifier = Modifier.padding(start = 8.dp, bottom = 12.dp),
        )
        TvTimeHeader(state, format)
        LazyColumn(Modifier.weight(1f).padding(end = 58.dp), state = list, contentPadding = PaddingValues(bottom = 40.dp)) {
            itemsIndexed(rows, key = { _, row -> row.channel.id }) { index, row ->
                TvGuideRow(
                    row, state, now, format, memory,
                    onFocusProgramme = { channel, programme ->
                        heroChannel = null
                        cursor = maxOf(programme.startMs, state.windowStartMs)
                        moves.onFocus(channel, programme)
                    },
                    onVertical = { time, step -> vertical(index, time, step) },
                    onFocusChannel = { heroChannel = it },
                    onWatch = onWatch,
                    extras = extras,
                    onChannelMenu = { channelMenu = it },
                    onProgrammeMenu = { channel, programme -> programmeMenu = channel to programme },
                    onEdge = { channel, time, later ->
                        pending = channel.id to time
                        if (later) moves.onLater() else moves.onEarlier()
                    },
                )
            }
            if (rows.isEmpty()) item("none") {
                Text(if (state.loading) "Loading listings…" else "No channels show this. Choose All channels to see every channel.", style = inter(21.sp), color = WmmColors.TextMuted, modifier = Modifier.padding(24.dp))
            }
        }
    }
    if (extras != null) {
        channelMenu?.let { channel ->
            val favourite = state.isFavourite(channel)
            TvLiveMenu(
                title = "${channel.number} ${channel.name}", subtitle = liveFavouriteState(favourite), logo = channel,
                items = listOf(
                    TvMenuItem(liveFavouriteAction(favourite), if (favourite) WmmIcons.StarFilled else WmmIcons.Star) { channelMenu = null; extras.onStar(channel) },
                    TvMenuItem("Watch ${channel.number}", WmmIcons.Play, enabled = channel.available) { channelMenu = null; onWatch(channel) },
                ),
                onDismiss = { channelMenu = null },
            )
        }
        programmeMenu?.let { (channel, programme) ->
            TvProgrammeMenu(channel, programme, state, now, format, extras, onWatch = { programmeMenu = null; onWatch(channel) }, onDismiss = { programmeMenu = null })
        }
    }
}

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

internal fun cellKey(channelId: String, programmeId: String) = "guide:$channelId:$programmeId"
internal fun channelKey(channelId: String) = "guide:$channelId"

/** The focused programme: its picture on the right, the channel, title, episode line, tags, times and description. */
@Composable
private fun TvGuideHero(selection: LiveGuideViewModel.Selection?, channelOnly: LiveChannel?, now: Long, format: LiveClockFormat, modifier: Modifier = Modifier, state: LiveGuideViewModel.State? = null, canRecord: Boolean = false) {
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

@Composable
private fun TvTimeHeader(state: LiveGuideViewModel.State, format: LiveClockFormat, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(end = 58.dp).height(48.dp).background(WmmColors.Surface), verticalAlignment = Alignment.CenterVertically) {
        Text(state.days.getOrNull(state.dayIndex)?.label.orEmpty(), style = inter(18.sp, FontWeight.SemiBold), color = WmmColors.TextSoft, modifier = Modifier.width(ChannelColumn).padding(start = 16.dp))
        state.slots.forEach { slot ->
            Box(Modifier.weight(1f).fillMaxHeight().border(0.5.dp, WmmColors.Line), contentAlignment = Alignment.CenterStart) {
                Text(format.time(slot), style = inter(18.sp), color = WmmColors.TextMuted, modifier = Modifier.padding(horizontal = 14.dp))
            }
        }
    }
}

@Composable
private fun TvGuideRow(
    row: LiveGuideRow,
    state: LiveGuideViewModel.State,
    now: Long,
    format: LiveClockFormat,
    memory: FocusMemory,
    onFocusProgramme: (LiveChannel, LiveProgramme) -> Unit,
    onFocusChannel: (LiveChannel) -> Unit,
    onWatch: (LiveChannel) -> Unit,
    onVertical: (time: Long?, step: Int) -> Boolean,
    onEdge: (LiveChannel, Long, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    extras: TvGuideExtras? = null,
    onChannelMenu: (LiveChannel) -> Unit = {},
    onProgrammeMenu: (LiveChannel, LiveProgramme) -> Unit = { _, _ -> },
) {
    val channel = row.channel
    val favourite = extras != null && state.isFavourite(channel)
    fun step(code: Int) = when (code) {
        AndroidKey.KEYCODE_DPAD_DOWN -> 1
        AndroidKey.KEYCODE_DPAD_UP -> -1
        else -> 0
    }
    Row(modifier.fillMaxWidth().height(RowHeight).border(0.5.dp, WmmColors.Line)) {
        val channelCell: @Composable (Boolean) -> Unit = { focused ->
            Row(Modifier.fillMaxSize().background(if (focused) WmmColors.Surface3 else WmmColors.Surface).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(channel.number, style = inter(24.sp, FontWeight.Bold), color = if (channel.available) WmmColors.Text else WmmColors.TextFaint)
                        if (favourite) Icon(WmmIcons.StarFilled, contentDescription = null, tint = WmmColors.Accent, modifier = Modifier.size(18.dp))
                    }
                    Text(channel.name, style = inter(15.sp), color = WmmColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (channel.logo != null) ChannelLogo(channel, Modifier.width(52.dp))
            }
        }
        if (extras != null && (channel.available || row.listings)) {
            // The channel's own cell: OK watches it, Hold OK stars it.
            FocusableBox(
                onClick = { if (channel.available) onWatch(channel) },
                onLongClick = { onChannelMenu(channel) },
                modifier = Modifier.width(ChannelColumn).fillMaxHeight().focusMemory(memory, "guide-channel:${channel.id}")
                    .onFocusChanged {
                        if (!it.isFocused) return@onFocusChanged
                        // The hero shows what is on the channel, or the channel alone without listings.
                        val on = row.programmes.at(now.coerceIn(state.windowStartMs, state.windowEndMs - 1)) ?: row.programmes.firstOrNull()
                        if (on != null) onFocusProgramme(channel, on) else onFocusChannel(channel)
                    }
                    .onPreviewKeyEvent { event -> event.type == KeyEventType.KeyDown && step(event.nativeKeyEvent.keyCode) != 0 && onVertical(null, step(event.nativeKeyEvent.keyCode)) }
                    .clearAndSetSemantics { contentDescription = "Watch ${channel.number} ${channel.name}" + (if (favourite) ", favourite" else "") + (row.programmes.firstOrNull { it.airingAt(now) }?.let { ", ${it.title}" } ?: "") },
                cornerRadius = 0.dp, focusedScale = 1f, ringOffset = (-3).dp,
            ) { focused -> channelCell(focused) }
        } else Box(Modifier.width(ChannelColumn).fillMaxHeight()) { channelCell(false) }
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
            val span = (state.windowEndMs - state.windowStartMs).toFloat()
            fun x(ms: Long): Dp = maxWidth * ((ms.coerceIn(state.windowStartMs, state.windowEndMs) - state.windowStartMs) / span)
            if (!row.listings || !channel.available) {
                FocusableBox(
                    onClick = { if (channel.available) onWatch(channel) else onFocusChannel(channel) },
                    modifier = Modifier.fillMaxSize().focusMemory(memory, channelKey(channel.id)).onFocusChanged { if (it.isFocused) onFocusChannel(channel) }
                        .onPreviewKeyEvent { event -> event.type == KeyEventType.KeyDown && step(event.nativeKeyEvent.keyCode) != 0 && onVertical(null, step(event.nativeKeyEvent.keyCode)) }
                        .clearAndSetSemantics { contentDescription = "${channel.number} ${channel.name}, ${if (channel.available) "No listings, Watch live" else "Unavailable"}" },
                    cornerRadius = 0.dp, focusedScale = 1f, ringOffset = (-3).dp,
                ) { focused ->
                    Column(Modifier.fillMaxSize().background(if (focused) WmmColors.Surface3 else Color(0xFF131315)).padding(horizontal = 14.dp), verticalArrangement = Arrangement.Center) {
                        Text(if (channel.available) "No listings" else "Unavailable", style = inter(19.sp, FontWeight.SemiBold), color = WmmColors.TextMuted)
                        Text(if (channel.available) "Plays live" else "Not in the tuner’s last channel list", style = inter(15.sp), color = WmmColors.TextFaint)
                    }
                }
            } else {
                row.programmes.forEachIndexed { index, programme ->
                    val start = maxOf(programme.startMs, state.windowStartMs)
                    val end = minOf(programme.endMs, state.windowEndMs)
                    TvGuideCell(
                        programme, now, format, clippedStart = programme.startMs < state.windowStartMs,
                        onClick = { onWatch(channel) },
                        onFocus = { onFocusProgramme(channel, programme) },
                        onLongClick = if (extras != null) ({ onProgrammeMenu(channel, programme) }) else null,
                        reminded = extras != null && state.reminderFor(programme) != null,
                        modifier = Modifier.offset(x = x(start)).width(x(end) - x(start)).fillMaxHeight()
                            .focusMemory(memory, cellKey(channel.id, programme.id))
                            .onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                val vertical = step(event.nativeKeyEvent.keyCode)
                                if (vertical != 0) return@onPreviewKeyEvent onVertical(maxOf(programme.startMs, state.windowStartMs), vertical)
                                // At the window's edge, Left and Right move it half an hour and keep focus on this channel.
                                val edge = when (event.nativeKeyEvent.keyCode) {
                                    AndroidKey.KEYCODE_DPAD_RIGHT -> (index == row.programmes.lastIndex || programme.endMs >= state.windowEndMs) && state.canGoLater
                                    AndroidKey.KEYCODE_DPAD_LEFT -> index == 0 && state.canGoEarlier
                                    else -> false
                                }
                                if (edge) {
                                    val later = event.nativeKeyEvent.keyCode == AndroidKey.KEYCODE_DPAD_RIGHT
                                    onEdge(channel, if (later) state.windowEndMs else state.windowStartMs - 1, later)
                                }
                                edge
                            },
                    )
                }
                val listedThrough = state.listedThroughMs
                val lastEnd = row.programmes.lastOrNull()?.endMs ?: listedThrough
                if (listedThrough != null && lastEnd != null && lastEnd < state.windowEndMs && listedThrough < state.windowEndMs) {
                    val start = maxOf(lastEnd, state.windowStartMs)
                    Box(Modifier.offset(x = x(start)).width(x(state.windowEndMs) - x(start)).fillMaxHeight().background(WmmColors.Ground).padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
                        Text("No listings after ${format.dayHour(listedThrough)}", style = inter(17.sp), color = WmmColors.TextMuted, maxLines = 2)
                    }
                }
            }
            if (state.showsNow) Box(Modifier.offset(x = x(now) - 1.dp).width(3.dp).fillMaxHeight().background(WmmColors.Accent))
        }
    }
}

@Composable
private fun TvGuideCell(
    programme: LiveProgramme,
    now: Long,
    format: LiveClockFormat,
    clippedStart: Boolean,
    onClick: () -> Unit,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    reminded: Boolean = false,
) {
    val airing = programme.airingAt(now)
    FocusableBox(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.onFocusChanged { if (it.isFocused) onFocus() }.clearAndSetSemantics { contentDescription = programme.spokenName(format) + (if (reminded) ", reminder set" else "") + programme.recordingSpoken() },
        cornerRadius = 0.dp, focusedScale = 1f, ringOffset = (-3).dp,
    ) { focused ->
        Box(Modifier.fillMaxSize().background(if (focused) WmmColors.Surface3 else if (airing) Color(0xFF1E1E21) else WmmColors.Surface).border(0.5.dp, WmmColors.Line)) {
            Column(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    programme.recording?.let { RecordingMarkBadge(it, size = 14.dp, textSize = 14.sp) }
                    if (reminded) Icon(WmmIcons.BellFilled, contentDescription = null, tint = WmmColors.Accent, modifier = Modifier.size(17.dp))
                    Text((if (clippedStart) "‹ " else "") + programme.title, style = inter(19.sp, FontWeight.SemiBold), color = WmmColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    programme.tags().forEach { ProgrammeTag(it, fontSize = 12.sp, height = 20.dp) }
                    Text(programme.episodeLine() ?: "${format.shortTime(programme.startMs)}–${format.shortTime(programme.endMs)}", style = inter(15.sp), color = WmmColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (airing) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(programme.progressAt(now)).height(3.dp).background(WmmColors.Accent))
        }
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
