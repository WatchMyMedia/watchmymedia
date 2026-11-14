import XCTest

/// TvLiveGuide with listings on the fakes' fictional listings: the hero
/// follows focus down the grid and Select watches the focused channel.
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
    }
}
