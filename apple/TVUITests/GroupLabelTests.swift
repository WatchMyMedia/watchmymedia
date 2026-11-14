import XCTest

/// VoiceOver reads each option of a group by its own label, with the group
/// named once (PLAN-APPLE-REVIEW.md §3.2.4): a label on a plain container went
/// to every option in it. Runs in `make apple-tv-test-sim`.
final class GroupLabelTests: XCTestCase {
    override func setUp() async throws { continueAfterFailure = false }

    @MainActor
    func testSkipAndRemindMeOptionsKeepTheirOwnLabels() {
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-fakesLiveGuide", "-tab", "settings"]
        app.launch()
        let lead = app.buttons["live-lead-5"]
        XCTAssertTrue(lead.waitUntilExists(timeout: 20), "Settings › Live TV has Remind me")
        XCTAssertEqual(lead.label, "5 min", "a lead time reads its own label, not the group's")
        let leads = app.descendants(matching: .any)["live-lead-choices"]
        XCTAssertTrue(leads.exists)
        XCTAssertEqual(leads.label, "Remind me", "the group is named once")

        let intro = app.descendants(matching: .any)["skip-intro"]
        XCTAssertTrue(intro.exists, "Skip has a control for intros")
        XCTAssertEqual(intro.label, "Skip Intro")
        XCTAssertEqual(intro.buttons.allElementsBoundByIndex.map(\.label), ["Never", "Ask", "Auto", "Auto after 3 s"], "each option reads its own label")
    }
}
