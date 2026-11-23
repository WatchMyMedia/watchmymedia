import Foundation
import WMMModel

// Search on Apple TV: movies and shows share one row, then Episodes, On live
// TV and, set apart, Not in your library. The phone and iPad keep their
// groups; these are the TV's.

extension SearchWords {
    /// Over the queries searched lately, before anything is typed.
    public static let recentSearches = "Recent searches"
    /// The last of the recent searches: forgets them all.
    public static let clearRecent = "Clear"
    public static let clearRecentLabel = "Clear recent searches"
    /// The row that holds what the libraries have, movies and shows together.
    public static let moviesAndShows = "Movies and TV shows"
}

extension SearchListing {
    /// The titles found, movies and shows in one row: the server in use's
    /// first (its shows, then its movies, each in the order the server
    /// ranked them), then each other server's the same way.
    public var titles: [FoundTitle] {
        let here = shows.filter { $0.server == nil } + movies.filter { $0.server == nil }
        var away: [FoundTitle] = []
        var servers: [String] = []
        for found in shows + movies {
            guard let server = found.server, !servers.contains(server.id) else { continue }
            servers.append(server.id)
        }
        for id in servers {
            away += shows.filter { $0.server?.id == id } + movies.filter { $0.server?.id == id }
        }
        return here + away
    }
}

extension BrowseItem {
    /// A title's line in Apple TV's search row, which mixes movies and shows:
    /// "TV show · 2022 · 3 seasons", "Movie · 2026 · 1 h 52 min", with
    /// "watched" when it is. No format: that is on the title's page.
    public var tvSearchLine: String {
        let watchedNote = watched ? "watched" : nil
        switch kind {
        case .show: return ["TV show", gridSubtitle, watchedNote].compactMap { $0 }.joined(separator: " · ")
        case .movie: return ["Movie", gridSubtitle, watchedNote].compactMap { $0 }.joined(separator: " · ")
        case .unmatched: return gridSubtitle ?? ""
        }
    }
}

/// What Select does on a programme found in Search.
public enum LiveSearchSelect: Equatable, Sendable {
    /// It is on now: watch its channel.
    case watch
    /// It is later: the guide opens on it, where it can be recorded or set to remind.
    case showInGuide
}

extension LiveSearchHit {
    /// Select on the result in Search: a programme on now on a channel that
    /// plays is watched at once; a later one opens the guide on it.
    public func searchSelect(now: Date) -> LiveSearchSelect {
        programme.isOn(at: now) && channel.available ? .watch : .showInGuide
    }
}
