import Foundation
import WMMAPI
import WMMModel

/// Cast grants are issued using one captured session and its original endpoint.
/// Alternate URLs come only from that server and receive only its scoped cast token.
/// The server's description comes from ``ServerInfoReader``, so Cast's check
/// and the level's refresh at launch and on return share one GET /api/server.
@MainActor
final class RealCastGateway: CastGateway {
    private let store: LocalStore
    private let reader: ServerInfoReader
    init(store: LocalStore, reader: ServerInfoReader) {
        self.store = store
        self.reader = reader
    }

    var identity: CastIdentity? {
        guard let server = store.current?.server, let user = store.user else { return nil }
        return CastIdentity(serverID: server.id, userID: user.id, origin: server.baseURL, revision: store.revision)
    }

    func onSessionChange(_ listener: @escaping @MainActor () -> Void) {
        store.onSessionChange { _ in listener() }
    }

    func capability() async throws -> CastCapability? {
        guard let identity, store.features.castTokens else { return nil }
        // The description is public; the identity's own address, as captured.
        let info: Components.Schemas.ServerInfo
        do { info = try await reader.info(at: identity.origin) } catch { throw ServerAPI.translate(error) }
        guard self.identity == identity else { throw SignedOutError() }
        guard info.id == identity.serverID, ServerFeatures(apiLevel: Int(info.apiLevel)).castTokens, info.casting == true,
              let appID = info.castAppId, appID == watchMyMediaCastAppID else { return nil }
        return CastCapability(identity: identity, apiLevel: Int(info.apiLevel), appID: appID)
    }

    func grant() async throws -> CastGrant {
        guard let identity else { throw SignedOutError() }
        let api = try store.signedIn().api
        guard let capability = try await capability(), self.identity == identity else {
            throw APIError(status: nil, message: "Casting is unavailable on this server.")
        }
        let token = try await api.call { try await $0.createCastToken().ok.body.json }
        guard self.identity == identity else { throw SignedOutError() }
        guard token.token.hasPrefix("c1."), token.expiresAt > .now else {
            throw APIError(status: nil, message: "The server did not issue a usable cast token.")
        }
        let urls = Self.safeURLs(token.urls + [identity.origin.absoluteString])
        guard !urls.isEmpty else { throw APIError(status: nil, message: "The TV has no address for this server.") }
        return CastGrant(capability: capability, token: token.token, expiresAt: token.expiresAt, urls: urls)
    }

    static func safeURLs(_ values: [String]) -> [URL] {
        var seen = Set<String>()
        return values.compactMap { value -> URL? in
            guard let parts = URLComponents(string: value.trimmingCharacters(in: .whitespacesAndNewlines)),
                  ["http", "https"].contains(parts.scheme?.lowercased() ?? ""),
                  let host = parts.host, !host.isEmpty, parts.user == nil, parts.password == nil,
                  parts.query == nil, parts.fragment == nil, let url = parts.url,
                  seen.insert(url.absoluteString).inserted else { return nil }
            return url
        }.prefix(8).map { $0 }
    }
}
