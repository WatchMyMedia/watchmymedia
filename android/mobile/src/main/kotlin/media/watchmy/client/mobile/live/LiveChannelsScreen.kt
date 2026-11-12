package media.watchmy.client.mobile.live

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import media.watchmy.client.mobile.components.EmptyState
import media.watchmy.client.mobile.components.ErrorState
import media.watchmy.client.mobile.components.GoldTextButton
import media.watchmy.client.mobile.components.FilledButton
import media.watchmy.client.mobile.components.InlineError
import media.watchmy.client.mobile.components.LoadingState
import media.watchmy.client.mobile.components.MainTopBar
import media.watchmy.client.mobile.components.panel
import media.watchmy.client.model.LiveChannel
import media.watchmy.client.model.LiveClockFormat
import media.watchmy.client.model.LiveGuideFilter
import media.watchmy.client.model.LiveGuideRow
import media.watchmy.client.model.LiveProgramme
import media.watchmy.client.model.liveLeadBefore
import media.watchmy.client.model.liveNextLine
import media.watchmy.client.model.liveTVError
import media.watchmy.client.model.nextAt
import media.watchmy.client.model.onAt
import media.watchmy.client.presentation.live.LiveChannelsViewModel
import media.watchmy.client.presentation.live.LiveGuideViewModel
import media.watchmy.client.ui.Inter
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.mobile.components.PhoneIcons
import media.watchmy.client.ui.components.ProgrammePicture
import media.watchmy.client.ui.components.WmmIcons
import media.watchmy.client.ui.components.rememberLiveClockFormat
import media.watchmy.client.ui.components.rememberLiveNow
import media.watchmy.client.ui.rememberTabletLayout

/** The tablet's panel beside the guide from 1000 dp: the programme, Search listings or Reminders (TabLiveExtras). */
internal enum class ExtrasPanel { Programme, Search, Reminders, Recordings }

/**
 * The LiveGuide board: with listings (API level 13 and `capabilities.guide`) the
 * guide; without them, as L6 and L7 built it, channels watchable with no
 * programme data. With the extras ([LiveChannelsViewModel.State.extras], API
 * level 15) the top bar has Search listings and More (Reminders, Reminder
 * time), [onSearchListings], [onReminders] and [onReminderTime]; from
 * 1000 dp they are header buttons that switch the panel beside the guide.
 * [target] opens the guide at a programme once, then [onTargetShown].
 */
@Composable
fun LiveChannelsScreen(
    state: LiveChannelsViewModel.State,
    userName: String?,
    onSearch: (String) -> Unit,
    onSelect: (LiveChannel) -> Unit,
    onWatch: (LiveChannel) -> Unit,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    onSearchListings: () -> Unit = {},
    onReminders: () -> Unit = {},
    onReminderTime: () -> Unit = {},
    onRecordings: () -> Unit = {},
    onPlayRecording: (media.watchmy.client.model.PlayRequest) -> Unit = {},
    target: LiveGuideViewModel.Target? = null,
    onTargetShown: () -> Unit = {},
    castDevice: String? = null,
    notLiveDevice: String? = null,
) {
    val layout = rememberTabletLayout()
    var unavailable by remember { mutableStateOf<LiveChannel?>(null) }
    var panel by rememberSaveable { mutableStateOf(ExtrasPanel.Programme) }
    var menu by remember { mutableStateOf(false) }
    val reminders by LocalAppServices.current.live.reminders.collectAsStateWithLifecycle()
    val now = rememberLiveNow()
    val upcoming = reminders?.items.orEmpty().count { it.endMs > now }
    Column(modifier.fillMaxSize().background(WmmColors.Ground)) {
        // Cast shows only where the server lets this account cast a channel (PLAN-LIVE-TV.md §4.1).
        MainTopBar(
            "Live TV", userName,
            onSearch = onSearchListings.takeIf { state.extras && !layout.twoPane },
            onAvatar = onSettings, showCast = state.castable, searchLabel = "Search listings",
        ) {
            if (state.extras && layout.twoPane) {
                if (state.capabilities.canRecord) PanelToggle(WmmIcons.Record, "Recordings", panel == ExtrasPanel.Recordings) { panel = if (panel == ExtrasPanel.Recordings) ExtrasPanel.Programme else ExtrasPanel.Recordings }
                PanelToggle(WmmIcons.Search, "Search listings", panel == ExtrasPanel.Search) { panel = if (panel == ExtrasPanel.Search) ExtrasPanel.Programme else ExtrasPanel.Search }
                PanelToggle(WmmIcons.Bell, "Reminders, $upcoming", panel == ExtrasPanel.Reminders) { panel = if (panel == ExtrasPanel.Reminders) ExtrasPanel.Programme else ExtrasPanel.Reminders }
            } else if (state.extras) {
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp)) { Icon(PhoneIcons.MoreVert, contentDescription = "More", tint = WmmColors.Text) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = WmmColors.Surface2) {
                        if (state.capabilities.canRecord) DropdownMenuItem(text = { MenuRow(WmmIcons.Record, "Recordings", "") }, onClick = { menu = false; onRecordings() })
                        DropdownMenuItem(text = { MenuRow(WmmIcons.Bell, "Reminders", "$upcoming") }, onClick = { menu = false; onReminders() })
                        DropdownMenuItem(text = { MenuRow(WmmIcons.Clock, "Reminder time", liveLeadBefore(reminders?.leadMinutes ?: 5)) }, onClick = { menu = false; onReminderTime() })
                    }
                }
            }
        }
        if (!state.listings && state.capabilities.canRecord) GoldTextButton("Recordings", onRecordings, Modifier.padding(horizontal = 16.dp))
        when {
            state.loading && !state.available -> LoadingState()
            state.error != null && state.channels.isEmpty() -> ErrorState(state.error!!, onRefresh)
            !state.available -> EmptyState("Live TV isn't available", "An admin can set up a tuner and allow access in the web app.", action = "Check again", onAction = onRefresh)
            state.channels.isEmpty() -> EmptyState("No channels yet", "An admin can refresh the tuner’s lineup and enable channels in the web app.", action = "Check again", onAction = onRefresh)
            state.listings -> LiveGuideContent(state, onSearch, onSelect, onWatch = { channel ->
                if (channel.available) onWatch(channel) else unavailable = channel
            }, onRefresh, panel = panel.takeIf { state.extras } ?: ExtrasPanel.Programme, onPanel = { panel = it },
                onReminderTime = onReminderTime, onPlayRecording = onPlayRecording, target = target, onTargetShown = onTargetShown, castDevice = castDevice, notLiveDevice = notLiveDevice)
            else -> {
                if (state.error != null) InlineError(state.error!!, onRefresh)
                Row(Modifier.fillMaxSize().padding(horizontal = if (layout.isTablet) 24.dp else 16.dp), horizontalArrangement = if (layout.twoPane) Arrangement.spacedBy(24.dp) else Arrangement.Center) {
                    Column(if (layout.twoPane) Modifier.width(360.dp) else Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                        OutlinedTextField(
                            value = state.query,
                            onValueChange = onSearch,
                            label = { Text("Find a channel") },
                            leadingIcon = { Icon(WmmIcons.Search, contentDescription = null) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        )
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("${state.channels.size} channels", color = WmmColors.TextMuted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            TextButton(onClick = onRefresh, enabled = !state.loading) { Text(if (state.loading) "Checking…" else "Refresh", color = WmmColors.Accent) }
                        }
                        if (state.visibleChannels.isEmpty()) {
                            EmptyState("No matching channels", "Try the channel name or number.")
                        } else LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                            items(state.visibleChannels, key = { it.id }) { channel ->
                                LiveChannelRow(channel, selected = layout.twoPane && channel.id == state.selected?.id, onClick = {
                                    onSelect(channel)
                                    if (!layout.twoPane) {
                                        if (channel.available) onWatch(channel) else unavailable = channel
                                    }
                                })
                            }
                        }
                    }
                    if (layout.twoPane) state.selected?.let { channel ->
                        Column(Modifier.weight(1f).panel().padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(channel.number, color = WmmColors.Accent, fontFamily = Inter, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                            Text(channel.name, color = WmmColors.Text, fontFamily = Inter, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                            Text("No listings for this channel", color = WmmColors.TextMuted)
                            Text(channel.reason?.let(::liveTVError) ?: "Watch this channel live. Brief pauses use a rolling buffer. Recordings are managed separately from the live player.", color = WmmColors.TextMuted)
                            FilledButton("Watch live", { onWatch(channel) }, enabled = channel.available, icon = WmmIcons.Play, large = true)
                        }
                    }
                }
            }
        }
    }
    unavailable?.let { channel ->
        AlertDialog(
            onDismissRequest = { unavailable = null },
            title = { Text("${channel.number} · ${channel.name}") },
            text = { Text(channel.reason?.let(::liveTVError) ?: "This channel is unavailable. An admin can check the tuner in the web app.") },
            confirmButton = { TextButton(onClick = { unavailable = null; onRefresh() }) { Text("Check again", color = WmmColors.Accent) } },
            dismissButton = { TextButton(onClick = { unavailable = null }) { Text("Close", color = WmmColors.Accent) } },
        )
    }
}

@Composable
internal fun LiveChannelRow(channel: LiveChannel, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, selectedLabel: String = "Selected") {
    Row(
        modifier.fillMaxWidth().heightIn(min = 72.dp).panel(selected).clickable(role = Role.Button, onClick = onClick).semantics { this.selected = selected }.padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(channel.number, color = if (channel.available) WmmColors.Text else WmmColors.TextFaint, fontFamily = Inter, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.widthIn(min = 48.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(channel.name, color = WmmColors.Text, fontFamily = Inter, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (channel.available) "No listings available" else channel.reason?.let(::liveTVError) ?: "Channel unavailable", color = WmmColors.TextMuted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (selected) Text(selectedLabel, color = WmmColors.Accent, fontSize = 12.sp)
        else if (channel.available) Icon(WmmIcons.Play, contentDescription = "Watch live", tint = WmmColors.Accent)
    }
}

/** Home's channel row uses the same access gate as the Live TV destination; with listings it is On now. */
@Composable
fun LiveHomeRow(
    channels: List<LiveChannel>,
    onWatch: (LiveChannel) -> Unit,
    onSeeAll: () -> Unit,
    modifier: Modifier = Modifier,
    listings: Boolean = false,
    /** The tablet Home's row screen (PLAN-HOME.md §7.5): the row's list state, a snapping fling, the leading card to outline and "Row 2 of 7". */
    state: LazyListState = rememberLazyListState(),
    flingBehavior: FlingBehavior = ScrollableDefaults.flingBehavior(),
    lead: Int = -1,
    counter: String? = null,
) {
    if (listings) {
        LiveOnNowRow(channels, onWatch, onSeeAll, modifier, state, flingBehavior, lead, counter)
        return
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Live TV", color = WmmColors.Text, fontFamily = Inter, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = onSeeAll) { Text("All channels", color = WmmColors.Accent) }
        }
        if (channels.none { it.available }) Text("Channels are unavailable. Open Live TV for details.", color = WmmColors.TextMuted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(channels.filter { it.available }.take(12), key = { it.id }) { channel ->
                Column(Modifier.width(176.dp).clip(RoundedCornerShape(12.dp)).background(WmmColors.Surface).clickable(role = Role.Button) { onWatch(channel) }.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("LIVE · ${channel.number}", color = WmmColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(channel.name, color = WmmColors.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Watch channel", color = WmmColors.TextMuted, fontSize = 12.sp)
                }
            }
        }
    }
}

/** LiveHome: On now, each card the programme's picture with the channel's logo and number. */
@Composable
private fun LiveOnNowRow(
    channels: List<LiveChannel>,
    onWatch: (LiveChannel) -> Unit,
    onGuide: () -> Unit,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    flingBehavior: FlingBehavior = ScrollableDefaults.flingBehavior(),
    lead: Int = -1,
    counter: String? = null,
) {
    val now = rememberLiveNow()
    val format = rememberLiveClockFormat()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("On now", color = WmmColors.Text, fontFamily = Inter, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(listOfNotNull("Live TV · ${format.time(now)}", counter?.let { "· $it" }).joinToString(" "), color = WmmColors.TextMuted, fontSize = 13.sp, modifier = Modifier.weight(1f).padding(start = 8.dp))
            TextButton(onClick = onGuide) {
                Text("Guide", color = WmmColors.Accent, fontWeight = FontWeight.SemiBold)
                Icon(WmmIcons.ChevronRight, contentDescription = null, tint = WmmColors.Accent, modifier = Modifier.size(18.dp))
            }
        }
        if (channels.none { it.available }) Text("Channels are unavailable. Open Live TV for details.", color = WmmColors.TextMuted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp))
        LazyRow(state = state, flingBehavior = flingBehavior, contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(channels.filter { it.available }.take(12), key = { _, it -> it.id }) { i, channel ->
                OnNowCard(channel, now, onWatch = { onWatch(channel) }, modifier = Modifier.width(232.dp), outlined = i == lead)
            }
        }
    }
}

/** A header button that switches the tablet's panel, gold while its panel shows. */
@Composable
private fun PanelToggle(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(48.dp).semantics { contentDescription = label; selected = on },
        colors = IconButtonDefaults.iconButtonColors(containerColor = if (on) WmmColors.Accent.copy(alpha = .18f) else Color.Transparent, contentColor = if (on) WmmColors.Accent else WmmColors.Text),
    ) { Icon(icon, contentDescription = null) }
}

/** Which listing the tablet shows from 1000 dp: the grid, or channels with what is on. */
private enum class LiveView { Guide, Channels }

/** The guide at phone and tablet widths (LiveGuide, TabLiveGuide, TabLiveChannels). */
@Composable
private fun LiveGuideContent(
    channels: LiveChannelsViewModel.State,
    onSearch: (String) -> Unit,
    onSelect: (LiveChannel) -> Unit,
    onWatch: (LiveChannel) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    panel: ExtrasPanel = ExtrasPanel.Programme,
    onPanel: (ExtrasPanel) -> Unit = {},
    onReminderTime: () -> Unit = {},
    onPlayRecording: (media.watchmy.client.model.PlayRequest) -> Unit = {},
    target: LiveGuideViewModel.Target? = null,
    onTargetShown: () -> Unit = {},
    castDevice: String? = null,
    notLiveDevice: String? = null,
) {
    val services = LocalAppServices.current
    val model = viewModel { LiveGuideViewModel(services.live, services.servers, services.auth) }
    val state by model.state.collectAsStateWithLifecycle()
    val layout = rememberTabletLayout()
    val now = rememberLiveNow()
    val format = rememberLiveClockFormat()
    var view by rememberSaveable { mutableStateOf(LiveView.Guide) }
    var details by rememberSaveable { mutableStateOf(false) }
    val device = rememberLiveDevice()
    val context = LocalContext.current
    val ask = rememberReminderAsk()
    val extras = remember(model, device, context) {
        GuideExtras(
            onStar = model::toggleFavourite,
            onRemind = { channel, programme -> model.remind(programme); ask.after(context, channel, programme) },
            onForget = model::forget,
            device = device,
        )
    }.takeIf { state.personal && channels.extras }
    LaunchedEffect(target) {
        val t = target ?: return@LaunchedEffect
        model.open(t)
        view = LiveView.Guide
        if (!layout.twoPane) details = true
        onTargetShown()
    }
    LaunchedEffect(Unit) { model.show(true, channels.selectedId) }
    LaunchedEffect(layout.isTablet) { model.setSpan(if (layout.isTablet) 120 else 60) }
    val query = channels.query.trim()
    val rows = state.rows.filter { query.isEmpty() || it.channel.number.contains(query, ignoreCase = true) || it.channel.name.contains(query, ignoreCase = true) }
    val controls = remember(model) { GuideMoves(model::earlier, model::later, model::pickDay, model::toNow, model::filter) }
    val channelWidth = if (extras != null) 84.dp + 44.dp else 84.dp
    fun openAt(channelId: String, programmeId: String, startMs: Long) {
        model.open(LiveGuideViewModel.Target(channelId, programmeId, startMs))
        view = LiveView.Guide
        onPanel(ExtrasPanel.Programme)
    }
    fun refresh() {
        onRefresh()
        model.refresh()
    }
    fun pick(channel: LiveChannel, programme: LiveProgramme) {
        model.select(channel, programme)
        onSelect(channel)
        if (!layout.twoPane) details = true
    }
    Column(modifier.fillMaxSize()) {
        if (layout.twoPane) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${channels.channels.count { it.available }} channels", color = WmmColors.TextMuted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                SingleChoiceSegmentedButtonRow {
                    LiveView.entries.forEachIndexed { index, option ->
                        SegmentedButton(
                            selected = view == option, onClick = { view = option }, shape = SegmentedButtonDefaults.itemShape(index, LiveView.entries.size),
                            colors = SegmentedButtonDefaults.colors(activeContainerColor = WmmColors.Accent.copy(alpha = .18f), activeContentColor = WmmColors.Accent, activeBorderColor = WmmColors.TextFaint, inactiveContainerColor = WmmColors.Ground, inactiveContentColor = WmmColors.TextSoft, inactiveBorderColor = WmmColors.TextFaint),
                        ) { Text(option.name) }
                    }
                }
            }
        }
        channels.error?.let { InlineError(it, ::refresh) }
        state.error?.let { InlineError("Couldn’t load listings. Channels still play. $it", ::refresh) }
        state.notice?.let { InlineError(it, model::dismissNotice) }
        if (layout.twoPane) notLiveDevice?.let { NotLiveNote(it, tablet = true, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) }
        when {
            layout.twoPane && view == LiveView.Channels -> TabletChannels(channels, now, format, onSearch, onSelect, onWatch, Modifier.weight(1f), castDevice = castDevice)
            layout.twoPane -> Row(Modifier.weight(1f).padding(start = 24.dp, end = 24.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight().panel()) {
                    GuideControls(channels.query, state, format, onSearch, controls, Modifier.padding(12.dp))
                    GuideGrid(rows, state, now, format, ::pick, onWatch, controls, Modifier.weight(1f), extras = extras, channelWidth = channelWidth)
                }
                when (panel) {
                    ExtrasPanel.Recordings -> LiveRecordingsPanel(channels.channels, onClose = { onPanel(ExtrasPanel.Programme) }, onPlay = onPlayRecording, modifier = Modifier.width(380.dp).fillMaxHeight().panel())
                    ExtrasPanel.Search -> LiveSearchPanel(
                        onClose = { onPanel(ExtrasPanel.Programme) }, onWatch = onWatch,
                        onOpen = { hit -> openAt(hit.channel.id, hit.programme.id, hit.programme.startMs) },
                        modifier = Modifier.width(380.dp).fillMaxHeight().panel(), castDevice = castDevice,
                    )
                    ExtrasPanel.Reminders -> LiveRemindersPanel(
                        channels.channels, onClose = { onPanel(ExtrasPanel.Programme) },
                        onOpen = { r -> r.channelId?.let { openAt(it, r.programmeId, r.startMs) } }, onSettings = onReminderTime,
                        modifier = Modifier.width(380.dp).fillMaxHeight().panel(),
                    )
                    ExtrasPanel.Programme -> Column(Modifier.width(360.dp).fillMaxHeight().panel().verticalScroll(rememberScrollState()).padding(16.dp)) {
                        state.selection?.let { ProgrammeDetails(it, now, format, onWatch = { onWatch(it.channel) }, extras = extras, personal = state.personalFor(it), castDevice = castDevice) }
                            ?: Text("Pick a programme to see what it is.", color = WmmColors.TextMuted, fontSize = 14.sp)
                    }
                }
            }
            else -> LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
                // One item for what comes and goes above the grid, so its arrival doesn't scroll the list past it.
                item("top") {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (state.stale) StaleListingsNote(state.updatedAtMs, format)
                        notLiveDevice?.let { NotLiveNote(it, tablet = layout.isTablet) }
                        state.selection?.let { selection ->
                            ProgrammeSummary(selection, now, format, onWatch = { onWatch(selection.channel) }, onDetails = { details = true },
                                reminded = extras != null && state.reminderFor(selection.programme) != null, castDevice = castDevice)
                        }
                        GuideControls(channels.query, state, format, onSearch, controls)
                    }
                }
                stickyHeader("times") { GuideTimeHeader(state, format, controls.onEarlier, controls.onLater, channelWidth = channelWidth) }
                items(rows, key = { it.channel.id }) { row -> GuideRow(row, state, now, format, ::pick, onWatch, extras = extras) }
                if (rows.isEmpty()) item("none") { NoGuideRows(query, state.loading, Modifier.padding(16.dp)) }
            }
        }
    }
    if (details && !layout.twoPane) state.selection?.let { selection ->
        ProgrammeSheet(selection, now, format, onWatch = { details = false; onWatch(selection.channel) }, onClose = { details = false },
            extras = extras, personal = state.personalFor(selection), castDevice = castDevice)
    }
    ReminderAskDialog(ask, state.leadMinutes, device)
}

/** How the guide moves: through time, between days, and by category. */
@Immutable
private class GuideMoves(
    val onEarlier: () -> Unit,
    val onLater: () -> Unit,
    val onDay: (Int) -> Unit,
    val onNow: () -> Unit,
    val onFilter: (LiveGuideFilter) -> Unit,
)

/** Find a channel, the days and Now, and the category filters. */
@Composable
private fun GuideControls(query: String, state: LiveGuideViewModel.State, format: LiveClockFormat, onSearch: (String) -> Unit, moves: GuideMoves, modifier: Modifier = Modifier) {
    val offered = state.filters
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (state.stale && rememberTabletLayout().twoPane) StaleListingsNote(state.updatedAtMs, format)
        OutlinedTextField(
            value = query,
            onValueChange = onSearch,
            placeholder = { Text("Go to channel: 7.1 or a name") },
            leadingIcon = { Icon(WmmIcons.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Go to a channel by number or name" },
        )
        GuideDays(state, moves.onDay, moves.onNow)
        GuideFilters(state.shownFilter, moves.onFilter, offered = offered)
    }
}

/** The rows under the time header, with a now line, scrolling on their own beside a tablet's panel. */
@Composable
private fun GuideGrid(
    rows: List<LiveGuideRow>,
    state: LiveGuideViewModel.State,
    now: Long,
    format: LiveClockFormat,
    onPick: (LiveChannel, LiveProgramme) -> Unit,
    onWatch: (LiveChannel) -> Unit,
    moves: GuideMoves,
    modifier: Modifier = Modifier,
    extras: GuideExtras? = null,
    channelWidth: Dp = 84.dp,
) {
    Column(modifier) {
        GuideTimeHeader(state, format, moves.onEarlier, moves.onLater, channelWidth = channelWidth)
        LazyColumn(Modifier.weight(1f)) {
            items(rows, key = { it.channel.id }) { row -> GuideRow(row, state, now, format, onPick, onWatch, extras = extras) }
            if (rows.isEmpty()) item("none") { NoGuideRows("", state.loading, Modifier.padding(16.dp)) }
        }
    }
}

@Composable
private fun NoGuideRows(query: String, loading: Boolean, modifier: Modifier = Modifier) {
    Text(
        when {
            loading -> "Loading listings…"
            query.isNotEmpty() -> "No channel matches “$query”."
            else -> "No channels show this. Choose All channels to see every channel."
        },
        color = WmmColors.TextMuted, fontSize = 14.sp, modifier = modifier,
    )
}

/** TabLiveChannels: channels with what is on beside the selected channel's programme. */
@Composable
private fun TabletChannels(
    state: LiveChannelsViewModel.State,
    now: Long,
    format: LiveClockFormat,
    onSearch: (String) -> Unit,
    onSelect: (LiveChannel) -> Unit,
    onWatch: (LiveChannel) -> Unit,
    modifier: Modifier = Modifier,
    castDevice: String? = null,
) {
    Row(modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.width(380.dp).fillMaxHeight().panel()) {
            OutlinedTextField(
                value = state.query, onValueChange = onSearch, placeholder = { Text("Find a channel") },
                leadingIcon = { Icon(WmmIcons.Search, contentDescription = null) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(12.dp),
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text("Channels", color = WmmColors.Text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                Text("On now · ${format.time(now)}", color = WmmColors.TextMuted, fontSize = 12.sp)
            }
            LazyColumn(Modifier.weight(1f)) {
                items(state.visibleChannels, key = { it.id }) { channel ->
                    OnNowRow(channel, now, format, watching = false, onClick = { onSelect(channel) }, pictureWidth = 72.dp,
                        modifier = Modifier.background(if (channel.id == state.selected?.id) WmmColors.Surface2 else Color.Transparent))
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight().panel().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.selected?.let { channel ->
                val on = channel.onAt(now)
                if (on != null) ProgrammeDetails(LiveGuideViewModel.Selection(channel, on), now, format, onWatch = { onWatch(channel) }, castDevice = castDevice)
                else {
                    ProgrammePicture(null, channel, Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)))
                    Text("${channel.number} · ${channel.name}", color = WmmColors.Text, fontFamily = Inter, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text(if (channel.available) "No listings for this channel. It still plays." else channel.reason?.let(::liveTVError) ?: "Channel unavailable", color = WmmColors.TextMuted)
                    FilledButton(watchLabel(channel, castDevice), { onWatch(channel) }, Modifier.watchDescription(channel, castDevice), enabled = channel.available, icon = WmmIcons.Play, large = true)
                }
                channel.nextAt(now)?.let { Text(liveNextLine(it, format), color = WmmColors.TextMuted, fontSize = 13.sp) }
            }
        }
    }
}

/** A TV is connected that can't play Live TV: Watch plays here (the LiveCast board's notLive). */
@Composable
private fun NotLiveNote(device: String, tablet: Boolean, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(WmmColors.Surface2).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(WmmIcons.Info, contentDescription = null, tint = WmmColors.TextSoft, modifier = Modifier.size(18.dp))
        Text("$device can’t play Live TV yet, so channels play on ${if (tablet) "this tablet" else "this phone"}.", color = WmmColors.TextSoft, fontSize = 13.sp)
    }
}
