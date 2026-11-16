package media.watchmy.client.mobile.live

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import media.watchmy.client.mobile.components.ListRow
import media.watchmy.client.mobile.components.RowValue
import media.watchmy.client.mobile.settings.FailureLine
import media.watchmy.client.mobile.settings.FootNote
import media.watchmy.client.mobile.settings.NoteTone
import media.watchmy.client.mobile.settings.StatusCard
import media.watchmy.client.mobile.settings.SubHeading
import media.watchmy.client.mobile.settings.cardInset
import media.watchmy.client.mobile.settings.notificationsAllowed
import media.watchmy.client.mobile.settings.openNotifications
import media.watchmy.client.model.LIVE_REMINDER_CHANNEL
import media.watchmy.client.model.LIVE_REMINDER_LEADS
import media.watchmy.client.model.liveLeadShort
import media.watchmy.client.model.liveReminderSettingsNote
import media.watchmy.client.presentation.live.LiveRemindersViewModel
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.WmmIcons

/** Live TV reminders' settings, as Settings shows them: whether there are any here, and whether Android lets them show. */
internal data class ReminderSettings(
    /** API level 15, and Live TV with listings for this person. */
    val shown: Boolean,
    /** Notifications are on for the app and for the reminders channel. */
    val allowed: Boolean,
)

/** [ReminderSettings] now, checked again each time the app comes back (from Android's settings, say). */
@Composable
internal fun rememberReminderSettings(): ReminderSettings {
    val state by reminderModel().state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(notificationsAllowed(context, LIVE_REMINDER_CHANNEL)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { allowed = notificationsAllowed(context, LIVE_REMINDER_CHANNEL) }
    return ReminderSettings(shown = state.available && state.liveTv, allowed = allowed)
}

@Composable
private fun reminderModel(): LiveRemindersViewModel {
    val services = LocalAppServices.current
    return viewModel(key = "live-reminder-settings") { LiveRemindersViewModel(services.live, services.servers) }
}

/**
 * Playback › Live TV reminders (PLAN-ANDROID-SETTINGS.md §5.1, the
 * SettingsPlaybackPage board): Reminder time, the same on all the person's
 * devices, and the reminders' notification channel. With notifications off
 * a card says reminders can't show here, and its button opens Android's
 * screen for them (§4.7). [enabled] is false while the account's settings
 * haven't loaded.
 */
@Composable
internal fun LiveRemindersBlock(enabled: Boolean, tablet: Boolean, modifier: Modifier = Modifier) {
    val model = reminderModel()
    val state by model.state.collectAsStateWithLifecycle()
    val reminders = rememberReminderSettings()
    if (!reminders.shown) return
    val context = LocalContext.current
    val device = if (tablet) "tablet" else "phone"
    // The time last picked, until it is the saved one: a failure says so under the choices.
    var tried by rememberSaveable { mutableStateOf<Int?>(null) }
    val pick = { minutes: Int ->
        tried = minutes
        model.setLead(minutes)
    }
    Column(modifier.fillMaxWidth()) {
        SubHeading("Live TV reminders", Modifier.padding(top = 4.dp))
        if (!reminders.allowed) {
            StatusCard(
                "Reminders can’t show on this $device",
                "Notifications are off for WatchMyMedia in Android’s settings. Your other devices still get them.",
                NoteTone.Warn,
                Modifier.cardInset(tablet = false),
                action = "Turn on notifications",
                onAction = { openNotifications(context, LIVE_REMINDER_CHANNEL) },
            )
        }
        ListRow("Reminder time", subtitle = "How long before a programme starts", minHeight = 44.dp)
        SingleChoiceSegmentedButtonRow(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            LIVE_REMINDER_LEADS.forEachIndexed { index, minutes ->
                SegmentedButton(
                    selected = minutes == state.leadMinutes,
                    onClick = { pick(minutes) },
                    enabled = enabled,
                    shape = SegmentedButtonDefaults.itemShape(index, LIVE_REMINDER_LEADS.size),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = WmmColors.Accent.copy(alpha = .18f), activeContentColor = WmmColors.Accent, activeBorderColor = WmmColors.TextFaint,
                        inactiveContainerColor = Color.Transparent, inactiveContentColor = WmmColors.TextSoft, inactiveBorderColor = WmmColors.TextFaint,
                        disabledActiveContainerColor = WmmColors.Accent.copy(alpha = .18f), disabledActiveContentColor = WmmColors.Accent, disabledActiveBorderColor = WmmColors.TextFaint,
                        disabledInactiveContainerColor = Color.Transparent, disabledInactiveContentColor = WmmColors.TextSoft, disabledInactiveBorderColor = WmmColors.TextFaint,
                    ),
                    icon = { if (minutes == state.leadMinutes) Icon(WmmIcons.Check, contentDescription = null) },
                ) { Text(liveLeadShort(minutes), fontSize = 12.sp, maxLines = 1, softWrap = false) }
            }
        }
        val error = state.error
        val retry = tried?.takeIf { it != state.leadMinutes }
        if (retry != null && error != null) FailureLine("Couldn’t save, so it’s still ${liveLeadShort(state.leadMinutes)}. $error", onRetry = { pick(retry) })
        ListRow(
            "Notifications",
            subtitle = "Opens the Live TV reminders channel in Android’s settings",
            modifier = Modifier.clickable { openNotifications(context, LIVE_REMINDER_CHANNEL) },
            trailing = { RowValue(if (reminders.allowed) "Allowed" else "Off") },
        )
        FootNote(liveReminderSettingsNote(state.leadMinutes))
    }
}
