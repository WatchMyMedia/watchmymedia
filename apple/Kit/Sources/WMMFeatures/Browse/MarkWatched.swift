import Foundation
import WMMModel

/// Mark watched or unwatched, the one way every screen does it,
/// and the note to show: "Marked as watched",
/// or why it failed.
///
/// Nothing is loaded again here, and a caller mustn't either: the mark's own
/// notice (`LibraryChange.watched`) reloads every screen showing the title,
/// this one included, once. What a caller may do:
///
/// - `drawn`: draw the mark at once in what the screen holds (a poster's
///   check, a search result), after the server has it.
/// - `offline`: with no server (an ``APIError`` without a status), keep the
///   mark with a download until there is one; true when it did, and the note
///   is then the mark's own.
///
/// A target on another server (``WatchTarget/onServer(_:_:)``) is marked
/// there, through `oneCard`'s services for it.
@MainActor
func markWatched(_ target: WatchTarget, _ watched: Bool, in library: any LibraryRepository, oneCard: OneCardContext? = nil,
                 drawn: () -> Void = {}, offline: () -> Bool = { false }) async -> String {
    do {
        if let server = target.server, server != library.serverScope {
            guard let other = oneCard?.services.on(server).library, other.serverScope == server else {
                return APIError(status: nil, message: "That title is on another server.").displayMessage
            }
            try await other.mark(target, watched: watched)
            drawn()
            return markedMessage(watched: watched)
        }
        try await library.mark(target, watched: watched)
        drawn()
        return markedMessage(watched: watched)
    } catch let error as APIError where error.status == nil && offline() {
        return markedMessage(watched: watched)
    } catch {
        return error.displayMessage
    }
}
