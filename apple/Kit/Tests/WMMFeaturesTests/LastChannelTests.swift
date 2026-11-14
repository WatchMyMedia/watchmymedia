import Foundation
import Testing
import WMMFakes
import WMMModel
@testable import WMMFeatures

/// The channel watched before this one (PLAN-APPLE-REVIEW.md §6.2), which
/// Ch− (the previous channel in the list) is not.
@Suite @MainActor
struct LastChannelTests {
    private func fixture() async throws -> (LivePlayerModel, FakeLiveTVRepository) {
        FakeLatency.duration = .zero
        let repository = FakeLiveTVRepository()
        try await repository.refresh()
        let model = LivePlayerModel(request: .init(channel: repository.channels[0]), repository: repository, controller: FakeLivePlaybackController())
        model.start()
        return (model, repository)
    }

    @Test func thereIsNoneUntilTheFirstSwitch() async throws {
        let (model, repository) = try await fixture()
        #expect(model.lastChannel == nil)
        model.switchToLastChannel()
        #expect(model.state.channel?.id == repository.channels[0].id, "nothing happens")
        model.dispose()
    }

    @Test func lastGoesBackToTheChannelWatchedBeforeNotTheOneBeforeInTheList() async throws {
        let (model, repository) = try await fixture()
        let channels = repository.channels.filter(\.available)
        try #require(channels.count >= 4)
        model.switchChannel(channels[3])
        #expect(model.lastChannel?.id == channels[0].id)
        model.switchToLastChannel()
        #expect(model.state.channel?.id == channels[0].id, "back to the first, not the third in the list")
        #expect(model.lastChannel?.id == channels[3].id)
        model.switchToLastChannel()
        #expect(model.state.channel?.id == channels[3].id, "pressed again, back again")
        model.dispose()
    }

    @Test func chMinusAndChPlusCountAsSwitches() async throws {
        let (model, repository) = try await fixture()
        let channels = repository.channels.filter(\.available)
        model.nextChannel()
        #expect(model.state.channel?.id == channels[1].id)
        #expect(model.lastChannel?.id == channels[0].id)
        model.dispose()
    }

    @Test func choosingThePlayingChannelAgainKeepsTheLastOne() async throws {
        let (model, repository) = try await fixture()
        let channels = repository.channels.filter(\.available)
        model.switchChannel(channels[2])
        model.switchChannel(channels[2])
        #expect(model.lastChannel?.id == channels[0].id)
        model.dispose()
    }

    @Test func aLastChannelThatCantBeWatchedNowIsNotTuned() async throws {
        let (model, repository) = try await fixture()
        let first = repository.channels[0]
        model.switchChannel(repository.channels[2])
        repository.availableChannels[0].available = false
        try await repository.refresh()
        #expect(repository.channels.first { $0.id == first.id }?.available == false)
        model.switchToLastChannel()
        #expect(model.state.channel?.id == repository.channels[2].id, "the list's word on it now counts")
        model.dispose()
    }

    @Test func theTVModelPassesItOnOnlyWhileTheVideoTakesInput() async throws {
        FakeLatency.duration = .zero
        let repository = FakeLiveTVRepository()
        try await repository.refresh()
        let controller = FakeLivePlaybackController()
        let model = TVLivePlayerModel(request: .init(channel: repository.channels[0]), repository: repository, controller: controller)
        model.start()
        model.switchChannel(repository.channels[2])
        #expect(model.lastChannel?.id == repository.channels[0].id)
        model.switchToLastChannel()
        #expect(model.state.channel?.id == repository.channels[0].id)
        model.dispose()
        model.switchToLastChannel()
        #expect(model.state.channel?.id == repository.channels[0].id, "not once the player is gone")
    }
}
