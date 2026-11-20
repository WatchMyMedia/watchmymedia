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

/// Why the server keeps an admin out of its settings here (`adminLimit` on GET /api/auth/status).
public enum AdminLimit: String, Hashable, Sendable {
    /// Away from home with admin settings away from home turned off.
    case network
    /// Away from home over plain http, or a proxy the server doesn't trust to say it is https.
    case https
}

/// What the signed-in account may do with the server's settings from here
/// (GET /api/auth/status). The apps have no
/// server settings of their own: an admin is handed to the web app.
public enum AdminAccess: Hashable, Sendable {
    /// Not an admin: Server settings isn't offered.
    case member
    /// An admin at home (the home network, the tailnet or the server itself).
    case home
    /// An admin away from home, let in over HTTPS: risky changes ask for the password again.
    case away
    /// An admin the server keeps out from here, and why.
    case keptOut(AdminLimit)

    /// From the auth status' fields. A server from before `onAdminNetwork`
    /// can't say an admin is away, so an allowed admin is at home there; a
    /// limit this app doesn't know is read as the network's.
    public init(isAdmin: Bool, adminAllowed: Bool, onAdminNetwork: Bool?, adminLimit: String?) {
        if !isAdmin {
            self = .member
        } else if adminAllowed {
            self = onAdminNetwork == false ? .away : .home
        } else {
            self = .keptOut(adminLimit.flatMap(AdminLimit.init(rawValue:)) ?? .network)
        }
    }

    public var isAdmin: Bool { self != .member }
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

    /// What the signed-in account may do with the server's settings from
    /// here (GET /api/auth/status); nil when nobody is signed in or the
    /// server couldn't be asked.
    func adminAccess() async -> AdminAccess?

    /// Changes the signed-in account's password (PUT /api/auth/password).
    /// The server signs out every other device of the account and keeps
    /// this one. Throws ``APIError``: 401 when `current` is wrong, 429 after
    /// too many tries, 422 when `new` isn't 8 to 256 characters.
    func changePassword(current: String, new: String) async throws
}

extension AuthRepository {
    /// From the account alone, for repositories that can't ask the server.
    public func adminAccess() async -> AdminAccess? {
        user.map { $0.isAdmin ? .home : .member }
    }

    /// Refuses, for repositories that can't change a password.
    public func changePassword(current: String, new: String) async throws {
        throw APIError(status: 501, message: "This app can’t change your password here. Change it in the web app.")
    }
}
