import Foundation
import Testing
@testable import WMMFeatures
import WMMFakes
import WMMModel

/// Remove from Continue watching, which keeps the place (API level 34).
@MainActor
@Suite struct ContinueWatchingRemovalTests {
    init() { FakeLatency.duration = .zero }

    private func home(level: Int) -> (HomeModel, TestLibrary) {
        let servers = FakeServerRepository(apiLevel: level)
        let library = TestLibrary()
        library.fake.servers = servers
        return (HomeModel(library: library, auth: FakeAuthRepository(), navigator: Navigator(), servers: servers), library)
    }

    @Test func removingFromContinueWatchingTakesTheCardOffAtOnce() async throws {
        let (model, library) = home(level: ServerFeatures.Level.redesign)
        await model.retry()
        #expect(model.canRemoveFromContinueWatching)
        let item = try #require(model.state.value?.continueWatching.first)
        await model.removeFromContinueWatching(item)
        #expect(library.hiddenCalls == ["hide episode \(item.episodeID!)"])
        #expect(model.state.value?.continueWatching.contains { $0.id == item.id } == false)
        #expect(model.message == continueRemovedNote)
        await model.retry()
        #expect(model.state.value?.continueWatching.contains { $0.id == item.id } == false, "the server keeps it off")
    }

    @Test func aRefusedRemovalPutsTheCardBack() async throws {
        let (model, library) = home(level: ServerFeatures.Level.redesign)
        await model.retry()
        let before = try #require(model.state.value?.continueWatching)
        library.failure = APIError(status: 503, message: "The server is starting up.")
        await model.removeFromContinueWatching(before[0])
        #expect(model.state.value?.continueWatching == before)
        #expect(model.message == "The server is starting up.")
    }

    @Test func olderServersOfferNoRemoval() async {
        let (model, _) = home(level: ServerFeatures.Level.watchlists)
        await model.retry()
        #expect(!model.canRemoveFromContinueWatching)
    }
}
