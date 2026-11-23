import XCTest

/// The player on the fakes, driven with the Siri Remote: Up next, Still
/// watching, and the stopped and can't-play states, each with Menu, and the
/// info panel's Audio & subtitles and Quality tabs (shown over the video on
/// the fakes, whose empty player has no info panel). The fake episode's credits start at 49:30. XCUI
/// does not see AVKit's contextual actions, so Skip has no test here.
/// Runs in `make apple-tv-test-sim`.
final class PlayerTests: XCTestCase {
    @MainActor private var remote: XCUIRemote { XCUIRemote.shared }

    override func setUp() async throws {
        continueAfterFailure = false
    }

    @MainActor
    private func play(_ arguments: [String] = []) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-open", "watchmymedia://play/9101"] + arguments
        app.launch()
        return app
    }

    /// Waits until `element` has focus.
    @MainActor
    private func focused(_ element: XCUIElement, timeout: TimeInterval = 10) -> Bool {
        element.waitForFocus(timeout: timeout)
    }

    /// Up next's countdown button, "Play in 8".
    @MainActor
    private func nextEpisode(_ app: XCUIApplication) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "Play in")).firstMatch
    }

    @MainActor
    func testTheNextEpisodeCardTakesFocusAndMenuWatchesTheCredits() {
        let app = play(["-playerSeek", "2970000"])
        let card = nextEpisode(app)
        XCTAssertTrue(card.waitUntilExists(timeout: 15), "the card comes with the credits")
        XCTAssertTrue(focused(card), "with focus on its countdown")
        XCTAssertTrue(app.buttons["Watch credits"].exists)
        remote.press(.menu)
        XCTAssertTrue(card.waitUntilGone(timeout: 5), "Menu puts it away, as Watch credits does")
        XCTAssertFalse(app.buttons["Watch credits"].exists)
    }

    @MainActor
    func testWatchCreditsIsBesideTheCountdown() {
        let app = play(["-playerSeek", "2970000"])
        let card = nextEpisode(app)
        XCTAssertTrue(card.waitUntilExists(timeout: 15))
        XCTAssertTrue(focused(card))
        remote.press(.right)
        let credits = app.buttons["Watch credits"]
        XCTAssertTrue(focused(credits, timeout: 5), "Right moves to Watch credits")
        remote.press(.select)
        XCTAssertTrue(card.waitUntilGone(timeout: 5), "which puts the card away")
    }

    @MainActor
    func testStillWatchingIsAnsweredOnlyByAButton() {
        let app = play(["-playerStillWatching"])
        let keep = app.buttons["Keep watching"]
        XCTAssertTrue(keep.waitUntilExists(timeout: 15), "the prompt shows")
        XCTAssertTrue(focused(keep), "with focus on Keep watching")
        let stop = app.buttons["Stop"]
        XCTAssertTrue(stop.exists)
        remote.press(.playPause)
        remote.press(.right)
        XCTAssertTrue(focused(stop, timeout: 5), "moving to Stop is not an answer")
        XCTAssertTrue(keep.exists, "and nor is Play/Pause")
        remote.press(.left)
        XCTAssertTrue(focused(keep, timeout: 5))
        remote.press(.select)
        XCTAssertTrue(keep.waitUntilGone(timeout: 5), "Keep watching answers it")
        XCTAssertTrue(app.staticTexts["Playing"].waitUntilExists(timeout: 10) || app.staticTexts["Paused"].exists, "and the player stays")
    }

    @MainActor
    func testMenuOnStillWatchingStops() {
        let app = play(["-playerStillWatching"])
        let keep = app.buttons["Keep watching"]
        XCTAssertTrue(keep.waitUntilExists(timeout: 15))
        XCTAssertTrue(focused(keep))
        remote.press(.menu)
        XCTAssertTrue(keep.waitUntilGone(timeout: 5))
        XCTAssertTrue(app.staticTexts["Playing"].waitUntilGone(timeout: 10), "Back is Stop: the player closes")
    }

    @MainActor
    func testTheAdminsStopShowsItsMessageAndBackLeaves() {
        let app = play(["-playerStopped"])
        XCTAssertTrue(app.staticTexts["Playback stopped"].waitUntilExists(timeout: 15))
        XCTAssertTrue(app.staticTexts["“Dinner’s ready. Pause it and come down.”"].exists)
        let back = app.buttons["Back"]
        XCTAssertTrue(focused(back), "with focus on Back")
        remote.press(.select)
        XCTAssertTrue(app.staticTexts["Playback stopped"].waitUntilGone(timeout: 10), "Back leaves the player")
    }

    @MainActor
    func testCantPlayOffersTryAgain() {
        let app = play(["-playerError"])
        XCTAssertTrue(app.staticTexts["Can’t play this"].waitUntilExists(timeout: 15))
        XCTAssertFalse(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "ffmpeg")).firstMatch.exists, "the server's own words aren't on the screen")
        let retry = app.buttons["Try again"]
        XCTAssertTrue(focused(retry), "with focus on Try again")
        XCTAssertTrue(app.buttons["Back"].exists)
        remote.press(.select)
        XCTAssertTrue(app.staticTexts["Can’t play this"].waitUntilGone(timeout: 10), "Try again plays")
    }

    @MainActor
    func testCantPlayOffersALowerQualityBesideTryAgain() {
        let app = play(["-playerError"])
        XCTAssertTrue(app.staticTexts["Can’t play this"].waitUntilExists(timeout: 15))
        let retry = app.buttons["Try again"]
        XCTAssertTrue(focused(retry))
        let lower = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Play at ")).firstMatch
        XCTAssertTrue(lower.exists, "the quality below is a button")
        remote.press(.right)
        XCTAssertTrue(focused(lower, timeout: 5))
        remote.press(.select)
        XCTAssertTrue(app.staticTexts["Can’t play this"].waitUntilGone(timeout: 10), "which plays at it")
    }

    @MainActor
    func testAudioAndSubtitlesAreTwoListsInWords() {
        let app = play(["-playerPanel", "tracks"])
        let panel = app.descendants(matching: .any)["player-options-tracks"]
        XCTAssertTrue(panel.waitUntilExists(timeout: 15), "the tab shows")
        XCTAssertTrue(app.staticTexts["5.1 surround"].exists, "sound in words")
        XCTAssertTrue(app.staticTexts["With sounds described"].exists, "subtitles that say the sounds")
        XCTAssertFalse(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "EAC3")).firstMatch.exists, "no codec names")
        let english = app.buttons["player-audio-1"]
        XCTAssertTrue(focused(english), "focus opens on the sound playing")
        remote.press(.right)
        let off = app.buttons["player-subtitle-off"]
        XCTAssertTrue(focused(off, timeout: 5), "Right goes to the other list, on what is chosen")
    }

    @MainActor
    func testQualityIsKeptHereAndSaysWhatIsPlaying() {
        let app = play(["-playerPanel", "quality"])
        let panel = app.descendants(matching: .any)["player-options-quality"]
        XCTAssertTrue(panel.waitUntilExists(timeout: 15), "the tab shows")
        XCTAssertTrue(app.staticTexts["Kept for this Apple TV, not for your other devices"].exists)
        XCTAssertTrue(app.descendants(matching: .any)["player-what-is-playing"].exists, "What is playing is beside the choice")
        XCTAssertFalse(app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "Mbps")).firstMatch.exists, "rows by name, not bitrate")
        XCTAssertTrue(app.buttons["player-quality-auto"].exists || app.buttons["player-quality-original"].exists)
    }
}
