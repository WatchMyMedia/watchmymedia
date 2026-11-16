package media.watchmy.client.mobile.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import media.watchmy.client.model.LIVE_REMINDER_CHANNEL
import media.watchmy.client.model.NEW_EPISODES_CHANNEL
import media.watchmy.client.model.NotificationsBlocked

/**
 * Whether the notifications on [channel] can show (PLAN-NEW-EPISODES.md §7.3,
 * after public #54): notifications are on for the app, and the channel isn't
 * off. The same check for Live TV reminders and new episodes; a channel not
 * made yet counts as on, as Android makes it on.
 */
fun notificationsAllowed(context: Context, channel: String): Boolean {
    val manager = NotificationManagerCompat.from(context)
    if (!manager.areNotificationsEnabled()) return false
    val made = manager.getNotificationChannelCompat(channel) ?: return true
    return made.importance != NotificationManagerCompat.IMPORTANCE_NONE
}

/**
 * Android's screen where [channel]'s notifications are turned on
 * (PLAN-ANDROID-SETTINGS.md §4.7, PLAN-NEW-EPISODES.md §7.3). With the app's
 * notifications off, as they are on Android 13 and later until the
 * permission is given, that is the app's notifications screen: a channel's
 * own screen says the channel is blocked and its switch does nothing until
 * the app's switch is on. With only the channel off, it is the channel's
 * screen. The app's screen also stands in where Android has no channel screen.
 */
internal fun openNotifications(context: Context, channel: String) {
    val app = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val intent = if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
        app
    } else {
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, channel)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
        .recoverCatching { context.startActivity(app) }
}

/** The app's notifications screen, which lists every channel: for a card about more than one. */
internal fun openAppNotifications(context: Context) {
    val app = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(app) }
}

/**
 * The overview card's Turn on notifications (the boards' `off` table): the
 * app's screen while the app's notifications are off or both channels are,
 * else the one channel's screen.
 */
internal fun openNotificationsFor(context: Context, blocked: NotificationsBlocked) = when {
    blocked.app || blocked.reminders && blocked.newEpisodes -> openAppNotifications(context)
    blocked.newEpisodes -> openNotifications(context, NEW_EPISODES_CHANNEL)
    else -> openNotifications(context, LIVE_REMINDER_CHANNEL)
}

/** What Android allows now: the app's notifications and each channel's. */
@Immutable
internal data class ChannelsAllowed(val app: Boolean, val reminders: Boolean, val newEpisodes: Boolean) {
    /**
     * Which of what is on here can't show: reminders where Settings shows
     * them ([remindersShown]), new episodes while the person follows a show
     * and this device's switch is on ([newEpisodesOn]).
     */
    fun blocked(remindersShown: Boolean, newEpisodesOn: Boolean) = NotificationsBlocked(
        app = !app,
        reminders = remindersShown && !reminders,
        newEpisodes = newEpisodesOn && !newEpisodes,
    )
}

/** [ChannelsAllowed] now, asked again each time the app comes back (from Android's settings, say). */
@Composable
internal fun rememberChannelsAllowed(): ChannelsAllowed {
    val context = LocalContext.current
    fun read() = ChannelsAllowed(
        app = NotificationManagerCompat.from(context).areNotificationsEnabled(),
        reminders = notificationsAllowed(context, LIVE_REMINDER_CHANNEL),
        newEpisodes = notificationsAllowed(context, NEW_EPISODES_CHANNEL),
    )
    var allowed by remember { mutableStateOf(read()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { allowed = read() }
    return allowed
}
