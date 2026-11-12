import XCTest

final class FirstRunAndSubtitleTests: XCTestCase {
    override func setUp() async throws { continueAfterFailure = false }

    @MainActor
    func testAnEmptyServerShowsTheAddressToAnAdmin() {
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-fakesFirstRun", "noLibraries"]
        app.launch()
        defer { app.terminate() }
        XCTAssertTrue(app.staticTexts["This server has no movies or TV yet."].waitForExistence(timeout: 20))
        XCTAssertTrue(app.staticTexts["192.168.1.20:27587"].exists)
        XCTAssertTrue(app.buttons["homeCheckAgain"].exists)
    }

    @MainActor
    func testSubtitleSettingsExposeModeAndDisabledAutomaticDownload() {
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-fakesSubtitlePreferences", "-tab", "settings", "-subtitlesSettings"]
        app.launch()
        defer { app.terminate() }
        let choice = app.buttons["subtitle-choice"]
        XCTAssertTrue(choice.waitForExistence(timeout: 20))
        XCTAssertTrue(app.buttons["Done"].exists)
        // A server before audio languages: no audio list.
        XCTAssertFalse(app.buttons["audio-add-language"].exists)
        let automatic = app.descendants(matching: .any).matching(identifier: "subtitle-automatic-download").firstMatch
        XCTAssertTrue(automatic.waitForExistence(timeout: 5))
        XCTAssertFalse(automatic.isEnabled)
    }

    /// PLAN-LANGUAGES.md §5.6 on Apple TV: the audio languages, and the four
    /// ways to start subtitles in a menu.
    @MainActor
    func testAudioLanguagesAndTheSubtitleChoiceMenu() {
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-fakesAudioLanguages", "es,en", "-tab", "settings", "-subtitlesSettings"]
        app.launch()
        defer { app.terminate() }
        let choice = app.buttons["subtitle-choice"]
        XCTAssertTrue(choice.waitForExistence(timeout: 20))
        XCTAssertTrue(app.descendants(matching: .any)["audio-language-es"].exists)
        XCTAssertTrue(app.staticTexts["Forced only when you follow the audio. Full subtitles when it isn’t in your audio languages."].exists)
        let remote = XCUIRemote.shared
        // A Menu's focus is on an element inside it, so focus is found by where it is.
        func focused(on element: XCUIElement) -> Bool {
            if element.hasFocus { return true }
            let focus = app.descendants(matching: .any).element(matching: NSPredicate(format: "hasFocus == true"))
            return focus.exists && element.frame.contains(CGPoint(x: focus.frame.midX, y: focus.frame.midY))
        }
        for _ in 0..<6 where !focused(on: choice) { remote.press(.right) }
        XCTAssertTrue(focused(on: choice), "Right reaches the subtitle choice")
        remote.press(.select)
        // The menu's items are cells; each choice's identifier is on the view inside its cell.
        let on = app.descendants(matching: .any)["subtitle-mode-on"]
        XCTAssertTrue(on.waitForExistence(timeout: 5))
        for _ in 0..<5 where !focused(on: on) { remote.press(.down) }
        XCTAssertTrue(focused(on: on))
        remote.press(.select)
        XCTAssertTrue(app.staticTexts["Always start with a subtitle in your first language that has one."].waitForExistence(timeout: 5))
    }
}
