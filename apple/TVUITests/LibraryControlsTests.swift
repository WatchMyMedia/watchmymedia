import XCTest

/// The Movies grid's controls and focus on Apple TV, on the fakes: the four
/// controls on one line with the heading, Down from them into the grid and
/// Up back to the control above, Down again landing on the poster focused
/// last; Unwatched narrowing the grid; only the focused poster carrying
/// words. And Search's recent searches before anything is typed, one move
/// down from the keyboard. Runs in `make apple-tv-test-sim`.
final class LibraryControlsTests: XCTestCase {
    @MainActor private var remote: XCUIRemote { XCUIRemote.shared }

    override func setUp() async throws { continueAfterFailure = false }

    /// A poster's label holds its runtime ("Ninety Fathoms, 2024 · 1 h 52 min").
    private static func isPoster(_ label: String) -> Bool { label.contains(" min") }

    @MainActor
    func testDownFromTheControlsLandsOnThePosterFocusedLast() {
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-tab", "movies"]
        app.launch()
        let sort = app.buttons["Sort by"]
        XCTAssertTrue(sort.waitUntilExists(timeout: 20))
        XCTAssertTrue(app.buttons["library-unwatched"].exists, "Unwatched is a control of its own")
        XCTAssertTrue(app.descendants(matching: .any)["library-more-filters"].exists, "the rest are in More filters")
        XCTAssertTrue(app.waitForFocusedButton(timeout: 15) { _ in true }, "the tab bar has focus")

        // Down from the tab bar to the controls, then into the grid. A focused menu (Sort, Genre)
        // is no focused button to XCUI, so the label is read only where there is one.
        for _ in 0..<3 where !Self.isPoster(app.focusedButton.lookedAt?.label ?? "") { app.press(.down, thenWaitForFocusToMove: 3) }
        XCTAssertTrue(app.waitForFocusedButton { Self.isPoster($0) }, "Down reaches the grid: \(app.focusedButton.label)")
        shot("The grid, the first poster focused with its words under it")

        // Along to the third poster, up to the controls, and down again.
        app.press(.right, thenWaitForFocusToMove: 3)
        app.press(.right, thenWaitForFocusToMove: 3)
        let third = app.focusedButton.label
        XCTAssertTrue(Self.isPoster(third))
        app.press(.up, thenWaitForFocusToMove: 3)
        XCTAssertTrue(eventually(timeout: 5) { !Self.isPoster(app.focusedButton.lookedAt?.label ?? "") }, "Up from the first row reaches a control")
        shot("Up from the grid: a control above")
        app.press(.down, thenWaitForFocusToMove: 3)
        XCTAssertTrue(app.waitForFocusedButton(timeout: 5) { $0 == third }, "Down lands on the poster focused last, \(third), not \(app.focusedButton.label)")
    }

    @MainActor
    func testUnwatchedNarrowsTheGrid() {
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-tab", "movies"]
        app.launch()
        let unwatched = app.buttons["library-unwatched"]
        XCTAssertTrue(unwatched.waitUntilExists(timeout: 20))
        let watched = app.buttons.matching(NSPredicate(format: "label ENDSWITH %@", "Watched")).firstMatch
        XCTAssertTrue(watched.waitUntilExists(timeout: 10), "a watched film is in the grid")
        XCTAssertTrue(app.waitForFocusedButton(timeout: 15) { _ in true })
        app.press(.down, thenWaitForFocusToMove: 3)
        // Along the controls to Unwatched. A focused menu is no focused button to XCUI: find what has focus.
        let focused = app.descendants(matching: .any).matching(NSPredicate(format: "hasFocus == true"))
        for _ in 0..<4 where !unwatched.hasFocus {
            let x = focused.allElementsBoundByIndex.last?.frame.midX ?? 0
            remote.press(x > unwatched.frame.midX ? .left : .right)
            _ = unwatched.waitForFocus(timeout: 2)
        }
        XCTAssertTrue(unwatched.waitForFocus(timeout: 5))
        remote.press(.select)
        XCTAssertTrue(unwatched.wait(until: NSPredicate(format: "isSelected == true"), timeout: 10), "the chip is chosen")
        XCTAssertTrue(watched.waitUntilGone(timeout: 10), "and the watched film has gone")
        shot("Unwatched chosen")
    }

    @MainActor
    func testRecentSearchesAreOneMoveDownFromTheKeyboard() {
        let first = XCUIApplication()
        first.launchArguments = ["-fakes", "-tab", "search", "-search", "harbor"]
        first.launch()
        let found = first.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Harbor Lights")).firstMatch
        XCTAssertTrue(found.waitUntilExists(timeout: 20), "the search is made, so it is remembered")
        first.terminate()

        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-tab", "search"]
        app.launch()
        let recent = app.buttons["search-recent-harbor"]
        XCTAssertTrue(recent.waitUntilExists(timeout: 20), "before anything is typed, the recent search")
        XCTAssertTrue(app.buttons["search-recent-clear"].exists, "with Clear last")
        shot("Search before typing, the recent searches under the keyboard")
        for _ in 0..<4 where !recent.hasFocus { app.press(.down, thenWaitForFocusToMove: 3) }
        XCTAssertTrue(recent.waitForFocus(timeout: 5), "Down from the keyboard reaches it: \(app.focusedButton.label)")
        remote.press(.select)
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Harbor Lights")).firstMatch.waitUntilExists(timeout: 20),
                      "Select searches for it")
        shot("Search results: movies and shows in one row")

        // Clear forgets them.
        app.terminate()
        app.launch()
        let clear = app.buttons["search-recent-clear"]
        XCTAssertTrue(clear.waitUntilExists(timeout: 20))
        for _ in 0..<4 where !app.buttons["search-recent-harbor"].hasFocus && !clear.hasFocus { app.press(.down, thenWaitForFocusToMove: 3) }
        for _ in 0..<6 where !clear.hasFocus { app.press(.right, thenWaitForFocusToMove: 3) }
        XCTAssertTrue(clear.waitForFocus(timeout: 5))
        remote.press(.select)
        XCTAssertTrue(app.buttons["search-recent-harbor"].waitUntilGone(timeout: 10), "Clear forgets the recent searches")
    }

    @MainActor
    private func shot(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
