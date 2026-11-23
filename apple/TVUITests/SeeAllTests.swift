import XCTest

/// A Home row's See all on Apple TV, on the fakes with
/// arranged rows: Recently aired ends in a card that says where it goes; with
/// it focused the panel stays on the row's last episode; Select opens TV
/// Shows (already visited, so its grid is alive) sorted Last aired with focus
/// in the grid, below the tab's own rows; Menu goes back to Home with focus
/// on the card. A poster row's card (Recently added · TV Shows) fits its
/// words. Runs in `make apple-tv-test-sim`.
final class SeeAllTests: XCTestCase {
    @MainActor private var remote: XCUIRemote { XCUIRemote.shared }

    override func setUp() async throws {
        continueAfterFailure = false
    }

    @MainActor
    func testARowsSeeAllCardOpensItsLibrarySortedTheRowsWay() {
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-fakesRows", "-tab", "tvShows"]
        app.launch()
        let sort = app.buttons["Sort by"]
        XCTAssertTrue(sort.waitUntilExists(timeout: 15), "TV Shows first, so the link reaches a grid that is already there")
        XCTAssertTrue(sort.wait(until: NSPredicate(format: "value == %@", "Date added"), timeout: 10))
        XCTAssertTrue(app.waitForFocusedButton(timeout: 15) { _ in true }, "the tab bar has focus")
        // Into the tab's rows, then Menu goes Home (Menu in the tab bar would leave the app).
        app.press(.down, thenWaitForFocusToMove: 5)
        XCTAssertFalse(app.tabBars.buttons.matching(NSPredicate(format: "hasFocus == true")).firstMatch.exists, "focus is in TV Shows")
        remote.press(.menu)
        let continuing = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "S02 E04 · 23 min left")).firstMatch
        XCTAssertTrue(continuing.waitUntilExists(timeout: 15), "Home shows its first row")
        XCTAssertTrue(app.waitForFocusedButton(timeout: 15) { _ in true }, "the tab bar has focus")
        remote.press(.down)
        XCTAssertTrue(continuing.waitForFocus(), "Down from the tab bar lands on the first Continue watching card")

        // Recently aired is the fourth row: Recently added · TV Shows and a list come first.
        let panel = app.descendants(matching: .any)["homeFocusPanel-recentlyAired"]
        for _ in 0..<3 { app.press(.down, thenWaitForFocusToMove: 5) }
        XCTAssertTrue(eventually(timeout: 10) { panel.exists && app.windows.firstMatch.frame.contains(panel.frame) }, "Down pages to Recently aired")

        // ▶ to the row's end: See all comes after the last episode, and the panel stays on that episode.
        let card = app.buttons["See all TV shows, by last aired"]
        var lastEpisode = panel.lookedAt?.label ?? ""
        for _ in 0..<12 where card.lookedAt?.hasFocus != true {
            lastEpisode = panel.lookedAt?.label ?? lastEpisode
            app.press(.right, thenWaitForFocusToMove: 3)
        }
        XCTAssertTrue(card.waitForFocus(timeout: 5), "the row ends in See all")
        XCTAssertFalse(lastEpisode.isEmpty)
        XCTAssertFalse(eventually(timeout: 1.5) { panel.lookedAt?.label != lastEpisode },
                       "the panel stays on the last episode: \(panel.label), was \(lastEpisode)")
        shot("Recently aired ends in See all; the panel stays on the last episode")

        remote.press(.select)
        XCTAssertTrue(sort.waitUntilExists(timeout: 10), "TV Shows opens")
        XCTAssertTrue(sort.wait(until: NSPredicate(format: "value == %@", "Last aired"), timeout: 10), "sorted the row's way: \(sort.value ?? "")")
        let allShows = app.staticTexts["All shows"]
        XCTAssertTrue(allShows.waitUntilExists(timeout: 10))
        XCTAssertTrue(eventually(timeout: 10) {
            guard let focused = app.focusedButton.lookedAt, let heading = allShows.lookedAt else { return false }
            return focused.frame.minY > heading.frame.maxY && app.windows.firstMatch.frame.contains(focused.frame)
        }, "focus is on the grid, below the tab's own rows: \(app.focusedButton.label)")
        shot("TV Shows at its grid, sorted Last aired")

        remote.press(.menu)
        XCTAssertTrue(card.waitForFocus(timeout: 10), "Menu goes back to Home with focus on See all: \(app.focusedButton.label)")
        XCTAssertTrue(eventually(timeout: 5) { panel.exists && app.windows.firstMatch.frame.contains(panel.frame) }, "on Recently aired's screen")
        XCTAssertTrue(panel.waitForLabel(containing: lastEpisode, timeout: 5), "the panel still on the last episode: \(panel.label)")
        shot("Menu: back on Recently aired, See all focused")

        // Up past the list row to Recently added · TV Shows, a poster row, and along it to its card.
        for _ in 0..<2 { app.press(.up, thenWaitForFocusToMove: 5) }
        let posterCard = app.buttons["See all TV shows, by date added"]
        for _ in 0..<14 where posterCard.lookedAt?.hasFocus != true { app.press(.right, thenWaitForFocusToMove: 3) }
        XCTAssertTrue(posterCard.waitForFocus(timeout: 5), "Recently added · TV Shows ends in See all too")
        shot("A poster row's See all card")
    }

    @MainActor
    private func shot(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
