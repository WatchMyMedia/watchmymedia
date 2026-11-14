import XCTest

/// Favourites, search and reminders on Apple TV (PLAN-LIVE-TV-GUIDE.md G9,
/// TvLiveGuideExtras, TvLiveSearch, TvLiveReminder) on the fakes, driven with
/// the Siri Remote. Each step keeps a screenshot in the result bundle.
final class LiveExtrasTests: XCTestCase {
    @MainActor private var remote: XCUIRemote { XCUIRemote.shared }

    override func setUp() async throws { continueAfterFailure = false }

    @MainActor
    private func launch(_ extra: [String] = []) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-fakesLiveGuide"] + extra
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
    private func label(_ element: XCUIElement, contains text: String, timeout: TimeInterval = 10) -> Bool {
        element.waitForLabel(containing: text, timeout: timeout)
    }

    @MainActor
    private func any(_ app: XCUIApplication, _ text: String) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", text)).firstMatch
    }

    /// Down from the tab bar to the guide's first programme.
    @MainActor
    private func focusFirstCell(_ app: XCUIApplication) -> XCUIElement {
        let first = app.buttons["live-cell-2.1-Quiz Parlor"]
        XCTAssertTrue(first.waitUntilExists(timeout: 20))
        for _ in 0..<5 where !first.hasFocus { remote.press(.down) }
        XCTAssertTrue(first.hasFocus, "Down reaches the first channel's programme")
        return first
    }

    @MainActor
    private func longPress() { remote.press(.select, forDuration: 1.5) }

    /// A context menu's item: not a button to XCUI.
    @MainActor
    private func menuItem(_ app: XCUIApplication, _ text: String) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", text)).firstMatch
    }

    @MainActor
    func testALongPressRemindsAndTheHeroSaysSo() {
        let app = launch(["-tab", "liveTV"])
        _ = focusFirstCell(app)
        XCTAssertTrue(app.buttons["live-guide-reminders"].exists, "The chips have Reminders")
        XCTAssertTrue(app.buttons["live-filter-favourites"].exists, "and Favourites")
        let hero = app.staticTexts["live-hero-title"]
        let dash = app.buttons["live-cell-2.1-Dollar Dash"]
        remote.press(.right)
        XCTAssertTrue(dash.hasFocus)
        XCTAssertTrue(label(hero, contains: "Dollar Dash"))
        XCTAssertTrue(dash.label.contains("reminder set"), dash.label)
        XCTAssertTrue(any(app, "Reminder set · 5 min before").waitUntilExists(timeout: 5), "The hero says the reminder is set")
        XCTAssertTrue(any(app, "Hold · Remove reminder").exists)
        snap("tv-guide-reminded")
        let football = app.buttons["live-cell-4.1-Conference Football"]
        // Down three rows to 4.1, then right past The Sleuth Files.
        for _ in 0..<3 { remote.press(.down) }
        for _ in 0..<2 where !football.hasFocus { remote.press(.right) }
        XCTAssertTrue(football.hasFocus, "Conference Football at 9:00 on 4.1")
        XCTAssertTrue(any(app, "Hold · Remind me").waitUntilExists(timeout: 5))
        snap("tv-guide-later")
        longPress()
        let remind = menuItem(app, "Remind me")
        XCTAssertTrue(remind.waitUntilExists(timeout: 5), "A long press offers Remind me")
        snap("tv-menu-programme")
        remote.press(.select)
        XCTAssertTrue(label(football, contains: "reminder set", timeout: 10), football.label)
        XCTAssertTrue(any(app, "Reminder set · 5 min before").waitUntilExists(timeout: 5))
        snap("tv-guide-reminder-set")
        app.terminate()
    }

    @MainActor
    func testAChannelsLongPressStarsIt() {
        let app = launch(["-tab", "liveTV"])
        _ = focusFirstCell(app)
        let channel = app.buttons["live-channel-2.1"]
        remote.press(.left)
        XCTAssertTrue(channel.hasFocus, "Left from the first programme reaches the channel")
        XCTAssertTrue(channel.label.contains("favourite"), channel.label)
        XCTAssertTrue(any(app, "Hold · Remove from Favourites").waitUntilExists(timeout: 5))
        longPress()
        let remove = menuItem(app, "Remove from Favourites")
        XCTAssertTrue(remove.waitUntilExists(timeout: 5))
        snap("tv-menu-channel")
        remote.press(.select)
        XCTAssertTrue(channel.wait(until: NSPredicate(format: "NOT (label CONTAINS 'favourite')"), timeout: 10), channel.label)
        app.terminate()
    }

    @MainActor
    func testTheRemindersListShowsAGoneOneAsKept() {
        let app = launch(["-tab", "liveTV", "-fakesLiveReminder", "gone"])
        _ = focusFirstCell(app)
        let chip = app.buttons["live-guide-reminders"]
        remote.press(.up)
        for _ in 0..<16 where !chip.hasFocus { remote.press(.right) }
        XCTAssertTrue(chip.hasFocus, "Right along the chips reaches Reminders")
        remote.press(.select)
        let gone = app.buttons["live-reminder-Conference Football"]
        XCTAssertTrue(gone.waitUntilExists(timeout: 10))
        XCTAssertTrue(gone.label.contains("No longer in the listings"), gone.label)
        snap("tv-reminders")
        // Near midnight this reminder is in Tomorrow's column, beside Today.
        // Follow the visible rows and columns instead of assuming it is below.
        for _ in 0..<12 where !gone.hasFocus {
            let focused = app.buttons.matching(NSPredicate(format: "hasFocus == true")).firstMatch
            XCTAssertTrue(focused.waitUntilExists(timeout: 5), "A reminder or its Remove action has focus")
            let current = focused.frame, target = gone.frame
            if target.minX >= current.maxX { remote.press(.right) }
            else if target.maxX <= current.minX { remote.press(.left) }
            else if target.midY > current.midY { remote.press(.down) }
            else { remote.press(.up) }
        }
        XCTAssertTrue(gone.hasFocus, "The remote reaches the gone reminder across day columns")
        remote.press(.select)
        XCTAssertTrue(any(app, "Was ").waitUntilExists(timeout: 10), "A gone reminder shows as it was kept")
        XCTAssertTrue(app.buttons["live-reminder-remove"].exists)
        snap("tv-reminder-gone")
        app.terminate()
    }

    @MainActor
    func testTheBannerComesOverHomeWithoutTakingFocus() {
        // The reminder comes 8 s after launch, once Home is up, rather than the
        // fakes' default 20 s. On a busy simulator one look at the screen took
        // 8 s, and a 10 s banner had gone between finding it and reading its
        // label. So the look that finds the banner also reads its label and
        // focus, from one snapshot, and the banner stays 15 s: long enough for
        // a look to start and finish while it is up, and a 15 s wait for it to
        // go rather than 30.
        let app = launch(["-fakesLiveReminderSoon", "8", "-liveBannerSeconds", "15"])
        let banner = app.descendants(matching: .any)["live-reminder-banner"]
        let says = "Pro Hockey starts in 5 minutes on 9.1 PNVL-HD. Watch it from Live TV."
        XCTAssertTrue(banner.wait(until: NSPredicate(format: "label CONTAINS %@ AND hasFocus == false", says), timeout: 45),
                      "The reminder's banner comes at its time, says what is on and where, and takes no focus: \(banner.lookedAt?.label ?? "no banner")")
        snap("tv-banner-home")
        // Looks every quarter second; an XCTNSPredicateExpectation looked only
        // every 6 s here.
        XCTAssertTrue(banner.waitUntilGone(timeout: 45), "It goes by itself")
        app.terminate()
    }

    @MainActor
    func testMenuPutsThePlayersBannerAwayBeforeLeaving() {
        // 10 s: the channel is playing before the reminder comes.
        // A 30 s banner, so it is Menu that puts it away and not its own time.
        let app = launch(["-fakesLiveReminderSoon", "10", "-liveBannerSeconds", "30", "-open", "watchmymedia://live-tv/ch-7.1"])
        let player = app.descendants(matching: .any)["live-player-screen"]
        let banner = app.descendants(matching: .any)["live-reminder-banner"]
        XCTAssertTrue(banner.waitUntilExists(timeout: 45), "the reminder's banner comes over the channel")
        remote.press(.menu)
        XCTAssertTrue(banner.waitUntilGone(timeout: 5), "Menu puts the banner away")
        XCTAssertTrue(player.exists, "and stays on the channel")
        remote.press(.menu)
        XCTAssertTrue(player.waitUntilGone(timeout: 10), "the next Menu leaves Live TV")
        app.terminate()
    }

    @MainActor
    func testThePlayersChannelsPutTheDueReminderFirst() {
        // 12 s: the channel plays and its Channels panel is open before the
        // reminder comes, so the banner is the player's.
        let app = launch(["-fakesLiveReminderSoon", "12", "-liveBannerSeconds", "30", "-open", "watchmymedia://live-tv/ch-7.1", "-livePanel", "channels"])
        let banner = app.descendants(matching: .any)["live-reminder-banner"]
        XCTAssertTrue(banner.waitUntilExists(timeout: 45))
        XCTAssertTrue(banner.label.contains("Swipe down to watch."), banner.label)
        sleep(1)
        snap("tv-banner-player")
        let card = app.buttons["live-channel-option-ch-9.1"]
        XCTAssertTrue(card.waitUntilExists(timeout: 10))
        XCTAssertTrue(label(card, contains: "Pro Hockey starts at", timeout: 20), card.label)
        let first = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'live-channel-option-'")).firstMatch
        XCTAssertEqual(first.identifier, "live-channel-option-ch-9.1", "The Channels panel puts the reminder first")
        snap("tv-panel-reminder")
        app.terminate()
    }

    @MainActor
    func testSearchShowsOnLiveTVAfterTheLibrary() {
        let app = launch(["-tab", "search", "-search", "harbor"])
        let glass = app.buttons["live-search-20.1-The Glass Harbor"].firstMatch
        XCTAssertTrue(glass.waitUntilExists(timeout: 20), "On Live TV follows the library's rows")
        XCTAssertTrue(glass.label.contains("on now on 20.1 Cinema Vault"), glass.label)
        snap("tv-search")
        app.terminate()
        let none = launch(["-tab", "search", "-search", "meridian"])
        XCTAssertTrue(none.descendants(matching: .any)["live-search-none"].waitUntilExists(timeout: 20))
        snap("tv-search-none")
        none.terminate()
    }

    @MainActor
    func testSettingsHasTheLeadTime() {
        let app = launch(["-tab", "settings"])
        XCTAssertTrue(app.buttons["live-lead-5"].waitUntilExists(timeout: 20), "Settings › Live TV has Remind me")
        XCTAssertTrue(app.buttons["settings-live-reminders"].exists)
        snap("tv-settings")
        app.terminate()
    }

    @MainActor
    func testOlderServersKeepG5sGuide() {
        let app = launch(["-tab", "liveTV", "-fakesLiveGuideNoExtras"])
        XCTAssertTrue(app.buttons["live-cell-2.1-Quiz Parlor"].waitUntilExists(timeout: 20))
        XCTAssertFalse(app.buttons["live-guide-reminders"].exists)
        XCTAssertFalse(app.buttons["live-filter-favourites"].exists)
        XCTAssertFalse(app.buttons["live-channel-2.1"].exists)
        app.terminate()
    }
}
