import AVFoundation
import Foundation
import Observation
import Testing
import WMMFakes
import WMMModel
@testable import WMMFeatures

@MainActor @Observable
private final class TVLiveControllerSpy: LivePlaybackController {
    var state = LivePlayerState()
    var player: AVPlayer?
    var calls: [String] = []
    func start(_ request: LivePlayRequest) { calls.append("start:\(request.channel.id)"); state = .init(); state.channel = request.channel; state.quality = request.quality; state.status = .playing }
    func play() { calls.append("play"); state.status = .playing }
    func pause() { calls.append("pause"); state.status = .paused; state.isAtLiveEdge = false }
    func togglePlayPause() { calls.append("toggle"); state.status == .paused ? play() : pause() }
    func goLive() { calls.append("live"); state.isAtLiveEdge = true; state.status = .playing }
    func selectQuality(_ quality: LiveQuality) { calls.append("quality:\(quality.rawValue)"); state.quality = quality }
    func noteUserInput() { calls.append("input"); state.askStillWatching = false }
    func answerStillWatching(keepWatching: Bool) { calls.append(keepWatching ? "keep" : "stop-answer"); state.askStillWatching = false; if !keepWatching { stop() } }
    func stop() { calls.append("stop"); state.status = .stopped }
    func release() { calls.append("release"); state.status = .stopped }
}

@Suite @MainActor
struct TVLivePlayerModelTests {
    private func fixture(onExit: (@MainActor () -> Void)? = nil) async throws -> (TVLivePlayerModel, TVLiveControllerSpy, FakeLiveTVRepository) {
        FakeLatency.duration = .zero
        let repository = FakeLiveTVRepository()
        try await repository.refresh()
        let controller = TVLiveControllerSpy()
        let model = TVLivePlayerModel(request: .init(channel: repository.channels[0]), repository: repository, controller: controller, onExit: onExit)
        model.start()
        return (model, controller, repository)
    }

    @Test func theTakeoverWarningNeverBecomesAnActionAndOnlyItsSetterReachesDontRecordIt() async throws {
        let (model, controller, _) = try await fixture()
        let warning = LiveRecordingTakeover(recordingID: "r1", title: "Inspector Vane", at: .now.addingTimeInterval(120))
        controller.state.takeover = warning
        #expect(model.cancellableTakeover == nil, "Someone who didn't set it can't stop it")
        controller.state.takeover?.canCancel = true
        #expect(model.cancellableTakeover?.recordingID == "r1")
        // The banner takes no focus: nothing joins the contextual actions or the transport bar.
        #expect(!model.contextualActions.contains { $0.title.contains("record") })
        #expect(model.transportBarActions.map(\.kind) == [.stop])
        controller.state.askStillWatching = true
        #expect(model.cancellableTakeover == nil, "Not under a prompt")
        model.dispose()
    }
    @Test func channelActionsStaySeparateFromFileTransportAndWrapPlayableChannels() async throws {
        let (model, controller, repository) = try await fixture()
        repository.availableChannels[1].available = false
        #expect(model.contextualActions.map(\.kind) == [.previousChannel, .nextChannel])
        #expect(model.contextualActions.map(\.accessibilityLabel) == ["Previous channel", "Next channel"])
        model.pickQuality(.p480)
        model.perform(.nextChannel)
        #expect(model.state.channel?.id == repository.channels[2].id)
        #expect(model.state.quality == .p480)
        model.perform(.previousChannel); model.perform(.previousChannel)
        #expect(model.state.channel?.id == repository.channels.last?.id)
        #expect(controller.calls.filter { $0.hasPrefix("start:") }.count == 4)
        model.dispose()
    }
    @Test func aSinglePlayableChannelHasNoRedundantChannelActions() async throws {
        let (model, controller, repository) = try await fixture()
        repository.availableChannels = [repository.availableChannels[0]]
        #expect(model.contextualActions.isEmpty)
        #expect(model.transportBarActions.map(\.kind) == [.stop])
        controller.state.status = .paused
        #expect(model.contextualActions.map(\.kind) == [.goLive])
        model.dispose()
    }
    @Test func pauseOrBehindLiveAddsGoLiveAndItReturnsToTheEdge() async throws {
        let (model, controller, _) = try await fixture()
        #expect(model.transportBarActions.map(\.kind) == [.stop])
        controller.state.isAtLiveEdge = false
        #expect(model.contextualActions.map(\.kind) == [.previousChannel, .nextChannel, .goLive])
        #expect(model.transportBarActions.map(\.kind) == [.stop])
        model.perform(.goLive)
        #expect(model.contextualActions.map(\.kind) == [.previousChannel, .nextChannel])
        #expect(model.transportBarActions.map(\.kind) == [.stop])
        controller.state.status = .paused
        #expect(model.contextualActions.map(\.kind) == [.previousChannel, .nextChannel, .goLive])
        #expect(model.transportBarActions.map(\.kind) == [.stop])
        model.dispose()
    }
    @Test func firstBackAnswersStillWatchingAndSecondBackStopsAndExitsOnce() async throws {
        var exits = 0
        let (model, controller, _) = try await fixture(onExit: { exits += 1 })
        controller.state.askStillWatching = true
        #expect(model.overlay == .stillWatching)
        #expect(!model.videoTakesInput)
        model.back()
        #expect(model.overlay == nil)
        #expect(exits == 0)
        #expect(controller.calls.last == "keep")
        model.back(); model.back(); model.dispose()
        #expect(exits == 1)
        #expect(controller.calls.filter { $0 == "release" }.count == 1)
        #expect(model.state.status == .stopped)
    }
    @Test func backWithAReminderBannerClosesItBeforeLeaving() async throws {
        var exits = 0
        let (model, controller, _) = try await fixture(onExit: { exits += 1 })
        #expect(model.back(bannerShowing: true) == .closeBanner)
        #expect(exits == 0)
        #expect(model.state.status == .playing, "the channel plays on under the banner")
        #expect(!controller.calls.contains("release"))
        // Still watching comes first: its prompt hides the banner.
        controller.state.askStillWatching = true
        #expect(model.back(bannerShowing: true) == .handled)
        #expect(controller.calls.last == "keep")
        #expect(exits == 0)
        // With the banner gone, Menu leaves.
        #expect(model.back() == .handled)
        #expect(exits == 1)
        #expect(model.back(bannerShowing: true) == .handled, "a closed player has nothing to hide")
    }

    @Test func playPauseAnswersPromptWithoutTogglingPlayback() async throws {
        let (model, controller, _) = try await fixture()
        controller.state.askStillWatching = true
        model.playPause()
        #expect(!model.state.askStillWatching)
        #expect(model.state.status == .playing)
        #expect(!controller.calls.contains("toggle"))
        model.playPause()
        #expect(model.state.status == .paused)
        model.dispose()
    }
    @Test func bubbledPlayPauseTogglesNativeControllerAndPromptsTakePriority() async throws {
        let (model, controller, _) = try await fixture()
        controller.player = AVPlayer()
        model.playPause()
        #expect(controller.calls.filter { $0 == "toggle" }.count == 1)
        controller.state.askStillWatching = true
        model.playPause()
        #expect(!controller.state.askStillWatching)
        #expect(controller.calls.filter { $0 == "toggle" }.count == 1, "Answering the prompt does not toggle playback.")
        controller.state.status = .stopped
        model.playPause()
        model.dispose()
        model.playPause()
        #expect(controller.calls.filter { $0 == "toggle" }.count == 1)
    }
    @Test func decliningStillWatchingReleasesAndReturnsToChannels() async throws {
        var exits = 0
        let (model, controller, _) = try await fixture(onExit: { exits += 1 })
        controller.state.askStillWatching = true
        model.answerStillWatching(keepWatching: false)
        #expect(model.state.status == .stopped)
        #expect(controller.calls.last == "release")
        #expect(exits == 1)
    }
    @Test func stoppedAndFailedStatesTakePriorityAndSuppressNativeActions() async throws {
        let (model, controller, _) = try await fixture()
        controller.state.askStillWatching = true
        controller.state.status = .stopped; controller.state.message = "The tuner was given back."
        #expect(model.overlay == .stopped("The tuner was given back."))
        #expect(!model.showsVideo && !model.videoTakesInput)
        #expect(model.contextualActions.isEmpty && model.transportBarActions.isEmpty)
        controller.state.status = .error; controller.state.error = "All tuners are in use."
        #expect(model.overlay == .failed("All tuners are in use."))
        let before = controller.calls
        model.perform(.nextChannel); model.perform(.goLive); model.playPause()
        #expect(controller.calls == before)
        model.dispose()
    }
    @Test func retryKeepsTheLatestChannelAndFixedQuality() async throws {
        let (model, controller, repository) = try await fixture()
        model.pickQuality(.p720); model.switchChannel(repository.channels[2])
        controller.state.status = .error; controller.state.error = "No signal."
        model.retry()
        #expect(model.state.status == .playing)
        #expect(model.state.channel?.id == repository.channels[2].id)
        #expect(model.state.quality == .p720)
        model.dispose()
    }
    @Test func qualityPanelKeepsPlayingAndRejectsAChoiceOutsideOfferedCaps() async throws {
        let (model, controller, _) = try await fixture()
        controller.state.qualities = [.p720, .p480, .p360]
        model.pickQuality(.p480)
        #expect(model.state.quality == .p480)
        #expect(model.state.status == .playing)
        model.pickQuality(.p1080)
        #expect(model.state.quality == .p480)
        #expect(!controller.calls.contains("pause"))
        model.dispose()
    }
    @Test func transportVisibilityCountsUserInputOnlyOnceAndDoesNotDismissPrompt() async throws {
        let (model, controller, _) = try await fixture()
        model.transportBarChanged(visible: true); model.transportBarChanged(visible: true)
        #expect(model.transportBarVisible)
        #expect(controller.calls.filter { $0 == "input" }.count == 1)
        model.transportBarChanged(visible: false)
        controller.state.askStillWatching = true
        model.transportBarChanged(visible: true)
        #expect(model.state.askStillWatching)
        model.dispose()
    }
    @Test func homeButtonReleasesAndForegroundWaitsForExplicitRetry() async throws {
        let (model, controller, _) = try await fixture()
        model.enteredBackground()
        #expect(controller.calls.last == "stop")
        model.becameActive()
        #expect(model.state.status == .stopped)
        #expect(controller.calls.filter { $0.hasPrefix("start:") }.count == 1)
        model.retry()
        #expect(model.state.status == .playing)
        model.dispose()
    }
    @Test func pictureInPictureKeepsTunerAndClosingItInBackgroundStops() async throws {
        let (model, controller, _) = try await fixture()
        model.pictureInPicturePossibleChanged(true)
        model.pictureInPictureChanged(active: true, appActive: true)
        model.enteredBackground()
        #expect(model.state.status == .playing)
        model.pictureInPictureChanged(active: false, appActive: false)
        #expect(model.state.status == .stopped)
        #expect(controller.calls.last == "stop")
        model.dispose()
    }
    @Test func failedPictureInPictureStartReleasesRatherThanWaitingInBackground() async throws {
        let (model, controller, _) = try await fixture()
        model.pictureInPicturePossibleChanged(true)
        model.enteredBackground()
        #expect(model.state.status == .playing)
        model.pictureInPictureChanged(active: false, appActive: false)
        #expect(model.state.status == .stopped)
        #expect(controller.calls.last == "stop")
        model.dispose()
    }
    @Test func transportStopReleasesExactlyOnceAndStaleActionsCannotRestart() async throws {
        var exits = 0
        let (model, controller, repository) = try await fixture(onExit: { exits += 1 })
        model.perform(.stop)
        let before = controller.calls
        model.retry(); model.start(); model.perform(.nextChannel)
        model.switchChannel(repository.channels[1]); model.back(); model.dispose()
        #expect(controller.calls == before)
        #expect(exits == 1)
    }
}
