import XCTest
import UIKit

/// Following a show and its new episodes
/// on the fakes at API level 29 (`-fakesFollows`): the bell on a
/// show's page and the explainer before iOS's question, the notifications a
/// second check posts, Settings › Notifications and Home's row. Each step
/// keeps a screenshot in the result bundle, to compare with the
/// new-episodes boards. The first test expects iOS not to have asked yet
/// (a fresh simulator) and answers Allow, so the second has notifications.
final class NewEpisodesTests: XCTestCase {
    override func setUp() async throws { continueAfterFailure = false }

    @MainActor
    private func launch(_ extra: [String]) -> XCUIApplication {
        XCUIDevice.shared.orientation = pad ? .landscapeLeft : .portrait
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-fakesFollows"] + extra
        app.launch()
        return app
    }

    private var pad: Bool { UIDevice.current.userInterfaceIdiom == .pad }

    @MainActor
    private func snap(_ name: String) {
        let shot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        shot.name = name
        shot.lifetime = .keepAlways
        add(shot)
    }

    /// Scrolls the page up until `element` can be tapped.
    @MainActor
    private func scroll(_ app: XCUIApplication, to element: XCUIElement) {
        for _ in 0..<8 where !(element.exists && element.isHittable) { app.swipeUp() }
    }

    @MainActor
    func testA_NotifyMeFollowsAtOnceThenExplainsBeforeIOSAsks() throws {
        let app = launch(["-newEpisodesReset", "-open", "watchmymedia://shows/101"])
        let bell = app.buttons["show-notify"]
        XCTAssertTrue(bell.waitForExistence(timeout: 20), "the bell is in About the show from level 29")
        scroll(app, to: bell)
        XCTAssertEqual(bell.label, "Notify me of new episodes of The Quiet Meridian")
        snap("Bell off")
        bell.tap()

        let notNow = app.buttons["new-episode-ask-not-now"]
        XCTAssertTrue(notNow.waitForExistence(timeout: 10), "the first Notify me while iOS hasn't asked explains first")
        XCTAssertTrue(app.staticTexts["Get notified of new episodes"].exists)
        snap("Explainer")
        app.buttons["new-episode-ask-continue"].tap()
        // iOS's own question, answered Allow.
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let allow = springboard.buttons["Allow"]
        if allow.waitForExistence(timeout: 10) {
            snap("iOS's question")
            allow.tap()
        }
        XCTAssertTrue(app.buttons["Notifications on for The Quiet Meridian. Turn them off"].waitForExistence(timeout: 10), "followed")
        snap("Bell on")

        // Stopping asks nothing.
        app.buttons["show-notify"].tap()
        XCTAssertTrue(app.buttons["Notify me of new episodes of The Quiet Meridian"].waitForExistence(timeout: 10))
        XCTAssertFalse(notNow.exists, "the explainer comes once")
        app.buttons["show-notify"].tap()
        XCTAssertTrue(app.buttons["Notifications on for The Quiet Meridian. Turn them off"].waitForExistence(timeout: 10))
        XCTAssertFalse(notNow.waitForExistence(timeout: 2), "the explainer comes once on this device")
        app.terminate()
    }

    /// A first check keeps the cursor; opening the app again checks after
    /// it, and the fakes' feed has two shows' arrivals.
    @MainActor
    func testB_ASecondCheckPostsOneNotificationAShow() throws {
        let first = launch(["-newEpisodesReset", "-tab", "home"])
        XCTAssertTrue(first.tabBars.firstMatch.waitForExistence(timeout: 20) || first.buttons.firstMatch.exists)
        _ = first.staticTexts["never"].waitForExistence(timeout: 4)
        first.terminate()
        let app = launch(["-tab", "home"])
        XCTAssertTrue(app.staticTexts["New from shows you follow"].waitForExistence(timeout: 20), "Home's row for followed shows")
        snap("Home with the followed row")
        // The check on opening posts them; in the app, iOS shows each as a banner.
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let banner = springboard.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", "3 new episodes · Harbor Lights")).firstMatch
        XCTAssertTrue(banner.waitForExistence(timeout: 15), "Harbor Lights' three episodes as one notification")
        XCTAssertTrue(banner.label.contains("S2 E8 to S3 E2"), banner.label)
        snap("The banner")
        banner.tap()
        // The tap opens Harbor Lights at the earliest of them, S2 E8.
        XCTAssertTrue(app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", "S02 E08")).firstMatch.waitForExistence(timeout: 15))
        snap("The tap opened the show")
        app.terminate()
    }

    /// Seven shows at once: five notifications, then "2 more shows have new
    /// episodes" listed after them, in Notification Center as iOS orders it.
    @MainActor
    func testD_PastFiveShowsTheMoreShowsNotificationListsLast() throws {
        let first = launch(["-newEpisodesReset", "-fakesManyNewEpisodes", "-tab", "home"])
        XCTAssertTrue(first.tabBars.firstMatch.waitForExistence(timeout: 20) || first.buttons.firstMatch.exists)
        _ = first.staticTexts["never"].waitForExistence(timeout: 4)
        first.terminate()
        let app = launch(["-fakesManyNewEpisodes", "-tab", "home"])
        XCTAssertTrue(app.staticTexts["New from shows you follow"].waitForExistence(timeout: 20))
        _ = app.staticTexts["never"].waitForExistence(timeout: 8)
        XCUIDevice.shared.press(.home)
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        _ = springboard.staticTexts["never"].waitForExistence(timeout: 2)
        let top = springboard.coordinate(withNormalizedOffset: CGVector(dx: 0.3, dy: 0.001))
        top.press(forDuration: 0.1, thenDragTo: springboard.coordinate(withNormalizedOffset: CGVector(dx: 0.3, dy: 0.6)))
        let cells = springboard.buttons.matching(NSPredicate(format: "identifier == 'ListCell' AND label CONTAINS 'WATCHMYMEDIA'"))
        XCTAssertTrue(cells.firstMatch.waitForExistence(timeout: 5))
        // A collapsed stack opens on a tap.
        cells.firstMatch.tap()
        _ = springboard.staticTexts["never"].waitForExistence(timeout: 2)
        snap("Seven shows in Notification Center")
        let labels = cells.allElementsBoundByIndex.sorted { $0.frame.minY < $1.frame.minY }.map(\.label)
        let more = labels.firstIndex { $0.contains("2 more shows have new episodes") }
        let harbor = labels.firstIndex { $0.contains("3 new episodes · Harbor Lights") }
        XCTAssertNotNil(more, labels.joined(separator: "\n"))
        XCTAssertNotNil(harbor, labels.joined(separator: "\n"))
        if let more, let harbor { XCTAssertGreaterThan(more, harbor, "the more shows notification lists after the shows:\n\(labels.joined(separator: "\n"))") }
        XCTAssertEqual(more, labels.count - 1, labels.joined(separator: "\n"))
        XCUIDevice.shared.press(.home)
    }

    @MainActor
    func testC_SettingsNotificationsListsTheShowsAndStopsOne() throws {
        let app = launch(["-tab", "settings"])
        let row = app.buttons["settings-notifications"]
        XCTAssertTrue(row.waitForExistence(timeout: 20), "Notifications is in the You group")
        XCTAssertTrue(row.label.contains("Notifications"))
        snap("Settings root")
        row.tap()
        let gone = app.staticTexts["Not in the library now"]
        XCTAssertTrue(gone.waitForExistence(timeout: 10), "a show that left the library says so")
        XCTAssertTrue(app.switches["notifications-new-episodes"].exists || app.otherElements["notifications-new-episodes"].exists)
        snap("Settings › Notifications")
        let stop = app.buttons["Stop notifying me of new episodes of The Tidewater Array"]
        XCTAssertTrue(stop.exists)
        stop.tap()
        XCTAssertTrue(becomes(gone, "exists == false"), "Stop notifying takes the row away")
        snap("A show stopped")
        // A swipe to the left shows Stop notifying beside the row too.
        let harborRow = app.descendants(matching: .any)["followed-70100"]
        harborRow.swipeLeft()
        _ = app.staticTexts["never"].waitForExistence(timeout: 1)
        snap("A row swiped")
        harborRow.swipeRight()
        // A row opens its show's page.
        app.staticTexts["Harbor Lights"].firstMatch.tap()
        XCTAssertTrue(app.descendants(matching: .any)["about-the-show"].waitForExistence(timeout: 10), "the row opened Harbor Lights")
        app.terminate()
    }

    @MainActor
    private func becomes(_ element: XCUIElement, _ predicate: String, timeout: TimeInterval = 10) -> Bool {
        let expectation = XCTNSPredicateExpectation(predicate: NSPredicate(format: predicate), object: element)
        return XCTWaiter().wait(for: [expectation], timeout: timeout) == .completed
    }
}
