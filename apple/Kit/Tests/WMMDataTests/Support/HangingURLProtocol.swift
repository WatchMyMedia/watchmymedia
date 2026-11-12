import Foundation
import Synchronization

/// Never answers requests to the origins it is given ("https://host:443"), so
/// callers time out; other requests go on to ``StubURLProtocol``.
final class HangingURLProtocol: URLProtocol, @unchecked Sendable {
    private static let origins = Mutex<Set<String>>([])

    static func hang(_ origin: String) {
        _ = origins.withLock { $0.insert(origin) }
    }

    static func release(_ origin: String) {
        _ = origins.withLock { $0.remove(origin) }
    }

    static func origin(_ url: URL) -> String {
        let scheme = url.scheme ?? "http"
        return "\(scheme)://\(url.host ?? ""):\(url.port ?? (scheme == "https" ? 443 : 80))"
    }

    override class func canInit(with request: URLRequest) -> Bool {
        guard let url = request.url else { return false }
        return origins.withLock { $0.contains(origin(url)) }
    }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {}

    override func stopLoading() {}
}
