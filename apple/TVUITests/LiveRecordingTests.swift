import XCTest

/// Recordings as rows on the fakes' household: Ready to watch first, with
/// what is recording now leading it; Coming up, with Won't record marked on
/// its card; and no form for a manual recording on the TV.
final class LiveRecordingTests: XCTestCase {
    @MainActor private var remote: XCUIRemote { XCUIRemote.shared }

    override func setUp() async throws { continueAfterFailure = false }

    @MainActor func testReadyToWatchLeadsAndWontRecordOpensTheChoice() {
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-fakesLiveRecordings", "-open", "watchmymedia://live-tv/recordings"]
        app.launch(); defer { app.terminate() }
        let cards = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'recording-'"))
        XCTAssertTrue(cards.firstMatch.waitUntilExists(timeout: 20))
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "Ready to watch")).firstMatch.exists)
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "Coming up")).firstMatch.exists)
        XCTAssertFalse(app.buttons["New recording"].exists)
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = "tv-dvr-recordings"; attachment.lifetime = .keepAlways; add(attachment)

        // Down to Coming up, then along it to the one that won't record.
        let wont = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'recording-' AND label CONTAINS %@", "Won’t record")).firstMatch
        XCTAssertTrue(wont.waitUntilExists(timeout: 10))
        // A link can leave focus in the tab bar: down to Ready to watch's first card, then Coming up.
        for _ in 0..<3 where !cards.firstMatch.hasFocus { app.press(.down, thenWaitForFocusToMove: 3) }
        XCTAssertTrue(cards.firstMatch.hasFocus, "Ready to watch's first card")
        app.press(.down, thenWaitForFocusToMove: 3)
        for _ in 0..<6 where !wont.hasFocus { app.press(.right, thenWaitForFocusToMove: 3) }
        XCTAssertTrue(wont.hasFocus, "Coming up has the one that won't record")
        remote.press(.select)
        let clash = app.descendants(matching: .any)["live-recording-clash"]
        XCTAssertTrue(clash.waitUntilExists(timeout: 10), "Won't record opens the choice of which to leave out")
        XCTAssertTrue(app.buttons["live-clash-keep"].waitForFocus(timeout: 5), "the one left out has focus, so a stray press changes nothing")
        let shot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        shot.name = "tv-dvr-clash"; shot.lifetime = .keepAlways; add(shot)
        remote.press(.menu)
        XCTAssertTrue(clash.waitUntilGone(timeout: 5))
    }
}
