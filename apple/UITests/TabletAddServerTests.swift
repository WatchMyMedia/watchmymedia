import XCTest
import UIKit

/// Add a server in the iPad's Settings split view, and switching between the
/// servers it leaves signed in. Runs against AppServices.fake in one app
/// launch: remembered fake accounts are deliberately in-memory. These checks
/// rotate an iPad; they do not resize its window or claim coverage of compact
/// Split View.
final class TabletAddServerTests: XCTestCase {
    override func setUp() async throws { continueAfterFailure = false }

    @MainActor
    func testUnsignedPasswordSelectionStaysInSettingsAndKeepsTheFormAcrossRotation() throws {
        let app = try launchPicker()
        defer { app.terminate(); XCUIDevice.shared.orientation = .portrait }
        choose("Office Mac mini", in: app)

        let name = app.textFields["add-name"]
        XCTAssertTrue(name.waitForExistence(timeout: 15))
        assertLockedSettingsShell(app)
        type("Robin", into: name)
        let password = app.secureTextFields["add-password"]
        type("unfinished", into: password)
        let concealedPassword = password.value as? String
        shot(app, "Add a server — unsigned password pane")

        XCUIDevice.shared.orientation = .portrait
        XCTAssertTrue(name.waitForExistence(timeout: 10))
        XCTAssertEqual(name.value as? String, "Robin")
        XCTAssertEqual(password.value as? String, concealedPassword)
        XCTAssertTrue(app.buttons["task-cancel"].exists)
        shot(app, "Add a server — password form after portrait rotation")

        XCUIDevice.shared.orientation = .landscapeLeft
        XCTAssertTrue(name.waitForExistence(timeout: 10))
        XCTAssertEqual(name.value as? String, "Robin")
        assertLockedSettingsShell(app)
    }

    @MainActor
    func testCancelRestoresTheInitialServerAndAccount() throws {
        let app = try launchPicker()
        defer { app.terminate(); XCUIDevice.shared.orientation = .portrait }
        choose("Office Mac mini", in: app)
        XCTAssertTrue(app.textFields["add-name"].waitForExistence(timeout: 15))
        assertLockedSettingsShell(app)

        let cancel = app.buttons["task-cancel"].firstMatch
        waitForHittable(cancel)
        cancel.tap()
        assertServerPage(app, server: "Living room server", user: "Sam")
        XCTAssertFalse(app.textFields["add-name"].exists)
        XCTAssertTrue(app.buttons["settings-account"].firstMatch.isEnabled)
        shot(app, "Add a server — cancellation restored Sam on Living room server")
    }

    @MainActor
    func testAddAServerThenSwitchBetweenTheTwoWithoutAnotherPassword() throws {
        let app = try launchPicker()
        defer { app.terminate(); XCUIDevice.shared.orientation = .portrait }
        // Living room server is registered already, so the picker doesn't offer it again.
        XCTAssertFalse(app.buttons["add-server-srv-living"].exists)
        XCTAssertTrue(element(in: app, containing: "Already added: Living room server").exists)
        choose("Office Mac mini", in: app)
        let name = app.textFields["add-name"]
        XCTAssertTrue(name.waitForExistence(timeout: 15))
        type("Robin", into: name)
        type("password", into: app.secureTextFields["add-password"])
        app.buttons["add-sign-in"].firstMatch.tap()
        finishAdding(app)

        openSettings(app)
        assertServerPage(app, server: "Office Mac mini", user: "Robin")

        // Switching back is a server's page away, and asks for no password.
        switchTo("srv-living", named: "Living room server", in: app)
        XCTAssertFalse(app.textFields["add-name"].exists)
        openSettings(app)
        assertServerPage(app, server: "Living room server", user: "Sam")

        // The account added in this test is kept too, independently
        // of the initial Sam account supplied by the fakes.
        switchTo("srv-office", named: "Office Mac mini", in: app)
        XCTAssertFalse(app.textFields["add-name"].exists)
        openSettings(app)
        assertServerPage(app, server: "Office Mac mini", user: "Robin")
        shot(app, "Settings — Robin on Office Mac mini again, without a password")
    }

    @MainActor
    private func launchPicker() throws -> XCUIApplication {
        guard UIDevice.current.userInterfaceIdiom == .pad else {
            throw XCTSkip("Add a server in the Settings split view runs on an iPad simulator.")
        }
        XCUIDevice.shared.orientation = .landscapeLeft
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-tab", "settings", "-settingsPage", "server", "-addServer"]
        app.launch()
        XCTAssertTrue(app.navigationBars["Add a server"].waitForExistence(timeout: 20))
        XCTAssertTrue(app.navigationBars["Settings"].exists, "The Settings sidebar stays beside the picker.")
        return app
    }

    @MainActor
    private func choose(_ server: String, in app: XCUIApplication) {
        let row = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", server)).firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 15), "The fictional server is in the picker.")
        waitForHittable(row)
        row.tap()
    }

    /// Added: what changed, then Done shows the new server's Home.
    @MainActor
    private func finishAdding(_ app: XCUIApplication) {
        let done = app.buttons["add-done"]
        XCTAssertTrue(done.waitForExistence(timeout: 20))
        XCTAssertTrue(element(in: app, containing: "It doesn’t ask for a password again").exists)
        waitForHittable(done)
        done.tap()
        XCTAssertTrue(app.navigationBars["Home"].waitForExistence(timeout: 15))
    }

    /// From Settings › Server: the server's row under Your servers, then
    /// Switch to this server on its page; the app lands on its Home.
    @MainActor
    private func switchTo(_ id: String, named name: String, in app: XCUIApplication) {
        let row = app.buttons["server-row-\(id)"].firstMatch
        waitForHittable(row)
        row.tap()
        XCTAssertTrue(app.navigationBars[name].waitForExistence(timeout: 10))
        let button = app.buttons["server-switch-to"].firstMatch
        waitForHittable(button)
        button.tap()
        let note = app.descendants(matching: .any)["server-note"]
        XCTAssertTrue(note.waitForExistence(timeout: 15))
        XCTAssertEqual(note.label, "Now on \(name)")
        XCTAssertTrue(app.navigationBars["Home"].waitForExistence(timeout: 15))
    }

    @MainActor
    private func assertLockedSettingsShell(_ app: XCUIApplication) {
        XCTAssertTrue(app.navigationBars["Settings"].exists)
        XCTAssertTrue(app.navigationBars["Add a server"].exists)
        XCTAssertTrue(element(in: app, containing: "Admin · Living room server").exists)
        let account = app.buttons["settings-account"].firstMatch
        XCTAssertTrue(account.exists)
        XCTAssertFalse(account.isEnabled, "A server not yet signed in to must not expose account mutations.")
        let home = app.buttons["Home"].firstMatch
        // The adaptable tab bar can report enabled through accessibility
        // even when TabContent.disabled is set. Exercise the selection gate
        // instead of treating that system-reported flag as the behavior.
        if home.exists && home.isEnabled && home.isHittable { home.tap() }
        XCTAssertTrue(app.textFields["add-name"].waitForExistence(timeout: 5))
        waitForHittable(app.buttons["task-cancel"].firstMatch)
        XCTAssertTrue(app.navigationBars["Settings"].exists)
        XCTAssertTrue(app.navigationBars["Add a server"].exists)
        XCTAssertFalse(app.navigationBars["Home"].exists, "Tapping Home must keep the unfinished task inside Settings.")
    }

    @MainActor
    private func openSettings(_ app: XCUIApplication) {
        let settings = app.buttons["Settings"].firstMatch
        waitForHittable(settings)
        settings.tap()
        XCTAssertTrue(app.navigationBars["Server"].waitForExistence(timeout: 15))
    }

    @MainActor
    private func assertServerPage(_ app: XCUIApplication, server: String, user: String) {
        XCTAssertTrue(app.navigationBars["Server"].waitForExistence(timeout: 15))
        XCTAssertTrue(element(in: app, containing: "Admin · \(server)").exists)
        XCTAssertTrue(element(in: app, containing: "\(user) · admin").exists)
        XCTAssertTrue(app.buttons["server-add"].firstMatch.exists)
    }

    @MainActor
    private func element(in app: XCUIApplication, containing text: String) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", text)).firstMatch
    }

    /// Types into a field once it has the keyboard: on a busy iPad a tap can
    /// land before the field takes focus, and typing then goes nowhere.
    @MainActor
    private func type(_ text: String, into field: XCUIElement) {
        for _ in 0..<4 {
            waitForHittable(field)
            field.tap()
            let focused = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in (field.value(forKey: "hasKeyboardFocus") as? Bool) == true }, object: nil)
            if XCTWaiter.wait(for: [focused], timeout: 3) == .completed { break }
        }
        field.typeText(text)
    }

    @MainActor
    private func waitForHittable(_ element: XCUIElement) {
        let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == true AND hittable == true"), object: element)
        XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 15), .completed)
    }

    @MainActor
    private func shot(_ app: XCUIApplication, _ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
