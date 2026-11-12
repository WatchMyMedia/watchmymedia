import XCTest

/// The approved completion boards, entirely on fictional in-process fakes.
final class FirstRunAndSubtitleTests: XCTestCase {
    override func setUp() async throws { continueAfterFailure = false }

    @MainActor
    private func launch(_ extra: [String]) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-fakes"] + extra
        app.launch()
        return app
    }

    @MainActor
    func testEmptyHomeShowsSetupOnlyToAdmins() {
        let app = launch(["-fakesFirstRun", "noLibraries"])
        XCTAssertTrue(app.staticTexts["This server has no movies or TV yet."].waitForExistence(timeout: 20))
        XCTAssertTrue(app.buttons["homeOpenWeb"].exists)
        XCTAssertTrue(app.buttons["homeCheckAgain"].exists)
        app.terminate()
        app.launchArguments = ["-fakesMember", "-fakesFirstRun", "noLibraries"]
        app.launch()
        defer { app.terminate() }
        XCTAssertTrue(app.staticTexts["Ask the person who runs it to add a library."].waitForExistence(timeout: 20))
        XCTAssertFalse(app.buttons["homeOpenWeb"].exists)
    }

    @MainActor
    func testScanningAndFailedHomeExplainDifferentNextSteps() {
        let app = launch(["-fakesFirstRun", "scanning"])
        XCTAssertTrue(app.staticTexts["Finding your files…"].waitForExistence(timeout: 20))
        XCTAssertFalse(app.buttons["homeOpenWeb"].exists)
        app.terminate()
        app.launchArguments = ["-fakes", "-fakesFirstRun", "failed"]
        app.launch()
        defer { app.terminate() }
        XCTAssertTrue(app.staticTexts["Couldn’t read the library"].waitForExistence(timeout: 20))
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "Permission denied.")).firstMatch.exists)
        XCTAssertTrue(app.buttons["homeOpenWeb"].exists)
    }

    /// Scrolls the page until `element` can be tapped. The application's
    /// frame can cover the whole iPad display after a resize, so each scroll
    /// stays inside the actual, possibly compact window.
    @MainActor
    private func scroll(to element: XCUIElement, in app: XCUIApplication) {
        let window = app.windows.allElementsBoundByIndex.max { $0.frame.width * $0.frame.height < $1.frame.width * $1.frame.height }!
        for _ in 0..<4 where !element.exists || !element.isHittable {
            window.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.8)).press(
                forDuration: 0.1, thenDragTo: window.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.3)))
        }
    }

    @MainActor
    func testSubtitleSettingsSaveModeAndOptInSeparately() {
        let app = launch(["-fakesSubtitlePreferences", "-tab", "settings", "-settingsPage", "subtitles"])
        defer { app.terminate() }
        let on = app.buttons["subtitle-mode-on"]
        XCTAssertTrue(on.waitForExistence(timeout: 20))
        // A server before audio languages keeps three choices and no audio list.
        XCTAssertFalse(app.buttons["subtitle-mode-foreign"].exists)
        XCTAssertFalse(app.buttons["audio-add-language"].exists)
        let automatic = app.switches["subtitle-automatic-download"]
        on.tap()
        let english = app.buttons["subtitle-use-device-language"]
        scroll(to: english, in: app)
        english.tap()
        scroll(to: automatic, in: app)
        XCTAssertTrue(automatic.waitForExistence(timeout: 5))
        let enabledSwitch = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true"), object: automatic)
        XCTAssertEqual(XCTWaiter.wait(for: [enabledSwitch], timeout: 10), .completed, "On with a subtitle language allows it")
        XCTAssertEqual(automatic.value as? String, "0", "choosing On alone never opts into quota use")
        automatic.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.5)).tap()
        let enabled = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value == '1'"), object: automatic)
        XCTAssertEqual(XCTWaiter.wait(for: [enabled], timeout: 10), .completed)
        let shot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        shot.name = "Subtitle settings with automatic download enabled"
        shot.lifetime = .keepAlways
        add(shot)
    }

    /// PLAN-LANGUAGES.md §5.6: the first audio language turns on subtitles
    /// for foreign audio, and the page says so once.
    @MainActor
    func testTheFirstAudioLanguageStartsSubtitlesForForeignAudio() {
        let app = launch(["-fakesAudioLanguages", "-tab", "settings", "-settingsPage", "subtitles"])
        defer { app.terminate() }
        let useDevice = app.buttons["audio-use-device-language"]
        XCTAssertTrue(useDevice.waitForExistence(timeout: 20))
        XCTAssertTrue(app.buttons["subtitle-mode-forced"].isSelected)
        useDevice.tap()
        XCTAssertTrue(app.descendants(matching: .any)["foreign-audio-notice"].waitForExistence(timeout: 10))
        let foreign = app.buttons["subtitle-mode-foreign"]
        XCTAssertTrue(foreign.waitForExistence(timeout: 5))
        XCTAssertTrue(foreign.isSelected)
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'audio-language-'")).firstMatch.exists)
        let shot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        shot.name = "Audio & subtitles after the first audio language"
        shot.lifetime = .keepAlways
        add(shot)
        // Moving and removing go through the language's actions.
        app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'audio-language-'")).firstMatch.tap()
        let remove = app.buttons["Remove"]
        XCTAssertTrue(remove.waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["Move up"].exists, "the only language can't move")
        remove.tap()
        XCTAssertTrue(useDevice.waitForExistence(timeout: 5))
        XCTAssertTrue(foreign.isSelected, "removing the language keeps the choice")
    }

    @MainActor
    func testOlderServerDoesNotOfferSubtitlePreferences() {
        let app = launch(["-tab", "settings"])
        defer { app.terminate() }
        XCTAssertTrue(app.navigationBars["Settings"].waitForExistence(timeout: 20))
        XCTAssertFalse(app.buttons["subtitle-mode-on"].exists)
        XCTAssertFalse(app.buttons["Subtitles"].exists)
    }
}
