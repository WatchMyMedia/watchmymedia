import AVFoundation
import Foundation
import Observation
import Testing
import WMMFakes
import WMMModel
@testable import WMMFeatures

@MainActor @Observable
private final class KeysControllerSpy: LivePlaybackController {
    var state = LivePlayerState()
    var player: AVPlayer?
    var starts: [String] = []
    var inputs = 0
    func start(_ request: LivePlayRequest) { starts.append(request.channel.id); state = .init(); state.channel = request.channel; state.quality = request.quality; state.status = .playing }
    func play() { state.status = .playing }
    func pause() { state.status = .paused; state.isAtLiveEdge = false }
    func togglePlayPause() { state.status == .paused ? play() : pause() }
    func goLive() { state.isAtLiveEdge = true; state.status = .playing }
    func selectQuality(_ quality: LiveQuality) { state.quality = quality }
    func noteUserInput() { inputs += 1; state.askStillWatching = false }
    func answerStillWatching(keepWatching: Bool) { state.askStillWatching = false }
    func stop() { state.status = .stopped }
    func release() { state.status = .stopped }
}

/// The channel buttons on Live TV's transport bar, the Channels tab's first
/// tiles and the remote's channel keys. The fakes' channels are 2.1 Cedar One,
/// 5.1 Northline, 5.2 Northline Extra, 8.1 Coast Public and 12.1 Valley News.
@Suite @MainActor
struct TVLiveChannelKeysTests {
    private func fixture() async throws -> (TVLivePlayerModel, KeysControllerSpy, FakeLiveTVRepository) {
        FakeLatency.duration = .zero
        let repository = FakeLiveTVRepository()
        try await repository.refresh()
        let controller = KeysControllerSpy()
        let model = TVLivePlayerModel(request: .init(channel: repository.channels[1]), repository: repository, controller: controller)
        model.start()
        return (model, controller, repository)
    }

    @Test func theChannelButtonsAreOnTheTransportBarNamingWhereTheyGoAndNoneTakesSelect() async throws {
        let (model, _, _) = try await fixture()
        #expect(model.contextualActions.isEmpty, "nothing takes Select from the bar at the live edge")
        let bar = model.transportBarActions
        #expect(bar.map(\.kind) == [.previousChannel, .nextChannel], "no Record without recordings; Back stops")
        #expect(bar[0].title == "Channel down")
        #expect(bar[0].subtitle == "2.1 Cedar One")
        #expect(bar[0].accessibilityLabel == "Channel down, 2.1 Cedar One")
        #expect(bar[1].accessibilityLabel == "Channel up, 5.2 Northline Extra")
        let tiles = model.channelsPanelActions()
        #expect(tiles.map(\.kind) == [.goToChannel], "no Last channel before a switch")
        #expect(tiles[0].subtitle == "By its number")
        #expect(tiles[0].accessibilityLabel == "Go to channel")
        model.dispose()
    }

    @Test func lastChannelComesWithTheFirstSwitchAndSwapsBack() async throws {
        let (model, controller, _) = try await fixture()
        model.perform(.nextChannel)
        let last = try #require(model.channelsPanelActions().first)
        #expect(last.kind == .lastChannel)
        #expect(last.accessibilityLabel == "Last channel, 5.1 Northline")
        #expect(last.subtitle == "5.1 Northline", "no listings, so no programme")
        #expect(!model.transportBarActions.contains { $0.kind == .lastChannel }, "it leads Channels, not the bar")
        model.perform(.lastChannel)
        #expect(controller.starts.last == "live-2")
        #expect(model.channelsPanelActions().first?.accessibilityLabel == "Last channel, 5.2 Northline Extra")
        model.dispose()
    }

    @Test func lastChannelHidesWhenItCantBeWatched() async throws {
        let (model, _, repository) = try await fixture()
        model.perform(.nextChannel)
        repository.availableChannels[1].available = false
        #expect(model.channelsPanelActions().first?.kind == .goToChannel)
        model.dispose()
    }

    @Test func goLiveStaysAContextualActionSoSelectTakesIt() async throws {
        let (model, controller, _) = try await fixture()
        controller.state.isAtLiveEdge = false
        #expect(model.contextualActions.map(\.kind) == [.goLive])
        model.perform(.goLive)
        #expect(model.contextualActions.isEmpty)
        model.dispose()
    }

    @Test func oneChannelHasNoChannelButtons() async throws {
        let (model, controller, repository) = try await fixture()
        repository.availableChannels = [repository.availableChannels[1]]
        #expect(model.transportBarActions.isEmpty)
        #expect(model.channelsPanelActions().isEmpty)
        model.pageUp()
        model.openGoToChannel()
        #expect(model.goTo == nil)
        #expect(controller.starts == ["live-2"])
        model.dispose()
    }

    /// The banner names the new channel, as after any change: no note of its own.
    @Test func pageUpAndDownChangeChannel() async throws {
        let (model, controller, _) = try await fixture()
        model.pageUp()
        #expect(controller.starts.last == "live-3")
        model.pageDown()
        model.pageDown()
        #expect(controller.starts.last == "live-1")
        model.dispose()
    }

    @Test func theKeysWaitWhileAPromptAsks() async throws {
        let (model, controller, _) = try await fixture()
        controller.state.askStillWatching = true
        model.pageUp()
        model.openGoToChannel()
        #expect(controller.starts == ["live-2"])
        #expect(model.goTo == nil)
        model.dispose()
    }

    /// With listings and recordings, the bar's first button records the
    /// programme on now, then offers to stop that recording.
    @Test func recordOnTheBarRecordsTheProgrammeOnNowThenOffersStop() async throws {
        FakeLatency.duration = .zero
        let repository = FakeLiveTVRepository(guide: FakeLiveGuide(now: .now))
        repository.offered.recording = true
        repository.offered.canRecord = true
        try await repository.refresh()
        let recordings = LiveRecordingsStore(repository: repository)
        let channel = try #require(repository.channels.first { $0.available && $0.programme(at: .now) != nil })
        let model = TVLivePlayerModel(request: .init(channel: channel), repository: repository, controller: KeysControllerSpy(), recordings: recordings)
        model.start()
        let record = try #require(model.transportBarActions.first)
        #expect(record.kind == .record)
        #expect(record.subtitle == channel.programme(at: .now)?.title)
        #expect(record.accessibilityLabel == "Record this programme")
        model.perform(.record)
        #expect(await eventually { model.transportBarActions.first?.kind == .stopRecording })
        #expect(repository.recordingItems.contains { $0.programmeID == channel.programme(at: .now)?.id && $0.state == .recording })
        model.dispose()
    }

    @Test func goToChannelTunesTheChosenMatchAndBackClosesItFirst() async throws {
        let (model, controller, _) = try await fixture()
        model.perform(.goToChannel)
        let keypad = try #require(model.goTo)
        keypad.press("8")
        #expect(keypad.matches.map(\.id) == ["live-4"])
        keypad.confirm()
        #expect(model.goTo == nil)
        #expect(controller.starts.last == "live-4")
        #expect(model.lastChannel?.id == "live-2")
        model.openGoToChannel()
        #expect(model.back() == .handled)
        #expect(model.goTo == nil, "Back closes the keypad")
        #expect(controller.state.status == .playing, "and the channel keeps playing")
        model.dispose()
    }
}
