import XCTest
import UIKit

/// Trailer on the movie page (PLAN-TRAILERS.md §6.3), on the fakes: Dead
/// Reckoning Bay has a YouTube trailer, The Paper Kite a trailer file. The
/// YouTube player needs the network; the screen around it doesn't.
final class TrailerTests: XCTestCase {
    override func setUp() async throws { continueAfterFailure = false }

    @MainActor
    func testAYouTubeTrailerOpensYouTubesPlayerUnderOurBarAndCloses() throws {
        let app = launch(movie: 500)
        let trailer = app.buttons["movie-trailer"]
        XCTAssertTrue(trailer.waitForExistence(timeout: 20))
        shot("Movie page with Trailer")
        landscapeOnIPad()
        trailer.tap()
        XCTAssertTrue(app.buttons["trailer-close"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["Official Trailer"].exists || app.otherElements["Official Trailer, Dead Reckoning Bay"].exists)
        XCTAssertTrue(app.buttons["YouTube Terms of Service"].exists)
        XCTAssertTrue(app.webViews.firstMatch.waitForExistence(timeout: 10))
        // Long enough for YouTube's player to load and start.
        _ = app.staticTexts["never"].waitForExistence(timeout: 8)
        shot("YouTube trailer playing")
        app.buttons["trailer-close"].tap()
        XCTAssertTrue(trailer.waitForExistence(timeout: 10))
        XCTAssertFalse(app.buttons["trailer-close"].exists)
    }

    /// Two trailers, each reporting error 150 once YouTube's player is ready
    /// (`-trailerError`, debug builds): the second is tried, then the screen
    /// offers the first in YouTube. TRAILER_REFUSED_KEYS
    /// (TEST_RUNNER_TRAILER_REFUSED_KEYS to xcodebuild) tries real videos
    /// whose owners keep them on YouTube instead.
    @MainActor
    func testATrailerItsOwnerKeepsOnYouTubeOffersItInYouTube() throws {
        let keys = ProcessInfo.processInfo.environment["TRAILER_REFUSED_KEYS"].flatMap { $0.isEmpty ? nil : $0 }
        let app = launch(movie: 500, extra: ["-fakesTrailerKeys", keys ?? "YE7VzlLtp-4,eRsGyueVLvQ"] + (keys == nil ? ["-trailerError", "150"] : []))
        XCTAssertTrue(app.buttons["movie-trailer"].waitForExistence(timeout: 20))
        landscapeOnIPad()
        app.buttons["movie-trailer"].firstMatch.tap()
        XCTAssertTrue(app.buttons["trailer-close"].waitForExistence(timeout: 10))
        let open = app.buttons["trailer-open-youtube"]
        XCTAssertTrue(open.waitForExistence(timeout: 40), "every trailer refused")
        XCTAssertTrue(app.staticTexts["This trailer can’t play here"].exists)
        XCTAssertTrue(app.staticTexts["Trailer 1"].exists || app.otherElements["Trailer 1, Dead Reckoning Bay"].exists, "it offers the first")
        shot("Trailer refused")
        open.tap()
        // No YouTube app on a simulator: the in-app Safari, with its Close.
        XCTAssertTrue(app.buttons["Close"].waitForExistence(timeout: 10))
        _ = app.staticTexts["never"].waitForExistence(timeout: 4)
        shot("Trailer in the in-app Safari")
    }

    @MainActor
    func testATrailerFilePlaysInThePlayer() throws {
        let app = launch(movie: 501, extra: ["-playerKeepControls"])
        let trailer = app.buttons["movie-trailer"]
        XCTAssertTrue(trailer.waitForExistence(timeout: 20))
        shot("Movie page resuming, with Trailer")
        trailer.tap()
        XCTAssertTrue(app.buttons["player-play-pause"].waitForExistence(timeout: 20))
        XCTAssertFalse(app.buttons["trailer-close"].exists)
        XCTAssertTrue(app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", "Trailer")).firstMatch.exists)
        shot("Trailer file in the player")
    }

    /// A trailer file from a server, played through its playback route. Set
    /// WMM_E2E_SERVER (with WMM_E2E_USER and WMM_E2E_PASSWORD, as for
    /// EndToEndTests) and WMM_E2E_TRAILER_MOVIE, a movie with a trailer file.
    /// The fixture's movies are unmatched, so on a scratch copy give Paper
    /// Moons a page by hand and link its trailer (`extra_of`), as
    /// apple/AGENTS.md describes for movie pages.
    @MainActor
    func testATrailerFilePlaysFromAServer() throws {
        let env = ProcessInfo.processInfo.environment
        guard let server = env["WMM_E2E_SERVER"], !server.isEmpty, let movie = env["WMM_E2E_TRAILER_MOVIE"] else {
            throw XCTSkip("Set WMM_E2E_SERVER and WMM_E2E_TRAILER_MOVIE to play a trailer file from a server.")
        }
        let app = XCUIApplication()
        addUIInterruptionMonitor(withDescription: "Local network") { alert in
            for title in ["Allow", "OK"] where alert.buttons[title].exists {
                alert.buttons[title].tap()
                return true
            }
            return false
        }
        app.launch()
        let address = app.textFields.firstMatch
        if !address.waitForExistence(timeout: 10) {
            let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
            for title in ["Allow", "OK"] where springboard.buttons[title].waitForExistence(timeout: 2) { springboard.buttons[title].tap() }
        }
        if address.waitForExistence(timeout: 10) {
            address.tap()
            address.typeText(server)
            app.buttons["Connect"].firstMatch.tap()
            let name = app.textFields["Username"]
            XCTAssertTrue(name.waitForExistence(timeout: 15))
            name.tap()
            name.typeText(env["WMM_E2E_USER"] ?? "viewer")
            let secret = app.secureTextFields["Password"]
            secret.tap()
            secret.typeText(env["WMM_E2E_PASSWORD"] ?? "fixture-viewer-1")
            app.buttons["Sign in"].firstMatch.tap()
            XCTAssertTrue(app.navigationBars["Home"].waitForExistence(timeout: 20), "signed in")
        }
        app.terminate()
        app.launchArguments = ["-open", "watchmymedia://movies/\(movie)", "-playerKeepControls"]
        app.launch()
        let trailer = app.buttons["movie-trailer"]
        XCTAssertTrue(trailer.waitForExistence(timeout: 20), "the server lists a trailer")
        shot("Server movie page with Trailer")
        trailer.tap()
        let position = app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", "Playback position")).firstMatch
        XCTAssertTrue(position.waitForExistence(timeout: 20), "the player opens")
        XCTAssertTrue(app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", "Trailer")).firstMatch.exists)
        let start = try XCTUnwrap(position.value as? String)
        let moved = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value != %@", start), object: position)
        XCTAssertEqual(XCTWaiter.wait(for: [moved], timeout: 15), .completed, "the trailer plays")
        shot("Server trailer playing")
    }

    @MainActor
    private func launch(movie: Int, extra: [String] = []) -> XCUIApplication {
        XCUIDevice.shared.orientation = .portrait
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-open", "watchmymedia://movies/\(movie)"] + extra
        app.launch()
        return app
    }

    /// The iPad boards draw the trailer screen in landscape; an iPhone turns by itself.
    @MainActor
    private func landscapeOnIPad() {
        guard UIDevice.current.userInterfaceIdiom == .pad else { return }
        XCUIDevice.shared.orientation = .landscapeLeft
        _ = XCUIApplication().staticTexts["never"].waitForExistence(timeout: 1)
    }

    @MainActor
    private func shot(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
