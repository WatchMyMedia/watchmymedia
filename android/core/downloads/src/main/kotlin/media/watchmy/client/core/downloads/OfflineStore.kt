package media.watchmy.client.core.downloads

import androidx.core.util.AtomicFile
import android.util.Log
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import media.watchmy.client.model.DownloadMark
import media.watchmy.client.model.OfflineOwner

/**
 * A download as this device keeps it: whose it is, the server's record as it
 * came ([json], with the decision the offline player needs), and what the
 * last sync said of it. Media3's download index keeps the bytes and their
 * progress; this keeps the rest.
 */
@Serializable
internal data class StoredRecord(
    val id: String,
    val serverId: String,
    val userId: Long,
    val baseUrl: String,
    val apiLevel: Int,
    val json: String,
    val createdAtMs: Long,
    val fileId: Long,
    val mark: String = MARK_NONE,
    val replacedBy: Long? = null,
    val completedAtMs: Long? = null,
    /** What the last sync told the server, so a change is sent once. */
    val reported: String? = null,
    /** A new version's download replaces this one once it is done ("Download again"). */
    val replaces: String? = null,
    /** An original file's subtitles, each its own Media3 download ([partId]). */
    val subtitleParts: List<String> = emptyList(),
    /** The item's watch state as the server last said, and when this device heard it. */
    val watchPositionMs: Long? = null,
    val watchDurationMs: Long? = null,
    val watchCompleted: Boolean = false,
) {
    val owner: OfflineOwner get() = OfflineOwner(serverId, userId, baseUrl, apiLevel)
    val ownerKey: String get() = "$serverId/$userId"

    val downloadMark: DownloadMark
        get() = when (mark) {
            MARK_GONE -> DownloadMark.RemovedFromServer
            MARK_REPLACED -> DownloadMark.NewVersion
            else -> DownloadMark.None
        }

    companion object {
        const val MARK_NONE = "none"
        const val MARK_GONE = "gone"
        const val MARK_REPLACED = "replaced"
    }
}

/**
 * Progress made on a downloaded item (PLAN-DOWNLOADS.md §6): a position, or a
 * mark, with when it happened by this device's clock. [seq] tells entries
 * apart, so a sync drops exactly the ones it sent.
 */
@Serializable
internal data class StoredProgress(
    val seq: Long,
    val ownerKey: String,
    val fileId: Long,
    val downloadId: String?,
    val atMs: Long,
    val positionMs: Long? = null,
    val durationMs: Long? = null,
    val watched: Boolean? = null,
) {
    val isMark: Boolean get() = watched != null
}

/** A download removed here, still to be told to the server. */
@Serializable
internal data class StoredRemoval(val id: String, val ownerKey: String)

@Serializable
internal data class StoredState(
    val records: List<StoredRecord> = emptyList(),
    val progress: List<StoredProgress> = emptyList(),
    val removed: List<StoredRemoval> = emptyList(),
    val quality: String = "original",
    val wifiOnly: Boolean = true,
    /** Whether each account may download, as last heard, so the app still offers its downloads offline. */
    val allowed: Map<String, Boolean> = emptyMap(),
    val seq: Long = 0,
)

/**
 * The offline queue's rule (PLAN-DOWNLOADS.md §6): per file, the newest
 * position with its time, and the mark made after it, if any. A position
 * drops the marks before it; a mark replaces the one before it.
 */
internal fun List<StoredProgress>.adding(entry: StoredProgress): List<StoredProgress> {
    val same = { p: StoredProgress -> p.ownerKey == entry.ownerKey && p.fileId == entry.fileId }
    val kept = if (entry.isMark) {
        filterNot { same(it) && it.isMark }
    } else {
        filterNot { same(it) && (!it.isMark || it.atMs <= entry.atMs) }
    }
    return kept + entry
}

/**
 * The offline store: [StoredState] in `noBackupFilesDir/offline/state.json`,
 * read once when made and written behind after each change, in order. Memory
 * is the source of truth while the process runs.
 */
internal class OfflineStore(private val file: File, private val scope: CoroutineScope) {
    private val atomic = AtomicFile(file)
    private val writes = Mutex()
    private val lock = Any()
    private val _state = MutableStateFlow(load())
    val state: StateFlow<StoredState> = _state.asStateFlow()

    val value: StoredState get() = _state.value

    fun update(change: (StoredState) -> StoredState): StoredState {
        val next = synchronized(lock) { change(_state.value).also { _state.value = it } }
        scope.launch { save() }
        return next
    }

    /** Adds a progress entry under the queue's rule, with the next sequence number. */
    fun addProgress(entry: (seq: Long) -> StoredProgress) = update { s ->
        val seq = s.seq + 1
        s.copy(progress = s.progress.adding(entry(seq)), seq = seq)
    }

    fun record(id: String): StoredRecord? = value.records.firstOrNull { it.id == id }

    fun updateRecord(id: String, change: (StoredRecord) -> StoredRecord) = update { s ->
        s.copy(records = s.records.map { if (it.id == id) change(it) else it })
    }

    private fun load(): StoredState = try {
        if (!file.exists()) StoredState() else json.decodeFromString(StoredState.serializer(), atomic.readFully().decodeToString())
    } catch (e: Exception) {
        Log.w(TAG, "could not read the offline store; starting afresh", e)
        StoredState()
    }

    private suspend fun save() = writes.withLock {
        // Always the newest state, so writes that overtake each other still end right.
        val text = json.encodeToString(StoredState.serializer(), _state.value)
        file.parentFile?.mkdirs()
        val out = atomic.startWrite()
        try {
            out.write(text.encodeToByteArray())
            atomic.finishWrite(out)
        } catch (e: Exception) {
            atomic.failWrite(out)
            Log.w(TAG, "could not save the offline store", e)
        }
    }

    /** Waits for the writes started so far (tests, and before the process may go). */
    suspend fun flush() = writes.withLock { }

    private companion object {
        const val TAG = "WmmDownloads"
        val json = Json { ignoreUnknownKeys = true }
    }
}
