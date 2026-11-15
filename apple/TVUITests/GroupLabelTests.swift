import XCTest

/// VoiceOver reads each Settings row as its name, then its value (PLAN-APPLE-
/// REVIEW.md §3.2.4), now that Skipping and Remind me are rows that open a
/// menu (PLAN-APPLE-SETTINGS.md §3.3) rather than groups of chips. Runs in
/// `make apple-tv-test-sim`.
final class GroupLabelTests: XCTestCase {
    override func setUp() async throws { continueAfterFailure = false }

    @MainActor
    func testSkippingAndRemindMeRowsReadTheirNameAndChoice() {
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-fakesLiveGuide", "-tab", "settings", "-settingsGroup", "playback"]
        app.launch()
        let lead = app.descendants(matching: .any)["settings-row-lead"]
        XCTAssertTrue(lead.waitUntilExists(timeout: 20), "Playback has Remind me")
        XCTAssertEqual(lead.label, "Remind me")
        XCTAssertEqual(lead.value as? String, "5 minutes before")
        for (kind, name) in [("intro", "Intro"), ("recap", "Recap"), ("credits", "Credits"), ("preview", "Preview"), ("commercial", "Ads")] {
            let row = app.descendants(matching: .any)["settings-row-skip-\(kind)"]
            XCTAssertTrue(row.exists, "Skipping has a row for \(name)")
            XCTAssertEqual(row.label, name, "the row is named once, by its part")
        }
        let intro = app.descendants(matching: .any)["settings-row-skip-intro"]
        XCTAssertEqual(intro.value as? String, "Show a Skip button", "its value is its choice in the web's words")
        XCTAssertFalse(app.buttons["Never"].exists || app.buttons["Auto after 3 s"].exists, "no chips in the old words")
    }
}
