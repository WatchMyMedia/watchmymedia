import Foundation

/// A `watchmymedia://` link: `play/{fileId}` (Top Shelf, Continue watching),
/// `shows/{id}` (optionally `?season=` and `&episode=`, which opens that
/// episode's page), `movies/{id}`, `link?code=` (a TV's pairing code),
/// `requests` (Your requests), `inbox` (the admins' Inbox), `downloads`
/// (Settings › Downloads, iPhone and iPad) and `lists/{id}` (a list's page),
/// as android/mobile/.../DeepLinks.kt reads them.
public enum DeepLink: Hashable, Sendable {
    case play(fileID: Int)
    case liveTV(channelID: String?)
    case liveRecordings
    case show(id: Int, season: Int?, episode: Int? = nil)
    case movie(id: Int)
    case mediaList(id: Int)
    case linkTV(code: String?)
    case myRequests
    case inbox
    case downloads

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
        case "movies": guard let id else { return nil }; self = .movie(id: id)
        case "lists": guard let id else { return nil }; self = .mediaList(id: id)
        case "link": self = .linkTV(code: value("code"))
        case "requests": self = .myRequests
        case "inbox": self = .inbox
        case "downloads": self = .downloads
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
        case .play(let id):
            c.host = "play"
            c.path = "/\(id)"
        case .show(let id, let season, let episode):
            c.host = "shows"
            c.path = "/\(id)"
            items = [season.map { URLQueryItem(name: "season", value: String($0)) }, episode.map { URLQueryItem(name: "episode", value: String($0)) }].compactMap { $0 }
        case .movie(let id):
            c.host = "movies"
            c.path = "/\(id)"
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
        }
        c.queryItems = items.isEmpty ? nil : items
        return c.url!
    }
}
