import XCTest

/// Home on the fakes with arranged rows: it opens on one large card for the
/// title most likely to be resumed, with its words beside it; ▼ pages to
/// Continue watching, which holds the rest, then one row a screen, whose
/// words follow the focused card; the row that comes back restores its
/// card; Menu returns to the first screen, and so does the last screen's
/// Back to the top; Menu on the first screen goes to the tab bar. Runs in
/// `make apple-tv-test-sim`.
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

    /// Home opens on the large card, which says where you were and when it
    /// would end, and the last screen's Back to the top returns there.
    @MainActor
    func testHomeOpensOnTheLargeCardAndBackToTheTopReturnsThere() {
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-fakesRows"]
        app.launch()
        let card = app.homeFirstCard
        XCTAssertTrue(focused(card, timeout: 20), "Home opens on the large card: \(app.focusedButton.label)")
        XCTAssertTrue(card.label.hasPrefix("Continue watching. The Quiet Meridian. S02 E04 · Drift. 23 min left, Ends at"),
                      "it says where you were and when it ends: \(card.label)")
        XCTAssertTrue(visible(app.descendants(matching: .any)["homeNextRow-lead"]), "the next row is named at its foot")
        XCTAssertFalse(app.buttons["Play"].exists, "no hero with buttons: the card is the one thing to press")

        // Down through the card and the seven rows to the last screen's buttons.
        let edit = app.buttons["editRows"]
        let back = app.buttons["homeBackToTop"]
        for _ in 0..<12 where edit.lookedAt?.hasFocus != true && back.lookedAt?.hasFocus != true {
            app.press(.down, thenWaitForFocusToMove: 5)
        }
        XCTAssertTrue(visible(back), "Down reaches the last screen: \(app.focusedButton.label)")
        for _ in 0..<2 where back.lookedAt?.hasFocus != true { app.press(.right, thenWaitForFocusToMove: 3) }
        XCTAssertTrue(focused(back, timeout: 5), "Right from Edit rows reaches Back to the top: \(app.focusedButton.label)")

        remote.press(.select)
        XCTAssertTrue(focused(card), "Back to the top returns to the first screen, on its card: \(app.focusedButton.label)")
        XCTAssertTrue(visible(app.homeFirstScreen))

        // Menu on the first screen goes to the tab bar.
        remote.press(.menu)
        XCTAssertTrue(eventually(timeout: 10) { app.tabBars.buttons.matching(NSPredicate(format: "hasFocus == true")).firstMatch.exists },
                      "Menu on the first screen goes to the tab bar: \(app.focusedButton.label)")
    }

    @MainActor
    func testDownPagesToContinueWatchingWhosePanelFollowsFocusAndMenuReturnsToTheCard() {
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-fakesRows"]
        app.launch()
        let card = app.homeFirstCard
        XCTAssertTrue(focused(card, timeout: 20), "Home opens on the large card: \(app.focusedButton.label)")
        // Every screen is laid out (the screens are a VStack), so the others' panels exist off screen.
        let panel = app.descendants(matching: .any)["homeFocusPanel-continue"]
        XCTAssertFalse(app.windows.firstMatch.frame.contains(panel.frame), "Continue watching's words are on its own screen")

        remote.press(.down)
        XCTAssertTrue(visible(panel), "Down from the card pages to Continue watching")
        XCTAssertTrue(visible(app.staticTexts["1 of 7"]), "the rows are counted without the card")
        XCTAssertTrue(panelSays(panel, "Dead Reckoning Bay"), "the row starts after the title on the card: \(panel.label)")
        XCTAssertFalse(panel.label.contains("The Quiet Meridian"), "the title on the card isn't in the row again: \(panel.label)")
        XCTAssertFalse(app.windows.firstMatch.frame.contains(app.homeFirstScreen.frame), "the first screen has gone")

        remote.press(.right)
        XCTAssertTrue(panelSays(panel, "Saltmarsh"), "▶ moves the words to the next card: \(panel.label)")

        remote.press(.menu)
        XCTAssertTrue(focused(card), "Menu on a row screen returns to the first screen, on its card")
        XCTAssertTrue(visible(app.homeFirstScreen))

        remote.press(.down)
        XCTAssertTrue(visible(panel))
        XCTAssertTrue(panelSays(panel, "Saltmarsh"), "the row that comes back restores the card it had focus on: \(panel.label)")

        remote.press(.down)
        let next = app.descendants(matching: .any)["homeFocusPanel-recentlyAddedShows"]
        XCTAssertTrue(visible(next), "Down again pages to the next row")
        XCTAssertTrue(visible(app.staticTexts["2 of 7"]))
        XCTAssertTrue(panelSays(next, "Recently added · S03 E07 · added today. Harbor Lights"), "the words are the first card's: \(next.label)")
        XCTAssertFalse(next.label.contains("4K") || next.label.contains("HDR"), "no format on Home: \(next.label)")
    }
    /// Where a row starts: moving back along a row,
    /// the card before the focused one stays whole on screen, so the 2nd card
    /// never sits where the 1st does; Left on the 1st card keeps focus there.
    @MainActor
    func testMovingBackAlongARowKeepsTheCardBeforeInViewAndStopsAtTheFirst() {
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-fakesRows"]
        app.launch()
        XCTAssertTrue(focused(app.homeFirstCard, timeout: 20), "Home opens on its large card")
        let window = app.windows.firstMatch.frame
        let panel = app.descendants(matching: .any)["homeFocusPanel-recentlyAddedShows"]
        // Down to Recently added's screen, however many presses the way there takes: the way down is the other test's to check.
        for _ in 0..<7 where !(panel.exists && window.contains(panel.frame)) { app.press(.down, thenWaitForFocusToMove: 3) }
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
