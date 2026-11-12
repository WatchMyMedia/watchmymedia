import Foundation
import os
import Security

/// The device's storage: the device ID and each server's sign-in in the
/// Keychain (readable after the first unlock, and kept across reinstalls),
/// everything else as JSON in UserDefaults.
@MainActor
final class DeviceStorage: LocalStorage {
    static let service = "media.watchmy.client"
    private static let deviceIDAccount = "device-id"
    private static let stateKey = "media.watchmy.client.state"
    private static let log = Logger(subsystem: "media.watchmy.client", category: "storage")

    private let defaults: UserDefaults
    private var madeID: String?

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func loadState() -> StoredState {
        guard let data = defaults.data(forKey: Self.stateKey) else { return StoredState() }
        do {
            return try JSONDecoder().decode(StoredState.self, from: data)
        } catch {
            Self.log.error("could not read the saved servers; starting afresh: \(error.localizedDescription)")
            return StoredState()
        }
    }

    func saveState(_ state: StoredState) {
        do {
            defaults.set(try JSONEncoder().encode(state), forKey: Self.stateKey)
        } catch {
            Self.log.error("could not save the servers: \(error.localizedDescription)")
        }
    }

    func credential(serverID: String) -> StoredCredential? {
        switch Self.read(account: Self.account(serverID)) {
        case .found(let data):
            return try? JSONDecoder().decode(StoredCredential.self, from: data)
        case .missing, .unavailable:
            return nil
        }
    }

    func setCredential(_ credential: StoredCredential?, serverID: String) {
        let account = Self.account(serverID)
        guard let credential, let data = try? JSONEncoder().encode(credential) else {
            Self.delete(account: account)
            return
        }
        Self.write(data, account: account)
    }

    func deviceID() -> String {
        if let madeID { return madeID }
        switch Self.read(account: Self.deviceIDAccount) {
        case .found(let data):
            if let id = String(data: data, encoding: .utf8), !id.isEmpty {
                madeID = id
                return id
            }
            fallthrough
        case .missing:
            let id = Self.newDeviceID()
            Self.write(Data(id.utf8), account: Self.deviceIDAccount)
            madeID = id
            return id
        case .unavailable:
            // Locked since a restart: use an ID for this run and keep the saved one.
            let id = Self.newDeviceID()
            madeID = id
            return id
        }
    }

    // MARK: Keychain

    private enum ReadResult {
        case found(Data)
        case missing
        case unavailable
    }

    private static func account(_ serverID: String) -> String { "server:\(serverID)" }

    private static func query(_ account: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecUseDataProtectionKeychain as String: true,
        ]
    }

    private static func read(account: String) -> ReadResult {
        var q = query(account)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        let status = SecItemCopyMatching(q as CFDictionary, &item)
        switch status {
        case errSecSuccess:
            return (item as? Data).map(ReadResult.found) ?? .missing
        case errSecItemNotFound:
            return .missing
        default:
            log.error("Keychain read failed (\(status))")
            return .unavailable
        }
    }

    private static func write(_ data: Data, account: String) {
        let attributes: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlock,
        ]
        var status = SecItemUpdate(query(account) as CFDictionary, attributes as CFDictionary)
        if status == errSecItemNotFound {
            status = SecItemAdd(query(account).merging(attributes) { $1 } as CFDictionary, nil)
        }
        if status != errSecSuccess { log.error("Keychain write failed (\(status))") }
    }

    private static func delete(account: String) {
        let status = SecItemDelete(query(account) as CFDictionary)
        if status != errSecSuccess && status != errSecItemNotFound { log.error("Keychain delete failed (\(status))") }
    }
}
