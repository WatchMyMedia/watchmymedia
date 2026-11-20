import XCTest

/// Following a show on Apple TV on the fakes at API level 29
/// (`-fakesFollows`): Notify me first in About the show, with the line about
/// phones while it has focus; Settings › Notifications' list, each show's
/// panel, and Select's menu. Runs in `make apple-tv-test-sim`.
final class NewEpisodesTests: XCTestCase {
    @MainActor private var remote: XCUIRemote { XCUIRemote.shared }

    override func setUp() async throws { continueAfterFailure = false }

    @MainActor
    private func launch(_ arguments: [String]) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-fakesFollows"] + arguments
        app.launch()
        return app
    }

    @MainActor
    private func shot(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    @MainActor
    private func focusFrame(_ app: XCUIApplication) -> CGRect? {
        app.descendants(matching: .any).matching(NSPredicate(format: "hasFocus == true")).firstMatch.lookedAt?.frame
    }

    /// Whether focus is on `element` or inside it (a `Menu` row takes focus on an element inside it).
    @MainActor
    private func holds(_ element: XCUIElement, in app: XCUIApplication) -> Bool {
        guard let row = element.lookedAt else { return false }
        if row.hasFocus { return true }
        guard let focus = focusFrame(app) else { return false }
        return row.frame.contains(CGPoint(x: focus.midX, y: focus.midY))
    }

    @MainActor
    func testNotifyMeIsFirstInAboutTheShowAndFollowsAtOnce() {
        let app = launch(["-open", "watchmymedia://shows/101"])
        let bell = app.buttons["show-notify"]
        XCTAssertTrue(bell.waitUntilExists(timeout: 20), "the bell is in About the show from level 29")
        // Down from the top, past the season tabs and the row, into About the show.
        for _ in 0..<8 where !bell.hasFocus {
            let before = focusFrame(app)
            remote.press(.down)
            _ = eventually(timeout: 2) { focusFrame(app).map { $0 != before } ?? false }
        }
        XCTAssertTrue(bell.waitForFocus(timeout: 5), "About the show starts on Notify me")
        XCTAssertEqual(bell.label, "Notify me of new episodes of The Quiet Meridian")
        let line = app.staticTexts["show-notify-line"]
        XCTAssertTrue(line.waitUntilExists(timeout: 5), "the line about phones while the bell has focus")
        XCTAssertTrue(line.label.contains("Your phone or tablet notifies you. This TV shows new episodes on Home."), line.label)
        shot("Bell off, with focus")
        remote.press(.select)
        XCTAssertTrue(bell.waitForLabel(containing: "Notifications on for The Quiet Meridian. Turn them off"), bell.label)
        XCTAssertTrue(bell.hasFocus, "nothing asks first on Apple TV")
        shot("Bell on, with focus")
        remote.press(.right)
        XCTAssertTrue(line.waitUntilGone(timeout: 5), "the line goes with focus")
        shot("Bell on, focus elsewhere")
        remote.press(.left)
        XCTAssertTrue(bell.waitForFocus(timeout: 5))
        remote.press(.select)
        XCTAssertTrue(bell.waitForLabel(containing: "Notify me of new episodes of The Quiet Meridian"), "Select again stops at once")
    }

    @MainActor
    func testSettingsListsTheShowsFollowedWithGoToShowAndStopNotifying() {
        let app = launch(["-tab", "settings", "-settingsGroup", "notifications"])
        let group = app.buttons["settings-group-notifications"]
        XCTAssertTrue(group.waitUntilExists(timeout: 20))
        XCTAssertTrue(group.waitForFocus(), "launch puts focus on Notifications")
        XCTAssertTrue(group.waitForLabel(containing: "4 shows you follow"), group.label)
        let help = app.descendants(matching: .any)["settings-help"]
        XCTAssertTrue(help.waitForLabel(containing: "Your phone or tablet notifies you."), help.label)
        XCTAssertTrue(app.staticTexts["Not in the library now"].exists, "a show that left the library says so")
        shot("Notifications, the group focused")

        remote.press(.right)
        let harbor = app.descendants(matching: .any)["settings-row-follow-70100"]
        XCTAssertTrue(eventually(timeout: 10) { holds(harbor, in: app) }, "Right moves into the list, on its first show")
        XCTAssertTrue(help.waitForLabel(containing: "Select offers Go to show or Stop notifying"), help.label)
        shot("A show focused")
        remote.press(.select)
        let goTo = app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", "Go to show")).firstMatch
        XCTAssertTrue(goTo.waitUntilExists(timeout: 5), "Select opens the show's menu")
        XCTAssertTrue(app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", "Stop notifying")).firstMatch.exists)
        shot("The show's menu")
        remote.press(.menu)
        XCTAssertTrue(goTo.waitUntilGone(timeout: 5), "Menu closes the menu and keeps the show")
        XCTAssertTrue(harbor.exists)
    }
}
