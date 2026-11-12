package media.watchmy.client.reminders

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import media.watchmy.client.model.LIVE_REMINDER_CHANNEL
import media.watchmy.client.model.LIVE_REMINDER_CHANNEL_NAME
import media.watchmy.client.model.LiveChannel
import media.watchmy.client.model.LiveReminderAlarm
import media.watchmy.client.model.LiveReminders
import media.watchmy.client.model.LiveRepository
import media.watchmy.client.model.alarms
import media.watchmy.client.model.comeKey
import media.watchmy.client.model.liveReminderHeadline
import media.watchmy.client.model.notificationText
import org.json.JSONArray
import org.json.JSONObject

/**
 * Live TV reminders on phones and tablets (PLAN-LIVE-TV-GUIDE.md §6.5): one
 * inexact [AlarmManager.setWindow] alarm a reminder, from its time, in a
 * window of [WINDOW_MS], since Play allows the exact-alarm permissions only
 * to alarm and calendar apps. Android 12 and later stretch a window under
 * 10 minutes to 10 for apps without them, and Doze may hold one back more,
 * so the notification says how long is left as it comes. The alarms follow
 * the server's list ([schedule]), and what each will say is kept here too,
 * so [restore] can set them again after a reboot without the network. TVs
 * don't use this: they show a banner while the app is open.
 */
class LiveReminderAlarms(private val context: Context) {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The Live TV reminders channel, which Settings opens. */
    fun createChannel() {
        val channel = NotificationChannel(LIVE_REMINDER_CHANNEL, LIVE_REMINDER_CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH)
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    /**
     * Sets an alarm for each reminder still to come and cancels the rest.
     * [account] goes into each Watch link, so a reminder opens a channel
     * only for the person it was set by; [logos] are the channels' logo URLs.
     */
    fun schedule(reminders: LiveReminders, account: String, logos: Map<String, String>, nowMs: Long = System.currentTimeMillis()) {
        val planned = reminders.alarms(nowMs).map { Planned.of(it, account, logos[it.reminder.channelId]) }
        replace(planned)
    }

    /** Signed out, or another account: nothing more comes. */
    fun cancelAll() = replace(emptyList())

    /** After a reboot: the alarms as they were, from what is kept here. */
    fun restore(nowMs: Long = System.currentTimeMillis()) {
        val kept = stored().filter { it.atMs + GRACE_MS > nowMs }
        replace(kept)
    }

    /** True once, for the reminder [key]: a reminder comes once, even if its alarm is set again. */
    fun firstTime(key: String): Boolean = synchronized(LOCK) {
        val came = prefs.getStringSet(CAME, emptySet()).orEmpty()
        if (key in came) return false
        prefs.edit { putStringSet(CAME, (came + key).toList().takeLast(MAX_CAME).toSet()) }
        true
    }

    private fun replace(planned: List<Planned>) = synchronized(LOCK) {
        val before = stored()
        val keep = planned.map { it.id }.toSet()
        before.filter { it.id !in keep }.forEach { alarms?.cancel(pending(it)) }
        planned.forEach { alarms?.setWindow(AlarmManager.RTC_WAKEUP, it.atMs, WINDOW_MS, pending(it)) }
        prefs.edit { putString(ALARMS, JSONArray(planned.map(Planned::toJson)).toString()) }
    }

    private fun stored(): List<Planned> = runCatching {
        val array = JSONArray(prefs.getString(ALARMS, "[]"))
        (0 until array.length()).mapNotNull { Planned.from(array.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    /** One reminder's alarm, unique by its id (the data URI), carrying what the notification says. */
    private fun pending(p: Planned): PendingIntent {
        val intent = Intent(context, LiveReminderReceiver::class.java)
            .setAction(LiveReminderReceiver.ACTION)
            .setData("wmm-reminder://${p.id}".toUri())
            .putExtra(LiveReminderReceiver.EXTRA_KEY, p.key)
            .putExtra(LiveReminderReceiver.EXTRA_TITLE, p.title)
            .putExtra(LiveReminderReceiver.EXTRA_TEXT, p.text)
            .putExtra(LiveReminderReceiver.EXTRA_CHANNEL, p.channelId)
            .putExtra(LiveReminderReceiver.EXTRA_ACCOUNT, p.account)
            .putExtra(LiveReminderReceiver.EXTRA_LOGO, p.logo)
            .putExtra(LiveReminderReceiver.EXTRA_START, p.startMs)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private data class Planned(
        val id: String,
        val key: String,
        val atMs: Long,
        val startMs: Long,
        val title: String,
        val text: String,
        val channelId: String?,
        val account: String,
        val logo: String?,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("id", id).put("key", key).put("at", atMs).put("start", startMs).put("title", title).put("text", text)
            .put("channel", channelId ?: "").put("account", account).put("logo", logo ?: "")

        companion object {
            fun of(a: LiveReminderAlarm, account: String, logo: String?) = Planned(
                a.reminder.id, a.reminder.comeKey(), a.atMs, a.reminder.startMs, liveReminderHeadline(a.leadMinutes), a.reminder.notificationText(),
                a.reminder.channelId, account, logo,
            )

            fun from(o: JSONObject): Planned? = runCatching {
                Planned(
                    o.getString("id"), o.getString("key"), o.getLong("at"), o.getLong("start"), o.getString("title"), o.getString("text"),
                    o.optString("channel").ifEmpty { null }, o.getString("account"), o.optString("logo").ifEmpty { null },
                )
            }.getOrNull()
        }
    }

    companion object {
        /** The alarm's window: a reminder may come up to a minute after its time. */
        const val WINDOW_MS = 60_000L
        private const val GRACE_MS = 10 * 60_000L
        private const val PREFS = "live-tv-reminders"
        private const val ALARMS = "alarms"
        private const val CAME = "came"
        private const val MAX_CAME = 200
        private val LOCK = Any()

        /** The channels' logos as URLs the image loader can fetch, by channel id. */
        suspend fun logos(live: LiveRepository): Map<String, String> = try {
            live.channels().mapNotNull { c: LiveChannel -> live.artUrl(c.logo)?.let { c.id to it } }.toMap()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyMap()
        }
    }
}
