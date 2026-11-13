import Foundation
import Observation
import OpenAPIRuntime
import WMMAPI
import WMMModel

/// Servers found on the network, remembered, and the one in use
/// (android/core/data/.../RealServerRepository.kt).
@MainActor
@Observable
final class RealServerRepository: ServerRepository {
    private(set) var discovered: [DiscoveredServer] = []
    private(set) var localNetworkDenied = false

    var saved: [SavedServer] { store.saved }
    var current: SavedServer? { store.current }
    var reachable: Bool? { reachability?.serverID == current?.server.id ? reachability?.answered : nil }

    /// What the last check of the server in use found.
    private var reachability: (serverID: String, answered: Bool)?

    @ObservationIgnored private let store: LocalStore
    @ObservationIgnored private let discovery: any ServerDiscovery
    @ObservationIgnored private let probeSession: URLSession
    @ObservationIgnored private let probeTimeout: Duration
    @ObservationIgnored private let localAddressTimeout: Duration
    @ObservationIgnored private let now: @Sendable () -> Date
    @ObservationIgnored private let reader: ServerInfoReader
    /// The screens browsing now; discovery runs while there is one.
    @ObservationIgnored private var browsing = 0

    /// - Parameters:
    ///   - probeSession: for looking up a typed address; gives up connecting quickly.
    ///   - probeTimeout: how long one address may take altogether.
    ///   - localAddressTimeout: how long ``localAddress()`` looks.
    init(store: LocalStore, discovery: any ServerDiscovery, probeSession: URLSession, probeTimeout: Duration, localAddressTimeout: Duration = .seconds(10),
         now: @escaping @Sendable () -> Date, reader: ServerInfoReader? = nil) {
        self.reader = reader ?? ServerInfoReader(store: store, session: probeSession, timeout: probeTimeout, now: now)
        self.store = store
        self.discovery = discovery
        self.probeSession = probeSession
        self.probeTimeout = probeTimeout
        self.localAddressTimeout = localAddressTimeout
        self.now = now
    }

    /// Counted, so a picker that takes another's place (the iPad's sign-in
    /// screen when its window widens) keeps discovery going although the
    /// old one's stop comes after the new one's start.
    func startDiscovery() {
        browsing += 1
        guard browsing == 1 else { return }
        discovered = []
        discovery.start(
            found: { [weak self] in self?.discovered = $0 },
            denied: { [weak self] in self?.localNetworkDenied = $0 }
        )
    }

    func stopDiscovery() {
        guard browsing > 0 else { return }
        browsing -= 1
        if browsing == 0 { discovery.stop() }
    }

    /// Tries each URL the address could mean, in order, and returns the first
    /// that answers as a WatchMyMedia server; refuses one that is too old.
    func probe(_ address: String) async throws -> Server {
        var reached = false
        var explained: APIError?
        for base in try ServerAddress.candidates(address) {
            try Task.checkCancellation()
            do {
                let server = try await lookUp(base, within: probeTimeout)
                if server.tooOld { throw ServerTooOldError(server: server) }
                return server
            } catch let error as ServerTooOldError {
                throw error
            } catch {
                if Task.isCancelled { throw CancellationError() }
                switch Self.failure(error) {
                case .unreachable: break
                case .explained(let message): explained = message
                case .reached: reached = true
                }
            }
        }
        let shown = address.trimmingCharacters(in: .whitespacesAndNewlines)
        if reached { throw APIError(status: nil, message: "\(shown) doesn't look like a WatchMyMedia server.") }
        if let explained { throw explained }
        throw APIError(status: nil, message: "Couldn't reach \(shown). Check the address and that the server is running.")
    }

    /// GET /api/server at `base`.
    private func lookUp(_ base: URL, within timeout: Duration) async throws -> Server {
        let api = store.api(baseURL: base, session: probeSession)
        let info = try await withDeadline(timeout) { try await api.client.getServer().ok.body.json }
        return Server(
            id: info.id, name: info.name, baseURL: base, version: info.version, apiLevel: Int(info.apiLevel),
            setupRequired: info.setupRequired, turnstile: info.turnstile
        )
    }

    /// Only an explicitly selected LAN endpoint is trusted for AirPlay.
    /// Discovery and a matching public ID cannot authenticate another origin.
    /// Otherwise keep the original media URL instead of forwarding its token.
    func localAddress() async -> URL? {
        guard let server = current?.server, isLANHost(server.baseURL.host() ?? "") else { return nil }
        return server.baseURL
    }

    private enum Failure {
        /// Nothing answered.
        case unreachable
        /// Something stopped the connection that the person can fix (https, a certificate).
        case explained(APIError)
        /// Something answered, but not as a WatchMyMedia server.
        case reached
    }

    private static func failure(_ error: Error) -> Failure {
        var underlying = error
        while let wrapped = underlying as? ClientError { underlying = wrapped.underlyingError }
        if underlying is TimedOut { return .unreachable }
        guard let url = underlying as? URLError else { return .reached }
        switch url.code {
        case .appTransportSecurityRequiresSecureConnection, .serverCertificateUntrusted, .serverCertificateHasBadDate,
             .serverCertificateNotYetValid, .serverCertificateHasUnknownRoot:
            return (ServerAPI.translate(url) as? APIError).map(Failure.explained) ?? .unreachable
        default:
            return .unreachable
        }
    }

    /// Makes `server` the one in use, remembering it most recent first; its
    /// sign-in, if one is kept, is in use again.
    func select(_ server: Server) async throws {
        if server.tooOld { throw ServerTooOldError(server: server) }
        store.loadCredential(serverID: server.id, baseURL: server.baseURL)
        let now = now()
        store.update { state in
            var entry = state.servers.first { $0.server.id == server.id } ?? StoredServer(server: server, lastUsedAt: now)
            // Public server IDs identify saved entries, never credential origins.
            entry.server = server
            entry.lastUsedAt = now
            state.servers = [entry] + state.servers.filter { $0.server.id != server.id }
            state.currentID = server.id
        }
    }

    /// Keeps the address, the sign-in and when it was last used; a server
    /// that now answers as another, or is too old, is left as it was. An
    /// answer from the last few seconds (Cast's check) stands in for asking.
    func refreshCurrent() async {
        await refreshCurrent(fresh: false)
    }

    /// `fresh` asks whatever was heard lately: the event stream came back,
    /// perhaps from a server upgraded meanwhile. True when the API level changed.
    @discardableResult
    func refreshCurrent(fresh: Bool) async -> Bool {
        guard let saved = current?.server else { return false }
        let info: Components.Schemas.ServerInfo
        do {
            info = try await reader.info(at: saved.baseURL, maxAge: fresh ? nil : ServerInfoReader.maxAge)
        } catch {
            if !(error is CancellationError), current?.server.id == saved.id { reachability = (saved.id, false) }
            return false
        }
        guard current?.server.id == saved.id else { return false }
        reachability = (saved.id, true)
        let found = Server(
            id: info.id, name: info.name, baseURL: saved.baseURL, version: info.version, apiLevel: Int(info.apiLevel),
            setupRequired: info.setupRequired, turnstile: info.turnstile
        )
        guard found.id == saved.id, !found.tooOld, found != saved else { return false }
        store.update { $0.updateServer(saved.id) { $0.server = found } }
        return found.apiLevel != saved.apiLevel
    }

    /// Forgets the server and its sign-in, and ends the session on the server
    /// when it can be reached (forgetting works offline either way).
    func forget(serverID: String) async {
        guard let gone = store.state.servers.first(where: { $0.server.id == serverID }) else { return }
        let credential = store.loadCredential(serverID: serverID)
        store.update { state in
            state.servers.removeAll { $0.server.id == serverID }
            if state.currentID == serverID { state.currentID = nil }
        }
        store.setCredential(nil, serverID: serverID)
        if let credential {
            let store = store
            Task { await store.signOutQuietly(baseURL: gone.server.baseURL, token: credential.token) }
        }
    }
}
