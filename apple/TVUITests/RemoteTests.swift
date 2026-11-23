import XCTest

/// The Apple TV app on the fakes, driven with the Siri Remote: where focus
/// lands, a long press for the context menu, and Menu going back (the stack,
/// then Home, then out of the player). Runs in `make apple-tv-test-sim`.
final class RemoteTests: XCTestCase {
    @MainActor private var remote: XCUIRemote { XCUIRemote.shared }

    override func setUp() async throws {
        continueAfterFailure = false
    }

    @MainActor
    private func launch(_ arguments: [String] = []) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-fakes"] + arguments
        app.launch()
        return app
    }

    /// Waits until `element` has focus.
    @MainActor
    private func focused(_ element: XCUIElement, timeout: TimeInterval = 10) -> Bool {
        element.waitForFocus(timeout: timeout)
    }

    /// Waits until `query` finds `n` elements.
    @MainActor
    private func count(_ query: XCUIElementQuery, is n: Int, timeout: TimeInterval = 5) -> Bool {
        eventually(timeout: timeout) { query.count == n }
    }

    @MainActor
    func testDownFromTheTabBarLandsOnTheHerosPlay() {
        let app = launch()
        let play = app.buttons["Play"]
        XCTAssertTrue(play.waitUntilExists(timeout: 15), "Home shows the hero")
        remote.press(.down)
        XCTAssertTrue(focused(play), "Down from the tab bar lands on Play")
    }

    @MainActor
    func testALongPressOpensTheCardsMenuAndMenuClosesIt() {
        let app = launch()
        XCTAssertTrue(app.buttons["Play"].waitUntilExists(timeout: 15))
        remote.press(.down)
        remote.press(.down)
        remote.press(.select, forDuration: 1.5)
        // The menu's items are not buttons to XCUI.
        let mark = app.descendants(matching: .any)["Mark watched"]
        XCTAssertTrue(mark.waitUntilExists(timeout: 5), "a long press on a Continue watching card offers Mark watched")
        XCTAssertTrue(app.descendants(matching: .any)["Go to show"].exists, "and Go to show")
        remote.press(.menu)
        XCTAssertTrue(mark.waitUntilGone(timeout: 5), "Menu closes the menu")
        XCTAssertTrue(app.buttons["Play"].exists, "and stays on Home")
    }

    @MainActor
    func testTheShowPageOpensOnPlayAndFocusAlongTheRowChangesTheEpisodeOnTop() {
        let app = launch(["-open", "watchmymedia://shows/100?season=3&episode=100035"])
        let resume = app.buttons["Resume from 21:00"]
        XCTAssertTrue(resume.waitUntilExists(timeout: 15), "the show's page is open on the episode")
        XCTAssertTrue(focused(resume), "with focus on Resume")
        XCTAssertTrue(app.buttons["From the start"].exists)
        remote.press(.down)
        // One look for either label ("Season 3" alone or with more after it),
        // rather than ten seconds on the first before trying the second.
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@ AND hasFocus == true", "Season 3")).firstMatch.waitUntilExists(timeout: 10),
                      "Down reaches the chosen season's tab")
        remote.press(.down)
        let this = app.buttons["episode-card-100035"]
        XCTAssertTrue(focused(this), "Down reaches the episode on top in the row")
        remote.press(.right)
        XCTAssertTrue(focused(app.buttons["episode-card-100036"]))
        let play = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Play ·")).firstMatch
        XCTAssertTrue(play.waitUntilExists(timeout: 5), "focus on the next card puts it on top")
        XCTAssertFalse(resume.exists)
        remote.press(.select)
        XCTAssertTrue(focused(play), "Select goes back up to Play")
        XCTAssertNil(app.staticTexts["Playing"].exists ? true : nil, "and never plays")
        remote.press(.menu)
        XCTAssertTrue(app.buttons["Go to episode"].waitUntilExists(timeout: 10), "Menu leaves the show in one step, for Home")
    }

    @MainActor
    func testTheShowOpensOnItsNextEpisodeAndAboutTheShowIsBelow() {
        let app = launch(["-open", "watchmymedia://shows/100"])
        let resume = app.buttons["Resume from 21:00"]
        XCTAssertTrue(resume.waitUntilExists(timeout: 15), "the next episode is on top")
        XCTAssertTrue(focused(resume))
        XCTAssertTrue(app.staticTexts["show-episode-kicker"].label.hasPrefix("UP NEXT · SEASON 3, EPISODE 5"), app.staticTexts["show-episode-kicker"].label)
        remote.press(.down)
        XCTAssertTrue(focused(app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Season 3")).firstMatch), "Down reaches the tabs")
        remote.press(.down)
        XCTAssertTrue(focused(app.buttons["episode-card-100035"]), "then the row, on the episode on top")
        remote.press(.down)
        // Without Notify me and watchlists (older servers), About the show's first button is its More.
        let more = app.buttons["show-more"]
        XCTAssertTrue(focused(more), "Down from the row reaches About the show, on its first button")
        remote.press(.select)
        let markShow = app.buttons["show-mark-watched"]
        XCTAssertTrue(focused(markShow), "its More opens on Mark show watched")
        remote.press(.menu)
        XCTAssertTrue(focused(more), "Menu closes it, back on More")
    }

    @MainActor
    func testDeletingAsksFullScreenWithFocusOnCancel() {
        let app = launch(["-open", "watchmymedia://shows/100?season=3&episode=100037"])
        let play = app.buttons["Play · 52 min"]
        XCTAssertTrue(play.waitUntilExists(timeout: 15))
        XCTAssertTrue(focused(play))
        // More, beside Play, opens its panel.
        remote.press(.right)
        XCTAssertTrue(focused(app.buttons["episode-more"]))
        remote.press(.select)
        let delete = app.buttons["episode-delete"]
        XCTAssertTrue(delete.waitUntilExists(timeout: 5), "More offers Delete episode…")
        XCTAssertEqual(delete.label, "Delete episode…")
        // The show's group moved to About the show.
        XCTAssertFalse(app.descendants(matching: .any)["Mark Season 3 watched"].exists)
        XCTAssertFalse(app.descendants(matching: .any)["Cast & details"].exists)
        for _ in 0..<10 where !delete.hasFocus { remote.press(.down) }
        remote.press(.select)
        let cancel = app.buttons["Cancel"]
        XCTAssertTrue(cancel.waitUntilExists(timeout: 10), "the confirmation is up")
        XCTAssertTrue(app.staticTexts["Delete “The Long Dark”?"].exists)
        XCTAssertTrue(focused(cancel), "with focus on Cancel, so a stray press deletes nothing")
        remote.press(.select)
        XCTAssertTrue(cancel.waitUntilGone(timeout: 5), "Cancel closes it")
        XCTAssertTrue(play.exists, "and the episode stays")
    }

    @MainActor
    func testALongPressForgetsARememberedServer() {
        let app = XCUIApplication()
        app.launchArguments = ["-fakesNoServer"]
        app.launch()
        let remembered = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "home.watchmy.media")).firstMatch
        XCTAssertTrue(remembered.waitUntilExists(timeout: 15), "the remembered server is listed")
        remote.press(.down)
        remote.press(.down)
        XCTAssertTrue(focused(remembered))
        remote.press(.select, forDuration: 1.5)
        let forget = app.descendants(matching: .any)["Forget"]
        XCTAssertTrue(forget.waitUntilExists(timeout: 5), "a long press offers Forget")
        remote.press(.select)
        XCTAssertTrue(remembered.waitUntilGone(timeout: 5), "Forget takes it off the list")
    }

    @MainActor
    func testMenuPopsThenGoesFromASectionToHome() {
        let app = launch(["-tab", "movies", "-open", "watchmymedia://movies/501"])
        XCTAssertTrue(app.buttons["From the start"].waitUntilExists(timeout: 15), "the movie page is open")
        remote.press(.down)
        remote.press(.menu)
        let sort = app.buttons["Sort by"]
        // The grid stays laid out under the page: wait for the page to go.
        XCTAssertTrue(app.buttons["From the start"].waitUntilGone(timeout: 10), "Menu leaves the movie page")
        XCTAssertTrue(sort.waitUntilExists(timeout: 10), "Menu goes back to the Movies grid")
        remote.press(.down)
        remote.press(.menu)
        XCTAssertTrue(app.buttons["Go to episode"].waitUntilExists(timeout: 10), "Menu at the grid goes to Home")
    }

    @MainActor
    func testMenuLeavesThePlayer() {
        let app = launch(["-open", "watchmymedia://play/9101"])
        XCTAssertTrue(app.staticTexts["Playing"].waitUntilExists(timeout: 15) || app.staticTexts["Paused"].exists, "the player is up")
        remote.press(.menu)
        XCTAssertTrue(app.buttons["Go to episode"].waitUntilExists(timeout: 10), "Menu leaves the player for Home")
    }
}
