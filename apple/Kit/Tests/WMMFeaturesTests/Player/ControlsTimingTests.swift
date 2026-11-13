import Foundation
import Testing
@testable import WMMFeatures
import WMMModel

/// When the controls show and hide (android/.../ControlsStateTest.kt), and taps on the video.
@MainActor
@Suite struct ControlsTimingTests {
    @Test func controlsHideThreeSecondsAfterTheLastTouchWhilePlaying() async {
        let h = PlayerHarness()
        await h.time.settle()
        #expect(h.model.hideAtMs == 3_000)
        await h.time.advance(byMs: 2_999)
        #expect(h.model.controlsVisible)
        await h.time.advance(byMs: 1)
        #expect(!h.model.controlsVisible)
    }

    @Test func aTouchPushesTheHideBack() async {
        let h = PlayerHarness()
        await h.time.advance(byMs: 2_000)
        h.model.seek(byMs: 10_000)
        #expect(h.model.hideAtMs == 5_000)
        await h.time.advance(byMs: 2_999)
        #expect(h.model.controlsVisible)
        await h.time.advance(byMs: 1)
        #expect(!h.model.controlsVisible)
    }

    @Test func aLongLoadDoesNotHideThemTheMomentPlaybackStarts() async {
        let h = PlayerHarness(status: .loading)
        await h.time.advance(byMs: 6_000)
        #expect(h.model.controlsVisible)
        #expect(h.model.hideAtMs == nil)
        h.spy.state.status = .playing
        await h.time.settle()
        #expect(h.model.hideAtMs == 9_000)
        await h.time.advance(byMs: 2_999)
        #expect(h.model.controlsVisible)
        await h.time.advance(byMs: 1)
        #expect(!h.model.controlsVisible)
    }

    @Test(arguments: [PlayerStatus.paused, .loading, .ended, .error])
    func theyStayWhileNotPlaying(_ status: PlayerStatus) async {
        let h = PlayerHarness(status: status)
        await h.time.advance(byMs: 10_000)
        #expect(h.model.controlsVisible)
        #expect(h.model.hideAtMs == nil)
    }

    @Test func theyHideByThemselvesOnlyWhilePlayingOrBufferingNotScrubbingWithThePickerClosed() {
        #expect(PlayerChromeModel.autoHides(status: .playing, scrubbing: false, pickerOpen: false))
        #expect(PlayerChromeModel.autoHides(status: .buffering, scrubbing: false, pickerOpen: false))
        #expect(!PlayerChromeModel.autoHides(status: .paused, scrubbing: false, pickerOpen: false))
        #expect(!PlayerChromeModel.autoHides(status: .loading, scrubbing: false, pickerOpen: false))
        #expect(!PlayerChromeModel.autoHides(status: .ended, scrubbing: false, pickerOpen: false))
        #expect(!PlayerChromeModel.autoHides(status: .playing, scrubbing: true, pickerOpen: false))
        #expect(!PlayerChromeModel.autoHides(status: .playing, scrubbing: false, pickerOpen: true))
    }

    @Test func pausingKeepsThemUpAndPlayingAgainHidesThemThreeSecondsLater() async {
        let h = PlayerHarness()
        await h.time.advance(byMs: 1_000)
        h.model.togglePlayPause()
        await h.time.advance(byMs: 10_000)
        #expect(h.model.controlsVisible)
        h.model.togglePlayPause()
        await h.time.settle()
        #expect(h.model.hideAtMs == 14_000)
        await h.time.advance(byMs: 3_000)
        #expect(!h.model.controlsVisible)
    }

    @Test func withVoiceOverTheyStayUpAndShowAgain() async {
        let h = PlayerHarness()
        await h.time.advance(byMs: 3_000)
        #expect(!h.model.controlsVisible)
        h.model.keepsControlsUp = true
        #expect(h.model.controlsVisible)
        await h.time.advance(byMs: 10_000)
        #expect(h.model.controlsVisible)
        h.model.keepsControlsUp = false
        await h.time.advance(byMs: 3_000)
        #expect(!h.model.controlsVisible)
    }

    @Test func aTapTogglesThemOnceItIsClearNoSecondTapFollows() async {
        let h = PlayerHarness(status: .paused)
        h.model.tap(x: 450, width: 900)
        #expect(h.model.controlsVisible)
        await h.time.advance(byMs: PlayerChromeModel.doubleTapMs)
        #expect(!h.model.controlsVisible)
        h.model.tap(x: 100, width: 900)
        await h.time.advance(byMs: PlayerChromeModel.doubleTapMs)
        #expect(h.model.controlsVisible)
    }

    @Test func controlsShownByATapHideThreeSecondsLater() async {
        let h = PlayerHarness()
        await h.time.advance(byMs: 3_000)
        #expect(!h.model.controlsVisible)
        // They show at once, so the three seconds run from the tap.
        h.model.tap(x: 450, width: 900)
        #expect(h.model.controlsVisible)
        await h.time.advance(byMs: 2_999)
        #expect(h.model.controlsVisible)
        await h.time.advance(byMs: 1)
        #expect(!h.model.controlsVisible)
    }

    @Test func everyTouchTellsTheController() async {
        let h = PlayerHarness()
        h.model.tap(x: 450, width: 900)
        h.model.seek(byMs: -10_000)
        h.model.scrub(x: 10, width: 100)
        h.model.endScrub()
        h.model.togglePlayPause()
        #expect(h.spy.inputs >= 5)
    }
}

/// Double-tap seeking on the model: the run, its label and its end.
@MainActor
@Suite struct DoubleTapOnTheVideoTests {
    @Test func aDoubleTapOnTheRightSeeksForwardAndLeavesTheControlsAlone() async {
        let h = PlayerHarness(status: .paused)
        h.model.tap(x: 880, width: 915)
        await h.time.advance(byMs: 150)
        h.model.tap(x: 880, width: 915)
        #expect(h.spy.calls.last == "seekBy 10000")
        #expect(h.model.ripple?.label == "+10 s")
        await h.time.advance(byMs: 1_000)
        #expect(h.model.controlsVisible)
    }

    @Test func furtherTapsOnThatSideAccumulate() async {
        let h = PlayerHarness(status: .paused)
        h.model.tap(x: 880, width: 915)
        await h.time.advance(byMs: 100)
        h.model.tap(x: 870, width: 915)
        await h.time.advance(byMs: 400)
        h.model.tap(x: 860, width: 915)
        await h.time.advance(byMs: 500)
        h.model.tap(x: 860, width: 915)
        #expect(h.spy.calls.filter { $0 == "seekBy 10000" }.count == 3)
        #expect(h.spy.state.positionMs == 80_000)
        #expect(h.model.ripple?.label == "+30 s")
        // Taps that continue a run seek at once and never toggle the controls.
        await h.time.advance(byMs: 300)
        #expect(h.model.controlsVisible)
    }

    @Test func goingBackTheLabelCountsDown() async {
        let h = PlayerHarness(status: .paused)
        h.model.tap(x: 40, width: 915)
        h.model.tap(x: 60, width: 915)
        await h.time.advance(byMs: 300)
        h.model.tap(x: 50, width: 915)
        #expect(h.model.ripple?.label == "−20 s")
        #expect(h.spy.state.positionMs == 30_000)
    }

    @Test func theRunEndsAfterTheWindowAndTheLabelGoes() async {
        let h = PlayerHarness(status: .paused)
        h.model.tap(x: 880, width: 915)
        h.model.tap(x: 880, width: 915)
        await h.time.advance(byMs: PlayerChromeModel.rippleMs)
        #expect(h.model.ripple == nil)
        // A tap after the window is an ordinary tap: it toggles, it does not seek.
        h.model.tap(x: 880, width: 915)
        await h.time.advance(byMs: PlayerChromeModel.doubleTapMs)
        #expect(!h.model.controlsVisible)
        #expect(h.spy.calls.filter { $0.hasPrefix("seekBy") }.count == 1)
    }

    @Test func aDoubleTapInTheMiddleTogglesOnce() async {
        let h = PlayerHarness(status: .paused)
        h.model.tap(x: 450, width: 915)
        h.model.tap(x: 460, width: 915)
        #expect(!h.model.controlsVisible)
        await h.time.advance(byMs: 1_000)
        #expect(!h.model.controlsVisible)
        #expect(!h.spy.calls.contains { $0.hasPrefix("seekBy") })
    }

    @Test func twoTapsTooFarApartAreTwoTaps() async {
        let h = PlayerHarness(status: .paused)
        h.model.tap(x: 880, width: 915)
        await h.time.advance(byMs: PlayerChromeModel.doubleTapMs + 1)
        #expect(!h.model.controlsVisible)
        h.model.tap(x: 880, width: 915)
        await h.time.advance(byMs: PlayerChromeModel.doubleTapMs)
        #expect(h.model.controlsVisible)
        #expect(!h.spy.calls.contains { $0.hasPrefix("seekBy") })
    }
}

/// A tap on hidden controls shows them at once (PLAN-APPLE-REVIEW.md §6.3):
/// only the hide waits to see whether a second tap follows.
@MainActor
@Suite struct ImmediateTapTests {
    private func hidden(_ status: PlayerStatus = .paused) async -> PlayerHarness {
        let h = PlayerHarness(status: status)
        h.model.tap(x: 450, width: 900)
        await h.time.advance(byMs: PlayerChromeModel.doubleTapMs)
        #expect(!h.model.controlsVisible)
        return h
    }

    @Test func aTapShowsHiddenControlsWithoutWaiting() async {
        let h = await hidden()
        h.model.tap(x: 450, width: 900)
        #expect(h.model.controlsVisible, "no 300 ms wait")
        await h.time.advance(byMs: 1_000)
        #expect(h.model.controlsVisible)
    }

    @Test func aTapStillWaitsBeforeHidingShownControls() async {
        let h = PlayerHarness(status: .paused)
        h.model.tap(x: 450, width: 900)
        #expect(h.model.controlsVisible)
        await h.time.advance(byMs: PlayerChromeModel.doubleTapMs - 1)
        #expect(h.model.controlsVisible)
        await h.time.advance(byMs: 1)
        #expect(!h.model.controlsVisible)
    }

    @Test func aDoubleTapOnASideStillSeeksAndKeepsTheControlsItShowed() async {
        let h = await hidden()
        h.model.tap(x: 880, width: 915)
        #expect(h.model.controlsVisible)
        await h.time.advance(byMs: 120)
        h.model.tap(x: 880, width: 915)
        #expect(h.spy.calls.last == "seekBy 10000")
        #expect(h.model.ripple?.label == "+10 s")
        #expect(h.model.controlsVisible)
        // A third tap carries the run on, without hiding anything.
        await h.time.advance(byMs: 200)
        h.model.tap(x: 880, width: 915)
        #expect(h.model.ripple?.label == "+20 s")
        await h.time.advance(byMs: PlayerChromeModel.doubleTapMs)
        #expect(h.model.controlsVisible)
    }

    @Test func aDoubleTapInTheMiddleOfHiddenControlsShowsThemOnce() async {
        let h = await hidden()
        h.model.tap(x: 450, width: 915)
        h.model.tap(x: 460, width: 915)
        #expect(h.model.controlsVisible)
        await h.time.advance(byMs: 1_000)
        #expect(h.model.controlsVisible)
        #expect(!h.spy.calls.contains { $0.hasPrefix("seekBy") })
    }
}
