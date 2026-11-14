import Foundation
import WMMAPI
import WMMModel

/// GET /api/server for the server in use, read once for everyone who asks
/// within a short while (PLAN-APPLE-REVIEW.md §4.1.3): the foreground
/// refresh of the API level and Cast's check ask together at launch and on
/// every return to the app. Unsigned, on the probe's session, so a server
/// that doesn't answer is known in seconds.
@MainActor
final class ServerInfoReader {
    /// How long an answer stands in for another request.
    static let maxAge: TimeInterval = 30

    private let store: LocalStore
    private let session: URLSession
    private let timeout: Duration
    private let now: @Sendable () -> Date
    private let flight = SingleFlight<Components.Schemas.ServerInfo>()
    private var last: (base: URL, at: Date, info: Components.Schemas.ServerInfo)?

    init(store: LocalStore, session: URLSession, timeout: Duration, now: @escaping @Sendable () -> Date) {
        self.store = store
        self.session = session
        self.timeout = timeout
        self.now = now
    }

    /// The server's description at `base`: the last one when it is newer
    /// than `maxAge` (nil: ask again whatever), else one request, shared.
    func info(at base: URL, maxAge: TimeInterval? = ServerInfoReader.maxAge) async throws -> Components.Schemas.ServerInfo {
        if let maxAge, let last, last.base == base, now().timeIntervalSince(last.at) < maxAge { return last.info }
        let api = store.api(baseURL: base, session: session)
        let timeout = timeout
        let info = try await flight.run(stamp: base.absoluteString + (maxAge == nil ? "#fresh" : "")) {
            try await withDeadline(timeout) { try await api.client.getServer().ok.body.json }
        }
        last = (base, now(), info)
        return info
    }
}
