package media.watchmy.client.mobile.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

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
