import XCTest

/// DVR controls use the fictional household schedule; no discovery or real tuner.
final class LiveRecordingTests: XCTestCase {
    override func setUp() async throws { continueAfterFailure = false }
    @MainActor private func launch(_ arguments: [String] = []) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-fakesLiveRecordings", "-open", "watchmymedia://live-tv/recordings"] + arguments
        app.launch()
        return app
    }
    @MainActor private func snapshot(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name; attachment.lifetime = .keepAlways; add(attachment)
    }
    @MainActor func testListOffersGrowingPlaybackAndManualScheduling() {
        let app = launch(); defer { app.terminate() }
        XCTAssertTrue(app.buttons["Watch from the start"].firstMatch.waitForExistence(timeout: 20))
        XCTAssertTrue(app.buttons["Watch from the start"].firstMatch.waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["Recording now"].firstMatch.exists)
        snapshot("dvr-recordings")
        app.buttons["live-new-recording"].tap()
        let name = app.textFields["manual-recording-name"]
        XCTAssertTrue(name.waitForExistence(timeout: 10))
        XCTAssertFalse(app.buttons["manual-recording-save"].isEnabled, "An unnamed recording without a channel cannot be submitted")
        snapshot("dvr-manual")
    }
    @MainActor func testGrowingRecordingUsesTheOrdinaryPlayerWithGoToNow() {
        let app = launch(); defer { app.terminate() }
        let watch = app.buttons["Watch from the start"].firstMatch
        XCTAssertTrue(watch.waitForExistence(timeout: 20)); watch.tap()
        let now = app.buttons["Go to now"].firstMatch
        XCTAssertTrue(now.waitForExistence(timeout: 10))
        snapshot("dvr-growing-player")
        now.tap()
        XCTAssertTrue(app.buttons["player-play-pause"].exists)
    }
    @MainActor func testMembersCannotStopAnotherPersonsRecording() {
        let app = launch(["-fakesMember"]); defer { app.terminate() }
        XCTAssertTrue(app.buttons["Watch from the start"].firstMatch.waitForExistence(timeout: 20))
        XCTAssertTrue(app.staticTexts["Set by Sam"].firstMatch.exists)
        XCTAssertFalse(app.buttons["Stop recording"].exists)
        snapshot("dvr-member")
    }
    @MainActor func testSwipingLeftOnAnUpcomingEpisodeSkipsIt() {
        let app = launch(); defer { app.terminate() }
        let row = app.descendants(matching: .any)["recording-dvr-upcoming"]
        XCTAssertTrue(row.waitForExistence(timeout: 20))
        // Up from the tab bar first: a swipe that starts on it changes tab.
        for _ in 0..<3 where row.frame.maxY > app.tabBars.firstMatch.frame.minY - 20 {
            app.descendants(matching: .any)["live-recordings"].firstMatch.coordinate(withNormalizedOffset: .init(dx: 0.5, dy: 0.4))
                .press(forDuration: 0.05, thenDragTo: app.coordinate(withNormalizedOffset: .init(dx: 0.5, dy: 0.15)))
        }
        let from = row.coordinate(withNormalizedOffset: .init(dx: 0.85, dy: 0.3)), to = row.coordinate(withNormalizedOffset: .init(dx: 0.15, dy: 0.3))
        from.press(forDuration: 0.05, thenDragTo: to)
        let skip = app.buttons["Skip this one"]
        XCTAssertTrue(skip.waitForExistence(timeout: 5), "A swipe reaches Don't record for an episode of a series")
        snapshot("dvr-swipe")
        skip.tap()
        XCTAssertTrue(waitForGone(row), "The skipped episode leaves Upcoming")
    }
    @MainActor func testWontRecordChoosesWhichRecordingGivesWay() {
        let app = launch(); defer { app.terminate() }
        let instead = app.buttons["Record this instead"]
        XCTAssertTrue(instead.waitForExistence(timeout: 20)); instead.tap()
        let choice = app.buttons["live-clash-instead-dvr-upcoming"]
        XCTAssertTrue(choice.waitForExistence(timeout: 10))
        XCTAssertFalse(app.buttons["live-clash-instead-dvr-now"].exists, "A recording already recording can't give way")
        XCTAssertTrue(app.staticTexts["Recording now"].exists && app.staticTexts["Set by Sam"].exists)
        XCTAssertEqual(app.buttons["live-clash-keep"].label, "Keep Inspector Vane and Station 9")
        snapshot("dvr-clash")
        choice.tap()
        let gaveWay = app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", "Won’t record: Coastline Rescue has the tuner")).firstMatch
        XCTAssertTrue(gaveWay.waitForExistence(timeout: 10), "Station 9 gave way to Coastline Rescue")
    }
    @MainActor func testTheTakeoverWarningHasOKAndDontRecordIt() {
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-fakesLiveRecordings", "-open", "watchmymedia://live-tv/ch-7.1", "-liveTakeover"]
        app.launch(); defer { app.terminate() }
        let ok = app.buttons["live-takeover-ok"]
        XCTAssertTrue(ok.waitForExistence(timeout: 20))
        XCTAssertTrue(app.buttons["live-takeover-cancel"].exists)
        XCTAssertTrue(app.staticTexts.containing(NSPredicate(format: "label ENDSWITH %@", "a recording needs this tuner. Watching stops then.")).firstMatch.exists)
        snapshot("dvr-takeover")
        ok.tap()
        XCTAssertTrue(waitForGone(ok))
    }
    @MainActor func testStoppedForARecordingOffersItFromTheStart() {
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-fakesLiveRecordings", "-open", "watchmymedia://live-tv/ch-7.1", "-liveTakenOver"]
        app.launch(); defer { app.terminate() }
        XCTAssertTrue(app.staticTexts["Stopped for a recording"].waitForExistence(timeout: 20))
        XCTAssertTrue(app.buttons["live-watch-recording"].exists)
        snapshot("dvr-stopped")
    }
    @MainActor private func waitForGone(_ element: XCUIElement, timeout: TimeInterval = 10) -> Bool {
        let gone = expectation(for: NSPredicate(format: "exists == false"), evaluatedWith: element)
        return XCTWaiter().wait(for: [gone], timeout: timeout) == .completed
    }
}
