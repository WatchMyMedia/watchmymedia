import XCTest

/// Subtitle timing and the sound against a real server at API level 35:
/// an admin moves a subtitle later and saves it for everyone, and a second
/// person who isn't an admin then gets it, marked Set for everyone, moves
/// their own and resets back to it. Run as the admin first, then as the
/// other person on a fresh install (`xcrun simctl uninstall` and `simctl
/// keychain <udid> reset` between), with `TEST_RUNNER_WMM_DIALOGUE_SERVER`
/// (an address), `_FILE` (a file with a text subtitle the person's
/// preferences start), `_USER`, `_PASSWORD` and `_ADMIN` (1 for the
/// admin's run). Skipped without a server. Every step keeps a screenshot.
final class DialogueServerTests: XCTestCase {
    private var app: XCUIApplication!

    override func setUp() async throws { continueAfterFailure = false }

    @MainActor
    func testATimingSavedForEveryoneReachesAnotherPerson() throws {
        let env = ProcessInfo.processInfo.environment
        guard let server = env["WMM_DIALOGUE_SERVER"], !server.isEmpty, let file = env["WMM_DIALOGUE_FILE"] else {
            throw XCTSkip("Set WMM_DIALOGUE_SERVER and WMM_DIALOGUE_FILE to run against a server.")
        }
        let admin = env["WMM_DIALOGUE_ADMIN"] == "1"
        XCUIDevice.shared.orientation = .portrait
        app = XCUIApplication()
        app.launch()
        signIn(server: server, user: env["WMM_DIALOGUE_USER"] ?? "viewer", password: env["WMM_DIALOGUE_PASSWORD"] ?? "fixture-viewer-1")
        app.terminate()

        app.launchArguments = ["-open", "watchmymedia://play/\(file)"]
        app.launch()
        openOptions()
        let value = app.staticTexts["player-timing-value"]
        XCTAssertTrue(value.waitForExistence(timeout: 20), "the person's subtitle is on, and its timing under it")
        let later = app.buttons["player-timing-later"]
        XCTAssertTrue(becomes(later, "hittable == true"))
        if admin {
            XCTAssertEqual(value.label, "In step")
            shot("admin in step")
            for _ in 0..<5 { later.tap() }
            XCTAssertTrue(becomes(value, "label == '0.5 s later'"))
            sleep(2)
            shot("admin 0.5 s later, saved")
            let everyone = app.buttons["player-timing-everyone"]
            XCTAssertTrue(everyone.exists, "an admin can save it for everyone")
            everyone.tap()
            XCTAssertTrue(app.staticTexts["player-timing-saved-everyone"].waitForExistence(timeout: 10))
            XCTAssertTrue(app.staticTexts["player-timing-shared"].exists)
            shot("admin saved for everyone")

            // The sound: saved, then the play reloads here, the options open.
            app.segmentedControls.firstMatch.buttons["Audio"].tap()
            let boost = app.segmentedControls["player-sound-dialogueBoost"]
            XCTAssertTrue(boost.waitForExistence(timeout: 5))
            boost.buttons["Medium"].tap()
            let line = app.descendants(matching: .any)["player-sound-dialogueBoost-line"]
            shot("admin sound changing")
            XCTAssertTrue(becomes(line, "label == 'Makes voices clearer over music and effects.'", timeout: 20), "the reload settles: \(line.label)")
            XCTAssertTrue(boost.buttons["Medium"].isSelected)
            shot("admin sound medium")
            // Back to Off, so the person's sound is as it was.
            boost.buttons["Off"].tap()
            XCTAssertTrue(becomes(line, "label == 'Makes voices clearer over music and effects.'", timeout: 20))
        } else {
            XCTAssertEqual(value.label, "0.5 s later", "the admin's timing for everyone")
            XCTAssertTrue(app.staticTexts["player-timing-shared"].exists, "marked as set for everyone")
            XCTAssertFalse(app.buttons["player-timing-everyone"].exists, "only admins save for everyone")
            XCTAssertFalse(app.buttons["player-timing-reset"].exists, "Reset would do nothing")
            shot("viewer shared")
            later.tap()
            XCTAssertTrue(becomes(value, "label == '0.6 s later'"))
            XCTAssertFalse(app.staticTexts["player-timing-shared"].exists)
            sleep(2)
            shot("viewer own 0.6 s later")
            app.buttons["player-timing-reset"].tap()
            XCTAssertTrue(becomes(value, "label == '0.5 s later'"), "Reset goes back to the timing saved for everyone")
            XCTAssertTrue(app.staticTexts["player-timing-shared"].waitForExistence(timeout: 5))
            shot("viewer reset to shared")
        }
    }

    /// The server picker, then the account, on a fresh install.
    @MainActor
    private func signIn(server: String, user: String, password: String) {
        let address = app.textFields.firstMatch
        if !address.waitForExistence(timeout: 15) {
            let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
            for title in ["Allow", "OK"] where springboard.buttons[title].waitForExistence(timeout: 2) { springboard.buttons[title].tap() }
        }
        XCTAssertTrue(address.waitForExistence(timeout: 10), "a fresh install starts at the server picker")
        address.tap()
        address.typeText(server)
        app.buttons["Connect"].firstMatch.tap()
        let name = app.textFields["Username"]
        XCTAssertTrue(name.waitForExistence(timeout: 15))
        name.tap()
        name.typeText(user)
        let secret = app.secureTextFields["Password"]
        secret.tap()
        secret.typeText(password)
        app.buttons["Sign in"].firstMatch.tap()
        XCTAssertTrue(app.navigationBars["Home"].waitForExistence(timeout: 20), "Home shows once signed in")
    }

    /// Shows the controls and opens the options (the iPhone's sheet, on Subtitles).
    @MainActor
    private func openOptions() {
        let more = app.buttons["Audio, subtitles and quality"]
        for _ in 0..<4 where !more.exists {
            let window = app.windows.firstMatch
            window.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.3)).tap()
            _ = more.waitForExistence(timeout: 3)
        }
        XCTAssertTrue(more.exists, "the controls show")
        more.tap()
    }

    @MainActor
    private func becomes(_ element: XCUIElement, _ predicate: String, timeout: TimeInterval = 10) -> Bool {
        let expectation = XCTNSPredicateExpectation(predicate: NSPredicate(format: predicate), object: element)
        return XCTWaiter().wait(for: [expectation], timeout: timeout) == .completed
    }

    @MainActor
    private func shot(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
