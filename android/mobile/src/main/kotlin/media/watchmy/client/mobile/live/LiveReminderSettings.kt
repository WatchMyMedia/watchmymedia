package media.watchmy.client.mobile.live

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import media.watchmy.client.mobile.components.ListRow
import media.watchmy.client.mobile.components.RowValue
import media.watchmy.client.mobile.components.SectionLabel
import media.watchmy.client.model.LIVE_REMINDER_CHANNEL
import media.watchmy.client.model.LIVE_REMINDER_LEADS
import media.watchmy.client.model.liveLeadShort
import media.watchmy.client.model.liveReminderSettingsNote
import media.watchmy.client.presentation.live.LiveRemindersViewModel
import media.watchmy.client.ui.Inter
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.WmmIcons

/**
 * Settings › Live TV (the LiveReminders board's settings view): Reminder
 * time, the same on all the person's devices, and the Live TV reminders
 * notification channel. Shown only where reminders are (API level 15, Live
 * TV with listings).
 */
@Composable
internal fun LiveReminderSettings(modifier: Modifier = Modifier) {
    val services = LocalAppServices.current
    val model = viewModel(key = "live-reminder-settings") { LiveRemindersViewModel(services.live, services.servers) }
    val state by model.state.collectAsStateWithLifecycle()
    if (!state.available || !state.liveTv) return
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(remindersAllowed(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { allowed = remindersAllowed(context) }
    Column(modifier.fillMaxWidth()) {
        SectionLabel("Live TV", Modifier.padding(top = 16.dp, bottom = 4.dp))
        ListRow("Reminder time", subtitle = "How long before a programme starts")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            LIVE_REMINDER_LEADS.forEachIndexed { index, minutes ->
                SegmentedButton(
                    selected = minutes == state.leadMinutes,
                    onClick = { model.setLead(minutes) },
                    shape = SegmentedButtonDefaults.itemShape(index, LIVE_REMINDER_LEADS.size),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = WmmColors.Accent.copy(alpha = .18f), activeContentColor = WmmColors.Accent, activeBorderColor = WmmColors.TextFaint,
                        inactiveContainerColor = Color.Transparent, inactiveContentColor = WmmColors.TextSoft, inactiveBorderColor = WmmColors.TextFaint,
                    ),
                    icon = { if (minutes == state.leadMinutes) Icon(WmmIcons.Check, contentDescription = null) },
                ) { Text(liveLeadShort(minutes), fontSize = 12.sp, maxLines = 1, softWrap = false) }
            }
        }
        ListRow(
            "Notifications",
            subtitle = "The Live TV reminders channel, in Android settings",
            modifier = Modifier.clickable { openReminderChannel(context) },
            trailing = { RowValue(if (allowed) "Allowed" else "Off") },
        )
        Text(
            liveReminderSettingsNote(state.leadMinutes),
            style = TextStyle(fontFamily = Inter, fontSize = 12.sp, lineHeight = 18.sp, color = WmmColors.TextFaint),
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp),
        )
    }
}

/** Whether reminders can show: notifications are on for the app and for the channel. */
private fun remindersAllowed(context: Context): Boolean {
    val manager = NotificationManagerCompat.from(context)
    if (!manager.areNotificationsEnabled()) return false
    val channel = manager.getNotificationChannelCompat(LIVE_REMINDER_CHANNEL) ?: return true
    return channel.importance != NotificationManagerCompat.IMPORTANCE_NONE
}

private fun openReminderChannel(context: Context) {
    val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .putExtra(Settings.EXTRA_CHANNEL_ID, LIVE_REMINDER_CHANNEL)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
        .recoverCatching { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
