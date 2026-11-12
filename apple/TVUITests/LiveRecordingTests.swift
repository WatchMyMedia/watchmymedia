import XCTest

final class LiveRecordingTests: XCTestCase {
    override func setUp() async throws { continueAfterFailure = false }
    @MainActor func testHouseholdRecordingsAndNoManualFormOnTV() {
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-fakesLiveRecordings", "-open", "watchmymedia://live-tv/recordings"]
        app.launch(); defer { app.terminate() }
        XCTAssertTrue(app.buttons["Watch from the start"].firstMatch.waitForExistence(timeout: 20))
        XCTAssertTrue(app.buttons["Watch from the start"].firstMatch.exists)
        XCTAssertFalse(app.buttons["New recording"].exists)
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = "tv-dvr-recordings"; attachment.lifetime = .keepAlways; add(attachment)
    }
}
