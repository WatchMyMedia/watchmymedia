import Foundation
import Testing
@testable import WMMData
import WMMModel

@MainActor
@Suite(.timeLimit(.minutes(1)))
struct DataServicesTests {
    let h = Harness()

    @Test func theAPIIsTheCurrentSignInsOrSignedOut() async throws {
        #expect(throws: SignedOutError()) { try h.services.api() }
        try await h.select()
        #expect(throws: SignedOutError()) { try h.services.api() }
        try await h.signIn()
        let api = try h.services.api()
        #expect(api.baseURL == h.router.baseURL)
        #expect(api.authorizedRequest(api.url("/api/home")).value(forHTTPHeaderField: "Authorization") == "Bearer tok-viewer")
    }

    @Test func theClientKeepsTheStoredIDAndTheNameAsItIsNow() async throws {
        #expect(h.services.client.deviceID == "dev-1")
        #expect(h.services.client.name == "Sam’s iPhone")
        #expect(h.services.client.product == "WatchMyMedia for iOS")
        await h.services.auth.setDeviceName("Kitchen iPad")
        #expect(h.services.client.name == "Kitchen iPad")
        #expect(h.storage.state.deviceName == "Kitchen iPad")
    }

    @Test func appServicesCarryTheRepositories() async throws {
        let services = h.services.makeAppServices(playback: NoPlayback())
        #expect(services.servers === h.services.servers)
        #expect(services.auth === h.services.auth)
        #expect(services.library === h.services.library)
        #expect(services.preferences === h.services.preferences)
        #expect(services.client.deviceID == "dev-1")
    }

    @Test func aFreshDeviceIsSignedOutWithNoServer() {
        #expect(h.services.servers.current == nil)
        #expect(h.services.servers.saved.isEmpty)
        #expect(h.services.auth.user == nil)
        #expect(h.storage.state.deviceName == "Sam’s iPhone", "the first name is kept")
    }

    @Test func memoryStorageMakesTheDeviceIDOnce() {
        let storage = MemoryStorage()
        let id = storage.deviceID()
        #expect(id.count == 32)
        #expect(storage.deviceID() == id)
    }
}

/// Playback is not this module's: a factory that makes nothing.
@MainActor
private final class NoPlayback: PlaybackControllerFactory {
    func make() -> any PlaybackController { fatalError("not used") }
    func plans(fileIDs: [Int]) async -> [Int: PlaybackPlan] { [:] }
}

/// The API's own HTTP cache (PLAN-APPLE-REVIEW.md §4.1.6).
@MainActor
@Suite struct APICacheTests {
    let h = Harness()

    @Test func theCacheIsEmptiedWhenTheSignInChanges() async throws {
        let cache = URLCache(memoryCapacity: 1 << 20, diskCapacity: 0, directory: nil)
        h.apiCache = cache
        h.reopen()
        try await h.signIn()
        let request = URLRequest(url: URL(string: h.router.baseURL.absoluteString + "/api/home")!)
        let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: ["ETag": "\"v1\""])!
        cache.storeCachedResponse(CachedURLResponse(response: response, data: Data("{}".utf8)), for: request)
        #expect(cache.cachedResponse(for: request) != nil)
        await h.services.auth.signOut()
        #expect(cache.cachedResponse(for: request) == nil, "one account's replies never wait on disk for another")
    }

    @Test func theAppsCacheIsItsOwnUnderCaches() throws {
        let cache = DataServices.apiCache()
        #expect(cache !== URLCache.shared)
        #expect(cache.diskCapacity > 0)
    }
}

/// The offline routes' sign-in goes only to its own server (PLAN-APPLE-REVIEW.md §3.4.8).
@MainActor
@Suite struct OfflineGatewayOriginTests {
    let h = Harness()

    @Test func aRequestForAnotherAddressIsRefused() async throws {
        try await h.signIn()
        let owner = try #require(h.services.offline.owner())
        let own = try #require(h.services.offline.authorizedRequest(owner, url: URL(string: h.router.baseURL.absoluteString + "/api/offline/d1/playlist.m3u8")!))
        #expect(own.value(forHTTPHeaderField: "Authorization") == "Bearer tok-viewer")
        #expect(h.services.offline.authorizedRequest(owner, url: URL(string: "https://elsewhere.example.net/api/offline/d1/playlist.m3u8")!) == nil)
        let otherPort = URL(string: "http://\(h.server.host):9999/api/offline/d1/playlist.m3u8")!
        #expect(h.services.offline.authorizedRequest(owner, url: otherPort) == nil)
        #expect(own.value(forHTTPHeaderField: "User-Agent") == "WatchMyMedia/1.0 (ios; iOS 18.0)")
    }

    /// A download's token goes to its owner's server only, as playback's does,
    /// and a blank parameter name is access_token.
    @Test func aDownloadsTokenIsForItsOwnersServer() async throws {
        try await h.signIn()
        let owner = try #require(h.services.offline.owner())
        h.router.on("POST /api/offline/downloads/d1/token", .json(#"{"token":"o1.abc","expiresAt":"2099-01-01T00:00:00Z","param":" "}"#))
        let token = try await h.services.offline.token(owner, downloadID: "d1")
        #expect(token == MediaToken(token: "o1.abc", expiresAt: Date(timeIntervalSince1970: 4_070_908_800), param: "access_token", server: h.router.baseURL))
        let elsewhere = URL(string: "https://elsewhere.example.net/api/offline/d1/playlist.m3u8")!
        #expect(token.apply(to: elsewhere) == elsewhere)
    }

    /// A 401 on the offline routes went unread (it arrived as SignedOutError,
    /// not the APIError the gateway caught), so the sign-in stayed.
    @Test func aRefusedTokenSignsTheOwnerOut() async throws {
        try await h.signIn()
        let owner = try #require(h.services.offline.owner())
        h.router.always("POST /api/offline/downloads/d1/token", .problem(401, "sign in required"))
        await #expect(throws: SignedOutError()) { try await h.services.offline.token(owner, downloadID: "d1") }
        #expect(h.services.auth.user == nil)
        #expect(!h.services.offline.signedIn(owner))
    }
}
