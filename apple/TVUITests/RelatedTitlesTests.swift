import XCTest

/// Related titles at the end of movie and show pages on Apple TV, on the
/// fakes at API level 30 (`-fakesRelated`), driven with the remote: Down
/// from the movie's versions reaches its collection, then More like this,
/// each shelf on its first card; Up leaves them; Select opens a card's page
/// and Menu comes back to the card. On a show, Down from About the show
/// reaches More like this and Up goes back. Runs in `make apple-tv-test-sim`.
final class RelatedTitlesTests: XCTestCase {
    @MainActor private var remote: XCUIRemote { XCUIRemote.shared }

    override func setUp() async throws {
        continueAfterFailure = false
    }

    @MainActor
    private func launch(_ link: String) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-fakesRelated", "-open", link]
        app.launch()
        return app
    }

    /// Presses `button` until the focused card's label starts with `title`, a few times at most.
    @MainActor
    private func press(_ button: XCUIRemote.Button, in app: XCUIApplication, untilFocusOn title: String, times: Int = 8) -> Bool {
        for _ in 0..<times where !(app.focusedButton.lookedAt?.label.hasPrefix("\(title),") ?? false) {
            app.press(button, thenWaitForFocusToMove: 3)
        }
        return app.waitForFocusedButton(timeout: 5) { $0.hasPrefix("\(title),") }
    }

    @MainActor
    func testDownFromAMoviesVersionsReachesItsShelvesAndUpLeavesThem() {
        let app = launch("watchmymedia://movies/500")
        defer { app.terminate() }
        XCTAssertTrue(app.staticTexts["Dead Reckoning Bay"].firstMatch.waitUntilExists(timeout: 20))
        XCTAssertTrue(app.waitForFocusedButton(timeout: 15) { _ in true }, "the page has focus")
        // The shelves arrive below the first screen, named along its bottom edge.
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Cold Harbour Road,")).firstMatch.waitUntilExists(timeout: 10))
        shot("The first screen, the shelves named below it")

        XCTAssertTrue(press(.down, in: app, untilFocusOn: "Cold Harbour Road"), "Down from the versions reaches the collection, on its first film: \(app.focusedButton.label)")
        shot("The collection's first card in focus")
        app.press(.down, thenWaitForFocusToMove: 3)
        XCTAssertTrue(app.waitForFocusedButton { $0.hasPrefix("Ninety Fathoms,") }, "Down again reaches More like this, on its first card: \(app.focusedButton.label)")
        app.press(.right, thenWaitForFocusToMove: 3)
        XCTAssertTrue(app.waitForFocusedButton { $0.hasPrefix("Signal to Noise,") }, "Right moves along it")
        shot("More like this in focus")

        app.press(.up, thenWaitForFocusToMove: 3)
        XCTAssertTrue(app.waitForFocusedButton { $0.hasPrefix("Cold Harbour Road,") }, "Up goes back to the collection's card: \(app.focusedButton.label)")
        app.press(.up, thenWaitForFocusToMove: 3)
        XCTAssertTrue(app.waitForFocusedButton { $0.hasPrefix("Play") }, "Up again leaves the shelves for the versions: \(app.focusedButton.label)")

        // Select opens the card's page over this one; Menu comes back to the card.
        XCTAssertTrue(press(.down, in: app, untilFocusOn: "Cold Harbour Road", times: 3))
        remote.press(.select)
        let breadcrumb = app.staticTexts.matching(NSPredicate(format: "label ENDSWITH %@", "› Cold Harbour Road")).firstMatch
        XCTAssertTrue(breadcrumb.waitUntilExists(timeout: 10), "Cold Harbour Road's page opens")
        shot("A card's page")
        remote.press(.menu)
        XCTAssertTrue(app.waitForFocusedButton(timeout: 10) { $0.hasPrefix("Cold Harbour Road,") }, "Menu comes back to the card: \(app.focusedButton.label)")
        XCTAssertTrue(breadcrumb.waitUntilGone(timeout: 5))
    }

    @MainActor
    func testDownFromAboutTheShowReachesMoreLikeThisAndUpGoesBack() {
        let app = launch("watchmymedia://shows/100")
        defer { app.terminate() }
        XCTAssertTrue(app.waitForFocusedButton(timeout: 20) { $0.hasPrefix("Resume") || $0.hasPrefix("Play") }, "focus starts on the episode on top")

        // Harbor Lights' More like this begins with The Quiet Meridian, the nearest in years of those sharing a genre.
        XCTAssertTrue(press(.down, in: app, untilFocusOn: "The Quiet Meridian"), "Down past the row and About the show reaches More like this: \(app.focusedButton.label)")
        shot("More like this in focus on the show page")
        app.press(.up, thenWaitForFocusToMove: 3)
        XCTAssertTrue(app.waitForFocusedButton { !$0.contains(", ") || $0.hasPrefix("Notify") }, "Up goes back to About the show's buttons: \(app.focusedButton.label)")
        app.press(.down, thenWaitForFocusToMove: 3)
        XCTAssertTrue(app.waitForFocusedButton { $0.hasPrefix("The Quiet Meridian,") }, "Down comes back to the same card: \(app.focusedButton.label)")
    }

    @MainActor
    private func shot(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
