package media.watchmy.client.mobile.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import media.watchmy.client.mobile.components.BackTopBar
import media.watchmy.client.mobile.components.FilledButton
import media.watchmy.client.mobile.components.GoldTextButton
import media.watchmy.client.mobile.components.TonalButton
import media.watchmy.client.model.*
import media.watchmy.client.presentation.live.LiveRecordingsViewModel
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.Artwork
import media.watchmy.client.ui.components.LiveProgress
import media.watchmy.client.ui.components.WmmIcons
import media.watchmy.client.ui.components.rememberLiveClockFormat
import media.watchmy.client.ui.components.rememberLiveNow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** One scoped model for the screen’s recording actions; it does not fetch list sections. */
@Composable
internal fun rememberRecordingActions(): LiveRecordingsViewModel {
    val services = LocalAppServices.current
    return viewModel(key = "live-recording-actions") { LiveRecordingsViewModel(services.live, services.servers, services.auth, loadSections = false) }
}

/** Record alongside reminders in both the phone sheet and the tablet panel. */
@Composable
internal fun RecordControls(programme: LiveProgramme, modifier: Modifier = Modifier) {
    val model = rememberRecordingActions()
    val state by model.state.collectAsStateWithLifecycle()
    var choosing by rememberSaveable(programme.id) { mutableStateOf(false) }
    if (!state.canRecord || programme.endMs <= System.currentTimeMillis()) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val mark = programme.recording?.takeIf { it.state !in listOf("cancelled", "deleted") }
        // Skipped, gone, done, partial or failed: say why, and offer Record again.
        if (mark != null) Text(mark.label, color = when (mark.state) { "conflict" -> WmmColors.Warn; "scheduled", "recording" -> WmmColors.Error; else -> WmmColors.TextSoft }, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        if (mark?.active == true) {
            mark.cancelAction?.let { action -> GoldTextButton(action, { model.cancel(mark.id) }, enabled = !state.busy) }
        } else TonalButton(if (programme.airingAt(System.currentTimeMillis())) "Record the rest" else "Record", {
            model.clearError()
            if (programme.offersSeries) choosing = true else model.record(programme)
        }, Modifier.fillMaxWidth(), enabled = !state.busy, busy = state.busy, icon = WmmIcons.Record)
        state.notice?.takeIf { state.noticeProgrammeId == programme.id }?.let { Text(it, color = WmmColors.TextSoft, fontSize = 13.sp, modifier = Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }) }
        state.error?.let { Text(it, color = WmmColors.Warn, fontSize = 13.sp) }
    }
    if (choosing) RecordingChoiceSheet(programme, state.busy, onRecord = { options -> model.record(programme, options); choosing = false }, onClose = { choosing = false })
    state.clash?.takeIf { it.programmeId == programme.id }?.let { clash -> RecordingClashSheet(clash, state.busy, onPrefer = { holder -> model.prefer(clash, holder) }, onKeep = model::keepClash) }
}

/**
 * The clash when a recording can't get a tuner, adapted from the web's
 * DvrTakeover clash dialog as a bottom sheet (Android's canvas has no clash
 * board): the recordings holding the tuners, a Record … instead on each one
 * that can still give way, and Keep them, which leaves this one under Won't
 * record.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecordingClashSheet(clash: LiveRecording, busy: Boolean, onPrefer: (RecordingConflict?) -> Unit, onKeep: () -> Unit) {
    val format = rememberLiveClockFormat()
    val choosable = clash.canCancel && clash.reason != "no_tuners"
    ModalBottomSheet(onDismissRequest = onKeep, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = WmmColors.Surface2) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(clash.clashTitle(format), color = WmmColors.Text, fontWeight = FontWeight.Bold, fontSize = 22.sp, modifier = Modifier.semantics { heading() })
            Text(clash.clashIntro(), color = WmmColors.TextSoft)
            if (clash.yielding.isNotEmpty() && choosable) Text("Which one won’t record", color = WmmColors.TextMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            clash.conflictsWith.forEach { holder ->
                Column(Modifier.fillMaxWidth().background(WmmColors.Surface3, RoundedCornerShape(12.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(holder.title, color = WmmColors.Text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text(holder.line(format), color = WmmColors.TextMuted, fontSize = 13.sp)
                    holder.setBy()?.let { Text(it, color = WmmColors.TextFaint, fontSize = 12.sp) }
                    if (holder.running) Text(RECORDING_CLASH_RUNNING, color = WmmColors.Error, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    else if (choosable) TonalButton(clash.clashInstead(), { onPrefer(holder) }, Modifier.fillMaxWidth().padding(top = 6.dp), enabled = !busy, busy = busy, icon = WmmIcons.Record)
                }
            }
            // A server that names no holders: this one can still go ahead of every one not yet recording.
            if (clash.conflictsWith.isEmpty() && choosable) TonalButton(clash.clashInstead(), { onPrefer(null) }, Modifier.fillMaxWidth(), enabled = !busy, busy = busy, icon = WmmIcons.Record)
            GoldTextButton(clash.clashKeep(), onKeep)
            if (clash.reason != "no_tuners") Text(clash.clashKeepNote(), color = WmmColors.TextMuted, fontSize = 12.sp)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordingChoiceSheet(programme: LiveProgramme, busy: Boolean, onRecord: (RecordingOptions) -> Unit, onClose: () -> Unit) {
    var options by remember { mutableStateOf(RecordingOptions(series = true)) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = WmmColors.Surface2) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(programme.title, color = WmmColors.Text, fontWeight = FontWeight.Bold, fontSize = 22.sp)
            Text("What would you like to record?", color = WmmColors.TextSoft)
            TonalButton("Just this episode", { onRecord(options.copy(series = false)) }, Modifier.fillMaxWidth(), large = true, enabled = !busy, icon = WmmIcons.Record)
            FilledButton(if (options.newOnly) "Every new episode" else "Every episode", { onRecord(options.copy(series = true)) }, Modifier.fillMaxWidth(), large = true, enabled = !busy, icon = WmmIcons.Record)
            GoldTextButton(if (expanded) "Hide options" else "Options", { expanded = !expanded })
            if (expanded) RecordingOptionsForm(options, { options = it }, series = true)
            Text("A recording takes priority over someone watching live. If the tuners are taken then, you choose which recording gives way.", color = WmmColors.TextMuted, fontSize = 12.sp)
        }
    }
}

@Composable
internal fun RecordingOptionsForm(options: RecordingOptions, onChange: (RecordingOptions) -> Unit, series: Boolean, modifier: Modifier = Modifier, allowServerDefaults: Boolean = true) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (series) {
            RecordingSwitch("New episodes only", options.newOnly) { onChange(options.copy(newOnly = it)) }
            RecordingSwitch("Any channel", options.anyChannel) { onChange(options.copy(anyChannel = it)) }
            RecordingPicker("Keep", (0..10).toList(), options.keep, { if (it == 0) "All recordings" else "The latest $it" }) { onChange(options.copy(keep = it)) }
        }
        if (allowServerDefaults) RecordingSwitch("Use server padding defaults", !options.customPadding) { onChange(options.copy(customPadding = !it)) }
        if (!allowServerDefaults || options.customPadding) {
            RecordingPicker("Start early", RECORDING_PADDING, options.padBefore, { if (it == 0) "On time" else "$it min" }) { onChange(options.copy(padBefore = it, customPadding = true)) }
            RecordingPicker("End late", RECORDING_PADDING, options.padAfter, { if (it == 0) "On time" else "$it min" }) { onChange(options.copy(padAfter = it, customPadding = true)) }
        }
    }
}

@Composable
private fun RecordingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = WmmColors.Text, modifier = Modifier.weight(1f))
        Switch(checked, onCheckedChange = onChange)
    }
}

@Composable
private fun <T> RecordingPicker(label: String, values: List<T>, selected: T, text: (T) -> String, onChange: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = WmmColors.Text, modifier = Modifier.weight(1f))
        Box {
            TextButton(onClick = { expanded = true }) { Text(text(selected), color = WmmColors.Accent) }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }, containerColor = WmmColors.Surface2) {
                values.forEach { value -> DropdownMenuItem(text = { Text(text(value)) }, onClick = { onChange(value); expanded = false }) }
            }
        }
    }
}

@Composable
fun LiveRecordingsScreen(channels: List<LiveChannel>, onBack: () -> Unit, onPlay: (PlayRequest) -> Unit, modifier: Modifier = Modifier) {
    val services = LocalAppServices.current
    val model = viewModel { LiveRecordingsViewModel(services.live, services.servers, services.auth) }
    val state by model.state.collectAsStateWithLifecycle()
    var manual by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxSize().background(WmmColors.Ground)) {
        BackTopBar("Recordings", onBack)
        if (state.canRecord) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
            FilledButton("New recording", { model.clearSaved(); manual = true }, icon = WmmIcons.Plus, enabled = !state.busy)
        }
        RecordingsList(state, model, onPlay, Modifier.weight(1f))
    }
    if (manual) ManualRecordingDialog(channels, state, onSave = model::manual, onDismiss = { manual = false })
}

@Composable
internal fun LiveRecordingsPanel(channels: List<LiveChannel>, onClose: () -> Unit, onPlay: (PlayRequest) -> Unit, modifier: Modifier = Modifier) {
    val services = LocalAppServices.current
    val model = viewModel(key = "live-recordings-panel") { LiveRecordingsViewModel(services.live, services.servers, services.auth) }
    val state by model.state.collectAsStateWithLifecycle()
    var manual by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Recordings", fontWeight = FontWeight.SemiBold, color = WmmColors.Text, modifier = Modifier.weight(1f).semantics { heading() })
            IconButton(onClick = { model.clearSaved(); manual = true }) { Icon(WmmIcons.Plus, "New recording", tint = WmmColors.Accent) }
            IconButton(onClick = onClose) { Icon(WmmIcons.Close, "Close recordings", tint = WmmColors.Text) }
        }
        RecordingsList(state, model, onPlay, Modifier.weight(1f))
    }
    if (manual) ManualRecordingDialog(channels, state, model::manual, { manual = false })
}

@Composable
private fun RecordingsList(state: LiveRecordingsViewModel.State, model: LiveRecordingsViewModel, onPlay: (PlayRequest) -> Unit, modifier: Modifier = Modifier) {
    val now = rememberLiveNow()
    val format = rememberLiveClockFormat()
    val live = LocalAppServices.current.live
    var editing by remember { mutableStateOf<LiveRecordingRule?>(null) }
    var editRecording by remember { mutableStateOf<LiveRecording?>(null) }
    var confirm by remember { mutableStateOf<LiveRecording?>(null) }
    var deleting by remember { mutableStateOf<LiveRecording?>(null) }
    var recordAgain by rememberSaveable { mutableStateOf(false) }
    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.loading) item("loading") { LinearProgressIndicator(Modifier.fillMaxWidth(), color = WmmColors.Accent) }
        state.error?.let { error -> item("error") { Text(error, color = WmmColors.Warn); GoldTextButton("Try again", model::refresh) } }
        if (!state.loading && state.error == null && state.empty) item("empty") {
            Text(if (state.canRecord) "No recordings yet. Choose Record in the guide, or set a channel and time with New recording." else "An admin can turn recording on in the web app’s Live TV settings.", color = WmmColors.TextMuted, modifier = Modifier.padding(vertical = 24.dp))
        }
        RecordingSection.entries.forEach { section ->
            val page = state.page(section)
            if (page.items.isNotEmpty() || page.rules.isNotEmpty()) {
                item("heading-${section.id}") { Text(section.title, fontWeight = FontWeight.Bold, color = WmmColors.Text, fontSize = 18.sp, modifier = Modifier.padding(top = 8.dp).semantics { heading() }) }
                items(page.items, key = { "${section.id}-${it.id}" }) { recording ->
                    Column(Modifier.fillMaxWidth().background(WmmColors.Surface, RoundedCornerShape(12.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Artwork(live.artUrl(recording.picture), recording.title, null, Modifier.width(88.dp).height(58.dp), shape = RoundedCornerShape(6.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(recording.title, color = WmmColors.Text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                recording.episodeTitle?.let { Text(it, color = WmmColors.TextSoft, fontSize = 13.sp) }
                                Text("${format.dayTime(recording.startMs)} · ${recording.number}", color = WmmColors.TextMuted, fontSize = 12.sp)
                            }
                        }
                        Text(recording.label, color = if (recording.active) WmmColors.Error else if (section == RecordingSection.Conflicts) WmmColors.Warn else WmmColors.TextSoft, fontSize = 13.sp)
                        recording.gaps.take(3).forEach { gap -> Text(gap.label(format), color = WmmColors.TextMuted, fontSize = 12.sp) }
                        if (recording.userName.isNotBlank()) Text("Set by ${recording.userName}", color = WmmColors.TextFaint, fontSize = 12.sp)
                        if (recording.active) LiveProgress(recording.progressAt(now), Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            recording.playRequest()?.let { request -> GoldTextButton(if (recording.active) "Watch from the start" else "Watch", { onPlay(request) }, enabled = !state.busy) }
                            if (recording.canCancel && recording.state == "conflict" && recording.reason != "no_tuners") GoldTextButton("Record this instead", { model.chooseClash(recording) }, enabled = !state.busy)
                            if (recording.canCancel && recording.state in listOf("scheduled", "conflict")) GoldTextButton("Edit", { editRecording = recording }, enabled = !state.busy)
                        }
                        if (recording.canDelete && recording.state in listOf("done", "partial")) GoldTextButton("Delete recording", { recordAgain = false; deleting = recording }, enabled = !state.busy)
                        if (recording.canCancel && recording.state in listOf("recording", "scheduled", "conflict", "gone")) GoldTextButton(if (recording.active) "Stop recording" else "Don’t record", { confirm = recording }, enabled = !state.busy)
                    }
                }
                items(page.rules, key = { it.id }) { rule ->
                    Column(Modifier.fillMaxWidth().background(WmmColors.Surface, RoundedCornerShape(12.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(rule.title, fontWeight = FontWeight.SemiBold, color = WmmColors.Text)
                        Text(rule.label, color = WmmColors.TextMuted, fontSize = 13.sp)
                        if (rule.canCancel) Row { GoldTextButton("Options", { editing = rule }); GoldTextButton("Stop this series", { model.delete(rule) }, enabled = !state.busy) }
                    }
                }
                page.nextCursor?.let { item("more-${section.id}") { GoldTextButton(if (state.paging == section) "Loading…" else "Show more ${section.title.lowercase()}", { model.next(section) }, enabled = state.paging == null) } }
            }
        }
    }
    deleting?.let { recording -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete recording?") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Delete ${recording.title} from the server? This can’t be undone.")
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked = !recordAgain, onCheckedChange = { recordAgain = !it }); Text("Don’t record it again") }
    } }, confirmButton = { TextButton(onClick = { model.deleteFile(recording, recordAgain); deleting = null }) { Text("Delete recording") } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }) }
    state.clash?.let { clash -> RecordingClashSheet(clash, state.busy, onPrefer = { holder -> model.prefer(clash, holder) }, onKeep = model::keepClash) }
    editing?.let { rule -> RecordingOptionsDialog(rule.title, rule.options, series = rule.kind == "series", onSave = { model.update(rule, it); editing = null }, onDismiss = { editing = null }) }
    editRecording?.let { recording -> RecordingOptionsDialog(recording.title, RecordingOptions(padBefore = recording.padBefore, padAfter = recording.padAfter), series = false, onSave = { model.update(recording, it); editRecording = null }, onDismiss = { editRecording = null }) }
    confirm?.let { recording -> AlertDialog(onDismissRequest = { confirm = null }, title = { Text(if (recording.active) "Stop recording?" else "Don’t record?") }, text = { Text(if (recording.active) "Keep what has already been recorded of ${recording.title}." else "Cancel ${recording.title}? Other episodes in the series will still record.") }, confirmButton = { TextButton(onClick = { model.cancel(recording); confirm = null }) { Text(if (recording.active) "Stop recording" else "Don’t record") } }, dismissButton = { TextButton(onClick = { confirm = null }) { Text("Keep recording") } }) }
}

@Composable
private fun RecordingOptionsDialog(title: String, initial: RecordingOptions, series: Boolean, onSave: (RecordingOptions) -> Unit, onDismiss: () -> Unit) {
    var options by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { RecordingOptionsForm(options, { options = it }, series, Modifier.verticalScroll(rememberScrollState()), allowServerDefaults = false) }, confirmButton = { TextButton(onClick = { onSave(options) }) { Text("Save") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun ManualRecordingDialog(channels: List<LiveChannel>, state: LiveRecordingsViewModel.State, onSave: (ManualRecording) -> Unit, onDismiss: () -> Unit) {
    val zone = remember { ZoneId.systemDefault() }
    var channelId by rememberSaveable { mutableStateOf(channels.firstOrNull { it.available }?.id.orEmpty()) }
    var name by rememberSaveable { mutableStateOf("") }
    var start by rememberSaveable { mutableLongStateOf(System.currentTimeMillis() + 300_000) }
    var end by rememberSaveable { mutableLongStateOf(start + 3_600_000) }
    var repeat by rememberSaveable { mutableStateOf("once") }
    var submitted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.saved) { if (submitted && state.saved) onDismiss() }
    Dialog(onDismissRequest = { if (!state.busy) onDismiss() }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = WmmColors.Ground) {
            Column(Modifier.fillMaxSize()) {
                BackTopBar("New recording", { if (!state.busy) onDismiss() })
                Column(Modifier.widthIn(max = 600.dp).fillMaxWidth().align(Alignment.CenterHorizontally).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Record a channel at a time you choose, even without listings.", color = WmmColors.TextMuted)
                    OutlinedTextField(name, onValueChange = { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    RecordingPicker("Channel", channels.filter { it.available }.map { it.id }, channelId, { id -> channels.firstOrNull { it.id == id }?.let { "${it.number} ${it.name}" }.orEmpty() }) { channelId = it }
                    RecordingDateTime("Start", start, zone) { start = it }
                    RecordingDateTime("End", end, zone) { end = it }
                    RecordingPicker("Repeat", listOf("once", "daily", "weekdays", "weekly"), repeat, { manualRepeatLabel(it, start, zone) }) { repeat = it }
                    Text("Time zone: ${zone.id}. The computer running WatchMyMedia must be awake.", color = WmmColors.TextMuted, fontSize = 12.sp)
                    val request = ManualRecording(channelId, name.trim(), start, end, repeat, zone.id)
                    // An end before the start, or more than 12 hours, shows at once and keeps Save off; the rest after a try.
                    val error = request.timingProblem ?: (if (submitted) request.validation(System.currentTimeMillis()) else null) ?: state.manualError
                    error?.let { Text(it, color = WmmColors.Warn, modifier = Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }) }
                    FilledButton("Record", { submitted = true; onSave(request) }, Modifier.fillMaxWidth(), large = true, busy = state.busy, enabled = channels.isNotEmpty() && request.timingProblem == null, icon = WmmIcons.Record)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordingDateTime(label: String, value: Long, zone: ZoneId, onChange: (Long) -> Unit) {
    val time = Instant.ofEpochMilli(value).atZone(zone)
    val format = rememberLiveClockFormat()
    var picker by remember { mutableStateOf<String?>(null) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = WmmColors.Text, modifier = Modifier.weight(1f))
        TextButton(onClick = { picker = "date" }) { Text(time.toLocalDate().toString(), color = WmmColors.Accent) }
        TextButton(onClick = { picker = "time" }) { Text(format.time(value), color = WmmColors.Accent) }
    }
    if (picker == "date") {
        val date = rememberDatePickerState(initialSelectedDateMillis = time.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(onDismissRequest = { picker = null }, confirmButton = { TextButton(onClick = { date.selectedDateMillis?.let { ms -> val local = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate(); onChange(local.atTime(time.toLocalTime()).atZone(zone).toInstant().toEpochMilli()) }; picker = null }) { Text("Set date") } }, dismissButton = { TextButton(onClick = { picker = null }) { Text("Cancel") } }) { DatePicker(date) }
    }
    if (picker == "time") {
        val selected = rememberTimePickerState(time.hour, time.minute)
        AlertDialog(onDismissRequest = { picker = null }, title = { Text(label) }, text = { TimePicker(selected) }, confirmButton = { TextButton(onClick = { onChange(time.toLocalDate().atTime(selected.hour, selected.minute).atZone(zone).toInstant().toEpochMilli()); picker = null }) { Text("Set time") } }, dismissButton = { TextButton(onClick = { picker = null }) { Text("Cancel") } })
    }
}
