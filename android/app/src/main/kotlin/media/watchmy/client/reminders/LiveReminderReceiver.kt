package media.watchmy.client.reminders

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import media.watchmy.client.MainActivity
import media.watchmy.client.R
import media.watchmy.client.model.DeepLink
import media.watchmy.client.model.LIVE_REMINDER_CHANNEL
import media.watchmy.client.model.liveReminderHeadline

/**
 * A reminder's alarm (LiveReminderAlarms): the notification the LiveReminders
 * board draws, "Starting in 5 min" over "Dollar Dash · 2.1 HRBR-HD", with the
 * channel's logo and a Watch action that opens the channel for the account
 * that set it. Nothing shows without the notification permission.
 */
class LiveReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val key = intent.getStringExtra(EXTRA_KEY) ?: return
        val text = intent.getStringExtra(EXTRA_TEXT) ?: return
        val startMs = intent.getLongExtra(EXTRA_START, 0L)
        // Said as it comes: Android may deliver a windowed alarm after its time.
        val title = if (startMs > 0) liveReminderHeadline(minutesUntil(startMs, System.currentTimeMillis())) else intent.getStringExtra(EXTRA_TITLE) ?: return
        if (!allowed(context)) return
        if (!LiveReminderAlarms(context).firstTime(key)) return
        val channelId = intent.getStringExtra(EXTRA_CHANNEL)
        val account = intent.getStringExtra(EXTRA_ACCOUNT).orEmpty()
        val logo = intent.getStringExtra(EXTRA_LOGO)
        val done = goAsync()
        scope.launch {
            try {
                val icon = logo?.let { withTimeoutOrNull(LOGO_TIMEOUT_MS) { load(context, it) } }
                post(context, key, title, text, channelId, account, icon, startMs)
            } finally {
                done.finish()
            }
        }
    }

    private suspend fun load(context: Context, url: String): Bitmap? = runCatching {
        val request = ImageRequest.Builder(context).data(url).size(LOGO_PX).allowHardware(false).build()
        (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap()
    }.getOrNull()

    private fun post(context: Context, key: String, title: String, text: String, channelId: String?, account: String, icon: Bitmap?, startMs: Long) {
        if (!allowed(context)) return
        val open = activity(context, key.hashCode(), "watchmymedia://live")
        val builder = NotificationCompat.Builder(context, LIVE_REMINDER_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_reminder)
            .setContentTitle(title)
            .setContentText(text)
            .setLargeIcon(icon)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .apply { if (startMs > 0) setWhen(startMs).setShowWhen(false) }
            // Gone once the programme has been on a while: it's no longer a reminder.
            .setTimeoutAfter(TIMEOUT_MS)
        if (channelId != null && account.isNotEmpty()) {
            builder.addAction(0, "Watch", activity(context, key.hashCode() + 1, DeepLink.live(channelId, account)))
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < 33) {
            NotificationManagerCompat.from(context).notify(TAG, key.hashCode(), builder.build())
        }
    }

    private fun activity(context: Context, code: Int, link: String): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, link.toUri(), context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun allowed(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    companion object {
        /** Whole minutes until [startMs], rounded up; 0 once it has started. */
        internal fun minutesUntil(startMs: Long, nowMs: Long): Int = ((startMs - nowMs + 59_999) / 60_000).coerceAtLeast(0).toInt()

        const val ACTION = "media.watchmy.client.LIVE_REMINDER"
        const val EXTRA_KEY = "key"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        const val EXTRA_CHANNEL = "channel"
        const val EXTRA_ACCOUNT = "account"
        const val EXTRA_LOGO = "logo"
        const val EXTRA_START = "start"
        private const val TAG = "live-reminder"
        private const val LOGO_PX = 256
        private const val LOGO_TIMEOUT_MS = 8_000L
        private const val TIMEOUT_MS = 30 * 60_000L
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
