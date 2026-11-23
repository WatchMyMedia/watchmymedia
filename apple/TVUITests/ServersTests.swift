import XCTest

/// Several servers on Apple TV, on the fakes with the Siri Remote
/// (`-fakesServers`: Sam on Living room server, Cabin server with a small
/// library of its own, and Den server, which doesn't answer). The account
/// is the tab bar's last tab: Right from Settings reaches it, its page is
/// Servers, which says who is signed in and where, Down lands on the server
/// in use, and Menu closes it for Home, with focus back in the tab bar. A
/// switch from Servers shows the banner and the other server's Home; a
/// server that doesn't answer says so on its row, with focus kept there and
/// Select trying again; Add a server runs to its last step; a server is
/// removed from its page in Settings after a question that opens on Keep;
/// signing out goes on to the next server, and the one signed out of signs
/// in again from its row in Servers; and a search result on another server
/// opens there, with Menu back to the results. `-serverNoteSeconds` keeps the banner up long enough for a look
/// at the screen on a busy simulator. Runs in `make apple-tv-test-sim`.
final class ServersTests: XCTestCase {
    @MainActor private var remote: XCUIRemote { XCUIRemote.shared }

    override func setUp() async throws { continueAfterFailure = false }

    @MainActor
    private func launch(_ arguments: [String] = []) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-fakesServers", "-serverNoteSeconds", "30"] + arguments
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

    /// Presses `button` until `element` has focus, waiting for focus to move after each press.
    @MainActor
    private func move(_ button: XCUIRemote.Button, to element: XCUIElement, in app: XCUIApplication, presses: Int = 8) -> Bool {
        for _ in 0..<presses where element.lookedAt?.hasFocus != true {
            app.press(button, thenWaitForFocusToMove: 3)
        }
        return element.waitForFocus(timeout: 5)
    }

    /// The account tab at the tab bar's end, which names the server in use.
    @MainActor
    private func accountTab(_ app: XCUIApplication, on server: String = "Living room server") -> XCUIElement {
        app.tabBars.buttons.matching(NSPredicate(format: "label CONTAINS %@", server)).firstMatch
    }

    /// The tab of the tab bar that has focus.
    @MainActor
    private func focusedTab(_ app: XCUIApplication) -> XCUIElement {
        app.tabBars.buttons.matching(NSPredicate(format: "hasFocus == true")).firstMatch
    }

    /// Up into the tab bar: Home opens on its first card, and Menu from its
    /// first screen goes to the tab bar. Waits for Home to take focus first,
    /// so Menu isn't pressed in the tab bar, where it leaves the app.
    @MainActor
    private func toTabBar(_ app: XCUIApplication) {
        _ = eventually(timeout: 10) { app.focusedButton.lookedAt != nil && self.focusedTab(app).lookedAt == nil }
        if focusedTab(app).lookedAt == nil { remote.press(.menu) }
        XCTAssertTrue(eventually(timeout: 10) { self.focusedTab(app).lookedAt != nil }, "focus is in the tab bar: \(app.focusedButton.label)")
    }

    /// Right along the tabs to the account tab at the bar's end: Servers is its page.
    @MainActor
    @discardableResult
    private func openServers(_ app: XCUIApplication) -> XCUIElement {
        let account = accountTab(app)
        XCTAssertTrue(account.waitUntilExists(timeout: 20), "the account is the tab bar's last tab")
        toTabBar(app)
        XCTAssertTrue(move(.right, to: account, in: app, presses: 10), "Right from the last tab reaches the account: \(app.focusedButton.label)")
        let panel = app.descendants(matching: .any)["servers-panel"]
        XCTAssertTrue(panel.waitUntilExists(timeout: 10), "its page is Servers")
        return account
    }

    @MainActor
    func testTheAccountTabTakesFocusShowsServersAndMenuClosesIt() {
        let app = launch()
        let account = openServers(app)
        XCTAssertTrue(account.label.contains("Living room server"), "it names the server in use: \(account.label)")
        let panel = app.descendants(matching: .any)["servers-panel"]
        // It says who is signed in, and where, before anything else.
        let who = app.descendants(matching: .any)["servers-who"]
        XCTAssertTrue(who.waitUntilExists(timeout: 10))
        XCTAssertTrue(who.label.contains("Signed in on Living room server"), who.label)
        shot("Servers, from the account tab")
        // Down from the tab: the server in use, so a second press changes nothing.
        let living = app.buttons["servers-row-srv-living"]
        remote.press(.down)
        XCTAssertTrue(living.waitForFocus(), "focus opens on the server in use: \(app.focusedButton.label)")
        XCTAssertTrue(living.label.contains("Current"), "the server in use carries Current: \(living.label)")
        XCTAssertFalse(living.label.contains("192.0.2") || living.label.contains(":27587"), "no address on a row: \(living.label)")
        XCTAssertFalse(app.descendants(matching: .any)["servers-hint"].exists, "no line explains the remote")
        remote.press(.select)
        XCTAssertTrue(living.waitForFocus(), "Select on the server in use changes nothing")
        XCTAssertTrue(accountTab(app).exists)
        // Down the list: Cabin server, Den server, Add a server, Manage servers.
        remote.press(.down)
        XCTAssertTrue(app.buttons["servers-row-srv-cabin"].waitForFocus())
        remote.press(.down)
        XCTAssertTrue(app.buttons["servers-row-srv-den"].waitForFocus())
        remote.press(.down)
        XCTAssertTrue(app.buttons["servers-add"].waitForFocus())
        remote.press(.down)
        XCTAssertTrue(app.buttons["servers-manage"].waitForFocus())
        shot("Servers, Manage servers focused")

        // Menu closes Servers and goes Home, as from any section, with focus in the tab bar.
        remote.press(.menu)
        XCTAssertTrue(panel.waitUntilGone(timeout: 10), "Menu closes Servers")
        XCTAssertTrue(eventually(timeout: 10) { self.focusedTab(app).lookedAt?.label == "Home" },
                      "and focus is back in the tab bar, on Home: \(app.focusedButton.label)")
        XCTAssertTrue(app.homeFirstScreen.waitUntilExists(timeout: 10), "Home is showing")
        // Right along the tabs shows it again; Left leaves it, as any tab.
        XCTAssertTrue(move(.right, to: account, in: app, presses: 8))
        XCTAssertTrue(panel.waitUntilExists(timeout: 10))
        app.press(.left, thenWaitForFocusToMove: 5)
        XCTAssertTrue(panel.waitUntilGone(timeout: 10), "Left from the account tab leaves Servers")
        XCTAssertTrue(focusedTab(app).lookedAt?.label.hasPrefix("Settings") == true, app.focusedButton.label)
    }

    @MainActor
    func testSwitchingFromServersShowsTheBannerAndTheOtherServersHome() {
        let app = launch()
        openServers(app)
        let cabin = app.buttons["servers-row-srv-cabin"]
        XCTAssertTrue(move(.down, to: cabin, in: app, presses: 2))
        remote.press(.select)
        let note = app.descendants(matching: .any)["server-note"]
        XCTAssertTrue(note.wait(until: NSPredicate(format: "label == %@", "Now on Cabin server"), timeout: 15), "the banner says where this Apple TV is now")
        XCTAssertTrue(app.descendants(matching: .any)["servers-panel"].waitUntilGone(timeout: 10), "Servers closes")
        XCTAssertTrue(app.staticTexts["Harbour Run"].waitUntilExists(timeout: 15), "Home is Cabin server's")
        XCTAssertFalse(app.homeFirstCard.exists, "and not Living room server's")
        XCTAssertTrue(accountTab(app, on: "Cabin server").waitUntilExists(timeout: 10), "the account tab names Cabin server now")
        shot("Now on Cabin server")
        // The banner takes no focus: the remote still drives Home.
        XCTAssertTrue(app.waitForFocusedButton(timeout: 10) { _ in true })
        XCTAssertFalse(note.lookedAt?.hasFocus == true)
        // Cabin server's Home opens on its first card, as a new Home does; from the tabs, Down reaches it.
        if focusedTab(app).lookedAt != nil { remote.press(.down) }
        XCTAssertTrue(eventually(timeout: 10) { self.focusedTab(app).lookedAt == nil && app.focusedButton.lookedAt != nil },
                      "focus is in Cabin server's Home: \(app.focusedButton.label)")
    }

    @MainActor
    func testAServerThatDoesNotAnswerSaysSoWithFocusOnTryAgain() {
        let app = launch()
        let account = openServers(app)
        let den = app.buttons["servers-row-srv-den"]
        XCTAssertTrue(move(.down, to: den, in: app, presses: 3))
        remote.press(.select)
        // The row says it didn't answer, and offers Try again, with focus kept on it.
        XCTAssertTrue(den.wait(until: NSPredicate(format: "label CONTAINS %@", "Couldn’t reach Den server"), timeout: 15), "it says Den server didn't answer: \(den.label)")
        XCTAssertTrue(den.label.contains("Try again"), den.label)
        XCTAssertTrue(den.waitForFocus(timeout: 10), "focus stays on Den server: \(app.focusedButton.label)")
        XCTAssertTrue(app.descendants(matching: .any)["servers-who"].label.contains("Signed in on Living room server"), "and the page still says where this Apple TV is")
        XCTAssertFalse(app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Watch its")).firstMatch.exists, "Apple TV keeps no downloads to watch")
        shot("Servers, Den server didn't answer")
        // It still doesn't answer; the failure stays, with focus where it was.
        remote.press(.select)
        XCTAssertTrue(den.wait(until: NSPredicate(format: "label CONTAINS %@", "Couldn’t reach Den server"), timeout: 15))
        XCTAssertTrue(den.waitForFocus(timeout: 10))
        remote.press(.menu)
        XCTAssertTrue(app.descendants(matching: .any)["servers-panel"].waitUntilGone(timeout: 10), "Menu closes Servers")
        XCTAssertTrue(eventually(timeout: 10) { self.focusedTab(app).lookedAt?.label == "Home" },
                      "focus is back in the tab bar, on Home: \(app.focusedButton.label)")
        XCTAssertTrue(account.exists, "still on the first server: the account tab names it")
        // Opened again, the failure it was showing has gone.
        XCTAssertTrue(move(.right, to: account, in: app, presses: 8))
        XCTAssertTrue(app.descendants(matching: .any)["servers-panel"].waitUntilExists(timeout: 10))
        XCTAssertTrue(den.waitUntilExists(timeout: 10))
        XCTAssertFalse(den.label.contains("Couldn’t reach"), den.label)
    }

    @MainActor
    func testAddingAServerFromServersToItsLastStep() {
        let app = launch()
        openServers(app)
        let add = app.buttons["servers-add"]
        XCTAssertTrue(move(.down, to: add, in: app, presses: 4))
        remote.press(.select)
        let steps = app.descendants(matching: .any)["task-steps"]
        XCTAssertTrue(app.staticTexts["Which server?"].waitUntilExists(timeout: 10), "Add a server's first step takes the screen")
        XCTAssertEqual(steps.label, "Step 1 of 3, Choose a server")
        let already = app.descendants(matching: .any)["add-already"]
        XCTAssertTrue(already.waitForLabel(containing: "Already added: Living room server, Cabin server, Den server."), already.label)
        let office = app.buttons["add-server-srv-office"]
        XCTAssertTrue(office.waitUntilExists(timeout: 10), "a server on this network that isn't added yet is offered")
        XCTAssertFalse(app.buttons["add-server-srv-living"].exists, "the ones added already aren't offered again")
        // Menu from the first step goes back to Servers, where it was started.
        XCTAssertTrue(app.waitForFocusedButton { _ in true })
        remote.press(.menu)
        XCTAssertTrue(app.descendants(matching: .any)["servers-panel"].waitUntilExists(timeout: 10), "Menu goes back to Servers")
        XCTAssertTrue(move(.down, to: add, in: app, presses: 5))
        remote.press(.select)
        XCTAssertTrue(office.waitUntilExists(timeout: 10))
        // The step gives focus to its first server once it is up; a press before that goes nowhere.
        XCTAssertTrue(office.waitForFocus(timeout: 10), "the server offered has focus: \(app.focusedButton.label)")
        shot("Add a server, step 1")
        remote.press(.select)
        XCTAssertTrue(app.staticTexts["Sign in to Office Mac mini"].waitUntilExists(timeout: 10))
        XCTAssertEqual(steps.label, "Step 2 of 3, Sign in")
        // The fakes approve the pairing code by themselves after a few seconds.
        XCTAssertTrue(app.staticTexts["Office Mac mini is added"].waitUntilExists(timeout: 30), "the last step says it is added")
        XCTAssertEqual(steps.label, "Added", "the last step is named alone")
        XCTAssertTrue(app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", "This Apple TV is signed in to 4 servers. Search looks in all of them.")).firstMatch.exists)
        let home = app.buttons["add-go-home"]
        XCTAssertTrue(home.waitForFocus(), "with focus on Go to Home")
        shot("Add a server, added")
        remote.press(.select)
        XCTAssertTrue(home.waitUntilGone(timeout: 10))
        XCTAssertTrue(accountTab(app, on: "Office Mac mini").waitUntilExists(timeout: 15), "Home, on the server just added")
    }

    @MainActor
    func testAServersPageSwitchesToItAndRemovingItAsksFirstOnKeep() {
        let app = launch(["-tab", "settings", "-settingsGroup", "server"])
        let server = app.buttons["settings-group-server"]
        XCTAssertTrue(server.waitUntilExists(timeout: 20))
        XCTAssertTrue(server.waitForFocus())
        remote.press(.right)
        let living = app.buttons["settings-row-registered-srv-living"]
        XCTAssertTrue(living.waitForFocus(), "Right moves into Server, on the server in use: \(app.focusedButton.label)")
        let cabin = app.buttons["settings-row-registered-srv-cabin"]
        remote.press(.down)
        XCTAssertTrue(cabin.waitForFocus())
        let help = app.descendants(matching: .any)["settings-help"]
        XCTAssertTrue(help.waitForLabel(containing: "Opens its page"), help.label)
        shot("Settings › Server, Your servers")
        remote.press(.select)

        let page = app.descendants(matching: .any)["server-page"]
        XCTAssertTrue(page.waitUntilExists(timeout: 10), "its page takes the screen")
        XCTAssertTrue(app.staticTexts["Settings › Server › Cabin server"].exists)
        let switchTo = app.buttons["server-switch"]
        XCTAssertTrue(switchTo.waitForFocus(), "with focus on Switch to this server")
        let hint = app.descendants(matching: .any)["task-hint"]
        XCTAssertTrue(hint.waitForLabel(containing: "Select switches to Cabin server"), hint.label)
        let remove = app.buttons["server-remove"]
        remote.press(.down)
        XCTAssertTrue(remove.waitForFocus())
        shot("Cabin server's page")
        remote.press(.select)

        let question = app.descendants(matching: .any)["question"]
        XCTAssertTrue(question.waitUntilExists(timeout: 10))
        XCTAssertTrue(app.staticTexts["Remove Cabin server from this Apple TV?"].exists)
        let keep = app.buttons["question-keep"]
        XCTAssertTrue(keep.waitForFocus(), "the question opens on Keep")
        XCTAssertEqual(keep.label, "Keep")
        shot("Remove Cabin server?")
        // Menu is Keep: nothing is removed.
        remote.press(.menu)
        XCTAssertTrue(question.waitUntilGone(timeout: 10))
        XCTAssertTrue(page.exists, "its page is still up")
        XCTAssertTrue(remove.waitForFocus(timeout: 10), "back on Remove this server: \(app.focusedButton.label)")

        remote.press(.select)
        XCTAssertTrue(keep.waitForFocus())
        remote.press(.down)
        let act = app.buttons["question-act"]
        XCTAssertTrue(act.waitForFocus())
        XCTAssertEqual(act.label, "Remove")
        remote.press(.select)
        XCTAssertTrue(page.waitUntilGone(timeout: 15), "removed, its page closes")
        XCTAssertTrue(cabin.waitUntilGone(timeout: 10), "and it is off the list")
        XCTAssertTrue(living.waitForFocus(timeout: 10), "focus is on Server's first row: \(app.focusedButton.label)")
        XCTAssertTrue(app.buttons["settings-row-registered-srv-den"].exists, "the others stay")
    }

    @MainActor
    func testSwitchToThisServerOnItsPage() {
        let app = launch(["-tab", "settings", "-settingsGroup", "server", "-serverPage", "srv-cabin"])
        let switchTo = app.buttons["server-switch"]
        XCTAssertTrue(switchTo.waitUntilExists(timeout: 20))
        XCTAssertTrue(switchTo.waitForFocus())
        remote.press(.select)
        let note = app.descendants(matching: .any)["server-note"]
        XCTAssertTrue(note.wait(until: NSPredicate(format: "label == %@", "Now on Cabin server"), timeout: 15))
        XCTAssertTrue(app.staticTexts["Harbour Run"].waitUntilExists(timeout: 15), "Home, on Cabin server")
    }

    @MainActor
    func testADifferentServersPageThatDoesNotAnswerOpensServersOnTheFailure() {
        let app = launch(["-tab", "settings", "-settingsGroup", "server", "-serverPage", "srv-den"])
        let switchTo = app.buttons["server-switch"]
        XCTAssertTrue(switchTo.waitUntilExists(timeout: 20))
        XCTAssertTrue(switchTo.waitForFocus())
        remote.press(.select)
        let den = app.buttons["servers-row-srv-den"]
        XCTAssertTrue(den.wait(until: NSPredicate(format: "label CONTAINS %@", "Couldn’t reach Den server"), timeout: 20),
                      "Servers opens to say Den server didn't answer, on its row")
        XCTAssertTrue(app.descendants(matching: .any)["servers-who"].label.contains("Signed in on Living room server"), "and where this Apple TV still is")
        XCTAssertTrue(den.waitForFocus(timeout: 10), "with focus on its row, where Select tries again: \(app.focusedButton.label)")
        XCTAssertFalse(app.descendants(matching: .any)["server-page"].exists, "its page has closed")
    }

    @MainActor
    func testSigningOutMovesOnToTheNextServerAndTheOneLeftSignsInAgainFromServers() {
        let app = launch(["-tab", "settings", "-settingsGroup", "account", "-signOutQuestion"])
        let keep = app.buttons["question-keep"]
        XCTAssertTrue(keep.waitUntilExists(timeout: 20))
        XCTAssertTrue(app.staticTexts["Sign out of Living room server?"].exists)
        XCTAssertTrue(keep.waitForFocus(), "the question opens on Stay signed in")
        let act = app.buttons["question-act"]
        XCTAssertTrue(move(.down, to: act, in: app, presses: 2))
        remote.press(.select)

        // Signed in to two more servers: the app goes on to the next, not to sign-in.
        let note = app.descendants(matching: .any)["server-note"]
        XCTAssertTrue(note.wait(until: NSPredicate(format: "label == %@", "Now on Cabin server"), timeout: 20), "it says where this Apple TV is now")
        XCTAssertTrue(app.staticTexts["Harbour Run"].waitUntilExists(timeout: 15), "Home is Cabin server's")

        // The server signed out of stays in Servers, and says so; Select opens its sign-in.
        let account = accountTab(app, on: "Cabin server")
        XCTAssertTrue(account.waitUntilExists(timeout: 10))
        toTabBar(app)
        XCTAssertTrue(move(.right, to: account, in: app, presses: 10), "Right reaches the account tab: \(app.focusedButton.label)")
        let living = app.buttons["servers-row-srv-living"]
        XCTAssertTrue(living.waitUntilExists(timeout: 10))
        XCTAssertTrue(living.label.contains("Signed out here"), living.label)
        XCTAssertTrue(move(.down, to: living, in: app, presses: 3), "it is under the server in use: \(app.focusedButton.label)")
        shot("Servers, signed out of Living room server")
        remote.press(.select)
        XCTAssertTrue(app.staticTexts["Sign in to Living room server"].waitUntilExists(timeout: 15), "its sign-in takes the screen")
        XCTAssertTrue(app.staticTexts["Settings › Server › Living room server"].exists, "under its own path, with no steps")
        XCTAssertFalse(app.descendants(matching: .any)["task-steps"].exists)
        shot("Sign in again")
        // The fakes approve the pairing code by themselves after a few seconds.
        XCTAssertTrue(app.staticTexts["You’re signed in to Living room server again"].waitUntilExists(timeout: 30))
        let home = app.buttons["add-go-home"]
        XCTAssertTrue(home.waitForFocus(), "with focus on Go to Home")
        remote.press(.select)
        XCTAssertTrue(home.waitUntilGone(timeout: 10))
        XCTAssertTrue(accountTab(app, on: "Living room server").waitUntilExists(timeout: 15), "Home, on the server signed in to again")
        XCTAssertTrue(app.homeFirstScreen.waitUntilExists(timeout: 15), "Living room server's Home")
    }

    @MainActor
    func testSearchOpensAnotherServersResultThereAndMenuReturnsToTheResults() {
        let app = launch(["-tab", "search", "-search", "harbor"])
        // Cabin server's Harbor Nights is listed with its server named; Den server didn't answer.
        let nights = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Harbor Nights, Cabin server")).firstMatch
        XCTAssertTrue(nights.waitUntilExists(timeout: 20), "a card from another server names it")
        let lights = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Harbor Lights")).allElementsBoundByIndex
        XCTAssertEqual(lights.count, 2, "the same title on two servers is listed twice")
        XCTAssertFalse(lights[0].label.contains("Cabin server"), "the server in use's comes first and names no server: \(lights[0].label)")
        XCTAssertTrue(lights[1].label.contains("Cabin server"), lights[1].label)
        let den = app.descendants(matching: .any)["search-unlisted-srv-den"]
        XCTAssertTrue(den.waitUntilExists(timeout: 15), "a server that didn't answer has its tile at the end")
        XCTAssertTrue(den.label.contains("Den server didn’t answer, so its titles aren’t listed."), den.label)
        shot("Search across servers")

        // Down from the keyboard into the results, then along to Cabin server's movie.
        XCTAssertTrue(app.waitForFocusedButton(timeout: 15) { _ in true })
        for _ in 0..<8 where !lights.contains(where: { $0.lookedAt?.hasFocus == true }) && nights.lookedAt?.hasFocus != true {
            app.press(.down, thenWaitForFocusToMove: 3)
        }
        XCTAssertTrue(move(.right, to: nights, in: app, presses: 6), "focus reaches Harbor Nights: \(app.focusedButton.label)")
        remote.press(.select)

        let note = app.descendants(matching: .any)["server-note"]
        XCTAssertTrue(note.wait(until: NSPredicate(format: "label == %@", "Now on Cabin server"), timeout: 15), "it switches to Cabin server")
        XCTAssertTrue(accountTab(app, on: "Cabin server").waitUntilExists(timeout: 10))
        // Harbor Nights was left partway on Cabin server: its page offers Resume.
        let resume = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Resume")).firstMatch
        XCTAssertTrue(resume.waitUntilExists(timeout: 15), "and opens Harbor Nights there")
        XCTAssertTrue(app.staticTexts["Harbor Nights"].exists)
        XCTAssertFalse(nights.exists, "the results are under its page")
        shot("Harbor Nights, on Cabin server")

        // Menu: back to Search with the same words, asked again on Cabin server.
        remote.press(.menu)
        let living = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Harbor Lights, Living room server")).firstMatch
        XCTAssertTrue(living.waitUntilExists(timeout: 20), "the results are back, and the other server is the one named now")
        let here = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Harbor Nights")).firstMatch
        XCTAssertTrue(here.waitUntilExists(timeout: 10))
        XCTAssertFalse(here.label.contains("Cabin server"), "Cabin server's own carry no name now: \(here.label)")
        shot("Back to the results, on Cabin server")
    }
}
