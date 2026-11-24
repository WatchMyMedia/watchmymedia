import XCTest
import UIKit

/// Several servers against two real servers, end to end. Each server needs
/// its own id and name and a title the same words find (`WMM_E2E_SEARCH`,
/// "harbor" unless set), so run it against two throwaway servers, never
/// against one copied from the other.
///
/// The first test signs in to the first server, adds the second from
/// Servers, switches back without a password, searches both, and plays the
/// second server's copy without switching: a title only the second has
/// opens from there; a title both have (by TMDB id) is one result that says
/// so, and its page's Versions plays the second server's copy. It leaves
/// the app on the first server, signed in to both.
///
/// The second test is for after that, with the second server stopped
/// (`WMM_E2E_SERVER_2_STOPPED=1`): the app switches to the first server,
/// choosing the second in Servers says it couldn't be reached and leaves
/// the app where it was, and search says the second didn't answer.
///
/// The third is for after the first too, with this device's sign-in on the
/// second server ended from somewhere else (Signed-in devices on another
/// device) and `WMM_E2E_SERVER_2_SIGNED_OUT=1`: the app shows the second
/// server's sign-in (at once when it was left on it, after switching to it
/// otherwise); Change server lists the first, which opens without a
/// password; and Servers then says the second is signed out here.
///
/// All are skipped without `WMM_E2E_SERVER` and `WMM_E2E_SERVER_2` (each
/// with its `_USER` and `_PASSWORD`), passed to xcodebuild with a
/// `TEST_RUNNER_` prefix. Every step leaves a screenshot in the test results.
final class ServersEndToEndTests: XCTestCase {
    private var app: XCUIApplication!

    private struct Account {
        let server: String
        let user: String
        let password: String
    }

    override func setUp() async throws { continueAfterFailure = false }

    /// The two servers and the words that find a title on each, or a skip.
    private func servers() throws -> (first: Account, second: Account, words: String) {
        let env = ProcessInfo.processInfo.environment
        guard let first = env["WMM_E2E_SERVER"], !first.isEmpty, let second = env["WMM_E2E_SERVER_2"], !second.isEmpty else {
            throw XCTSkip("Set WMM_E2E_SERVER and WMM_E2E_SERVER_2 (each with _USER and _PASSWORD) to run against two servers.")
        }
        return (Account(server: first, user: env["WMM_E2E_USER"] ?? "viewer", password: env["WMM_E2E_PASSWORD"] ?? ""),
                Account(server: second, user: env["WMM_E2E_USER_2"] ?? "viewer", password: env["WMM_E2E_PASSWORD_2"] ?? ""),
                env["WMM_E2E_SEARCH"] ?? "harbor")
    }

    @MainActor
    func testAddSwitchSearchAndOpenAcrossTwoServers() throws {
        let (first, second, words) = try servers()
        let env = ProcessInfo.processInfo.environment
        try XCTSkipIf(env["WMM_E2E_SERVER_2_STOPPED"] != nil || env["WMM_E2E_SERVER_2_SIGNED_OUT"] != nil, "this run is for after the first test")
        app = XCUIApplication()
        // A fresh install asks for the local network (Bonjour discovery): allow it.
        addUIInterruptionMonitor(withDescription: "Local network") { alert in
            for title in ["Allow", "OK"] where alert.buttons[title].exists {
                alert.buttons[title].tap()
                return true
            }
            return false
        }
        app.launch()

        // The first server: its address, then its account.
        let address = app.textFields.firstMatch
        if !address.waitForExistence(timeout: 15) { allowSystemAlert() }
        XCTAssertTrue(address.waitForExistence(timeout: 10), "the server picker shows first on a fresh install")
        type(first.server, into: address)
        app.buttons["Connect"].firstMatch.tap()
        type(first.user, into: app.textFields["Username"])
        type(first.password, into: app.secureTextFields["Password"])
        app.buttons["Sign in"].firstMatch.tap()
        XCTAssertTrue(app.navigationBars["Home"].waitForExistence(timeout: 20), "Home shows once signed in")
        let account = app.buttons["account-button"].firstMatch
        XCTAssertTrue(account.waitForExistence(timeout: 20))
        XCTAssertFalse(app.buttons["home-server-name"].exists, "with one server, Home doesn't name it")
        shot("1 Home on the first server")

        // Add the second from Servers: Choose a server, Sign in, Added.
        account.tap()
        tap(app.buttons["servers-add"])
        type(second.server, into: app.textFields["add-address"])
        tap(app.buttons["add-connect"])
        type(second.user, into: app.textFields["add-name"])
        type(second.password, into: app.secureTextFields["add-password"])
        tap(app.buttons["add-sign-in"])
        let done = app.buttons["add-done"]
        XCTAssertTrue(done.waitForExistence(timeout: 20), "the second server is added")
        XCTAssertTrue(text("signed in to 2 servers. Search looks in all of them.").exists)
        shot("2 Added")
        tap(done)

        // On the second server now; Servers lists both, the one in use first.
        XCTAssertTrue(waitUntil(timeout: 20) { account.exists && account.label.contains(", on ") }, account.label)
        let secondName = try XCTUnwrap(account.label.components(separatedBy: ", on ").last)
        account.tap()
        XCTAssertTrue(waitUntil { self.rows.count == 2 }, "both servers are in Servers")
        XCTAssertEqual(rows.element(boundBy: 0).value as? String, "Current")
        XCTAssertTrue(rows.element(boundBy: 0).label.hasPrefix(secondName), rows.element(boundBy: 0).label)
        let other = rows.element(boundBy: 1)
        let firstName = try XCTUnwrap(other.label.components(separatedBy: ", ").first)
        XCTAssertNotEqual(firstName, secondName, "each server has its own name")
        shot("3 Servers")

        // Back to the first: no password, no steps.
        other.tap()
        let note = app.descendants(matching: .any)["server-note"]
        XCTAssertTrue(note.waitForExistence(timeout: 15), "the note after a switch")
        XCTAssertEqual(note.label, "Now on \(firstName)")
        XCTAssertFalse(app.secureTextFields.firstMatch.exists, "a switch doesn't ask for a password")
        XCTAssertTrue(waitUntil { account.exists && account.label.hasSuffix(", on \(firstName)") }, account.label)

        // Search looks in both: a title only the second has names it; one both have is one result that says so.
        tap(tab("Search"))
        let field = app.searchFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 15))
        field.tap()
        field.typeText(words)
        let secondShort = secondName.hasSuffix(" server") ? String(secondName.dropLast(7)) : secondName
        let here = results(elsewhere: false).firstMatch, there = results(elsewhere: true).firstMatch
        // A row reads where it is in its label on iPhone, in its value under a poster on iPad.
        let shared = results(elsewhere: false).matching(NSPredicate(format: "label CONTAINS %@ OR value CONTAINS %@", " and \(secondShort)", " and \(secondShort)")).firstMatch
        XCTAssertTrue(waitUntil(timeout: 20) { there.exists || shared.exists }, "the second server's title is listed, or one both have")
        XCTAssertTrue(here.exists, "the first server's title is listed")
        if there.exists {
            XCTAssertTrue(there.label.contains("On \(secondName)") || (there.value as? String)?.contains("On \(secondName)") == true,
                          "a result on another server names it: \(there.label) / \(there.value ?? "")")
        }
        shot("4 Search across both")

        let play: XCUIElement
        if there.exists {
            // A title only the second server has opens from there, without switching.
            there.tap()
            play = app.buttons.matching(NSPredicate(format: "label == 'Play' OR label BEGINSWITH 'Resume ·'")).firstMatch
            XCTAssertTrue(play.waitForExistence(timeout: 20), "the title's page, from the second server")
        } else {
            // A title both have opens the first server's page, whose Versions lists the second's copies too.
            shared.tap()
            play = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Play this: \(secondShort),")).firstMatch
            XCTAssertTrue(play.waitForExistence(timeout: 20), "Versions lists the second server's copy")
            for _ in 0..<6 where !play.isHittable { app.swipeUp() }
        }
        XCTAssertFalse(note.exists, "opening it doesn't switch")
        shot("5 The title, with the second server's copy")

        // It plays from the second server.
        play.tap()
        let close = app.buttons["Close"]
        if !close.waitForExistence(timeout: 20) { tapPlayerVideo() }
        XCTAssertTrue(close.waitForExistence(timeout: 10), "the player opens with its controls")
        let position = app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", "Playback position")).firstMatch
        let started = try XCTUnwrap(position.value as? String)
        sleep(6)
        let pause = app.buttons["player-play-pause"]
        for _ in 0..<3 where !pause.exists {
            tapPlayerVideo()
            if pause.waitForExistence(timeout: 1) { break }
        }
        XCTAssertTrue(pause.exists)
        pause.tap()
        XCTAssertTrue(waitUntil { (position.value as? String) != started }, "the video advances")
        shot("6 Playing from the second server")
        XCTAssertTrue(close.waitForExistence(timeout: 5))
        close.tap()

        // Back from the title: Search with the same words, still on the first server.
        // Leaving the landscape player rotates back, and until that settles the button has no point to tap.
        tap(app.navigationBars.buttons.firstMatch)
        XCTAssertTrue(results(elsewhere: false).firstMatch.waitForExistence(timeout: 20), "the results are back")
        XCTAssertEqual(app.searchFields.firstMatch.value as? String, words, "the same words")
        shot("7 Back to the results")
    }

    @MainActor
    func testAStoppedServerIsSaidSoInSearchAndServers() throws {
        let (_, _, words) = try servers()
        try XCTSkipIf(ProcessInfo.processInfo.environment["WMM_E2E_SERVER_2_STOPPED"] == nil,
                      "Stop the second server after the first test and set WMM_E2E_SERVER_2_STOPPED.")
        app = XCUIApplication()
        app.launch()

        // Left on the second server, which no longer answers, Home's account button still opens
        // Servers, and the first server is a tap away. (The first test leaves the app on the
        // first server, and this step is skipped.)
        tap(tab("Home"))
        let account = app.buttons["account-button"].firstMatch
        XCTAssertTrue(account.waitForExistence(timeout: 30))
        let note = app.descendants(matching: .any)["server-note"]
        if text("You’re offline").waitForExistence(timeout: 10) {
            shot("1 On the stopped server")
            account.tap()
            XCTAssertTrue(waitUntil { self.rows.count == 2 })
            let running = rows.element(boundBy: 1)
            let name = try XCTUnwrap(running.label.components(separatedBy: ", ").first)
            running.tap()
            XCTAssertTrue(note.waitForExistence(timeout: 15), "the running server is one tap away")
            XCTAssertEqual(note.label, "Now on \(name)")
        }

        // On the running server: the other one is the stopped one.
        XCTAssertTrue(waitUntil(timeout: 20) { account.exists && account.isHittable })
        account.tap()
        XCTAssertTrue(waitUntil { self.rows.count == 2 })
        let runningName = try XCTUnwrap(rows.element(boundBy: 0).label.components(separatedBy: ", ").first)
        let stoppedName = try XCTUnwrap(rows.element(boundBy: 1).label.components(separatedBy: ", ").first)

        // Choosing the stopped one says so and leaves the app where it was.
        rows.element(boundBy: 1).tap()
        XCTAssertTrue(app.descendants(matching: .any)["servers-failure"].waitForExistence(timeout: 20))
        XCTAssertTrue(text("Couldn’t reach \(stoppedName)").exists)
        XCTAssertTrue(text("You’re still on \(runningName).").exists)
        XCTAssertTrue(app.buttons["servers-try-again"].exists)
        XCTAssertEqual(rows.element(boundBy: 0).value as? String, "Current", "still on the running server")
        shot("2 Servers, the stopped server")
        tap(app.buttons["servers-close"])

        // Search: the running server's title, and one line for the server that didn't answer.
        tap(tab("Search"))
        let field = app.searchFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 15))
        field.tap()
        field.typeText(words)
        XCTAssertTrue(results(elsewhere: false).firstMatch.waitForExistence(timeout: 20))
        XCTAssertTrue(text("\(stoppedName) didn’t answer, so its titles aren’t listed.").waitForExistence(timeout: 15))
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'search-retry-'")).firstMatch.exists, "with Try again")
        XCTAssertFalse(results(elsewhere: true).firstMatch.exists)
        shot("3 Search, one server not answering")
    }

    @MainActor
    func testASignInEndedElsewhereShowsItsSignInAndTheOtherServerIsATapAway() throws {
        let (first, _, _) = try servers()
        try XCTSkipIf(ProcessInfo.processInfo.environment["WMM_E2E_SERVER_2_SIGNED_OUT"] == nil,
                      "End this device's sign-in on the second server after the first test and set WMM_E2E_SERVER_2_SIGNED_OUT.")
        app = XCUIApplication()
        app.launch()

        // Left on the second server, which now refuses its sign-in, the app shows that server's
        // sign-in. Left on the first, it switches to the second (which answers as itself) and
        // finds the sign-in ended only then.
        let password = app.secureTextFields["Password"]
        if !password.waitForExistence(timeout: 15) {
            tap(tab("Home"))
            let account = app.buttons["account-button"].firstMatch
            XCTAssertTrue(account.waitForExistence(timeout: 20))
            account.tap()
            XCTAssertTrue(waitUntil { self.rows.count == 2 })
            rows.element(boundBy: 1).tap()
        }
        XCTAssertTrue(password.waitForExistence(timeout: 30), "the second server's sign-in")
        shot("1 The second server's sign-in")

        // Change server: the first is remembered and still signed in, so it opens without a password.
        tap(app.buttons["Change server"])
        let remembered = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "signed in as \(first.user)")).firstMatch
        XCTAssertTrue(remembered.waitForExistence(timeout: 15), "the first server is in the picker")
        shot("2 The picker")
        tap(remembered)
        XCTAssertTrue(app.navigationBars["Home"].waitForExistence(timeout: 20), "the first server's Home")
        XCTAssertFalse(app.secureTextFields.firstMatch.exists, "no password is asked for")

        // Servers lists both; the second says its sign-in ended here.
        let account = app.buttons["account-button"].firstMatch
        XCTAssertTrue(account.waitForExistence(timeout: 20))
        account.tap()
        XCTAssertTrue(waitUntil { self.rows.count == 2 }, "both servers stay in Servers")
        XCTAssertEqual(rows.element(boundBy: 0).value as? String, "Current")
        XCTAssertEqual(rows.element(boundBy: 1).value as? String, "Signed out here · sign in again")
        shot("3 Servers, one signed out here")
    }

    // MARK: Helpers

    /// The servers' rows in Servers, in the list's order.
    @MainActor
    private var rows: XCUIElementQuery {
        app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'servers-row-'"))
    }

    /// Search's results: the server in use's ("search-result-/movie/1"), or
    /// the other servers', whose identifiers start with their server's id.
    @MainActor
    private func results(elsewhere: Bool) -> XCUIElementQuery {
        let own = "identifier BEGINSWITH 'search-result-/'"
        return app.buttons.matching(NSPredicate(format: elsewhere ? "identifier BEGINSWITH 'search-result-' AND NOT (\(own))" : own))
    }

    /// A tab in the tab bar: a hidden button of the same name stands behind the tabs for ⌘F.
    @MainActor
    private func tab(_ name: String) -> XCUIElement {
        app.tabBars.buttons[name].firstMatch
    }

    @MainActor
    private func text(_ text: String) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", text)).firstMatch
    }

    @MainActor
    private func tap(_ element: XCUIElement) {
        let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == true AND hittable == true AND enabled == true"), object: element)
        XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 20), .completed, "\(element) to tap")
        element.tap()
    }

    /// Types into a field once it has the keyboard: on a busy simulator a tap
    /// can land before the field takes focus, and typing then goes nowhere.
    @MainActor
    private func type(_ text: String, into field: XCUIElement) {
        XCTAssertTrue(field.waitForExistence(timeout: 20), "\(field) to type in")
        for _ in 0..<4 {
            field.tap()
            if waitUntil(timeout: 3, { (field.value(forKey: "hasKeyboardFocus") as? Bool) == true }) { break }
        }
        field.typeText(text)
    }

    @MainActor
    private func tapPlayerVideo() {
        guard let window = app.windows.allElementsBoundByIndex.max(by: {
            $0.frame.width * $0.frame.height < $1.frame.width * $1.frame.height
        }) else { return XCTFail("the player has a window") }
        window.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.3)).tap()
    }

    /// Taps Allow on a system alert over the app (Springboard's), if one is up.
    @MainActor
    private func allowSystemAlert() {
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        for title in ["Allow", "OK"] where springboard.buttons[title].waitForExistence(timeout: 2) {
            springboard.buttons[title].tap()
            return
        }
    }

    @MainActor
    private func waitUntil(timeout: TimeInterval = 10, _ condition: @escaping () -> Bool) -> Bool {
        let expectation = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in condition() }, object: nil)
        return XCTWaiter().wait(for: [expectation], timeout: timeout) == .completed
    }

    @MainActor
    private func shot(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
