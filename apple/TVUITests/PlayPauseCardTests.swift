import XCTest

/// Play/Pause on a focused card plays it where Select opens its page
/// (PLAN-APPLE-REVIEW.md §6.2): a Continue watching item resumes, a movie
/// plays, a channel tunes, and a show, with no single thing to play, opens
/// as Select does. Home on the fakes, below API level 22 (its fixed rows,
/// drawn by the arranged rows' cards); then an episode in a show page's row,
/// where Select puts it on top, and a guide cell on now. Runs in
/// `make apple-tv-test-sim`.
final class PlayPauseCardTests: XCTestCase {
    @MainActor private var remote: XCUIRemote { XCUIRemote.shared }

    override func setUp() async throws {
        continueAfterFailure = false
    }

    @MainActor
    private func launch(_ arguments: [String] = []) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-fakes"] + arguments
        app.launch()
        XCTAssertTrue(app.buttons["Play"].waitForExistence(timeout: 15), "Home shows the hero")
        return app
    }

    /// Waits until `element` has focus.
    @MainActor
    private func focused(_ element: XCUIElement, timeout: TimeInterval = 10) -> Bool {
        let expectation = XCTNSPredicateExpectation(predicate: NSPredicate(format: "hasFocus == true"), object: element)
        return XCTWaiter().wait(for: [expectation], timeout: timeout) == .completed
    }

    /// The player on the fakes, whose stand-in says Playing or Paused.
    @MainActor
    private func playerIsUp(_ app: XCUIApplication, timeout: TimeInterval = 15) -> Bool {
        let up = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in app.staticTexts["Playing"].exists || app.staticTexts["Paused"].exists }, object: nil)
        return XCTWaiter().wait(for: [up], timeout: timeout) == .completed
    }

    /// Down from the tab bar until a card whose label contains one of
    /// `labels` has focus: not a Continue watching card ("… left").
    @MainActor
    private func focusCard(_ app: XCUIApplication, labelled labels: [String], downs: Int = 8) -> XCUIElement? {
        for _ in 0...downs {
            let card = app.buttons.matching(NSPredicate(format: "hasFocus == true")).firstMatch
            if card.exists, !card.label.contains(" left"), labels.contains(where: { card.label.contains($0) }) { return card }
            remote.press(.down)
            _ = XCTWaiter().wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == true"), object: app.buttons.matching(NSPredicate(format: "hasFocus == true")).firstMatch)], timeout: 3)
        }
        return nil
    }

    @MainActor
    func testPlayPauseOnAContinueWatchingCardResumesIt() {
        let app = launch()
        remote.press(.down)
        XCTAssertTrue(focused(app.buttons["Play"]), "Down from the tab bar lands on the hero's Play")
        remote.press(.down)
        let card = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "S02 E04 · 23 min left")).firstMatch
        XCTAssertTrue(focused(card), "Down from Play lands on the first Continue watching card")
        remote.press(.playPause)
        XCTAssertTrue(playerIsUp(app), "Play/Pause resumes it")
        remote.press(.menu)
        XCTAssertTrue(app.buttons["Play"].waitForExistence(timeout: 10), "Menu leaves the player for Home")
    }

    @MainActor
    func testPlayPauseOnAMovieCardPlaysIt() {
        let app = launch()
        let movies = ["Dead Reckoning Bay", "The Paper Kite", "Ninety Fathoms", "An Honest Winter", "Signal to Noise"]
        guard let card = focusCard(app, labelled: movies) else { return XCTFail("a movie's card takes focus going down Home") }
        let title = movies.first { card.label.contains($0) }!
        remote.press(.playPause)
        XCTAssertTrue(playerIsUp(app), "Play/Pause plays the movie")
        XCTAssertTrue(app.staticTexts[title].exists, "\(title) plays")
    }

    @MainActor
    func testPlayPauseOnAShowCardOpensItAsSelectDoes() {
        let app = launch()
        guard focusCard(app, labelled: ["Harbor Lights"]) != nil else { return XCTFail("a show's card takes focus going down Home") }
        remote.press(.playPause)
        let episodeRow = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "episode-card-")).firstMatch
        XCTAssertTrue(episodeRow.waitForExistence(timeout: 15), "the show's page opens")
        XCTAssertFalse(app.staticTexts["Playing"].exists, "and nothing plays")
    }

    @MainActor
    func testPlayPauseOnAChannelCardTunesIt() {
        let app = launch(["-fakesLiveTV"])
        let channel = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "home-live-channel-")).firstMatch
        XCTAssertTrue(channel.waitForExistence(timeout: 15), "Home has the Live TV row")
        for _ in 0..<4 where !channel.hasFocus {
            remote.press(.down)
            _ = focused(channel, timeout: 3)
        }
        XCTAssertTrue(focused(channel), "a channel's card takes focus")
        remote.press(.playPause)
        XCTAssertTrue(app.descendants(matching: .any)["live-player-screen"].waitForExistence(timeout: 15), "Play/Pause tunes the channel")
    }

    @MainActor
    func testPlayPauseOnAnEpisodeInTheShowPagesRowPlaysIt() {
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-open", "watchmymedia://shows/100?season=3&episode=100037"]
        app.launch()
        XCTAssertTrue(app.buttons["Play · 52 min"].waitForExistence(timeout: 15), "the show's page opens on S03 E07")
        let onTop = app.buttons["episode-card-100037"]
        for _ in 0..<4 where !onTop.hasFocus {
            remote.press(.down)
            _ = focused(onTop, timeout: 3)
        }
        XCTAssertTrue(focused(onTop), "Down reaches the episode on top, in the row")
        remote.press(.right)
        XCTAssertTrue(focused(app.buttons["episode-card-100038"]), "Right moves to E08")
        remote.press(.playPause)
        XCTAssertTrue(playerIsUp(app), "Play/Pause plays it")
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "S03 E08")).firstMatch.exists, "E08 plays, not the episode Play would have")
    }

    @MainActor
    func testPlayPauseOnAGuideCellOnNowTunesItsChannel() {
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-fakesLiveGuide", "-tab", "liveTV"]
        app.launch()
        let onNow = app.buttons["live-cell-2.1-Quiz Parlor"]
        XCTAssertTrue(onNow.waitForExistence(timeout: 20))
        for _ in 0..<5 where !onNow.hasFocus {
            remote.press(.down)
            _ = focused(onNow, timeout: 3)
        }
        XCTAssertTrue(focused(onNow), "Down reaches the first channel's programme on now")
        remote.press(.playPause)
        let title = app.staticTexts["live-player-title"]
        XCTAssertTrue(title.waitForExistence(timeout: 15), "Play/Pause tunes its channel")
        XCTAssertTrue(title.label.contains("Quiz Parlor"), title.label)
    }
}
