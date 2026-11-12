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
 * time. A [WorkManagerScheduler] starts it again when Wi-Fi or space comes
 * back and after a reboot. Media3 1.11 stops it on Android 15's `onTimeout`
 * (6 hours of data sync in 24), and the scheduler picks the download up later.
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
