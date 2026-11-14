import XCTest

/// The letter index on the Apple TV grid (TvLibraryAZ.dc.html,
/// PLAN-APPLE-REVIEW.md §6.2), on the fakes' large Movies library (about
/// 1,200 made-up titles, so most letters are beyond the first page): right
/// from the last column enters it at the poster's letter, Down moves through
/// the letters, Select lands on a letter's first title, Menu goes back to
/// the poster, and Page Down moves two rows. Sorted by date added there is
/// no index. Runs in `make apple-tv-test-sim`.
final class LetterIndexTests: XCTestCase {
    @MainActor private var remote: XCUIRemote { XCUIRemote.shared }

    override func setUp() async throws { continueAfterFailure = false }

    @MainActor
    private func launch(sort: String? = "title") -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-fakesLargeLibrary", "-tab", "movies"] + (sort.map { ["-librarySort", $0] } ?? [])
        app.launch()
        XCTAssertTrue(app.staticTexts["Movies"].waitUntilExists(timeout: 20))
        return app
    }

    @MainActor
    private func focusedButton(_ app: XCUIApplication) -> XCUIElement { app.focusedButton }

    /// Waits until `element` has focus.
    @MainActor
    private func focused(_ element: XCUIElement, timeout: TimeInterval = 10) -> Bool {
        element.waitForFocus(timeout: timeout)
    }

    /// Waits until the focused button's label passes `test`.
    @MainActor
    private func focus(_ app: XCUIApplication, timeout: TimeInterval = 10, where test: (String) -> Bool) -> Bool {
        app.waitForFocusedButton(timeout: timeout, where: test)
    }

    /// Presses `button` and waits for focus to move, so the next press
    /// starts from where this one went.
    @MainActor
    private func move(_ app: XCUIApplication, _ button: XCUIRemote.Button) {
        app.press(button, thenWaitForFocusToMove: 2)
    }

    /// A poster's label holds its runtime ("A Lesser Moon, 2026 · 1 h 52 min").
    private static func isPoster(_ label: String) -> Bool { label.contains(" min") }

    /// Down from the tab bar onto the grid's first row.
    @MainActor
    private func focusGrid(_ app: XCUIApplication) {
        for _ in 0..<4 where !Self.isPoster(focusedButton(app).label) { move(app, .down) }
        XCTAssertTrue(focus(app, where: Self.isPoster), "Down from the tab bar reaches the grid")
    }

    @MainActor
    private func shot(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    @MainActor
    func testTheIndexJumpsToALetterAndMenuGoesBack() {
        let app = launch()
        XCTAssertTrue(app.otherElements["letter-index"].waitUntilExists(timeout: 10) || app.buttons["letter-A"].waitUntilExists(timeout: 10))
        XCTAssertFalse(app.buttons["letter-X"].isEnabled, "no title starts with X: dimmed and passed over")
        focusGrid(app)
        shot("Grid sorted by title, the index at the right")
        // Right along the first row; from its last column into the index.
        for _ in 0..<8 where !focusedButton(app).identifier.hasPrefix("letter-") { move(app, .right) }
        let a = app.buttons["letter-A"]
        XCTAssertTrue(focused(a), "the index takes focus at the poster's letter")
        XCTAssertTrue(app.otherElements["letter-bubble"].exists || app.staticTexts["Select or swipe left to go there"].exists, "the bubble says what the letter holds")

        // Down to W, beyond the first page, passing X going on to Y.
        let target = "W"
        for _ in 0..<30 where !app.buttons["letter-\(target)"].hasFocus { move(app, .down) }
        XCTAssertTrue(focused(app.buttons["letter-\(target)"]))
        XCTAssertTrue(app.staticTexts["Select or swipe left to go there"].waitUntilExists(timeout: 5))
        shot("Index focused on W")
        remote.press(.select)
        XCTAssertTrue(focus(app, timeout: 15) { $0.hasPrefix("Wyn") }, "Select lands on W's first title")

        // Along the row and into the index again; Down a letter, then Menu: the poster focus came from.
        var rowEnd = focusedButton(app).label
        for _ in 0..<8 where !focusedButton(app).identifier.hasPrefix("letter-") {
            rowEnd = focusedButton(app).label
            remote.press(.right)
            _ = focus(app, timeout: 2) { $0 != rowEnd }
        }
        XCTAssertTrue(focusedButton(app).identifier.hasPrefix("letter-"), "right from the last column enters the index")
        XCTAssertTrue(focused(app.buttons["letter-W"]), "at the poster's letter")
        remote.press(.up)
        XCTAssertTrue(focused(app.buttons["letter-V"]))
        remote.press(.menu)
        XCTAssertTrue(focus(app, timeout: 15) { $0 == rowEnd }, "Menu goes back to the poster focus came from")
    }

    @MainActor
    func testLeftFromTheIndexLandsOnTheLettersFirstTitle() {
        let app = launch()
        focusGrid(app)
        for _ in 0..<8 where !focusedButton(app).identifier.hasPrefix("letter-") { move(app, .right) }
        XCTAssertTrue(focused(app.buttons["letter-A"]))
        remote.press(.down)
        XCTAssertTrue(focused(app.buttons["letter-B"]))
        remote.press(.down)
        XCTAssertTrue(focused(app.buttons["letter-C"]))
        // Let the grid follow, then swipe left.
        _ = XCTWaiter().wait(for: [XCTestExpectation(description: "settle")], timeout: 1.5)
        remote.press(.left)
        XCTAssertTrue(focus(app, timeout: 15) { $0.hasPrefix("Cold Harbour Road") }, "left lands on C's first title")
    }

    @MainActor
    func testPageDownMovesTwoRowsInTheSameColumn() {
        let app = launch(sort: nil)
        focusGrid(app)
        // Onto the first column, and let focus settle there.
        for _ in 0..<7 { remote.press(.left) }
        _ = XCTWaiter().wait(for: [XCTestExpectation(description: "settle")], timeout: 1.5)
        XCTAssertTrue(focus(app, where: Self.isPoster))
        let before = focusedButton(app)
        let label = before.label
        let x = before.frame.midX
        remote.press(.pageDown)
        XCTAssertTrue(focus(app) { $0 != label && Self.isPoster($0) }, "Page Down moves focus")
        _ = XCTWaiter().wait(for: [XCTestExpectation(description: "settle")], timeout: 1.5)
        let after = focusedButton(app)
        XCTAssertEqual(after.frame.midX, x, accuracy: 30, "the same column")
        shot("Page Down")
        let paged = after.label
        remote.press(.down)
        remote.press(.down)
        XCTAssertTrue(focus(app) { $0 != paged })
        remote.press(.pageUp)
        remote.press(.pageUp)
        XCTAssertTrue(focus(app) { $0 == label }, "two Page Ups go back four rows, to the first row")
    }

    @MainActor
    func testNoIndexWhenSortedByDateAdded() {
        let app = launch(sort: nil)
        focusGrid(app)
        XCTAssertFalse(app.buttons["letter-A"].exists, "no index unless sorted by title")
        shot("Sorted by date added: no index")
    }
}
