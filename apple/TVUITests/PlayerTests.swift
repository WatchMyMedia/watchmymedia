import XCTest

/// The player on the fakes, driven with the Siri Remote: the Next Episode
/// card, Still watching, and the stopped and can't-play states, each with
/// Menu (PLAN-IOS.md §11.2). The fake episode's credits start at 49:30. XCUI
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

    /// The card's countdown button, "Next episode in 7".
    @MainActor
    private func nextEpisode(_ app: XCUIApplication) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "Next episode in")).firstMatch
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
    func testStillWatchingTakesFocusAndMenuKeepsWatching() {
        let app = play(["-playerStillWatching"])
        let keep = app.buttons["Keep watching"]
        XCTAssertTrue(keep.waitUntilExists(timeout: 15), "the prompt shows")
        XCTAssertTrue(focused(keep), "with focus on Keep watching")
        XCTAssertTrue(app.buttons["Stop"].exists)
        remote.press(.menu)
        XCTAssertTrue(keep.waitUntilGone(timeout: 5), "any press answers it, Menu too")
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
        let retry = app.buttons["Try again"]
        XCTAssertTrue(focused(retry), "with focus on Try again")
        XCTAssertTrue(app.buttons["Back"].exists)
        remote.press(.select)
        XCTAssertTrue(app.staticTexts["Can’t play this"].waitUntilGone(timeout: 10), "Try again plays")
    }
}
