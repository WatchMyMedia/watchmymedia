package media.watchmy.client.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import media.watchmy.client.WatchMyMediaApp

/**
 * After a reboot (or an update of the app) the alarms are gone: set them
 * again at once from what LiveReminderAlarms kept, then ask the server for
 * the list once there is a network ([LiveReminderSync]).
 */
class LiveReminderBoot : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        LiveReminderAlarms(context).restore()
        LiveReminderSync.enqueue(context)
    }
}

/** Reads the reminders from the server and sets the alarms (the app's [WatchMyMediaApp.syncReminders]). */
class LiveReminderSync(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? WatchMyMediaApp ?: return Result.success()
        return if (app.syncReminders()) Result.success() else Result.retry()
    }

    companion object {
        private const val NAME = "live-tv-reminders-sync"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<LiveReminderSync>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
