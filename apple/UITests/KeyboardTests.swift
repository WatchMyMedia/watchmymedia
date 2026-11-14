import XCTest

/// A hardware keyboard on iPad (PLAN-APPLE-REVIEW.md §6.3): space and the
/// arrows in the players, and ⌘F for Search. On the fakes;
/// iPad only, since an iPhone is rarely used with a keyboard and the keys
/// are the same.
final class KeyboardTests: XCTestCase {
    override func setUp() async throws { continueAfterFailure = false }

    @MainActor private func requirePad() throws {
        guard UIDevice.current.userInterfaceIdiom == .pad else { throw XCTSkip("Keyboard checks run on an iPad simulator.") }
    }

    @MainActor
    func testSpaceAndArrowsDriveTheFilmPlayer() throws {
        try requirePad()
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-open", "watchmymedia://play/9101", "-playerKeepControls"]
        app.launch()
        defer { app.terminate() }
        let playPause = app.buttons["player-play-pause"]
        XCTAssertTrue(playPause.waitForExistence(timeout: 20))
        XCTAssertTrue(waitFor(playPause, "label == 'Pause'"))
        app.typeKey(XCUIKeyboardKey.space.rawValue, modifierFlags: [])
        XCTAssertTrue(waitFor(playPause, "label == 'Play'"), "Space pauses")
        let scrubber = app.otherElements["Playback position"].firstMatch
        let before = scrubber.value as? String
        app.typeKey(XCUIKeyboardKey.rightArrow.rawValue, modifierFlags: [])
        XCTAssertTrue(waitFor(scrubber, "value != %@", before ?? ""), "Right seeks 10 s")
        // Escape (close the picker, then leave) isn't checked here: on the iPadOS 27.2
        // simulator XCUITest's Escape reached neither a key press handler, a cancel
        // action nor a key command, so it needs a real keyboard.
    }

    @MainActor
    func testSpaceAndUpDriveTheLiveTVPlayer() throws {
        try requirePad()
        XCUIDevice.shared.orientation = .landscapeLeft
        defer { XCUIDevice.shared.orientation = .portrait }
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-fakesLiveTV", "-open", "watchmymedia://live-tv/live-1", "-playerKeepControls"]
        app.launch()
        defer { app.terminate() }
        let playPause = app.buttons["live-play-pause"]
        XCTAssertTrue(playPause.waitForExistence(timeout: 20))
        app.typeKey(XCUIKeyboardKey.space.rawValue, modifierFlags: [])
        XCTAssertTrue(waitFor(playPause, "label == 'Play live TV'"), "Space pauses")
        app.typeKey(XCUIKeyboardKey.space.rawValue, modifierFlags: [])
        XCTAssertTrue(waitFor(playPause, "label == 'Pause live TV'"), "Space plays")
        let title = app.staticTexts.matching(NSPredicate(format: "label CONTAINS ' · '")).firstMatch
        XCTAssertTrue(title.waitForExistence(timeout: 5))
        let channel = title.label
        app.typeKey(XCUIKeyboardKey.upArrow.rawValue, modifierFlags: [])
        XCTAssertTrue(waitFor(app.staticTexts.matching(NSPredicate(format: "label CONTAINS ' · '")).firstMatch, "label != %@", channel), "Up goes to the next channel")
    }

    @MainActor
    func testCommandFOpensSearch() throws {
        try requirePad()
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-tab", "movies"]
        app.launch()
        defer { app.terminate() }
        XCTAssertTrue(app.navigationBars["Movies"].waitForExistence(timeout: 20))
        app.typeKey("f", modifierFlags: .command)
        let field = app.searchFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 10), "⌘F opens Search")
        XCTAssertTrue(waitFor(field, "hasKeyboardFocus == true"), "with the cursor in its field")
    }

    private func waitFor(_ element: XCUIElement, _ format: String, _ args: Any...) -> Bool {
        let done = expectation(for: NSPredicate(format: format, argumentArray: args), evaluatedWith: element)
        return XCTWaiter().wait(for: [done], timeout: 8) == .completed
    }
}
