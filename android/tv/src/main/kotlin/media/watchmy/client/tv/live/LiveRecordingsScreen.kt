package media.watchmy.client.tv.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Text
import media.watchmy.client.model.*
import media.watchmy.client.presentation.live.LiveRecordingsViewModel
import media.watchmy.client.tv.components.*
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.Artwork
import media.watchmy.client.ui.components.LiveProgress
import media.watchmy.client.ui.components.WmmIcons
import media.watchmy.client.ui.components.rememberLiveClockFormat
import media.watchmy.client.ui.components.rememberLiveNow

@Composable
internal fun rememberTvRecordingActions(): LiveRecordingsViewModel {
    val services = LocalAppServices.current
    return viewModel(key = "tv-recording-actions") { LiveRecordingsViewModel(services.live, services.servers, services.auth, loadSections = false) }
}

/** TV choices keep every control reachable using the remote; time entry belongs on phone/web. */
@Composable
internal fun TvRecordingChoice(programme: LiveProgramme, model: LiveRecordingsViewModel, onDismiss: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    var options by remember(programme.id) { mutableStateOf(RecordingOptions(series = true)) }
    var showingOptions by remember { mutableStateOf(false) }
    val entries = buildList {
        add(TvMenuItem("Just this episode", WmmIcons.Record, enabled = !state.busy) { model.record(programme, options.copy(series = false)); onDismiss() })
        if (programme.offersSeries) {
            add(TvMenuItem(if (options.newOnly) "Every new episode" else "Every episode", WmmIcons.Record, enabled = !state.busy) { model.record(programme, options); onDismiss() })
        }
        add(TvMenuItem("Options", WmmIcons.Settings) { showingOptions = true })
    }
    if (showingOptions) TvRecordingOptions(programme.title, options, series = programme.offersSeries, onSave = { options = it; showingOptions = false }, onDismiss = { showingOptions = false }, allowServerDefaults = true)
    else TvLiveMenu(programme.title, "Record this programme", null, entries, onDismiss, "Recordings take priority over live viewers. If the tuners are taken then, you choose which recording gives way.")
}

/**
 * The clash as a side panel, from the web's DvrTakeover clash dialog and the
 * TV guide board's Won't record panel (Android's canvas has no clash board):
 * the recordings holding the tuners, a Record this instead of … for each
 * one that can still give way, and Keep them. It never takes a tuner by
 * itself; Back keeps the holders.
 */
@Composable
internal fun TvRecordingClash(clash: LiveRecording, model: LiveRecordingsViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val format = rememberLiveClockFormat()
    val first = remember(clash.id) { FocusRequester() }
    val choosable = clash.canCancel && clash.reason != "no_tuners"
    TvDialog(model::keepClash) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .5f)), contentAlignment = Alignment.CenterEnd) {
            Column(
                Modifier.width(640.dp).fillMaxHeight().background(WmmColors.Surface).border(1.dp, WmmColors.Line)
                    .padding(start = 44.dp, end = 44.dp, top = 56.dp, bottom = 40.dp).focusGroup(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("WON’T RECORD", style = inter(16.sp, FontWeight.Bold), color = ClashTone)
                Text(clash.title, style = inter(30.sp, FontWeight.Bold), color = WmmColors.Text)
                Text(listOfNotNull("${format.weekday(clash.startMs)} ${format.span(clash.startMs, clash.endMs)} · ${clash.number}", clash.userName.takeIf { it.isNotBlank() }?.let { "Set by $it" }).joinToString(" · "), style = inter(18.sp), color = WmmColors.TextMuted)
                Text(clash.clashIntro(), style = inter(19.sp, lineHeight = 27.sp), color = WmmColors.TextSoft)
                if (clash.conflictsWith.isNotEmpty()) Column(Modifier.fillMaxWidth().background(WmmColors.Text.copy(alpha = .05f), RoundedCornerShape(14.dp)).padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    clash.conflictsWith.forEach { holder ->
                        Text(listOfNotNull("${holder.title} on ${holder.line(format)}", holder.setBy(), RECORDING_CLASH_RUNNING.takeIf { holder.running }).joinToString(" · "), style = inter(18.sp), color = WmmColors.TextSoft)
                    }
                }
                val choices = if (!choosable) emptyList() else clash.yielding.map { holder -> clash.clashInsteadOf(holder) to { model.prefer(clash, holder) } } +
                    (if (clash.conflictsWith.isEmpty()) listOf("Record this instead" to { model.prefer(clash) }) else emptyList())
                choices.forEachIndexed { index, (label, choose) ->
                    TvButton(label, choose, if (index == 0) Modifier.focusRequester(first) else Modifier, icon = WmmIcons.Record, size = TvButtonSize.Medium, enabled = !state.busy)
                }
                TvButton(clash.clashKeep(), model::keepClash, if (choices.isEmpty()) Modifier.focusRequester(first) else Modifier, icon = WmmIcons.Close, size = TvButtonSize.Medium)
                state.error?.let { Text(it, style = inter(18.sp), color = WmmColors.Error) }
                Text(listOfNotNull(clash.clashKeepNote().takeIf { clash.reason != "no_tuners" }, "Back closes this panel.").joinToString(" "), style = inter(16.sp), color = WmmColors.TextMuted)
            }
        }
        InitialFocus(first)
    }
}

/** A clash's orange (PLAN-LIVE-TV-DVR.md §3.1, Colours). */
private val ClashTone = Color(0xFFFDBA74)

@Composable
private fun TvRecordingOptions(title: String, initial: RecordingOptions, series: Boolean, onSave: (RecordingOptions) -> Unit, onDismiss: () -> Unit, allowServerDefaults: Boolean = false) {
    var options by remember { mutableStateOf(initial) }
    val first = remember { FocusRequester() }
    fun nextPad(value: Int) = RECORDING_PADDING[(RECORDING_PADDING.indexOf(value) + 1) % RECORDING_PADDING.size]
    TvDialog(onDismiss) {
        Box(Modifier.fillMaxSize().background(WmmColors.Ground.copy(alpha = .9f)), contentAlignment = Alignment.Center) {
            Column(Modifier.width(800.dp).background(WmmColors.Surface2, RoundedCornerShape(20.dp)).padding(32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = inter(32.sp, FontWeight.Bold), color = WmmColors.Text)
                Text("Recording options · OK changes a choice", style = inter(19.sp), color = WmmColors.TextMuted)
                if (series) {
                    TvButton(if (options.newOnly) "New episodes only" else "Every episode", { options = options.copy(newOnly = !options.newOnly) }, Modifier.focusRequester(first))
                    TvButton(if (options.anyChannel) "Any channel" else "This channel", { options = options.copy(anyChannel = !options.anyChannel) })
                    TvButton(if (options.keep == 0) "Keep all recordings" else "Keep the latest ${options.keep}", { options = options.copy(keep = (options.keep + 1) % 11) })
                }
                if (allowServerDefaults) TvButton(if (options.customPadding) "Use custom padding" else "Use server padding defaults", { options = options.copy(customPadding = !options.customPadding) }, if (!series) Modifier.focusRequester(first) else Modifier)
                if (!allowServerDefaults || options.customPadding) {
                    TvButton("Start early: ${options.padBefore} min", { options = options.copy(padBefore = nextPad(options.padBefore), customPadding = true) }, if (!series && !allowServerDefaults) Modifier.focusRequester(first) else Modifier)
                    TvButton("End late: ${options.padAfter} min", { options = options.copy(padAfter = nextPad(options.padAfter), customPadding = true) })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    TvButton("Save options", { onSave(options) }, style = TvButtonStyle.Primary)
                    TvButton("Cancel", onDismiss)
                }
            }
        }
        InitialFocus(first)
    }
}

@Composable
fun LiveRecordingsScreenTv(onNavigate: (Any) -> Unit, onPlay: (PlayRequest) -> Unit, modifier: Modifier = Modifier) {
    val services = LocalAppServices.current
    val model = viewModel { LiveRecordingsViewModel(services.live, services.servers, services.auth) }
    val state by model.state.collectAsStateWithLifecycle()
    val now = rememberLiveNow()
    val format = rememberLiveClockFormat()
    val first = remember { FocusRequester() }
    var menu by remember { mutableStateOf<LiveRecording?>(null) }
    var ruleMenu by remember { mutableStateOf<LiveRecordingRule?>(null) }
    var editing by remember { mutableStateOf<LiveRecordingRule?>(null) }
    var deleting by remember { mutableStateOf<LiveRecording?>(null) }
    var recordAgain by remember { mutableStateOf(false) }
    TvScaffold(RailSection.LiveTV, onNavigate, modifier) {
        Column(Modifier.fillMaxSize().padding(start = 150.dp, end = 58.dp, top = 40.dp)) {
            Text("Live TV › Recordings", style = inter(19.sp), color = WmmColors.TextMuted)
            Text("Recordings", style = inter(56.sp, FontWeight.ExtraBold), color = WmmColors.Text)
            Text("Record a programme in the guide. Set a channel and time on your phone or in the web app.", style = inter(20.sp), color = WmmColors.TextMuted, modifier = Modifier.padding(top = 8.dp, bottom = 18.dp))
            state.error?.let { Text(it, style = inter(20.sp), color = WmmColors.Error) }
            if (state.empty && !state.loading) Text(if (state.canRecord) "No recordings yet." else "An admin can turn recording on in the web app’s Live TV settings.", style = inter(24.sp), color = WmmColors.TextSoft)
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 36.dp)) {
                RecordingSection.entries.forEach { section ->
                    val page = state.page(section)
                    if (page.items.isNotEmpty() || page.rules.isNotEmpty()) item("heading-${section.id}") { Text(section.title, style = inter(28.sp, FontWeight.Bold), color = WmmColors.Text, modifier = Modifier.padding(top = 16.dp)) }
                    items(page.items, key = { "${section.id}-${it.id}" }) { recording ->
                        val isFirst = state.pages.values.firstOrNull { it.items.isNotEmpty() }?.items?.firstOrNull()?.id == recording.id
                        FocusableBox(onClick = { menu = recording }, onLongClick = { menu = recording }, modifier = Modifier.fillMaxWidth().then(if (isFirst) Modifier.focusRequester(first) else Modifier).semantics(mergeDescendants = true) { contentDescription = "${recording.title}, ${recording.label}, ${format.dayTime(recording.startMs)}, channel ${recording.number}" }) { active ->
                            Row(Modifier.fillMaxWidth().background(if (active) WmmColors.Surface3 else WmmColors.Surface, RoundedCornerShape(14.dp)).padding(22.dp), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                                Artwork(services.live.artUrl(recording.picture), recording.title, null, Modifier.width(200.dp).height(112.dp), shape = RoundedCornerShape(8.dp))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(recording.title, style = inter(28.sp, FontWeight.Bold), color = WmmColors.Text)
                                    recording.episodeTitle?.let { Text(it, style = inter(20.sp), color = WmmColors.TextSoft) }
                                    Text("${format.dayTime(recording.startMs)} · ${recording.number} ${recording.channelName}", style = inter(19.sp), color = WmmColors.TextMuted)
                                    recording.gaps.take(3).forEach { gap -> Text(gap.label(format), style = inter(18.sp), color = WmmColors.TextMuted) }
                                    if (recording.active) LiveProgress(recording.progressAt(now), Modifier.fillMaxWidth(.7f), height = 5.dp)
                                }
                                Text(recording.label, style = inter(20.sp, FontWeight.SemiBold), color = if (recording.active) WmmColors.Error else if (section == RecordingSection.Conflicts) WmmColors.Warn else WmmColors.TextSoft)
                            }
                        }
                    }
                    items(page.rules, key = { it.id }) { rule ->
                        TvButton("${rule.title} · ${rule.label}", { ruleMenu = rule }, if (state.pages.values.all { it.items.isEmpty() } && state.pages.values.firstOrNull { it.rules.isNotEmpty() }?.rules?.firstOrNull()?.id == rule.id) Modifier.focusRequester(first) else Modifier, size = TvButtonSize.Medium)
                    }
                    page.nextCursor?.let { item("more-${section.id}") { TvButton(if (state.paging == section) "Loading…" else "Show more ${section.title.lowercase()}", { model.next(section) }, enabled = state.paging == null) } }
                }
            }
        }
        if (state.pages.values.any { it.items.isNotEmpty() || it.rules.isNotEmpty() }) InitialFocus(first)
    }
    menu?.let { recording ->
        val actions = listOfNotNull(
            recording.playRequest()?.let { request -> TvMenuItem(if (recording.active) "Watch from the start" else "Watch", WmmIcons.Play) { menu = null; onPlay(request) } },
            if (recording.canDelete && recording.state in listOf("done", "partial")) TvMenuItem("Delete recording…", WmmIcons.Trash) { recordAgain = false; deleting = recording; menu = null } else null,
            if (recording.canCancel && recording.state == "conflict" && recording.reason != "no_tuners") TvMenuItem("Record this instead…", WmmIcons.Record, enabled = !state.busy) { model.chooseClash(recording); menu = null } else null,
            if (recording.canCancel && recording.state in listOf("scheduled", "recording", "conflict", "gone")) TvMenuItem(if (recording.active) "Stop recording · keep what’s recorded" else "Don’t record", WmmIcons.Close, enabled = !state.busy) { model.cancel(recording); menu = null } else null,
        )
        TvLiveMenu(recording.title, recording.label, null, actions.ifEmpty { listOf(TvMenuItem("Close", WmmIcons.Close) { menu = null }) }, onDismiss = { menu = null })
    }
    ruleMenu?.let { rule -> TvLiveMenu(rule.title, rule.label, null, if (rule.canCancel) listOf(TvMenuItem("Options", WmmIcons.Settings) { editing = rule; ruleMenu = null }, TvMenuItem("Stop this series · keep recordings", WmmIcons.Close) { model.delete(rule); ruleMenu = null }) else listOf(TvMenuItem("Close", WmmIcons.Close) { ruleMenu = null }), onDismiss = { ruleMenu = null }) }
    deleting?.let { recording -> TvLiveMenu(recording.title, "Delete the recorded file? This can’t be undone.", null, listOf(
        TvMenuItem(if (recordAgain) "☐ Don’t record it again" else "☑ Don’t record it again", WmmIcons.Record) { recordAgain = !recordAgain },
        TvMenuItem("Cancel", WmmIcons.Close) { deleting = null },
        TvMenuItem("Delete recording", WmmIcons.Trash) { model.deleteFile(recording, recordAgain); deleting = null },
    ), onDismiss = { deleting = null }) }
    editing?.let { rule -> TvRecordingOptions(rule.title, rule.options, rule.kind == "series", onSave = { model.update(rule, it); editing = null }, onDismiss = { editing = null }) }
    state.clash?.let { clash -> TvRecordingClash(clash, model) }
}
