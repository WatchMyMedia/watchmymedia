import Foundation
import Network
import os
import WMMModel

/// Servers announcing themselves on the local network.
@MainActor
protocol ServerDiscovery: AnyObject {
    /// Starts browsing: `found` gets the whole list on every change, and
    /// `denied` whether the person said no to local network access.
    func start(found: @escaping @MainActor ([DiscoveredServer]) -> Void, denied: @escaping @MainActor (Bool) -> Void)
    func stop()
}

/// Finds nothing: for tests, and where browsing is not wanted.
@MainActor
final class NoDiscovery: ServerDiscovery {
    func start(found: @escaping @MainActor ([DiscoveredServer]) -> Void, denied: @escaping @MainActor (Bool) -> Void) {}
    func stop() {}
}

/// Bonjour through `NWBrowser`: browses `_watchmymedia._tcp` while started,
/// resolves each service to an address with a short-lived connection, reads
/// its TXT record (`id`, `version`, `api`, `path`), and drops services when
/// they say goodbye (android/core/data/.../ServerDiscovery.kt).
@MainActor
final class BonjourDiscovery: ServerDiscovery {
    static let serviceType = "_watchmymedia._tcp"
    /// kDNSServiceErr_PolicyDenied: the person turned local network access off.
    private static let policyDenied: Int32 = -65570
    private static let resolveTimeout: TimeInterval = 5
    private static let log = Logger(subsystem: "media.watchmy.client", category: "discovery")

    private var browser: NWBrowser?
    /// Resolved servers by service name.
    private var servers: [String: DiscoveredServer] = [:]
    /// Connections resolving a service, by service name.
    private var resolving: [String: NWConnection] = [:]
    private var found: (@MainActor ([DiscoveredServer]) -> Void)?
    private var denied: (@MainActor (Bool) -> Void)?

    func start(found: @escaping @MainActor ([DiscoveredServer]) -> Void, denied: @escaping @MainActor (Bool) -> Void) {
        stop()
        self.found = found
        self.denied = denied
        let browser = NWBrowser(for: .bonjourWithTXTRecord(type: Self.serviceType, domain: nil), using: .tcp)
        browser.stateUpdateHandler = { [weak self] state in
            MainActor.assumeIsolated { self?.browserChanged(state) }
        }
        browser.browseResultsChangedHandler = { [weak self] results, _ in
            MainActor.assumeIsolated { self?.resultsChanged(results) }
        }
        self.browser = browser
        browser.start(queue: .main)
    }

    func stop() {
        browser?.cancel()
        browser = nil
        for connection in resolving.values { connection.cancel() }
        resolving.removeAll()
        servers.removeAll()
        found = nil
        denied = nil
    }

    private func browserChanged(_ state: NWBrowser.State) {
        switch state {
        case .ready:
            denied?(false)
        case .waiting(let error), .failed(let error):
            if case .dns(let code) = error, code == Self.policyDenied {
                denied?(true)
            } else {
                Self.log.warning("Bonjour browsing stopped: \(error.localizedDescription)")
            }
        default:
            break
        }
    }

    private func resultsChanged(_ results: Set<NWBrowser.Result>) {
        var present = Set<String>()
        for result in results {
            guard case .service(let name, _, _, _) = result.endpoint else { continue }
            present.insert(name)
            guard servers[name] == nil, resolving[name] == nil else { continue }
            var txt: [String: String] = [:]
            if case .bonjour(let record) = result.metadata { txt = record.dictionary }
            resolve(result.endpoint, name: name, txt: txt, ipv4: true)
        }
        for name in Set(servers.keys).union(resolving.keys).subtracting(present) {
            servers[name] = nil
            resolving.removeValue(forKey: name)?.cancel()
        }
        publish()
    }

    /// Connects to the service to learn its address, preferring IPv4, then gives up the connection.
    private func resolve(_ endpoint: NWEndpoint, name: String, txt: [String: String], ipv4: Bool) {
        let parameters = NWParameters.tcp
        if ipv4, let ip = parameters.defaultProtocolStack.internetProtocol as? NWProtocolIP.Options { ip.version = .v4 }
        let connection = NWConnection(to: endpoint, using: parameters)
        resolving[name] = connection
        connection.stateUpdateHandler = { [weak self, weak connection] state in
            MainActor.assumeIsolated {
                guard let self, let connection else { return }
                self.resolveChanged(state, connection: connection, endpoint: endpoint, name: name, txt: txt, ipv4: ipv4)
            }
        }
        connection.start(queue: .main)
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.resolveTimeout) { [weak self, weak connection] in
            MainActor.assumeIsolated {
                guard let self, let connection, self.resolving[name] === connection else { return }
                self.resolveChanged(.failed(.posix(.ETIMEDOUT)), connection: connection, endpoint: endpoint, name: name, txt: txt, ipv4: ipv4)
            }
        }
    }

    private func resolveChanged(_ state: NWConnection.State, connection: NWConnection, endpoint: NWEndpoint, name: String, txt: [String: String], ipv4: Bool) {
        guard resolving[name] === connection else { return }
        switch state {
        case .ready:
            resolving[name] = nil
            if case .hostPort(let host, let port)? = connection.currentPath?.remoteEndpoint, let server = Self.server(name: name, host: host, port: Int(port.rawValue), txt: txt) {
                servers[name] = server
                publish()
            }
            connection.cancel()
        case .waiting, .failed:
            resolving[name] = nil
            connection.cancel()
            // No IPv4 address: try whatever the service has.
            if ipv4 { resolve(endpoint, name: name, txt: txt, ipv4: false) }
        default:
            break
        }
    }

    private func publish() {
        found?(servers.values.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending })
    }

    private static func server(name: String, host: NWEndpoint.Host, port: Int, txt: [String: String]) -> DiscoveredServer? {
        switch host {
        case .ipv4(let address):
            return discoveredServer(serviceName: name, host: "\(address)", ipv6: false, port: port, txt: txt)
        case .ipv6(let address):
            // A link-local address needs its interface, which a URL cannot carry well.
            if address.isLinkLocal { return nil }
            return discoveredServer(serviceName: name, host: "\(address)", ipv6: true, port: port, txt: txt)
        case .name(let hostName, _):
            return discoveredServer(serviceName: name, host: hostName, ipv6: false, port: port, txt: txt)
        @unknown default:
            return nil
        }
    }

    /// A resolved service as the server picker lists it; nil when the address makes no URL.
    nonisolated static func discoveredServer(serviceName: String, host: String, ipv6: Bool, port: Int, txt: [String: String]) -> DiscoveredServer? {
        let bare = host.split(separator: "%", maxSplits: 1).first.map(String.init) ?? host
        let shown = ipv6 ? "[\(bare)]" : bare
        let trimmedPath = (txt["path"] ?? "").trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        let path = trimmedPath.isEmpty ? "" : "/" + trimmedPath
        let id = txt["id"].flatMap { $0.trimmingCharacters(in: .whitespaces).isEmpty ? nil : $0 } ?? serviceName
        guard let url = URL(string: "http://\(shown):\(port)\(path)") else { return nil }
        return DiscoveredServer(
            id: id,
            name: serviceName,
            baseURL: url,
            version: txt["version"] ?? "",
            apiLevel: txt["api"].flatMap { Int($0) } ?? 0
        )
    }
}
