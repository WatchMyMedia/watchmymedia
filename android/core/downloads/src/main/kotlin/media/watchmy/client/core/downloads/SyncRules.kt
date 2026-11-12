package media.watchmy.client.core.downloads

import media.watchmy.client.model.OfflineRecord
import media.watchmy.client.model.OfflineRecordState
import media.watchmy.client.model.SyncReply

/** What a sync means for one download held here. */
internal sealed interface SyncAction {
    val id: String

    /** Gone from the server's list, or revoked: delete the copy. [notice] says so to the person (revoked). */
    data class Delete(override val id: String, val notice: Boolean) : SyncAction

    /** The file was deleted or replaced on the server: mark the copy, which still plays. */
    data class Mark(override val id: String, val mark: String, val replacedBy: Long? = null) : SyncAction

    /** A remade session's init differs while downloading: delete what came and start over. */
    data class Restart(override val id: String) : SyncAction
}

/**
 * PLAN-DOWNLOADS.md §5's one rule: anything about the person's access
 * removes downloads; anything about the file only marks them. [held] is each
 * download here and whether it has finished.
 */
internal fun reconcile(held: Map<String, Boolean>, reply: SyncReply): List<SyncAction> {
    val records: Map<String, OfflineRecord> = reply.downloads.associateBy { it.id }
    return held.map { (id, done) ->
        val record = records[id]
        when {
            // The server no longer knows it (removed, or replaced by downloading the file again).
            record == null -> SyncAction.Delete(id, notice = false)
            record.state == OfflineRecordState.Revoked || !reply.offline -> SyncAction.Delete(id, notice = true)
            record.state == OfflineRecordState.Gone -> SyncAction.Mark(id, StoredRecord.MARK_GONE)
            record.state == OfflineRecordState.Replaced -> SyncAction.Mark(id, StoredRecord.MARK_REPLACED, record.replacedBy)
            // Only a download still coming starts over; a finished one plays as it is.
            record.state == OfflineRecordState.Restart && !done -> SyncAction.Restart(id)
            else -> SyncAction.Mark(id, StoredRecord.MARK_NONE)
        }
    }
}
