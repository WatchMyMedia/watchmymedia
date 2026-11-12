import Foundation
import Testing
import WMMFakes
import WMMModel
@testable import WMMFeatures

@Suite @MainActor
struct LiveTVModelsTests {
    @Test func searchUsesChannelNumberAndNameWithoutListings() async throws {
        FakeLatency.duration = .zero
        let repository = FakeLiveTVRepository()
        let model = LiveTVModel(repository: repository)
        await model.refresh()
        #expect(model.available)
        model.query = "  5.  "
        #expect(model.filteredChannels.map(\.number) == ["5.1", "5.2"])
        model.query = "cedar"
        #expect(model.filteredChannels.map(\.name) == ["Cedar One"])
        #expect(model.unavailableMessage == nil)
    }
    @Test func unavailableAndEmptyStatesExplainWebSetup() async {
        FakeLatency.duration = .zero
        let repository = FakeLiveTVRepository()
        repository.offered.allowed = false
        let model = LiveTVModel(repository: repository)
        await model.refresh()
        #expect(!model.available)
        #expect(model.unavailableMessage?.contains("access") == true)
        repository.offered.allowed = true; repository.availableChannels = []
        await model.refresh()
        #expect(model.unavailableMessage?.contains("refresh the tuner") == true)
    }
    @Test func previousAndNextWrapAndSkipUnavailableChannels() async throws {
        FakeLatency.duration = .zero
        let repository = FakeLiveTVRepository()
        repository.availableChannels[1].available = false
        try await repository.refresh()
        let controller = FakeLivePlaybackController()
        let model = LivePlayerModel(request: .init(channel: repository.channels[0]), repository: repository, controller: controller)
        model.start(); model.nextChannel()
        #expect(model.state.channel?.id == repository.channels[2].id)
        model.previousChannel(); model.previousChannel()
        #expect(model.state.channel?.id == repository.channels.last?.id)
        model.dispose()
    }
    @Test func switchingPreservesChosenFixedQuality() async throws {
        FakeLatency.duration = .zero
        let repository = FakeLiveTVRepository(); try await repository.refresh()
        let controller = FakeLivePlaybackController()
        let model = LivePlayerModel(request: .init(channel: repository.channels[0]), repository: repository, controller: controller)
        model.start(); controller.selectQuality(.p480); model.nextChannel()
        #expect(model.state.quality == .p480)
        model.dispose()
    }
    @Test func ordinaryBackgroundStopsAndForegroundDoesNotSilentlyRetune() async throws {
        FakeLatency.duration = .zero
        let repository = FakeLiveTVRepository(); try await repository.refresh()
        let controller = FakeLivePlaybackController()
        let model = LivePlayerModel(request: .init(channel: repository.channels[0]), repository: repository, controller: controller)
        model.start()
        model.enteredBackground(externalPlayback: false, pictureInPictureMayStart: false)
        #expect(model.state.status == .stopped)
        model.becameActive()
        #expect(model.state.status == .stopped)
        model.retry()
        #expect(model.state.status == .playing)
        model.dispose()
    }
    @Test func externalPlaybackAndPipKeepLiveSessionUntilPipClosesInBackground() async throws {
        FakeLatency.duration = .zero
        let repository = FakeLiveTVRepository(); try await repository.refresh()
        let controller = FakeLivePlaybackController()
        let model = LivePlayerModel(request: .init(channel: repository.channels[0]), repository: repository, controller: controller)
        model.start()
        model.enteredBackground(externalPlayback: true, pictureInPictureMayStart: false)
        #expect(model.state.status == .playing)
        model.pictureInPictureChanged(active: true, appActive: true)
        model.enteredBackground(externalPlayback: false, pictureInPictureMayStart: true)
        #expect(model.state.status == .playing)
        model.pictureInPictureChanged(active: false, appActive: false)
        #expect(model.state.status == .stopped)
        model.dispose()
    }
    @Test func closeReleasesAndCannotExitOrRestartASecondTime() async throws {
        FakeLatency.duration = .zero
        let repository = FakeLiveTVRepository(); try await repository.refresh()
        let controller = FakeLivePlaybackController()
        var exits = 0
        let model = LivePlayerModel(request: .init(channel: repository.channels[0]), repository: repository, controller: controller, onExit: { exits += 1 })
        model.start(); model.close()
        #expect(model.state.status == .stopped)
        model.close(); model.retry()
        #expect(exits == 1)
        model.switchChannel(repository.channels[1])
        #expect(model.state.status == .stopped)
    }
}
