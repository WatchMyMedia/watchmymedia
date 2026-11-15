import XCTest
import UIKit

/// Settings' tasks on the fakes (PLAN-APPLE-SETTINGS.md §10): Link a TV and
/// Switch server from the first step to the last, Back and Cancel at each
/// step, swiping a sheet down with something typed, Rename, and both
/// sign-out questions. On an iPhone, and on an iPad, where the tasks are
/// form sheets and Switch server stays in the detail column.
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
        let stop = dialogButton(app, "Stop linking")
        XCTAssertTrue(stop.waitForExistence(timeout: 10), "a typed code isn't thrown away by a swipe")
        shot(app, "Link a TV — asks before closing")
        dismissDialog(app, keep: "Keep linking")
        XCTAssertTrue(stop.waitForNonExistence(timeout: 5))
        XCTAssertTrue(app.buttons["link-check"].exists, "keeping on leaves the sheet up")
        swipeSheetDown(app)
        tap(dialogButton(app, "Stop linking"))
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

    // MARK: Switch server

    @MainActor
    func testSwitchServerFromFirstStepToLastWithBackAndAWrongPassword() {
        let app = launch(["-settingsPage", "server"])
        defer { app.terminate() }
        tap(app.buttons["server-switch"])
        XCTAssertTrue(steps(app).waitForExistence(timeout: 10))
        XCTAssertEqual(steps(app).label, "Step 1 of 3, Choose a server")
        chooseServer("Office Mac mini", in: app)
        XCTAssertTrue(app.textFields["switch-name"].waitForExistence(timeout: 15))
        XCTAssertEqual(steps(app).label, "Step 2 of 3, Sign in")

        // Back returns to the servers, still on the first one.
        tap(app.buttons["task-back"])
        XCTAssertTrue(app.textFields["switch-address"].waitForExistence(timeout: 10))
        XCTAssertTrue(text(app, "Living room server").exists)
        chooseServer("Office Mac mini", in: app)

        let name = app.textFields["switch-name"]
        XCTAssertTrue(name.waitForExistence(timeout: 15))
        name.tap()
        name.typeText("Robin")
        let password = app.secureTextFields["switch-password"]
        password.tap()
        password.typeText("wrong")
        tap(app.buttons["switch-sign-in"])
        XCTAssertTrue(text(app, "You’re still on Living room server").waitForExistence(timeout: 10), "a refusal says where you still are")
        shot(app, "Switch server — wrong password")

        password.tap()
        password.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: 5) + "password")
        tap(app.buttons["switch-sign-in"])
        XCTAssertTrue(app.buttons["switch-done"].waitForExistence(timeout: 15))
        XCTAssertEqual(steps(app).label, "Switched")
        XCTAssertTrue(text(app, "You’re on Office Mac mini").exists)
        XCTAssertTrue(text(app, "switching back needs no password").exists || text(app, "It needs no password").exists)
        shot(app, "Switch server — switched")
        tap(app.buttons["switch-done"])
        XCTAssertTrue(app.buttons["switch-done"].waitForNonExistence(timeout: 10))
        XCTAssertTrue(app.navigationBars["Home"].waitForExistence(timeout: 15), "Done shows the new server's Home")
    }

    @MainActor
    func testSwitchServerCancelsAtEachStepAndStaysOnTheFirstServer() {
        let app = launch(["-settingsPage", "server"])
        defer { app.terminate() }
        tap(app.buttons["server-switch"])
        tap(app.buttons["task-cancel"])
        XCTAssertTrue(app.buttons["task-cancel"].waitForNonExistence(timeout: 10))
        XCTAssertTrue(text(app, "Living room server").exists)

        tap(app.buttons["server-switch"])
        chooseServer("Office Mac mini", in: app)
        XCTAssertTrue(app.textFields["switch-name"].waitForExistence(timeout: 15))
        tap(app.buttons["task-cancel"])
        XCTAssertTrue(app.textFields["switch-name"].waitForNonExistence(timeout: 10))
        XCTAssertTrue(app.navigationBars["Server"].waitForExistence(timeout: 10))
        XCTAssertTrue(text(app, "Sam · admin").exists, "Cancel keeps Sam on Living room server")
    }

    // MARK: Account & devices

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
        XCTAssertTrue(app.buttons["account-rename"].label.contains("Den iPhone"), app.buttons["account-rename"].label)
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
            for _ in 0..<6 where !element.isHittable { app(of: element).swipeUp(velocity: .slow) }
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
