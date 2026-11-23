import XCTest

/// Settings on Apple TV
/// on the fakes, driven with the Siri Remote: the groups beside
/// the page, Menu from a page back to its group and then the top bar, each
/// group's remembered row, a question that opens on the button that keeps
/// things, a row that keeps focus while it saves and says so, and Add a
/// server from its first step to its last. `-settingsGroup <group>` shows a
/// group with focus on it at launch. Runs in `make apple-tv-test-sim`.
final class SettingsTests: XCTestCase {
    @MainActor private var remote: XCUIRemote { XCUIRemote.shared }

    override func setUp() async throws { continueAfterFailure = false }

    @MainActor
    private func launch(_ arguments: [String]) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-tab", "settings"] + arguments
        app.launch()
        return app
    }

    @MainActor
    private func focused(_ element: XCUIElement, timeout: TimeInterval = 10) -> Bool {
        element.waitForFocus(timeout: timeout)
    }

    /// Whether focus is on `element` or inside it: a `Menu` row takes focus
    /// on an element inside it, so its own `hasFocus` stays false
    /// (apple/AGENTS.md), and focus is found by where it is.
    @MainActor
    private func holds(_ element: XCUIElement, in app: XCUIApplication) -> Bool {
        guard let row = element.lookedAt else { return false }
        if row.hasFocus { return true }
        guard let focus = app.descendants(matching: .any).matching(NSPredicate(format: "hasFocus == true")).firstMatch.lookedAt else { return false }
        return row.frame.contains(CGPoint(x: focus.frame.midX, y: focus.frame.midY))
    }

    /// Where focus is: a `Menu` row's focus is on an element inside it, not a button.
    @MainActor
    private func focusFrame(_ app: XCUIApplication) -> CGRect? {
        app.descendants(matching: .any).matching(NSPredicate(format: "hasFocus == true")).firstMatch.lookedAt?.frame
    }

    /// Presses `button` until focus is on (or in) `element`, waiting for
    /// focus to move after each press, so a slow move isn't pressed twice.
    @MainActor
    private func move(_ button: XCUIRemote.Button, to element: XCUIElement, in app: XCUIApplication, presses: Int = 8) -> Bool {
        for _ in 0..<presses {
            if holds(element, in: app) { return true }
            let before = focusFrame(app)
            remote.press(button)
            _ = eventually(timeout: 2) { focusFrame(app).map { $0 != before } ?? false }
        }
        return eventually(timeout: 2) { holds(element, in: app) }
    }

    @MainActor
    private func shot(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    @MainActor
    func testMenuFromAPageGoesBackToItsGroupThenTheTopBar() {
        let app = launch(["-settingsGroup", "account"])
        let group = app.buttons["settings-group-account"]
        XCTAssertTrue(group.waitUntilExists(timeout: 20))
        XCTAssertTrue(focused(group), "launch puts focus on Account & devices")
        let help = app.descendants(matching: .any)["settings-help"]
        XCTAssertTrue(help.waitForLabel(containing: "Right opens Account & devices"), help.label)
        remote.press(.right)
        let name = app.buttons["settings-row-name"]
        XCTAssertTrue(focused(name), "Right moves into the page, on its first row")
        remote.press(.down)
        XCTAssertTrue(focused(app.buttons["settings-row-everyone"]))
        XCTAssertTrue(help.waitForLabel(containing: "Menu goes back to the list"), help.label)
        shot("Account & devices, a row focused")
        remote.press(.menu)
        XCTAssertTrue(focused(group), "Menu from the page comes back to its group")
        remote.press(.menu)
        XCTAssertTrue(app.waitForFocusedButton { $0.hasPrefix("Settings") }, "Menu from the list goes to the top bar")
        XCTAssertTrue(name.exists, "and Settings stays on Account & devices")
        // A press made while the top bar takes focus can be lost: press again until focus comes down.
        for _ in 0..<3 where !focused(group, timeout: 3) { remote.press(.down) }
        XCTAssertTrue(focused(group), "Down from the top bar comes back to the group")
    }

    @MainActor
    func testEachGroupRemembersTheRowFocusWasOn() {
        let app = launch(["-settingsGroup", "account"])
        let account = app.buttons["settings-group-account"]
        XCTAssertTrue(account.waitUntilExists(timeout: 20))
        XCTAssertTrue(focused(account))
        remote.press(.right)
        XCTAssertTrue(focused(app.buttons["settings-row-name"]))
        remote.press(.down)
        let everyone = app.buttons["settings-row-everyone"]
        XCTAssertTrue(focused(everyone))
        remote.press(.left)
        XCTAssertTrue(focused(account), "Left goes back to the page's own group")
        remote.press(.down)
        let requests = app.buttons["settings-group-requests"]
        XCTAssertTrue(focused(requests))
        XCTAssertTrue(app.buttons["settings-row-mine"].waitUntilExists(timeout: 5), "moving along the list shows that group's page")
        XCTAssertFalse(everyone.exists)
        remote.press(.up)
        XCTAssertTrue(focused(account))
        remote.press(.right)
        XCTAssertTrue(focused(everyone), "Right goes back to the row the group had, not its first")
    }

    @MainActor
    func testSignOutQuestionsOpenOnTheButtonThatKeepsThings() {
        let app = launch(["-settingsGroup", "account"])
        let account = app.buttons["settings-group-account"]
        XCTAssertTrue(account.waitUntilExists(timeout: 20))
        XCTAssertTrue(focused(account))
        remote.press(.right)
        XCTAssertTrue(focused(app.buttons["settings-row-name"]))
        // Past Everyone's devices to the first other device's Sign out.
        let device = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "settings-row-device-")).firstMatch
        XCTAssertTrue(device.waitUntilExists(timeout: 10), "the devices are listed")
        XCTAssertTrue(move(.down, to: device, in: app))
        let name = String(device.label.dropFirst("Sign out ".count))
        remote.press(.select)
        let keep = app.buttons["question-keep"]
        XCTAssertTrue(keep.waitUntilExists(timeout: 10), "Sign out asks first")
        XCTAssertTrue(app.staticTexts["Sign out \(name)?"].exists)
        XCTAssertEqual(keep.label, "Keep it signed in")
        XCTAssertEqual(app.buttons["question-act"].label, "Sign out \(name)")
        XCTAssertTrue(focused(keep), "the question opens on Keep it signed in")
        shot("Sign out another device")
        remote.press(.menu)
        XCTAssertTrue(keep.waitUntilGone(timeout: 5), "Menu keeps it signed in")
        XCTAssertTrue(focused(device), "and focus goes back to its Sign out")

        let signOut = app.buttons["settings-row-signout"]
        XCTAssertTrue(move(.down, to: signOut, in: app, presses: 12), "Sign out of this Apple TV is at the bottom")
        remote.press(.select)
        XCTAssertTrue(keep.waitUntilExists(timeout: 10))
        XCTAssertTrue(app.staticTexts["Sign out of Living room server?"].exists)
        XCTAssertEqual(keep.label, "Stay signed in")
        XCTAssertEqual(app.buttons["question-act"].label, "Sign out of this Apple TV")
        XCTAssertTrue(focused(keep), "the question opens on Stay signed in")
        shot("Sign out of this Apple TV")
        remote.press(.select)
        XCTAssertTrue(keep.waitUntilGone(timeout: 5))
        XCTAssertTrue(focused(signOut), "Stay signed in leaves this Apple TV signed in")
    }

    @MainActor
    func testAChoiceFromAMenuSavesAndSaysSoWhileItsRowKeepsFocus() {
        let app = launch(["-settingsGroup", "playback"])
        let playback = app.buttons["settings-group-playback"]
        XCTAssertTrue(playback.waitUntilExists(timeout: 20))
        XCTAssertTrue(focused(playback))
        remote.press(.right)
        let quality = app.descendants(matching: .any)["settings-row-quality"]
        XCTAssertTrue(eventually(timeout: 10) { holds(quality, in: app) }, "Right moves into Playback, on Default quality")
        let credits = app.descendants(matching: .any)["settings-row-skip-credits"]
        XCTAssertTrue(move(.down, to: credits, in: app))
        XCTAssertEqual(credits.label, "Credits")
        XCTAssertEqual(credits.value as? String, "Show a Skip button")
        remote.press(.select)
        // The menu's choices are toggles, which reach XCUI as elements inside
        // cells labelled "<choice>, <its line>"; tvOS 27 drops their identifiers.
        func menuChoice(_ title: String) -> XCUIElement {
            app.otherElements.matching(NSPredicate(format: "label BEGINSWITH %@", "\(title), ")).firstMatch
        }
        let choice = menuChoice("Skip after 3 seconds")
        XCTAssertTrue(choice.waitUntilExists(timeout: 5), "Select opens the row's menu")
        XCTAssertTrue(choice.label.hasSuffix("Shows the button, then skips by itself"), "each choice has its line: \(choice.label)")
        XCTAssertTrue(menuChoice("Show a Skip button").isSelected, "the saved choice is ticked")
        XCTAssertTrue(move(.down, to: choice, in: app, presses: 4))
        shot("Credits' menu")
        remote.press(.select)
        XCTAssertTrue(choice.waitUntilGone(timeout: 5), "choosing closes the menu")
        // Focus comes back to the row from the closing menu, and stays there while it saves.
        XCTAssertTrue(eventually(timeout: 3) { holds(credits, in: app) }, "the row keeps focus while it saves")
        XCTAssertTrue(credits.wait(until: NSPredicate(format: "value CONTAINS %@", "Saved"), timeout: 10), "and says it saved: \(String(describing: credits.value))")
        XCTAssertTrue((credits.value as? String)?.hasPrefix("Skip after 3 seconds") == true, String(describing: credits.value))
        XCTAssertTrue(holds(credits, in: app), "and keeps focus after")
        let help = app.descendants(matching: .any)["settings-help"]
        XCTAssertTrue(help.waitForLabel(containing: "Skip after 3 seconds shows the Skip button, then skips by itself."), help.label)
        shot("Credits saved")

        // A switch saves at once too, and keeps focus while it does.
        let autoplay = app.buttons["settings-row-autoplay"]
        XCTAssertTrue(move(.down, to: autoplay, in: app))
        XCTAssertEqual(autoplay.value as? String, "On")
        remote.press(.select)
        XCTAssertTrue(autoplay.wait(until: NSPredicate(format: "value == 'Off'"), timeout: 5))
        XCTAssertTrue(autoplay.hasFocus, "the row keeps focus while it saves")
        XCTAssertTrue(autoplay.wait(until: NSPredicate(format: "label CONTAINS %@", "waits for you"), timeout: 10), autoplay.label)
        XCTAssertTrue(autoplay.hasFocus)
    }

    /// `-fakesSaveFails recap` (the Kit's fakes) refuses Recap's saves, unanswered.
    @MainActor
    func testARefusedChoiceSaysWhyInItsRowAndGoesBack() {
        let app = launch(["-settingsGroup", "playback", "-fakesSaveFails", "recap"])
        let playback = app.buttons["settings-group-playback"]
        XCTAssertTrue(playback.waitUntilExists(timeout: 20))
        XCTAssertTrue(focused(playback))
        remote.press(.right)
        let recap = app.descendants(matching: .any)["settings-row-skip-recap"]
        XCTAssertTrue(move(.down, to: recap, in: app))
        XCTAssertEqual(recap.value as? String, "Show a Skip button")
        remote.press(.select)
        let choice = app.otherElements.matching(NSPredicate(format: "label BEGINSWITH %@", "Skip right away, ")).firstMatch
        XCTAssertTrue(choice.waitUntilExists(timeout: 5), "Select opens the row's menu")
        XCTAssertTrue(move(.down, to: choice, in: app, presses: 5))
        remote.press(.select)
        XCTAssertTrue(choice.waitUntilGone(timeout: 5))
        XCTAssertTrue(recap.wait(until: NSPredicate(format: "value CONTAINS %@", "Couldn’t save, so it’s still Show a Skip button"), timeout: 10),
                      "the row says it couldn't save and what it still is: \(String(describing: recap.value))")
        XCTAssertTrue((recap.value as? String)?.hasPrefix("Show a Skip button") == true, "and the choice is back: \(String(describing: recap.value))")
        XCTAssertTrue(eventually(timeout: 3) { holds(recap, in: app) }, "the row keeps focus")
        shot("Recap couldn't save")
    }

    @MainActor
    func testAddAServerFromTheFirstStepToTheLast() {
        let app = launch(["-settingsGroup", "server"])
        let server = app.buttons["settings-group-server"]
        XCTAssertTrue(server.waitUntilExists(timeout: 20))
        XCTAssertTrue(focused(server))
        remote.press(.right)
        // Your servers comes first: the server in use, then the others, then Add a server.
        XCTAssertTrue(focused(app.buttons["settings-row-registered-srv-living"]), "Right moves into Server, on the server in use")
        let row = app.buttons["settings-row-add"]
        XCTAssertTrue(move(.down, to: row, in: app), "Add a server follows the servers")
        let help = app.descendants(matching: .any)["settings-help"]
        XCTAssertTrue(help.waitForLabel(containing: "switching between them needs no password"), help.label)
        remote.press(.select)
        let steps = app.descendants(matching: .any)["task-steps"]
        XCTAssertTrue(app.staticTexts["Which server?"].waitUntilExists(timeout: 10), "the first step takes the screen")
        XCTAssertEqual(steps.label, "Step 1 of 3, Choose a server")
        // Menu goes to what has focus: wait for the step to give it to a server.
        XCTAssertTrue(app.waitForFocusedButton { _ in true }, "a server has focus")
        remote.press(.menu)
        XCTAssertTrue(app.staticTexts["Which server?"].waitUntilGone(timeout: 10), "Menu on the first step goes back to Settings")
        XCTAssertTrue(focused(row), "on Add a server")

        remote.press(.select)
        XCTAssertTrue(app.staticTexts["Which server?"].waitUntilExists(timeout: 10))
        XCTAssertTrue(app.waitForFocusedButton { _ in true })
        let office = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Office Mac mini")).firstMatch
        XCTAssertTrue(office.waitUntilExists(timeout: 10), "a server on this network is listed")
        XCTAssertFalse(app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Living room server")).firstMatch.exists,
                       "the server this Apple TV is on already isn't offered")
        XCTAssertTrue(move(.down, to: office, in: app))
        shot("Add a server, step 1")
        remote.press(.select)
        XCTAssertTrue(app.staticTexts["Sign in to Office Mac mini"].waitUntilExists(timeout: 10), "a server not signed in to asks to sign in")
        XCTAssertEqual(steps.label, "Step 2 of 3, Sign in")
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "On a phone or computer signed in to Office Mac mini")).firstMatch.exists,
                      "Sign in starts on the code")
        // The password form, with focus on its name, and back to the code.
        let otherWay = app.buttons["add-other-way"]
        XCTAssertTrue(focused(otherWay), "the other way in has focus under the code")
        XCTAssertEqual(otherWay.label, "Sign in with a password instead")
        shot("Add a server, step 2")
        remote.press(.select)
        XCTAssertTrue(focused(app.textFields["Username"]), "the form starts on the name")
        XCTAssertTrue(otherWay.waitForLabel(containing: "Use a code"), otherWay.label)
        // Down past the password to the buttons, landing on whichever is nearest, then right to the other way.
        let signIn = app.buttons["Sign in"]
        for _ in 0..<4 where !(otherWay.hasFocus || signIn.hasFocus) {
            app.press(.down, thenWaitForFocusToMove: 2)
        }
        XCTAssertTrue(move(.right, to: otherWay, in: app, presses: 3))
        remote.press(.select)
        XCTAssertTrue(otherWay.waitForLabel(containing: "with a password"), "back to the code: \(otherWay.label)")
        XCTAssertTrue(focused(otherWay), "with focus on the other way in again")
        // The fakes approve the pairing code by themselves after a few seconds.
        XCTAssertTrue(app.staticTexts["Office Mac mini is added"].waitUntilExists(timeout: 30), "the last step says it is added")
        XCTAssertEqual(steps.label, "Added", "the last step is named alone")
        XCTAssertTrue(app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", "Search looks in all of them")).firstMatch.exists,
                      "and that search looks in every server")
        let home = app.buttons["add-go-home"]
        XCTAssertTrue(focused(home), "with focus on Go to Home")
        shot("Add a server, added")
        remote.press(.select)
        XCTAssertTrue(home.waitUntilGone(timeout: 10))
        XCTAssertTrue(app.homeFirstScreen.waitUntilExists(timeout: 10), "Go to Home goes to Home")
    }
}
