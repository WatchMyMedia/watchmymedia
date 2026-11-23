package media.watchmy.client.tv.live

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import media.watchmy.client.model.LiveChannel
import media.watchmy.client.model.LiveClockFormat
import media.watchmy.client.model.LiveGuideFilter
import media.watchmy.client.model.LiveGuideRow
import media.watchmy.client.model.LiveProgramme
import media.watchmy.client.model.LiveReminder
import media.watchmy.client.model.at
import media.watchmy.client.presentation.live.LiveGuideViewModel
import media.watchmy.client.tv.components.FocusMemory
import media.watchmy.client.tv.components.TvPalette
import media.watchmy.client.tv.components.TvType
import media.watchmy.client.tv.components.tryFocus
import media.watchmy.client.ui.TestTags
import media.watchmy.client.ui.WmmColors

/**
 * What the TV guide can do with the person's favourites and reminders,
 * and whether they may record: all of it is among a programme's choices.
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
 * The guide's hero and grid, under the screen's tabs: the focused
 * programme above, then two hours across six channels with the seventh
 * peeking. ◀ ▶ move through time, and at the window's edge move it half an
 * hour; ▲ ▼ go to the programme on at the same time on the channel above
 * or below, and ▲ from the first channel goes to the Guide tab
 * ([guideTab]). OK on what is on now watches the channel; OK on a later
 * programme, and Hold OK on any, opens its choices ([onChoices]).
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
    onChoices: (LiveChannel, LiveProgramme) -> Unit,
    guideTab: FocusRequester,
    modifier: Modifier = Modifier,
    personal: Boolean = false,
    canRecord: Boolean = false,
) {
    var heroChannel by remember { mutableStateOf<LiveChannel?>(null) }
    var pending by remember { mutableStateOf<Pair<String, Long>?>(null) }
    // The time ▲ and ▼ keep to, as cable guides do: where the focused programme starts in view.
    var cursor by remember { mutableLongStateOf(0L) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val currentRows by rememberUpdatedState(rows)
    val currentState by rememberUpdatedState(state)
    val currentMoves by rememberUpdatedState(moves)
    val currentWatch by rememberUpdatedState(onWatch)
    val currentChoices by rememberUpdatedState(onChoices)
    val currentNow by rememberUpdatedState(now)

    /**
     * ▲ ▼ go to the row above or below, to the programme on at [time]. Compose's
     * own search weighs the distance between centres, so it would pass over a
     * long programme or a channel without listings for a short cell further on.
     */
    fun vertical(from: Int, time: Long?, step: Int): Boolean {
        val index = from + step
        if (index < 0) return guideTab.tryFocus()
        val target = currentRows.getOrNull(index) ?: return false
        val at = time ?: cursor.coerceIn(currentState.windowStartMs, currentState.windowEndMs - 1)
        val programme = target.programmes.at(at) ?: target.programmes.minByOrNull { minOf(kotlin.math.abs(it.startMs - at), kotlin.math.abs(it.endMs - at)) }
        val key = programme?.let { cellKey(target.channel.id, it.id) } ?: channelKey(target.channel.id)
        scope.launch {
            val visible = list.layoutInfo.visibleItemsInfo.map { it.index }
            // The last row showing only peeks: moving onto it scrolls, so the row focused is always whole.
            if (visible.isNotEmpty() && index !in visible.drop(1).dropLast(1)) {
                list.scrollToItem(if (step > 0) (index - visible.size + 2).coerceAtLeast(0) else index)
            }
            withFrameNanos { }
            memory.requester(key).tryFocus()
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
            choose = { channel, programme ->
                if (programme.airingAt(currentNow) && channel.available) currentWatch(channel) else currentChoices(channel, programme)
            },
            watch = { currentWatch(it) },
            vertical = { from, time, step -> vertical(from, time, step) },
            edge = { channel, time, later ->
                pending = channel.id to time
                if (later) currentMoves.onLater() else currentMoves.onEarlier()
            },
            programmeMenu = { channel, programme -> currentChoices(channel, programme) },
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
        memory.requester(cellKey(channelId, programme.id)).tryFocus()
    }
    Column(modifier.fillMaxSize()) {
        TvGuideHero(
            state.selection.takeIf { heroChannel == null }, heroChannel, now, format,
            Modifier.fillMaxWidth().padding(top = 22.dp, end = TvSafeEnd).height(GuideHeroHeight),
            state = state,
            canRecord = canRecord,
        )
        if (state.stale) Text(
            state.updatedAtMs?.let { "Listings last updated ${format.dayTime(it)} and may be out of date" } ?: "Listings may be out of date",
            style = TvType.Caption, color = WmmColors.Warn, maxLines = 1,
        )
        // What a row draws of the window: the same object across focus moves, which change only the selection.
        val window = remember(state.windowStartMs, state.windowEndMs, state.canGoEarlier, state.canGoLater, state.showsNow, state.listedThroughMs) {
            GuideWindow(state.windowStartMs, state.windowEndMs, state.canGoEarlier, state.canGoLater, state.showsNow, state.listedThroughMs)
        }
        val favourites = if (personal) state.favourites else emptySet()
        Column(
            Modifier.padding(top = 8.dp).fillMaxSize().drawWithContent {
                drawContent()
                // The now line: from the first row to the foot of the screen, while the window shows now.
                if (window.showsNow) {
                    val span = (window.endMs - window.startMs).toFloat()
                    val x = (ChannelColumn + ChannelGap).toPx() + TrackWidth.toPx() * ((now.coerceIn(window.startMs, window.endMs) - window.startMs) / span)
                    val top = 44.dp.toPx()
                    val w = 3.dp.toPx()
                    drawRoundRect(WmmColors.Accent, topLeft = Offset(x - w / 2, top), size = Size(w, size.height - top), cornerRadius = CornerRadius(w / 2))
                }
            },
        ) {
            TvTimeHeader(state, format)
            LazyColumn(
                Modifier.fillMaxSize().testTag(TestTags.GUIDE),
                state = list,
                // Room above the first row for the focused cell's ring; none below, so the next channel peeks at the foot.
                contentPadding = PaddingValues(top = RowGap, bottom = 0.dp),
                verticalArrangement = Arrangement.spacedBy(RowGap),
            ) {
                itemsIndexed(rows, key = { _, row -> row.channel.id }) { index, row ->
                    // The row's reminders, as a set kept until they change, so a move elsewhere leaves the row as it is.
                    val reminded = remember(row.programmes, state.reminders, state.personal, personal) {
                        if (!personal) emptySet() else row.programmes.filter { state.reminderFor(it) != null }.mapTo(HashSet()) { it.id }
                    }
                    TvGuideRow(row, index, window, now, format, memory, actions, favourite = row.channel.id in favourites, reminded = reminded)
                }
                if (rows.isEmpty()) item("none") {
                    Text(
                        if (state.loading) "Getting the listings…" else "No channels show this. Choose All channels to see every channel.",
                        style = TvType.Body, color = TvPalette.Muted, modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }
        }
    }
    // Something above the grid was focused when the rows went (a filter with no channels): the tab keeps focus.
    LaunchedEffect(rows.isEmpty()) { if (rows.isEmpty()) guideTab.tryFocus() }
}

internal fun cellKey(channelId: String, programmeId: String) = "guide:$channelId:$programmeId"
internal fun channelKey(channelId: String) = "guide:$channelId"

/** The guide's day and filter buttons say what is chosen: "All channels", "Favourites"; "Today", "Tomorrow". */
internal fun LiveGuideViewModel.State.filterLabel(): String = shownFilter.label

internal fun LiveGuideViewModel.State.dayLabel(): String = days.getOrNull(dayIndex)?.label ?: "Today"
