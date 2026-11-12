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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import media.watchmy.client.mobile.components.BackTopBar
import media.watchmy.client.mobile.components.GoldTextButton
import media.watchmy.client.model.LIVE_REMINDERS_EMPTY
import media.watchmy.client.model.LIVE_REMINDERS_EMPTY_TITLE
import media.watchmy.client.model.LiveChannel
import media.watchmy.client.model.LiveClockFormat
import media.watchmy.client.model.LiveDevice
import media.watchmy.client.model.LiveReminder
import media.watchmy.client.model.LiveReminderState
import media.watchmy.client.model.dayLabel
import media.watchmy.client.model.liveRemindersNote
import media.watchmy.client.model.note
import media.watchmy.client.model.removeLabel
import media.watchmy.client.model.spokenName
import media.watchmy.client.model.whenLine
import media.watchmy.client.presentation.live.LiveRemindersViewModel
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.WmmIcons
import media.watchmy.client.ui.components.rememberLiveClockFormat
import media.watchmy.client.ui.components.rememberLiveNow

/**
 * LiveReminders on the phone: the person's reminders by day, soonest first,
 * each with Remove; a moved or gone one says so. Choosing one opens the
 * guide at it ([onOpen]); [onSettings] changes the time.
 */
@Composable
fun LiveRemindersScreen(channels: List<LiveChannel>, onBack: () -> Unit, onOpen: (LiveReminder) -> Unit, onSettings: () -> Unit, modifier: Modifier = Modifier) {
    val services = LocalAppServices.current
    val model = viewModel { LiveRemindersViewModel(services.live, services.servers) }
    val state by model.state.collectAsStateWithLifecycle()
    val format = rememberLiveClockFormat()
    val now = rememberLiveNow()
    Column(modifier.fillMaxSize().background(WmmColors.Ground)) {
        BackTopBar("Reminders", onBack)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
            reminderRows(state, channels, now, format, onOpen, model::remove, onSettings, LiveDevice.Phone, wide = false)
        }
    }
}

/** The tablet's Reminders panel beside the guide (TabLiveExtras). */
@Composable
internal fun LiveRemindersPanel(channels: List<LiveChannel>, onClose: () -> Unit, onOpen: (LiveReminder) -> Unit, onSettings: () -> Unit, modifier: Modifier = Modifier) {
    val services = LocalAppServices.current
    val model = viewModel(key = "live-reminders-panel") { LiveRemindersViewModel(services.live, services.servers) }
    val state by model.state.collectAsStateWithLifecycle()
    val format = rememberLiveClockFormat()
    val now = rememberLiveNow()
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Reminders", color = WmmColors.Text, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, modifier = Modifier.weight(1f).semantics { heading() })
            IconButton(onClick = onClose) { Icon(WmmIcons.Close, contentDescription = "Close reminders", tint = WmmColors.Text) }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
            reminderRows(state, channels, now, format, onOpen, model::remove, onSettings, LiveDevice.Tablet, wide = true)
        }
    }
}

private fun LazyListScope.reminderRows(
    state: LiveRemindersViewModel.State,
    channels: List<LiveChannel>,
    now: Long,
    format: LiveClockFormat,
    onOpen: (LiveReminder) -> Unit,
    onRemove: (LiveReminder) -> Unit,
    onSettings: () -> Unit,
    device: LiveDevice,
    wide: Boolean,
) {
    val items = state.items
    state.error?.let { error -> item("error") { Text(error, color = WmmColors.TextMuted, fontSize = 14.sp, modifier = Modifier.padding(16.dp)) } }
    if (items.isEmpty() && !state.loading) item("empty") {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(WmmIcons.Bell, contentDescription = null, tint = WmmColors.TextMuted, modifier = Modifier.size(36.dp))
            Text(LIVE_REMINDERS_EMPTY_TITLE, color = WmmColors.Text, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            Text(LIVE_REMINDERS_EMPTY, color = WmmColors.TextMuted, fontSize = 14.sp, textAlign = TextAlign.Center)
        }
    }
    items.groupBy { it.dayLabel(now, format) }.forEach { (day, rows) ->
        item("day-$day") {
            Text(day, color = WmmColors.TextMuted, fontWeight = FontWeight.SemiBold, fontSize = if (wide) 13.sp else 14.sp,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp).semantics { heading() })
        }
        items(rows, key = { it.id }) { reminder ->
            ReminderRow(reminder, channels.firstOrNull { it.id == reminder.channelId } ?: reminder.channelStandIn(), now, format, onOpen, onRemove)
        }
    }
    item("note") {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(liveRemindersNote(state.leadMinutes, device), color = WmmColors.TextFaint, fontSize = 12.sp, lineHeight = 18.sp)
            TextButton(onClick = onSettings, contentPadding = PaddingValues(0.dp)) { Text("Change the time in Settings", color = WmmColors.Accent, fontWeight = FontWeight.SemiBold) }
        }
    }
}

/** A channel for a reminder whose channel isn't in the list now: its number and name. */
private fun LiveReminder.channelStandIn() = LiveChannel(channelId.orEmpty(), "", number, channelName.orEmpty())

@Composable
private fun ReminderRow(reminder: LiveReminder, channel: LiveChannel, now: Long, format: LiveClockFormat, onOpen: (LiveReminder) -> Unit, onRemove: (LiveReminder) -> Unit, modifier: Modifier = Modifier) {
    val gone = reminder.state == LiveReminderState.Gone
    Row(modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable(enabled = !gone, role = Role.Button) { onOpen(reminder) }
                .semantics(mergeDescendants = true) { contentDescription = reminder.spokenName(now, format, action = "open in the guide".takeIf { !gone }) }.padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(64.dp), contentAlignment = Alignment.Center) { ChannelCell(channel, Modifier.fillMaxWidth()) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(reminder.title, color = if (gone) WmmColors.TextMuted else WmmColors.Text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                reminder.episodeLine()?.let { Text(it, color = WmmColors.TextSoft, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Text(reminder.whenLine(now, format), color = WmmColors.TextMuted, fontSize = 12.sp)
                reminder.note(format)?.let { note ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (gone) WmmIcons.BellOff else WmmIcons.Moved, contentDescription = null, tint = if (gone) WmmColors.TextMuted else WmmColors.Accent, modifier = Modifier.size(14.dp))
                        Text(note, color = if (gone) WmmColors.TextMuted else WmmColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
        GoldTextButton("Remove", { onRemove(reminder) }, Modifier.semantics { contentDescription = reminder.removeLabel() })
    }
}
