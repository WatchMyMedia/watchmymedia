import XCTest
import UIKit

/// Runs against AppServices.fake in one app launch: remembered fake accounts
/// are deliberately in-memory. These checks rotate an iPad; they do not resize
/// its window or claim coverage of compact Split View.
final class TabletServerSwitchTests: XCTestCase {
    override func setUp() async throws { continueAfterFailure = false }

    @MainActor
    func testUnsignedPasswordSelectionStaysInSettingsAndKeepsTheFormAcrossRotation() throws {
        let app = try launchPicker()
        defer { app.terminate(); XCUIDevice.shared.orientation = .portrait }
        choose("Office Mac mini", in: app)

        let name = app.textFields["Username"]
        XCTAssertTrue(name.waitForExistence(timeout: 15))
        assertLockedSettingsShell(app)
        name.tap()
        name.typeText("Robin")
        let password = app.secureTextFields["Password"]
        password.tap()
        password.typeText("unfinished")
        let concealedPassword = password.value as? String
        shot(app, "Settings switch — unsigned password pane")

        XCUIDevice.shared.orientation = .portrait
        XCTAssertTrue(name.waitForExistence(timeout: 10))
        XCTAssertEqual(name.value as? String, "Robin")
        XCTAssertEqual(password.value as? String, concealedPassword)
        XCTAssertTrue(app.buttons["Cancel"].exists)
        shot(app, "Settings switch — password form after portrait rotation")

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
        XCTAssertTrue(app.textFields["Username"].waitForExistence(timeout: 15))
        assertLockedSettingsShell(app)

        let cancel = app.buttons["Cancel"].firstMatch
        waitForHittable(cancel)
        cancel.tap()
        assertServerPage(app, server: "Living room server", user: "Sam")
        XCTAssertFalse(app.textFields["Username"].exists)
        XCTAssertTrue(app.buttons["Sign out"].firstMatch.isEnabled)
        shot(app, "Settings switch — cancellation restored Sam on Living room server")
    }

    @MainActor
    func testSignInThenSwitchBetweenRememberedAccountsWithoutAnotherPassword() throws {
        let app = try launchPicker()
        defer { app.terminate(); XCUIDevice.shared.orientation = .portrait }
        choose("Office Mac mini", in: app)
        let name = app.textFields["Username"]
        XCTAssertTrue(name.waitForExistence(timeout: 15))
        name.tap()
        name.typeText("Robin")
        let password = app.secureTextFields["Password"]
        password.tap()
        password.typeText("password")
        app.buttons["Sign in"].firstMatch.tap()
        XCTAssertTrue(app.navigationBars["Home"].waitForExistence(timeout: 20))

        openSettings(app)
        assertServerPage(app, server: "Office Mac mini", user: "Robin")
        openPicker(app)
        choose("Living room server", signedInAs: "Sam", in: app)
        XCTAssertTrue(app.navigationBars["Home"].waitForExistence(timeout: 15))
        XCTAssertFalse(app.textFields["Username"].exists)
        openSettings(app)
        assertServerPage(app, server: "Living room server", user: "Sam")

        // The account created in this test is remembered too, independently
        // of the initial Sam account supplied by the fakes.
        openPicker(app)
        choose("Office Mac mini", signedInAs: "Robin", in: app)
        XCTAssertTrue(app.navigationBars["Home"].waitForExistence(timeout: 15))
        XCTAssertFalse(app.textFields["Username"].exists)
        openSettings(app)
        assertServerPage(app, server: "Office Mac mini", user: "Robin")
        shot(app, "Settings switch — remembered Robin account restored")
    }

    @MainActor
    private func launchPicker() throws -> XCUIApplication {
        guard UIDevice.current.userInterfaceIdiom == .pad else {
            throw XCTSkip("Server switching in the Settings split view runs on an iPad simulator.")
        }
        XCUIDevice.shared.orientation = .landscapeLeft
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-tab", "settings", "-settingsPage", "server", "-switchServer"]
        app.launch()
        XCTAssertTrue(app.navigationBars["Switch server"].waitForExistence(timeout: 20))
        XCTAssertTrue(app.navigationBars["Settings"].exists, "The Settings sidebar stays beside the picker.")
        return app
    }

    @MainActor
    private func choose(_ server: String, signedInAs user: String? = nil, in app: XCUIApplication) {
        let row = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", server)).firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 15), "The fictional server is in the picker.")
        if let user { XCTAssertTrue(row.label.contains("signed in as \(user)")) }
        waitForHittable(row)
        row.tap()
    }

    @MainActor
    private func assertLockedSettingsShell(_ app: XCUIApplication) {
        XCTAssertTrue(app.navigationBars["Settings"].exists)
        XCTAssertTrue(app.navigationBars["Sign in"].exists)
        XCTAssertTrue(element(in: app, containing: "Admin · Living room server").exists)
        let signOut = app.buttons["Sign out"].firstMatch
        XCTAssertTrue(signOut.exists)
        XCTAssertFalse(signOut.isEnabled, "An unsigned server switch must not expose account mutations.")
        let home = app.buttons["Home"].firstMatch
        // The adaptable tab bar can report enabled through accessibility
        // even when TabContent.disabled is set. Exercise the selection gate
        // instead of treating that system-reported flag as the behavior.
        if home.exists && home.isEnabled && home.isHittable { home.tap() }
        XCTAssertTrue(app.textFields["Username"].waitForExistence(timeout: 5))
        waitForHittable(app.buttons["Cancel"].firstMatch)
        XCTAssertTrue(app.navigationBars["Settings"].exists)
        XCTAssertTrue(app.navigationBars["Sign in"].exists)
        XCTAssertFalse(app.navigationBars["Home"].exists, "Tapping Home must keep the unsigned switch inside Settings.")
    }

    @MainActor
    private func openSettings(_ app: XCUIApplication) {
        let settings = app.buttons["Settings"].firstMatch
        waitForHittable(settings)
        settings.tap()
        XCTAssertTrue(app.navigationBars["Server"].waitForExistence(timeout: 15))
    }

    @MainActor
    private func openPicker(_ app: XCUIApplication) {
        let switchServer = app.buttons["Switch server"].firstMatch
        waitForHittable(switchServer)
        switchServer.tap()
        XCTAssertTrue(app.navigationBars["Switch server"].waitForExistence(timeout: 10))
    }

    @MainActor
    private func assertServerPage(_ app: XCUIApplication, server: String, user: String) {
        XCTAssertTrue(app.navigationBars["Server"].waitForExistence(timeout: 15))
        XCTAssertTrue(element(in: app, containing: "Admin · \(server)").exists)
        XCTAssertTrue(element(in: app, containing: "\(user) · admin").exists)
        XCTAssertTrue(app.buttons["Switch server"].firstMatch.exists)
    }

    @MainActor
    private func element(in app: XCUIApplication, containing text: String) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", text)).firstMatch
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
