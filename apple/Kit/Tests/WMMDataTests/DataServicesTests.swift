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
