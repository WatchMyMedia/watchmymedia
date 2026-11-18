import XCTest

/// Home one row a screen (PLAN-HOME.md §7.4) on the fakes with arranged
/// rows: ▼ from the first row pages to the next row's screen, whose panel
/// follows the focused card; the row that comes back restores its card;
/// Menu returns to the first screen, and so does the last screen's Back to
/// the top. Runs in `make apple-tv-test-sim`.
final class HomeScreensTests: XCTestCase {
    @MainActor private var remote: XCUIRemote { XCUIRemote.shared }

    override func setUp() async throws {
        continueAfterFailure = false
    }

    /// Waits until `element` has focus.
    @MainActor
    private func focused(_ element: XCUIElement, timeout: TimeInterval = 10) -> Bool {
        element.waitForFocus(timeout: timeout)
    }

    /// Waits until `element` is on screen.
    @MainActor
    private func visible(_ element: XCUIElement, timeout: TimeInterval = 10) -> Bool {
        eventually(timeout: timeout) { element.exists && XCUIApplication().windows.firstMatch.frame.contains(element.frame) }
    }

    /// Waits until the panel above the row speaks of `title`.
    @MainActor
    private func panelSays(_ panel: XCUIElement, _ title: String, timeout: TimeInterval = 10) -> Bool {
        panel.waitForLabel(containing: title, timeout: timeout)
    }

    @MainActor
    func testDownPagesToTheNextRowWhosePanelFollowsFocusAndMenuReturnsToTheFirstScreen() {
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-fakesRows"]
        app.launch()
        let play = app.buttons["Play"]
        XCTAssertTrue(play.waitUntilExists(timeout: 15), "Home shows the hero")
        XCTAssertTrue(visible(app.staticTexts["Row 1 of 7"]), "the first screen counts the drawn rows")
        // Every screen is laid out (the screens are a VStack), so the others' panels exist off screen.
        let panel = app.descendants(matching: .any)["homeFocusPanel-recentlyAddedShows"]
        XCTAssertFalse(app.windows.firstMatch.frame.contains(panel.frame), "the first screen has the hero, not a panel")

        // Something must have focus before Down can move it: on CI's slower
        // runner a Down pressed as soon as Play existed was dropped (2026-11-14).
        XCTAssertTrue(app.waitForFocusedButton(timeout: 15) { _ in true }, "the tab bar has focus")
        remote.press(.down)
        XCTAssertTrue(focused(play), "Down from the tab bar lands on Play")
        remote.press(.down)
        // Every screen is laid out, so the show's poster on the next screen matches the title too.
        let continuing = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "S02 E04 · 23 min left")).firstMatch
        XCTAssertTrue(focused(continuing), "Down from Play lands on the first Continue watching card")
        remote.press(.down)
        XCTAssertTrue(visible(panel), "Down from the row pages to the next row's screen")
        XCTAssertTrue(visible(app.staticTexts["Row 2 of 7"]))
        XCTAssertTrue(panelSays(panel, "Recently added · S03 E07 · added today. Harbor Lights"), "the panel is the first card's: \(panel.label)")
        XCTAssertFalse(app.windows.firstMatch.frame.contains(app.staticTexts["Row 1 of 7"].frame), "the first screen has gone")

        remote.press(.right)
        XCTAssertTrue(panelSays(panel, "Recently added · Season 1 · yesterday. The Quiet Meridian"), "▶ moves the panel to the next card: \(panel.label)")

        remote.press(.menu)
        XCTAssertTrue(focused(play), "Menu on a row screen returns to the first screen, on Play")
        XCTAssertTrue(visible(app.staticTexts["Row 1 of 7"]))

        remote.press(.down)
        XCTAssertTrue(focused(continuing))
        remote.press(.down)
        XCTAssertTrue(visible(panel))
        XCTAssertTrue(panelSays(panel, "Season 1 · yesterday. The Quiet Meridian"), "the row that comes back restores the card it had focus on: \(panel.label)")
    }

    /// The last screen's Back to the top: the first screen again, with focus on Play.
    @MainActor
    func testBackToTheTopOnTheLastScreenReturnsToTheFirstOnPlay() {
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-fakesRows"]
        app.launch()
        let play = app.buttons["Play"]
        XCTAssertTrue(play.waitUntilExists(timeout: 15), "Home shows the hero")
        XCTAssertTrue(app.waitForFocusedButton(timeout: 15) { _ in true }, "the tab bar has focus")

        // Down through Play and the seven rows to the last screen's buttons.
        let edit = app.buttons["editRows"]
        let back = app.buttons["homeBackToTop"]
        for _ in 0..<12 where edit.lookedAt?.hasFocus != true && back.lookedAt?.hasFocus != true {
            app.press(.down, thenWaitForFocusToMove: 5)
        }
        XCTAssertTrue(visible(back), "Down reaches the last screen: \(app.focusedButton.label)")
        for _ in 0..<2 where back.lookedAt?.hasFocus != true { app.press(.right, thenWaitForFocusToMove: 3) }
        XCTAssertTrue(focused(back, timeout: 5), "Right from Edit rows reaches Back to the top: \(app.focusedButton.label)")

        remote.press(.select)
        XCTAssertTrue(focused(play), "Back to the top returns to the first screen, on Play: \(app.focusedButton.label)")
        XCTAssertTrue(visible(app.staticTexts["Row 1 of 7"]))
    }
}
