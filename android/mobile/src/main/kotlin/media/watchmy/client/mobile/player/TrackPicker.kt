package media.watchmy.client.mobile.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import media.watchmy.client.model.OptionLabel
import media.watchmy.client.model.PlayerTracks
import media.watchmy.client.model.StreamQuality
import media.watchmy.client.model.SubtitleEntry
import media.watchmy.client.model.audioOptionLabel
import media.watchmy.client.model.subtitleEntries
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.WmmIcons

internal enum class PickerTab(val title: String) { Audio("Audio"), Subtitles("Subtitles"), Quality("Quality") }

/**
 * Audio, subtitles and quality in one M3 modal bottom sheet
 * (PlayerPicker.dc.html): three columns when the sheet is wide (the player
 * is landscape), tabs when it is narrow. Picking an option applies it and
 * closes the sheet; an online subtitle keeps it open while it downloads,
 * then closes it once it is on. [tab] is the one the viewer opened it from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrackPicker(
    tracks: PlayerTracks,
    tab: PickerTab,
    onAudio: (Int) -> Unit,
    onSubtitle: (String?) -> Unit,
    onDownload: (String) -> Unit,
    onQuality: (StreamQuality) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val pick: (() -> Unit) -> Unit = { apply ->
        apply()
        scope.launch { sheet.hide() }.invokeOnCompletion { onDismiss() }
    }
    // A download that ends with its track on closes the sheet, as picking a track does.
    var awaiting by remember { mutableStateOf<String?>(null) }
    val downloading = tracks.online?.downloading
    LaunchedEffect(downloading, tracks.online?.error) {
        val id = awaiting ?: return@LaunchedEffect
        if (downloading != null) return@LaunchedEffect
        awaiting = null
        val done = tracks.online?.results?.firstOrNull { it.id == id }?.trackId
        if (done != null && tracks.online?.error == null) pick {}
    }
    val options = PickerOptions(
        tracks,
        onAudio = { pick { onAudio(it) } },
        onSubtitle = { pick { onSubtitle(it) } },
        onDownload = { id ->
            awaiting = id
            onDownload(id)
        },
        onQuality = { pick { onQuality(it) } },
    )
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        sheetMaxWidth = Dp.Unspecified,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = WmmColors.Surface2,
        contentColor = WmmColors.Text,
        // The player draws its own scrim, under the title (PickerBackdrop).
        scrimColor = Color.Transparent,
        dragHandle = { Box(Modifier.padding(top = 12.dp, bottom = 10.dp).size(32.dp, 4.dp).background(WmmColors.Line3, RoundedCornerShape(2.dp))) },
        contentWindowInsets = { WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal) },
    ) {
        HideSystemBarsInDialog()
        BoxWithConstraints(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
            if (maxWidth >= 600.dp) Columns(options) else Tabs(options, tab)
        }
    }
}

private class PickerOptions(
    val tracks: PlayerTracks,
    val onAudio: (Int) -> Unit,
    val onSubtitle: (String?) -> Unit,
    val onDownload: (String) -> Unit,
    val onQuality: (StreamQuality) -> Unit,
)

@Composable
private fun Columns(options: PickerOptions) {
    Column {
        Row(Modifier.fillMaxWidth().weight(1f, fill = false).height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Section(PickerTab.Audio.title, Modifier.weight(1f)) { AudioOptions(options) }
            val line = WmmColors.Line2
            Section(
                PickerTab.Subtitles.title,
                Modifier.weight(1f)
                    .drawBehind {
                        val w = 1.dp.toPx()
                        drawLine(line, Offset(w / 2, 0f), Offset(w / 2, size.height), w)
                        drawLine(line, Offset(size.width - w / 2, 0f), Offset(size.width - w / 2, size.height), w)
                    }
                    .padding(horizontal = 16.dp),
            ) { SubtitleOptions(options) }
            if (!options.tracks.isOffline) Section(PickerTab.Quality.title, Modifier.weight(1f)) { QualityOptions(options) }
        }
        Footnote(options.tracks)
    }
}

@Composable
private fun Tabs(options: PickerOptions, initial: PickerTab) {
    val tabs = if (options.tracks.isOffline) listOf(PickerTab.Audio, PickerTab.Subtitles) else PickerTab.entries
    var tab by remember(initial, options.tracks.isOffline) { mutableStateOf(initial.takeIf { it in tabs } ?: PickerTab.Audio) }
    Column {
        PrimaryTabRow(
            selectedTabIndex = tab.ordinal,
            containerColor = Color.Transparent,
            contentColor = WmmColors.Text,
            indicator = { TabRowDefaults.PrimaryIndicator(Modifier.tabIndicatorOffset(tab.ordinal, matchContentSize = true), color = WmmColors.Accent) },
            divider = { HorizontalDivider(color = WmmColors.Line2) },
        ) {
            tabs.forEach { t ->
                Tab(
                    selected = t == tab,
                    onClick = { tab = t },
                    text = { Text(t.title, style = PlayerType.Button) },
                    selectedContentColor = WmmColors.Text,
                    unselectedContentColor = WmmColors.TextMuted,
                )
            }
        }
        Column(Modifier.padding(top = 8.dp).weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            when (tab) {
                PickerTab.Audio -> AudioOptions(options)
                PickerTab.Subtitles -> SubtitleOptions(options)
                PickerTab.Quality -> QualityOptions(options)
            }
        }
        Footnote(options.tracks)
    }
}

@Composable
private fun Section(title: String, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title.uppercase(), style = PlayerType.SheetHeader, modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 6.dp))
        content()
    }
}

@Composable
private fun AudioOptions(options: PickerOptions) {
    val s = options.tracks
    if (s.audio.isEmpty()) Empty("No other audio")
    s.audio.forEach { a -> OptionRow(audioOptionLabel(a), selected = a.index == s.selectedAudio) { options.onAudio(a.index) } }
}

@Composable
private fun SubtitleOptions(options: PickerOptions) {
    subtitleEntries(options.tracks).forEach { e ->
        when (e) {
            is SubtitleEntry.Heading -> Text(e.text, style = PlayerType.OptionGroup, modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 10.dp, bottom = 2.dp))
            is SubtitleEntry.Track -> OptionRow(e.label, selected = e.selected) { options.onSubtitle(e.id) }
            is SubtitleEntry.Online -> DownloadRow(e) { options.onDownload(e.id) }
            is SubtitleEntry.Note -> Text(e.text, style = PlayerType.Footnote, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
        }
    }
}

/** An online result: a download mark where the radio goes, a spinner while it downloads. */
@Composable
private fun DownloadRow(entry: SubtitleEntry.Online, onClick: () -> Unit) {
    val detail = entry.label.detail
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = entry.enabled && !entry.downloading, interactionSource = null, indication = ripple(color = WmmColors.Accent), role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            if (entry.downloading) {
                CircularProgressIndicator(Modifier.size(16.dp), color = WmmColors.Accent, strokeWidth = 2.dp)
            } else {
                Icon(WmmIcons.Download, contentDescription = null, tint = if (entry.enabled) WmmColors.TextMuted else WmmColors.TextFaint, modifier = Modifier.size(18.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            Text(entry.label.label, style = PlayerType.Option, color = if (entry.enabled) Color.Unspecified else WmmColors.TextFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            detail?.let { Text(it, style = PlayerType.OptionDetail, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun QualityOptions(options: PickerOptions) {
    val s = options.tracks
    s.qualities.forEach { q ->
        // Automatic's "Now 720p · 4 Mbps" goes under its name.
        OptionRow(qualityOption(q, s), selected = q == s.quality, stackDetail = q == StreamQuality.Auto) { options.onQuality(q) }
    }
}

@Composable
private fun Footnote(tracks: PlayerTracks) {
    Text(
        listOfNotNull(if (tracks.isOffline) "This copy plays at its downloaded quality." else "Changes apply at once. A burned-in subtitle restarts the stream from here.", streamReason(tracks)).joinToString(" "),
        style = PlayerType.Footnote,
        modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 10.dp),
    )
}

@Composable
private fun Empty(text: String) {
    Text(text, style = PlayerType.OptionDetail, modifier = Modifier.padding(horizontal = 8.dp, vertical = 14.dp))
}

/**
 * A radio row: 48 dp, the mockup's gold radio, the name and a muted detail
 * on the right; a long detail ("PGS, burned in, restarts playback") goes
 * under the name instead, so neither is cut short in a narrow column.
 */
@Composable
private fun OptionRow(label: OptionLabel, selected: Boolean, stackDetail: Boolean = false, onClick: () -> Unit) {
    val detail = label.detail
    val stacked = detail != null && (stackDetail || detail.length > STACKED_DETAIL)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .selectable(selected, interactionSource = null, indication = ripple(color = WmmColors.Accent), role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = if (stacked) 6.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RadioMark(selected)
        if (stacked) {
            Column(Modifier.weight(1f)) {
                Text(label.label, style = PlayerType.Option, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(detail, style = PlayerType.OptionDetail)
            }
        } else {
            Text(label.label, style = PlayerType.Option, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            detail?.let { Text(it, style = PlayerType.OptionDetail, textAlign = TextAlign.End, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 150.dp)) }
        }
    }
}

/** Details longer than this go under the option's name. */
private const val STACKED_DETAIL = 24

@Composable
private fun RadioMark(selected: Boolean) {
    Canvas(Modifier.size(20.dp)) {
        val line = 2.dp.toPx()
        drawCircle(if (selected) WmmColors.Accent else WmmColors.TextMuted, radius = size.minDimension / 2 - line / 2, style = Stroke(line))
        if (selected) drawCircle(WmmColors.Accent, radius = 5.dp.toPx())
    }
}

/** Tablet choices stay beside the live video; every entry point scrolls to its own section. */
@Composable
internal fun TabletTrackPicker(
    tracks: PlayerTracks,
    tab: PickerTab,
    onAudio: (Int) -> Unit,
    onSubtitle: (String?) -> Unit,
    onDownload: (String) -> Unit,
    onQuality: (StreamQuality) -> Unit,
    onDismiss: () -> Unit,
) {
    val options = PickerOptions(tracks, onAudio, onSubtitle, onDownload, onQuality)
    val index = if (tracks.isOffline) tab.ordinal.coerceAtMost(1) else tab.ordinal
    val scroll = rememberLazyListState(initialFirstVisibleItemIndex = index)
    LaunchedEffect(index) { scroll.scrollToItem(index) }
    TabletPlayerPanel(if (tracks.isOffline) "Audio and subtitles" else "Audio, subtitles and quality", onDismiss) {
        LazyColumn(state = scroll, modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            item(key = PickerTab.Audio) { StackedSection("Audio") { AudioOptions(options) } }
            item(key = PickerTab.Subtitles) { StackedSection("Subtitles") { SubtitleOptions(options) } }
            if (!tracks.isOffline) item(key = PickerTab.Quality) { StackedSection("Quality") { QualityOptions(options) } }
            item { Footnote(tracks) }
        }
    }
}

@Composable
private fun StackedSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title.uppercase(), style = PlayerType.SheetHeader, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
        content()
    }
}

@Composable
internal fun TabletPlayerPanel(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().clickable(interactionSource = null, indication = null, onClickLabel = "Close $title", onClick = onDismiss))
        Surface(
            modifier = Modifier.align(Alignment.CenterEnd).padding(16.dp).width(420.dp).fillMaxHeight().semantics { paneTitle = title },
            shape = RoundedCornerShape(28.dp),
            color = WmmColors.Surface2,
            contentColor = WmmColors.Text,
            shadowElevation = 16.dp,
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = PlayerType.Title, modifier = Modifier.weight(1f).padding(start = 12.dp))
                    RoundButton(WmmIcons.Close, "Close $title", onDismiss)
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = WmmColors.Line2)
                content()
            }
        }
    }
}
