import XCTest

/// Trailer on the Apple TV movie page (TvMovieTrailer.dc.html, PLAN-TRAILERS.md
/// §6.3), on the fakes: Dead Reckoning Bay has a YouTube trailer, The Paper
/// Kite a trailer file. A simulator has no YouTube app, so a YouTube trailer
/// shows the code to scan (TvTrailerQR.dc.html). Runs in `make apple-tv-test-sim`.
final class TrailerTests: XCTestCase {
    @MainActor private var remote: XCUIRemote { XCUIRemote.shared }

    override func setUp() async throws { continueAfterFailure = false }

    @MainActor
    func testAYouTubeTrailerWithoutTheYouTubeAppShowsACodeToScan() {
        let app = launch(movie: 500)
        let trailer = app.buttons["movie-trailer"]
        XCTAssertTrue(trailer.waitForExistence(timeout: 20))
        XCTAssertTrue(focus(trailer), "Right from Play reaches Trailer")
        XCTAssertTrue(app.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", "Shows a code to scan with your phone")).firstMatch.exists)
        shot("Trailer focused, no YouTube app")
        remote.press(.select)
        let done = app.buttons["trailer-code-done"]
        XCTAssertTrue(done.waitForExistence(timeout: 10), "the code to scan")
        XCTAssertTrue(app.staticTexts["Watch the trailer on your iPhone"].exists)
        XCTAssertTrue(focused(done), "Done holds the focus")
        shot("Trailer code to scan")
        remote.press(.menu)
        XCTAssertTrue(done.waitForNonExistence(timeout: 5), "Menu closes it")
        XCTAssertTrue(focused(trailer), "back on Trailer")
        remote.press(.select)
        XCTAssertTrue(done.waitForExistence(timeout: 10))
        remote.press(.select)
        XCTAssertTrue(done.waitForNonExistence(timeout: 5), "Done closes it")
    }

    @MainActor
    func testATrailerFilePlaysInThePlayer() {
        let app = launch(movie: 501)
        let trailer = app.buttons["movie-trailer"]
        XCTAssertTrue(trailer.waitForExistence(timeout: 20))
        XCTAssertTrue(app.buttons["Play from start"].exists, "a movie partway through")
        XCTAssertTrue(focus(trailer), "Right from Resume and Play from start reaches Trailer")
        XCTAssertFalse(app.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", "YouTube")).firstMatch.exists, "a file says nothing about YouTube")
        shot("Trailer focused, a trailer file")
        remote.press(.select)
        XCTAssertTrue(app.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", "Trailer")).firstMatch.waitForExistence(timeout: 20), "the player names it")
        XCTAssertFalse(app.buttons["trailer-code-done"].exists)
    }

    @MainActor
    private func launch(movie: Int) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-open", "watchmymedia://movies/\(movie)"]
        app.launch()
        return app
    }

    /// Down from the tab bar into the button row (to the button under the
    /// focused tab), then left or right along it until `element` has focus.
    @MainActor
    private func focus(_ element: XCUIElement) -> Bool {
        let app = XCUIApplication()
        remote.press(.down)
        let inRow = app.buttons.matching(NSPredicate(format: "hasFocus == true"))
        for _ in 0..<6 where !element.hasFocus {
            guard let current = inRow.allElementsBoundByIndex.first else {
                _ = focused(element, timeout: 1)
                continue
            }
            remote.press(current.frame.minX > element.frame.minX ? .left : .right)
            _ = focused(element, timeout: 1)
        }
        return element.hasFocus
    }

    @MainActor
    private func focused(_ element: XCUIElement, timeout: TimeInterval = 5) -> Bool {
        let expectation = XCTNSPredicateExpectation(predicate: NSPredicate(format: "hasFocus == true"), object: element)
        return XCTWaiter().wait(for: [expectation], timeout: timeout) == .completed
    }

    @MainActor
    private func shot(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
