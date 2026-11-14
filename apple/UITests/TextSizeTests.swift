import XCTest

/// Buttons and chips follow the system's text size (PLAN-APPLE-REVIEW.md
/// §2.8): a movie's page and the Movies filters at the largest accessibility
/// size, on the fakes. The buttons grow with their text and stay on screen.
final class TextSizeTests: XCTestCase {
    override func setUp() async throws { continueAfterFailure = false }

    @MainActor private func launch(_ arguments: [String]) -> XCUIApplication {
        XCUIDevice.shared.orientation = .portrait
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"] + arguments
        app.launch()
        return app
    }

    @MainActor
    func testAMoviesButtonsGrowWithTheLargestTextAndStayOnScreen() {
        let app = launch(["-open", "watchmymedia://movies/500"])
        defer { app.terminate() }
        let play = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Play' OR label BEGINSWITH 'Resume'")).firstMatch
        XCTAssertTrue(play.waitForExistence(timeout: 20))
        let window = app.windows.firstMatch.frame
        XCTAssertGreaterThan(play.frame.height, 50, "Play is taller than its 50 pt at the default size")
        XCTAssertGreaterThanOrEqual(play.frame.minX, window.minX)
        XCTAssertLessThanOrEqual(play.frame.maxX, window.maxX)
        attach(app, "Movie page at accessibility XXXL")
    }

    @MainActor
    func testTheLibraryChipsGrowWithTheLargestText() {
        let app = launch(["-tab", "movies"])
        defer { app.terminate() }
        let chip = app.buttons["All"].firstMatch
        XCTAssertTrue(chip.waitForExistence(timeout: 20))
        XCTAssertGreaterThan(chip.frame.height, 44, "A chip is drawn 34 pt tall, tappable across 44, at the default size")
        attach(app, "Movies filters at accessibility XXXL")
    }

    @MainActor private func attach(_ app: XCUIApplication, _ name: String) {
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = name
        shot.lifetime = .keepAlways
        add(shot)
    }
}

