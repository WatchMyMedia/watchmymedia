package media.watchmy.client.tv.live

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.focused
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import media.watchmy.client.model.LiveChannel
import media.watchmy.client.model.LiveClockFormat
import media.watchmy.client.model.LiveGuideFilter
import media.watchmy.client.model.LiveGuideRow
import media.watchmy.client.model.LiveProgramme
import media.watchmy.client.model.at
import media.watchmy.client.model.LiveReminder
import media.watchmy.client.model.liveFavouriteAction
import media.watchmy.client.model.liveFavouriteState
import media.watchmy.client.presentation.live.LiveGuideViewModel
import media.watchmy.client.tv.components.FocusMemory
import media.watchmy.client.tv.components.TvButton
import media.watchmy.client.tv.components.TvButtonSize
import media.watchmy.client.tv.components.TvChip
import media.watchmy.client.tv.components.inter
import media.watchmy.client.ui.TestTags
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.WmmIcons

internal val LiveRed = Color(0xFFFCA5A5)
internal val ChannelColumn = 220.dp
internal val RowHeight = 72.dp

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
    val currentRows by rememberUpdatedState(rows)
    val currentState by rememberUpdatedState(state)
    val currentMoves by rememberUpdatedState(moves)
    val currentWatch by rememberUpdatedState(onWatch)
    fun vertical(from: Int, time: Long?, step: Int): Boolean {
        val index = from + step
        val target = currentRows.getOrNull(index) ?: return false
        val at = time ?: cursor.coerceIn(currentState.windowStartMs, currentState.windowEndMs - 1)
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
    // One set for every row, made once: each action reads the guide's state when it runs, so a focus move,
    // which changes the state, hands no row a new lambda (android/AGENTS.md, "Focus that changes state").
    val actions = remember {
        GuideRowActions(
            focusProgramme = { channel, programme ->
                heroChannel = null
                cursor = maxOf(programme.startMs, currentState.windowStartMs)
                currentMoves.onFocus(channel, programme)
            },
            focusChannel = { heroChannel = it },
            watch = { currentWatch(it) },
            vertical = { from, time, step -> vertical(from, time, step) },
            edge = { channel, time, later ->
                pending = channel.id to time
                if (later) currentMoves.onLater() else currentMoves.onEarlier()
            },
            channelMenu = { channelMenu = it },
            programmeMenu = { channel, programme -> programmeMenu = channel to programme },
        )
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
        // What a row draws of the window: the same object across focus moves, which change only the selection.
        val window = remember(state.windowStartMs, state.windowEndMs, state.canGoEarlier, state.canGoLater, state.showsNow, state.listedThroughMs) {
            GuideWindow(state.windowStartMs, state.windowEndMs, state.canGoEarlier, state.canGoLater, state.showsNow, state.listedThroughMs)
        }
        val favourites = if (extras != null) state.favourites else emptySet()
        LazyColumn(Modifier.weight(1f).padding(end = 58.dp).testTag(TestTags.GUIDE), state = list, contentPadding = PaddingValues(bottom = 40.dp)) {
            itemsIndexed(rows, key = { _, row -> row.channel.id }) { index, row ->
                // The row's reminders, as a set kept until they change, so a move elsewhere leaves the row as it is.
                val reminded = remember(row.programmes, state.reminders, state.personal, extras != null) {
                    if (extras == null) emptySet() else row.programmes.filter { state.reminderFor(it) != null }.mapTo(HashSet()) { it.id }
                }
                TvGuideRow(
                    row, index, window, now, format, memory, actions,
                    favourite = row.channel.id in favourites,
                    reminded = reminded,
                    menus = extras != null,
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

internal fun cellKey(channelId: String, programmeId: String) = "guide:$channelId:$programmeId"
internal fun channelKey(channelId: String) = "guide:$channelId"
