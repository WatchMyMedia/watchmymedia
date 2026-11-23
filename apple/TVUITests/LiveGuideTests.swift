import XCTest

/// The guide with listings on the fakes' fictional listings: the lines above
/// the chips follow focus down the grid, Select on a programme on now watches
/// it, Select on a later one opens its sheet, and Back from the player lands
/// on the channel watched.
final class LiveGuideTests: XCTestCase {
    @MainActor private var remote: XCUIRemote { XCUIRemote.shared }

    override func setUp() async throws { continueAfterFailure = false }

    @MainActor
    private func label(_ element: XCUIElement, contains text: String, timeout: TimeInterval = 10) -> Bool {
        element.waitForLabel(containing: text, timeout: timeout)
    }

    @MainActor
    func testTheHeroFollowsFocusAndSelectWatches() {
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-fakesLiveGuide", "-tab", "liveTV"]
        app.launch()
        let first = app.buttons["live-cell-2.1-Quiz Parlor"]
        let sleuth = app.buttons["live-cell-4.1-The Sleuth Files"]
        XCTAssertTrue(first.waitUntilExists(timeout: 15))
        // A directly selected tab can keep focus in the tab bar; go down through the chips.
        for _ in 0..<4 where !first.hasFocus { remote.press(.down) }
        XCTAssertTrue(first.hasFocus, "Down reaches the first channel's programme")
        let hero = app.staticTexts["live-hero-title"]
        XCTAssertTrue(label(hero, contains: "Quiz Parlor"))
        for _ in 0..<5 where !sleuth.hasFocus { remote.press(.down) }
        XCTAssertTrue(sleuth.hasFocus)
        XCTAssertTrue(label(hero, contains: "The Sleuth Files"), "The hero follows focus")
        remote.press(.select)
        let title = app.staticTexts["live-player-title"]
        XCTAssertTrue(title.waitUntilExists(timeout: 10))
        XCTAssertTrue(label(title, contains: "The Sleuth Files"), "The banner names the programme")
        remote.press(.menu)
        XCTAssertTrue(hero.waitUntilExists(timeout: 10))
        XCTAssertTrue(sleuth.waitForFocus(timeout: 10), "Back lands on the channel watched")
        XCTAssertTrue(app.buttons["live-channel-4.1"].label.contains("watching"), app.buttons["live-channel-4.1"].label)
    }

    @MainActor
    func testSelectOnALaterProgrammeOpensItsSheet() {
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-fakesLiveGuide", "-tab", "liveTV"]
        app.launch()
        let first = app.buttons["live-cell-2.1-Quiz Parlor"]
        XCTAssertTrue(first.waitUntilExists(timeout: 15))
        for _ in 0..<4 where !first.hasFocus { remote.press(.down) }
        XCTAssertTrue(first.hasFocus)
        // Dollar Dash follows Quiz Parlor on 2.1, still to come.
        let dash = app.buttons["live-cell-2.1-Dollar Dash"]
        app.press(.right, thenWaitForFocusToMove: 3)
        XCTAssertTrue(dash.hasFocus)
        remote.press(.select)
        let sheet = app.descendants(matching: .any)["live-programme-sheet"]
        XCTAssertTrue(sheet.waitUntilExists(timeout: 5), "A later programme's Select opens its sheet")
        XCTAssertTrue(app.buttons["live-sheet-watch"].exists, "Watch now is a row")
        let shot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        shot.name = "tv-guide-sheet"; shot.lifetime = .keepAlways; add(shot)
        remote.press(.menu)
        XCTAssertTrue(sheet.waitUntilGone(timeout: 5), "Back closes the sheet")
    }
}
