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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.Layout
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.key
import androidx.compose.runtime.Stable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.focused
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import media.watchmy.client.model.LiveChannel
import media.watchmy.client.model.LiveClockFormat
import media.watchmy.client.model.LiveGuideRow
import media.watchmy.client.model.LiveProgramme
import media.watchmy.client.model.at
import media.watchmy.client.model.episodeLine
import media.watchmy.client.ui.components.RecordingMarkBadge
import media.watchmy.client.model.recordingSpoken
import media.watchmy.client.model.progressAt
import media.watchmy.client.model.spokenName
import media.watchmy.client.model.tags
import media.watchmy.client.presentation.live.LiveGuideViewModel
import media.watchmy.client.tv.components.FocusMemory
import media.watchmy.client.tv.components.FocusableBox
import media.watchmy.client.tv.components.focusMemory
import media.watchmy.client.tv.components.inter
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.ChannelLogo
import media.watchmy.client.ui.components.ProgrammeTag
import media.watchmy.client.ui.components.WmmIcons

@Composable
internal fun TvTimeHeader(state: LiveGuideViewModel.State, format: LiveClockFormat, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(end = 58.dp).height(48.dp).background(WmmColors.Surface), verticalAlignment = Alignment.CenterVertically) {
        Text(state.days.getOrNull(state.dayIndex)?.label.orEmpty(), style = inter(18.sp, FontWeight.SemiBold), color = WmmColors.TextSoft, modifier = Modifier.width(ChannelColumn).padding(start = 16.dp))
        state.slots.forEach { slot ->
            Box(Modifier.weight(1f).fillMaxHeight().border(0.5.dp, WmmColors.Line), contentAlignment = Alignment.CenterStart) {
                Text(format.time(slot), style = inter(18.sp), color = WmmColors.TextMuted, modifier = Modifier.padding(horizontal = 14.dp))
            }
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
    val watch: (LiveChannel) -> Unit,
    /** ▲ ▼ from row [from] to the programme on at the time given, or at the cursor; false at either end. */
    val vertical: (from: Int, time: Long?, step: Int) -> Boolean,
    /** Left or Right at the window's edge: move it half an hour and keep focus on this channel at that time. */
    val edge: (LiveChannel, Long, Boolean) -> Unit,
    val channelMenu: (LiveChannel) -> Unit,
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
 * two cells whose ring moves, inside them.
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
    menus: Boolean,
    modifier: Modifier = Modifier,
) {
    val channel = row.channel
    // Read when a key arrives or focus lands, not when the row composes.
    val currentIndex by rememberUpdatedState(index)
    val currentRow by rememberUpdatedState(row)
    val currentWindow by rememberUpdatedState(window)
    val currentNow by rememberUpdatedState(now)
    val vertical = remember(actions) {
        Modifier.onPreviewKeyEvent { event ->
            val step = verticalStep(event.nativeKeyEvent.keyCode)
            event.type == KeyEventType.KeyDown && step != 0 && actions.vertical(currentIndex, null, step)
        }
    }
    val watch = remember(channel, actions) { { if (channel.available) actions.watch(channel) } }
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
        if (menus && (channel.available || row.listings)) {
            // The channel's own cell: OK watches it, Hold OK stars it.
            val focusing = remember(channel.id, memory, actions, vertical) {
                Modifier.width(ChannelColumn).fillMaxHeight().focusMemory(memory, "guide-channel:${channel.id}")
                    .onFocusChanged {
                        if (!it.isFocused) return@onFocusChanged
                        // The hero shows what is on the channel, or the channel alone without listings.
                        val w = currentWindow
                        val programmes = currentRow.programmes
                        val on = programmes.at(currentNow.coerceIn(w.startMs, w.endMs - 1)) ?: programmes.firstOrNull()
                        if (on != null) actions.focusProgramme(currentRow.channel, on) else actions.focusChannel(currentRow.channel)
                    }
                    .then(vertical)
            }
            val star = remember(channel, actions) { { actions.channelMenu(channel) } }
            FocusableBox(
                onClick = watch,
                onLongClick = star,
                modifier = focusing.clearAndSetSemantics { contentDescription = "Watch ${channel.number} ${channel.name}" + (if (favourite) ", favourite" else "") + (row.programmes.firstOrNull { it.airingAt(now) }?.let { ", ${it.title}" } ?: "") },
                cornerRadius = 0.dp, focusedScale = 1f, ringOffset = (-3).dp,
            ) { focused -> channelCell(focused) }
        } else Box(Modifier.width(ChannelColumn).fillMaxHeight()) { channelCell(false) }
        if (!row.listings || !channel.available) {
            val focusing = remember(channel.id, memory, actions, vertical) {
                Modifier.fillMaxSize().focusMemory(memory, channelKey(channel.id)).onFocusChanged { if (it.isFocused) actions.focusChannel(currentRow.channel) }.then(vertical)
            }
            val choose = remember(channel, actions) { { if (channel.available) actions.watch(channel) else actions.focusChannel(channel) } }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                FocusableBox(
                    onClick = choose,
                    modifier = focusing.clearAndSetSemantics { contentDescription = "${channel.number} ${channel.name}, ${if (channel.available) "No listings, Watch live" else "Unavailable"}" },
                    cornerRadius = 0.dp, focusedScale = 1f, ringOffset = (-3).dp,
                ) { focused ->
                    Column(Modifier.fillMaxSize().background(if (focused) WmmColors.Surface3 else Color(0xFF131315)).padding(horizontal = 14.dp), verticalArrangement = Arrangement.Center) {
                        Text(if (channel.available) "No listings" else "Unavailable", style = inter(19.sp, FontWeight.SemiBold), color = WmmColors.TextMuted)
                        Text(if (channel.available) "Plays live" else "Not in the tuner’s last channel list", style = inter(15.sp), color = WmmColors.TextFaint)
                    }
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
        GuideTrack(window, spans, now, Modifier.weight(1f).fillMaxHeight()) {
            row.programmes.forEachIndexed { i, programme ->
                key(programme.id) {
                    val currentI by rememberUpdatedState(i)
                    val focusing = remember(channel.id, programme.id, memory, actions) {
                        Modifier.focusMemory(memory, cellKey(channel.id, programme.id))
                            .onFocusChanged { if (it.isFocused) actions.focusProgramme(currentRow.channel, programme) }
                            .onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                // The remote's Record opens the programme's menu, with Record on it for people who may record.
                                if (event.nativeKeyEvent.keyCode == AndroidKey.KEYCODE_MEDIA_RECORD && menus) {
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
                    val menu = remember(channel, programme, actions, menus) { if (menus) ({ actions.programmeMenu(channel, programme) }) else null }
                    TvGuideCell(
                        programme, now, format, clippedStart = programme.startMs < window.startMs,
                        onClick = watch,
                        onLongClick = menu,
                        reminded = programme.id in reminded,
                        modifier = focusing,
                    )
                }
            }
            if (gapStart != null && listedThrough != null) {
                Box(Modifier.background(WmmColors.Ground).padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
                    Text("No listings after ${format.dayHour(listedThrough)}", style = inter(17.sp), color = WmmColors.TextMuted, maxLines = 2)
                }
            }
        }
    }
}

/**
 * The window's track: each child at its span's place in time, as wide as
 * the span, and the now line over them while the window shows now.
 */
@Composable
private fun GuideTrack(window: GuideWindow, spans: List<Pair<Long, Long>>, now: Long, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val nowLine = with(LocalDensity.current) { 3.dp.toPx() }
    Layout(
        content = content,
        modifier = modifier.drawWithContent {
            drawContent()
            if (window.showsNow) {
                val x = size.width * (now.coerceIn(window.startMs, window.endMs) - window.startMs) / (window.endMs - window.startMs).toFloat()
                drawRect(WmmColors.Accent, topLeft = Offset(x - nowLine / 3, 0f), size = Size(nowLine, size.height))
            }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val span = (window.endMs - window.startMs).toFloat()
        fun x(ms: Long): Int = (width * ((ms.coerceIn(window.startMs, window.endMs) - window.startMs) / span)).roundToInt()
        val placed = measurables.mapIndexed { i, m ->
            val (start, end) = spans.getOrElse(i) { window.startMs to window.startMs }
            val left = x(start)
            m.measure(Constraints.fixed((x(end) - left).coerceAtLeast(0), height)) to left
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
    FocusableBox(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.clearAndSetSemantics { contentDescription = programme.spokenName(format) + (if (reminded) ", reminder set" else "") + programme.recordingSpoken() },
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
