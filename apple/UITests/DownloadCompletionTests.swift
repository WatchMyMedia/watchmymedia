import XCTest
import UIKit

/// The approved offline and batch-download screens on fictional data only.
/// Real download/background transfer acceptance uses the isolated fixture.
final class DownloadCompletionTests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    @MainActor
    func testASeasonConfirmsItsTotalAndDoesNotReplaceHeldEpisodes() {
        if UIDevice.current.userInterfaceIdiom == .pad { XCUIDevice.shared.orientation = .landscapeLeft }
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-open", "watchmymedia://shows/100?season=3"]
        app.launch()
        defer { app.terminate(); XCUIDevice.shared.orientation = .portrait }
        let menu = app.buttons["season-download-menu"]
        XCTAssertTrue(menu.waitForExistence(timeout: 20))
        menu.tap()
        app.buttons["Download season"].tap()
        let summary = app.staticTexts["season-download-summary"]
        XCTAssertTrue(summary.waitForExistence(timeout: 15))
        XCTAssertTrue(summary.label.hasPrefix("7 episodes"), summary.label)
        let lower = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "480p")).firstMatch
        XCTAssertTrue(lower.exists)
        lower.tap()
        let confirm = app.buttons["download-season-confirm"]
        XCTAssertTrue(confirm.isEnabled)
        shot("Season download includes seven eligible episodes")
        confirm.tap()
        let closed = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in !confirm.exists }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [closed], timeout: 20), .completed)
        menu.tap()
        app.buttons["Download season"].tap()
        XCTAssertTrue(app.staticTexts["Every episode in your library is already downloaded or queued."].waitForExistence(timeout: 15))
        XCTAssertFalse(app.buttons["download-season-confirm"].exists)
        shot("Held and queued episodes are not replaced")
    }

    @MainActor
    func testADownloadedEpisodeOpensOfflineAndHasNoStreamingQualityControl() {
        if UIDevice.current.userInterfaceIdiom == .pad { XCUIDevice.shared.orientation = .landscapeLeft }
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-fakesOffline", "-open", "watchmymedia://shows/102?season=1&episode=102019", "-playerKeepControls"]
        app.launch()
        defer { app.terminate(); XCUIDevice.shared.orientation = .portrait }
        let offline = app.descendants(matching: .any)["episode-offline"].firstMatch
        XCTAssertTrue(offline.waitForExistence(timeout: 20))
        XCTAssertTrue(app.staticTexts["Undertow"].exists)
        XCTAssertFalse(app.buttons["More"].exists)
        XCTAssertFalse(app.buttons["All episodes"].exists)
        XCTAssertTrue(app.buttons["episode-card-102019"].isSelected)
        if UIDevice.current.userInterfaceIdiom == .pad {
            XCUIDevice.shared.orientation = .portrait
            XCTAssertTrue(offline.waitForExistence(timeout: 10))
            XCTAssertTrue(app.buttons["episode-card-102019"].exists)
            shot("Offline iPad episode retains its page at a narrower width")
            XCUIDevice.shared.orientation = .landscapeLeft
        }
        app.buttons["Mark watched"].tap()
        XCTAssertTrue(app.buttons["Mark unwatched"].waitForExistence(timeout: 5))
        shot("Downloaded episode page and queued watched mark")
        let play = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Play")).firstMatch
        XCTAssertTrue(play.exists)
        play.tap()
        let compactOptions = app.buttons["Audio and subtitles"]
        if compactOptions.waitForExistence(timeout: 5) {
            compactOptions.tap()
        } else {
            let subtitles = app.buttons["Subtitles"]
            XCTAssertTrue(subtitles.waitForExistence(timeout: 10))
            subtitles.tap()
        }
        XCTAssertTrue(app.buttons["Audio"].exists || app.staticTexts["AUDIO"].exists)
        XCTAssertTrue(app.buttons["Subtitles"].exists || app.staticTexts["SUBTITLES"].exists)
        XCTAssertFalse(app.buttons["Quality"].exists)
        XCTAssertFalse(app.staticTexts["QUALITY"].exists)
        shot("Offline playback offers only local audio and subtitles")
    }

    @MainActor
    private func shot(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
