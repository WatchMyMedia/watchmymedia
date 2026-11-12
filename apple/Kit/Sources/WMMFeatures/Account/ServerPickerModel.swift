import Foundation
import Observation
import WMMModel

/// Choosing a server (ServerPicker.dc.html): found on this network, remembered,
/// or typed. Every way probes the address first (GET /api/server), refuses a
/// server that is too old, then selects it (android/.../ServerPickerViewModel.kt).
@MainActor
@Observable
public final class ServerPickerModel {
    /// One server in a list. `address` is what connecting probes.
    public struct Row: Identifiable, Hashable, Sendable {
        public enum Kind: Hashable, Sendable { case found, remembered }

        /// The server's ID.
        public var id: String
        public var kind: Kind
        public var name: String
        public var address: String
        /// "192.168.1.20:27587 · v0.9.3 · signed in as Sam" or "Last used 3 days ago · not signed in".
        public var detail: String
        public var tooOld: Bool
        /// The server in use now.
        public var current: Bool
    }

    /// What the connecting spinner sits on: a row's ID, or ``addressKey``.
    public static let addressKey = "address"

    private let servers: any ServerRepository
    private let now: () -> Date
    private let selectionAllowed: () -> Bool

    /// The address typed under Enter an address.
    public var address = "" {
        didSet { if address != oldValue { addressError = nil } }
    }

    /// The row (or ``addressKey``) being connected to.
    public private(set) var connecting: String?
    /// Why connecting to a listed server failed.
    public private(set) var error: String?
    /// Why connecting to the typed address failed.
    public private(set) var addressError: String?
    public private(set) var discovering = false
    private var discoveryOwners: Set<UUID> = []

    public init(servers: any ServerRepository, now: @escaping () -> Date = { .now }, selectionAllowed: @escaping () -> Bool = { true }) {
        self.servers = servers
        self.now = now
        self.selectionAllowed = selectionAllowed
    }

    /// Servers announcing themselves on this network, with their sign-in.
    public var found: [Row] {
        let saved = Dictionary(servers.saved.map { ($0.server.id, $0) }, uniquingKeysWith: { a, _ in a })
        return servers.discovered.map { d in
            let tooOld = d.apiLevel < requiredAPILevel
            return Row(
                id: d.id, kind: .found, name: d.name, address: d.baseURL.absoluteString,
                detail: [d.displayAddress, versionLabel(d.version), tooOld ? "too old for this app" : accountLabel(saved[d.id])].joined(separator: " · "),
                tooOld: tooOld, current: servers.current?.server.id == d.id
            )
        }
    }

    /// Servers used before that are not on this network now, most recent
    /// first. One too old for this app says so, as a found one does.
    public var remembered: [Row] {
        let foundIDs = Set(servers.discovered.map(\.id))
        return servers.saved.filter { !foundIDs.contains($0.server.id) }.map { s in
            let detail = s.server.tooOld
                ? [s.server.displayAddress, s.server.versionLabel, "too old for this app"].joined(separator: " · ")
                : "Last used \(formatAgo(s.lastUsedAt, now: now())) · \(accountLabel(s))"
            return Row(
                id: s.server.id, kind: .remembered, name: s.server.name, address: s.server.baseURL.absoluteString,
                detail: detail, tooOld: s.server.tooOld, current: servers.current?.server.id == s.server.id
            )
        }
    }

    /// The person said no to local network access, so discovery finds nothing.
    public var localNetworkDenied: Bool { servers.localNetworkDenied }

    /// A server is chosen already, so the picker can go back to sign-in.
    public var canGoBack: Bool { servers.current != nil }

    /// The typed address is plain http to a host name, which iOS refuses.
    public var showsHTTPSNudge: Bool { needsHTTPSNudge(address) }

    /// Browses the network while the picker is on screen.
    public func startDiscovery() {
        guard !discovering else { return }
        discovering = true
        servers.startDiscovery()
    }

    public func stopDiscovery() {
        guard discovering else { return }
        discovering = false
        servers.stopDiscovery()
    }

    /// A retained picker can briefly have both its compact and regular views
    /// mounted during a resize. One disappearing must not stop the other.
    public func startDiscovery(owner: UUID) {
        discoveryOwners.insert(owner)
        startDiscovery()
    }

    public func stopDiscovery(owner: UUID) {
        discoveryOwners.remove(owner)
        if discoveryOwners.isEmpty { stopDiscovery() }
    }

    /// Connects to a listed server; true once it is the one in use.
    @discardableResult
    public func choose(_ row: Row) async -> Bool {
        await connect(to: row.address, key: row.id, label: row.name)
    }

    /// Connects to the typed address; true once it is the one in use.
    @discardableResult
    public func connectToAddress() async -> Bool {
        let typed = address.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !typed.isEmpty else {
            addressError = "Enter the server's address."
            return false
        }
        return await connect(to: typed, key: Self.addressKey, label: typed)
    }

    /// Forgets a remembered server and its sign-in.
    public func forget(_ row: Row) async {
        await servers.forget(serverID: row.id)
    }

    public func dismissError() { error = nil }

    private func connect(to address: String, key: String, label: String) async -> Bool {
        guard connecting == nil else { return false }
        connecting = key
        error = nil
        addressError = nil
        defer { connecting = nil }
        do {
            let server = try await servers.probe(address)
            if server.tooOld { throw ServerTooOldError(server: server) }
            try Task.checkCancellation()
            // A Settings switch belongs to the account that opened it. A
            // sign-out or account change during the probe must not select
            // its result over the new context.
            guard selectionAllowed() else { throw CancellationError() }
            try await servers.select(server)
            if key == Self.addressKey { self.address = "" }
            return true
        } catch is CancellationError {
            return false
        } catch {
            let text = connectMessage(error, label: label)
            if key == Self.addressKey { addressError = text } else { self.error = text }
            return false
        }
    }
}
