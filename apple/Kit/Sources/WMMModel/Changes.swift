import Foundation

/// What changed in the library or the person's watch state, as the server's
/// events say it (PLAN-APPLE-REVIEW.md §4.1.1), so a screen patches or
/// ignores what isn't its own instead of reloading everything.
public enum LibraryChange: Hashable, Sendable {
    /// The person's watch state of these movies or episodes changed: the
    /// server's `watch.changed`, or a mark made on this device. `watched` is
    /// the new state when this device made it; nil from the server, which
    /// says only which items changed (a pause changes progress, too).
    case watched(WatchKind, ids: [Int], watched: Bool?)
    /// A season (`season` set) or a whole show was marked on this device;
    /// which of its episodes changed is not known here.
    case markedShow(id: Int, season: Int?, watched: Bool)
    /// A library's titles changed (`library.changed`, after a scan or a
    /// match); `id` is nil when the event didn't say which.
    case library(id: Int?)
    /// A scan started, finished or failed (`scan.*`). `library.changed`
    /// follows whatever a scan changed, so only screens that show the scan
    /// itself (an empty Home) follow these.
    case scan(libraryID: Int?)
    /// Anything may have changed: the event stream came back after missing
    /// some, an event couldn't be read, or a change made here (a deleted
    /// episode) whose reach isn't known.
    case unknown

    /// Whether this change can touch `libraryID`'s titles.
    public func touches(library libraryID: Int?) -> Bool {
        switch self {
        case .library(let id): id == nil || libraryID == nil || id == libraryID
        case .unknown: true
        case .watched, .markedShow, .scan: false
        }
    }
}

extension Array where Element == LibraryChange {
    /// A burst read as one: duplicates go, and an unknown change stands for all.
    public var merged: [LibraryChange] {
        if contains(.unknown) { return [.unknown] }
        var seen: Set<LibraryChange> = []
        return filter { seen.insert($0).inserted }
    }
}

/// What changed in the person's Live TV, by the server's event, so each store
/// asks again only for its own part (PLAN-APPLE-REVIEW.md §4.1.8).
public enum LiveTVChange: Hashable, Sendable {
    /// `live.favourites.changed`: the channels' stars.
    case favourites
    /// `live.reminders.changed`: the reminders and the lead time.
    case reminders
    /// `live.recordings.changed`: the household's recordings.
    case recordings
    /// The event stream came back and some may have been missed.
    case all

    /// Whether this change asks `part` again.
    public func includes(_ part: LiveTVChange) -> Bool { self == .all || self == part }
}

extension LibraryRepository {
    /// Each library change as a ``LibraryChange`` of unknown reach, for a
    /// repository that only says something changed.
    public func libraryChanges() -> AsyncStream<LibraryChange> {
        typedStream(changes(), as: .unknown)
    }
}

extension LiveTVRepository {
    /// Each Live TV change as ``LiveTVChange/all``, for a repository that only says something changed.
    public func liveChanges() -> AsyncStream<LiveTVChange> {
        typedStream(changes(), as: .all)
    }
}

/// `stream`'s signals as `value`s; ending the result ends the listening.
@MainActor
func typedStream<T: Sendable>(_ stream: AsyncStream<Void>, as value: T) -> AsyncStream<T> {
    AsyncStream { continuation in
        let task = Task {
            for await _ in stream { continuation.yield(value) }
            continuation.finish()
        }
        continuation.onTermination = { _ in task.cancel() }
    }
}
