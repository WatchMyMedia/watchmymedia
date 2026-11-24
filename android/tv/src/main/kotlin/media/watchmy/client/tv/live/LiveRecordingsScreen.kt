package media.watchmy.client.tv.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import media.watchmy.client.model.LIVE_PERSONAL_API_LEVEL
import media.watchmy.client.model.LiveClockFormat
import media.watchmy.client.model.LiveRecording
import media.watchmy.client.model.LiveRecordingRule
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.RECORDING_CLASH_RUNNING
import media.watchmy.client.model.RECORDING_PADDING
import media.watchmy.client.tv.components.ArtProgress
import media.watchmy.client.tv.components.ArtWatched
import media.watchmy.client.model.RecordingOptions
import media.watchmy.client.model.RecordingSection
import media.watchmy.client.model.clashInsteadOf
import media.watchmy.client.model.clashIntro
import media.watchmy.client.model.clashKeep
import media.watchmy.client.model.clashKeepNote
import media.watchmy.client.model.formatDuration
import media.watchmy.client.model.line
import media.watchmy.client.model.setBy
import media.watchmy.client.presentation.live.LiveRecordingsViewModel
import media.watchmy.client.tv.components.ContentStart
import media.watchmy.client.tv.components.FocusGrowth
import media.watchmy.client.tv.components.FocusableBox
import media.watchmy.client.tv.components.InitialFocus
import media.watchmy.client.tv.components.MediaCard
import media.watchmy.client.tv.components.PanelRow
import media.watchmy.client.tv.components.RailSection
import media.watchmy.client.tv.components.SafeY
import media.watchmy.client.tv.components.TvButton
import media.watchmy.client.tv.components.TvButtonSize
import media.watchmy.client.tv.components.TvButtonStyle
import media.watchmy.client.tv.components.TvDialog
import media.watchmy.client.tv.components.TvPalette
import media.watchmy.client.tv.components.TvScaffold
import media.watchmy.client.tv.components.TvSidePanel
import media.watchmy.client.tv.components.TvType
import media.watchmy.client.tv.components.WideCardHeight
import media.watchmy.client.tv.components.WideCardWidth
import media.watchmy.client.tv.components.tryFocus
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.WmmIcons
import media.watchmy.client.ui.components.rememberLiveClockFormat
import media.watchmy.client.ui.components.rememberLiveNow

@Composable
internal fun rememberTvRecordingActions(): LiveRecordingsViewModel {
    val services = LocalAppServices.current
    return viewModel(key = "tv-recording-actions") { LiveRecordingsViewModel(services.live, services.servers, services.auth, loadSections = false) }
}

/**
 * The clash as a panel from the right: the recordings holding the tuners, a
 * Record this instead of … for each one that can still give way, and Keep
 * them. It never takes a tuner by itself; Back keeps the holders.
 */
@Composable
internal fun TvRecordingClash(clash: LiveRecording, model: LiveRecordingsViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val format = rememberLiveClockFormat()
    val first = remember(clash.id) { FocusRequester() }
    val choosable = clash.canCancel && clash.reason != "no_tuners"
    TvDialog(model::keepClash) {
        TvSidePanel(title = clash.title, kicker = "Won’t record", modifier = Modifier.focusGroup()) {
            Text(listOfNotNull("${format.weekday(clash.startMs)} ${format.span(clash.startMs, clash.endMs)} · ${clash.number}", clash.userName.takeIf { it.isNotBlank() }?.let { "Set by $it" }).joinToString(" · "), style = TvType.Second, color = TvPalette.Muted)
            Text(clash.clashIntro(), style = TvType.Body, color = WmmColors.TextSoft)
            if (clash.conflictsWith.isNotEmpty()) Column(Modifier.fillMaxWidth().background(WmmColors.Surface2, RoundedCornerShape(16.dp)).padding(horizontal = 22.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                clash.conflictsWith.forEach { holder ->
                    Text(listOfNotNull("${holder.title} on ${holder.line(format)}", holder.setBy(), RECORDING_CLASH_RUNNING.takeIf { holder.running }).joinToString(" · "), style = TvType.Second, color = WmmColors.TextSoft)
                }
            }
            val choices = if (!choosable) emptyList() else clash.yielding.map { holder -> clash.clashInsteadOf(holder) to { model.prefer(clash, holder) } } +
                (if (clash.conflictsWith.isEmpty()) listOf("Record this instead" to { model.prefer(clash) }) else emptyList())
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                choices.forEachIndexed { index, (label, choose) ->
                    PanelRow(label, choose, if (index == 0) Modifier.focusRequester(first) else Modifier, icon = WmmIcons.Record, iconTint = RecRim, enabled = !state.busy)
                }
                PanelRow(clash.clashKeep(), model::keepClash, if (choices.isEmpty()) Modifier.focusRequester(first) else Modifier, icon = WmmIcons.Close, description = clash.clashKeepNote().takeIf { clash.reason != "no_tuners" })
            }
            state.error?.let { Text(it, style = TvType.Second, color = WmmColors.Warn) }
        }
        InitialFocus(first)
    }
}

@Composable
private fun TvRecordingOptions(title: String, initial: RecordingOptions, series: Boolean, onSave: (RecordingOptions) -> Unit, onDismiss: () -> Unit) {
    var options by remember { mutableStateOf(initial) }
    val first = remember { FocusRequester() }
    fun nextPad(value: Int) = RECORDING_PADDING[(RECORDING_PADDING.indexOf(value) + 1) % RECORDING_PADDING.size]
    TvDialog(onDismiss) {
        TvSidePanel(title = title, kicker = "Recording options", context = "OK changes a choice. Save keeps them.", modifier = Modifier.focusGroup()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (series) {
                    PanelRow(if (options.newOnly) "New episodes only" else "Every episode", { options = options.copy(newOnly = !options.newOnly) }, Modifier.focusRequester(first))
                    PanelRow(if (options.anyChannel) "On any channel" else "On this channel", { options = options.copy(anyChannel = !options.anyChannel) })
                    PanelRow(if (options.keep == 0) "Keep every recording" else "Keep the latest ${options.keep}", { options = options.copy(keep = (options.keep + 1) % 11) })
                }
                PanelRow("Start early: ${options.padBefore} min", { options = options.copy(padBefore = nextPad(options.padBefore), customPadding = true) }, if (!series) Modifier.focusRequester(first) else Modifier)
                PanelRow("End late: ${options.padAfter} min", { options = options.copy(padAfter = nextPad(options.padAfter), customPadding = true) })
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                TvButton("Save", { onSave(options) }, style = TvButtonStyle.Primary)
                TvButton("Cancel", onDismiss)
            }
        }
        InitialFocus(first)
    }
}

/** Recordings' own tabs: what can be watched, what is set to record, and the series that record by themselves. */
internal enum class RecordingsTab(val label: String) { Ready("Ready to watch"), Scheduled("Scheduled"), Series("Series") }

/** A card of Ready to watch: one recording, or a show's several, latest first. */
internal data class RecordingGroup(val title: String, val items: List<LiveRecording>) {
    val latest: LiveRecording get() = items.first()
    val key: String get() = if (items.size == 1) "r:${latest.id}" else "g:$title"
}

/**
 * Ready to watch, in its order: the one recording now first, then the rest
 * latest first, a show's recordings together on one card.
 */
internal fun readyGroups(recordingNow: List<LiveRecording>, recorded: List<LiveRecording>): List<RecordingGroup> {
    val now = recordingNow.sortedByDescending { it.startMs }.map { RecordingGroup(it.title, listOf(it)) }
    val done = recorded.sortedByDescending { it.startMs }.groupBy { it.title.trim().lowercase() }.values.map { RecordingGroup(it.first().title, it) }
        .sortedByDescending { it.latest.startMs }
    return now + done
}

/** "S2 E4 · Undertow", or just its title: an episode's line. */
internal fun LiveRecording.episodeLine(): String? {
    val code = if (season != null && episode != null) "S$season E$episode" else null
    return listOfNotNull(code, episodeTitle?.takeIf { it.isNotBlank() }).joinToString(" · ").ifEmpty { null }
}

/** "Tonight 9:00 PM", "Thursday 7:30 PM": when a scheduled recording starts. */
internal fun whenLine(startMs: Long, nowMs: Long, format: LiveClockFormat): String = "${programmeDay(startMs, nowMs, format)} ${format.time(startMs)}"

/**
 * Live TV's Recordings: three tabs, Ready to watch (the one recording now
 * first, a show's recordings on one card), Scheduled and Series. A clash
 * says which recording loses, one ▲ above the list, and Choose which to
 * keep opens the choice. Focus starts on the first recording; with none,
 * on the tab. OK on a recording opens its choices, Watch first; on a show
 * with several, its episodes.
 */
@Composable
fun LiveRecordingsScreenTv(onNavigate: (Any) -> Unit, onPlay: (PlayRequest) -> Unit, modifier: Modifier = Modifier) {
    val services = LocalAppServices.current
    val model = viewModel { LiveRecordingsViewModel(services.live, services.servers, services.auth) }
    val state by model.state.collectAsStateWithLifecycle()
    val server by services.servers.current.collectAsStateWithLifecycle()
    val now = rememberLiveNow()
    val format = rememberLiveClockFormat()
    var tab by rememberSaveable { mutableStateOf(RecordingsTab.Ready) }
    var focusedGroup by remember { mutableStateOf<RecordingGroup?>(null) }
    var menu by remember { mutableStateOf<LiveRecording?>(null) }
    var episodes by remember { mutableStateOf<RecordingGroup?>(null) }
    var ruleMenu by remember { mutableStateOf<LiveRecordingRule?>(null) }
    var editing by remember { mutableStateOf<LiveRecordingRule?>(null) }
    var deleting by remember { mutableStateOf<LiveRecording?>(null) }
    var recordAgain by remember { mutableStateOf(false) }
    val topTab = remember { FocusRequester() }
    val subTabs = remember { RecordingsTab.entries.associateWith { FocusRequester() } }
    val firstItem = remember { FocusRequester() }
    val clashButton = remember { FocusRequester() }

    val ready = readyGroups(state.page(RecordingSection.Now).items, state.page(RecordingSection.Recorded).items)
    val scheduled = state.page(RecordingSection.Conflicts).items + state.page(RecordingSection.Upcoming).items.sortedBy { it.startMs }
    val rules = RecordingSection.entries.flatMap { state.page(it).rules }.distinctBy { it.id }
    val clash = state.page(RecordingSection.Conflicts).items.firstOrNull()?.takeIf { tab != RecordingsTab.Series }
    val count = mapOf(
        RecordingsTab.Ready to ready.sumOf { it.items.size },
        RecordingsTab.Scheduled to scheduled.size,
        RecordingsTab.Series to rules.size,
    )
    val personal = (server?.server?.apiLevel ?: 0) >= LIVE_PERSONAL_API_LEVEL
    val empty = when (tab) {
        RecordingsTab.Ready -> ready.isEmpty()
        RecordingsTab.Scheduled -> scheduled.isEmpty()
        RecordingsTab.Series -> rules.isEmpty()
    }

    // ▲ from the first row: the clash's Choose which to keep, else the tabs.
    fun topUp(): Boolean = if (clash != null) clashButton.tryFocus() else subTabs.getValue(tab).tryFocus()

    TvScaffold(RailSection.LiveTV, onNavigate, modifier) {
        Column(Modifier.fillMaxSize().padding(start = ContentStart, top = SafeY)) {
            LiveTabsHeader(LiveTab.Recordings, liveTabs(recordings = true, reminders = personal), onNavigate, first = topTab, onShowing = { subTabs.getValue(tab).tryFocus() })
            if (!state.canRecord && !state.loading) {
                LiveStateMessage(
                    "Recording is off",
                    if (state.available) "Whoever looks after this server can turn recording on, in the web app’s Live TV settings." else "This server or account can’t record Live TV.",
                    Modifier.padding(top = 150.dp),
                ) {
                    TvButton("Back to the guide", { onNavigate(LiveTab.Guide.route) }, Modifier.focusRequester(firstItem), style = TvButtonStyle.Primary, growFrom = TransformOrigin(0f, 0.5f))
                }
                InitialFocus(firstItem)
                return@Column
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 28.dp, end = TvSafeEnd).onPreviewKeyEvent { event ->
                    // ▼ from the tabs: the clash, else the first recording.
                    event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown && (if (clash != null) clashButton.tryFocus() else firstItem.tryFocus())
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RecordingsTab.entries.forEachIndexed { index, t ->
                    SubTab(
                        t.label, count[t] ?: 0, selected = t == tab,
                        onFocus = { tab = t },
                        onClick = { firstItem.tryFocus() },
                        modifier = Modifier.focusRequester(subTabs.getValue(t)),
                        first = index == 0,
                    )
                }
                // How much more fits, past the space the recorder keeps free: from servers that say what it keeps.
                val room = state.page(RecordingSection.Recorded).let { recorded ->
                    media.watchmy.client.model.recordingRoomLine(recorded.copy(freeBytes = recorded.freeBytes ?: state.page(RecordingSection.Now).freeBytes))
                }
                if (room != null) {
                    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    Text(room, style = TvType.Second, color = TvPalette.Muted, maxLines = 1)
                }
            }
            clash?.let { c ->
                ClashNotice(
                    c, now, format, onChoose = { model.chooseClash(c) }, button = clashButton,
                    onUp = { subTabs.getValue(tab).tryFocus() }, onDown = { firstItem.tryFocus() },
                    modifier = Modifier.padding(top = 26.dp, end = TvSafeEnd),
                )
            }
            state.error?.let { Text(it, style = TvType.Second, color = WmmColors.Warn, modifier = Modifier.padding(top = 16.dp)) }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.loading && state.pages.isEmpty() -> Text("Getting your recordings…", style = TvType.Body, color = TvPalette.Muted, modifier = Modifier.padding(top = 36.dp))
                    empty -> Text(
                        when (tab) {
                            RecordingsTab.Ready -> "Nothing recorded yet. Record a programme from the guide: OK on a later programme, or Hold OK on any."
                            RecordingsTab.Scheduled -> "Nothing is set to record. Recordings are set from the guide."
                            RecordingsTab.Series -> "No series record by themselves yet. Record every new episode from a programme’s choices in the guide."
                        },
                        style = TvType.Body, color = WmmColors.TextSoft, modifier = Modifier.padding(top = 36.dp).widthIn(max = 1100.dp),
                    )
                    tab == RecordingsTab.Ready -> Column {
                        // The focused card's facts, one line above the grid.
                        (focusedGroup ?: ready.first()).let { FocusedRecording(it, now, format, Modifier.padding(top = 20.dp, end = TvSafeEnd)) }
                        ReadyGrid(ready, now, format, firstItem, onTopUp = ::topUp, onFocus = { focusedGroup = it }) { group ->
                            if (group.items.size > 1) episodes = group else menu = group.latest
                        }
                    }
                    tab == RecordingsTab.Scheduled -> RecordingList(
                        scheduled.map { r ->
                            RecordingListRow(
                                key = r.id, whenText = whenLine(r.startMs, now, format), title = r.title,
                                detail = listOfNotNull(r.episodeLine(), "${r.number} ${r.channelName}".trim(), "series".takeIf { r.series }).joinToString(" · "),
                                warn = r.state == "conflict",
                            ) { menu = r }
                        },
                        firstItem, hint = "Change or cancel", onTopUp = ::topUp,
                    )
                    else -> RecordingList(
                        rules.map { rule ->
                            RecordingListRow(
                                key = rule.id, whenText = if (rule.kind == "series") (if (rule.newOnly) "Every new episode" else "Every episode") else rule.label, title = rule.title,
                                detail = listOfNotNull(if (rule.anyChannel) "Any channel" else rule.number, if (rule.keep == 0) "keeps every recording" else "keeps the latest ${rule.keep}", rule.userName.takeIf { it.isNotBlank() }?.let { "set by $it" }).joinToString(" · "),
                                warn = false,
                            ) { ruleMenu = rule }
                        },
                        firstItem, hint = "Change or stop the series", onTopUp = ::topUp,
                    )
                }
            }
            val more = when (tab) {
                RecordingsTab.Ready -> listOf(RecordingSection.Now, RecordingSection.Recorded)
                RecordingsTab.Scheduled -> listOf(RecordingSection.Conflicts, RecordingSection.Upcoming)
                RecordingsTab.Series -> listOf(RecordingSection.Series)
            }.firstOrNull { state.page(it).nextCursor != null }
            if (more != null) TvButton(if (state.paging == more) "Getting more…" else "Show more", { model.next(more) }, Modifier.padding(bottom = SafeY), size = TvButtonSize.Small, enabled = state.paging == null)
        }
    }
    // Focus starts on the first recording, once they are in; with none, on the tab, so the remote always has somewhere to be.
    // A tab that shows by taking focus keeps it.
    var placed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.pages.isEmpty(), state.loading, state.canRecord) {
        if (placed || (state.pages.isEmpty() && (state.loading || state.canRecord))) return@LaunchedEffect
        withFrameNanos { }
        placed = if (empty) subTabs.getValue(tab).tryFocus() else firstItem.tryFocus()
    }
    menu?.let { recording ->
        val actions = listOfNotNull(
            recording.playRequest()?.let { request -> TvMenuItem(if (recording.active) "Watch from the start" else "Watch", WmmIcons.Play) { menu = null; onPlay(request) } },
            if (recording.canCancel && recording.state == "conflict") TvMenuItem("Choose which to keep…", WmmIcons.Record, enabled = !state.busy) { model.chooseClash(recording); menu = null } else null,
            if (recording.canCancel && recording.state in listOf("scheduled", "recording", "conflict", "gone")) TvMenuItem(if (recording.active) "Stop recording · keep what’s recorded" else "Don’t record", WmmIcons.Close, enabled = !state.busy) { model.cancel(recording); menu = null } else null,
            if (recording.canDelete && recording.state in listOf("done", "partial")) TvMenuItem("Delete recording…", WmmIcons.Trash) { recordAgain = false; deleting = recording; menu = null } else null,
        )
        TvLiveMenu(
            recording.title, listOfNotNull(recording.episodeLine(), "${format.dayTime(recording.startMs)} · ${recording.number} ${recording.channelName}".trim(), recording.label).joinToString(" · "), null,
            actions.ifEmpty { listOf(TvMenuItem("Close", WmmIcons.Close) { menu = null }) },
            onDismiss = { menu = null },
        )
    }
    episodes?.let { group ->
        val first = remember(group.key) { FocusRequester() }
        TvDialog({ episodes = null }) {
            TvSidePanel(title = group.title, context = "${group.items.size} recordings, latest first", modifier = Modifier.focusGroup()) {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(vertical = 8.dp, horizontal = 8.dp)) {
                    itemsIndexed(group.items, key = { _, it -> it.id }) { i, r ->
                        PanelRow(
                            r.episodeLine() ?: format.dayTime(r.startMs),
                            { episodes = null; menu = r },
                            if (i == 0) Modifier.focusRequester(first) else Modifier,
                            description = "${programmeDay(r.startMs, now, format)} · ${formatDuration(r.endMs - r.startMs)} · ${r.label}",
                        )
                    }
                }
            }
            InitialFocus(first)
        }
    }
    ruleMenu?.let { rule ->
        TvLiveMenu(
            rule.title, rule.label, null,
            if (rule.canCancel) listOf(
                TvMenuItem("Options", WmmIcons.Settings) { editing = rule; ruleMenu = null },
                TvMenuItem("Stop this series · keep its recordings", WmmIcons.Close) { model.delete(rule); ruleMenu = null },
            ) else listOf(TvMenuItem("Close", WmmIcons.Close) { ruleMenu = null }),
            onDismiss = { ruleMenu = null },
            note = if (rule.canCancel) null else "Someone else set this series, so only they or an admin can change it.",
        )
    }
    // Deleting asks first, naming which recording goes, and opens on the choice that changes nothing.
    // Only its own Delete is marked; Delete recording… in the recording's choices is plain, and last.
    deleting?.let { recording ->
        val which = listOfNotNull(recording.episodeLine(), format.dayTime(recording.startMs)).joinToString(" · ")
        TvLiveMenu(
            recording.title, "$which. Delete the recorded file? This can’t be undone.", null,
            listOf(
                TvMenuItem("Keep it", WmmIcons.Close) { deleting = null },
                TvMenuItem(if (recordAgain) "Don’t record it again: on" else "Don’t record it again: off", WmmIcons.Record) { recordAgain = !recordAgain },
                TvMenuItem("Delete recording", WmmIcons.Trash, warn = true) { model.deleteFile(recording, recordAgain); deleting = null },
            ),
            onDismiss = { deleting = null },
        )
    }
    editing?.let { rule -> TvRecordingOptions(rule.title, rule.options, rule.kind == "series", onSave = { model.update(rule, it); editing = null }, onDismiss = { editing = null }) }
    state.clash?.let { c -> TvRecordingClash(c, model) }
}

/** One of Recordings' own tabs: its words and how many, a gold underline when it is the one showing; focus shows it. */
@Composable
private fun SubTab(label: String, count: Int, selected: Boolean, onFocus: () -> Unit, onClick: () -> Unit, modifier: Modifier = Modifier, first: Boolean = false) {
    FocusableBox(
        onClick = onClick,
        modifier = modifier.padding(end = 24.dp).onFocusChanged { if (it.isFocused) onFocus() }.semantics {
            this.selected = selected
            role = Role.Tab
            contentDescription = "$label, $count"
        },
        cornerRadius = 14.dp,
        focusedScale = FocusGrowth.BUTTON,
        growFrom = if (first) TransformOrigin(0f, 0.5f) else TransformOrigin.Center,
    ) { focused ->
        Row(
            Modifier.heightIn(min = 56.dp)
                .background(if (focused) TvPalette.Lift else Color.Transparent, RoundedCornerShape(14.dp))
                .padding(horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, style = TvType.BodyStrong, color = if (selected || focused) WmmColors.Text else TvPalette.Muted, maxLines = 1)
                    Text("$count", style = TvType.Caption.copy(fontWeight = FontWeight.Medium), color = if (selected || focused) WmmColors.TextSoft else TvPalette.Muted, maxLines = 1)
                }
                Box(Modifier.padding(top = 6.dp).height(4.dp).width(if (selected) 120.dp else 0.dp).background(WmmColors.Accent, RoundedCornerShape(2.dp)))
            }
        }
    }
}

/**
 * A clash, in orange: which recording won't record and why, and Choose
 * which to keep. It takes no focus by itself; it is one ▲ above the list,
 * and from it ▲ goes to the tabs and ▼ to the first recording.
 */
@Composable
private fun ClashNotice(
    clash: LiveRecording,
    now: Long,
    format: LiveClockFormat,
    onChoose: () -> Unit,
    button: FocusRequester,
    onUp: () -> Boolean,
    onDown: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier.fillMaxWidth().background(WmmColors.Warn.copy(alpha = .1f), shape).border(2.dp, WmmColors.Warn, shape).padding(horizontal = 26.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(WmmIcons.Alert, contentDescription = null, tint = WmmColors.Warn, modifier = Modifier.size(32.dp))
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Color.White)) { append("${clash.title} won’t record ${programmeDay(clash.startMs, now, format).lowercase()}.") }
                append(" ")
                append(clash.clashIntro())
            },
            style = TvType.Second, color = WmmColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        TvButton(
            "Choose which to keep", onChoose,
            Modifier.focusRequester(button).onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionUp -> onUp()
                    Key.DirectionDown -> onDown()
                    // Nothing beside it.
                    Key.DirectionLeft, Key.DirectionRight -> true
                    else -> false
                }
            },
            size = TvButtonSize.Small,
        )
    }
}

/** Above Ready to watch's grid: the focused card's title, its episode and what OK does. */
@Composable
private fun FocusedRecording(group: RecordingGroup, now: Long, format: LiveClockFormat, modifier: Modifier = Modifier) {
    val r = group.latest
    FactsRow(
        buildList {
            add { Text(group.title, style = TvType.CardTitle, color = WmmColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 560.dp)) }
            if (group.items.size == 1) r.episodeLine()?.let { add { Text(it, style = TvType.Body, color = WmmColors.TextSoft, maxLines = 1) } }
            add {
                Text(
                    when {
                        group.items.size > 1 -> "${group.items.size} recordings"
                        r.active -> "Recording now, ${formatDuration(r.elapsedMs(now))} so far"
                        else -> "${programmeDay(r.startMs, now, format)} · ${formatDuration(r.endMs - r.startMs)}"
                    },
                    style = TvType.BodyStrong, color = WmmColors.Text, maxLines = 1,
                )
            }
            add { Text(if (group.items.size > 1) "OK opens its recordings" else if (r.active) "OK watches from the start" else "OK watches it", style = TvType.Body, color = WmmColors.TextSoft, maxLines = 1) }
        },
        modifier.height(44.dp),
    )
}

/** Ready to watch: four across, the next row peeking, under the focused card's facts. */
@Composable
private fun ReadyGrid(groups: List<RecordingGroup>, now: Long, format: LiveClockFormat, first: FocusRequester, onTopUp: () -> Boolean, onFocus: (RecordingGroup) -> Unit, onOpen: (RecordingGroup) -> Unit) {
    val live = LocalAppServices.current.live
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        modifier = Modifier.width(WideCardWidth * 4 + 40.dp * 3 + 24.dp).fillMaxSize(),
        // Room above for the facts line and the focused card's growth; none below, so the next row peeks.
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 28.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(40.dp),
        verticalArrangement = Arrangement.spacedBy(36.dp),
    ) {
        itemsIndexed(groups, key = { _, it -> it.key }) { i, group ->
            val r = group.latest
            MediaCard(
                title = group.title,
                subtitle = when {
                    r.active -> "Recording · ${media.watchmy.client.model.liveMinutesLeft(now, r.endMs)}"
                    group.items.size > 1 -> "Latest: ${programmeDay(r.startMs, now, format).let { if (it == "Today" || it == "Tonight") it.lowercase() else it }}"
                    else -> "${programmeDay(r.startMs, now, format)} · ${formatDuration(r.endMs - r.startMs)}"
                },
                imageUrl = live.artUrl(r.picture),
                artWidth = WideCardWidth,
                artHeight = WideCardHeight,
                seed = group.title,
                onClick = { onOpen(group) },
                modifier = Modifier.onFocusChanged { if (it.isFocused) onFocus(group) }.then(if (i == 0) Modifier.focusRequester(first) else Modifier).then(if (i < 4) Modifier.upTo(onTopUp) else Modifier),
            ) {
                if (r.active) PictureFlag("REC", Modifier.align(Alignment.TopStart).padding(10.dp), rec = true, spoken = "Recording")
                // A first airing nobody here has watched yet, from servers that keep the listing's New flag.
                else if (group.items.any { it.isNew && !it.watched && it.watchedProgress == null }) NewFlag(Modifier.align(Alignment.TopStart).padding(10.dp))
                if (group.items.size > 1) PictureFlag("${group.items.size} recordings", Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = 16.dp))
                if (r.active) media.watchmy.client.ui.components.LiveProgress(r.progressAt(now), Modifier.align(Alignment.BottomStart).fillMaxWidth(), height = 8.dp, track = WmmColors.Ground.copy(alpha = .62f))
                else if (group.items.size == 1) {
                    // How far the person has watched it, as any title's card shows; a tick once watched to the end.
                    ArtProgress(r.watchedProgress?.takeIf { !r.watched })
                    ArtWatched(r.watched)
                }
            }
        }
    }
}

/** ▲ on this goes where [onUp] says, when it can; a focus search would pass over the clash for the tabs above. */
private fun Modifier.upTo(onUp: () -> Boolean): Modifier = onPreviewKeyEvent { event ->
    event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp && onUp()
}

/** One row of Scheduled or Series. */
private class RecordingListRow(val key: String, val whenText: String, val title: String, val detail: String, val warn: Boolean, val onClick: () -> Unit)

/** Scheduled or Series: a row each, when first, then what and where; OK opens its choices. */
@Composable
private fun RecordingList(rows: List<RecordingListRow>, first: FocusRequester, hint: String, onTopUp: () -> Boolean) {
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.weight(1f).padding(end = 120.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(top = 22.dp, bottom = 12.dp, start = 8.dp, end = 8.dp),
        ) {
            itemsIndexed(rows, key = { _, it -> it.key }) { i, row ->
                FocusableBox(
                    onClick = row.onClick,
                    modifier = Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(first).upTo(onTopUp) else Modifier),
                    cornerRadius = 16.dp,
                    focusedScale = FocusGrowth.ROW,
                    growFrom = TransformOrigin(0f, 0.5f),
                    role = Role.Button,
                ) { focused ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 80.dp).background(if (focused) TvPalette.Lift else Color.Transparent, RoundedCornerShape(16.dp)).padding(horizontal = 24.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(row.whenText, style = TvType.Second.copy(fontWeight = FontWeight.SemiBold), color = WmmColors.Text, maxLines = 2, modifier = Modifier.width(250.dp))
                        Column(Modifier.weight(1f)) {
                            Text(row.title, style = TvType.Body.copy(fontWeight = FontWeight.Medium), color = WmmColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (row.detail.isNotBlank()) Text(row.detail, style = TvType.Caption, color = TvPalette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (row.warn) WarnTag("Won’t record")
                    }
                }
            }
        }
        KeyHints(listOf(KeyHint("OK", hint)), Modifier.padding(top = 8.dp, bottom = SafeY))
    }
}
