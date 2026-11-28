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

    /// From API level 35 (`-fakesDialogue`) the remote has the player's Sound
    /// and Subtitle timing: a sound change waits for the TV to play again, and
    /// the timing moves the TV's cues and saves, as the player's do.
    @MainActor
    func testTheRemoteChangesTheSoundAndTheSubtitleTimingOnTheTV() {
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-fakesDialogue", "-fakesCast", "-open", "watchmymedia://play/9101"]
        app.launch()
        XCTAssertTrue(app.staticTexts["Playing on Cove TV"].waitForExistence(timeout: 20))
        let boost = app.segmentedControls["player-sound-dialogueBoost"]
        XCTAssertTrue(boost.waitForExistence(timeout: 10), "Sound is beside the TV's audio")
        boost.buttons["Medium"].tap()
        let line = app.descendants(matching: .any)["player-sound-dialogueBoost-line"]
        XCTAssertTrue(line.label.contains("Changing the sound"), "busy while the TV plays it again: \(line.label)")
        shot("Cast remote, changing the sound")
        let settled = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label == 'Makes voices clearer over music and effects.'"), object: line)
        XCTAssertEqual(XCTWaiter.wait(for: [settled], timeout: 10), .completed)
        XCTAssertTrue(boost.buttons["Medium"].isSelected)

        XCTAssertFalse(app.buttons["player-timing-later"].exists, "no timing with subtitles off")
        app.buttons["cast.remote.subtitles"].tap()
        app.buttons["English"].firstMatch.tap()
        let later = app.buttons["player-timing-later"]
        XCTAssertTrue(later.waitForExistence(timeout: 10), "Subtitle timing once a subtitle is on")
        for _ in 0..<3 { later.tap() }
        let value = app.staticTexts["player-timing-value"]
        let moved = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label == '0.3 s later'"), object: value)
        XCTAssertEqual(XCTWaiter.wait(for: [moved], timeout: 5), .completed)
        XCTAssertTrue(app.buttons["player-timing-everyone"].exists, "Sam is an admin")
        shot("Cast remote, subtitle timing")
        app.buttons["player-timing-reset"].tap()
        let reset = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label == 'In step'"), object: value)
        XCTAssertEqual(XCTWaiter.wait(for: [reset], timeout: 5), .completed)
    }

    @MainActor
    private func shot(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
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
