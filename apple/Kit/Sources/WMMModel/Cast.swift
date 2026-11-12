import Foundation

public let castAPILevel = 2
public let liveCastAPILevel = 14
public let watchMyMediaCastAppID = "0A4D0B6A"

/// A non-secret session identity. Revision changes on sign-in, sign-out and endpoint changes.
public struct CastIdentity: Hashable, Sendable {
    public var serverID: String
    public var userID: Int
    public var origin: URL
    public var revision: Int
    public init(serverID: String, userID: Int, origin: URL, revision: Int) {
        self.serverID = serverID; self.userID = userID; self.origin = origin; self.revision = revision
    }
}

public struct CastCapability: Hashable, Sendable {
    public var identity: CastIdentity
    public var apiLevel: Int
    public var appID: String
    public init(identity: CastIdentity, apiLevel: Int, appID: String = watchMyMediaCastAppID) {
        self.identity = identity; self.apiLevel = apiLevel; self.appID = appID
    }
}

/// A scoped cast credential issued only by the selected, signed-in server.
/// The sender's bearer credential never enters this value or the Cast SDK.
public struct CastGrant: Sendable {
    public var capability: CastCapability
    public var token: String
    public var expiresAt: Date
    public var urls: [URL]
    public init(capability: CastCapability, token: String, expiresAt: Date, urls: [URL]) {
        self.capability = capability; self.token = token; self.expiresAt = expiresAt; self.urls = urls
    }
}

@MainActor
public protocol CastGateway: AnyObject {
    var identity: CastIdentity? { get }
    func capability() async throws -> CastCapability?
    func grant() async throws -> CastGrant
    func onSessionChange(_ listener: @escaping @MainActor () -> Void)
}

@MainActor
public final class UnavailableCastGateway: CastGateway {
    public init() {}
    public var identity: CastIdentity? { nil }
    public func capability() async throws -> CastCapability? { nil }
    public func grant() async throws -> CastGrant { throw APIError(status: nil, message: "Casting is unavailable on this server.") }
    public func onSessionChange(_ listener: @escaping @MainActor () -> Void) {}
}
