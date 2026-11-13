import XCTest

/// Tapping the tab already shown goes back to its first page (PLAN-APPLE-REVIEW.md §6.3).
final class TabReselectTests: XCTestCase {
    override func setUp() async throws { continueAfterFailure = false }

    @MainActor
    func testTappingTheSelectedTabGoesBackToItsRoot() {
        XCUIDevice.shared.orientation = .portrait
        let app = XCUIApplication()
        // Settings › Downloads keeps the tab bar on iPhone (a show or movie page hides it).
        app.launchArguments = ["-fakes", "-open", "watchmymedia://downloads"]
        app.launch()
        defer { app.terminate() }
        XCTAssertTrue(app.navigationBars["Downloads"].waitForExistence(timeout: 20), "Downloads is open on Settings")
        app.tabBars.buttons["Settings"].firstMatch.tap()
        XCTAssertTrue(app.navigationBars["Settings"].waitForExistence(timeout: 5), "Settings is back at its first page")
        XCTAssertFalse(app.navigationBars["Downloads"].exists)
    }
}
