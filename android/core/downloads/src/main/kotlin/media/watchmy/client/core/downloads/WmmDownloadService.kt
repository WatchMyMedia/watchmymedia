package media.watchmy.client.core.downloads

import android.app.Notification
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import androidx.media3.exoplayer.workmanager.WorkManagerScheduler

/**
 * The downloads' `dataSync` foreground service (PLAN-DOWNLOADS.md §10.3):
 * Media3's DownloadService with its progress notification, one download at a
 * time. Below Android 12 a [WorkManagerScheduler] starts it again when Wi-Fi
 * or space comes back and after a reboot. Media3 passes no scheduler from
 * Android 12, which refuses a foreground service started from the
 * background: there the app's own job runs the downloads while the app is
 * away, and the app starts this service again on coming to the foreground
 * ([DownloadRestarts]). Media3 1.11 stops it on Android 15's `onTimeout`
 * (6 hours of data sync in 24); the download goes on at the next start or job.
 */
@OptIn(UnstableApi::class)
class WmmDownloadService : DownloadService(
    FOREGROUND_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    OfflineDownloads.CHANNEL,
    R.string.downloads_channel,
    R.string.downloads_channel_description,
) {
    private val downloads: OfflineDownloads
        get() = checkNotNull((application as? OfflineDownloads.Host)?.offlineDownloads) { "no downloads in this app" }

    override fun getDownloadManager(): DownloadManager = downloads.manager

    override fun getScheduler(): Scheduler = WorkManagerScheduler(this, SCHEDULER_WORK)

    override fun getForegroundNotification(downloads: List<Download>, notMetRequirements: Int): Notification =
        this.downloads.foregroundNotification(downloads, notMetRequirements)

    private companion object {
        const val FOREGROUND_ID = 4_100
        const val SCHEDULER_WORK = "offline-downloads"
    }
}
