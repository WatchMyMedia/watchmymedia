package media.watchmy.client.mobile.live

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import media.watchmy.client.mobile.components.FilledButton
import media.watchmy.client.mobile.components.GoldTextButton
import media.watchmy.client.mobile.downloads.rememberNotificationAsk
import media.watchmy.client.model.LiveChannel
import media.watchmy.client.model.LiveDevice
import media.watchmy.client.model.LiveProgramme
import media.watchmy.client.model.LiveReminder
import media.watchmy.client.model.LiveReminderStatus
import media.watchmy.client.model.liveFavouriteLabel
import media.watchmy.client.model.liveNotifyAskMore
import media.watchmy.client.model.liveNotifyAskTitle
import media.watchmy.client.model.liveNotifyAskWhat
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.WmmIcons

/*
 * Favourites and reminders on the phone and tablet (PLAN-LIVE-TV-GUIDE.md
 * §6.5, the LiveGuideExtras, LiveReminders and TabLiveExtras boards).
 */

/** What the guide's star column and details can do, from [media.watchmy.client.model.LIVE_PERSONAL_API_LEVEL]. */
@Immutable
internal class GuideExtras(
    val onStar: (LiveChannel) -> Unit,
    val onRemind: (LiveChannel, LiveProgramme) -> Unit,
    val onForget: (LiveReminder) -> Unit,
    val device: LiveDevice,
)

/** Which device the reminders come on, in the boards' words. */
@Composable
@androidx.compose.runtime.ReadOnlyComposable
internal fun rememberLiveDevice(): LiveDevice = if (LocalConfiguration.current.smallestScreenWidthDp >= 600) LiveDevice.Tablet else LiveDevice.Phone

/** The star column left of a channel (44 dp): gold when the channel is a favourite. */
@Composable
internal fun StarCell(channel: LiveChannel, favourite: Boolean, onToggle: (LiveChannel) -> Unit, modifier: Modifier = Modifier, width: Dp = 44.dp) {
    Box(
        modifier.width(width).fillMaxHeight().background(WmmColors.Surface)
            .clickable(role = Role.Button) { onToggle(channel) }
            .semantics { contentDescription = liveFavouriteLabel(channel, favourite); selected = favourite },
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (favourite) WmmIcons.StarFilled else WmmIcons.Star, contentDescription = null, tint = if (favourite) WmmColors.Accent else WmmColors.TextMuted, modifier = Modifier.size(20.dp))
    }
}

/** The empty star column beside a channel that can't be starred (gone from the tuner's list). */
@Composable
internal fun StarCellNone(modifier: Modifier = Modifier, width: Dp = 44.dp) {
    Box(modifier.width(width).fillMaxHeight().background(WmmColors.Ground))
}

/** The star beside the channel in the details. */
@Composable
internal fun StarButton(channel: LiveChannel, favourite: Boolean, onToggle: (LiveChannel) -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).clickable(role = Role.Button) { onToggle(channel) }
            .semantics { contentDescription = liveFavouriteLabel(channel, favourite); selected = favourite },
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (favourite) WmmIcons.StarFilled else WmmIcons.Star, contentDescription = null, tint = if (favourite) WmmColors.Accent else WmmColors.TextMuted, modifier = Modifier.size(20.dp))
    }
}

/** A reminder's status in the details: the bell, what it is, and Remove. */
@Composable
internal fun ReminderStatusRow(status: LiveReminderStatus, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(WmmColors.Surface).border(1.dp, WmmColors.Line, RoundedCornerShape(12.dp)).padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(if (status.gone) WmmIcons.BellOff else WmmIcons.BellFilled, contentDescription = null, tint = if (status.gone) WmmColors.TextMuted else WmmColors.Accent, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(status.title, color = if (status.gone) WmmColors.Text else WmmColors.Accent, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(status.detail, color = WmmColors.TextMuted, fontSize = 12.sp, lineHeight = 17.sp)
        }
        GoldTextButton("Remove", onRemove)
    }
}

/** A small bell after a title: the programme has a reminder. */
@Composable
internal fun ReminderBell(modifier: Modifier = Modifier, size: Dp = 13.dp) {
    Icon(WmmIcons.BellFilled, contentDescription = null, tint = WmmColors.Accent, modifier = modifier.size(size).clearAndSetSemantics {})
}

/** The tonal Reminder set button: gold, and pressing it removes the reminder. */
@Composable
internal fun ReminderSetButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, small: Boolean = false) {
    Row(
        modifier.height(if (small) 34.dp else 40.dp).clip(RoundedCornerShape(20.dp)).background(WmmColors.Accent.copy(alpha = .16f))
            .clickable(role = Role.Button, onClick = onClick).semantics(mergeDescendants = true) { contentDescription = label; selected = true }
            .padding(horizontal = if (small) 12.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(WmmIcons.BellFilled, contentDescription = null, tint = WmmColors.Accent, modifier = Modifier.size(16.dp))
        Text("Reminder set", color = WmmColors.Accent, fontWeight = FontWeight.SemiBold, fontSize = if (small) 13.sp else 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Asking to post reminders, once, at the first Remind me (LiveReminders'
 * permission board): the reminder is already saved, and Continue hands over
 * to Android's own question. Not now keeps the reminder on the server.
 */
@Stable
internal class ReminderAsk {
    var pending by mutableStateOf<Pair<LiveChannel, LiveProgramme>?>(null)

    /** After a Remind me: explains, then asks, when this device may not post reminders yet. */
    fun after(context: Context, channel: LiveChannel, programme: LiveProgramme) {
        if (explained || Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        explained = true
        pending = channel to programme
    }

    private companion object {
        /** Explained once a process at most; Android itself stops asking after two refusals. */
        var explained = false
    }
}

@Composable
internal fun rememberReminderAsk(): ReminderAsk = remember { ReminderAsk() }

@Composable
internal fun ReminderAskDialog(ask: ReminderAsk, leadMinutes: Int, device: LiveDevice) {
    // Registered while the screen is, so Android's answer has somewhere to land after this closes.
    val notify = rememberNotificationAsk()
    val (channel, programme) = ask.pending ?: return
    AlertDialog(
        onDismissRequest = { ask.pending = null },
        icon = { Icon(WmmIcons.Bell, contentDescription = null, tint = WmmColors.Accent) },
        title = { Text(liveNotifyAskTitle(device)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(liveNotifyAskWhat(programme, channel, leadMinutes), color = WmmColors.TextSoft, fontSize = 14.sp, lineHeight = 20.sp)
                Text(liveNotifyAskMore(device), color = WmmColors.TextMuted, fontSize = 13.sp, lineHeight = 19.sp)
            }
        },
        dismissButton = { TextButton(onClick = { ask.pending = null }) { Text("Not now", color = WmmColors.Accent) } },
        confirmButton = { FilledButton("Continue", { ask.pending = null; notify() }) },
        containerColor = WmmColors.Surface2,
    )
}

/** The guide's More menu's rows (LiveGuideExtras' ⋮): an icon, the label and its value. */
@Composable
internal fun MenuRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, contentDescription = null, tint = WmmColors.TextSoft, modifier = Modifier.size(20.dp))
        Text(label, color = WmmColors.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(value, color = WmmColors.TextMuted, fontSize = 13.sp)
    }
}

internal val LiveRedText = Color(0xFFFCA5A5)
