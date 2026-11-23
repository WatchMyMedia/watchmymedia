import XCTest

/// A focused button that disables itself keeps focus
/// (checked on the tvOS 27 simulator): dimmed, it holds focus while
/// its request runs or at the end of a list, and the remote moves on from it.
/// These guard that, for sign-in, Mark watched and Move up. On the fakes every
/// request takes 350 ms. Runs in `make apple-tv-test-sim`.
final class BusyFocusTests: XCTestCase {
    @MainActor private var remote: XCUIRemote { XCUIRemote.shared }

    override func setUp() async throws {
        continueAfterFailure = false
    }

    /// Waits until `element` has focus.
    @MainActor
    private func focused(_ element: XCUIElement, timeout: TimeInterval = 10) -> Bool {
        element.waitForFocus(timeout: timeout)
    }

    /// Waits until `element`'s label ends with `text`.
    @MainActor
    private func label(_ element: XCUIElement, endsWith text: String, timeout: TimeInterval = 10) -> Bool {
        element.wait(until: NSPredicate(format: "label ENDSWITH %@", text), timeout: timeout)
    }

    /// Selects the focused field and types into it one character at a time:
    /// XCUITest drops characters typed into a tvOS field at once when the Mac
    /// is busy. Menu puts the keyboard away.
    @MainActor
    private func type(_ text: String) {
        remote.press(.select)
        for character in text { XCUIApplication().typeText(String(character)) }
        remote.press(.menu)
    }

    @MainActor
    func testAWrongPasswordLeavesFocusOnSignIn() {
        let app = XCUIApplication()
        app.launchArguments = ["-fakesSignedOut"]
        app.launch()
        let usePassword = app.buttons["Use a password instead"]
        XCTAssertTrue(usePassword.waitUntilExists(timeout: 15), "sign-in opens on the pairing code")
        XCTAssertTrue(focused(app.buttons["signIn-newCode"]), "with focus on New code, where a stray press does no harm")
        for _ in 0..<3 where !usePassword.hasFocus { remote.press(.right) }
        XCTAssertTrue(focused(usePassword), "Use a password instead is beside it")
        remote.press(.select)

        let name = app.textFields["Name"]
        XCTAssertTrue(name.waitUntilExists(timeout: 10), "the password form is up")
        XCTAssertTrue(focused(name), "on the name")
        type("sam")
        let password = app.secureTextFields["Password"]
        // A press just after the keyboard goes can be lost.
        for _ in 0..<3 where !password.hasFocus {
            remote.press(.down)
            _ = focused(password, timeout: 1)
        }
        XCTAssertTrue(focused(password))
        type("nope")
        // Down reaches the buttons (a press just after the keyboard goes can be
        // lost) on the one nearest the field; Left then reaches Sign in.
        let signIn = app.buttons["signInButton"]
        let row = [signIn, app.buttons["Use a code instead"], app.buttons["Another server"]]
        for _ in 0..<4 where !row.contains(where: \.hasFocus) {
            remote.press(.down)
            _ = eventually(timeout: 1) { row.contains(where: \.hasFocus) }
        }
        for _ in 0..<3 where !signIn.hasFocus { remote.press(.left) }
        XCTAssertTrue(focused(signIn), "Sign in is below the password")

        remote.press(.select)
        XCTAssertTrue(app.staticTexts["Wrong name or password."].waitUntilExists(timeout: 10), "the server refuses the password")
        XCTAssertTrue(signIn.hasFocus, "focus stays on Sign in while it works and after")
        remote.press(.right)
        XCTAssertTrue(focused(app.buttons["Use a code instead"]), "and the remote moves on from there")
    }

    @MainActor
    func testMarkWatchedKeepsFocusWhileItWorks() {
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-open", "watchmymedia://shows/100?season=3&episode=100037"]
        app.launch()
        let play = app.buttons["Play · 52 min"]
        XCTAssertTrue(play.waitUntilExists(timeout: 15))
        XCTAssertTrue(focused(play))
        remote.press(.right)
        let more = app.buttons["episode-more"]
        XCTAssertTrue(focused(more), "More is beside Play")
        remote.press(.select)
        // Mark watched is a row of More's panel, which stays up while it works.
        let mark = app.buttons["episodeWatchToggle"]
        XCTAssertTrue(mark.waitUntilExists(timeout: 5), "More's panel is up")
        for _ in 0..<6 where !mark.hasFocus {
            remote.press(.down)
            _ = focused(mark, timeout: 1)
        }
        XCTAssertTrue(focused(mark), "Mark watched is in More")
        XCTAssertTrue(mark.label.hasSuffix("Mark watched"), mark.label)

        remote.press(.select)
        XCTAssertTrue(label(mark, endsWith: "Mark unwatched"), "the episode is marked")
        XCTAssertTrue(mark.hasFocus, "focus stays on the row while it works and after")
        remote.press(.menu)
        XCTAssertTrue(mark.waitUntilGone(timeout: 5), "Menu closes the panel")
        XCTAssertTrue(focused(more), "and focus is back on More")
    }

    @MainActor
    func testMoveUpToTheTopKeepsFocus() {
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-fakesRows", "-tab", "settings", "-homeRowsSettings"]
        app.launch()
        let ups = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Move ' AND label ENDSWITH ' up'"))
        XCTAssertTrue(ups.firstMatch.waitUntilExists(timeout: 20), "Home rows is open")
        // The first row's Move up is disabled, so focus starts on the second's.
        let second = ups.element(boundBy: 1)
        let title = second.label
        XCTAssertTrue(focused(second), "focus starts on the second row's Move up")

        remote.press(.select)
        let moved = app.buttons[title]
        XCTAssertTrue(ups.element(boundBy: 0).wait(until: NSPredicate(format: "label == %@", title), timeout: 10), "the row is first now")
        XCTAssertFalse(moved.isEnabled, "its Move up can't move it further")
        XCTAssertTrue(moved.hasFocus, "and keeps focus at the top")
        remote.press(.right)
        XCTAssertTrue(focused(app.buttons[title.replacingOccurrences(of: " up", with: " down")]), "the remote moves on to Move down")
    }
}
