import UIKit
import XCTest

/// The iPad's Home one row a screen (PLAN-HOME.md §7.6) on the fakes with
/// arranged rows: the hero beside Continue watching, a swipe up to a row's
/// screen whose panel is the leading card's and follows the row's snap, the
/// next-row line paging, portrait keeping the screens, the end screen and
/// Back to the top. Runs in `make apple-test-sim` on an iPad simulator.
final class HomeScreensTests: XCTestCase {
    override func setUp() async throws { continueAfterFailure = false }

    @MainActor
    func testScreensPageByRowWithTheLeadingCardsPanel() throws {
        try requireTablet()
        XCUIDevice.shared.orientation = .landscapeLeft
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-fakesRows"]
        app.launch()
        XCTAssertTrue(app.buttons["Play"].waitForExistence(timeout: 20), "Home shows the hero")
        let screens = app.descendants(matching: .any)["homeScreens"]
        XCTAssertTrue(screens.waitForExistence(timeout: 5), "an arranged Home on iPad is screens")
        XCTAssertTrue(visible(app.staticTexts["Row 1 of 7"]), "the first screen counts the drawn rows")
        XCTAssertTrue(visible(app.descendants(matching: .any)["row-continue"].firstMatch), "Continue watching sits beside the hero")
        // Every screen is laid out (the screens are a VStack), so the others' panels exist off screen.
        let panel = app.descendants(matching: .any)["homeLeadPanel-recentlyAddedShows"]
        XCTAssertFalse(app.windows.firstMatch.frame.contains(panel.frame), "the first screen has the hero, not a panel")
        shot(app, "iPad Home screen 1 landscape")

        // The scroll view's element is every screen tall, so its centre is off screen: swipe on the window.
        app.windows.firstMatch.swipeUp()
        XCTAssertTrue(visible(panel), "a swipe up pages to the first row screen")
        XCTAssertTrue(visible(app.staticTexts["Row 2 of 7"]))
        XCTAssertTrue(panelSays(panel, "Recently added · S03 E07 · added today. Harbor Lights"), "the panel is the leading card's: \(panel.label)")
        // The hero's Go to episode sits on the first screen, above: look for one on this screen.
        XCTAssertTrue(anyVisible(app.buttons.matching(identifier: "Go to episode")), "with the buttons the hero would have")
        XCTAssertTrue(anyVisible(app.buttons.matching(identifier: "Go to show")))
        shot(app, "iPad Home row screen landscape")

        // Swipe on a card: the row's identifier reaches its heading too (apple/AGENTS.md), which doesn't scroll.
        let card = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Harbor Lights, S03 E07 · added today")).firstMatch
        XCTAssertTrue(visible(card))
        card.swipeLeft()
        XCTAssertTrue(waitUntil(timeout: 10) { !panel.label.contains("Harbor Lights") }, "swiping the row moves the panel to the card it snapped to: \(panel.label)")
        shot(app, "iPad Home row screen after a swipe")

        app.buttons["homeNextRow-1"].tap()
        XCTAssertTrue(visible(app.staticTexts["Row 3 of 7"]), "the next-row line pages to the next row")

        XCUIDevice.shared.orientation = .portrait
        waitForOrientation(app, portrait: true)
        let third = app.descendants(matching: .any)["homeLeadPanel-list:9001"]
        XCTAssertTrue(visible(third), "portrait keeps the screens, the panel above the row")
        XCTAssertTrue(visible(app.staticTexts["Row 3 of 7"]))
        shot(app, "iPad Home row screen portrait")

        for index in 2..<7 {
            let line = app.buttons["homeNextRow-\(index)"]
            XCTAssertTrue(visible(line), "screen \(index + 1) shows its next-row line")
            line.tap()
        }
        let end = app.descendants(matching: .any)["homeEnd"]
        XCTAssertTrue(visible(end), "the last screen is the end of Home")
        XCTAssertTrue(visible(app.buttons["editRows"]), "with Edit rows")
        shot(app, "iPad Home end screen portrait")
        app.buttons["homeBackToTop"].tap()
        XCTAssertTrue(anyVisible(app.buttons.matching(identifier: "Play")), "Back to the top returns to the hero")
        shot(app, "iPad Home screen 1 portrait")
        XCUIDevice.shared.orientation = .landscapeLeft
        app.terminate()
    }

    @MainActor
    private func visible(_ element: XCUIElement, timeout: TimeInterval = 10) -> Bool {
        let expectation = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in element.exists && XCUIApplication().windows.firstMatch.frame.contains(element.frame) }, object: nil)
        return XCTWaiter().wait(for: [expectation], timeout: timeout) == .completed
    }

    /// Waits until one of `query`'s elements is on screen (a label several screens share).
    @MainActor
    private func anyVisible(_ query: XCUIElementQuery, timeout: TimeInterval = 10) -> Bool {
        let window = XCUIApplication().windows.firstMatch
        let expectation = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            query.allElementsBoundByIndex.contains { window.frame.contains($0.frame) }
        }, object: nil)
        return XCTWaiter().wait(for: [expectation], timeout: timeout) == .completed
    }

    @MainActor
    private func panelSays(_ panel: XCUIElement, _ text: String, timeout: TimeInterval = 10) -> Bool {
        let expectation = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label CONTAINS %@", text), object: panel)
        return XCTWaiter().wait(for: [expectation], timeout: timeout) == .completed
    }

    @MainActor
    private func waitUntil(timeout: TimeInterval, _ condition: () -> Bool) -> Bool {
        let end = Date().addingTimeInterval(timeout)
        while Date() < end {
            if condition() { return true }
            RunLoop.current.run(until: Date().addingTimeInterval(0.25))
        }
        return condition()
    }

    @MainActor
    private func waitForOrientation(_ app: XCUIApplication, portrait: Bool) {
        _ = waitUntil(timeout: 10) {
            let frame = app.windows.firstMatch.frame
            return portrait ? frame.height > frame.width : frame.width > frame.height
        }
    }

    private func requireTablet() throws {
        guard UIDevice.current.userInterfaceIdiom == .pad else { throw XCTSkip("Home's screens run on an iPad simulator.") }
    }

    @MainActor
    private func shot(_ app: XCUIApplication, _ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
