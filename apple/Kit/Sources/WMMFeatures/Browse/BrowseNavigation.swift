import Foundation
import WMMModel

/// What tapping a show or movie card does.
public enum ItemOpening: Hashable, Sendable {
    /// Its page.
    case page(Route)
    /// An unmatched film: its file plays.
    case play(PlayRequest)
    /// An unmatched folder has no page and nothing single to play.
    case needsMatching
}

extension BrowseItem {
    /// A Recently added card for one episode opens that episode's page
    /// (PLAN-EPISODE-PAGE.md §10); other show cards open the show.
    public var opening: ItemOpening {
        switch kind {
        case .movie: return .page(.movie(id: id))
        case .show:
            if let episodeID { return .page(.episode(showID: id, season: seasonNumber, id: episodeID)) }
            return .page(.show(id: id))
        case .unmatched: return unmatchedPlayRequest.map(ItemOpening.play) ?? .needsMatching
        }
    }
}

extension Navigator {
    /// Opens a card: its page, or plays an unmatched film. Returns the notice
    /// to show instead for an unmatched folder, which has nowhere to go.
    @discardableResult
    public func open(_ item: BrowseItem) -> Notice? {
        switch item.opening {
        case .page(let route): open(route)
        case .play(let request): play(request)
        case .needsMatching: return .notMatched
        }
        return nil
    }

    /// Opens an episode's page, over its show.
    public func open(_ episode: EpisodeCard) {
        open(episode.route)
    }
}

/// A screen model's hold on the library's changes (PLAN-APPLE-REVIEW.md
/// §4.1.1–2). It subscribes the first time the screen shows and keeps
/// listening for as long as the model lives, so what changes while the
/// screen is away (another tab, a page pushed over it, the player) is kept
/// and applied when it shows again. Returning to a screen loads nothing
/// unless a change arrived meanwhile or what it shows is older than
/// ``maxAge``. A burst of changes is applied as one.
@MainActor
final class ScreenChanges {
    /// How long what a screen shows is trusted on returning to it with no
    /// change heard: the event stream says what changes, so this only covers
    /// events it missed without knowing (a server too old to send them).
    static let maxAge: TimeInterval = 5 * 60

    private let now: @MainActor () -> Date
    private let maxAge: TimeInterval
    private let subscription = TaskBox()
    private var pending: [LibraryChange] = []
    private var shownAt: Date?
    private var wake: (id: Int, continuation: AsyncStream<Void>.Continuation)?
    private var runs = 0

    init(maxAge: TimeInterval = ScreenChanges.maxAge, now: @escaping @MainActor () -> Date = { Date() }) {
        self.maxAge = maxAge
        self.now = now
    }

    /// While the screen shows: subscribes (once, before the first load, so
    /// nothing is missed), loads the first time (quietly when something is
    /// already on screen), else applies what changed while it was away or
    /// reloads quietly what is too old, then applies each change as it comes,
    /// until the task is cancelled.
    func run(subscribe: () -> AsyncStream<LibraryChange>, hasContent: @autoclosure () -> Bool,
             load: (_ quiet: Bool) async -> Void, apply: ([LibraryChange]) async -> Void) async {
        if subscription.task == nil {
            let stream = subscribe()
            subscription.task = Task { [weak self] in
                for await change in stream { self?.receive(change) }
            }
        }
        runs += 1
        let run = runs
        let (signals, continuation) = AsyncStream.makeStream(of: Void.self, bufferingPolicy: .bufferingNewest(1))
        wake = (run, continuation)
        defer {
            if wake?.id == run { wake = nil }
            continuation.finish()
        }
        if let shownAt, hasContent() {
            if pending.isEmpty, now().timeIntervalSince(shownAt) >= maxAge { pending = [.unknown] }
        } else {
            // The first time, or the last load failed or was left unfinished.
            pending = []
            await load(hasContent())
            if hasContent() { shownAt = now() }
            guard !Task.isCancelled else { return }
        }
        if !pending.isEmpty { continuation.yield() }
        for await _ in signals {
            let changes = pending.merged
            pending = []
            guard !changes.isEmpty else { continue }
            await apply(changes)
            if Task.isCancelled {
                // Left before it finished: applied again on return.
                pending = changes + pending
                return
            }
            shownAt = now()
        }
    }

    /// The model loaded everything again by itself (pull to refresh, Try again).
    func loaded() {
        if shownAt != nil { shownAt = now() }
    }

    private func receive(_ change: LibraryChange) {
        // A screen away for long hears a great many: past a point, everything.
        if pending.count >= 100 { pending = [.unknown] } else { pending.append(change) }
        wake?.continuation.yield()
    }
}

/// Cancels its task when it goes, with the model that holds it.
final class TaskBox: @unchecked Sendable {
    var task: Task<Void, Never>?
    deinit { task?.cancel() }
}

/// `changes` with `other`'s signals as ``LibraryChange/unknown``: the
/// person's rows and lists, or requests, which a screen reloads for.
@MainActor
func merge(_ changes: AsyncStream<LibraryChange>, unknown other: AsyncStream<Void>?) -> AsyncStream<LibraryChange> {
    guard let other else { return changes }
    return AsyncStream { continuation in
        let tasks = [
            Task { for await change in changes { continuation.yield(change) } },
            Task { for await _ in other { continuation.yield(.unknown) } },
        ]
        continuation.onTermination = { _ in tasks.forEach { $0.cancel() } }
    }
}

/// Runs one load at a time: a load that a newer one replaces is cancelled,
/// so its request stops rather than finishing for nothing (§4.1.1).
@MainActor
final class LatestLoad {
    private var current: Task<Void, Never>?
    private var count = 0

    func run(_ load: @escaping @MainActor () async -> Void) async {
        current?.cancel()
        count += 1
        let mine = count
        let task = Task { await load() }
        current = task
        await withTaskCancellationHandler {
            await task.value
        } onCancel: {
            task.cancel()
        }
        if mine == count { current = nil }
    }
}

extension Navigator {
    /// A row's See all: a list's page, or the library's tab (`home` says which
    /// library is the TV one and which the movies').
    public func open(_ link: RowLink, home: HomeData?) {
        switch link {
        case .list(let id):
            open(.mediaList(id: id))
        case .library(let id, _, _, _, _):
            if id == home?.tvLibraryID {
                select(.tvShows)
            } else if id == home?.movieLibraryID {
                select(.movies)
            }
        }
    }
}
