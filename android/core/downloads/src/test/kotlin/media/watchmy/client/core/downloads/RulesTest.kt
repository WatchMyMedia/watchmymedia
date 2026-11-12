package media.watchmy.client.core.downloads

import media.watchmy.client.model.DownloadQuality
import media.watchmy.client.model.DownloadStatus
import media.watchmy.client.model.OfflineItem
import media.watchmy.client.model.OfflineRecord
import media.watchmy.client.model.OfflineRecordState
import media.watchmy.client.model.PlaybackMode
import media.watchmy.client.model.StreamProtocol
import media.watchmy.client.model.SyncReply
import org.junit.Assert.assertEquals
import org.junit.Test

/** The rules that need no Android: what a sync does to each download, the offline queue, and Media3's states as the screens say them. */
class RulesTest {
    private fun record(id: String, state: OfflineRecordState, replacedBy: Long? = null) = OfflineRecord(
        id, 42, state, replacedBy, PlaybackMode.Direct, StreamProtocol.File, "http://s/api/offline/$id/stream", 1, false,
        DownloadQuality.Original, 0, OfflineItem(OfflineItem.Kind.Movie, "Dead Reckoning Bay"), null,
    )

    @Test
    fun `access removes downloads, the file only marks them`() {
        val reply = SyncReply(
            offline = true,
            downloads = listOf(
                record("ready", OfflineRecordState.Ready),
                record("revoked", OfflineRecordState.Revoked),
                record("gone", OfflineRecordState.Gone),
                record("replaced", OfflineRecordState.Replaced, replacedBy = 77),
                record("restart", OfflineRecordState.Restart),
                record("restartDone", OfflineRecordState.Restart),
            ),
            results = emptyList(),
        )
        val held = mapOf("ready" to true, "revoked" to true, "gone" to true, "replaced" to false, "restart" to false, "restartDone" to true, "unknown" to true)
        val actions = reconcile(held, reply).associateBy { it.id }
        assertEquals(SyncAction.Mark("ready", StoredRecord.MARK_NONE), actions["ready"])
        assertEquals(SyncAction.Delete("revoked", notice = true), actions["revoked"])
        assertEquals(SyncAction.Mark("gone", StoredRecord.MARK_GONE), actions["gone"])
        assertEquals(SyncAction.Mark("replaced", StoredRecord.MARK_REPLACED, 77), actions["replaced"])
        assertEquals("A download still coming starts over", SyncAction.Restart("restart"), actions["restart"])
        assertEquals("A finished one plays as it is", SyncAction.Mark("restartDone", StoredRecord.MARK_NONE), actions["restartDone"])
        assertEquals("Not on the server's list: deleted, with nothing to say", SyncAction.Delete("unknown", notice = false), actions["unknown"])
    }

    @Test
    fun `a person who may no longer download loses every download`() {
        val reply = SyncReply(offline = false, downloads = listOf(record("a", OfflineRecordState.Ready), record("b", OfflineRecordState.Gone)), results = emptyList())
        assertEquals(listOf(SyncAction.Delete("a", true), SyncAction.Delete("b", true)), reconcile(mapOf("a" to true, "b" to true), reply))
    }

    private fun position(seq: Long, at: Long, pos: Long, file: Long = 42) = StoredProgress(seq, "s/1", file, "d", at, positionMs = pos, durationMs = 100)

    private fun mark(seq: Long, at: Long, watched: Boolean, file: Long = 42) = StoredProgress(seq, "s/1", file, "d", at, watched = watched)

    @Test
    fun `the queue keeps the newest position and the mark after it, per file`() {
        var q = emptyList<StoredProgress>()
        q = q.adding(position(1, 10, 5))
        q = q.adding(position(2, 20, 9))
        assertEquals("A newer position replaces the older", listOf(position(2, 20, 9)), q)
        q = q.adding(mark(3, 30, true))
        q = q.adding(mark(4, 40, false))
        assertEquals("A mark replaces the mark before it, after the position", listOf(position(2, 20, 9), mark(4, 40, false)), q)
        q = q.adding(position(5, 50, 12))
        assertEquals("A position drops the marks before it", listOf(position(5, 50, 12)), q)
        q = q.adding(position(6, 55, 1, file = 43))
        assertEquals("Another file keeps its own", 2, q.size)
    }

    private fun engine(state: EngineDownload.State, percent: Float = 42.4f) = EngineDownload("a", state, percent, 1_800_000_000)

    @Test
    fun `Media3's states read as the boards' states`() {
        assertEquals(DownloadStatus.Downloaded, statusOf(engine(EngineDownload.State.Completed), 0, true, false))
        assertEquals(DownloadStatus.Failed, statusOf(engine(EngineDownload.State.Failed), 0, true, false))
        assertEquals(DownloadStatus.Downloading(42, 1_800_000_000), statusOf(engine(EngineDownload.State.Downloading), 0, true, false))
        assertEquals("A 503's wait is Preparing", DownloadStatus.Preparing, statusOf(engine(EngineDownload.State.Downloading), 0, true, true))
        assertEquals(DownloadStatus.Paused(42, 1_800_000_000), statusOf(engine(EngineDownload.State.Paused), 0, true, false))
        assertEquals("Another download is first", DownloadStatus.Waiting(DownloadStatus.Reason.Turn), statusOf(engine(EngineDownload.State.Queued), 0, true, false))
        assertEquals(DownloadStatus.Waiting(DownloadStatus.Reason.Wifi), statusOf(engine(EngineDownload.State.Queued), Needs.UNMETERED, true, false))
        assertEquals(DownloadStatus.Waiting(DownloadStatus.Reason.Network), statusOf(engine(EngineDownload.State.Queued), Needs.NETWORK, false, false))
        assertEquals(DownloadStatus.Waiting(DownloadStatus.Reason.Storage), statusOf(engine(EngineDownload.State.Queued), Needs.STORAGE, true, false))
        assertEquals("An unknown percentage is 0", DownloadStatus.Downloading(0, 1_800_000_000), statusOf(engine(EngineDownload.State.Downloading, -1f), 0, true, false))
    }
}
