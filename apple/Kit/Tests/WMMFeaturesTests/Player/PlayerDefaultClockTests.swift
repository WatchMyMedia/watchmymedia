import Foundation
import Testing
@testable import WMMFeatures
import WMMModel

/// Exercise the production sleeper, not the manual clock used by timing tests.
/// Keep this in a separate file from the fake-download test: multiple callers
/// of the default initializer exposed Swift's async default-argument crash.
@MainActor
@Suite(.timeLimit(.minutes(1))) struct PlayerDefaultClockTests {
    @Test func theDefaultTimerCompletesASingleTap() async throws {
        let spy = SpyPlaybackController()
        spy.state.status = .paused
        let model = PlayerChromeModel(controller: spy, request: PlayRequest(fileID: 1, title: "Test"), onExit: {})
        model.start()
        defer { model.dispose() }

        model.tap(x: 450, width: 900)
        let clock = ContinuousClock()
        let deadline = clock.now.advanced(by: .seconds(5))
        while model.controlsVisible && clock.now < deadline {
            try await clock.sleep(until: clock.now.advanced(by: .milliseconds(10)))
        }
        #expect(!model.controlsVisible)
    }

    @Test func openingThePickerCancelsTheDefaultHideTimer() async throws {
        let spy = SpyPlaybackController()
        spy.state.status = .playing
        let model = PlayerChromeModel(controller: spy, request: PlayRequest(fileID: 1, title: "Test"), onExit: {})
        model.start()
        defer { model.dispose() }

        let clock = ContinuousClock()
        for _ in 0..<3 {
            // Let the real timer suspend before cancelling it.
            try await clock.sleep(until: clock.now.advanced(by: .milliseconds(20)))
            model.openPicker(.subtitles)
            try await clock.sleep(until: clock.now.advanced(by: .milliseconds(20)))
            #expect(model.controlsVisible)
            #expect(model.picker == .subtitles)
            #expect(model.hideAtMs == nil)
            model.closePicker()
        }
    }
}
