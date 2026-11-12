import Foundation
import WMMModel

/// Turns what someone typed into the base URLs to try, in order, as
/// android/core/data/.../ServerAddress.kt does.
public enum ServerAddress {
    public static let defaultPort = 27587

    /// "192.168.1.20" and "nas.local" mean the server's own port over http.
    /// A port or scheme typed is kept. A domain name without either is tried
    /// over https first (a reverse proxy), then on the server's port.
    public static func candidates(_ input: String) throws -> [URL] {
        var text = input.trimmingCharacters(in: .whitespacesAndNewlines)
        while text.hasSuffix("/") { text.removeLast() }
        guard !text.isEmpty, !text.contains(where: \.isWhitespace) else { throw invalid }
        let schemeGiven = text.contains("://")
        guard var components = URLComponents(string: schemeGiven ? text : "http://\(text)"),
              let scheme = components.scheme?.lowercased(), scheme == "http" || scheme == "https",
              let host = components.host, !host.isEmpty
        else { throw invalid }
        components.scheme = scheme
        components.query = nil
        components.fragment = nil
        let portGiven = components.port != nil

        func with(scheme: String? = nil, port: Int?) -> URL? {
            var c = components
            if let scheme { c.scheme = scheme }
            c.port = port
            return c.url
        }

        let urls: [URL?]
        if portGiven {
            urls = [components.url]
        } else if schemeGiven && scheme == "https" {
            urls = [components.url]
        } else if schemeGiven {
            urls = [with(port: defaultPort), components.url]
        } else if isDomain(host) {
            urls = [with(scheme: "https", port: nil), with(port: defaultPort)]
        } else {
            urls = [with(port: defaultPort)]
        }
        var seen = Set<String>()
        return urls.compactMap { $0.map(normalise) }.filter { seen.insert($0.absoluteString).inserted }
    }

    /// The base URL as servers are remembered: no query, no trailing slash.
    public static func normalise(_ url: URL) -> URL {
        var s = url.absoluteString
        while s.hasSuffix("/") { s.removeLast() }
        return URL(string: s) ?? url
    }

    /// Whether plain http to this host is allowed by App Transport Security:
    /// an IP address or a `.local` name. Other hosts need https.
    public static func isLocal(_ url: URL) -> Bool {
        url.host.map(isLocalNetworkHost) ?? false
    }

    /// A name with dots that is not an address or an mDNS name: probably reached through a proxy.
    static func isDomain(_ host: String) -> Bool {
        host.contains(".") && !isLocalNetworkHost(host)
    }

    static let invalid = APIError(status: nil, message: "That doesn't look like a server address. Try one like 192.168.1.20 or nas.local.")
}
