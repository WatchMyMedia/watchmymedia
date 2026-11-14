import Foundation
import WMMModel

/// Mark watched or unwatched, the one way every screen does it
/// (PLAN-APPLE-REVIEW.md §5.5), and the note to show: "Marked as watched",
/// or why it failed.
///
/// Nothing is loaded again here, and a caller mustn't either: the mark's own
/// notice (`LibraryChange.watched`) reloads every screen showing the title,
/// this one included, once (§4.1.1). What a caller may do:
///
/// - `drawn`: draw the mark at once in what the screen holds (a poster's
///   check, a search result), after the server has it.
/// - `offline`: with no server (an ``APIError`` without a status), keep the
///   mark with a download until there is one; true when it did, and the note
///   is then the mark's own.
@MainActor
func markWatched(_ target: WatchTarget, _ watched: Bool, in library: any LibraryRepository,
                 drawn: () -> Void = {}, offline: () -> Bool = { false }) async -> String {
    do {
        try await library.mark(target, watched: watched)
        drawn()
        return markedMessage(watched: watched)
    } catch let error as APIError where error.status == nil && offline() {
        return markedMessage(watched: watched)
    } catch {
        return error.displayMessage
    }
}
