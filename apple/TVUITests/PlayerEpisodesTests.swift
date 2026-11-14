import XCTest

/// The player's Episodes tab (TvPlayerEpisodes.dc.html) on the fakes: Harbor
/// Lights S03 E07, The Long Dark, is file 9101. The fake player has no item,
/// so AVKit opens no info panel; `-playerPanel episodes` shows the same tab
/// over the video. Runs in `make apple-tv-test-sim`.
final class PlayerEpisodesTests: XCTestCase {
    @MainActor private var remote: XCUIRemote { XCUIRemote.shared }

    override func setUp() async throws {
        continueAfterFailure = false
    }

    @MainActor
    private func play(_ arguments: [String] = []) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-open", "watchmymedia://play/9101", "-playerPanel", "episodes"] + arguments
        app.launch()
        return app
    }

    @MainActor
    private func focused(_ element: XCUIElement, timeout: TimeInterval = 10) -> Bool {
        let expectation = XCTNSPredicateExpectation(predicate: NSPredicate(format: "hasFocus == true"), object: element)
        return XCTWaiter().wait(for: [expectation], timeout: timeout) == .completed
    }

    @MainActor
    private func says(_ element: XCUIElement, _ text: String, timeout: TimeInterval = 10) -> Bool {
        let expectation = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label CONTAINS %@", text), object: element)
        return XCTWaiter().wait(for: [expectation], timeout: timeout) == .completed
    }

    @MainActor
    private func snap(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    @MainActor
    func testTheTabOpensOnTheEpisodePlayingAndRightIsTheNextOne() {
        let app = play()
        let playing = app.buttons["player-episode-7"]
        XCTAssertTrue(playing.waitForExistence(timeout: 20), app.debugDescription)
        XCTAssertTrue(focused(playing), "focus starts on the one playing")
        XCTAssertTrue(playing.label.contains("now playing"), playing.label)
        let line = app.staticTexts["player-episodes-focus-line"]
        XCTAssertTrue(says(line, "Now playing · S03 E07 · The Long Dark"), line.label)
        remote.press(.right)
        let next = app.buttons["player-episode-8"]
        XCTAssertTrue(focused(next))
        XCTAssertTrue(says(line, "Next · S03 E08"), line.label)
        snap("tv-player-episodes")
        for _ in 0..<6 { remote.press(.left) }
        let earlier = app.buttons["player-episode-2"]
        XCTAssertTrue(focused(earlier))
        XCTAssertTrue(earlier.label.hasSuffix(", watched"), earlier.label)
    }

    @MainActor
    func testUpReachesTheSeasonsAndFocusChoosesOne() {
        let app = play()
        let playing = app.buttons["player-episode-7"]
        XCTAssertTrue(playing.waitForExistence(timeout: 20))
        XCTAssertTrue(focused(playing))
        remote.press(.up)
        let season3 = app.buttons["player-season-3"]
        let season2 = app.buttons["player-season-2"]
        XCTAssertTrue(season2.waitForExistence(timeout: 10))
        // Up lands on the season button nearest the card; move to Season 2 from there.
        for _ in 0..<3 where !season2.hasFocus { remote.press(.left) }
        XCTAssertTrue(focused(season2))
        let line = app.staticTexts["player-episodes-focus-line"]
        XCTAssertTrue(says(line, "Season 2 ·"), line.label)
        // The fakes' season 2 has episodes 4 to 8 on disk.
        let first = app.buttons["player-episode-4"]
        XCTAssertTrue(first.waitForExistence(timeout: 10))
        XCTAssertFalse(app.buttons["player-episode-3"].exists, "only the episodes on disk")
        XCTAssertFalse(first.label.contains("now playing"))
        snap("tv-player-episodes-season")
        remote.press(.down)
        XCTAssertTrue(focused(first), "Season 2 opens on its first episode")
        remote.press(.up)
        for _ in 0..<2 where !season3.hasFocus { remote.press(.right) }
        XCTAssertTrue(focused(season3))
        remote.press(.down)
        XCTAssertTrue(focused(app.buttons["player-episode-7"]), "back on the season playing, the one playing")
    }

    @MainActor
    func testSelectPlaysAnotherEpisodeInPlace() {
        let app = play()
        let playing = app.buttons["player-episode-7"]
        XCTAssertTrue(playing.waitForExistence(timeout: 20))
        XCTAssertTrue(focused(playing))
        remote.press(.select)
        XCTAssertTrue(playing.label.contains("now playing"), "the one playing keeps playing")
        remote.press(.right)
        let next = app.buttons["player-episode-8"]
        XCTAssertTrue(focused(next))
        remote.press(.select)
        XCTAssertTrue(says(next, "now playing"), next.label)
        XCTAssertFalse(playing.label.contains("now playing"))
        remote.press(.menu)
        XCTAssertTrue(next.waitForNonExistence(timeout: 5), "Back closes the tab")
        XCTAssertTrue(app.staticTexts["S03 E08 · Squall Line"].waitForExistence(timeout: 10), "and the chosen episode plays")
    }

    @MainActor
    func testPlayPausePlaysTheFocusedEpisode() {
        let app = play()
        let playing = app.buttons["player-episode-7"]
        XCTAssertTrue(playing.waitForExistence(timeout: 20))
        XCTAssertTrue(focused(playing))
        remote.press(.left)
        let earlier = app.buttons["player-episode-6"]
        XCTAssertTrue(focused(earlier))
        remote.press(.playPause)
        XCTAssertTrue(says(earlier, "now playing"), earlier.label)
    }

    @MainActor
    func testAFilmHasNoEpisodesTab() {
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-open", "watchmymedia://play/9900", "-playerPanel", "episodes"]
        app.launch()
        XCTAssertTrue(app.staticTexts["Playing"].waitForExistence(timeout: 20) || app.staticTexts["Paused"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.descendants(matching: .any)["player-episodes-tab"].exists)
    }
}
