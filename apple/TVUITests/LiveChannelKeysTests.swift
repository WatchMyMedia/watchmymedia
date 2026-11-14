import XCTest

/// The remote's channel keys on Live TV (TvLiveChannelKeys.dc.html) on the
/// fakes' channels: 2.1 Cedar One, 5.1 Northline, 5.2 Northline Extra, 8.1
/// Coast Public and 12.1 Valley News. The channel buttons on the transport
/// bar are AVKit's, which the fakes' empty player doesn't show, so the Kit's
/// TVLiveChannelKeysTests cover them; Page Up, Page Down and the 123 key reach
/// the player here as they would on a device.
final class LiveChannelKeysTests: XCTestCase {
    override func setUpWithError() throws {
        guard #available(tvOS 18.1, *) else { throw XCTSkip("The 123 key needs tvOS 18.1.") }
    }

    @MainActor private var remote: XCUIRemote { XCUIRemote.shared }

    override func setUp() async throws { continueAfterFailure = false }

    @MainActor
    private func play() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-fakesLiveTV", "-open", "watchmymedia://live-tv/live-1"]
        app.launch()
        return app
    }

    @MainActor
    private func says(_ element: XCUIElement, _ text: String, timeout: TimeInterval = 10) -> Bool {
        let expectation = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label CONTAINS %@", text), object: element)
        return XCTWaiter().wait(for: [expectation], timeout: timeout) == .completed
    }

    @MainActor
    private func focused(_ element: XCUIElement, timeout: TimeInterval = 10) -> Bool {
        let expectation = XCTNSPredicateExpectation(predicate: NSPredicate(format: "hasFocus == true"), object: element)
        return XCTWaiter().wait(for: [expectation], timeout: timeout) == .completed
    }

    @MainActor
    private func snap(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    /// Opens the keypad with the 123 key, focus on 1.
    @MainActor
    private func openKeypad(_ app: XCUIApplication) -> XCUIElement {
        XCTAssertTrue(app.staticTexts["live-player-title"].waitForExistence(timeout: 15))
        guard #available(tvOS 18.1, *) else { return app.buttons["live-goto-key-1"] }
        remote.press(.oneTwoThree)
        let one = app.buttons["live-goto-key-1"]
        XCTAssertTrue(one.waitForExistence(timeout: 10), "the 123 key opens Go to channel")
        XCTAssertTrue(focused(one), "with focus on 1")
        return one
    }

    @MainActor
    func testPageUpAndPageDownChangeChannelAndSayWhichOne() {
        let app = play()
        let title = app.staticTexts["live-player-title"]
        XCTAssertTrue(title.waitForExistence(timeout: 15))
        XCTAssertTrue(says(title, "Cedar One"))
        remote.press(.pageUp)
        XCTAssertTrue(says(title, "Northline"), title.label)
        let note = app.staticTexts["live-key-note"]
        XCTAssertTrue(note.waitForExistence(timeout: 5))
        XCTAssertEqual(note.label, "Page Up · 5.1 Northline")
        remote.press(.pageDown)
        remote.press(.pageDown)
        XCTAssertTrue(says(note, "Page Down · 12.1 Valley News"), "Page Down wraps past the first channel: \(note.label)")
        XCTAssertTrue(says(title, "Valley News"), title.label)
        snap("tv-live-page-down")
    }

    @MainActor
    func testTheKeypadTunesTheFirstMatchTwoSecondsAfterTheLastKey() {
        let app = play()
        _ = openKeypad(app)
        // 8 is under 5, under 2: down twice, right once.
        remote.press(.down); remote.press(.down); remote.press(.right)
        XCTAssertTrue(focused(app.buttons["live-goto-key-8"]))
        remote.press(.select)
        XCTAssertTrue(app.descendants(matching: .any)["live-goto"].waitForNonExistence(timeout: 6), "it tunes by itself")
        XCTAssertTrue(says(app.staticTexts["live-player-title"], "Coast Public"))
    }

    @MainActor
    func testSelectOnAMatchTunesItAtOnce() {
        let app = play()
        _ = openKeypad(app)
        // 5: down once, right once.
        remote.press(.down); remote.press(.right)
        XCTAssertTrue(focused(app.buttons["live-goto-key-5"]))
        remote.press(.select)
        // Right twice reaches the matches; focus there holds the two-second wait.
        remote.press(.right)
        remote.press(.right)
        let first = app.buttons["live-goto-match-live-2"]
        let second = app.buttons["live-goto-match-live-3"]
        XCTAssertTrue(second.waitForExistence(timeout: 5))
        XCTAssertTrue(first.exists)
        XCTAssertTrue(says(app.staticTexts["live-goto-typed"], "5"))
        for _ in 0..<2 where !second.hasFocus { remote.press(.down) }
        XCTAssertTrue(focused(second), "the matches take focus to the right of the keypad")
        sleep(3)
        XCTAssertTrue(second.exists, "nothing tuned while choosing")
        snap("tv-live-goto")
        remote.press(.select)
        XCTAssertTrue(says(app.staticTexts["live-player-title"], "Northline Extra"))
        XCTAssertFalse(app.descendants(matching: .any)["live-goto"].exists)
    }

    @MainActor
    func testBackClosesTheKeypadAndKeepsTheChannel() {
        let app = play()
        _ = openKeypad(app)
        remote.press(.menu)
        XCTAssertTrue(app.descendants(matching: .any)["live-goto"].waitForNonExistence(timeout: 5))
        XCTAssertTrue(says(app.staticTexts["live-player-title"], "Cedar One"), "nothing tuned and the player stays")
        remote.press(.menu)
        XCTAssertTrue(app.staticTexts["live-tv-title"].waitForExistence(timeout: 10), "the next Back leaves")
    }
}
