import XCTest

/// Home one row a screen (PLAN-HOME.md §7.4) on the fakes with arranged
/// rows: ▼ from the first row pages to the next row's screen, whose panel
/// follows the focused card; the row that comes back restores its card;
/// Menu returns to the first screen. Runs in `make apple-tv-test-sim`.
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
    /// Where a row starts (RowStartBest.dc.html): moving back along a row,
    /// the card before the focused one stays whole on screen, so the 2nd card
    /// never sits where the 1st does; Left on the 1st card keeps focus there.
    @MainActor
    func testMovingBackAlongARowKeepsTheCardBeforeInViewAndStopsAtTheFirst() {
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-fakesRows"]
        app.launch()
        XCTAssertTrue(app.buttons["Play"].waitUntilExists(timeout: 15))
        XCTAssertTrue(app.waitForFocusedButton(timeout: 15) { _ in true }, "something has focus")
        let window = app.windows.firstMatch.frame
        let panel = app.descendants(matching: .any)["homeFocusPanel-recentlyAddedShows"]
        // Down to Recently added's screen, however many presses the way there takes: on CI's runner the first Down
        // from the tab bar didn't reach Play (2026-11-18), and the way down is the other test's to check.
        for _ in 0..<6 where !(panel.exists && window.contains(panel.frame)) { app.press(.down, thenWaitForFocusToMove: 3) }
        XCTAssertTrue(visible(panel), "Recently added's screen shows")
        XCTAssertTrue(panelSays(panel, "Harbor Lights"))

        // Every screen is laid out, so a title matches cards on other screens too: take the one in this row.
        func card(_ title: String) -> CGRect {
            app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", title)).allElementsBoundByIndex
                .map(\.frame).first { window.intersects($0) && $0.minY > window.midY - 100 } ?? .null
        }
        // To the row's end, which scrolls it, then back to its 3rd card.
        func focusedIs(_ prefix: String) -> Bool { app.focusedButton.lookedAt?.label.hasPrefix(prefix) == true }
        for _ in 0..<20 where !focusedIs("See all") { app.press(.right, thenWaitForFocusToMove: 3) }
        XCTAssertTrue(focusedIs("See all"), "focus reached the row's end")
        for _ in 0..<20 where !focusedIs("Saltmarsh") { app.press(.left, thenWaitForFocusToMove: 3) }
        XCTAssertTrue(panelSays(panel, "Saltmarsh"), "focus is back on the 3rd card: \(panel.label)")
        XCTAssertTrue(eventually(timeout: 3) { card("The Quiet Meridian").minX >= window.minX }, "the 2nd card is whole on screen: \(card("The Quiet Meridian"))")
        XCTAssertLessThan(card("Harbor Lights").minX, window.minX, "the 1st is past the edge, so the row still goes on")

        app.press(.left, thenWaitForFocusToMove: 3)
        XCTAssertTrue(panelSays(panel, "The Quiet Meridian"))
        XCTAssertTrue(eventually(timeout: 3) { card("Harbor Lights").minX >= window.minX }, "the 1st card comes whole into view: \(card("Harbor Lights"))")
        let second = card("The Quiet Meridian").minX

        app.press(.left, thenWaitForFocusToMove: 3)
        XCTAssertTrue(panelSays(panel, "Harbor Lights"))
        XCTAssertLessThan(card("Harbor Lights").minX + 50, second, "the 1st card's place isn't the one the 2nd had: the row has reached its start")

        remote.press(.left)
        remote.press(.left)
        XCTAssertTrue(panelSays(panel, "Harbor Lights"), "Left on the first card keeps focus there")
        XCTAssertTrue(visible(panel), "and on this screen")
    }
}
