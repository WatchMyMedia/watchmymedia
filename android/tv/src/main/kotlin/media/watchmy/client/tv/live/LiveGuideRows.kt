package media.watchmy.client.tv.live

import android.view.KeyEvent as AndroidKey
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.focused
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import media.watchmy.client.model.LiveChannel
import media.watchmy.client.model.LiveClockFormat
import media.watchmy.client.model.LiveGuideRow
import media.watchmy.client.model.LiveProgramme
import media.watchmy.client.model.RecordingGlyph
import media.watchmy.client.model.at
import media.watchmy.client.model.recordingSpoken
import media.watchmy.client.model.spokenName
import media.watchmy.client.presentation.live.LiveGuideViewModel
import media.watchmy.client.tv.components.FocusGrowth
import media.watchmy.client.tv.components.FocusMemory
import media.watchmy.client.tv.components.FocusableBox
import media.watchmy.client.tv.components.TvPalette
import media.watchmy.client.tv.components.TvType
import media.watchmy.client.tv.components.focusMemory
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.WmmIcons
import kotlin.math.roundToInt

/*
 * The guide's grid: a channel column 232 wide, a 12 gap, then two hours
 * across at 11.5 a minute (1380). Rows are 80 high, 6 apart, so six
 * channels fit under the hero and a seventh peeks at the foot. Cells are
 * text: the title at 26 and a line at 22. A cell on now is a shade lighter
 * with a hairline; the focused cell lifts and takes the gold ring.
 */

/** The channel's number and name, at the left of each row. */
internal val ChannelColumn = 232.dp

/** Between the channel column and the first programme. */
internal val ChannelGap = 12.dp

/** Two hours across: 11.5 dp a minute. */
internal val TrackWidth = 1380.dp

internal val RowHeight = 80.dp

/** Between two rows of the guide. */
internal val RowGap = 6.dp

/** Between two programmes in a row. */
private val CellGap = 6.dp

/** A cell on now: a shade lighter than the others, with a hairline. */
private val OnNowCell = Color(0xFF202024)

/** The times along the top of the grid, each over its half hour. */
@Composable
internal fun TvTimeHeader(state: LiveGuideViewModel.State, format: LiveClockFormat, modifier: Modifier = Modifier) {
    val span = (state.windowEndMs - state.windowStartMs).toFloat().coerceAtLeast(1f)
    Box(modifier.fillMaxWidth().height(38.dp)) {
        state.slots.forEach { slot ->
            val x = ChannelColumn + ChannelGap + TrackWidth * ((slot - state.windowStartMs) / span)
            Text(format.time(slot), style = TvType.Second.copy(fontWeight = FontWeight.SemiBold), color = WmmColors.TextSoft, maxLines = 1, modifier = Modifier.offset(x = x))
        }
    }
}

/** What a guide row draws of the window: kept across focus moves, which change only the selection. */
@Immutable
internal data class GuideWindow(
    val startMs: Long,
    val endMs: Long,
    val canGoEarlier: Boolean,
    val canGoLater: Boolean,
    val showsNow: Boolean,
    val listedThroughMs: Long?,
)

/** What a guide row's cells call: made once for the guide, reading its state when they run. */
@Stable
internal class GuideRowActions(
    val focusProgramme: (LiveChannel, LiveProgramme) -> Unit,
    val focusChannel: (LiveChannel) -> Unit,
    /** OK on a programme: watch the channel when it is on, else open its choices. */
    val choose: (LiveChannel, LiveProgramme) -> Unit,
    val watch: (LiveChannel) -> Unit,
    /** ▲ ▼ from row [from] to the programme on at the time given, or at the cursor; false at either end. */
    val vertical: (from: Int, time: Long?, step: Int) -> Boolean,
    /** Left or Right at the window's edge: move it half an hour and keep focus on this channel at that time. */
    val edge: (LiveChannel, Long, Boolean) -> Unit,
    /** Hold OK, or the remote's Record: the programme's choices, whenever it is on. */
    val programmeMenu: (LiveChannel, LiveProgramme) -> Unit,
)

/** ▲ and ▼ as a step down or up the guide; 0 for any other key. */
private fun verticalStep(code: Int) = when (code) {
    AndroidKey.KEYCODE_DPAD_DOWN -> 1
    AndroidKey.KEYCODE_DPAD_UP -> -1
    else -> 0
}

/**
 * One channel across the window. It is given only what it draws, and its
 * cells' modifiers and lambdas are remembered by key, so moving focus
 * through the guide (which changes the selection) redraws no row: only the
 * two cells whose ring moves, inside them. The channel's own cell takes no
 * focus: ◀ from what is on now goes to the rail, and favourites are among
 * a programme's choices.
 */
@Composable
internal fun TvGuideRow(
    row: LiveGuideRow,
    index: Int,
    window: GuideWindow,
    now: Long,
    format: LiveClockFormat,
    memory: FocusMemory,
    actions: GuideRowActions,
    favourite: Boolean,
    reminded: Set<String>,
    modifier: Modifier = Modifier,
) {
    val channel = row.channel
    // Read when a key arrives or focus lands, not when the row composes.
    val currentIndex by rememberUpdatedState(index)
    val currentRow by rememberUpdatedState(row)
    val currentWindow by rememberUpdatedState(window)
    val vertical = remember(actions) {
        Modifier.onPreviewKeyEvent { event ->
            val step = verticalStep(event.nativeKeyEvent.keyCode)
            event.type == KeyEventType.KeyDown && step != 0 && actions.vertical(currentIndex, null, step)
        }
    }
    Row(modifier.fillMaxWidth().height(RowHeight), verticalAlignment = Alignment.CenterVertically) {
        ChannelCell(channel, favourite, Modifier.width(ChannelColumn))
        Box(Modifier.width(ChannelGap))
        if (!row.listings || !channel.available) {
            // A channel without listings is one cell across the window: OK watches it.
            var rowFocused by remember { mutableStateOf(false) }
            val focusing = remember(channel.id, memory, actions, vertical) {
                Modifier.width(TrackWidth - CellGap).fillMaxHeight().focusMemory(memory, channelKey(channel.id)).onFocusChanged { if (it.isFocused) actions.focusChannel(currentRow.channel) }.then(vertical)
            }
            val choose = remember(channel, actions) { { if (channel.available) actions.watch(channel) else actions.focusChannel(channel) } }
            FocusableBox(
                onClick = choose,
                modifier = focusing.onFocusChanged { rowFocused = it.isFocused }.clearAndSetSemantics {
                    contentDescription = "${channel.number} ${channel.name}, ${if (channel.available) "No listings, Watch live" else "Unavailable"}"
                    focused = rowFocused
                },
                cornerRadius = 12.dp, focusedScale = FocusGrowth.NONE, ringOffset = 3.dp,
            ) { focused ->
                Column(
                    Modifier.fillMaxSize().background(if (focused) TvPalette.Lift else WmmColors.Surface2, RoundedCornerShape(12.dp)).padding(horizontal = 18.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(if (channel.available) "No listings" else "Unavailable", style = TvType.BodyStrong, color = if (channel.available) WmmColors.Text else TvPalette.Muted, maxLines = 1)
                    Text(if (channel.available) "Plays live" else "Not in the tuner’s last channel list", style = TvType.Caption, color = TvPalette.Muted, maxLines = 1)
                }
            }
            return@Row
        }
        val listedThrough = window.listedThroughMs
        val lastEnd = row.programmes.lastOrNull()?.endMs ?: listedThrough
        val gapStart = if (listedThrough != null && lastEnd != null && lastEnd < window.endMs && listedThrough < window.endMs) maxOf(lastEnd, window.startMs) else null
        // Where each child goes, in the window's time: the programmes, then the gap after the listings.
        val spans = remember(row.programmes, window, gapStart) {
            row.programmes.map { maxOf(it.startMs, window.startMs) to minOf(it.endMs, window.endMs) } + listOfNotNull(gapStart?.let { it to window.endMs })
        }
        GuideTrack(window, spans, Modifier.width(TrackWidth).fillMaxHeight()) {
            row.programmes.forEachIndexed { i, programme ->
                key(programme.id) {
                    val currentI by rememberUpdatedState(i)
                    val focusing = remember(channel.id, programme.id, memory, actions) {
                        Modifier.focusMemory(memory, cellKey(channel.id, programme.id))
                            .onFocusChanged { if (it.isFocused) actions.focusProgramme(currentRow.channel, programme) }
                            .onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                // The remote's Record opens the programme's choices, with Record among them for people who may record.
                                if (event.nativeKeyEvent.keyCode == AndroidKey.KEYCODE_MEDIA_RECORD) {
                                    actions.programmeMenu(currentRow.channel, programme)
                                    return@onPreviewKeyEvent true
                                }
                                val w = currentWindow
                                val step = verticalStep(event.nativeKeyEvent.keyCode)
                                if (step != 0) return@onPreviewKeyEvent actions.vertical(currentIndex, maxOf(programme.startMs, w.startMs), step)
                                // At the window's edge, Left and Right move it half an hour and keep focus on this channel.
                                val edge = when (event.nativeKeyEvent.keyCode) {
                                    AndroidKey.KEYCODE_DPAD_RIGHT -> (currentI == currentRow.programmes.lastIndex || programme.endMs >= w.endMs) && w.canGoLater
                                    AndroidKey.KEYCODE_DPAD_LEFT -> currentI == 0 && w.canGoEarlier
                                    else -> false
                                }
                                if (edge) {
                                    val later = event.nativeKeyEvent.keyCode == AndroidKey.KEYCODE_DPAD_RIGHT
                                    actions.edge(currentRow.channel, if (later) w.endMs else w.startMs - 1, later)
                                }
                                edge
                            }
                    }
                    val choose = remember(channel, programme, actions) { { actions.choose(channel, programme) } }
                    val menu = remember(channel, programme, actions) { { actions.programmeMenu(channel, programme) } }
                    TvGuideCell(
                        programme, now, format, clippedStart = programme.startMs < window.startMs,
                        onClick = choose,
                        onLongClick = menu,
                        reminded = programme.id in reminded,
                        modifier = focusing,
                    )
                }
            }
            if (gapStart != null && listedThrough != null) {
                Box(Modifier.padding(horizontal = 18.dp), contentAlignment = Alignment.CenterStart) {
                    Text("No listings after ${format.dayHour(listedThrough)}", style = TvType.Caption, color = TvPalette.Muted, maxLines = 2)
                }
            }
        }
    }
}

/** The channel's number, its name and a gold star for a favourite. */
@Composable
private fun ChannelCell(channel: LiveChannel, favourite: Boolean, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            channel.number,
            style = TvType.Heading.copy(fontSize = TvType.Heading.fontSize * (30f / 34f), fontWeight = FontWeight.Bold),
            color = if (channel.available) WmmColors.Text else TvPalette.Muted,
            maxLines = 1,
            modifier = Modifier.widthIn(min = 74.dp),
        )
        Text(channel.name, style = TvType.Caption.copy(fontWeight = FontWeight.SemiBold), color = TvPalette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (favourite) Icon(WmmIcons.StarFilled, contentDescription = "Favourite", tint = WmmColors.Accent, modifier = Modifier.size(24.dp))
    }
}

/**
 * The window's track: each child at its span's place in time, as wide as
 * the span less the gap between cells.
 */
@Composable
private fun GuideTrack(window: GuideWindow, spans: List<Pair<Long, Long>>, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val gap = CellGap.roundToPx()
        val span = (window.endMs - window.startMs).toFloat()
        fun x(ms: Long): Int = (width * ((ms.coerceIn(window.startMs, window.endMs) - window.startMs) / span)).roundToInt()
        val placed = measurables.mapIndexed { i, m ->
            val (start, end) = spans.getOrElse(i) { window.startMs to window.startMs }
            val left = x(start)
            m.measure(Constraints.fixed((x(end) - left - gap).coerceAtLeast(0), height)) to left
        }
        layout(width, height) { placed.forEach { (p, left) -> p.place(left, 0) } }
    }
}

@Composable
private fun TvGuideCell(
    programme: LiveProgramme,
    now: Long,
    format: LiveClockFormat,
    clippedStart: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    reminded: Boolean = false,
) {
    val airing = programme.airingAt(now)
    // A cell reads as one thing; its focus state stays in the semantics it clears, so TalkBack (and the focus tests) see it.
    var isFocused by remember { mutableStateOf(false) }
    FocusableBox(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.onFocusChanged { isFocused = it.isFocused }.clearAndSetSemantics {
            contentDescription = programme.spokenName(format) + (if (reminded) ", reminder set" else "") + programme.recordingSpoken()
            focused = isFocused
        },
        cornerRadius = 12.dp, focusedScale = FocusGrowth.NONE, ringOffset = 3.dp,
    ) { focused ->
        val shape = RoundedCornerShape(12.dp)
        val fill = when {
            focused -> TvPalette.Lift
            airing -> OnNowCell
            else -> WmmColors.Surface2
        }
        Column(
            Modifier.fillMaxSize().background(fill, shape)
                .then(if (airing && !focused) Modifier.border(2.dp, TvPalette.Line, shape) else Modifier)
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text((if (clippedStart) "‹ " else "") + programme.title, style = TvType.BodyStrong, color = WmmColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                val mark = programme.recording
                RecordingGlyphMark(mark)
                if (reminded) ReminderBell(size = 22.dp)
                val (line, warn) = when {
                    mark?.glyph == RecordingGlyph.Clash -> "Won’t record" to true
                    !airing && (mark?.glyph == RecordingGlyph.Dot || mark?.glyph == RecordingGlyph.Ringed) -> "Will record" to false
                    else -> programme.cellLine(now, format) to false
                }
                Text(line, style = TvType.Caption, color = if (warn) WmmColors.Warn else TvPalette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
