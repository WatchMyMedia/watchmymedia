import XCTest
import UIKit

/// The Watchlist button on a movie's page, on the fakes at API level 33
/// (`-fakesWatchlists`): with one watchlist it saves at once and says so,
/// with Undo; with several it opens Save to watchlist, where New
/// watchlist names one and saves the title to it.
final class WatchlistsTests: XCTestCase {
    override func setUp() async throws { continueAfterFailure = false }

    @MainActor
    private func launch(_ extra: [String]) -> XCUIApplication {
        XCUIDevice.shared.orientation = UIDevice.current.userInterfaceIdiom == .pad ? .landscapeLeft : .portrait
        let app = XCUIApplication()
        // Dead Reckoning Bay, on no watchlist.
        app.launchArguments = ["-fakes", "-fakesWatchlists", "-watchlistBannerSeconds", "30", "-open", "watchmymedia://movies/500"] + extra
        app.launch()
        return app
    }

    /// The button and the picker's rows are toggles to VoiceOver, which XCUI lists as switches, not buttons.
    @MainActor
    private func toggle(_ app: XCUIApplication, _ identifier: String) -> XCUIElement {
        app.descendants(matching: .any)[identifier]
    }

    /// A toggle's state, which XCUI reads as its value: "1" when on.
    @MainActor
    private func turnsOn(_ element: XCUIElement) -> Bool {
        XCTWaiter().wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "value == '1'"), object: element)], timeout: 10) == .completed
    }

    @MainActor
    private func says(_ element: XCUIElement, _ label: String) -> Bool {
        element.wait(for: \.label, toEqual: label, timeout: 10)
    }

    @MainActor
    func testWithOneWatchlistTheButtonSavesAtOnceAndUndoTakesItOff() throws {
        let app = launch(["-fakesOneWatchlist"])
        let button = toggle(app, "watchlist-button")
        XCTAssertTrue(button.waitForExistence(timeout: 20), "the button is on the movie's page from level 33")
        XCTAssertEqual(button.label, "Watchlist, Dead Reckoning Bay")
        button.tap()
        XCTAssertTrue(says(button, "On Watchlist, Dead Reckoning Bay"), button.label)
        let banner = app.otherElements["watchlist-banner"]
        XCTAssertTrue(banner.waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["Saved to Watchlist"].exists)
        XCTAssertFalse(app.otherElements["watchlist-picker"].exists, "one watchlist opens no picker")
        app.buttons["watchlist-undo"].tap()
        XCTAssertTrue(says(button, "Watchlist, Dead Reckoning Bay"), "Undo takes it off again")
        XCTAssertTrue(banner.waitForNonExistence(timeout: 5))
    }

    @MainActor
    func testWithSeveralTheButtonOpensSaveToWatchlistAndNewWatchlistSavesToIt() throws {
        let app = launch([])
        let button = toggle(app, "watchlist-button")
        XCTAssertTrue(button.waitForExistence(timeout: 20))
        button.tap()
        let picker = app.otherElements["watchlist-picker"]
        XCTAssertTrue(picker.waitForExistence(timeout: 10), "three watchlists: the picker")
        let halloween = toggle(app, "watchlist-row-9102")
        XCTAssertTrue(halloween.waitForExistence(timeout: 5))
        XCTAssertEqual(halloween.value as? String, "0")
        halloween.tap()
        XCTAssertTrue(turnsOn(halloween), "saved as it is pressed")

        app.buttons["watchlist-new"].tap()
        let name = app.textFields["watchlist-name"]
        XCTAssertTrue(name.waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["watchlist-create"].isEnabled, "Create waits for a name")
        // The field takes focus as it appears; a tap that lands first would type nowhere.
        let focused = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in (name.value(forKey: "hasKeyboardFocus") as? Bool) == true }, object: nil)
        if XCTWaiter().wait(for: [focused], timeout: 5) != .completed { name.tap() }
        name.typeText("Rainy days")
        app.buttons["watchlist-create"].tap()
        let made = toggle(app, "watchlist-row-9104")
        XCTAssertTrue(made.waitForExistence(timeout: 10), "back to the checks with the new watchlist")
        XCTAssertTrue(turnsOn(made), "Create saves the title to it")
        app.buttons["watchlist-done"].tap()
        XCTAssertTrue(picker.waitForNonExistence(timeout: 10))
        XCTAssertTrue(says(button, "On 2 watchlists, Dead Reckoning Bay"), button.label)
    }
}
