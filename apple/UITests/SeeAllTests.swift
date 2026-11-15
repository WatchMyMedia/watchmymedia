import XCTest

/// A Home row's See all on iPhone and iPad (PLAN-SEE-ALL.md §4.4, S3), on the
/// fakes with arranged rows: Recently aired's heading link and the card at the
/// row's end both read "See all TV shows, by last aired"; the card opens TV
/// Shows at its grid sorted Last aired; and the link is used once, so a sort
/// picked afterwards stays when the tab is selected again.
final class SeeAllTests: XCTestCase {
    override func setUp() async throws { continueAfterFailure = false }

    @MainActor
    func testARowsSeeAllOpensItsLibrarySortedTheRowsWayOnce() throws {
        XCUIDevice.shared.orientation = .portrait
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-fakesRows"]
        app.launch()
        defer { app.terminate() }
        let label = "See all TV shows, by last aired"
        let links = app.buttons.matching(NSPredicate(format: "label == %@", label))
        XCTAssertTrue(links.firstMatch.waitForExistence(timeout: 20), "Recently aired's heading says where it goes and how it's sorted")
        let heading = links.firstMatch
        XCTAssertTrue(scroll(app, until: heading), "Home reaches Recently aired")

        // Swipe the row to its end, below the heading: the card is made as it comes into view.
        let card = links.element(boundBy: 1)
        for _ in 0..<8 where !onScreen(app, card) {
            let window = app.windows.firstMatch.frame
            let y = heading.frame.maxY + 70
            let start = app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: window.width - 30, dy: y))
            let end = app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: 30, dy: y))
            start.press(forDuration: 0.05, thenDragTo: end)
        }
        XCTAssertTrue(onScreen(app, card), "the row ends in a See all card with the same words")
        XCTAssertEqual(links.count, 2, "the heading link and the card")
        XCTAssertGreaterThanOrEqual(card.frame.minY, heading.frame.maxY, "the card is in the row, under the heading")

        card.tap()
        let sort = app.buttons["Sort by"].firstMatch
        XCTAssertTrue(sort.waitForExistence(timeout: 10), "TV Shows opens")
        XCTAssertTrue(waitUntil { (sort.value as? String) == "Last aired" }, "sorted the row's way: \(sort.value ?? "")")
        XCTAssertTrue(waitUntil { self.onScreen(app, app.staticTexts["All shows"]) }, "at the grid, below the tab's own rows")

        // A sort picked afterwards stays: the row's link was used once.
        sort.tap()
        app.buttons["Title"].firstMatch.tap()
        XCTAssertTrue(waitUntil { (sort.value as? String) == "Title" })
        tab(app, "Home").tap()
        tab(app, "TV Shows").tap()
        XCTAssertTrue(sort.waitForExistence(timeout: 10))
        XCTAssertFalse(waitUntil(timeout: 2) { (sort.value as? String) != "Title" }, "selecting the tab again keeps Title")
    }

    /// A tab: in the tab bar on iPhone, beside the title on iPad (EndToEndTests).
    @MainActor
    private func tab(_ app: XCUIApplication, _ name: String) -> XCUIElement {
        let bar = app.tabBars.buttons[name].firstMatch
        return bar.exists ? bar : app.buttons[name].firstMatch
    }

    /// Swipes Home up until `element` is on screen: a scroll on iPhone, a screen at a time on iPad.
    @MainActor
    private func scroll(_ app: XCUIApplication, until element: XCUIElement) -> Bool {
        for _ in 0..<10 {
            if onScreen(app, element) { return true }
            app.windows.firstMatch.swipeUp(velocity: .slow)
        }
        return onScreen(app, element)
    }

    /// On screen, clear of the tab bar.
    @MainActor
    private func onScreen(_ app: XCUIApplication, _ element: XCUIElement) -> Bool {
        guard element.exists else { return false }
        var visible = app.windows.firstMatch.frame
        let tabBar = app.tabBars.firstMatch
        if tabBar.exists, tabBar.frame.minY > visible.midY { visible.size.height = tabBar.frame.minY - visible.minY }
        return visible.contains(element.frame)
    }

    @MainActor
    private func waitUntil(timeout: TimeInterval = 10, _ condition: @escaping () -> Bool) -> Bool {
        let expectation = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in condition() }, object: nil)
        return XCTWaiter().wait(for: [expectation], timeout: timeout) == .completed
    }
}
