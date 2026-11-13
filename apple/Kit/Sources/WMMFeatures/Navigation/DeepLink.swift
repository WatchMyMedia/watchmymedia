import Foundation

/// A `watchmymedia://` link: `play/{fileId}` (Top Shelf, Continue watching),
/// `shows/{id}` (optionally `?season=` and `&episode=`, which opens that
/// episode's page), `movies/{id}` (with `?play=1`, plays the movie: Top
/// Shelf's Play/Pause), `link?code=` (a TV's pairing code),
/// `requests` (Your requests), `inbox` (the admins' Inbox), `downloads`
/// (Settings › Downloads, iPhone and iPad), `lists/{id}` (a list's page),
/// `search` (Search, with the cursor in its field) and `home` (Home at its
/// top, where Continue watching is: the quick actions'),
/// as android/mobile/.../DeepLinks.kt reads them.
///
/// A link that starts playback or takes a tuner (`play/…`, `movies/{id}?play=1`, `live-tv/{id}`)
/// does so only when the app published it for the server signed in
/// (PLAN-APPLE-REVIEW.md §2.5, §3.4.7, as Android's DeepLink.open): such a
/// link carries `source=` and `server=` (``url(publishedFor:source:)``), and
/// one for another server opens nothing. Any other play link, from a web
/// page, a QR code or another app, opens the file's page with Play ready
/// (``filePage(fileID:)``), any other movie play link the movie's page, and
/// any other channel link opens the guide.
/// Open a link from outside with ``opening(_:signedInServer:)``; the app's
/// own launch arguments (`-open`) are the app's, and act as they read.
public enum DeepLink: Hashable, Sendable {
    case play(fileID: Int)
    /// The page of the movie or episode a file plays: a play link the app didn't publish.
    case filePage(fileID: Int)
    case liveTV(channelID: String?)
    case liveRecordings
    case show(id: Int, season: Int?, episode: Int? = nil)
    case movie(id: Int)
    /// Plays the movie's first version, resuming, as its page's Play does
    /// (Top Shelf's Play/Pause on a Recently added movie).
    case playMovie(id: Int)
    case mediaList(id: Int)
    case linkTV(code: String?)
    case myRequests
    case inbox
    case downloads
    case search
    case home

    public static let scheme = "watchmymedia"

    /// Nil for anything that is not one of the app's links.
    public init?(url: URL) {
        guard url.scheme?.lowercased() == Self.scheme else { return nil }
        let segments = ([url.host].compactMap { $0 } + url.pathComponents).filter { $0 != "/" && !$0.isEmpty }
        let query = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
        func value(_ name: String) -> String? { query.first { $0.name == name }?.value.flatMap { $0.isEmpty ? nil : $0 } }
        let id = segments.count > 1 ? Int(segments[1]) : nil
        switch segments.first?.lowercased() {
        case "live-tv": self = segments.dropFirst().first == "recordings" ? .liveRecordings : .liveTV(channelID: segments.count > 1 ? segments[1] : nil)
        case "play": guard let id else { return nil }; self = .play(fileID: id)
        case "shows":
            guard let id else { return nil }
            self = .show(id: id, season: value("season").flatMap(Int.init), episode: value("episode").flatMap(Int.init))
        case "movies":
            guard let id else { return nil }
            self = value("play") == "1" ? .playMovie(id: id) : .movie(id: id)
        case "lists": guard let id else { return nil }; self = .mediaList(id: id)
        case "link": self = .linkTV(code: value("code"))
        case "requests": self = .myRequests
        case "inbox": self = .inbox
        case "downloads": self = .downloads
        case "search": self = .search
        case "home": self = .home
        default: return nil
        }
    }

    public var url: URL {
        var c = URLComponents()
        c.scheme = Self.scheme
        var items: [URLQueryItem] = []
        switch self {
        case .liveRecordings:
            c.host = "live-tv"; c.path = "/recordings"
        case .liveTV(let id):
            c.host = "live-tv"
            if let id { c.path = "/\(id)" }
        case .play(let id), .filePage(let id):
            c.host = "play"
            c.path = "/\(id)"
        case .show(let id, let season, let episode):
            c.host = "shows"
            c.path = "/\(id)"
            items = [season.map { URLQueryItem(name: "season", value: String($0)) }, episode.map { URLQueryItem(name: "episode", value: String($0)) }].compactMap { $0 }
        case .movie(let id):
            c.host = "movies"
            c.path = "/\(id)"
        case .playMovie(let id):
            c.host = "movies"
            c.path = "/\(id)"
            items = [URLQueryItem(name: "play", value: "1")]
        case .mediaList(let id):
            c.host = "lists"
            c.path = "/\(id)"
        case .linkTV(let code):
            c.host = "link"
            items = code.map { [URLQueryItem(name: "code", value: $0)] } ?? []
        case .myRequests:
            c.host = "requests"
        case .inbox:
            c.host = "inbox"
        case .downloads:
            c.host = "downloads"
        case .search:
            c.host = "search"
        case .home:
            c.host = "home"
        }
        c.queryItems = items.isEmpty ? nil : items
        return c.url!
    }
}

extension DeepLink {
    /// The server a link was published for (it says `source=` or `server=`),
    /// "" when it names none; nil for a link the app didn't publish.
    public static func publishedServer(_ url: URL) -> String? {
        guard url.scheme?.lowercased() == scheme else { return nil }
        let query = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
        let source = query.first { $0.name == "source" }?.value
        let server = query.first { $0.name == "server" }
        guard source != nil || server != nil else { return nil }
        return server?.value ?? ""
    }

    /// `url` as the app opens it with `serverID` signed in (nil when none
    /// is): a link the app published for another server, or for none, opens
    /// nothing; a play link the app didn't publish opens the file's page, a
    /// movie play link the movie's; a channel link it didn't publish opens
    /// the guide; every other link opens as it reads.
    public static func opening(_ url: URL, signedInServer serverID: String?) -> DeepLink? {
        let publisher = publishedServer(url)
        if let publisher, serverID == nil || publisher != serverID { return nil }
        guard let link = DeepLink(url: url) else { return nil }
        switch link {
        case .play(let id): return publisher != nil ? link : .filePage(fileID: id)
        case .playMovie(let id): return publisher != nil ? link : .movie(id: id)
        case .liveTV(_?): return publisher != nil ? link : .liveTV(channelID: nil)
        default: return link
        }
    }

    /// The link as the app publishes it (a reminder's notification, Top
    /// Shelf and quick actions), for `serverID`: only that server's sign-in opens it.
    public func url(publishedFor serverID: String, source: String = "app") -> URL {
        var c = URLComponents(url: url, resolvingAgainstBaseURL: false)!
        c.queryItems = (c.queryItems ?? []) + [URLQueryItem(name: "source", value: source), URLQueryItem(name: "server", value: serverID)]
        return c.url!
    }
}
