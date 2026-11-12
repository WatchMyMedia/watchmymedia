import Foundation
import OSLog
import WMMModel

let downloadsLog = Logger(subsystem: "media.watchmy.client", category: "downloads")

/// What a sync means for one download held here.
enum SyncAction: Hashable, Sendable {
    /// Gone from the server's list, or revoked: delete the copy. `notice` says so to the person (revoked).
    case delete(String, notice: Bool)
    /// The file was deleted or replaced on the server: mark the copy, which still plays.
    case mark(String, DownloadMark, replacedBy: Int?)
    /// A remade session's init differs while downloading: delete what came and start over.
    case restart(String)

    var id: String {
        switch self {
        case .delete(let id, _), .mark(let id, _, _), .restart(let id): id
        }
    }
}

/// PLAN-DOWNLOADS.md §5's one rule: anything about the person's access
/// removes downloads; anything about the file only marks them. `held` is each
/// download here and whether it has finished.
func reconcile(_ held: [(id: String, done: Bool)], _ reply: SyncReply) -> [SyncAction] {
    let records = Dictionary(reply.downloads.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
    return held.map { id, done in
        guard let record = records[id] else {
            // The server no longer knows it (removed, or replaced by downloading the file again).
            return .delete(id, notice: false)
        }
        if record.state == .revoked || !reply.offline { return .delete(id, notice: true) }
        switch record.state {
        case .gone: return .mark(id, .removedFromServer, replacedBy: nil)
        case .replaced: return .mark(id, .newVersion, replacedBy: record.replacedBy)
        // Only a download still coming starts over; a finished one plays as it is.
        case .restart where !done: return .restart(id)
        default: return .mark(id, .none, replacedBy: nil)
        }
    }
}
