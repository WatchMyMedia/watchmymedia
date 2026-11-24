import Foundation

// One card for a title on several servers. A device signed in to more than
// one server shows a movie, show or episode they share once, in search, on
// Home, in the libraries and in Continue watching, and Play offers each
// server's copy. Titles are matched by TMDB id, an episode by its show's
// TMDB id, season and number; a title TMDB didn't match is never merged.

/// What makes two servers' titles the same one.
public enum TitleKey: Hashable, Sendable {
    case movie(tmdbID: Int)
    case show(tmdbID: Int)
    case episode(showTMDBID: Int, season: Int, number: Int)

    /// A movie or show card's key; nil for one TMDB hasn't matched, or an unmatched file.
    public init?(_ item: BrowseItem) {
        guard let tmdb = item.tmdbID, tmdb > 0 else { return nil }
        switch item.kind {
        case .movie: self = .movie(tmdbID: tmdb)
        case .show: self = .show(tmdbID: tmdb)
        case .unmatched: return nil
        }
    }

    /// An episode card's key, from servers that send its show's TMDB id (API level 34).
    public init?(_ episode: EpisodeCard) {
        guard let show = episode.showTMDBID, show > 0 else { return nil }
        self = .episode(showTMDBID: show, season: episode.season, number: episode.episode)
    }

    /// A Continue watching card's key: the movie's, or the episode's by its show.
    public init?(_ item: ContinueItem) {
        guard let tmdb = item.tmdbID, tmdb > 0 else { return nil }
        switch item.kind {
        case .movie: self = .movie(tmdbID: tmdb)
        case .episode:
            guard let season = item.season, let number = item.episode else { return nil }
            self = .episode(showTMDBID: tmdb, season: season, number: number)
        }
    }

    /// The key as it is kept on the device for the copy chosen to play: "movie-603".
    public var storageKey: String {
        switch self {
        case .movie(let id): "movie-\(id)"
        case .show(let id): "show-\(id)"
        case .episode(let show, let season, let number): "episode-\(show)-\(season)-\(number)"
        }
    }
}

/// A server signed in to, as one card names it: in the chooser's
/// headings ("Cabin server"), a card's line ("On Cabin") and the note for
/// one reached away from home.
public struct CardServer: Hashable, Sendable, Identifiable {
    public var id: String
    /// "Cabin server", as the Servers list names it.
    public var name: String
    /// The server in use.
    public var isCurrent: Bool
    /// Reached over the internet rather than on this network, so it may
    /// convert a file to keep up; offered last, with a note.
    public var away: Bool

    public init(id: String, name: String, isCurrent: Bool = false, away: Bool = false) {
        self.id = id
        self.name = name
        self.isCurrent = isCurrent
        self.away = away
    }

    /// The server as a card's line names it, without "server": "Cabin".
    public var shortName: String { cardServerShortName(name) }
}

/// "Cabin" for "Cabin server", the name as the boards draw it on a card;
/// a name that doesn't end in " server" is kept whole.
public func cardServerShortName(_ name: String) -> String {
    for suffix in [" server", " Server"] where name.hasSuffix(suffix) && name.count > suffix.count {
        return String(name.dropLast(suffix.count))
    }
    return name
}

/// Whether `url` reaches its server away from home: anything but an
/// address on this network (a private IPv4 address, a `.local` name, or
/// this machine).
public func isAwayFromHome(_ url: URL) -> Bool {
    guard let host = url.host()?.lowercased() else { return false }
    if host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "[::1]" { return false }
    return !isLANHost(host)
}

extension SavedServer {
    /// The server as one card names it.
    public func cardServer(current: String?) -> CardServer {
        CardServer(id: server.id, name: server.name, isCurrent: server.id == current, away: isAwayFromHome(server.baseURL))
    }
}

/// How long each server has to answer for one card (a title's copies, a
/// row, a page of a library), as search gives it.
public let cardServerTimeout: Duration = .seconds(4)
/// How long a server is out before a row or the chooser says it is still being asked.
public let cardStillAskingAfter: Duration = .seconds(1)
