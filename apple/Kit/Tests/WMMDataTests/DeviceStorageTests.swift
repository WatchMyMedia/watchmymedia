import Foundation
import Security
import Testing
@testable import WMMData
import WMMModel

/// The Keychain in memory, with failures the test chooses.
@MainActor
final class FakeKeychain: KeychainItems {
    struct Item {
        var data: Data
        var accessible: String
    }
    var items: [String: Item] = [:]
    /// Returned by every read, as a Keychain locked since a restart.
    var readStatus: OSStatus = errSecSuccess
    /// Returned by every update that changes only how an item is kept.
    var moveStatus: OSStatus = errSecSuccess
    private(set) var deletes: [String] = []

    private func account(_ query: [String: Any]) -> String { query[kSecAttrAccount as String] as? String ?? "" }

    func copy(_ query: [String: Any]) -> (status: OSStatus, item: [String: Any]?) {
        guard readStatus == errSecSuccess else { return (readStatus, nil) }
        guard let item = items[account(query)] else { return (errSecItemNotFound, nil) }
        return (errSecSuccess, [kSecValueData as String: item.data, kSecAttrAccessible as String: item.accessible])
    }

    func update(_ query: [String: Any], _ attributes: [String: Any]) -> OSStatus {
        let key = account(query)
        guard var item = items[key] else { return errSecItemNotFound }
        if attributes[kSecValueData as String] == nil, moveStatus != errSecSuccess { return moveStatus }
        if let data = attributes[kSecValueData as String] as? Data { item.data = data }
        if let accessible = attributes[kSecAttrAccessible as String] as? String { item.accessible = accessible }
        items[key] = item
        return errSecSuccess
    }

    func add(_ attributes: [String: Any]) -> OSStatus {
        items[account(attributes)] = Item(data: attributes[kSecValueData as String] as? Data ?? Data(),
                                          accessible: attributes[kSecAttrAccessible as String] as? String ?? "")
        return errSecSuccess
    }

    func delete(_ query: [String: Any]) -> OSStatus {
        deletes.append(account(query))
        return items.removeValue(forKey: account(query)) == nil ? errSecItemNotFound : errSecSuccess
    }
}

/// Sign-ins and the device ID stay on this device (PLAN-APPLE-REVIEW.md §3.4.2, §2.6).
@MainActor
@Suite struct DeviceStorageTests {
    let keychain = FakeKeychain()
    private let thisDevice = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly as String
    private let anyDevice = kSecAttrAccessibleAfterFirstUnlock as String
    private let credential = StoredCredential(user: User(id: 2, name: "viewer", isAdmin: false), sessionID: "s", token: "tok", baseURL: URL(string: "http://nas.local:27587"))

    private func storage() -> DeviceStorage {
        DeviceStorage(defaults: UserDefaults(suiteName: "wmm-tests-\(UUID().uuidString)")!, keychain: keychain)
    }

    @Test func newItemsAreKeptOnThisDeviceOnly() throws {
        let storage = storage()
        storage.setCredential(credential, serverID: "srv")
        _ = storage.deviceID()
        #expect(keychain.items["server:srv"]?.accessible == thisDevice)
        #expect(keychain.items["device-id"]?.accessible == thisDevice)
    }

    @Test func itemsSavedBeforeAreMovedInPlaceAsTheyAreRead() throws {
        keychain.items["server:srv"] = .init(data: try JSONEncoder().encode(credential), accessible: anyDevice)
        keychain.items["device-id"] = .init(data: Data("0123abcd".utf8), accessible: anyDevice)
        let storage = storage()
        #expect(storage.readCredential(serverID: "srv") == .found(credential))
        #expect(storage.deviceID() == "0123abcd")
        #expect(keychain.items["server:srv"]?.accessible == thisDevice)
        #expect(keychain.items["device-id"]?.accessible == thisDevice)
        #expect(keychain.deletes.isEmpty)
    }

    @Test func aMoveThatFailsKeepsTheSignInAsItWas() throws {
        keychain.items["server:srv"] = .init(data: try JSONEncoder().encode(credential), accessible: anyDevice)
        keychain.moveStatus = errSecInteractionNotAllowed
        let storage = storage()
        #expect(storage.readCredential(serverID: "srv") == .found(credential))
        #expect(keychain.items["server:srv"]?.accessible == anyDevice, "moved at a later read")
        #expect(keychain.deletes.isEmpty, "never deleted for it")
        keychain.moveStatus = errSecSuccess
        _ = storage.readCredential(serverID: "srv")
        #expect(keychain.items["server:srv"]?.accessible == thisDevice)
    }

    @Test func aLockedKeychainIsUnavailableNotMissing() throws {
        keychain.items["server:srv"] = .init(data: try JSONEncoder().encode(credential), accessible: thisDevice)
        keychain.readStatus = errSecInteractionNotAllowed
        let storage = storage()
        #expect(storage.readCredential(serverID: "srv") == .unavailable)
        #expect(keychain.deletes.isEmpty)
    }
}

/// A launch before the first unlock (PLAN-APPLE-REVIEW.md §3.4.3).
@MainActor
@Suite struct UnreadableSignInTests {
    @Test func aSignInUnreadableAtLaunchIsReadOnceTheDeviceUnlocks() async throws {
        let h = Harness()
        try await h.signIn()
        #expect(h.services.auth.user != nil)
        // A restart before the first unlock: the Keychain can't be read.
        h.storage.unavailable = [h.testServer.id]
        let locked = h.reopen()
        #expect(locked.auth.user == nil)
        #expect(h.storage.credentials[h.testServer.id] != nil, "the sign-in is still kept")
        // Still locked: nothing changes.
        locked.protectedDataBecameAvailable()
        #expect(locked.auth.user == nil)
        h.storage.unavailable = []
        locked.protectedDataBecameAvailable()
        #expect(locked.auth.user?.name == "viewer")
        #expect(locked.servers.current?.signedIn == true)
    }

    @Test func aSignOutMeanwhileIsNotUndone() async throws {
        let h = Harness()
        try await h.signIn()
        h.storage.unavailable = [h.testServer.id]
        let locked = h.reopen()
        let kept = try #require(h.storage.credentials[h.testServer.id])
        // Signed out on this run, while the Keychain couldn't delete the item: the sign-out stands.
        locked.store.setCredential(nil, serverID: h.testServer.id)
        h.storage.credentials[h.testServer.id] = kept
        h.storage.unavailable = []
        locked.protectedDataBecameAvailable()
        #expect(locked.auth.user == nil)
    }
}
