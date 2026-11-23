import XCTest

/// Sign-in and the server picker with the Siri Remote, on the fakes. Sign-in
/// opens on New code, in a row with Use a password instead and Another
/// server, which Up and Down leave alone; the password form opens on its
/// name; Menu goes from the form back to the code, and from the code to
/// choosing a server. The picker is one column: the servers by name and
/// where each is, then Enter an address, with no address or version on a row.
/// The fakes approve a code eight seconds after it shows, so the walk on the
/// code asks for a new one first. Runs in `make apple-tv-test-sim`.
final class SignInFocusTests: XCTestCase {
    @MainActor private var remote: XCUIRemote { XCUIRemote.shared }

    override func setUp() async throws { continueAfterFailure = false }

    @MainActor
    private func shot(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    @MainActor
    func testTheCodeOpensOnNewCodeAndMenuGoesToChoosingAServer() {
        let app = XCUIApplication()
        app.launchArguments = ["-fakesSignedOut"]
        app.launch()
        let newCode = app.buttons["signIn-newCode"]
        XCTAssertTrue(newCode.waitUntilExists(timeout: 15))
        XCTAssertTrue(newCode.waitForFocus(), "focus rests on New code: \(app.focusedButton.label)")
        // A fresh code, so the fakes' approval doesn't cut the walk short.
        remote.press(.select)
        XCTAssertTrue(app.staticTexts["Sign in with your phone"].waitUntilExists(timeout: 10))
        XCTAssertTrue(app.staticTexts["Living room server"].exists, "the server is called by its name")
        XCTAssertFalse(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "This device")).firstMatch.exists, "no device line")
        XCTAssertFalse(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "v0.9.3")).firstMatch.exists, "no version")
        XCTAssertTrue(newCode.waitForFocus())
        shot("Sign in, the code")

        let password = app.buttons["Use a password instead"]
        let another = app.buttons["Another server"]
        app.press(.right, thenWaitForFocusToMove: 3)
        XCTAssertTrue(password.waitForFocus(), "Right: Use a password instead")
        app.press(.right, thenWaitForFocusToMove: 3)
        XCTAssertTrue(another.waitForFocus(), "Right: Another server")
        remote.press(.right)
        remote.press(.up)
        remote.press(.down)
        XCTAssertTrue(another.waitForFocus(), "Right at the end, Up and Down stay put: \(app.focusedButton.label)")
        app.press(.left, thenWaitForFocusToMove: 3)
        app.press(.left, thenWaitForFocusToMove: 3)
        XCTAssertTrue(newCode.waitForFocus(), "Left goes back along the row")

        // The password form, on its name; Menu back to the code.
        app.press(.right, thenWaitForFocusToMove: 3)
        XCTAssertTrue(password.waitForFocus())
        remote.press(.select)
        let name = app.textFields["Name"]
        XCTAssertTrue(name.waitUntilExists(timeout: 10), "the password form is up")
        XCTAssertTrue(name.waitForFocus(), "on the name")
        XCTAssertTrue(app.staticTexts["Type on your iPhone"].exists)
        shot("Sign in with a password")
        remote.press(.menu)
        XCTAssertTrue(newCode.waitUntilExists(timeout: 10), "Menu goes back to the code")
        XCTAssertTrue(newCode.waitForFocus(), "on New code: \(app.focusedButton.label)")

        // Menu from the code: choosing a server.
        remote.press(.menu)
        XCTAssertTrue(app.staticTexts["Choose a server"].waitUntilExists(timeout: 10), "Menu from the code goes to choosing a server")
    }

    @MainActor
    func testThePickerIsOneListWithEnterAnAddressLast() {
        let app = XCUIApplication()
        app.launchArguments = ["-fakesNoServer"]
        app.launch()
        let living = app.buttons["picker-server-srv-living"]
        XCTAssertTrue(living.waitUntilExists(timeout: 15))
        XCTAssertTrue(living.waitForFocus(timeout: 10), "the list opens on its first server: \(app.focusedButton.label)")
        XCTAssertTrue(living.label.contains("On this network"), living.label)
        XCTAssertFalse(living.label.contains("192.168") || living.label.contains("v0.9"), "no address or version on a row: \(living.label)")
        shot("Choose a server")
        let rows = ["picker-server-srv-office", "picker-server-srv-remote", "picker-server-srv-nas"].map { app.buttons[$0] }
        for row in rows {
            app.press(.down, thenWaitForFocusToMove: 3)
            XCTAssertTrue(row.waitForFocus(), "Down to the next server: \(app.focusedButton.label)")
        }
        XCTAssertTrue(rows[2].label.contains("Too old for this app"), rows[2].label)
        app.press(.down, thenWaitForFocusToMove: 3)
        let address = app.textFields["Server address"]
        XCTAssertTrue(address.waitForFocus(), "Enter an address is the last row")
        remote.press(.right)
        XCTAssertTrue(address.waitForFocus(), "Right stays put")
        app.press(.up, thenWaitForFocusToMove: 3)
        XCTAssertTrue(rows[2].waitForFocus(), "Up goes back up the list")
    }
}
