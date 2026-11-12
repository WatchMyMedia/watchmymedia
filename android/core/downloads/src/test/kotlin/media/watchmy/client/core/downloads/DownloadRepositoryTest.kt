package media.watchmy.client.core.downloads

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.ClientInfo
import media.watchmy.client.model.CreatedDownload
import media.watchmy.client.model.DecisionSubtitle
import media.watchmy.client.model.DeviceDownloadState
import media.watchmy.client.model.DownloadChoice
import media.watchmy.client.model.DownloadMark
import media.watchmy.client.model.DownloadPlan
import media.watchmy.client.model.DownloadQuality
import media.watchmy.client.model.DownloadStatus
import media.watchmy.client.model.MediaToken
import media.watchmy.client.model.OfflineGateway
import media.watchmy.client.model.OfflineItem
import media.watchmy.client.model.OfflineOwner
import media.watchmy.client.model.OfflineProgress
import media.watchmy.client.model.OfflineRecord
import media.watchmy.client.model.OfflineRecordState
import media.watchmy.client.model.PlayState
import media.watchmy.client.model.PlaybackDecision
import media.watchmy.client.model.PlaybackMode
import media.watchmy.client.model.PlaybackProfile
import media.watchmy.client.model.ProgressReport
import media.watchmy.client.model.SignedOutException
import media.watchmy.client.model.StreamProtocol
import media.watchmy.client.model.SubtitleOption
import media.watchmy.client.model.SyncOutcome
import media.watchmy.client.model.SyncReply
import media.watchmy.client.model.SyncResult
import media.watchmy.client.model.SyncedDownload
import media.watchmy.client.model.UpNext
import media.watchmy.client.model.WatchProgress
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The repository on a fake engine and gateway: downloading, every sync rule, sign-out, offline progress and plays. */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadRepositoryTest {
    private val dir: File = Files.createTempDirectory("wmm-offline").toFile()
    private val gateway = FakeGateway()
    private val engine = FakeEngine()
    private val notices = mutableListOf<String>()
    private var later = 0
    private var now = 1_800_000_000_000L

    @Test
    fun `a prepared copy larger than the available storage never queues media bytes`() = runTest {
        val repo = repo()
        gateway.createdSize = 200L shl 30
        try {
            repo.download(DownloadChoice(42, DownloadQuality.P720, burnInSubtitleId = "s2"))
            org.junit.Assert.fail("Expected a storage refusal")
        } catch (e: ApiException) {
            assertTrue(e.message.orEmpty().contains("prepared download"))
        }
        assertTrue(engine.downloads().isEmpty())
        assertTrue(gateway.syncs.any { sync -> sync.downloads.any { it.id == RECORD_ID && it.state == DeviceDownloadState.Removed } })
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun TestScope.repo(): RealDownloadRepository {
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        return RealDownloadRepository(
            store = OfflineStore(File(dir, "state.json"), scope),
            gateway = gateway,
            engine = engine,
            client = { CLIENT },
            profile = { PROFILE },
            space = { 100L shl 30 to (200L shl 30) },
            notices = object : DownloadNotices {
                override fun downloaded(title: String) { notices += "downloaded $title" }
                override fun failed(title: String) { notices += "failed $title" }
                override fun removedByAdmin(title: String) { notices += "removed $title" }
            },
            syncLater = { later++ },
            scope = scope,
            now = { now },
        )
    }

    @Test
    fun `downloads are offered when the server allows them, and stay shown while any are held`() = runTest {
        gateway.allowed = false
        val r = repo()
        assertFalse(r.canDownload.value)
        assertFalse(r.enabled.value)
        gateway.allowed = true
        gateway.changeSession()
        assertTrue(r.canDownload.value)
        r.download(DownloadChoice(42, DownloadQuality.Original))
        gateway.allowed = false
        gateway.changeSession()
        assertFalse(r.canDownload.value)
        assertTrue("Held downloads keep Downloads in the navigation", r.enabled.value)
    }

    @Test
    fun `a download is recorded and queued, and an original file's subtitles come beside it`() = runTest {
        val r = repo()
        r.download(DownloadChoice(42, DownloadQuality.Original))
        val request = engine.added.first()
        assertEquals(RECORD_ID, request.id)
        assertFalse(request.hls)
        assertEquals("A file is keyed by its URL without a token", request.url, request.cacheKey)
        assertEquals(partId(RECORD_ID, "x0"), engine.added[1].id)
        val d = r.downloads.value.single()
        assertEquals(DownloadStatus.Waiting(DownloadStatus.Reason.Turn), d.status)
        assertEquals("Dead Reckoning Bay", d.item.title)
        assertEquals(PROFILE, gateway.profiles.single())
    }

    @Test
    fun `progress, completion and the notification`() = runTest {
        val r = repo()
        r.download(DownloadChoice(42, DownloadQuality.Original))
        engine.set(RECORD_ID, EngineDownload.State.Downloading, 42f, 1_800_000_000)
        assertEquals(DownloadStatus.Downloading(42, 1_800_000_000), r.downloads.value.single().status)
        r.preparing(RECORD_ID)
        assertEquals(DownloadStatus.Preparing, r.downloads.value.single().status)
        r.fetching(RECORD_ID)
        r.pause(RECORD_ID)
        assertEquals(listOf(RECORD_ID to true), engine.paused)
        engine.set(RECORD_ID, EngineDownload.State.Completed, 100f, 4_000_000_000)
        val d = r.downloads.value.single()
        assertEquals(DownloadStatus.Downloaded, d.status)
        assertEquals("The real size once downloaded", 4_000_000_000, d.sizeBytes)
        assertEquals(now, d.completedAtMs)
        assertEquals(listOf("downloaded Dead Reckoning Bay"), notices)
        assertEquals("Downloaded is said to the server", DeviceDownloadState.Downloaded, gateway.syncs.last().downloads.single().state)
        assertEquals(4_000_000_000, gateway.syncs.last().downloads.single().sizeBytes)
    }

    @Test
    fun `a failure is said, but a refusal is the sync's to explain`() = runTest {
        val r = repo()
        r.download(DownloadChoice(42, DownloadQuality.Original))
        engine.set(RECORD_ID, EngineDownload.State.Failed, failure = java.io.IOException("reset"))
        assertEquals(DownloadStatus.Failed, r.downloads.value.single().status)
        assertEquals(listOf("failed Dead Reckoning Bay"), notices)
        r.retry(RECORD_ID)
        assertEquals("Try again queues it again", RECORD_ID, engine.added.last { !isPart(it.id) }.id)
        engine.set(RECORD_ID, EngineDownload.State.Failed, failure = OfflineRefusedException(410))
        assertEquals(1, notices.size)
    }

    @Test
    fun `revoked downloads are deleted with a notice, and reported gone at once`() = runTest {
        val r = repo()
        r.download(DownloadChoice(42, DownloadQuality.Original))
        engine.set(RECORD_ID, EngineDownload.State.Completed, 100f, 10)
        gateway.state = OfflineRecordState.Revoked
        r.sync()
        assertTrue(r.downloads.value.isEmpty())
        assertTrue(RECORD_ID in engine.removed)
        assertTrue(partId(RECORD_ID, "x0") in engine.removed)
        assertEquals("removed Dead Reckoning Bay", notices.last())
        assertEquals(SyncedDownload(RECORD_ID, DeviceDownloadState.Removed), gateway.syncs.last().downloads.single())
    }

    @Test
    fun `a deleted or replaced file only marks the copy, which still plays`() = runTest {
        val r = repo()
        r.download(DownloadChoice(42, DownloadQuality.Original))
        engine.set(RECORD_ID, EngineDownload.State.Completed, 100f, 10)
        gateway.state = OfflineRecordState.Gone
        r.sync()
        assertEquals(DownloadMark.RemovedFromServer, r.downloads.value.single().mark)
        assertNotNull(r.offlinePlay(42))
        gateway.state = OfflineRecordState.Replaced
        gateway.replacedBy = 77
        r.sync()
        val d = r.downloads.value.single()
        assertEquals(DownloadMark.NewVersion, d.mark)
        assertEquals(77L, d.replacedBy)
        gateway.state = OfflineRecordState.Ready
        r.sync()
        assertEquals(DownloadMark.None, r.downloads.value.single().mark)
    }

    @Test
    fun `downloading the new version again removes the old copy once it is done`() = runTest {
        val r = repo()
        r.download(DownloadChoice(42, DownloadQuality.Original))
        engine.set(RECORD_ID, EngineDownload.State.Completed, 100f, 10)
        gateway.state = OfflineRecordState.Replaced
        gateway.replacedBy = 77
        r.sync()
        gateway.state = OfflineRecordState.Ready
        r.download(DownloadChoice(77, DownloadQuality.Original))
        assertEquals("The old copy plays until the new one is here", 2, r.downloads.value.size)
        engine.set("bbbbbbbbbbbbbbbb", EngineDownload.State.Completed, 100f, 10)
        assertEquals(listOf(77L), r.downloads.value.map { it.fileId })
        assertTrue(RECORD_ID in engine.removed)
    }

    @Test
    fun `a session remade with another init starts the download over`() = runTest {
        val r = repo()
        r.download(DownloadChoice(42, DownloadQuality.Original))
        engine.set(RECORD_ID, EngineDownload.State.Downloading, 10f, 10)
        gateway.state = OfflineRecordState.Restart
        val before = engine.added.size
        r.sync()
        assertTrue(RECORD_ID in engine.removed)
        assertEquals(RECORD_ID, engine.added.drop(before).first().id)
        // The downloader's 409 does the same.
        r.restart(RECORD_ID)
        assertEquals(2, engine.removed.count { it == RECORD_ID })
    }

    @Test
    fun `a download the server no longer lists is deleted without a notice`() = runTest {
        val r = repo()
        r.download(DownloadChoice(42, DownloadQuality.Original))
        gateway.listed = false
        r.sync()
        assertTrue(r.downloads.value.isEmpty())
        assertTrue(notices.isEmpty())
    }

    @Test
    fun `a 401 signs out, and signing out deletes every download`() = runTest {
        val r = repo()
        r.download(DownloadChoice(42, DownloadQuality.Original))
        gateway.signOutOnSync = true
        r.sync()
        assertTrue(r.downloads.value.isEmpty())
        assertTrue(RECORD_ID in engine.removed)
    }

    @Test
    fun `signing out reports the downloads removed first, with the progress made here`() = runTest {
        val r = repo()
        r.download(DownloadChoice(42, DownloadQuality.Original))
        engine.set(RECORD_ID, EngineDownload.State.Completed, 100f, 10)
        gateway.syncError = ApiException(null, "Couldn't reach the server.")
        r.mark(42, true)
        gateway.syncError = null
        gateway.syncs.clear()
        gateway.beforeSignOutHooks.forEach { it() }
        val sent = gateway.syncs.single()
        assertEquals(DeviceDownloadState.Removed, sent.downloads.single().state)
        assertEquals(true, sent.progress.single().watched)
        gateway.signedIn.clear()
        gateway.current = null
        gateway.changeSession()
        assertTrue(r.downloads.value.isEmpty())
        assertTrue(RECORD_ID in engine.removed)
    }

    @Test
    fun `another account's downloads stay hidden and kept while it is signed in`() = runTest {
        val r = repo()
        r.download(DownloadChoice(42, DownloadQuality.Original))
        val other = OfflineOwner("other", 9, "http://other", 10)
        gateway.current = other
        gateway.signedIn += other.key
        gateway.changeSession()
        assertTrue(r.downloads.value.isEmpty())
        assertFalse(RECORD_ID in engine.removed)
        gateway.current = OWNER
        gateway.changeSession()
        assertEquals(1, r.downloads.value.size)
    }

    @Test
    fun `offline progress is queued, synced by when it happened, and dropped once answered`() = runTest {
        val r = repo()
        r.download(DownloadChoice(42, DownloadQuality.Original))
        engine.set(RECORD_ID, EngineDownload.State.Completed, 100f, 10)
        gateway.syncError = ApiException(null, "Couldn't reach the server.")
        val play = r.offlinePlay(42)!!
        now += 60_000
        r.progress(play, report(600_000, PlayState.Playing))
        now += 60_000
        r.progress(play, report(900_000, PlayState.Stopped))
        assertEquals(WatchProgress(900_000, 3_000_000, false), r.downloads.value.single().watch)
        assertTrue("Offline, the sync waits for a network", later > 0)

        gateway.syncError = null
        gateway.watch = WatchProgress(900_000, 3_000_000, false)
        r.sync()
        val sent = gateway.syncs.last().progress
        assertEquals("Only the newest position", listOf(OfflineProgress(42, RECORD_ID, now, 900_000, 3_000_000)), sent)
        r.sync()
        assertTrue("Answered entries are not sent again", gateway.syncs.last().progress.isEmpty())
        assertEquals("The server's answer is where the copy resumes", WatchProgress(900_000, 3_000_000, false), r.downloads.value.single().watch)
    }

    @Test
    fun `past the credits counts as watched`() = runTest {
        val r = repo()
        r.download(DownloadChoice(42, DownloadQuality.Original))
        engine.set(RECORD_ID, EngineDownload.State.Completed, 100f, 10)
        r.progress(r.offlinePlay(42)!!, report(2_700_000, PlayState.Playing))
        assertTrue(r.downloads.value.single().watch!!.completed)
    }

    @Test
    fun `a gone download's progress is not synced`() = runTest {
        val r = repo()
        r.download(DownloadChoice(42, DownloadQuality.Original))
        engine.set(RECORD_ID, EngineDownload.State.Completed, 100f, 10)
        gateway.state = OfflineRecordState.Gone
        r.sync()
        r.mark(42, true)
        r.sync()
        assertTrue(gateway.syncs.last().progress.isEmpty())
    }

    @Test
    fun `removing a download tells the server, after the network comes back`() = runTest {
        val r = repo()
        r.download(DownloadChoice(42, DownloadQuality.Original))
        gateway.syncError = ApiException(null, "Couldn't reach the server.")
        r.remove(RECORD_ID)
        assertTrue(r.downloads.value.isEmpty())
        assertTrue(RECORD_ID in engine.removed)
        gateway.syncError = null
        r.sync()
        assertEquals(listOf(SyncedDownload(RECORD_ID, DeviceDownloadState.Removed)), gateway.syncs.last().downloads)
        val syncs = gateway.syncs.size
        r.sync()
        assertEquals("Said once: nothing is left to sync", syncs, gateway.syncs.size)
    }

    @Test
    fun `a download that would leave under 1 GB free is refused`() = runTest {
        val r = repo()
        gateway.planSize = (99L shl 30) + (1L shl 29)
        r.plan(42)
        try {
            r.download(DownloadChoice(42, DownloadQuality.Original))
            throw AssertionError("expected a refusal")
        } catch (e: ApiException) {
            assertEquals("Needs 101 GB free. Pick a lower quality or free up space.", e.message)
        }
        assertTrue(engine.added.isEmpty())
    }

    @Test
    fun `an offline play resumes where this device was, with only what was downloaded`() = runTest {
        val r = repo()
        r.download(DownloadChoice(42, DownloadQuality.Original))
        engine.set(RECORD_ID, EngineDownload.State.Completed, 100f, 10)
        assertNull("A subtitle not downloaded is not offered", r.offlinePlay(42)!!.decision.subtitles.firstOrNull())
        engine.set(partId(RECORD_ID, "x0"), EngineDownload.State.Completed, 100f, 10)
        r.mark(42, false)
        r.progress(r.offlinePlay(42)!!, report(600_000, PlayState.Paused))
        val d = r.offlinePlay(42)!!.decision
        assertEquals(600_000L, d.resumeMs)
        assertEquals(OfflinePlaybackGateway.OFFLINE_PLAY, d.playId)
        assertNull(d.sessionId)
        assertEquals(listOf("x0"), d.subtitles.map { it.option.id })
        assertNull("The next episode only when it is downloaded too", d.next)
        assertEquals(0L, d.upNextMs)
        assertNull("Nothing not finished plays offline", r.offlinePlay(43))
    }

    @Test
    fun `settings are kept and Wi-Fi only goes to the engine`() = runTest {
        val r = repo()
        assertTrue(r.settings.value.wifiOnly)
        r.setWifiOnly(false)
        r.setQuality(DownloadQuality.P720)
        assertEquals(false, engine.wifiOnly)
        assertEquals(DownloadQuality.P720, r.settings.value.quality)
        assertEquals(DownloadQuality.P720, repo().settings.value.quality)
    }

    private fun report(pos: Long, state: PlayState) = ProgressReport(42, pos, 3_000_000, OfflinePlaybackGateway.OFFLINE_PLAY, state, 0)

    private class FakeEngine : DownloadEngine {
        val all = LinkedHashMap<String, EngineDownload>()
        val added = mutableListOf<EngineRequest>()
        val removed = mutableListOf<String>()
        val paused = mutableListOf<Pair<String, Boolean>>()
        var wifiOnly: Boolean? = null
        private var listener: ((EngineDownload?, Exception?) -> Unit)? = null

        fun set(id: String, state: EngineDownload.State, percent: Float = 0f, bytes: Long = 0, failure: Exception? = null) {
            val d = EngineDownload(id, state, percent, bytes)
            all[id] = d
            listener?.invoke(d, failure)
        }

        override fun downloads() = all.toMap()
        override fun notMet() = 0
        override fun add(request: EngineRequest) {
            added += request
            all[request.id] = EngineDownload(request.id, EngineDownload.State.Queued, 0f, 0)
            listener?.invoke(all[request.id], null)
        }
        override fun remove(id: String) {
            removed += id
            all.remove(id)
            listener?.invoke(null, null)
        }
        override fun setPaused(id: String, paused: Boolean) {
            this.paused += id to paused
        }
        override fun setWifiOnly(wifiOnly: Boolean) {
            this.wifiOnly = wifiOnly
        }
        override fun usedBytes() = 0L
        override fun listen(onChange: (EngineDownload?, Exception?) -> Unit) {
            listener = onChange
        }
    }

    private class FakeGateway : OfflineGateway {
        var current: OfflineOwner? = OWNER
        val signedIn = mutableSetOf(OWNER.key)
        private val listeners = mutableListOf<() -> Unit>()
        val beforeSignOutHooks = mutableListOf<suspend () -> Unit>()
        var allowed = true
        var state = OfflineRecordState.Ready
        var replacedBy: Long? = null
        var listed = true
        var watch: WatchProgress? = null
        var syncError: Exception? = null
        /** A 401: the sign-in is cleared, as the data layer does, and the call throws. */
        var signOutOnSync = false
        var planSize = 1L shl 30
        var createdSize = 4_200_000_000L
        val profiles = mutableListOf<PlaybackProfile>()
        private val records = HashMap<String, OfflineRecord>()

        data class Sync(val downloads: List<SyncedDownload>, val progress: List<OfflineProgress>)

        val syncs = mutableListOf<Sync>()

        fun changeSession() = listeners.forEach { it() }

        override fun owner() = current
        override fun signedIn(owner: OfflineOwner) = owner.key in signedIn
        override fun onSessionChange(listener: () -> Unit) {
            listeners += listener
        }
        override fun beforeSignOut(hook: suspend () -> Unit) {
            beforeSignOutHooks += hook
        }
        override suspend fun allowed(owner: OfflineOwner) = allowed
        override suspend fun plan(owner: OfflineOwner, fileId: Long, profile: PlaybackProfile) = DownloadPlan(
            fileId, 3_000_000,
            listOf(media.watchmy.client.model.DownloadOption(DownloadQuality.Original, PlaybackMode.Direct, StreamProtocol.File, planSize, false)),
            emptyList(), emptyList(),
        )

        override suspend fun create(owner: OfflineOwner, choice: DownloadChoice, profile: PlaybackProfile, client: ClientInfo): CreatedDownload {
            profiles += profile
            val id = if (choice.fileId == 42L) RECORD_ID else "bbbbbbbbbbbbbbbb"
            val url = "${owner.baseUrl}/api/offline/$id/stream"
            val decision = PlaybackDecision(
                mode = PlaybackMode.Direct, reasons = emptyList(), protocol = StreamProtocol.File, vod = true, url = url, mimeType = null,
                playId = "", sessionId = null, durationMs = 3_000_000, startMs = 0, resumeMs = 120_000, audio = emptyList(), selectedAudio = null,
                subtitles = listOf(DecisionSubtitle(SubtitleOption("x0", "English", "eng", false, false), "webvtt", "${owner.baseUrl}/api/offline/$id/subtitles/x0.vtt")),
                burnInSubtitleId = null, segments = emptyList(), creditsMs = 2_600_000, watchedAtMs = 2_650_000, upNextMs = 2_600_000,
                next = UpNext(43, 7, 1, "Show", 1, 2, "Next", null, null),
            )
            val record = OfflineRecord(
                id, choice.fileId, OfflineRecordState.Ready, null, PlaybackMode.Direct, StreamProtocol.File, url, createdSize, false,
                choice.quality, 0, OfflineItem(OfflineItem.Kind.Movie, "Dead Reckoning Bay", movieId = 500), decision,
            )
            records[id] = record
            return CreatedDownload(record, id)
        }

        override fun parse(owner: OfflineOwner, json: String) = records.getValue(json)

        override suspend fun token(owner: OfflineOwner, downloadId: String) = MediaToken("o1", Long.MAX_VALUE)

        override suspend fun sync(owner: OfflineOwner, client: ClientInfo, sentAtMs: Long, downloads: List<SyncedDownload>, progress: List<OfflineProgress>): SyncReply {
            syncError?.let { throw it }
            if (signOutOnSync) {
                signedIn.clear()
                throw SignedOutException()
            }
            syncs += Sync(downloads, progress)
            val listedRecords = if (!listed) emptyList() else records.values.filter { r -> downloads.any { it.id == r.id && it.state != DeviceDownloadState.Removed } }
                .map { it.copy(state = state, replacedBy = replacedBy.takeIf { state == OfflineRecordState.Replaced }) }
            return SyncReply(allowed, listedRecords, progress.map { SyncResult(SyncOutcome.Applied, watch) })
        }
    }

    private companion object {
        const val RECORD_ID = "abcdefghijklmnop"
        val OWNER = OfflineOwner("srv", 2, "http://server", 10)
        val CLIENT = ClientInfo("dev-1", "Sam's Pixel", "WatchMyMedia for Android", "android", "0.1.0")
        val PROFILE = PlaybackProfile("android", listOf("mp4"), listOf("h264"), listOf("aac"))
    }
}
