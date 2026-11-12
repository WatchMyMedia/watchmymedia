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

/// Runs `load` once (quietly when something is already on screen), then
/// again, quietly, each time the library changes (or `also` yields: the
/// person's rows and lists), until the task is cancelled. Subscribes first,
/// so a change during the first load is not missed.
@MainActor
func followChanges(of library: any LibraryRepository, also: AsyncStream<Void>? = nil, firstQuiet: Bool, load: (_ quiet: Bool) async -> Void) async {
    let changes = also.map { merge(library.changes(), $0) } ?? library.changes()
    await load(firstQuiet)
    for await _ in changes {
        await load(true)
    }
}

/// One stream that yields whenever any of `streams` does; ending it ends theirs.
func merge(_ streams: AsyncStream<Void>...) -> AsyncStream<Void> {
    AsyncStream(bufferingPolicy: .bufferingNewest(1)) { continuation in
        let tasks = streams.map { stream in
            Task {
                for await _ in stream { continuation.yield() }
            }
        }
        continuation.onTermination = { _ in tasks.forEach { $0.cancel() } }
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
