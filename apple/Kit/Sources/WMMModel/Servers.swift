import Foundation
import Observation

/// A WatchMyMedia server as GET /api/server describes it.
public struct Server: Hashable, Sendable, Codable, Identifiable {
    /// Stable random ID; servers are remembered by it, not by address.
    public var id: String
    public var name: String
    /// Normalised base URL without a trailing slash, such as http://192.168.1.20:27587.
    public var baseURL: URL
    public var version: String
    public var apiLevel: Int
    public var setupRequired: Bool
    /// The server wants a browser check (Cloudflare Turnstile) before a
    /// password sign-in, which apps cannot show: sign in by pairing instead.
    public var turnstile: Bool

    public init(id: String, name: String, baseURL: URL, version: String, apiLevel: Int, setupRequired: Bool = false, turnstile: Bool = false) {
        self.id = id
        self.name = name
        self.baseURL = baseURL
        self.version = version
        self.apiLevel = apiLevel
        self.setupRequired = setupRequired
        self.turnstile = turnstile
    }

    public var tooOld: Bool { !features.supported }

    /// "192.168.1.20:27587" or "home.example.net": the address without the scheme.
    public var displayAddress: String {
        var s = baseURL.absoluteString
        for prefix in ["https://", "http://"] where s.hasPrefix(prefix) { s.removeFirst(prefix.count) }
        return s
    }
}

/// An IPv4 address such as "192.168.1.20".
public func isIPv4Address(_ host: String) -> Bool {
    let parts = host.split(separator: ".", omittingEmptySubsequences: false)
    return parts.count == 4 && parts.allSatisfy { p in !p.isEmpty && p.count <= 3 && p.allSatisfy(\.isNumber) }
}

/// A host App Transport Security lets the app reach over plain http: an IP
/// address (v4 or v6), a `.local` name or localhost. Other names need https.
public func isLocalNetworkHost(_ host: String) -> Bool {
    let h = host.lowercased()
    return h.hasSuffix(".local") || h == "localhost" || isIPv4Address(h) || h.contains(":")
}

/// A host an Apple TV on the same network should reach, for AirPlay
/// (PLAN-IOS.md §5): a private IPv4 address (10/8, 172.16/12, 192.168/16) or
/// a `.local` name. Not a tailnet name or address, loopback, or a public
/// name, which needs the router to loop it back (hairpin NAT).
public func isLANHost(_ host: String) -> Bool {
    let h = host.lowercased()
    if h.hasSuffix(".local") { return true }
    guard isIPv4Address(h) else { return false }
    let octets = h.split(separator: ".").compactMap { Int($0) }
    guard octets.count == 4, octets.allSatisfy({ $0 <= 255 }) else { return false }
    return octets[0] == 10 || (octets[0] == 172 && (16...31).contains(octets[1])) || (octets[0] == 192 && octets[1] == 168)
}

/// A server found on the local network over Bonjour (`_watchmymedia._tcp`).
public struct DiscoveredServer: Hashable, Sendable, Identifiable {
    public var id: String
    public var name: String
    public var baseURL: URL
    public var version: String
    public var apiLevel: Int

    public init(id: String, name: String, baseURL: URL, version: String, apiLevel: Int) {
        self.id = id
        self.name = name
        self.baseURL = baseURL
        self.version = version
        self.apiLevel = apiLevel
    }
}

/// A server this device has used, with the account signed in on it, if any.
public struct SavedServer: Hashable, Sendable, Codable, Identifiable {
    public var server: Server
    public var userName: String?
    public var signedIn: Bool
    public var lastUsedAt: Date

    public init(server: Server, userName: String?, signedIn: Bool, lastUsedAt: Date) {
        self.server = server
        self.userName = userName
        self.signedIn = signedIn
        self.lastUsedAt = lastUsedAt
    }

    public var id: String { server.id }
}

/// Servers: found on the network, remembered, and the one in use. Methods
/// throw ``APIError`` or ``ServerTooOldError``.
@MainActor
public protocol ServerRepository: AnyObject, Observable {
    /// Servers announcing themselves on this network while discovery runs.
    var discovered: [DiscoveredServer] { get }

    /// Servers used before, most recent first.
    var saved: [SavedServer] { get }

    /// The server in use, or nil before one is chosen.
    var current: SavedServer? { get }

    /// Whether this app may use the local network; false after the person
    /// said no to the system's prompt (discovery then finds nothing).
    var localNetworkDenied: Bool { get }

    /// Browses the network while a server picker is on screen. Starts and
    /// stops are counted: browsing ends when every start has had its stop.
    func startDiscovery()

    func stopDiscovery()

    /// Looks an address up (GET /api/server) without choosing it. Accepts
    /// "192.168.1.20", "nas.local:27587", "https://home.example.net" and fills
    /// in the scheme and the default port.
    func probe(_ address: String) async throws -> Server

    /// Makes `server` the one in use.
    func select(_ server: Server) async throws

    /// The server in use at an address on this network (``isLANHost(_:)``),
    /// for an Apple TV that cannot reach the address in use: its own address
    /// when that is one, else one found over Bonjour that answers GET
    /// /api/server with the same `id`. Browses for a few seconds; nil when
    /// nothing turns up.
    func localAddress() async -> URL?

    /// Forgets a remembered server and its sign-in.
    func forget(serverID: String) async

    /// Asks the server in use who it is again (GET /api/server), so its
    /// version and API level follow an upgrade; quiet when it cannot be reached.
    func refreshCurrent() async

    /// Whether the server in use answered the last ``refreshCurrent()``: nil
    /// before one has, or for a server since chosen. The check gives up within
    /// seconds, so a screen can show what works offline (the offline Home)
    /// before its own request times out (PLAN-APPLE-REVIEW.md §4.1.5).
    var reachable: Bool? { get }
}

extension ServerRepository {
    public var reachable: Bool? { nil }
}
