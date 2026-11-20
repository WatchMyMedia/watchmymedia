package media.watchmy.client.newepisodes

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import media.watchmy.client.MainActivity
import media.watchmy.client.R
import media.watchmy.client.mobile.settings.notificationsAllowed
import media.watchmy.client.model.DeepLink
import media.watchmy.client.model.NEW_EPISODES_CHANNEL
import media.watchmy.client.model.NEW_EPISODES_CHANNEL_NAME
import media.watchmy.client.model.NEW_EPISODES_CHANNEL_DESCRIPTION
import media.watchmy.client.model.NewEpisodeGroup
import media.watchmy.client.model.link
import media.watchmy.client.model.newEpisodesMoreTitle
import media.watchmy.client.model.noticeText
import media.watchmy.client.model.noticeTitle
import media.watchmy.client.model.notices

/**
 * New-episode notifications on phones and tablets, on the `new-episodes`
 * channel at default importance (a sound, no pop-over). One a show, under the
 * show's own tag, so a later check replaces it; more than five shows at once
 * are those five and one for the rest, which opens Home and sorts last. Two
 * or more share the group `new-episodes` under a silent summary, so Android
 * bundles them apart from reminders. Each opens for the account that follows
 * the show alone. No picture and no buttons. Nothing shows while Android
 * doesn't allow the channel. TVs don't use this.
 */
class NewEpisodeNotifier(private val context: Context) : NewEpisodeNotices {
    /** The New episodes channel, made beside the Live TV reminders' one. */
    fun createChannel() {
        val channel = NotificationChannel(NEW_EPISODES_CHANNEL, NEW_EPISODES_CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = NEW_EPISODES_CHANNEL_DESCRIPTION }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    /** Android lets the channel's notifications show: the app's are on, the permission is given and the channel isn't off. */
    override fun allowed(): Boolean = notificationsAllowed(context, NEW_EPISODES_CHANNEL) &&
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    /** Posts a notification for each of [shows], for [account] ([media.watchmy.client.model.watchNextAccount]). */
    override fun post(shows: List<NewEpisodeGroup>, account: String) {
        if (shows.isEmpty() || !allowed()) return
        val notices = shows.notices()
        val manager = NotificationManagerCompat.from(context)
        notices.shows.forEachIndexed { i, group ->
            val notification = builder()
                .setContentTitle(group.noticeTitle())
                .setContentText(group.noticeText())
                // Every one has a key, in the feed's order (newest arrival first): Android lists one with a key before one without.
                .setSortKey("a$i")
                .setContentIntent(activity(group.tmdbId.toInt(), group.link(account)))
                .build()
            notify(manager, tagFor(group.tmdbId), notification)
        }
        if (notices.more > 0) {
            // It sorts last, after the five.
            val notification = builder()
                .setContentTitle(newEpisodesMoreTitle(notices.more))
                .setSortKey("z")
                .setContentIntent(activity(MORE_ID, DeepLink.home(account)))
                .build()
            notify(manager, MORE_TAG, notification)
        }
        // Two or more share a group, apart from reminders, under a silent summary with no words of its own.
        if (notices.shows.size + (if (notices.more > 0) 1 else 0) > 1 || active() > 1) {
            val summary = builder()
                .setGroupSummary(true)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
                .setSilent(true)
                .build()
            notify(manager, SUMMARY_TAG, summary)
        }
    }

    override fun cancelShown() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.activeNotifications.filter { it.tag?.startsWith("$TAG:") == true }.forEach { manager.cancel(it.tag, it.id) }
    }

    /** This app's new-episode notifications showing now. */
    private fun active(): Int = context.getSystemService(NotificationManager::class.java)?.activeNotifications
        ?.count { it.tag?.startsWith("$TAG:") == true && it.tag != SUMMARY_TAG } ?: 0

    private fun builder() = NotificationCompat.Builder(context, NEW_EPISODES_CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_new_episode)
        .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setAutoCancel(true)
        .setGroup(GROUP)

    private fun notify(manager: NotificationManagerCompat, tag: String, notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        manager.notify(tag, ID, notification)
    }

    private fun activity(code: Int, link: String): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, link.toUri(), context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        private const val TAG = "new-episodes"
        private const val GROUP = "new-episodes"
        private const val MORE_TAG = "$TAG:more"
        private const val SUMMARY_TAG = "$TAG:summary"
        private const val MORE_ID = -1
        private const val ID = 1

        /** A show's notification: one tag a show (the feed groups by TMDB id), so the next check for it replaces it. */
        internal fun tagFor(tmdbId: Long) = "$TAG:$tmdbId"
    }
}
