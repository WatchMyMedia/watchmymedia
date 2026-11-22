import XCTest

/// The Watchlist button on Apple TV, on the fakes at API level 33
/// (`-fakesWatchlists`, three watchlists): on a movie's page beside Trailer,
/// Select opens Save to watchlist over the whole screen, a selected
/// watchlist is saved at once, and Menu closes the list with the button's
/// words changed. Runs in `make apple-tv-test-sim`.
final class WatchlistsTests: XCTestCase {
    @MainActor private var remote: XCUIRemote { XCUIRemote.shared }

    override func setUp() async throws { continueAfterFailure = false }

    @MainActor
    func testSelectOpensSaveToWatchlistAndMenuClosesIt() {
        let app = XCUIApplication()
        // Dead Reckoning Bay, on no watchlist.
        app.launchArguments = ["-fakes", "-fakesWatchlists", "-open", "watchmymedia://movies/500"]
        app.launch()
        // A toggle to VoiceOver, which XCUI lists as a switch, not a button.
        let button = app.descendants(matching: .any)["watchlist-button"]
        XCTAssertTrue(button.waitUntilExists(timeout: 20), "the button is on the movie's page from level 33")
        XCTAssertEqual(button.label, "Watchlist, Dead Reckoning Bay")
        // Down from the tab bar into the row of buttons (under the focused tab), then along it.
        remote.press(.down)
        let inPage = app.descendants(matching: .any).matching(NSPredicate(format: "hasFocus == true"))
        for _ in 0..<6 where !button.hasFocus {
            guard let current = inPage.allElementsBoundByIndex.first, current.frame.minY > 150 else {
                _ = button.waitForFocus(timeout: 1)
                continue
            }
            remote.press(current.frame.minX > button.frame.minX ? .left : .right)
            _ = button.waitForFocus(timeout: 1)
        }
        XCTAssertTrue(button.waitForFocus(timeout: 5))
        remote.press(.select)

        let halloween = app.descendants(matching: .any)["watchlist-row-9102"]
        XCTAssertTrue(halloween.waitUntilExists(timeout: 10), "three watchlists: the picker, the whole screen")
        for _ in 0..<3 where !halloween.hasFocus {
            remote.press(.down)
            _ = halloween.waitForFocus(timeout: 2)
        }
        XCTAssertTrue(halloween.waitForFocus(timeout: 5))
        remote.press(.select)
        // A toggle's state, which XCUI reads as its value: "1" when on.
        XCTAssertTrue(eventually(timeout: 10) { (halloween.lookedAt?.value as? String) == "1" }, "saved as it is selected")

        remote.press(.menu)
        XCTAssertTrue(halloween.waitUntilGone(timeout: 10), "Menu closes the list")
        XCTAssertTrue(button.waitForLabel(containing: "On Halloween, Dead Reckoning Bay"), button.label)
    }
}
