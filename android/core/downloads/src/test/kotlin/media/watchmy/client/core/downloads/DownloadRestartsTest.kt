package media.watchmy.client.core.downloads

import android.os.Looper
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.offline.DownloaderFactory
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.work.NetworkType
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A real Media3 DownloadManager over a real index that already holds a queued
 * download, as after the app's process ended mid-download: it runs at once,
 * the service starts on the first ON_START, and the background job is set
 * while the app is away and cancelled when it is back.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadRestartsTest {
    private val context = RuntimeEnvironment.getApplication()
    private val database = StandaloneDatabaseProvider(context)
    private val release = CountDownLatch(1)
    private val running = CountDownLatch(1)
    private var finishAtOnce = false
    private var services = 0
    private val jobs = object : DownloadJobs {
        val scheduled = mutableListOf<Requirements>()
        var cancelled = 0

        override fun schedule(requirements: Requirements) {
            scheduled += requirements
        }

        override fun cancel() {
            cancelled++
        }
    }

    /** Runs until released (a long download), or returns at once (a short one). */
    private val downloaders = DownloaderFactory {
        object : Downloader {
            override fun download(progressListener: Downloader.ProgressListener?) {
                running.countDown()
                if (!finishAtOnce) release.await(10, TimeUnit.SECONDS)
            }

            override fun cancel() = release.countDown()

            override fun remove() = Unit
        }
    }

    @After
    fun tearDown() {
        release.countDown()
        database.close()
    }

    private fun queuedAtStart(): DownloadManager {
        val index = DefaultDownloadIndex(database)
        val request = DownloadRequest.Builder("abcdefghijklmnop", "https://srv.example/api/offline/abcdefghijklmnop/stream".toUri()).build()
        index.putDownload(Download(request, Download.STATE_QUEUED, 1L, 1L, -1L, Download.STOP_REASON_NONE, Download.FAILURE_REASON_NONE))
        return DownloadManager(context, index, downloaders).apply { requirements = Requirements(0) }
    }

    private fun restarts(manager: DownloadManager) = DownloadRestarts(manager, startService = { services++ }, jobs = jobs, backgroundJobs = true)

    private fun until(what: String, done: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!done()) {
            check(System.nanoTime() < deadline) { "timed out waiting for $what" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test
    fun `a download left queued runs at start, and the first ON_START starts the service once`() {
        val manager = queuedAtStart()
        assertTrue("A new manager starts paused", manager.downloadsPaused)
        val restarts = restarts(manager)
        assertFalse("Resumed at once, with no service", manager.downloadsPaused)
        until("the index to load") { manager.isInitialized }
        assertTrue("It runs in this process", running.await(10, TimeUnit.SECONDS))
        until("the download to run") { manager.currentDownloads.singleOrNull()?.state == Download.STATE_DOWNLOADING }
        assertTrue(restarts.unfinished())
        assertEquals("Not in front yet: the job is set, until ON_START", 1, jobs.scheduled.size)

        val app = Owner()
        app.registry.addObserver(restarts)
        app.registry.currentState = Lifecycle.State.CREATED
        assertEquals("Not before the app is in front", 0, services)
        app.registry.currentState = Lifecycle.State.STARTED
        assertEquals(1, services)
        assertEquals("Back in front: the service takes over from the job", 1, jobs.cancelled)

        app.registry.currentState = Lifecycle.State.CREATED
        assertEquals("Away with a download unfinished: the job runs it", 2, jobs.scheduled.size)
        assertEquals(Requirements(0), jobs.scheduled.last())
        app.registry.currentState = Lifecycle.State.STARTED
        assertEquals("Media3 restarts the service itself after the first time", 1, services)
        assertEquals(2, jobs.cancelled)
    }

    @Test
    fun `a process started in the background hands the download to the job, which runs it to the end`() {
        finishAtOnce = true
        val manager = queuedAtStart()
        val restarts = restarts(manager)
        until("the index to load") { manager.isInitialized }
        assertEquals("No ON_START: the job carries on", 1, jobs.scheduled.size)
        assertEquals(0, services)
        // Started here, outside any coroutine, so the manager's callbacks on the main thread resume it in place.
        val work = CoroutineScope(Dispatchers.Unconfined).async(start = CoroutineStart.UNDISPATCHED) { restarts.runUntilIdle() }
        until("the download to finish") { work.isCompleted }
        val done = runBlocking { work.await() }
        assertTrue("Nothing left: the job is done", done)
        assertFalse(restarts.unfinished())
    }

    @Test
    fun `nothing unfinished starts nothing`() {
        val manager = DownloadManager(context, DefaultDownloadIndex(database), downloaders)
        val restarts = restarts(manager)
        until("the index to load") { manager.isInitialized }
        val app = Owner()
        app.registry.addObserver(restarts)
        app.registry.currentState = Lifecycle.State.STARTED
        app.registry.currentState = Lifecycle.State.CREATED
        assertEquals(0, services)
        assertTrue(jobs.scheduled.isEmpty())
    }

    @Test
    fun `the job waits for the downloads' own requirements`() {
        assertEquals(NetworkType.UNMETERED, OfflineDownloads.constraintsFor(Media3Engine.requirements(wifiOnly = true)).requiredNetworkType)
        val any = OfflineDownloads.constraintsFor(Media3Engine.requirements(wifiOnly = false))
        assertEquals(NetworkType.CONNECTED, any.requiredNetworkType)
        assertTrue(any.requiresStorageNotLow())
    }
}
