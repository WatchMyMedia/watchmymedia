import Foundation
import Testing
import WMMFakes
import WMMModel
@testable import WMMFeatures

/// The iPhone and iPad Live TV player's controls hide as the film player's
/// do (PLAN-APPLE-REVIEW.md §3.3.1, §5.3), on manual time.
@Suite @MainActor
struct LivePlayerControlsTests {
    @MainActor private struct Harness {
        let time = ManualTime()
        let controller = FakeLivePlaybackController()
        let repository = FakeLiveTVRepository()
        let model: LivePlayerModel

        init() async throws {
            FakeLatency.duration = .zero
            try await repository.refresh()
            let time = time
            model = LivePlayerModel(
                request: .init(channel: repository.channels[0]), repository: repository, controller: controller,
                now: { time.nowMs }, sleep: { try await time.sleep($0) }
            )
            model.start()
            await time.settle()
        }
    }

    @Test func theRuleIsTheFilmPlayersPlayingOrBufferingWithNothingOpen() {
        #expect(LivePlayerModel.autoHides(status: .playing, stillWatching: false, channelsOpen: false, menuOpen: false, pictureInPicture: false))
        #expect(LivePlayerModel.autoHides(status: .buffering, stillWatching: false, channelsOpen: false, menuOpen: false, pictureInPicture: false))
        for status in [LivePlayerStatus.idle, .tuning, .paused, .stopped, .error] {
            #expect(!LivePlayerModel.autoHides(status: status, stillWatching: false, channelsOpen: false, menuOpen: false, pictureInPicture: false))
        }
        #expect(!LivePlayerModel.autoHides(status: .playing, stillWatching: true, channelsOpen: false, menuOpen: false, pictureInPicture: false))
        #expect(!LivePlayerModel.autoHides(status: .playing, stillWatching: false, channelsOpen: true, menuOpen: false, pictureInPicture: false))
        #expect(!LivePlayerModel.autoHides(status: .playing, stillWatching: false, channelsOpen: false, menuOpen: true, pictureInPicture: false))
        #expect(!LivePlayerModel.autoHides(status: .playing, stillWatching: false, channelsOpen: false, menuOpen: false, pictureInPicture: true))
    }

    @Test func controlsHideThreeSecondsAfterTheLastInputWhilePlaying() async throws {
        let h = try await Harness()
        #expect(h.model.controls.hideAtMs == ControlsAutoHide.hideDelayMs)
        await h.time.advance(byMs: 2_000)
        h.model.nextChannel()
        #expect(h.model.controls.hideAtMs == 5_000)
        await h.time.advance(byMs: 2_999)
        #expect(h.model.showsControls)
        await h.time.advance(byMs: 1)
        #expect(!h.model.showsControls)
        h.model.tap()
        #expect(h.model.showsControls)
        h.model.tap()
        #expect(!h.model.showsControls)
    }

    @Test func theyStayWhilePausedAndWhileChannelsOrTheMenuAreOpen() async throws {
        let h = try await Harness()
        h.model.togglePlayPause()
        await h.time.advance(byMs: 10_000)
        #expect(h.model.showsControls)
        #expect(h.model.controls.hideAtMs == nil)
        h.model.togglePlayPause()
        await h.time.settle()
        h.model.toggleChannels()
        await h.time.advance(byMs: 10_000)
        #expect(h.model.showsControls)
        h.model.toggleChannels()
        h.model.qualityMenuOpen = true
        await h.time.advance(byMs: 10_000)
        #expect(h.model.showsControls)
        h.model.qualityMenuOpen = false
        await h.time.advance(byMs: 3_000)
        #expect(!h.model.showsControls)
    }

    @Test func voiceOverHoldsThemUp() async throws {
        let h = try await Harness()
        await h.time.advance(byMs: 3_000)
        #expect(!h.model.showsControls)
        h.model.controls.hold("voiceOver", true)
        #expect(h.model.showsControls)
        await h.time.advance(byMs: 10_000)
        #expect(h.model.showsControls)
        h.model.controls.hold("voiceOver", false)
        await h.time.advance(byMs: 3_000)
        #expect(!h.model.showsControls)
    }

    @Test func aStopBringsThemBackForWatchLive() async throws {
        let h = try await Harness()
        await h.time.advance(byMs: 3_000)
        #expect(!h.model.controls.visible)
        h.controller.showStopped(reason: "pause_expired")
        await h.time.settle()
        #expect(h.model.controls.visible)
        h.model.retry()
        await h.time.settle()
        #expect(h.model.controls.visible)
        await h.time.advance(byMs: 3_000)
        #expect(!h.model.controls.visible)
    }

    @Test func pictureInPictureGetsTheFilmPlayersGraceToStart() async throws {
        let h = try await Harness()
        h.model.enteredBackground(externalPlayback: false, pictureInPictureMayStart: true)
        await h.time.advance(byMs: PlayerChromeModel.pictureInPictureGraceMs - 1)
        #expect(h.model.state.status == .playing)
        await h.time.advance(byMs: 1)
        #expect(h.model.state.status == .stopped)
        h.model.dispose()
    }

    @Test func pictureInPictureStartingInTimeKeepsTheTunerAndHidesTheControls() async throws {
        let h = try await Harness()
        h.model.enteredBackground(externalPlayback: false, pictureInPictureMayStart: true)
        await h.time.advance(byMs: 500)
        h.model.pictureInPictureChanged(active: true, appActive: false)
        await h.time.advance(byMs: PlayerChromeModel.pictureInPictureGraceMs)
        #expect(h.model.state.status == .playing)
        #expect(!h.model.showsControls)
        #expect(h.model.controls.hideAtMs == nil)
        h.model.dispose()
    }
}

/// Keys on a hardware keyboard in the Live TV player (PLAN-APPLE-REVIEW.md §6.3).
@Suite @MainActor
struct LivePlayerKeyTests {
    private func model(onExit: @escaping @MainActor () -> Void = {}) async throws -> (LivePlayerModel, FakeLivePlaybackController, FakeLiveTVRepository) {
        FakeLatency.duration = .zero
        let repository = FakeLiveTVRepository(); try await repository.refresh()
        let controller = FakeLivePlaybackController()
        let model = LivePlayerModel(request: .init(channel: repository.channels[0]), repository: repository, controller: controller, onExit: onExit)
        model.start()
        return (model, controller, repository)
    }

    @Test func spacePausesAndUpAndDownChangeChannel() async throws {
        let (model, _, repository) = try await model()
        #expect(model.handle(.space))
        #expect(model.state.status == .paused)
        #expect(model.handle(.space))
        #expect(model.state.status == .playing)
        #expect(model.handle(.up))
        #expect(model.state.channel?.id == repository.channels[1].id)
        #expect(model.handle(.down))
        #expect(model.state.channel?.id == repository.channels[0].id)
        #expect(!model.handle(.left), "Live TV doesn't seek")
        #expect(!model.handle(.right))
        model.dispose()
    }

    @Test func escapeClosesChannelsFirstThenLeaves() async throws {
        var exits = 0
        let (model, _, _) = try await model(onExit: { exits += 1 })
        model.toggleChannels()
        #expect(model.handle(.escape))
        #expect(!model.channelsOpen)
        #expect(exits == 0)
        #expect(model.handle(.escape))
        #expect(exits == 1)
    }

    @Test func stoppedTakesNoTransportKeysAndSpaceAnswersStillWatching() async throws {
        let (model, controller, _) = try await model()
        controller.showStillWatching()
        #expect(model.handle(.space))
        #expect(!model.state.askStillWatching)
        controller.showStopped(reason: "pause_expired")
        #expect(!model.handle(.space))
        #expect(!model.handle(.up))
        model.dispose()
    }
}
