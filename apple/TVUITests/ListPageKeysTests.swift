import XCTest

/// Page Up and Page Down on a list's page (TvListPage.dc.html), as on the
/// library grids (TvLibraryAZ.dc.html): two rows in the same column. On the
/// fakes' Top rated science fiction, twelve titles in two rows of seven.
/// Runs in `make apple-tv-test-sim`.
final class ListPageKeysTests: XCTestCase {
    @MainActor private var remote: XCUIRemote { XCUIRemote.shared }

    override func setUp() async throws { continueAfterFailure = false }

    @MainActor
    private func focus(_ app: XCUIApplication, timeout: TimeInterval = 10, where test: (String) -> Bool) -> Bool {
        app.waitForFocusedButton(timeout: timeout, where: test)
    }

    @MainActor
    func testPageDownAndUpMoveBetweenTheRows() {
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-fakesRows", "-open", "watchmymedia://lists/9001"]
        app.launch()
        XCTAssertTrue(app.staticTexts["Top rated science fiction"].waitUntilExists(timeout: 20))
        // Down into the grid, then left to the first column.
        let focused = app.buttons.matching(NSPredicate(format: "hasFocus == true")).firstMatch
        for _ in 0..<5 where !(focused.exists && focused.frame.minY > 200) {
            remote.press(.down)
            _ = focus(app, timeout: 1) { _ in false }
        }
        XCTAssertTrue(focused.frame.minY > 200, "Down reaches the grid")
        for _ in 0..<7 {
            remote.press(.left)
        }
        _ = focus(app, timeout: 1) { _ in false }
        let start = app.buttons.matching(NSPredicate(format: "hasFocus == true")).firstMatch
        let label = start.label
        let x = start.frame.midX
        let y = start.frame.midY
        remote.press(.pageDown)
        XCTAssertTrue(focus(app) { $0 != label }, "Page Down moves to the second row")
        _ = XCTWaiter().wait(for: [XCTestExpectation(description: "settle")], timeout: 1.5)
        let after = app.buttons.matching(NSPredicate(format: "hasFocus == true")).firstMatch
        XCTAssertGreaterThan(after.frame.midY, y + 100, "on the next row")
        XCTAssertEqual(after.frame.midX, x, accuracy: 30, "in the same column")
        remote.press(.pageUp)
        XCTAssertTrue(focus(app) { $0 == label }, "Page Up comes back")
    }
}
