import XCTest
import UIKit

/// Settings' tasks on the fakes: Link a TV and
/// Add a server from the first step to the last, Back and Cancel at each
/// step, swiping a sheet down with something typed, Rename, and both
/// sign-out questions. On an iPhone, and on an iPad, where the tasks are
/// form sheets and Add a server stays in the detail column.
final class SettingsTasksTests: XCTestCase {
    override func setUp() async throws { continueAfterFailure = false }

    private var pad: Bool { UIDevice.current.userInterfaceIdiom == .pad }

    // MARK: Link a TV

    @MainActor
    func testLinkATVFromFirstStepToLastWithBackOnTheWay() {
        let app = launch(["-settingsPage", "account"])
        defer { app.terminate() }
        tap(app.buttons["account-linkTV"])
        XCTAssertTrue(steps(app).waitForExistence(timeout: 10))
        XCTAssertEqual(steps(app).label, "Step 1 of 3, Enter the code")
        XCTAssertFalse(app.buttons["link-check"].isEnabled, "Check this code is off until six characters are in")

        typeCode("K7M4PQ", in: app)
        tap(app.buttons["link-check"])
        XCTAssertTrue(app.buttons["link-approve"].waitForExistence(timeout: 10))
        XCTAssertEqual(steps(app).label, "Step 2 of 3, Check it’s yours")
        XCTAssertTrue(app.buttons["link-approve"].label.contains("Approve Living room TV"), app.buttons["link-approve"].label)
        shot(app, "Link a TV — check it's yours")

        // Back keeps the code.
        tap(app.buttons["task-back"])
        XCTAssertTrue(app.buttons["link-check"].waitForExistence(timeout: 10))
        XCTAssertEqual(steps(app).label, "Step 1 of 3, Enter the code")
        XCTAssertTrue(app.buttons["link-check"].isEnabled, "the code is still in")

        tap(app.buttons["link-check"])
        tap(app.buttons["link-approve"])
        XCTAssertTrue(app.buttons["link-done"].waitForExistence(timeout: 10))
        XCTAssertEqual(steps(app).label, "Linked")
        XCTAssertFalse(app.buttons["task-back"].exists, "the last step has no way back")
        XCTAssertFalse(app.buttons["task-cancel"].exists)
        XCTAssertTrue(text(app, "What changed").exists && text(app, "What happens next").exists)
        shot(app, "Link a TV — linked")
        tap(app.buttons["link-done"])
        XCTAssertTrue(app.buttons["link-done"].waitForNonExistence(timeout: 10))
        XCTAssertTrue(app.buttons["account-linkTV"].waitForExistence(timeout: 10), "Done goes back to Account & devices")
    }

    @MainActor
    func testLinkATVCancelsAtEachStep() {
        let app = launch(["-settingsPage", "account"])
        defer { app.terminate() }
        // Step 1.
        tap(app.buttons["account-linkTV"])
        tap(app.buttons["task-cancel"])
        XCTAssertTrue(app.buttons["task-cancel"].waitForNonExistence(timeout: 10))
        // Step 2, opened from a TV's QR code with the code filled in.
        tap(app.buttons["account-linkTV"])
        typeCode("K7M4PQ", in: app)
        tap(app.buttons["link-check"])
        XCTAssertTrue(app.buttons["link-approve"].waitForExistence(timeout: 10))
        tap(app.buttons["task-cancel"])
        XCTAssertTrue(app.buttons["link-approve"].waitForNonExistence(timeout: 10))
        // Not my TV closes it too.
        tap(app.buttons["account-linkTV"])
        typeCode("K7M4PQ", in: app)
        tap(app.buttons["link-check"])
        tap(app.buttons["link-not-mine"])
        XCTAssertTrue(app.buttons["link-not-mine"].waitForNonExistence(timeout: 10))
    }

    @MainActor
    func testSwipingLinkATVDownWithACodeTypedAsksFirst() throws {
        try XCTSkipIf(pad, "A form sheet on iPad is dismissed by Cancel or Escape")
        let app = launch(["-settingsPage", "account"])
        defer { app.terminate() }
        tap(app.buttons["account-linkTV"])
        typeCode("K7M", in: app)
        swipeSheetDown(app)
        let stop = dialogButton(app, "Discard")
        XCTAssertTrue(stop.waitForExistence(timeout: 10), "a typed code isn't thrown away by a swipe")
        shot(app, "Link a TV — asks before closing")
        dismissDialog(app, keep: "Keep typing")
        XCTAssertTrue(stop.waitForNonExistence(timeout: 5))
        XCTAssertTrue(app.buttons["link-check"].exists, "keeping on leaves the sheet up")
        swipeSheetDown(app)
        tap(dialogButton(app, "Discard"))
        XCTAssertTrue(app.buttons["link-check"].waitForNonExistence(timeout: 10))
    }

    @MainActor
    func testALinkWithACodeOpensItsSecondStep() {
        let app = launch(["-open", "watchmymedia://link?code=K7M4PQ"])
        defer { app.terminate() }
        XCTAssertTrue(app.buttons["link-approve"].waitForExistence(timeout: 20))
        XCTAssertEqual(steps(app).label, "Step 2 of 3, Check it’s yours")
    }

    /// A task keeps its place when an iPad's window changes size: the
    /// sheet goes from the iPad's layout to the iPhone's and back on the
    /// same step, with the code still in.
    @MainActor
    func testLinkATVKeepsItsStepWhenTheWindowChangesSize() throws {
        try XCTSkipUnless(pad, "Window sizes are an iPad's")
        XCUIDevice.shared.orientation = .landscapeLeft
        let app = launch(["-settingsPage", "account"])
        defer { app.terminate(); XCUIDevice.shared.orientation = .portrait }
        tap(app.buttons["account-linkTV"])
        typeCode("K7M4PQ", in: app)
        tap(app.buttons["link-check"])
        XCTAssertTrue(app.buttons["link-approve"].waitForExistence(timeout: 10))

        let wide = app.windows.firstMatch.frame
        resizeWindow(app, toWidth: 0.45)
        XCTAssertTrue(app.buttons["link-approve"].waitForExistence(timeout: 10), "still on Check it’s yours")
        XCTAssertEqual(steps(app).label, "Step 2 of 3, Check it’s yours")
        shot(app, "Link a TV — compact window")
        tap(app.buttons["task-cancel"])
        XCTAssertTrue(app.buttons["account-linkTV"].waitForExistence(timeout: 10))
        shot(app, "Account & devices — compact window")

        // Back to full width: the iPad's layout, the sidebar beside the page.
        let window = app.windows.firstMatch
        window.coordinate(withNormalizedOffset: CGVector(dx: 1, dy: 1)).withOffset(CGVector(dx: -3, dy: -3))
            .press(forDuration: 0.5, thenDragTo: window.coordinate(withNormalizedOffset: .zero)
                .withOffset(CGVector(dx: wide.maxX - window.frame.minX - 3, dy: wide.maxY - window.frame.minY - 3)))
        let expanded = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in app.windows.firstMatch.frame.width >= 1000 }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [expanded], timeout: 10), .completed)
        XCTAssertTrue(app.navigationBars["Settings"].waitForExistence(timeout: 10))
    }

    /// In landscape an iPad's pages stand in two columns beside the sidebar
    /// (Pad2Settings2, Pad2Settings2Account): Skipping beside Quality, and
    /// Signed-in devices beside This iPad.
    @MainActor
    func testIPadPagesStandInTwoColumnsInLandscape() throws {
        try XCTSkipUnless(pad, "Columns are an iPad's")
        XCUIDevice.shared.orientation = .landscapeLeft
        let app = launch(["-settingsPage", "playback"])
        defer { app.terminate(); XCUIDevice.shared.orientation = .portrait }
        let quality = app.buttons["playback-quality"]
        let intro = app.buttons["playback-skip-intro"]
        XCTAssertTrue(intro.waitForExistence(timeout: 15))
        XCTAssertGreaterThan(intro.frame.minX, quality.frame.maxX, "Skipping stands beside Quality")
        shot(app, "iPad — Playback in two columns")

        tap(app.buttons["settings-account"])
        let rename = app.buttons["account-rename"]
        let everyone = app.switches["account-everyone"].exists ? app.switches["account-everyone"] : app.descendants(matching: .any)["account-everyone"]
        XCTAssertTrue(rename.waitForExistence(timeout: 10))
        XCTAssertTrue(everyone.waitForExistence(timeout: 10))
        XCTAssertGreaterThan(everyone.frame.minX, rename.frame.maxX, "Signed-in devices stands beside This iPad")
        shot(app, "iPad — Account & devices in two columns")
    }

    // MARK: Add a server

    @MainActor
    func testAddAServerFromFirstStepToLastWithBackAndAWrongPassword() {
        let app = launch(["-settingsPage", "server"])
        defer { app.terminate() }
        tap(app.buttons["server-add"])
        XCTAssertTrue(steps(app).waitForExistence(timeout: 10))
        XCTAssertEqual(steps(app).label, "Step 1 of 3, Choose a server")
        // The servers this iPhone is registered with aren't offered again; the step names them.
        XCTAssertTrue(text(app, "Already added: Living room server").waitForExistence(timeout: 10))
        XCTAssertFalse(app.buttons["add-server-srv-living"].exists)
        chooseServer("Office Mac mini", in: app)
        XCTAssertTrue(app.textFields["add-name"].waitForExistence(timeout: 15))
        XCTAssertEqual(steps(app).label, "Step 2 of 3, Sign in")

        // Back returns to the servers, still on the first one.
        tap(app.buttons["task-back"])
        XCTAssertTrue(app.textFields["add-address"].waitForExistence(timeout: 10))
        XCTAssertTrue(text(app, "Living room server").exists)
        chooseServer("Office Mac mini", in: app)

        let name = app.textFields["add-name"]
        XCTAssertTrue(name.waitForExistence(timeout: 15))
        name.tap()
        name.typeText("Robin")
        let password = app.secureTextFields["add-password"]
        password.tap()
        password.typeText("wrong")
        tap(app.buttons["add-sign-in"])
        XCTAssertTrue(text(app, "You’re still on Living room server").waitForExistence(timeout: 10), "a refusal says where you still are")
        shot(app, "Add a server — wrong password")

        password.tap()
        password.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: 5) + "password")
        tap(app.buttons["add-sign-in"])
        XCTAssertTrue(app.buttons["add-done"].waitForExistence(timeout: 15))
        XCTAssertEqual(steps(app).label, "Added")
        XCTAssertTrue(text(app, "Office Mac mini is added").exists)
        XCTAssertTrue(text(app, "You’re on it now, signed in as Robin.").exists)
        XCTAssertTrue(text(app, "is signed in to 2 servers. Search looks in all of them.").exists)
        XCTAssertTrue(text(app, "It doesn’t ask for a password again").exists)
        shot(app, "Add a server — added")
        tap(app.buttons["add-done"])
        XCTAssertTrue(app.buttons["add-done"].waitForNonExistence(timeout: 10))
        XCTAssertTrue(app.navigationBars["Home"].waitForExistence(timeout: 15), "Done shows the new server's Home")
    }

    /// "Use a code from a signed-in device instead" swaps the form for a
    /// code, and back; the fakes approve a code after a few seconds, so
    /// the task ends on Added without a password.
    @MainActor
    func testAddAServerSignsInWithACodeFromASignedInDevice() {
        let app = launch(["-settingsPage", "server"])
        defer { app.terminate() }
        tap(app.buttons["server-add"])
        chooseServer("Office Mac mini", in: app)
        XCTAssertTrue(app.textFields["add-name"].waitForExistence(timeout: 15))
        let other = app.buttons["add-other-way"]
        XCTAssertEqual(other.label, "Use a code from a signed-in device instead")
        tap(other)
        XCTAssertTrue(app.descendants(matching: .any)["add-code"].waitForExistence(timeout: 10))
        XCTAssertFalse(app.textFields["add-name"].exists)
        XCTAssertEqual(app.buttons["add-other-way"].label, "Sign in with a password instead")
        shot(app, "Add a server — a code from a signed-in device")
        tap(app.buttons["add-other-way"])
        XCTAssertTrue(app.textFields["add-name"].waitForExistence(timeout: 10), "back to the form")
        tap(app.buttons["add-other-way"])
        XCTAssertTrue(app.buttons["add-done"].waitForExistence(timeout: 30), "the code was approved")
        XCTAssertEqual(steps(app).label, "Added")
    }

    @MainActor
    func testAddAServerCancelsAtEachStepAndStaysOnTheFirstServer() {
        let app = launch(["-settingsPage", "server"])
        defer { app.terminate() }
        tap(app.buttons["server-add"])
        tap(app.buttons["task-cancel"])
        XCTAssertTrue(app.buttons["task-cancel"].waitForNonExistence(timeout: 10))
        XCTAssertTrue(text(app, "Living room server").exists)

        tap(app.buttons["server-add"])
        chooseServer("Office Mac mini", in: app)
        XCTAssertTrue(app.textFields["add-name"].waitForExistence(timeout: 15))
        tap(app.buttons["task-cancel"])
        XCTAssertTrue(app.textFields["add-name"].waitForNonExistence(timeout: 10))
        XCTAssertTrue(app.navigationBars["Server"].waitForExistence(timeout: 10))
        XCTAssertTrue(text(app, "Sam · admin").exists, "Cancel keeps Sam on Living room server")
    }

    // MARK: Saving

    /// A choice saves at once and its row says so; one the server refuses
    /// goes back, and its row says what it is still (Settings2PlaybackStates).
    @MainActor
    func testARowThatSavesSaysSoAndOneThatFailsGoesBack() {
        let app = launch(["-fakesSaveFails", "recap", "-settingsPage", "playback"])
        defer { app.terminate() }
        // Quality is kept on this device: it can't fail, and still says Saved.
        let quality = app.buttons["playback-quality"]
        tap(quality)
        tap(app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "1080p")).firstMatch)
        let qualitySaved = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value CONTAINS %@", "Saved."), object: quality)
        XCTAssertEqual(XCTWaiter.wait(for: [qualitySaved], timeout: 10), .completed, String(describing: quality.value))

        let credits = app.buttons["playback-skip-credits"]
        tap(credits)
        tap(app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Skip after 3 seconds")).firstMatch)
        let saved = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value CONTAINS %@", "Saved."), object: credits)
        XCTAssertEqual(XCTWaiter.wait(for: [saved], timeout: 10), .completed, String(describing: credits.value))
        XCTAssertTrue((credits.value as? String)?.hasPrefix("Skip after 3 seconds") == true, String(describing: credits.value))
        shot(app, "Playback — a row that saved")

        let recap = app.buttons["playback-skip-recap"]
        tap(recap)
        tap(app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Skip right away")).firstMatch)
        let failed = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value CONTAINS %@", "Couldn’t save, so it’s still Show a Skip button"), object: recap)
        XCTAssertEqual(XCTWaiter.wait(for: [failed], timeout: 10), .completed, String(describing: recap.value))
        XCTAssertTrue((recap.value as? String)?.hasPrefix("Show a Skip button") == true, "the choice goes back")
        shot(app, "Playback — a row that failed")
    }

    // MARK: Account & devices

    @MainActor
    func testChangePasswordSaysWhatIsWrongThenSignsTheOthersOut() {
        let app = launch(["-settingsPage", "account"])
        defer { app.terminate() }
        XCTAssertTrue(app.buttons["Sign out Living room TV"].waitForExistence(timeout: 15), "the devices have loaded")
        tap(app.buttons["account-changePassword"])
        let current = app.secureTextFields["password-current"]
        let new = app.secureTextFields["password-new"]
        let change = app.buttons["password-change"]
        XCTAssertTrue(current.waitForExistence(timeout: 10))
        XCTAssertTrue(change.label.hasPrefix("Change password and sign out"), change.label)
        XCTAssertTrue(text(app, "Living room TV").exists, "the devices it signs out, by name")

        // Too short: the button is off and the field says why.
        tap(current)
        current.typeText("nope")
        tap(new)
        new.typeText("short")
        XCTAssertTrue(text(app, "Use 8 characters or more.").waitForExistence(timeout: 5))
        XCTAssertFalse(change.isEnabled)

        // A wrong current password: said under its field, nothing changed.
        new.typeText("-and-longer")
        tap(change)
        XCTAssertTrue(text(app, "That password is wrong. Nothing was changed.").waitForExistence(timeout: 10))
        shot(app, "Change password — wrong password")

        tap(current)
        current.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: 4) + "password")
        tap(change)
        XCTAssertTrue(current.waitForNonExistence(timeout: 10), "the sheet closes once changed")
        XCTAssertTrue(app.descendants(matching: .any)["account-banner"].waitForExistence(timeout: 10))
        XCTAssertFalse(app.buttons["Sign out Living room TV"].exists, "the other devices are signed out")
        shot(app, "Account & devices — password changed")
    }

    @MainActor
    func testChangePasswordSaysTooManyTriesAboveItsButton() {
        let app = launch(["-fakesPasswordFails", "429", "-settingsPage", "account"])
        defer { app.terminate() }
        tap(app.buttons["account-changePassword"])
        let current = app.secureTextFields["password-current"]
        XCTAssertTrue(current.waitForExistence(timeout: 10))
        tap(current)
        current.typeText("password")
        let new = app.secureTextFields["password-new"]
        tap(new)
        new.typeText("a-new-password")
        tap(app.buttons["password-change"])
        let problem = app.descendants(matching: .any)["task-problem"]
        XCTAssertTrue(problem.waitForExistence(timeout: 10))
        XCTAssertTrue(problem.label.contains("Too many tries"), problem.label)
        XCTAssertTrue(problem.label.contains("Nothing was changed"), problem.label)
        XCTAssertTrue(current.exists, "the sheet stays up")
    }

    @MainActor
    func testRenameIsASheetWithCancelAndRename() {
        let app = launch(["-settingsPage", "account"])
        defer { app.terminate() }
        let row = app.buttons["account-rename"]
        tap(row)
        let field = app.textFields["rename-field"]
        XCTAssertTrue(field.waitForExistence(timeout: 10))
        // Empty, Rename is off and says Cancel keeps the name.
        tap(app.buttons["Clear"])
        XCTAssertFalse(app.buttons["rename-save"].isEnabled)
        XCTAssertTrue(text(app, "Rename is off until there is a name").exists)
        tap(app.buttons["rename-cancel"])
        XCTAssertTrue(field.waitForNonExistence(timeout: 10))
        XCTAssertTrue(row.label.contains(pad ? "Sam’s iPad" : "Sam’s iPhone"), row.label)

        tap(row)
        XCTAssertTrue(field.waitForExistence(timeout: 10))
        tap(app.buttons["Clear"])
        field.typeText("Den iPhone")
        tap(app.buttons["rename-save"])
        XCTAssertTrue(field.waitForNonExistence(timeout: 10))
        let renamed = app.buttons["account-rename"]
        XCTAssertTrue(renamed.label.contains("Den iPhone"), renamed.label)
        XCTAssertTrue(renamed.label.contains("Saved."), "the row says the name saved: \(renamed.label)")
        shot(app, "Account & devices — renamed")
    }

    @MainActor
    func testSigningAnotherDeviceOutAsksFirstAndNamesIt() {
        let app = launch(["-settingsPage", "account"])
        defer { app.terminate() }
        let signOut = app.buttons["Sign out Living room TV"].firstMatch
        tap(signOut)
        XCTAssertTrue(dialogButton(app, "Sign out Living room TV").waitForExistence(timeout: 10))
        XCTAssertTrue(text(app, "Sign out Living room TV?").exists)
        XCTAssertTrue(text(app, "Anything it is playing stops").exists)
        shot(app, "Account & devices — sign out another device")
        dismissDialog(app, keep: "Keep it signed in")
        XCTAssertTrue(signOut.waitForExistence(timeout: 5), "Keep it signed in keeps it")

        tap(signOut)
        tap(dialogButton(app, "Sign out Living room TV"))
        XCTAssertTrue(signOut.waitForNonExistence(timeout: 10))
        XCTAssertTrue(text(app, "Living room TV was signed out").waitForExistence(timeout: 10), "the list says what happened")
    }

    @MainActor
    func testSigningThisDeviceOutSaysWhatStaysAndWhatGoes() {
        let app = launch(["-settingsPage", "account"])
        defer { app.terminate() }
        tap(app.buttons["account-signOut"])
        let delete = dialogButton(app, "Sign out and delete", prefix: true)
        XCTAssertTrue(delete.waitForExistence(timeout: 10), "the button names the downloads it deletes")
        XCTAssertTrue(text(app, "Sign out of Living room server?").exists)
        XCTAssertTrue(text(app, "Stays on the server").exists)
        shot(app, "Account & devices — sign out of this device")
        dismissDialog(app, keep: "Stay signed in")
        XCTAssertTrue(app.buttons["account-signOut"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.textFields["Username"].exists, "Stay signed in stays")
    }

    @MainActor
    func testTheRootHasTheWebsGroupsAndNoSignOut() {
        let app = launch([])
        defer { app.terminate() }
        XCTAssertTrue(app.buttons["settings-playback"].waitForExistence(timeout: 20))
        for heading in ["YOU", "SERVER", "ABOUT"] {
            XCTAssertTrue(app.staticTexts[heading].exists, heading)
        }
        XCTAssertTrue(app.buttons["settings-account"].exists)
        XCTAssertTrue(app.buttons["settings-about"].label.contains("About WatchMyMedia"))
        XCTAssertFalse(app.buttons["Sign out"].exists, "Sign out moved to Account & devices")
        tap(app.buttons["settings-accountCard"])
        XCTAssertTrue(app.buttons["account-signOut"].waitForExistence(timeout: 10), "the account opens Account & devices")
    }

    @MainActor
    func testAddLanguageFindsALanguageByNameAndSavesIt() {
        let app = launch(["-fakesAudioLanguages", "en", "-settingsPage", "subtitles"])
        defer { app.terminate() }
        tap(app.buttons["audio-add-language"])
        let search = app.searchFields.firstMatch
        XCTAssertTrue(search.waitForExistence(timeout: 10))
        search.tap()
        search.typeText("engl")
        let english = app.buttons["add-language-en"]
        XCTAssertTrue(english.waitForExistence(timeout: 10))
        XCTAssertEqual(english.label, "English, added", "what the list holds is marked")
        XCTAssertFalse(english.isEnabled)
        search.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: 4) + "portu")
        let brazil = app.buttons["add-language-pt-BR"]
        XCTAssertTrue(brazil.waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["add-language-pt-PT"].exists)
        shot(app, "Add language — searched")
        brazil.tap()
        XCTAssertTrue(search.waitForNonExistence(timeout: 10), "choosing one closes the sheet")
        XCTAssertTrue(app.buttons["audio-language-pt-BR"].waitForExistence(timeout: 10), "and adds it to the list")
    }

    @MainActor
    func testALinkToAPageThatIsntOfferedSaysWhy() {
        let app = launch(["-fakesMember", "-settingsPage", "subtitles"])
        defer { app.terminate() }
        // The fakes' server is too old for languages unless asked otherwise.
        let keptOut = app.descendants(matching: .any)["kept-out"]
        XCTAssertTrue(keptOut.waitForExistence(timeout: 20))
        XCTAssertTrue(keptOut.label.contains("This server needs an update first"), keptOut.label)
        shot(app, "Kept out — old server")
    }

    // MARK: Helpers

    @MainActor
    private func launch(_ extra: [String]) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-tab", "settings"] + extra
        app.launch()
        return app
    }

    @MainActor
    private func steps(_ app: XCUIApplication) -> XCUIElement {
        app.descendants(matching: .any)["task-steps"]
    }

    @MainActor
    private func text(_ app: XCUIApplication, _ text: String) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS[c] %@", text)).firstMatch
    }

    @MainActor
    private func typeCode(_ code: String, in app: XCUIApplication) {
        let field = app.textFields["Code"].firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 10))
        field.tap()
        field.typeText(code)
    }

    /// A button in the dialog showing. On iOS 27 a confirmation dialog is a
    /// popover from the button that asked, on iPhone as on iPad, so a row's
    /// own button with the same label stays outside it.
    @MainActor
    private func dialogButton(_ app: XCUIApplication, _ label: String, prefix: Bool = false) -> XCUIElement {
        let predicate = NSPredicate(format: prefix ? "label BEGINSWITH %@" : "label == %@", label)
        return app.popovers.firstMatch.buttons.matching(predicate).firstMatch
    }

    /// Answers a dialog with the button that keeps things. On iOS 27 the
    /// dialog grows from the button that asked and shows no such button (an
    /// iPad's popover never does): a tap outside it is the same answer.
    @MainActor
    private func dismissDialog(_ app: XCUIApplication, keep: String) {
        let button = app.popovers.firstMatch.buttons[keep]
        if button.exists && button.isHittable {
            button.tap()
        } else if app.otherElements["PopoverDismissRegion"].firstMatch.exists {
            app.otherElements["PopoverDismissRegion"].firstMatch.tap()
        } else {
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.08)).tap()
        }
    }

    @MainActor
    private func chooseServer(_ name: String, in app: XCUIApplication) {
        let row = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", name)).firstMatch
        tap(row)
    }

    /// Drags an iPad window's corner until it is `fraction` of the screen wide.
    @MainActor
    private func resizeWindow(_ app: XCUIApplication, toWidth fraction: CGFloat) {
        let corner = app.coordinate(withNormalizedOffset: CGVector(dx: 1, dy: 1)).withOffset(CGVector(dx: -3, dy: -3))
        corner.press(forDuration: 0.5, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: fraction, dy: 0.9)))
        let resized = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in app.windows.firstMatch.frame.width < 600 }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [resized], timeout: 10), .completed, "the window went compact")
    }

    @MainActor
    private func swipeSheetDown(_ app: XCUIApplication) {
        let top = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.12))
        top.press(forDuration: 0.05, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.9)))
    }

    @MainActor
    private func tap(_ element: XCUIElement) {
        // A row low on a long page is below the fold until scrolled to.
        if element.waitForExistence(timeout: 10) {
            let app = app(of: element)
            for _ in 0..<6 where !element.isHittable {
                if pad, app.keyboards.firstMatch.exists {
                    // An iPad's keyboard has the middle of the screen, where a
                    // swipe on the app would start: drag the page above it.
                    let from = app.coordinate(withNormalizedOffset: CGVector(dx: 0.65, dy: 0.33))
                    from.press(forDuration: 0.05, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.65, dy: 0.19)))
                } else {
                    app.swipeUp(velocity: .slow)
                }
            }
        }
        let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == true AND hittable == true"), object: element)
        XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 15), .completed, "\(element) to tap")
        element.tap()
    }

    /// The app an element belongs to; these tests launch only one.
    @MainActor
    private func app(of element: XCUIElement) -> XCUIApplication { XCUIApplication() }

    @MainActor
    private func shot(_ app: XCUIApplication, _ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
