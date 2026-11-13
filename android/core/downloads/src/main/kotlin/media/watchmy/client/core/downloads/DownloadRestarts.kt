package media.watchmy.client.core.downloads

import android.os.Build
import androidx.annotation.OptIn
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.scheduler.Requirements
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** The background job that runs downloads while the app is away (Android 12 and later). */
internal interface DownloadJobs {
    /** Runs the downloads once [requirements] are met: now, when the network or space returns, and after a reboot. */
    fun schedule(requirements: Requirements)

    fun cancel()
}

/**
 * Keeps unfinished downloads going when the app's process starts again.
 * Media3's DownloadManager starts paused and only its DownloadService resumes
 * it, and the app started the service only when a download was added or
 * resumed, so a download left unfinished when the process ended waited until
 * someone paused and resumed it. Media3's own restart, its WorkManager
 * scheduler, is dropped on Android 12 and later, which refuses a foreground
 * service started from the background. So:
 *
 * - the manager resumes as soon as it is made, so a queued download runs in
 *   any process;
 * - the app's first ON_START starts the service, with its notification,
 *   when a download is unfinished ([startService]); after that Media3 starts
 *   it again itself whenever a download runs;
 * - on Android 12 and later ([backgroundJobs]), while the app is away with a
 *   download unfinished, a WorkManager job ([jobs]) with the downloads'
 *   requirements runs them in this process without the service ([runUntilIdle]):
 *   when the network or space returns, after a reboot, and again after
 *   Android stops a job (about ten minutes each). Coming back to the
 *   foreground cancels it: the service takes over.
 *
 * Main thread only, as the manager is.
 */
@OptIn(UnstableApi::class)
internal class DownloadRestarts(
    private val manager: DownloadManager,
    private val startService: () -> Unit,
    private val jobs: DownloadJobs,
    private val backgroundJobs: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
) : DefaultLifecycleObserver {
    private var foreground = false
    private var started = false
    private var startWhenInitialized = false

    init {
        manager.addListener(object : DownloadManager.Listener {
            override fun onInitialized(downloadManager: DownloadManager) {
                if (startWhenInitialized) startOnce()
                // A process started in the background (by the job itself, a reboot, a sync): the job carries on.
                else if (!foreground) scheduleIfUnfinished()
            }
        })
        manager.resumeDownloads()
    }

    override fun onStart(owner: LifecycleOwner) {
        foreground = true
        if (backgroundJobs) jobs.cancel()
        if (started) return
        if (manager.isInitialized) startOnce() else startWhenInitialized = true
    }

    override fun onStop(owner: LifecycleOwner) {
        foreground = false
        scheduleIfUnfinished()
    }

    /** A download that still has work to do: queued (whatever it waits for), running or starting over. Paused, finished and failed ones don't. */
    fun unfinished(): Boolean = manager.currentDownloads.any { it.state in UNFINISHED }

    /**
     * The job's work: the downloads run here until the manager can do no
     * more (all done, or the requirements not met). True when none is left
     * unfinished; false asks WorkManager to try again later.
     */
    suspend fun runUntilIdle(): Boolean {
        if (!manager.isInitialized) awaitManager { it.isInitialized }
        manager.resumeDownloads()
        if (!unfinished()) return true
        awaitManager { it.isIdle }
        return !unfinished()
    }

    private fun startOnce() {
        startWhenInitialized = false
        started = true
        if (unfinished()) startService()
    }

    private fun scheduleIfUnfinished() {
        if (backgroundJobs && manager.isInitialized && unfinished()) jobs.schedule(manager.requirements)
    }

    /** Suspends until [done] holds, checked now and at each of the manager's callbacks. */
    private suspend fun awaitManager(done: (DownloadManager) -> Boolean) {
        if (done(manager)) return
        suspendCancellableCoroutine { cont ->
            val listener = object : DownloadManager.Listener {
                override fun onInitialized(downloadManager: DownloadManager) = check(downloadManager)

                override fun onIdle(downloadManager: DownloadManager) = check(downloadManager)

                override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) = check(downloadManager)

                private fun check(downloadManager: DownloadManager) {
                    if (cont.isActive && done(downloadManager)) {
                        downloadManager.removeListener(this)
                        cont.resume(Unit)
                    }
                }
            }
            manager.addListener(listener)
            // The listeners are a copy-on-write set, so this is safe from the cancelling thread.
            cont.invokeOnCancellation { manager.removeListener(listener) }
        }
    }

    private companion object {
        val UNFINISHED = setOf(Download.STATE_QUEUED, Download.STATE_DOWNLOADING, Download.STATE_RESTARTING)
    }
}
