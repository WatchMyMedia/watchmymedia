import XCTest

/// Settings › About › Diagnostics on Apple TV (TvDiagnostics.dc.html,
/// PLAN-APPLE-REVIEW.md §6.4), on the fakes: the Help group's row, the code
/// to scan beside a summary of what it holds (no server name, address or
/// account), Show as text in pages, and Done and Menu. `-diagnostics` opens
/// the code at launch, as Select on the row does. Runs in
/// `make apple-tv-test-sim`.
final class DiagnosticsTests: XCTestCase {
    @MainActor private var remote: XCUIRemote { XCUIRemote.shared }

    override func setUp() async throws { continueAfterFailure = false }

    @MainActor
    private func launch(_ arguments: [String] = []) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-tab", "settings"] + arguments
        app.launch()
        return app
    }

    @MainActor
    private func focused(_ element: XCUIElement, timeout: TimeInterval = 10) -> Bool {
        element.waitForFocus(timeout: timeout)
    }

    @MainActor
    private func shot(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    @MainActor
    func testTheRowSaysNothingIsSent() {
        let app = launch()
        let row = app.buttons["settings-diagnostics"]
        XCTAssertTrue(row.waitUntilExists(timeout: 20))
        XCTAssertTrue(row.label.contains("Diagnostics"))
        XCTAssertTrue(app.staticTexts["Nothing is sent from this Apple TV. No account, server address or titles are included."].exists)
        // Down the right-hand column, past the devices and About, to Diagnostics.
        for _ in 0..<25 where !row.hasFocus {
            remote.press(.down)
            _ = XCTWaiter().wait(for: [XCTestExpectation(description: "move")], timeout: 0.3)
        }
        XCTAssertTrue(focused(row), "Down the right-hand column reaches Diagnostics")
        shot("Diagnostics row focused")
        remote.press(.select)
        XCTAssertTrue(app.buttons["diagnostics-done"].waitUntilExists(timeout: 10), "Select opens the code")
    }

    @MainActor
    func testALongLogIsCutToFitAndShownInPages() {
        let app = launch(["-diagnostics", "-diagnosticsLog"])
        let done = app.buttons["diagnostics-done"]
        XCTAssertTrue(done.waitUntilExists(timeout: 20))
        let summary = app.staticTexts["diagnostics-summary"]
        XCTAssertTrue(summary.label.contains("more log lines in the code"), summary.label)
        XCTAssertFalse(summary.label.contains("192.168.1.20"))
        XCTAssertTrue(app.staticTexts.containing(NSPredicate(format: "label BEGINSWITH %@", "Plain text · ")).firstMatch.exists)
        shot("Diagnostics code, a long log")
        XCTAssertTrue(focused(done), "Done holds the focus")
        remote.press(.right)
        XCTAssertTrue(focused(app.buttons["diagnostics-show-text"]))
        remote.press(.select)
        let line = app.staticTexts["diagnostics-page"]
        XCTAssertTrue(line.waitUntilExists(timeout: 10))
        XCTAssertTrue(line.label.hasPrefix("Page 1 of "), line.label)
        let text = app.staticTexts["diagnostics-text"]
        XCTAssertTrue(text.label.contains("<address>"), "the made-up address is redacted")
        XCTAssertFalse(text.label.contains("192.168.1.20"))
        shot("Diagnostics as text, page 1")
        remote.press(.down)
        XCTAssertTrue(line.wait(until: NSPredicate(format: "label BEGINSWITH %@", "Page 2 of"), timeout: 5), "Down turns the page")
        shot("Diagnostics as text, page 2")
        remote.press(.up)
        XCTAssertTrue(line.wait(until: NSPredicate(format: "label BEGINSWITH %@", "Page 1 of"), timeout: 5), "Up turns it back")
        XCTAssertTrue(app.buttons["diagnostics-text-done"].hasFocus)
        remote.press(.select)
        XCTAssertTrue(app.buttons["settings-diagnostics"].waitUntilExists(timeout: 5), "Done closes it")
        XCTAssertFalse(done.exists)
    }

    @MainActor
    func testTheCodeShowsWhatItHoldsWithoutTheServerOrAccount() {
        let app = launch(["-diagnostics"])
        let done = app.buttons["diagnostics-done"]
        XCTAssertTrue(done.waitUntilExists(timeout: 20))
        XCTAssertTrue(focused(done), "Done holds the focus")
        XCTAssertTrue(app.otherElements["diagnostics-code"].exists || app.images["diagnostics-code"].exists || app.descendants(matching: .any)["diagnostics-code"].exists)
        let summary = app.staticTexts["diagnostics-summary"]
        XCTAssertTrue(summary.exists)
        XCTAssertTrue(summary.label.contains("Server: 0.9.3, API level 6"), summary.label)
        for secret in ["Living room server", "192.168.1.20", "Sam"] {
            XCTAssertFalse(summary.label.contains(secret), "no \(secret) in \(summary.label)")
        }
        XCTAssertTrue(app.staticTexts.containing(NSPredicate(format: "label BEGINSWITH %@", "Plain text · ")).firstMatch.exists)
        shot("Diagnostics code")

        remote.press(.right)
        XCTAssertTrue(focused(app.buttons["diagnostics-show-text"]))
        remote.press(.select)
        let text = app.staticTexts["diagnostics-text"]
        XCTAssertTrue(text.waitUntilExists(timeout: 10), "Show as text")
        XCTAssertTrue(text.label.hasPrefix("WatchMyMedia diagnostics"))
        XCTAssertFalse(text.label.contains("192.168.1.20"))
        XCTAssertTrue(focused(app.buttons["diagnostics-text-done"]))
        shot("Diagnostics as text")
        // Down turns a page when there is more than one.
        let line = app.staticTexts["diagnostics-page"]
        if line.exists, line.label.hasPrefix("Page 1 of") {
            remote.press(.down)
            XCTAssertTrue(line.wait(until: NSPredicate(format: "label BEGINSWITH %@", "Page 2 of"), timeout: 5), "Down turns the page")
            remote.press(.up)
        }
        // Menu goes back to the code, Done closes it.
        remote.press(.menu)
        XCTAssertTrue(done.waitUntilExists(timeout: 5), "Menu goes back to the code")
        XCTAssertTrue(focused(done))
        remote.press(.select)
        XCTAssertTrue(done.waitUntilGone(timeout: 5), "Done closes it")
        XCTAssertTrue(app.buttons["settings-diagnostics"].waitUntilExists(timeout: 5))
    }
}
