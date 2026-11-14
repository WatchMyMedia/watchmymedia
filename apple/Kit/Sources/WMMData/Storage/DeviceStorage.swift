import Foundation
import os
import Security

/// The Keychain calls ``DeviceStorage`` makes, so tests can stand in for the
/// system's.
@MainActor
protocol KeychainItems: AnyObject {
    /// One item's data and attributes (`kSecReturnData`, `kSecReturnAttributes`).
    func copy(_ query: [String: Any]) -> (status: OSStatus, item: [String: Any]?)
    func update(_ query: [String: Any], _ attributes: [String: Any]) -> OSStatus
    func add(_ attributes: [String: Any]) -> OSStatus
    func delete(_ query: [String: Any]) -> OSStatus
}

/// The system's Keychain.
@MainActor
final class SystemKeychain: KeychainItems {
    func copy(_ query: [String: Any]) -> (status: OSStatus, item: [String: Any]?) {
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        return (status, item as? [String: Any])
    }
    func update(_ query: [String: Any], _ attributes: [String: Any]) -> OSStatus { SecItemUpdate(query as CFDictionary, attributes as CFDictionary) }
    func add(_ attributes: [String: Any]) -> OSStatus { SecItemAdd(attributes as CFDictionary, nil) }
    func delete(_ query: [String: Any]) -> OSStatus { SecItemDelete(query as CFDictionary) }
}

/// The device's storage: the device ID and each server's sign-in in the
/// Keychain (readable after the first unlock, kept across reinstalls, and
/// never leaving this device: a phone restored from a backup signs in again
/// and makes a device ID of its own, PLAN-APPLE-REVIEW.md §2.6), everything
/// else as JSON in UserDefaults. Items saved before as `AfterFirstUnlock`
/// are moved to `…ThisDeviceOnly` as they are read; a move that fails leaves
/// the item as it was, and a sign-in is never deleted for it.
@MainActor
final class DeviceStorage: LocalStorage {
    static let service = "media.watchmy.client"
    private static let deviceIDAccount = "device-id"
    private static let stateKey = "media.watchmy.client.state"
    private static let log = Logger(subsystem: "media.watchmy.client", category: "storage")

    /// How every item is kept.
    static let accessible = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly as String

    private let defaults: UserDefaults
    private let keychain: any KeychainItems
    private var madeID: String?

    init(defaults: UserDefaults = .standard, keychain: any KeychainItems = SystemKeychain()) {
        self.defaults = defaults
        self.keychain = keychain
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

    func readCredential(serverID: String) -> CredentialRead {
        switch read(account: Self.account(serverID)) {
        case .found(let data):
            return (try? JSONDecoder().decode(StoredCredential.self, from: data)).map(CredentialRead.found) ?? .missing
        case .missing:
            return .missing
        case .unavailable:
            return .unavailable
        }
    }

    func setCredential(_ credential: StoredCredential?, serverID: String) {
        let account = Self.account(serverID)
        guard let credential, let data = try? JSONEncoder().encode(credential) else {
            delete(account: account)
            return
        }
        write(data, account: account)
    }

    func deviceID() -> String {
        if let madeID { return madeID }
        switch read(account: Self.deviceIDAccount) {
        case .found(let data):
            if let id = String(data: data, encoding: .utf8), !id.isEmpty {
                madeID = id
                return id
            }
            fallthrough
        case .missing:
            let id = Self.newDeviceID()
            write(Data(id.utf8), account: Self.deviceIDAccount)
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

    private func read(account: String) -> ReadResult {
        var q = Self.query(account)
        q[kSecReturnData as String] = true
        q[kSecReturnAttributes as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        let (status, item) = keychain.copy(q)
        switch status {
        case errSecSuccess:
            guard let data = item?[kSecValueData as String] as? Data else { return .missing }
            if item?[kSecAttrAccessible as String] as? String != Self.accessible { keepOnThisDevice(account: account) }
            return .found(data)
        case errSecItemNotFound:
            return .missing
        default:
            Self.log.error("Keychain read failed (\(status))")
            return .unavailable
        }
    }

    /// Moves an item saved before to this-device-only, in place: a failure
    /// leaves it as it was, to be moved at the next read.
    private func keepOnThisDevice(account: String) {
        let status = keychain.update(Self.query(account), [kSecAttrAccessible as String: Self.accessible])
        if status != errSecSuccess { Self.log.error("Keychain move to this device failed (\(status))") }
    }

    private func write(_ data: Data, account: String) {
        let attributes: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: Self.accessible,
        ]
        var status = keychain.update(Self.query(account), attributes)
        if status == errSecItemNotFound {
            status = keychain.add(Self.query(account).merging(attributes) { $1 })
        }
        if status != errSecSuccess { Self.log.error("Keychain write failed (\(status))") }
    }

    private func delete(account: String) {
        let status = keychain.delete(Self.query(account))
        if status != errSecSuccess && status != errSecItemNotFound { Self.log.error("Keychain delete failed (\(status))") }
    }
}
