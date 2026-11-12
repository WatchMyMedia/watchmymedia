import XCTest
import UIKit

/// Tablet interaction checks use the fictional library and their own simulator.
final class TabletLayoutTests: XCTestCase {
    override func setUp() async throws { continueAfterFailure = false }

    @MainActor
    func testPlayerKeepsItsEpisodeAcrossRotationAndOpensEachOptionsSection() throws {
        try requireTablet()
        XCUIDevice.shared.orientation = .portrait
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-open", "watchmymedia://play/9101", "-playerKeepControls"]
        app.launch()
        XCTAssertTrue(app.staticTexts["Season 3"].waitForExistence(timeout: 20))
        XCTAssertTrue(app.buttons["Audio"].isHittable)
        shot(app, "Tablet player portrait")
        app.buttons["Quality"].tap()
        XCTAssertTrue(app.buttons["Done"].waitForExistence(timeout: 5))
        shot(app, "Tablet player quality panel")
        app.buttons["Done"].tap()
        let playPause = app.buttons["player-play-pause"]
        playPause.tap()
        waitForLabel(playPause, "Play")
        let position = app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", "Playback position")).firstMatch
        let pausedPosition = try XCTUnwrap(position.value as? String)
        XCUIDevice.shared.orientation = .landscapeLeft
        waitForOrientation(app, portrait: false)
        waitForLabel(playPause, "Play")
        XCTAssertEqual(position.value as? String, pausedPosition, "rotation does not reset the playback position")
        playPause.tap()
        XCTAssertTrue(app.buttons["Episodes"].waitForExistence(timeout: 10))
        XCTAssertTrue(playPause.isHittable)
        XCTAssertEqual(playPause.label, "Pause")
        shot(app, "Tablet player landscape")
        app.buttons["Episodes"].tap()
        XCTAssertTrue(app.staticTexts["Season 3"].waitForExistence(timeout: 5))
        app.buttons["Done"].tap()
        for title in ["Audio", "Subtitles", "Quality"] {
            app.buttons[title].tap()
            XCTAssertTrue(app.buttons["Done"].waitForExistence(timeout: 5))
            app.buttons["Done"].tap()
        }
        XCUIDevice.shared.orientation = .portrait
        waitForOrientation(app, portrait: true)
        XCTAssertTrue(app.staticTexts["Season 3"].waitForExistence(timeout: 10))
        // Closing Episodes restores the ordinary hide timer. Rotation may
        // finish after it expires, so reveal controls before checking playback.
        if !playPause.exists {
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.2)).tap()
        }
        XCTAssertTrue(playPause.waitForExistence(timeout: 5))
        XCTAssertEqual(playPause.label, "Pause")
        app.buttons["Episode 1, Low Water"].tap()
        XCTAssertTrue(app.staticTexts["S03 E01 · Low Water"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["Episode 1, Low Water, playing"].exists)
        shot(app, "Tablet player selected episode")
        app.terminate()
    }

    @MainActor
    func testTabletLibraryFiltersAndDetailRemainUsableInBothOrientations() throws {
        try requireTablet()
        XCUIDevice.shared.orientation = .landscapeLeft
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-tab", "tvShows"]
        app.launch()
        waitForOrientation(app, portrait: false)
        XCTAssertTrue(app.buttons["Clear filters"].waitForExistence(timeout: 20))
        shot(app, "Tablet library landscape")
        XCUIDevice.shared.orientation = .portrait
        waitForOrientation(app, portrait: true)
        let filters = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Filters")).firstMatch
        XCTAssertTrue(filters.waitForExistence(timeout: 10))
        // A 13-inch iPad is still over the two-pane threshold in portrait.
        if !app.buttons["Clear filters"].exists { filters.tap() }
        XCTAssertTrue(app.buttons["Clear filters"].waitForExistence(timeout: 5))
        shot(app, "Tablet library portrait filters")
        app.terminate()

        XCUIDevice.shared.orientation = .landscapeLeft
        app.launchArguments = ["-fakes", "-open", "watchmymedia://shows/100?season=3"]
        app.launch()
        waitForOrientation(app, portrait: false)
        XCTAssertTrue(app.staticTexts["Harbor Lights"].firstMatch.waitForExistence(timeout: 20))
        shot(app, "Tablet show landscape")
        XCUIDevice.shared.orientation = .portrait
        waitForOrientation(app, portrait: true)
        XCTAssertTrue(app.staticTexts["Harbor Lights"].firstMatch.waitForExistence(timeout: 10))
        shot(app, "Tablet show portrait")
        app.terminate()
    }

    @MainActor
    func testTheRowPutsAnEpisodeOnTopAndKeepsItAcrossRotation() throws {
        try requireTablet()
        XCUIDevice.shared.orientation = .landscapeLeft
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-open", "watchmymedia://shows/100?season=3&episode=100037"]
        app.launch()
        defer { app.terminate() }
        let next = app.buttons["episode-card-100038"]
        XCTAssertTrue(next.waitForExistence(timeout: 20))
        XCTAssertTrue(app.buttons["episode-card-100037"].isSelected, "the episode the link named is on top")
        next.tap()
        XCTAssertTrue(app.staticTexts["Squall Line"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["episode-card-100038"].isSelected)
        shot(app, "Tablet show page landscape")
        XCUIDevice.shared.orientation = .portrait
        waitForOrientation(app, portrait: true)
        XCTAssertTrue(app.staticTexts["Squall Line"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["episode-card-100038"].isSelected)
        shot(app, "Tablet show page portrait")
        app.buttons["season-tab-1"].tap()
        XCTAssertTrue(app.buttons["episode-card-100011"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["episode-card-100011"].isSelected, "a tab puts its first unfinished episode on top")
    }

    @MainActor
    func testInboxSelectionAndDecisionsKeepTheRemainingRowsUsable() throws {
        try requireTablet()
        XCUIDevice.shared.orientation = .landscapeLeft
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-fakesSeerr", "-open", "watchmymedia://inbox"]
        app.launch()
        defer { app.terminate() }
        let movie = app.buttons["inbox-request-12"]
        XCTAssertTrue(movie.waitForExistence(timeout: 20))
        movie.tap()
        XCTAssertTrue(movie.isSelected)
        XCTAssertTrue(app.staticTexts["Requests are approved in Seerr"].exists)
        shot(app, "Tablet Inbox selected request")
        app.buttons["Approve"].tap()
        XCTAssertTrue(app.buttons["Approve"].waitForNonExistence(timeout: 10))
        XCTAssertTrue(movie.isSelected, "approved requests keep their delivery status in the Inbox")
        let show = app.buttons["inbox-request-11"]
        show.tap()
        app.buttons["Decline"].tap()
        XCTAssertTrue(app.buttons["Cancel"].waitForExistence(timeout: 5))
        app.buttons["Cancel"].tap()
        app.buttons["Decline"].tap()
        XCTAssertTrue(app.buttons["Cancel"].waitForExistence(timeout: 5))
        app.buttons["Decline"].tap()
        XCTAssertTrue(show.waitForNonExistence(timeout: 10))
        XCTAssertTrue(movie.isSelected, "removing a selection falls back to the remaining request")
        app.segmentedControls.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Problems'")).firstMatch.tap()
        let problem = app.buttons["inbox-problem-22"]
        XCTAssertTrue(problem.waitForExistence(timeout: 10))
        problem.tap()
        XCTAssertTrue(problem.isSelected)
        app.buttons["Mark resolved"].tap()
        XCTAssertTrue(problem.waitForNonExistence(timeout: 10))
        XCTAssertTrue(app.buttons["inbox-problem-21"].isSelected)
        shot(app, "Tablet Inbox remaining problem")
    }

    @MainActor
    private func waitForLabel(_ element: XCUIElement, _ label: String) {
        let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label == %@", label), object: element)
        XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 10), .completed)
    }

    @MainActor
    private func waitForOrientation(_ app: XCUIApplication, portrait: Bool) {
        let ready = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            let size = app.windows.firstMatch.frame.size
            return portrait ? size.height > size.width : size.width > size.height
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 10), .completed)
    }

    @MainActor
    private func requireTablet() throws {
        guard UIDevice.current.userInterfaceIdiom == .pad else { throw XCTSkip("Tablet layouts run on an iPad simulator.") }
    }

    @MainActor
    private func shot(_ app: XCUIApplication, _ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
