import XCTest

/// Favourites, search and reminders (PLAN-LIVE-TV-GUIDE.md G9) on the fakes'
/// fictional listings, with the extras boards' five favourites and reminders.
/// Each step keeps a screenshot in the result bundle, to compare with the boards.
final class LiveExtrasTests: XCTestCase {
    override func setUp() async throws { continueAfterFailure = false }

    @MainActor
    private func launch(_ extra: [String] = [], orientation: UIDeviceOrientation = .portrait) -> XCUIApplication {
        XCUIDevice.shared.orientation = orientation
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-fakesLiveGuide", "-tab", "liveTV"] + extra
        app.launch()
        return app
    }

    @MainActor
    private func snap(_ name: String) {
        let shot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        shot.name = name
        shot.lifetime = .keepAlways
        add(shot)
    }

    @MainActor
    private func any(_ app: XCUIApplication, _ text: String) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", text)).firstMatch
    }

    private var pad: Bool { UIDevice.current.userInterfaceIdiom == .pad }

    /// Waits until `element` matches `predicate`.
    @MainActor
    private func becomes(_ element: XCUIElement, _ predicate: String, timeout: TimeInterval = 10) -> Bool {
        let expectation = XCTNSPredicateExpectation(predicate: NSPredicate(format: predicate), object: element)
        return XCTWaiter().wait(for: [expectation], timeout: timeout) == .completed
    }

    /// Waits until `element` is gone.
    @MainActor
    private func gone(_ element: XCUIElement, timeout: TimeInterval = 10) -> Bool {
        becomes(element, "exists == false", timeout: timeout)
    }

    @MainActor
    func testAStarAndTheFavouritesChip() throws {
        let app = launch()
        defer { app.terminate() }
        let star = app.buttons["live-star-9.1"]
        XCTAssertTrue(star.waitForExistence(timeout: 20), "Each channel has a star")
        XCTAssertEqual(star.label, "Add 9.1 to Favourites")
        XCTAssertEqual(app.buttons["live-star-2.1"].label, "Remove 2.1 from Favourites")
        snap("guide")
        star.tap()
        XCTAssertTrue(app.buttons["Remove 9.1 from Favourites"].waitForExistence(timeout: 5))
        let chip = app.buttons["live-filter-favourites"]
        XCTAssertTrue(chip.exists, "Favourites comes first once there is one")
        chip.tap()
        // Favourites with listings and without: 4.3 Coast Shop has none.
        XCTAssertTrue(any(app, "4.3 Coast Shop, No listings").waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["live-star-2.2"].exists, "Only favourites show")
        snap("favourites")
        app.buttons["live-star-9.1"].tap()
        XCTAssertTrue(app.buttons["Add 9.1 to Favourites"].waitForExistence(timeout: 5) || !app.buttons["live-star-9.1"].exists)
    }

    @MainActor
    func testRemindMeExplainsFirstAndNotNowKeepsTheReminder() throws {
        try XCTSkipIf(pad, "The iPad shows details in its panel; the search and panel tests cover it")
        let app = launch()
        defer { app.terminate() }
        let later = app.buttons["Later"]
        XCTAssertTrue(later.waitForExistence(timeout: 20))
        later.tap()
        let football = app.buttons["live-cell-4.1-Conference Football"]
        XCTAssertTrue(football.waitForExistence(timeout: 10))
        football.tap()
        let remind = app.buttons["live-details-remind"]
        XCTAssertTrue(remind.waitForExistence(timeout: 10), "A later programme offers Remind me")
        XCTAssertTrue(app.buttons["live-star-4.1"].exists, "The details have the channel's star")
        snap("details-later")
        remind.tap()
        let notNow = app.buttons["live-reminder-ask-not-now"]
        if notNow.waitForExistence(timeout: 5) {
            XCTAssertTrue(app.staticTexts["Remind you on this iPhone?"].exists)
            snap("permission")
            notNow.tap()
        }
        let status = app.otherElements["live-reminder-status"]
        XCTAssertTrue(any(app, "Reminder set · 5 min before").waitForExistence(timeout: 10), "The reminder is kept either way")
        XCTAssertTrue(status.exists || any(app, "On this iPhone and your other devices").exists)
        snap("details-reminder-set")
        app.buttons["live-reminder-remove"].firstMatch.tap()
        XCTAssertTrue(remind.waitForExistence(timeout: 10), "Remove brings Remind me back")
        remind.tap()
        XCTAssertTrue(any(app, "Reminder set · 5 min before").waitForExistence(timeout: 10), "Not now isn't asked again at once")
        app.buttons["Close"].firstMatch.tap()
        XCTAssertTrue(app.buttons["live-card-reminder-set"].waitForExistence(timeout: 10), "The card says it is set")
        XCTAssertTrue(app.buttons["live-cell-4.1-Conference Football"].label.contains("reminder set"))
        snap("card-reminder-set")
    }

    @MainActor
    func testTheRemindersListAndAGoneReminder() throws {
        try XCTSkipIf(pad, "The iPad's Reminders are in its panel")
        let app = launch(["-fakesLiveReminder", "gone"])
        defer { app.terminate() }
        let bell = app.buttons["live-guide-reminders"]
        XCTAssertTrue(bell.waitForExistence(timeout: 20))
        bell.tap()
        let gone = app.buttons["live-reminder-Conference Football"]
        XCTAssertTrue(gone.waitForExistence(timeout: 10))
        XCTAssertTrue(gone.label.contains("No longer in the listings"), gone.label)
        XCTAssertTrue(any(app, "Each comes 5 min before it starts").exists)
        snap("reminders")
        gone.tap()
        XCTAssertTrue(any(app, "Was ").waitForExistence(timeout: 10), "A gone reminder shows as it was kept")
        XCTAssertTrue(any(app, "No longer in the listings").exists)
        snap("reminder-gone")
        app.buttons["live-reminder-remove"].firstMatch.tap()
        XCTAssertTrue(app.otherElements["live-reminders"].waitForExistence(timeout: 10) || app.scrollViews.firstMatch.exists)
        XCTAssertFalse(app.buttons["live-reminder-Conference Football"].waitForExistence(timeout: 3), "Remove takes it off the list")
        // A reminder that is still on opens the guide at it.
        let station = app.buttons["live-reminder-Station 9"]
        XCTAssertTrue(station.waitForExistence(timeout: 5))
        station.tap()
        XCTAssertTrue(app.buttons["live-selected-card"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["live-selected-card"].label.contains("Station 9"), app.buttons["live-selected-card"].label)
    }

    @MainActor
    func testSearchFindsListingsOnTheSearchTabAndFromTheGuide() throws {
        // On iPad the panel beside the grid needs the landscape width.
        let app = launch(orientation: pad ? .landscapeLeft : .portrait)
        defer { app.terminate(); XCUIDevice.shared.orientation = .portrait }
        XCTAssertTrue(app.buttons["live-guide-search"].waitForExistence(timeout: 20))
        if pad {
            app.buttons["live-guide-search"].tap()
            let field = app.textFields["live-search-field"]
            XCTAssertTrue(field.waitForExistence(timeout: 10))
            field.tap(); field.typeText("harbor")
            XCTAssertTrue(app.buttons["live-search-20.1-The Glass Harbor"].firstMatch.waitForExistence(timeout: 10))
            snap("pad-search")
            app.buttons["live-search-7.1-Station 9"].firstMatch.tap()
            XCTAssertTrue(app.staticTexts["live-details-title"].waitForExistence(timeout: 10))
            XCTAssertEqual(app.staticTexts["live-details-title"].label, "Station 9")
            snap("pad-search-shown")
            app.buttons["live-guide-reminders"].tap()
            XCTAssertTrue(app.buttons["live-reminder-Dollar Dash"].waitForExistence(timeout: 10))
            snap("pad-reminders")
            return
        }
        app.buttons["live-guide-search"].tap()
        let field = app.searchFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 10))
        field.typeText("meridian")
        XCTAssertTrue(app.staticTexts["Nothing in the listings"].waitForExistence(timeout: 10), "From the guide, only listings")
        snap("guide-search-none")
        field.buttons.firstMatch.tap()
        field.typeText("harbor")
        let glass = app.buttons["live-search-20.1-The Glass Harbor"].firstMatch
        XCTAssertTrue(glass.waitForExistence(timeout: 10))
        XCTAssertTrue(glass.label.contains("on now on 20.1 Cinema Vault"), glass.label)
        XCTAssertTrue(app.buttons["Watch 20.1"].exists, "What is on has Watch")
        snap("guide-search")

        // The Search tab, from a launch straight onto it. Reaching it by the tab bar
        // meant putting the guide's keyboard away first, and no key or button
        // did that on every iPhone (an iPhone 18 Pro kept it up, 2026-11-13).
        app.terminate()
        app.launchArguments = ["-fakes", "-fakesLiveGuide", "-tab", "search"]
        app.launch()
        let searchTab = app.tabBars.buttons["Search"]
        XCTAssertTrue(searchTab.waitForExistence(timeout: 20))
        XCTAssertTrue(becomes(searchTab, "isSelected == true"), "the Search tab is chosen")
        // The Search tab's own field, by its prompt: the guide's may still be in the tree.
        let tabField = app.searchFields.matching(NSPredicate(format: "placeholderValue == %@", "Shows, movies, episodes, Live TV")).firstMatch
        XCTAssertTrue(tabField.waitForExistence(timeout: 10))
        tabField.tap(); tabField.typeText("harbor")
        let station = app.buttons["live-search-7.1-Station 9"].firstMatch
        XCTAssertTrue(station.waitForExistence(timeout: 10), "On Live TV follows the library's groups")
        snap("search-tab")
        station.tap()
        XCTAssertTrue(app.buttons["live-selected-card"].waitForExistence(timeout: 10), "Choosing one opens the guide at it")
        XCTAssertTrue(app.buttons["live-selected-card"].label.contains("Station 9"), app.buttons["live-selected-card"].label)
        snap("search-shown")
    }

    @MainActor
    func testTheIPadPanelShowsAReminderAsKept() throws {
        try XCTSkipUnless(pad, "The panel is the iPad's")
        XCUIDevice.shared.orientation = .landscapeLeft
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-fakesLiveGuide", "-tab", "liveTV", "-fakesLiveReminder", "gone"]
        app.launch()
        defer { app.terminate(); XCUIDevice.shared.orientation = .portrait }
        XCTAssertTrue(app.buttons["live-guide-reminders"].waitForExistence(timeout: 20))
        snap("pad-guide")
        app.buttons["live-guide-reminders"].tap()
        let gone = app.buttons["live-reminder-Conference Football"]
        XCTAssertTrue(gone.waitForExistence(timeout: 10))
        gone.tap()
        XCTAssertTrue(any(app, "Kept as you set it. The listings no longer have it on 4.1.").waitForExistence(timeout: 10))
        snap("pad-gone")
        XCUIDevice.shared.orientation = .portrait
        snap("pad-portrait")
    }

    @MainActor
    func testSettingsLiveTVSetsTheLeadTime() throws {
        try XCTSkipIf(pad, "The iPad's Settings is a split view")
        let app = launch()
        defer { app.terminate() }
        let profile = app.buttons["Settings"].firstMatch
        XCTAssertTrue(profile.waitForExistence(timeout: 20))
        profile.tap()
        let row = app.buttons["settings-liveTV"]
        XCTAssertTrue(row.waitForExistence(timeout: 10))
        XCTAssertTrue(row.label.contains("5 minutes before"), row.label)
        row.tap()
        let ten = app.buttons["live-lead-10"]
        XCTAssertTrue(ten.waitForExistence(timeout: 10))
        ten.tap()
        XCTAssertTrue(app.buttons["live-lead-10"].isSelected || any(app, "10 minutes before").exists)
        XCTAssertTrue(any(app, "Reminders").exists && any(app, "coming up").exists)
        snap("settings-live-tv")
    }

    /// A scheduled local notification arrives: the fakes' reminder whose alert comes 20 s after launch.
    @MainActor
    func testAReminderArrivesAsANotification() throws {
        try XCTSkipIf(pad, "Checked once, on iPhone")
        let app = launch(["-fakesLiveReminderSoon"])
        defer { app.terminate() }
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let profile = app.buttons["Settings"].firstMatch
        XCTAssertTrue(profile.waitForExistence(timeout: 20))
        profile.tap()
        XCTAssertTrue(app.buttons["settings-liveTV"].waitForExistence(timeout: 10))
        app.buttons["settings-liveTV"].tap()
        let notifications = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "Notifications")).firstMatch
        XCTAssertTrue(notifications.waitForExistence(timeout: 10))
        if notifications.label.contains("iOS asks") {
            notifications.tap()
            let allow = springboard.buttons["Allow"]
            if allow.waitForExistence(timeout: 10) { allow.tap() }
        }
        XCTAssertTrue(any(app, "Allowed in iOS Settings").waitForExistence(timeout: 10), notifications.label)
        let banner = springboard.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", "Pro Hockey")).firstMatch
        XCTAssertTrue(banner.waitForExistence(timeout: 60), "The reminder's notification shows")
        snap("notification")
        XCTAssertTrue(banner.label.contains("Starting in 5 min"), banner.label)
    }

    @MainActor
    func testOlderServersKeepG5sGuide() {
        let app = launch(["-fakesLiveGuideNoExtras"])
        defer { app.terminate() }
        XCTAssertTrue(app.buttons["live-cell-4.1-The Sleuth Files"].waitForExistence(timeout: 20))
        XCTAssertFalse(app.buttons["live-star-2.1"].exists)
        XCTAssertFalse(app.buttons["live-guide-search"].exists)
        XCTAssertFalse(app.buttons["live-guide-reminders"].exists)
        XCTAssertFalse(app.buttons["live-filter-favourites"].exists)
    }
}
