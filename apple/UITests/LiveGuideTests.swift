import XCTest

/// The guide with listings (PLAN-LIVE-TV-GUIDE.md G5) on the fakes' fictional
/// listings, which put The Sleuth Files on 4.1 now, as the boards do.
final class LiveGuideTests: XCTestCase {
    override func setUp() async throws { continueAfterFailure = false }

    @MainActor
    func testACellOpensItsDetailsAndWatchesTheChannel() {
        XCUIDevice.shared.orientation = .portrait
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-fakesLiveGuide", "-tab", "liveTV", "-playerKeepControls"]
        app.launch()
        defer { app.terminate() }
        let cell = app.buttons["live-cell-4.1-The Sleuth Files"]
        XCTAssertTrue(cell.waitForExistence(timeout: 20), "The guide lists the boards' programmes")
        XCTAssertTrue(cell.label.contains("The Sleuth Files, season 2 episode 4, The Low Tide, New, HD"), cell.label)
        XCTAssertTrue(app.buttons["live-selected-card"].exists)
        XCTAssertTrue(app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", "No listings. It still plays.")).firstMatch.exists,
                      "A channel without listings still shows and plays")
        cell.tap()
        let title = app.staticTexts["live-details-title"]
        XCTAssertTrue(title.waitForExistence(timeout: 10))
        XCTAssertEqual(title.label, "The Sleuth Files")
        let watch = app.buttons["live-details-watch"]
        XCTAssertTrue(watch.exists)
        XCTAssertEqual(watch.label, "Watch 4.1")
        watch.tap()
        let banner = app.staticTexts["live-banner-title"]
        XCTAssertTrue(banner.waitForExistence(timeout: 15), "The watch screen shows what is on")
        XCTAssertEqual(banner.label, "The Sleuth Files")
        app.buttons["Stop and close live TV"].tap()
    }
}
