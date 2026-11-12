import XCTest
import UIKit

/// No discovery or network: these run against the deterministic Cast transport.
final class CastTests: XCTestCase {
    @MainActor
    func testTabletRemoteAdaptsToLandscapeAndPortrait() throws {
        guard UIDevice.current.userInterfaceIdiom == .pad else { throw XCTSkip("Tablet layout") }
        defer { XCUIDevice.shared.orientation = .portrait }
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-fakesCast", "-open", "watchmymedia://play/9101"]
        XCUIDevice.shared.orientation = .portrait
        app.launch()
        XCTAssertTrue(app.staticTexts["Playing on Cove TV"].waitForExistence(timeout: 20))
        XCUIDevice.shared.orientation = .landscapeLeft
        let landscapeWidth = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            app.windows.firstMatch.frame.width > 1_000
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [landscapeWidth], timeout: 10), .completed)
        XCTAssertTrue(app.buttons["cast.remote.playPause"].isHittable)
        XCTAssertTrue(app.sliders["TV volume"].isHittable)
        let landscape = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        landscape.name = "Cast remote, iPad landscape"
        landscape.lifetime = .keepAlways
        add(landscape)
        XCUIDevice.shared.orientation = .portrait
        let portraitWidth = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            app.windows.firstMatch.frame.width < 1_000
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [portraitWidth], timeout: 10), .completed)
        XCTAssertTrue(app.buttons["cast.remote.playPause"].isHittable)
        let portrait = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        portrait.name = "Cast remote, iPad portrait"
        portrait.lifetime = .keepAlways
        add(portrait)
    }

    @MainActor
    func testCastRemoteAndMiniControllerKeepTheTVPlayingAfterDone() {
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-fakesCast", "-open", "watchmymedia://play/9101"]
        app.launch()
        XCTAssertTrue(app.staticTexts["Playing on Cove TV"].waitForExistence(timeout: 20))
        XCTAssertTrue(app.buttons["cast.remote.playPause"].waitForExistence(timeout: 5))
        app.buttons["cast.remote.playPause"].tap()
        XCTAssertTrue(app.buttons.matching(identifier: "cast.remote.playPause").matching(NSPredicate(format: "label == %@", "Play on TV")).firstMatch.waitForExistence(timeout: 5))
        app.buttons["Done"].tap()
        XCTAssertTrue(app.staticTexts["On Cove TV"].waitForExistence(timeout: 10))
        app.buttons["Open Cast remote for Cove TV"].tap()
        XCTAssertTrue(app.staticTexts["Playing on Cove TV"].waitForExistence(timeout: 10))
        app.buttons["Stop casting"].tap()
        XCTAssertTrue(app.navigationBars["Home"].waitForExistence(timeout: 10))
        XCTAssertFalse(app.staticTexts["On Cove TV"].exists)
    }

    @MainActor
    func testFailedReceiverLoadOffersLocalFallback() {
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-fakesCastError", "-open", "watchmymedia://play/9101"]
        app.launch()
        XCTAssertTrue(app.buttons["Play on this device"].waitForExistence(timeout: 20))
        XCTAssertTrue(app.buttons["Try again"].exists)
        XCTAssertFalse(app.buttons["Pause on TV"].exists)
        app.buttons["Play on this device"].tap()
        XCTAssertTrue(app.buttons["player-play-pause"].waitForExistence(timeout: 10))
    }
}
