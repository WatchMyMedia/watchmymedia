import Foundation
import Observation

public struct User: Hashable, Sendable, Codable, Identifiable {
    public var id: Int
    public var name: String
    public var isAdmin: Bool

    public init(id: Int, name: String, isAdmin: Bool) {
        self.id = id
        self.name = name
        self.isAdmin = isAdmin
    }
}

/// How this device describes itself to the server (sign-in, pairing, playback).
public struct ClientInfo: Hashable, Sendable {
    /// Random, made once and kept in the Keychain.
    public var deviceID: String
    /// The name people see in Signed-in devices and Activity; editable in Settings.
    public var name: String
    /// "WatchMyMedia for iOS".
    public var product: String
    /// "ios" or "tvos".
    public var platform: String
    public var version: String
    /// The device's model ("iPhone 17 Pro"); empty when unknown.
    public var model: String

    public init(deviceID: String, name: String, product: String, platform: String, version: String, model: String = "") {
        self.deviceID = deviceID
        self.name = name
        self.product = product
        self.platform = platform
        self.version = version
        self.model = model
    }
}

/// A code for someone signed in elsewhere to approve.
public struct PairingCode: Hashable, Sendable {
    /// Six characters, A–Z and 2–9 without O or I.
    public var code: String
    public var expiresAt: Date
    /// The approval page with the code filled in, for a QR code or an in-app browser.
    public var linkURL: URL
    public var pollInterval: Duration

    public init(code: String, expiresAt: Date, linkURL: URL, pollInterval: Duration) {
        self.code = code
        self.expiresAt = expiresAt
        self.linkURL = linkURL
        self.pollInterval = pollInterval
    }

    /// "K7M 4PQ", how the code is shown.
    public var spaced: String { spacedPairingCode(code) }
}

/// A six-character pairing code as it is shown: "K7M4PQ" → "K7M 4PQ".
public func spacedPairingCode(_ code: String) -> String {
    code.count == 6 ? "\(code.prefix(3)) \(code.suffix(3))" : code
}

public enum PairingState: Hashable, Sendable {
    case starting
    case waiting(PairingCode)
    /// Signed in; ``AuthRepository/user`` now holds the user.
    case approved(User)
    /// The code ran out; call ``AuthRepository/pair()`` again for a new one.
    case expired
    case failed(String)
}

/// A browser or app signed in to the account (Settings › Signed-in devices).
public struct DeviceSession: Hashable, Sendable, Identifiable {
    public enum Kind: String, Sendable { case browser, device }

    public var id: String
    public var kind: Kind
    public var name: String
    public var product: String?
    public var platform: String?
    public var version: String?
    public var lastIP: String?
    public var createdAt: Date
    public var lastSeenAt: Date
    public var current: Bool
    public var userName: String
    /// The device's model ("iPhone 17 Pro"), when the app said at sign-in.
    public var model: String?

    public init(id: String, kind: Kind, name: String, product: String? = nil, platform: String? = nil, version: String? = nil, lastIP: String? = nil, createdAt: Date, lastSeenAt: Date, current: Bool, userName: String, model: String? = nil) {
        self.id = id
        self.kind = kind
        self.name = name
        self.product = product
        self.platform = platform
        self.version = version
        self.lastIP = lastIP
        self.createdAt = createdAt
        self.lastSeenAt = lastSeenAt
        self.current = current
        self.userName = userName
        self.model = model
    }
}

/// A TV waiting behind a pairing code, as Link a TV shows it.
public struct PendingDevice: Hashable, Sendable {
    public var code: String
    public var name: String
    public var product: String?
    public var platform: String?
    public var version: String?
    public var ip: String?
    public var approved: Bool
    /// The TV's model ("Chromecast with Google TV"), when it said.
    public var model: String?
    /// When the code runs out (`expiresAt` of GET /api/auth/pair/{code}); codes
    /// last 10 minutes, so Link a TV says when the TV asked from it.
    public var expiresAt: Date?

    public init(code: String, name: String, product: String? = nil, platform: String? = nil, version: String? = nil, ip: String? = nil, approved: Bool, model: String? = nil, expiresAt: Date? = nil) {
        self.code = code
        self.name = name
        self.product = product
        self.platform = platform
        self.version = version
        self.ip = ip
        self.approved = approved
        self.model = model
        self.expiresAt = expiresAt
    }
}

/// Signing in on the server in use. Methods throw ``APIError``: a wrong
/// password is status 401, throttling 429.
@MainActor
public protocol AuthRepository: AnyObject, Observable {
    /// The signed-in user on the current server, or nil.
    var user: User? { get }

    /// This device's name as people see it; editable in Settings.
    var deviceName: String { get }

    func setDeviceName(_ name: String) async

    /// Password sign-in as a device (bearer token, kept for a year of disuse).
    func signIn(name: String, password: String) async throws -> User

    /// Sign-in by code, for servers whose sign-in protection refuses app
    /// passwords: starts a pairing, polls it, and yields each state until it
    /// is approved, expires or fails. Cancelling the iterating task stops polling.
    func pair() -> AsyncStream<PairingState>

    /// Signs this device out of the current server.
    func signOut() async

    /// Sessions of the signed-in user, or everyone's for an admin with `all`.
    func sessions(all: Bool) async throws -> [DeviceSession]

    func revoke(sessionID: String) async throws

    /// Link a TV: the device behind a code.
    func lookUpPairing(code: String) async throws -> PendingDevice

    /// Link a TV: signs the device behind `code` in as the current user.
    func approvePairing(code: String) async throws -> PendingDevice
}
