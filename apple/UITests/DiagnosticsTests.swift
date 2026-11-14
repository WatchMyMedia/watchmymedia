import XCTest

/// Settings › About › Diagnostics (PLAN-APPLE-REVIEW.md §6.4,
/// design/mockups/ios/Diagnostics.dc.html), on the fakes, on iPhone and
/// iPad: the Help row with its count, the page's groups, and Copy turning
/// into Copied with a Copied note. The page must not show the fake server's
/// name or address, even when the log holds them (`-diagnosticsLog`).
final class DiagnosticsTests: XCTestCase {
    override func setUp() async throws { continueAfterFailure = false }

    @MainActor
    func testAboutOpensDiagnosticsAndCopyTurnsIntoCopied() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-tab", "settings", "-settingsPage", "about", "-diagnosticsReports", "-diagnosticsLog"]
        app.launch()
        defer { app.terminate() }

        let row = app.buttons["settings-diagnostics"]
        XCTAssertTrue(row.waitForExistence(timeout: 20))
        XCTAssertTrue(row.label.contains("Diagnostics"))
        XCTAssertTrue(waitFor(row, "label CONTAINS '3 reports'"), row.label)
        XCTAssertTrue(app.staticTexts["Shows what the app can copy for a bug report. Nothing is sent."].exists)
        attach(app, "About")

        row.tap()
        let copy = app.buttons["diagnostics-copy"]
        XCTAssertTrue(copy.waitForExistence(timeout: 10))
        XCTAssertTrue(waitFor(copy, "isEnabled == true"))
        XCTAssertEqual(copy.label, "Copy diagnostics")
        XCTAssertTrue(app.staticTexts["APP AND SERVER"].exists)
        XCTAssertTrue(app.staticTexts["REPORTS FROM IOS"].exists)
        XCTAssertTrue(app.staticTexts["Hang"].exists)
        XCTAssertTrue(app.staticTexts["Exits in the background"].exists)
        let log = app.staticTexts["diagnostics-log"]
        XCTAssertTrue(log.exists)
        // No address or token anywhere on screen; the server's name nowhere in the
        // log (on iPad the Settings sidebar beside the page names it, as it should).
        for hidden in ["192.168.1.20", "27587", "abc123secret456def789"] {
            XCTAssertEqual(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", hidden)).count, 0, "\(hidden) shows on screen")
        }
        XCTAssertFalse(log.label.contains("Living room server"), "the server's name is in the log")
        XCTAssertTrue(log.label.contains("could not reach <name> at <address>"), "the sample line, redacted: \(log.label.prefix(400))")
        attach(app, "Diagnostics")

        copy.tap()
        XCTAssertTrue(waitFor(copy, "label == 'Copied'"))
        XCTAssertTrue(app.otherElements["diagnostics-copied"].waitForExistence(timeout: 2) || app.staticTexts["Copied"].exists)
        attach(app, "Copied")
        XCTAssertTrue(app.pasteboardHasText(), "Copy put text on the clipboard")
    }

    @MainActor
    func testANewInstallSaysItHasNoReportsYet() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-tab", "settings", "-settingsPage", "about", "-diagnosticsNoReports"]
        app.launch()
        defer { app.terminate() }
        let row = app.buttons["settings-diagnostics"]
        XCTAssertTrue(row.waitForExistence(timeout: 20))
        XCTAssertFalse(row.label.contains("report"), row.label)
        row.tap()
        XCTAssertTrue(app.staticTexts["No reports yet"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["iOS hands reports to the app about once a day, so a new install has none."].exists)
    }

    @MainActor private func attach(_ app: XCUIApplication, _ name: String) {
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = name
        shot.lifetime = .keepAlways
        add(shot)
    }

    private func waitFor(_ element: XCUIElement, _ format: String) -> Bool {
        let done = expectation(for: NSPredicate(format: format), evaluatedWith: element)
        return XCTWaiter().wait(for: [done], timeout: 8) == .completed
    }
}

private extension XCUIApplication {
    /// Whether the clipboard holds text, asked without reading it (reading
    /// another app's copy would ask the person first).
    @MainActor func pasteboardHasText() -> Bool { UIPasteboard.general.hasStrings }
}
