package media.watchmy.client.core.downloads

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.StatFs
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.media3.common.util.NotificationUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import media.watchmy.client.model.ClientInfo
import media.watchmy.client.model.DownloadRepository
import media.watchmy.client.model.OfflineGateway
import media.watchmy.client.model.PlaybackGateway
import media.watchmy.client.model.PlaybackProfile
import media.watchmy.client.model.downloadTitle

/**
 * Offline downloads for the phone app (PLAN-DOWNLOADS.md §10): made once by
 * the app on the main thread, phones and tablets only. It holds Media3's
 * download cache and manager (in `noBackupFilesDir/offline/`, left out of
 * backups), the [repository] the screens use, the player's way to the
 * downloaded bytes ([playerSources]) and calls ([playbackGateway]), and
 * syncs when the app comes to the foreground and when a network is back.
 * The application implements [Host], so the service and the sync job find it.
 */
@OptIn(UnstableApi::class)
class OfflineDownloads(
    context: Context,
    gateway: OfflineGateway,
    client: () -> ClientInfo,
    profile: suspend () -> PlaybackProfile,
    userAgent: String,
) {
    /** Implemented by the Application, so [WmmDownloadService] and [OfflineSyncWorker] find the downloads. */
    interface Host {
        val offlineDownloads: OfflineDownloads?
    }

    private val app = context.applicationContext
    private val dir = File(app.noBackupFilesDir, "offline")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = OfflineStore(File(dir, "state.json"), CoroutineScope(SupervisorJob() + Dispatchers.IO))

    /** Media3's index of downloads and cache, `exoplayer_internal.db`, left out of backups. */
    private val database = StandaloneDatabaseProvider(app)

    /** The downloaded bytes: never evicted. */
    internal val cache = SimpleCache(File(dir, "cache"), NoOpCacheEvictor(), database)

    /** Null only while this is being made, when the manager may already be fetching on its own thread. */
    @Volatile private var ready: RealDownloadRepository? = null
    private val repo: RealDownloadRepository get() = checkNotNull(ready)

    private val tokens = OfflineTokens(gateway::token, owner = { id -> store.record(id)?.owner })

    internal val manager: DownloadManager = run {
        // The player's read timeout: a VOD segment can wait while the server starts ffmpeg.
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setAllowCrossProtocolRedirects(false)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(45_000)
        val events = object : FetchEvents {
            override fun preparing(id: String) { ready?.preparing(id) }
            override fun fetching(id: String) { ready?.fetching(id) }
            override fun refused(id: String, status: Int) { ready?.refused(id, status) }
            override fun restart(id: String) { ready?.restart(id) }
        }
        val downloader = CacheDataSource.Factory()
            .setCache(cache)
            .setCacheKeyFactory(OfflineDataSource.cacheKeys)
            .setUpstreamDataSourceFactory(OfflineDataSource.Factory(http, tokens, events))
        // Runnable::run: segments one after another, in order, as the server's download session asks.
        DownloadManager(app, DefaultDownloadIndex(database), DefaultDownloaderFactory(downloader, Runnable::run)).apply {
            maxParallelDownloads = 1
            minRetryCount = 10
        }
    }

    private val notifications = DownloadNotificationHelper(app, CHANNEL)

    val repository: DownloadRepository

    init {
        ready = RealDownloadRepository(
            store = store,
            gateway = gateway,
            engine = Media3Engine(app, manager, cache),
            client = client,
            profile = profile,
            space = {
                runCatching { StatFs(dir.apply { mkdirs() }.path).let { it.availableBytes to it.totalBytes } }.getOrDefault(0L to 0L)
            },
            notices = AndroidNotices(app),
            syncLater = { scheduleSync(app) },
            scope = scope,
            artwork = OfflineArtwork(File(dir, "artwork")),
        )
        repository = repo
        // At start and on coming to the foreground (PLAN-DOWNLOADS.md §6).
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                scope.launch { repo.sync() }
            }
        })
    }

    /** The player's calls, with downloaded items played from the device. */
    fun playbackGateway(online: PlaybackGateway): PlaybackGateway = OfflinePlaybackGateway(online, repo)

    /**
     * Wraps the player's HTTP data source: a download's offline route is read
     * from the download cache, never the network, so it plays with no
     * connection; everything else goes to the network as before.
     */
    val playerSources: (DataSource.Factory) -> DataSource.Factory = { network ->
        val offline = CacheDataSource.Factory()
            .setCache(cache)
            .setCacheKeyFactory(OfflineDataSource.cacheKeys)
            .setUpstreamDataSourceFactory(null)
            .setCacheWriteDataSinkFactory(null)
        DataSource.Factory { RoutingDataSource(network.createDataSource(), offline.createDataSource()) }
    }

    /** The foreground notification while downloads run: the first one's title and the progress. */
    internal fun foregroundNotification(downloads: List<Download>, notMetRequirements: Int): Notification {
        val running = downloads.firstOrNull { it.state == Download.STATE_DOWNLOADING }
        val title = running?.let { repo.downloads.value.firstOrNull { d -> d.id == it.request.id } }?.item?.let { downloadTitle(it) }
        return notifications.buildProgressNotification(app, R.drawable.ic_stat_download, launchIntent(app), title, downloads.filterNot { isPart(it.request.id) }, notMetRequirements)
    }

    /** A job that syncs once a network is back. */
    class OfflineSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val downloads = (applicationContext as? Host)?.offlineDownloads ?: return Result.success()
            kotlinx.coroutines.withContext(Dispatchers.Main.immediate) { downloads.repo.sync() }
            return Result.success()
        }
    }

    companion object {
        internal const val CHANNEL = "downloads"
        private const val SYNC_WORK = "offline-sync"
        private const val TAG = "WmmDownloads"

        /** Syncs when the device next has a network (PLAN-DOWNLOADS.md §6). */
        internal fun scheduleSync(context: Context) {
            try {
                WorkManager.getInstance(context).enqueueUniqueWork(
                    SYNC_WORK,
                    ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<OfflineSyncWorker>()
                        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                        .build(),
                )
            } catch (e: IllegalStateException) {
                Log.w(TAG, "could not schedule a sync", e)
            }
        }

        internal fun launchIntent(context: Context): PendingIntent? {
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
            return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }
}

/**
 * Picks the download cache for an offline route ([OfflineDataSource.downloadIdOf])
 * and the network for everything else, request by request.
 */
@OptIn(UnstableApi::class)
private class RoutingDataSource(private val network: DataSource, private val offline: DataSource) : DataSource {
    private var open: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        network.addTransferListener(transferListener)
        offline.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val source = if (OfflineDataSource.downloadIdOf(dataSpec.uri) != null) offline else network
        open = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = checkNotNull(open).read(buffer, offset, length)

    override fun getUri(): Uri? = open?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = open?.responseHeaders ?: emptyMap()

    override fun close() {
        open?.close()
        open = null
    }
}

/** "Downloaded", "Couldn't download" and the admin's removal, as notifications in the downloads channel, when Android lets the app post them. */
private class AndroidNotices(private val context: Context) : DownloadNotices {
    private var next = 2

    override fun downloaded(title: String) = post(title, "Downloaded")

    override fun failed(title: String) = post(title, "Couldn’t download")

    override fun removedByAdmin(title: String) = post(title, "Your server’s admin removed this download")

    @OptIn(UnstableApi::class)
    private fun post(title: String, text: String) {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= 33
        ) {
            return
        }
        NotificationUtil.createNotificationChannel(context, OfflineDownloads.CHANNEL, R.string.downloads_channel, R.string.downloads_channel_description, NotificationUtil.IMPORTANCE_LOW)
        val n = NotificationCompat.Builder(context, OfflineDownloads.CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(OfflineDownloads.launchIntent(context))
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTICE_BASE + next++, n)
        } catch (_: SecurityException) {
            // Notifications turned off between the check and here.
        }
    }

    private companion object {
        const val NOTICE_BASE = 4_200
    }
}
