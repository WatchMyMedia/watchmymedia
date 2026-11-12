import Foundation

/// One of a movie's trailers, as getMovie lists them, best first
/// (PLAN-TRAILERS.md §5.3). Servers list them from API level 20; older ones
/// send none, so the Trailer button doesn't show.
public struct MovieTrailer: Hashable, Sendable {
    public enum Source: Hashable, Sendable {
        /// A trailer file in the library, played in our player like any other file.
        case file(fileID: Int)
        /// A YouTube video, played by YouTube's own player or app.
        case youTube(YouTubeTrailer)
    }

    /// "Official Trailer", or "Trailer" for a file next to the movie.
    public var name: String
    public var source: Source
    /// A trailer file's length; nil for YouTube.
    public var durationMs: Int?

    public init(name: String, source: Source, durationMs: Int? = nil) {
        self.name = name
        self.source = source
        self.durationMs = durationMs
    }

    /// A trailer from the server's fields, or nil for one this app can't play:
    /// a source it doesn't know, a file without an id, or a key that isn't a
    /// YouTube id.
    public init?(source: String, name: String, fileID: Int?, key: String?, durationMs: Int?) {
        let name = name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "Trailer" : name
        switch source {
        case "file":
            guard let fileID, fileID > 0 else { return nil }
            self.init(name: name, source: .file(fileID: fileID), durationMs: durationMs.flatMap { $0 > 0 ? $0 : nil })
        case "youtube":
            guard let key, let trailer = YouTubeTrailer(name: name, key: key) else { return nil }
            self.init(name: name, source: .youTube(trailer))
        default:
            return nil
        }
    }

    public var fileID: Int? {
        if case .file(let id) = source { return id }
        return nil
    }

    public var youTube: YouTubeTrailer? {
        if case .youTube(let trailer) = source { return trailer }
        return nil
    }
}

/// A trailer on YouTube: shown in YouTube's player inside the app on iPhone
/// and iPad, handed to the YouTube app on Apple TV, or a code to scan.
public struct YouTubeTrailer: Hashable, Sendable, Identifiable {
    public var id: String { key }
    public var name: String
    /// The YouTube video id.
    public var key: String

    /// Nil when `key` isn't a YouTube id. They are 11 characters today; this
    /// allows some room, as the web app does, but nothing that could change
    /// the URL or the player's page the key goes in.
    public init?(name: String, key: String) {
        guard (6...32).contains(key.count), key.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-" || $0 == "_") }) else { return nil }
        self.name = name
        self.key = key
    }

    /// The video on YouTube itself: Open in YouTube, and Apple TV's code to scan.
    public var watchURL: URL { URL(string: "https://www.youtube.com/watch?v=\(key)")! }

    /// The YouTube app's link on Apple TV, in the form Swiftfin uses. YouTube
    /// doesn't document it; `youtube` is in LSApplicationQueriesSchemes so the
    /// app can ask whether it opens.
    public var appURL: URL { URL(string: "youtube://www.youtube.com/watch?v=\(key)")! }
}

extension MovieDetail {
    /// The trailer Trailer plays: the first the server lists (PLAN-TRAILERS.md §4.4).
    public var trailer: MovieTrailer? { trailers.first }

    /// The movie's YouTube trailers, best first, as YouTube's player tries them.
    public var youTubeTrailers: [YouTubeTrailer] { trailers.compactMap(\.youTube) }

    /// Plays a trailer file from its start, named "<movie> · Trailer" in the player.
    public func trailerRequest(fileID: Int, name: String) -> PlayRequest {
        PlayRequest(fileID: fileID, startMs: 0, title: title, subtitle: name, backdropPath: backdropPath, posterPath: posterPath)
    }
}
