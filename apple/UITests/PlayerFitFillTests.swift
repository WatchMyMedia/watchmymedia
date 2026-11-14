import XCTest

/// Fit and fill in both players (PLAN-APPLE-REVIEW.md §6.3,
/// design/mockups/ios/PlayerFitFill.dc.html), on the fakes, on iPhone and
/// iPad: the button beside Close, the pinch, the note, the remembered choice,
/// and that a double tap still seeks. The fakes draw a backdrop rather than
/// video, so how the picture is cropped is checked by hand against a server.
final class PlayerFitFillTests: XCTestCase {
    override func setUp() async throws {
        continueAfterFailure = false
        // An iPad held upright puts the picture at the top with the season under it;
        // these gestures are for the picture filling the screen.
        await MainActor.run { XCUIDevice.shared.orientation = .landscapeLeft }
    }

    override func tearDown() async throws {
        await MainActor.run { XCUIDevice.shared.orientation = .portrait }
    }

    /// The note stays 8 s rather than 1.8 s, so a busy machine's slow queries still see it.
    static let noteMs = 8_000

    /// Starts fitted whatever an earlier run left (`-player.videoFit` is read
    /// before the saved choice), with the controls kept up unless `keepControls` is false.
    @MainActor private func launchFilm(fit: String? = "fit", keepControls: Bool = true) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-open", "watchmymedia://play/9101", "-playerFitNoteMs", "\(Self.noteMs)"]
            + (keepControls ? ["-playerKeepControls"] : [])
            + (fit.map { ["-player.videoFit", $0] } ?? [])
        app.launch()
        return app
    }

    @MainActor
    func testTheButtonFillsSaysSoAndIsRemembered() throws {
        var app = launchFilm()
        let button = app.buttons["player-fit-fill"]
        XCTAssertTrue(button.waitForExistence(timeout: 20))
        XCTAssertEqual(button.label, "Fill the screen")
        XCTAssertFalse(button.isSelected)
        attach(app, "Fit")

        button.tap()
        XCTAssertTrue(waitFor(button, "label == 'Fit the whole picture'"))
        XCTAssertTrue(button.isSelected, "Filled, the button is on")
        let note = app.staticTexts["player-fit-note"]
        XCTAssertTrue(note.waitForExistence(timeout: 5))
        XCTAssertTrue(note.label.hasPrefix("Fill · "), note.label)
        attach(app, "Filled, with the note")
        XCTAssertTrue(waitFor(note, "exists == false", timeout: 15), "The note goes after a moment")
        XCTAssertTrue(app.buttons["player-play-pause"].exists, "The controls stay")
        app.terminate()

        // The next video starts filled.
        app = launchFilm(fit: nil)
        let again = app.buttons["player-fit-fill"]
        XCTAssertTrue(again.waitForExistence(timeout: 20))
        XCTAssertEqual(again.label, "Fit the whole picture")
        again.tap()
        XCTAssertTrue(waitFor(again, "label == 'Fill the screen'"))
        XCTAssertTrue(app.staticTexts["Fit · the whole picture"].waitForExistence(timeout: 5))
        app.terminate()
    }

    @MainActor
    func testPinchingFillsAndFits() throws {
        let app = launchFilm(keepControls: false)
        defer { app.terminate() }
        let playPause = app.buttons["player-play-pause"]
        // The controls may already have hidden (3 s) by the time a busy machine
        // answers the first query; the note below shows the player is up.
        _ = playPause.waitForExistence(timeout: 20)
        // Once the controls hide, the picture takes the gestures.
        XCTAssertTrue(waitFor(playPause, "exists == false", timeout: 15))
        let picture = app.windows.firstMatch

        picture.pinch(withScale: 2.5, velocity: 3)
        let note = app.staticTexts["player-fit-note"]
        XCTAssertTrue(note.waitForExistence(timeout: 5))
        XCTAssertTrue(note.label.hasPrefix("Fill · "), "Pinch out fills: \(note.label)")
        XCTAssertTrue(waitFor(note, "exists == false", timeout: 15))

        picture.pinch(withScale: 0.4, velocity: -3)
        XCTAssertTrue(app.staticTexts["Fit · the whole picture"].waitForExistence(timeout: 5), "Pinch in fits")
    }

    @MainActor
    func testADoubleTapStillSeeks() throws {
        let app = launchFilm()
        defer { app.terminate() }
        let playPause = app.buttons["player-play-pause"]
        XCTAssertTrue(playPause.waitForExistence(timeout: 20))
        XCTAssertTrue(waitFor(playPause, "label == 'Pause'"))
        playPause.tap()
        XCTAssertTrue(waitFor(playPause, "label == 'Play'"))
        let scrubber = app.otherElements["Playback position"].firstMatch
        let before = scrubber.value as? String ?? ""
        // On the picture's right, clear of the buttons.
        app.windows.firstMatch.coordinate(withNormalizedOffset: CGVector(dx: 0.85, dy: 0.5)).doubleTap()
        XCTAssertTrue(waitFor(scrubber, "value != '\(before)'"), "A double tap on the right seeks 10 s")
        XCTAssertEqual(app.buttons["player-fit-fill"].label, "Fill the screen", "and leaves the picture fitted")
    }

    @MainActor
    func testTheLiveTVPlayerHasTheSameButton() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-fakesLiveTV", "-open", "watchmymedia://live-tv/live-1", "-playerKeepControls", "-player.videoFit", "fit",
                              "-playerFitNoteMs", "\(Self.noteMs)"]
        app.launch()
        defer { app.terminate() }
        let button = app.buttons["player-fit-fill"]
        XCTAssertTrue(button.waitForExistence(timeout: 20))
        XCTAssertEqual(button.label, "Fill the screen")
        button.tap()
        XCTAssertTrue(waitFor(button, "label == 'Fit the whole picture'"))
        XCTAssertTrue(app.staticTexts["player-fit-note"].waitForExistence(timeout: 5))
        attach(app, "Live TV filled")
        XCTAssertTrue(app.buttons["live-play-pause"].exists)
        button.tap()
        XCTAssertTrue(waitFor(button, "label == 'Fill the screen'"))
    }

    @MainActor private func attach(_ app: XCUIApplication, _ name: String) {
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = name
        shot.lifetime = .keepAlways
        add(shot)
    }

    private func waitFor(_ element: XCUIElement, _ format: String, timeout: TimeInterval = 8) -> Bool {
        let done = expectation(for: NSPredicate(format: format), evaluatedWith: element)
        return XCTWaiter().wait(for: [done], timeout: timeout) == .completed
    }
}
