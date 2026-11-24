package media.watchmy.client.tv.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import media.watchmy.client.model.LiveClockFormat
import media.watchmy.client.model.LiveProgramme
import media.watchmy.client.model.RecordingGlyph
import media.watchmy.client.model.RecordingMark
import media.watchmy.client.tv.components.TvPalette
import media.watchmy.client.tv.components.TvTab
import media.watchmy.client.tv.components.TvType
import media.watchmy.client.tv.components.rememberNow
import media.watchmy.client.tv.navigation.TvRoutes
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.WmmIcons
import media.watchmy.client.ui.components.rememberLiveClockFormat
import java.time.Instant
import java.time.ZoneId

/*
 * The pieces every Live TV screen on the TV shares: the Guide, Recordings
 * and Reminders tabs, key hints, and the recording marks, which are red:
 * red marks Recording and Live and nothing else, as on every app, burgundy
 * and gold are the accents, and orange is kept for what won't record.
 */

/** The three Live TV screens, as tabs along their top. */
internal enum class LiveTab(val label: String, val route: Any) {
    Guide("Guide", TvRoutes.LiveTV),
    Recordings("Recordings", TvRoutes.LiveRecordings),
    Reminders("Reminders", TvRoutes.LiveReminders),
}

/**
 * The top of a Live TV screen, 64 high: the tabs, then [trailing] (the
 * guide's buttons) and the time at the right. OK on another tab opens it
 * as a section, so Back from it goes Home; OK on the tab showing calls
 * [onShowing], which moves focus into its content. [first] is the tab
 * showing's requester, for focus to come back to it.
 */
@Composable
internal fun LiveTabsHeader(
    showing: LiveTab,
    tabs: List<LiveTab>,
    onNavigate: (Any) -> Unit,
    modifier: Modifier = Modifier,
    first: FocusRequester? = null,
    onShowing: () -> Unit = {},
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(modifier.fillMaxWidth().heightIn(min = 64.dp).padding(end = TvSafeEnd), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        tabs.forEachIndexed { index, tab ->
            TvTab(
                tab.label,
                selected = tab == showing,
                onClick = { if (tab == showing) onShowing() else onNavigate(tab.route) },
                modifier = if (tab == showing && first != null) Modifier.focusRequester(first) else Modifier,
                growFrom = if (index == 0) TransformOrigin(0f, 0.5f) else TransformOrigin.Center,
            )
        }
        Spacer(Modifier.weight(1f))
        trailing()
        LiveClockText(Modifier.padding(start = 24.dp))
    }
}

/** The safe area's right edge for a Live TV screen's own content. */
internal val TvSafeEnd = 96.dp

/** The time, as the Live TV screens show it in their top row: what "18 min left" is read against. */
@Composable
internal fun LiveClockText(modifier: Modifier = Modifier) {
    val now by rememberNow()
    val format = rememberLiveClockFormat()
    Text(format.time(now), style = TvType.Second.copy(fontWeight = FontWeight.Medium), color = WmmColors.TextSoft, maxLines = 1, modifier = modifier.clearAndSetSemantics {})
}

/** One key and what it does (`.kc`): only for what a key does that no button shows. */
internal data class KeyHint(val key: String, val does: String)

/** A line of key hints, 22 sp, each key in its own cap. */
@Composable
internal fun KeyHints(hints: List<KeyHint>, modifier: Modifier = Modifier, vertical: Boolean = false) {
    val items: @Composable () -> Unit = {
        hints.forEach { hint ->
            Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                KeyCap(hint.key)
                Text(hint.does, style = TvType.Caption, color = TvPalette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    if (vertical) Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) { items() }
    else Row(modifier, horizontalArrangement = Arrangement.spacedBy(40.dp), verticalAlignment = Alignment.CenterVertically) { items() }
}

/** One key and what it does, inline: the reminder banner's and a search card's. */
@Composable
internal fun TvOkHint(text: String, key: String = "OK") {
    Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        KeyCap(key)
        Text(text, style = TvType.Caption.copy(fontWeight = FontWeight.SemiBold), color = WmmColors.Text, maxLines = 1)
    }
}

/** A key's cap: "OK", "Hold OK", "▲ ▼". */
@Composable
internal fun KeyCap(key: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(end = 12.dp)
            .defaultMinSize(minWidth = 44.dp)
            .heightIn(min = 40.dp)
            .border(2.dp, TvPalette.Line3, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(key, style = TvType.Caption.copy(fontWeight = FontWeight.Bold), color = WmmColors.TextSoft, maxLines = 1)
    }
}

/** The red of Recording and Live marks, and only those: the dot for a recording, and the edge of Live, REC and Recording marks. */
internal val RecRim = WmmColors.Record

/** The round mark beside a programme that will record or is recording (`.dot`). */
@Composable
internal fun RecDot(modifier: Modifier = Modifier, size: Dp = 16.dp) {
    Box(modifier.size(size).background(RecRim, CircleShape))
}

/** A tag (`.tag`) in red: "Live", "Recording". */
@Composable
internal fun RecTag(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier
            .background(WmmColors.RecordFill, RoundedCornerShape(8.dp))
            .border(2.dp, RecRim, RoundedCornerShape(8.dp))
            .heightIn(min = 38.dp)
            .padding(horizontal = 10.dp, vertical = 3.dp),
        style = TvType.Caption.copy(fontWeight = FontWeight.Bold),
        color = Color.White,
        maxLines = 1,
    )
}

/** An outlined tag in the warning orange: "Won't record". */
@Composable
internal fun WarnTag(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.border(2.dp, WmmColors.Warn, RoundedCornerShape(8.dp)).heightIn(min = 38.dp).padding(horizontal = 10.dp, vertical = 3.dp),
        style = TvType.Caption.copy(fontWeight = FontWeight.Bold),
        color = WmmColors.Warn,
        maxLines = 1,
    )
}

/** A flag on a picture (`.flag`): REC or LIVE in red, or dark for a channel's number. */
@Composable
internal fun PictureFlag(text: String, modifier: Modifier = Modifier, rec: Boolean = false, spoken: String? = null) {
    val shape = RoundedCornerShape(8.dp)
    Text(
        text,
        modifier = modifier
            .then(if (spoken != null) Modifier.clearAndSetSemantics { contentDescription = spoken } else Modifier)
            .background(if (rec) WmmColors.RecordFill else Color(0xDB0F0F10), shape)
            .border(2.dp, if (rec) RecRim else Color.White.copy(alpha = .18f), shape)
            .heightIn(min = 36.dp)
            .padding(horizontal = 10.dp, vertical = 2.dp),
        style = TvType.Caption.copy(fontWeight = FontWeight.Bold),
        color = if (rec) Color.White else WmmColors.Text,
        maxLines = 1,
    )
}

/**
 * A programme's recording, as a guide cell's second line marks it: the
 * red dot while it records or will, a ring for a series, and an orange
 * ring for one that won't record because the tuners are taken.
 */
@Composable
internal fun RecordingGlyphMark(mark: RecordingMark?, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    when (mark?.glyph) {
        RecordingGlyph.Dot, RecordingGlyph.Rec -> RecDot(modifier, size)
        RecordingGlyph.Ringed -> Box(modifier.size(size).border(3.dp, RecRim, CircleShape).padding(4.dp).background(RecRim, CircleShape))
        RecordingGlyph.Clash -> Box(modifier.size(size).border(3.dp, WmmColors.Warn, CircleShape))
        null -> Unit
    }
}

/** The bell beside a programme with a reminder set. */
@Composable
internal fun ReminderBell(modifier: Modifier = Modifier, size: Dp = 22.dp) {
    Icon(WmmIcons.BellFilled, contentDescription = null, tint = WmmColors.Accent, modifier = modifier.size(size))
}

/**
 * A screen that can't show what it should (`.state`): a 48 sp title, why in
 * words, a quieter note, then its buttons in a row, the first focused by the
 * caller. Sits at [start] from the left and a third of the way down.
 */
@Composable
internal fun LiveStateMessage(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    note: String? = null,
    buttons: @Composable RowScope.() -> Unit,
) {
    Column(modifier.widthIn(max = 1100.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(title, style = TvType.Title, color = WmmColors.Text)
        Text(body, style = TvType.Body, color = WmmColors.TextSoft, modifier = Modifier.widthIn(max = 860.dp))
        note?.let { Text(it, style = TvType.Second, color = TvPalette.Muted, modifier = Modifier.widthIn(max = 860.dp)) }
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically, content = buttons)
    }
}

/** A grey block standing in for text or a row while the guide loads. */
@Composable
internal fun Skeleton(width: Dp?, height: Dp, modifier: Modifier = Modifier) {
    Box((if (width != null) modifier.size(width, height) else modifier.fillMaxWidth().height(height)).background(WmmColors.Surface2, RoundedCornerShape(14.dp)))
}

/** "Tonight", "Today", "Tomorrow" or the weekday: when a later programme is on, as the guide's hero and panel say it. */
internal fun programmeDay(startMs: Long, nowMs: Long, format: LiveClockFormat, zone: ZoneId = ZoneId.systemDefault()): String {
    val day = format.dayName(startMs, nowMs)
    if (day != "Today") return day
    return if (Instant.ofEpochMilli(startMs).atZone(zone).hour >= 17) "Tonight" else "Today"
}

/** A programme's own facts for a second line: "New · 48 min left", "9:00 PM · movie", "Live · until 11:00 PM". */
internal fun LiveProgramme.cellLine(nowMs: Long, format: LiveClockFormat): String {
    val airing = airingAt(nowMs)
    val parts = buildList {
        when {
            new -> add("New")
            live -> add("Live")
            repeat -> add("Repeat")
        }
        add(
            when {
                airing && live -> "until ${format.time(endMs)}"
                airing -> media.watchmy.client.model.liveMinutesLeft(nowMs, endMs)
                else -> format.time(startMs)
            },
        )
        if (!airing && kind == "movie") add("movie")
    }
    return parts.joinToString(" · ")
}

/** "New" on a recording's picture, gold as every New flag is on the TV. */
@Composable
internal fun NewFlag(modifier: Modifier = Modifier) {
    media.watchmy.client.tv.components.ArtBadge("New", modifier, media.watchmy.client.ui.components.BadgeStyle.Gold)
}
