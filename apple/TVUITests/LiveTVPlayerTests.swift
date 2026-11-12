import XCTest

/// Live navigation, prompt focus and remote Back ordering on fictional channels.
/// The fake-only panel launch argument renders the same Channels/Quality views as
/// AVKit; native transport and HLS are exercised separately against livetvfixture.
final class LiveTVPlayerTests: XCTestCase {
    @MainActor private var remote: XCUIRemote { XCUIRemote.shared }

    override func setUp() async throws { continueAfterFailure = false }

    @MainActor
    private func launch(_ arguments: [String] = [], live: Bool = true) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-fakes"] + (live ? ["-fakesLiveTV"] : []) + arguments
        app.launch()
        return app
    }
    @MainActor
    private func play(_ arguments: [String] = []) -> XCUIApplication {
        launch(["-open", "watchmymedia://live-tv/live-1"] + arguments)
    }
    @MainActor
    private func focused(_ element: XCUIElement, timeout: TimeInterval = 10) -> Bool {
        XCTWaiter().wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "hasFocus == true"), object: element)], timeout: timeout) == .completed
    }
    @MainActor
    private func label(_ element: XCUIElement, contains text: String, timeout: TimeInterval = 10) -> Bool {
        XCTWaiter().wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "label CONTAINS %@", text), object: element)], timeout: timeout) == .completed
    }

    @MainActor
    func testChannelGuideFocusNamesTheChannelAndBackRestoresItsCard() {
        let app = launch(["-tab", "liveTV"])
        let first = app.buttons["live-channel-live-1"]
        let second = app.buttons["live-channel-live-2"]
        XCTAssertTrue(first.waitForExistence(timeout: 15))
        // As on Home, a directly selected tvOS tab can retain focus in the
        // native tab bar. Move through the control row into its first card.
        for _ in 0..<3 where !first.hasFocus { remote.press(.down) }
        XCTAssertTrue(focused(first), "Down reaches the guide's first playable channel.")
        remote.press(.right)
        XCTAssertTrue(focused(second))
        XCTAssertTrue(label(app.staticTexts["live-selected-channel"], contains: "Northline"))
        remote.press(.select)
        XCTAssertTrue(app.staticTexts["live-player-title"].waitForExistence(timeout: 10))
        XCTAssertTrue(label(app.staticTexts["live-player-title"], contains: "Northline"))
        remote.press(.menu)
        XCTAssertTrue(app.staticTexts["live-tv-title"].waitForExistence(timeout: 10))
        XCTAssertTrue(focused(second), "Back restores the channel that opened the player.")
    }

    @MainActor
    func testStillWatchingTakesFocusAndFirstBackKeepsWatching() {
        let app = play(["-liveStillWatching"])
        let keep = app.buttons["live-keep-watching"]
        XCTAssertTrue(keep.waitForExistence(timeout: 15))
        XCTAssertTrue(focused(keep))
        XCTAssertTrue(app.buttons["live-stop-watching"].exists)
        remote.press(.menu)
        XCTAssertTrue(keep.waitForNonExistence(timeout: 5), "The first Back answers the prompt.")
        XCTAssertTrue(app.staticTexts["live-player-title"].exists, "Playback stays open.")
        remote.press(.menu)
        XCTAssertTrue(app.staticTexts["live-tv-title"].waitForExistence(timeout: 10), "The next Back releases and leaves.")
    }

    @MainActor
    func testStopWatchingIsBesideKeepWatchingAndReturnsToChannels() {
        let app = play(["-liveStillWatching"])
        let keep = app.buttons["live-keep-watching"]
        let stop = app.buttons["live-stop-watching"]
        XCTAssertTrue(keep.waitForExistence(timeout: 15))
        XCTAssertTrue(focused(keep))
        remote.press(.right)
        XCTAssertTrue(focused(stop))
        remote.press(.select)
        XCTAssertTrue(app.staticTexts["live-tv-title"].waitForExistence(timeout: 10))
        XCTAssertFalse(keep.exists)
    }

    @MainActor
    func testBusyStateFocusesRetryAndRetryTunesTheSameChannel() {
        let app = play(["-liveBusy"])
        let retry = app.buttons["live-retry"]
        XCTAssertTrue(app.staticTexts["Can't play this channel"].waitForExistence(timeout: 15))
        XCTAssertTrue(focused(retry))
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "All tuners are in use")).firstMatch.exists)
        XCTAssertTrue(app.buttons["live-back"].exists)
        remote.press(.select)
        XCTAssertTrue(retry.waitForNonExistence(timeout: 5))
        XCTAssertTrue(label(app.staticTexts["live-player-title"], contains: "Cedar One"))
    }

    @MainActor
    func testExpiredPauseExplainsReleaseAndWatchLiveRetunes() {
        let app = play(["-livePauseExpired"])
        let retry = app.buttons["live-retry"]
        XCTAssertTrue(app.staticTexts["Live TV stopped"].waitForExistence(timeout: 15))
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "five minutes paused")).firstMatch.exists)
        XCTAssertTrue(focused(retry))
        XCTAssertEqual(retry.label, "Watch live")
        remote.press(.select)
        XCTAssertTrue(retry.waitForNonExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["live-player-title"].exists)
    }

    @MainActor
    func testChannelsPanelStartsOnCurrentChannelAndBackClosesPanelBeforePlayer() {
        let app = play(["-livePanel", "channels"])
        let panel = app.staticTexts["live-panel-channels"]
        let first = app.buttons["live-channel-option-live-1"]
        let second = app.buttons["live-channel-option-live-2"]
        XCTAssertTrue(panel.waitForExistence(timeout: 15))
        XCTAssertTrue(focused(first), "Channels starts with the channel currently playing.")
        remote.press(.right)
        XCTAssertTrue(focused(second))
        remote.press(.select)
        XCTAssertTrue(label(app.staticTexts["live-player-title"], contains: "Northline"))
        XCTAssertTrue(panel.exists, "Changing a channel keeps the panel available.")
        remote.press(.menu)
        XCTAssertTrue(panel.waitForNonExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["live-player-title"].exists)
        remote.press(.menu)
        XCTAssertTrue(app.staticTexts["live-tv-title"].waitForExistence(timeout: 10))
    }

    @MainActor
    func testTheRecordingWarningTakesNoFocusAndSwipeDownReachesDontRecordIt() {
        let app = play(["-liveTakeover"])
        let banner = app.descendants(matching: .any)["live-recording-takeover"]
        XCTAssertTrue(banner.waitForExistence(timeout: 15))
        XCTAssertTrue(label(banner, contains: "a recording needs this tuner. Watching stops then."))
        XCTAssertTrue(label(banner, contains: "Swipe down · Don’t record it"))
        XCTAssertFalse(app.buttons["live-takeover-cancel"].exists, "The banner has no button to take focus")
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = "tv-dvr-takeover"; attachment.lifetime = .keepAlways; add(attachment)
        app.terminate()
        // Swipe down opens Channels (the fakes' stand-in for AVKit's panel): Don't record it comes first, with focus.
        let panel = play(["-liveTakeover", "-livePanel", "channels"])
        XCTAssertTrue(panel.staticTexts["live-panel-channels"].waitForExistence(timeout: 15))
        XCTAssertTrue(focused(panel.buttons["live-takeover-cancel"]), panel.debugDescription)
    }

    @MainActor
    func testStoppedForARecordingOffersItFromTheStart() {
        let app = play(["-liveTakenOver"])
        XCTAssertTrue(app.staticTexts["Stopped for a recording"].waitForExistence(timeout: 15))
        XCTAssertTrue(app.buttons["live-watch-recording"].exists)
    }

    @MainActor
    func testQualityPanelStartsOnCurrentCapAndAppliesFixedQuality() {
        let app = play(["-livePanel", "quality"])
        XCTAssertTrue(app.staticTexts["live-panel-quality"].waitForExistence(timeout: 15))
        XCTAssertTrue(focused(app.buttons["live-quality-option-1080"]))
        remote.press(.right)
        XCTAssertTrue(focused(app.buttons["live-quality-option-720"]))
        remote.press(.right)
        XCTAssertTrue(focused(app.buttons["live-quality-option-480"]))
        remote.press(.select)
        XCTAssertTrue(label(app.staticTexts["live-player-quality"], contains: "480p"))
        XCTAssertFalse(app.buttons["Automatic"].exists)
        XCTAssertFalse(app.buttons["Original"].exists)
    }

    @MainActor
    func testLiveAutomaticPanelShowsSelectedModeAndObservedOutput() {
        let app = play(["-fakesLiveAutomatic", "-liveAutoLow", "-livePanel", "quality"])
        defer { app.terminate() }
        XCTAssertTrue(app.staticTexts["live-panel-quality"].waitForExistence(timeout: 15))
        XCTAssertTrue(label(app.staticTexts["live-player-quality"], contains: "Auto · 480p"))
        XCTAssertTrue(app.buttons["live-quality-option-auto"].exists)
        XCTAssertTrue(label(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "Now Auto · 480p")).firstMatch, contains: "Now Auto · 480p"))
        remote.press(.right)
        XCTAssertTrue(focused(app.buttons["live-quality-option-1080"]))
        remote.press(.select)
        XCTAssertTrue(label(app.staticTexts["live-player-quality"], contains: "1080p"))
    }

    @MainActor
    func testOlderServerPreservesSettingsAndHasNoLiveNavigation() {
        let app = launch(live: false)
        XCTAssertTrue(app.buttons["Play"].waitForExistence(timeout: 15))
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Settings")).firstMatch.exists)
        XCTAssertFalse(app.buttons["Live TV"].exists)
        XCTAssertFalse(app.buttons["home-live-all-channels"].exists)
    }
}
