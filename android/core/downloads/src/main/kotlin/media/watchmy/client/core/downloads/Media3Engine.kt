package media.watchmy.client.core.downloads

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements

/**
 * [DownloadEngine] on Media3's DownloadManager (PLAN-DOWNLOADS.md §10.2).
 * Changes go to the manager directly, and [WmmDownloadService] is started
 * with them where Android lets the app start it (in the foreground): once it
 * has run in a process, Media3 starts it again in the foreground whenever a
 * download runs, so the download goes on in the background.
 */
@OptIn(UnstableApi::class)
internal class Media3Engine(
    private val context: Context,
    private val manager: DownloadManager,
    private val cache: Cache,
) : DownloadEngine {
    private val all = LinkedHashMap<String, Download>()
    private var onChange: ((EngineDownload?, Exception?) -> Unit)? = null

    init {
        manager.addListener(object : DownloadManager.Listener {
            override fun onInitialized(downloadManager: DownloadManager) {
                load()
                onChange?.invoke(null, null)
            }

            override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) {
                all[download.request.id] = download
                onChange?.invoke(download.toEngine(), finalException)
            }

            override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
                all.remove(download.request.id)
                onChange?.invoke(null, null)
            }

            override fun onRequirementsStateChanged(downloadManager: DownloadManager, requirements: Requirements, notMetRequirements: Int) {
                onChange?.invoke(null, null)
            }

            override fun onWaitingForRequirementsChanged(downloadManager: DownloadManager, waitingForRequirements: Boolean) {
                onChange?.invoke(null, null)
            }
        })
        if (manager.isInitialized) load()
    }

    /** Every download in the index, finished ones too; the running ones carry live progress. */
    private fun load() {
        all.clear()
        try {
            manager.downloadIndex.getDownloads().use { c -> while (c.moveToNext()) all[c.download.request.id] = c.download }
        } catch (e: Exception) {
            Log.w(TAG, "could not read the download index", e)
        }
        manager.currentDownloads.forEach { all[it.request.id] = it }
    }

    override fun downloads(): Map<String, EngineDownload> {
        // The running ones' progress objects are live; the index's copies are not.
        manager.currentDownloads.forEach { all[it.request.id] = it }
        return all.mapValues { it.value.toEngine() }
    }

    override fun notMet(): Int = manager.notMetRequirements

    override fun add(request: EngineRequest) {
        val r = DownloadRequest.Builder(request.id, request.url.toUri())
            .setMimeType(if (request.hls) MimeTypes.APPLICATION_M3U8 else null)
            .setCustomCacheKey(request.cacheKey)
            .setData(request.data)
            .build()
        manager.addDownload(r)
        startService()
    }

    override fun remove(id: String) = manager.removeDownload(id)

    override fun setPaused(id: String, paused: Boolean) {
        manager.setStopReason(id, if (paused) STOP_PAUSED else Download.STOP_REASON_NONE)
        if (!paused) startService()
    }

    override fun setWifiOnly(wifiOnly: Boolean) {
        val requirements = requirements(wifiOnly)
        if (manager.requirements != requirements) manager.requirements = requirements
    }

    override fun usedBytes(): Long = cache.cacheSpace

    override fun listen(onChange: (EngineDownload?, Exception?) -> Unit) {
        this.onChange = onChange
    }

    /** Starts the service, which goes into the foreground while a download runs; in the background Android may refuse. */
    private fun startService() {
        try {
            DownloadService.start(context, WmmDownloadService::class.java)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "could not start the download service now; Media3 starts it when the download runs", e)
        }
    }

    private fun Download.toEngine() = EngineDownload(
        id = request.id,
        state = when (state) {
            Download.STATE_COMPLETED -> EngineDownload.State.Completed
            Download.STATE_FAILED -> EngineDownload.State.Failed
            Download.STATE_DOWNLOADING -> EngineDownload.State.Downloading
            Download.STATE_STOPPED -> EngineDownload.State.Paused
            Download.STATE_REMOVING -> EngineDownload.State.Removing
            else -> EngineDownload.State.Queued
        },
        percent = percentDownloaded.takeIf { it >= 0 } ?: 0f,
        bytes = bytesDownloaded,
    )

    companion object {
        private const val TAG = "WmmDownloads"

        /** The stop reason for Pause download. */
        const val STOP_PAUSED = 1

        /** Wi-Fi only (an unmetered network) or any network, and storage that is not low (PLAN-DOWNLOADS.md §10.2). */
        fun requirements(wifiOnly: Boolean) = Requirements(
            (if (wifiOnly) Requirements.NETWORK_UNMETERED else Requirements.NETWORK) or Requirements.DEVICE_STORAGE_NOT_LOW,
        )
    }
}
