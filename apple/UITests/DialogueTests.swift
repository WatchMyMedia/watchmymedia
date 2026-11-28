import XCTest

/// Boost dialogue, Reduce loud sounds, Subtitle size and subtitle timing on
/// the fakes at API level 35 (`-fakesDialogue`): Settings saves each at once
/// and says so in its row, and the player's options keep open while the
/// sound changes and the timing moves the cues. Each step keeps a
/// screenshot in the result bundle, to compare with the designs.
final class DialogueTests: XCTestCase {
    override func setUp() async throws { continueAfterFailure = false }

    @MainActor
    private func launch(_ arguments: [String]) -> XCUIApplication {
        XCUIDevice.shared.orientation = .portrait
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-fakesDialogue"] + arguments
        app.launch()
        return app
    }

    @MainActor
    private func snap(_ name: String) {
        let shot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        shot.name = name
        shot.lifetime = .keepAlways
        add(shot)
    }

    @MainActor
    private func text(_ app: XCUIApplication, _ words: String) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", words)).firstMatch
    }

    @MainActor
    private func becomes(_ element: XCUIElement, _ predicate: String, timeout: TimeInterval = 10) -> Bool {
        let expectation = XCTNSPredicateExpectation(predicate: NSPredicate(format: predicate), object: element)
        return XCTWaiter().wait(for: [expectation], timeout: timeout) == .completed
    }

    /// Scrolls the page up until `element` is on screen.
    @MainActor
    private func scroll(_ app: XCUIApplication, to element: XCUIElement) {
        for _ in 0..<8 where !(element.exists && element.isHittable) {
            app.swipeUp(velocity: .slow)
        }
    }

    @MainActor
    func testSoundAndSubtitleSizeSaveAtOnceInSettings() throws {
        let app = launch(["-fakesAudioLanguages", "en", "-tab", "settings", "-settingsPage", "subtitles"])
        defer { app.terminate() }
        let boost = app.descendants(matching: .any)["settings-sound-dialogueBoost"]
        XCTAssertTrue(boost.waitForExistence(timeout: 20), "Sound follows the audio languages")
        XCTAssertTrue(text(app, "Makes voices clearer over music and effects.").exists)
        XCTAssertTrue(text(app, "While either is on, the server converts the sound.").exists)
        snap("settings-sound")
        boost.buttons["Medium"].tap()
        XCTAssertTrue(text(app, "Saved. Voices are boosted at Medium, on every device.").waitForExistence(timeout: 5))
        snap("settings-sound-saved")

        let size = app.segmentedControls["settings-subtitle-size"]
        scroll(app, to: size)
        XCTAssertTrue(size.isHittable)
        XCTAssertTrue(text(app, "Normal follows your device’s caption size.").exists)
        XCTAssertTrue(text(app, "Subtitles that are part of the picture keep their own size.").exists)
        snap("settings-size-normal")
        size.buttons["Largest"].tap()
        XCTAssertTrue(text(app, "Saved. Subtitles are now Largest, on every device.").waitForExistence(timeout: 5))
        XCTAssertTrue(size.buttons["Largest"].isSelected)
        snap("settings-size-largest")
        size.buttons["Smaller"].tap()
        XCTAssertTrue(text(app, "Saved. Subtitles are now Smaller, on every device.").waitForExistence(timeout: 5))
        snap("settings-size-smaller")
    }

    /// On a wide iPad the page is two columns: Sound under the audio
    /// languages, Subtitle size under the subtitle languages.
    @MainActor
    func testAWideIPadPutsSoundAndSizeInTheirColumns() throws {
        guard UIDevice.current.userInterfaceIdiom == .pad else { throw XCTSkip("iPad only") }
        XCUIDevice.shared.orientation = .landscapeLeft
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-fakesDialogue", "-fakesAudioLanguages", "en", "-tab", "settings", "-settingsPage", "subtitles"]
        app.launch()
        defer {
            app.terminate()
            XCUIDevice.shared.orientation = .portrait
        }
        let boost = app.descendants(matching: .any)["settings-sound-dialogueBoost"]
        XCTAssertTrue(boost.waitForExistence(timeout: 20))
        let size = app.segmentedControls["settings-subtitle-size"]
        XCTAssertTrue(size.waitForExistence(timeout: 5))
        XCTAssertGreaterThan(size.frame.minX, boost.frame.maxX, "Subtitle size is in the right-hand column")
        boost.buttons["Medium"].tap()
        size.buttons["Larger"].tap()
        XCTAssertTrue(text(app, "Saved. Subtitles are now Larger, on every device.").waitForExistence(timeout: 5))
        snap("settings-wide-ipad")
    }

    @MainActor
    func testAnOlderServerShowsNeither() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-fakesRedesign", "-fakesAudioLanguages", "en", "-tab", "settings", "-settingsPage", "subtitles"]
        app.launch()
        defer { app.terminate() }
        XCTAssertTrue(app.buttons["audio-add-language"].waitForExistence(timeout: 20))
        XCTAssertFalse(app.descendants(matching: .any)["settings-sound-dialogueBoost"].exists)
        XCTAssertFalse(app.segmentedControls["settings-subtitle-size"].exists)
        snap("settings-older-server")
    }

    @MainActor
    func testThePlayersSoundChangesWithTheOptionsOpen() throws {
        let app = launch(["-open", "watchmymedia://play/9101", "-playerSubtitle", "s4", "-playerKeepControls", "-playerSheet", "audio"])
        defer { app.terminate() }
        let boost = app.segmentedControls["player-sound-dialogueBoost"]
        XCTAssertTrue(boost.waitForExistence(timeout: 20), "Sound is under the audio tracks")
        _ = becomes(boost, "hittable == true", timeout: 3)
        XCTAssertTrue(text(app, "While either is on, the server converts the sound.").exists)
        snap("player-audio")
        boost.buttons["Medium"].tap()
        let line = app.descendants(matching: .any)["player-sound-dialogueBoost-line"]
        XCTAssertTrue(line.label.contains("Changing the sound"), "the row is busy while the play reloads: \(line.label)")
        snap("player-sound-busy")
        XCTAssertTrue(becomes(line, "label == 'Makes voices clearer over music and effects.'", timeout: 10), "then settles")
        XCTAssertTrue(boost.buttons["Medium"].isSelected)
        XCTAssertTrue(boost.exists, "the options stay open")
        snap("player-sound-medium")
    }

    @MainActor
    func testTheTimingMovesTheCuesAndAnAdminSavesItForEveryone() throws {
        let app = launch(["-open", "watchmymedia://play/9101", "-playerSubtitle", "s4", "-playerKeepControls", "-playerSheet", "subtitles"])
        defer { app.terminate() }
        let later = app.buttons["player-timing-later"]
        XCTAssertTrue(later.waitForExistence(timeout: 20), "Subtitle timing is under the tracks while one is on")
        // The sheet slides in; on iPad the panel beside the picture can report its buttons unhittable while it settles.
        _ = becomes(later, "hittable == true", timeout: 3)
        let value = app.staticTexts["player-timing-value"]
        XCTAssertEqual(value.label, "In step")
        XCTAssertFalse(app.buttons["player-timing-reset"].exists, "Reset is hidden when it would do nothing")
        snap("player-timing-in-step")
        for _ in 0..<4 { later.tap() }
        XCTAssertTrue(becomes(value, "label == '0.4 s later'", timeout: 3))
        XCTAssertTrue(app.buttons["player-timing-reset"].exists)
        XCTAssertTrue(app.buttons["player-timing-everyone"].exists, "Sam is an admin")
        snap("player-timing-later")
        app.buttons["player-timing-everyone"].tap()
        XCTAssertTrue(app.staticTexts["player-timing-saved-everyone"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["player-timing-shared"].exists, "now the timing saved for everyone")
        snap("player-timing-saved-for-everyone")
        let earlier = app.buttons["player-timing-earlier"]
        for _ in 0..<16 { earlier.tap() }
        XCTAssertTrue(becomes(value, "label == '1.2 s earlier'", timeout: 3))
        snap("player-timing-earlier")
        app.buttons["player-timing-reset"].tap()
        XCTAssertTrue(becomes(value, "label == '0.4 s later'", timeout: 3), "Reset goes back to the timing saved for everyone")
        XCTAssertTrue(app.segmentedControls.firstMatch.exists)
    }

    @MainActor
    func testAMembersSharedTimingAndAFailedSave() throws {
        let app = launch(["-fakesMember", "-fakesSaveFails", "timing", "-open", "watchmymedia://play/9101", "-playerSubtitle", "s4",
                          "-playerTimingShared", "600", "-playerKeepControls", "-playerSheet", "subtitles"])
        defer { app.terminate() }
        let value = app.staticTexts["player-timing-value"]
        XCTAssertTrue(value.waitForExistence(timeout: 20))
        XCTAssertEqual(value.label, "0.6 s later")
        XCTAssertTrue(app.staticTexts["player-timing-shared"].exists)
        snap("player-timing-shared")
        app.buttons["player-timing-later"].tap()
        XCTAssertFalse(app.buttons["player-timing-everyone"].exists, "only admins save for everyone")
        XCTAssertTrue(text(app, "Couldn’t save, so this timing is for this play only.").waitForExistence(timeout: 5))
        XCTAssertEqual(value.label, "0.7 s later", "the timing stays for the play")
        snap("player-timing-failed")
    }
}
