import Foundation
import WMMModel

/// A server this device has used, as it is kept on the device.
struct StoredServer: Codable, Equatable, Sendable {
    var server: Server
    var lastUsedAt: Date
    /// Who signed in here last; kept after signing out, for the server list.
    var lastUserName: String?
}

/// Everything the data layer keeps on the device that is not a secret.
struct StoredState: Codable, Equatable, Sendable {
    /// Most recent first.
    var servers: [StoredServer] = []
    var currentID: String?
    var deviceName: String?
    /// A ``StreamQuality`` raw value.
    var quality: String?
    /// Shows whose page lists missing episodes, as "serverID/showID".
    var missingShown: [String]?

    var current: StoredServer? { servers.first { $0.server.id == currentID } }

    mutating func updateServer(_ id: String, _ change: (inout StoredServer) -> Void) {
        guard let i = servers.firstIndex(where: { $0.server.id == id }) else { return }
        change(&servers[i])
    }
}

/// The account signed in on a server: who, and the bearer token. Kept with
/// the token so a server chosen again (after a reinstall, say) is signed in.
struct StoredCredential: Codable, Equatable, Sendable {
    var user: User
    var sessionID: String?
    var token: String
    /// The endpoint that issued this credential; nil only in older app storage.
    var baseURL: URL? = nil
}

/// Where the data layer keeps its state: the Keychain and UserDefaults on a
/// device (``DeviceStorage``), memory in tests (``MemoryStorage``).
@MainActor
protocol LocalStorage: AnyObject {
    func loadState() -> StoredState
    func saveState(_ state: StoredState)
    func credential(serverID: String) -> StoredCredential?
    /// Nil removes it.
    func setCredential(_ credential: StoredCredential?, serverID: String)
    /// This device's random ID, made on first use and kept.
    func deviceID() -> String
}

extension LocalStorage {
    /// 32 hex digits, as the Android app makes them.
    static func newDeviceID() -> String {
        UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
    }
}

/// Keeps everything in memory: for tests, and for a second ``DataServices``
/// on the same storage, as after a restart.
@MainActor
final class MemoryStorage: LocalStorage {
    var state = StoredState()
    var credentials: [String: StoredCredential] = [:]
    private var id: String?

    init(state: StoredState = StoredState(), deviceID: String? = nil) {
        self.state = state
        id = deviceID
    }

    func loadState() -> StoredState { state }
    func saveState(_ state: StoredState) { self.state = state }
    func credential(serverID: String) -> StoredCredential? { credentials[serverID] }
    func setCredential(_ credential: StoredCredential?, serverID: String) { credentials[serverID] = credential }

    func deviceID() -> String {
        if let id { return id }
        let made = Self.newDeviceID()
        id = made
        return made
    }
}
