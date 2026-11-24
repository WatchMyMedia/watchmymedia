import XCTest
import UIKit

/// Several servers on iPhone and iPad, on the fakes with `-fakesServers`: Sam
/// is signed in to Living room server (in use), Cabin server, which has a
/// small library of its own, and Den server, which doesn't answer. Servers
/// opens from the account button (a sheet on iPhone, a popover on iPad) and
/// switches; a server that doesn't answer says so with Try again and Watch
/// its downloads; Add a server runs to its last step; a server is removed
/// from its page in Settings; search lists the other servers' titles, a
/// title on several once, and opens another server's from there; the
/// Downloads list names the other servers' downloads; and signing out moves
/// to the next server.
final class ServersTests: XCTestCase {
    override func setUp() async throws { continueAfterFailure = false }

    private var pad: Bool { UIDevice.current.userInterfaceIdiom == .pad }
    private var device: String { pad ? "iPad" : "iPhone" }

    @MainActor
    private func launch(_ extra: [String] = [], several: Bool = true) -> XCUIApplication {
        XCUIDevice.shared.orientation = pad ? .landscapeLeft : .portrait
        let app = XCUIApplication()
        app.launchArguments = ["-fakes"] + (several ? ["-fakesServers"] : []) + extra
        app.launch()
        return app
    }

    // MARK: Servers, the switcher

    @MainActor
    func testServersOpensFromTheAccountButtonAndSwitchesToAnotherServer() {
        let app = launch()
        defer { app.terminate() }
        let account = app.buttons["account-button"].firstMatch
        XCTAssertTrue(account.waitForExistence(timeout: 20))
        XCTAssertEqual(account.label, "Servers. Sam, on Living room server")
        XCTAssertTrue(text(app, "Harbor Lights").waitForExistence(timeout: 20), "Living room server's Home")
        if !pad {
            // No room in the bar on a phone: the server's name is under Home's title, and opens Servers too.
            let name = app.buttons["home-server-name"]
            XCTAssertTrue(name.waitForExistence(timeout: 10))
            XCTAssertEqual(name.label, "Living room server. Opens Servers")
            name.tap()
            XCTAssertTrue(row(app, "srv-living").waitForExistence(timeout: 10), "the name opens Servers as the initial does")
            closeServers(app)
        }

        account.tap()
        let living = row(app, "srv-living"), cabin = row(app, "srv-cabin"), den = row(app, "srv-den")
        XCTAssertTrue(living.waitForExistence(timeout: 10))
        if pad { XCTAssertTrue(app.popovers.firstMatch.exists, "a popover under the button on iPad") }
        XCTAssertTrue(living.label.contains("Living room server"), living.label)
        XCTAssertEqual(living.value as? String, "Current")
        XCTAssertTrue(cabin.label.contains("Cabin server") && cabin.label.contains("Sam · 192.0.2.31"), cabin.label)
        XCTAssertLessThan(living.frame.minY, cabin.frame.minY, "the server in use first")
        XCTAssertLessThan(cabin.frame.minY, den.frame.minY, "then by last use")
        XCTAssertTrue(app.buttons["servers-add"].exists && app.buttons["servers-manage"].exists)
        XCTAssertTrue(app.buttons["servers-add"].label.contains("On this network, or by its address"), app.buttons["servers-add"].label)
        XCTAssertFalse(app.buttons["servers-settings"].exists, "Settings has its tab here")
        shot("Servers — several")

        cabin.tap()
        let note = app.descendants(matching: .any)["server-note"]
        XCTAssertTrue(note.waitForExistence(timeout: 10), "the note after a switch")
        XCTAssertEqual(note.label, "Now on Cabin server")
        XCTAssertTrue(text(app, "Harbour Run").waitForExistence(timeout: 20), "Cabin server's Home")
        XCTAssertFalse(row(app, "srv-cabin").exists, "Servers closed")
        shot("Home on Cabin server, with the note")
        XCTAssertTrue(waitUntil { app.buttons["account-button"].firstMatch.label == "Servers. Sam, on Cabin server" })
        XCTAssertTrue(note.waitForNonExistence(timeout: 10), "the note goes by itself")

        // Servers again: Cabin server is the one in use, and comes first.
        app.buttons["account-button"].firstMatch.tap()
        XCTAssertTrue(row(app, "srv-cabin").waitForExistence(timeout: 10))
        XCTAssertEqual(row(app, "srv-cabin").value as? String, "Current")
        XCTAssertLessThan(row(app, "srv-cabin").frame.minY, row(app, "srv-living").frame.minY)
    }

    @MainActor
    func testAServerThatDoesNotAnswerSaysSoWithTryAgainAndWatchItsDownloads() {
        let app = launch()
        defer { app.terminate() }
        let account = app.buttons["account-button"].firstMatch
        XCTAssertTrue(account.waitForExistence(timeout: 20))
        account.tap()
        let den = row(app, "srv-den")
        XCTAssertTrue(den.waitForExistence(timeout: 10))
        den.tap()

        let failure = app.descendants(matching: .any)["servers-failure"]
        XCTAssertTrue(failure.waitForExistence(timeout: 15))
        XCTAssertTrue(text(app, "Couldn’t reach Den server").exists)
        XCTAssertTrue(text(app, "You’re still on Living room server.").exists)
        let again = app.buttons["servers-try-again"], watch = app.buttons["servers-watch-downloads"]
        XCTAssertTrue(again.exists)
        XCTAssertEqual(watch.label, "Watch its 3 downloads")
        XCTAssertLessThan(failure.frame.minY, row(app, "srv-living").frame.minY, "the message sits above the list")
        XCTAssertEqual(row(app, "srv-living").value as? String, "Current", "still on Living room server")
        shot("Servers — a server that didn't answer")

        // Try again asks it again; it still doesn't answer.
        again.tap()
        XCTAssertTrue(waitUntil { again.exists && again.isEnabled })
        XCTAssertTrue(text(app, "Couldn’t reach Den server").exists)

        // Watch its downloads switches anyway, and Home opens as it does offline.
        watch.tap()
        let note = app.descendants(matching: .any)["server-note"]
        XCTAssertTrue(note.waitForExistence(timeout: 10))
        XCTAssertEqual(note.label, "Now on Den server")
        XCTAssertTrue(text(app, "Can’t reach Den server").waitForExistence(timeout: 20), "the offline Home, on Den server")
        shot("Den server, to watch its downloads")
    }

    /// The fakes without `-fakesServers`: signed in on one server, signed out
    /// of another, and one too old for the app.
    @MainActor
    func testASignedOutServerOpensItsSignInAndOneTooOldIsNotChosen() {
        let app = launch(several: false)
        defer { app.terminate() }
        let account = app.buttons["account-button"].firstMatch
        XCTAssertTrue(account.waitForExistence(timeout: 20))
        account.tap()
        let signedOut = row(app, "srv-remote"), old = row(app, "srv-nas")
        XCTAssertTrue(signedOut.waitForExistence(timeout: 10))
        XCTAssertEqual(signedOut.value as? String, "Signed out here · sign in again")
        XCTAssertEqual(old.value as? String, "Too old for this app")
        XCTAssertFalse(app.buttons["servers-row-srv-nas"].exists, "one too old is not a button")
        shot("Servers — signed out and too old")

        signedOut.tap()
        let steps = app.descendants(matching: .any)["task-steps"]
        XCTAssertTrue(steps.waitForExistence(timeout: 15), "its sign-in opens")
        XCTAssertEqual(steps.label, "Step 2 of 3, Sign in")
        XCTAssertTrue(text(app, "Sign in to home.watchmy.media").waitForExistence(timeout: 10))
        // Cancel leaves the app on the first server, back where Servers was opened.
        let cancel = app.buttons["task-cancel"].firstMatch
        XCTAssertTrue(waitUntil { cancel.exists && cancel.isHittable && cancel.isEnabled })
        cancel.tap()
        XCTAssertTrue(steps.waitForNonExistence(timeout: 10))
        XCTAssertTrue(app.buttons["account-button"].firstMatch.waitForExistence(timeout: 15), "back on Home")
        XCTAssertTrue(text(app, "Harbor Lights").waitForExistence(timeout: 15))
    }

    // MARK: Add a server

    @MainActor
    func testAddAServerFromServersToItsLastStep() {
        let app = launch()
        defer { app.terminate() }
        let account = app.buttons["account-button"].firstMatch
        XCTAssertTrue(account.waitForExistence(timeout: 20))
        account.tap()
        tap(app.buttons["servers-add"])

        let steps = app.descendants(matching: .any)["task-steps"]
        XCTAssertTrue(steps.waitForExistence(timeout: 15))
        XCTAssertEqual(steps.label, "Step 1 of 3, Choose a server")
        XCTAssertTrue(app.navigationBars["Add a server"].exists)
        // The servers already registered aren't offered again; the step names them.
        let offered = app.buttons["add-server-srv-office"]
        XCTAssertTrue(offered.waitForExistence(timeout: 15), "the Office Mac mini is on this network")
        XCTAssertFalse(app.buttons["add-server-srv-living"].exists)
        XCTAssertFalse(app.buttons["add-server-srv-cabin"].exists)
        XCTAssertTrue(text(app, "Already added: Living room server, Cabin server, Den server.").exists)
        shot("Add a server — choose")

        tap(offered)
        let name = app.textFields["add-name"]
        XCTAssertTrue(name.waitForExistence(timeout: 15))
        XCTAssertEqual(steps.label, "Step 2 of 3, Sign in")
        type("Sam", into: name)
        type("password", into: app.secureTextFields["add-password"])
        tap(app.buttons["add-sign-in"])

        let done = app.buttons["add-done"]
        XCTAssertTrue(done.waitForExistence(timeout: 15))
        XCTAssertEqual(steps.label, "Added")
        XCTAssertTrue(text(app, "Office Mac mini is added").exists)
        XCTAssertTrue(text(app, "You’re on it now, signed in as Sam.").exists)
        XCTAssertTrue(text(app, "This \(device) is signed in to 4 servers. Search looks in all of them.").exists)
        XCTAssertTrue(text(app, "Tap your initial on Home to switch. It doesn’t ask for a password again.").exists)
        XCTAssertFalse(app.buttons["task-cancel"].exists, "the last step has no way back")
        shot("Add a server — added")
        tap(done)
        XCTAssertTrue(done.waitForNonExistence(timeout: 10))

        // Done shows the new server's Home, and Servers lists it first, as the one in use.
        let button = app.buttons["account-button"].firstMatch
        XCTAssertTrue(button.waitForExistence(timeout: 15))
        XCTAssertTrue(waitUntil { button.label == "Servers. Sam, on Office Mac mini" }, button.label)
        button.tap()
        XCTAssertTrue(row(app, "srv-office").waitForExistence(timeout: 10))
        XCTAssertEqual(row(app, "srv-office").value as? String, "Current")
        // Switching back needs no password.
        row(app, "srv-living").tap()
        XCTAssertTrue(app.descendants(matching: .any)["server-note"].waitForExistence(timeout: 10))
        XCTAssertFalse(app.textFields["add-name"].exists)
    }

    @MainActor
    func testCancellingAddAServerFromServersGoesBackToHome() {
        let app = launch()
        defer { app.terminate() }
        let account = app.buttons["account-button"].firstMatch
        XCTAssertTrue(account.waitForExistence(timeout: 20))
        account.tap()
        tap(app.buttons["servers-add"])
        let cancel = app.buttons["task-cancel"].firstMatch
        XCTAssertTrue(cancel.waitForExistence(timeout: 15))
        tap(cancel)
        XCTAssertTrue(cancel.waitForNonExistence(timeout: 10))
        XCTAssertTrue(app.buttons["account-button"].firstMatch.waitForExistence(timeout: 15), "Cancel goes back to where it was started")
        XCTAssertEqual(app.buttons["account-button"].firstMatch.label, "Servers. Sam, on Living room server")
    }

    // MARK: Settings › Server

    @MainActor
    func testManageServersOpensSettingsServerAndAServersPageRemovesIt() {
        let app = launch()
        defer { app.terminate() }
        let account = app.buttons["account-button"].firstMatch
        XCTAssertTrue(account.waitForExistence(timeout: 20))
        account.tap()
        tap(app.buttons["servers-manage"])

        // Settings › Server: Your servers, a row each, then Add a server.
        XCTAssertTrue(app.navigationBars["Server"].waitForExistence(timeout: 15))
        XCTAssertTrue(text(app, "This \(device) is signed in to each of these. Search looks in all of them.").waitForExistence(timeout: 10))
        let living = app.buttons["server-row-srv-living"], cabin = app.buttons["server-row-srv-cabin"]
        XCTAssertTrue(cabin.waitForExistence(timeout: 10))
        XCTAssertTrue(living.label.contains("Sam · 192.168.1.20:27587 · v0.9.3"), living.label)
        XCTAssertEqual(living.value as? String, "Current")
        XCTAssertTrue(cabin.label.contains("Sam · 192.0.2.31:27587 · v1.4.5"), cabin.label)
        XCTAssertTrue(app.buttons["server-add"].exists)
        shot("Settings › Server — Your servers")

        // Cabin server's page.
        tap(cabin)
        XCTAssertTrue(app.navigationBars["Cabin server"].waitForExistence(timeout: 10))
        XCTAssertTrue(text(app, "192.0.2.31:27587").exists)
        XCTAssertTrue(text(app, "Downloads from it").exists)
        XCTAssertTrue(app.buttons["server-switch-to"].exists)
        let remove = app.buttons["server-remove"]
        XCTAssertTrue(remove.exists)
        shot("A server's page")

        // The question, from the row that asked; a tap outside keeps the server.
        tap(remove)
        XCTAssertTrue(text(app, "Remove Cabin server from this \(device)?").waitForExistence(timeout: 10))
        XCTAssertTrue(text(app, "This \(device) is signed out of it.").exists)
        XCTAssertTrue(text(app, "You can add it again.").exists)
        shot("Remove this server — the question")
        keep(app)
        XCTAssertTrue(app.navigationBars["Cabin server"].waitForExistence(timeout: 10))

        // Remove takes it off the list and goes back to Server.
        tap(remove)
        let confirm = app.popovers.firstMatch.buttons["Remove"].firstMatch
        XCTAssertTrue(confirm.waitForExistence(timeout: 10))
        confirm.tap()
        XCTAssertTrue(app.navigationBars["Server"].waitForExistence(timeout: 15), "back on Server")
        XCTAssertTrue(app.buttons["server-row-srv-living"].waitForExistence(timeout: 10))
        XCTAssertTrue(waitUntil { !app.buttons["server-row-srv-cabin"].exists }, "Cabin server is off the list")
        XCTAssertTrue(app.buttons["server-row-srv-den"].exists, "the others stay")
    }

    @MainActor
    func testSwitchingFromAServersPageAndAFailureOpensServers() {
        let app = launch(["-tab", "settings", "-settingsPage", "server"])
        defer { app.terminate() }
        // Den server doesn't answer: the failure is said in Servers, which opens over Settings.
        tap(app.buttons["server-row-srv-den"])
        XCTAssertTrue(app.navigationBars["Den server"].waitForExistence(timeout: 10))
        XCTAssertTrue(text(app, "3 · 12.4 GB").exists, "the downloads held from it")
        tap(app.buttons["server-switch-to"])
        XCTAssertTrue(app.descendants(matching: .any)["servers-failure"].waitForExistence(timeout: 15))
        XCTAssertTrue(text(app, "Couldn’t reach Den server").exists)
        closeServers(app)

        // Cabin server answers: the app is on it, at Home, with the note.
        tap(app.navigationBars["Den server"].buttons.firstMatch)
        tap(app.buttons["server-row-srv-cabin"])
        tap(app.buttons["server-switch-to"])
        let note = app.descendants(matching: .any)["server-note"]
        XCTAssertTrue(note.waitForExistence(timeout: 10))
        XCTAssertEqual(note.label, "Now on Cabin server")
        XCTAssertTrue(text(app, "Harbour Run").waitForExistence(timeout: 20), "Cabin server's Home")
    }

    @MainActor
    func testSigningOutMovesToTheNextServerSignedIn() {
        let app = launch(["-tab", "settings", "-settingsPage", "account"])
        defer { app.terminate() }
        tap(app.buttons["account-signOut"])
        let confirm = app.popovers.firstMatch.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Sign out")).firstMatch
        XCTAssertTrue(confirm.waitForExistence(timeout: 10))
        confirm.tap()
        let note = app.descendants(matching: .any)["server-note"]
        XCTAssertTrue(note.waitForExistence(timeout: 15), "the app moves on rather than showing sign-in")
        XCTAssertEqual(note.label, "Now on Cabin server")
        XCTAssertTrue(text(app, "Harbour Run").waitForExistence(timeout: 20))
        // Living room server stays on the list, signed out.
        app.buttons["account-button"].firstMatch.tap()
        XCTAssertTrue(row(app, "srv-living").waitForExistence(timeout: 10))
        XCTAssertEqual(row(app, "srv-living").value as? String, "Signed out here · sign in again")
    }

    // MARK: Search

    @MainActor
    func testSearchListsEveryServersTitlesOnceAndOpensAnothersInPlace() {
        let app = launch(["-tab", "search", "-search", "harbor"])
        defer { app.terminate() }
        // Harbor Lights is on Living room and Cabin: one row, saying so. Cabin server's own titles name it.
        let near = result(app, "/show/100"), far = result(app, "srv-cabin/movie/800")
        XCTAssertTrue(far.waitForExistence(timeout: 20), "Cabin server's Harbor Nights is listed")
        XCTAssertTrue(near.exists)
        XCTAssertTrue(describes(near, "On Living room and Cabin"), "one row says where it is: \(near.label) / \(near.value ?? "")")
        XCTAssertFalse(result(app, "srv-cabin/show/701").exists, "and Cabin server's copy has no row of its own")
        XCTAssertTrue(far.label.contains("Harbor Nights"), far.label)
        XCTAssertTrue(describes(far, "On Cabin server"), "a row from another server names it: \(far.label) / \(far.value ?? "")")
        // Den server didn't answer: one line, with Try again.
        let unlisted = app.descendants(matching: .any)["search-unlisted-srv-den"]
        scroll(app, to: unlisted)
        XCTAssertTrue(text(app, "Den server didn’t answer, so its titles aren’t listed.").exists)
        XCTAssertTrue(app.buttons["search-retry-srv-den"].exists)
        shot("Search across servers")

        // Opening Cabin server's result opens it from there, without switching.
        scroll(app, to: far)
        far.tap()
        XCTAssertTrue(app.staticTexts["Harbor Nights"].firstMatch.waitForExistence(timeout: 20), "its page, from Cabin server")
        XCTAssertTrue(text(app, "One night on the quay").waitForExistence(timeout: 10), "Cabin server's own film")
        XCTAssertFalse(app.descendants(matching: .any)["server-note"].exists, "no switch, so no note")
        shot("Another server's title, opened in place")

        // Back: the results as they were, still on Living room server.
        app.navigationBars.buttons.firstMatch.tap()
        XCTAssertTrue(far.waitForExistence(timeout: 20), "the results are back")
        XCTAssertTrue(describes(far, "On Cabin server"), "\(far.label) / \(far.value ?? "")")
        XCTAssertTrue(near.exists)
        let field = app.searchFields.firstMatch
        XCTAssertEqual(field.value as? String, "harbor", "the same words")
    }

    @MainActor
    func testSearchSaysWhichServerItIsStillAsking() {
        let app = launch(["-fakesSlowServer", "-tab", "search", "-search", "harbor"])
        defer { app.terminate() }
        let asking = app.descendants(matching: .any)["search-still-asking"]
        XCTAssertTrue(asking.waitForExistence(timeout: 20))
        XCTAssertEqual(asking.label, "Still asking Cabin server…")
        XCTAssertTrue(result(app, "/show/100").exists, "the server in use's rows are up meanwhile")
        shot("Search — still asking a server")
        XCTAssertTrue(result(app, "srv-cabin/movie/800").waitForExistence(timeout: 15), "its rows are added when it answers")
        XCTAssertFalse(asking.exists)
    }

    @MainActor
    func testSearchWordsNameTheServersWhenThereAreSeveral() {
        let app = launch(["-tab", "search"])
        defer { app.terminate() }
        XCTAssertTrue(text(app, "Search your servers").waitForExistence(timeout: 20))
        XCTAssertTrue(text(app, "Find a show, a movie or an episode on any of your 3 servers.").exists)
        let field = app.searchFields.firstMatch
        field.tap()
        field.typeText("zzzz")
        XCTAssertTrue(text(app, "No matches on your 3 servers").waitForExistence(timeout: 20))
        XCTAssertTrue(text(app, "Nothing is called “zzzz” on any of them.").exists)
        shot("Search — no matches on any server")
    }

    // MARK: Downloads

    @MainActor
    func testTheDownloadsListNamesTheOtherServersDownloads() {
        // The fakes' server offers downloads from API level 10; `-fakesRows` puts it past that.
        let app = launch(["-fakesRows", "-open", "watchmymedia://downloads"])
        defer { app.terminate() }
        // Under the list, which makes its rows as they come into view.
        XCTAssertTrue(app.navigationBars["Downloads"].waitForExistence(timeout: 20))
        let line = app.buttons["downloads-elsewhere-srv-den"]
        scroll(app, to: line)
        XCTAssertTrue(line.waitForExistence(timeout: 10))
        XCTAssertTrue(line.label.contains("3 more from Den server"), line.label)
        shot("Downloads — another server's line")
        // Den server doesn't answer, so Servers opens on the failure, with Watch its downloads.
        line.tap()
        XCTAssertTrue(app.descendants(matching: .any)["servers-failure"].waitForExistence(timeout: 15))
        XCTAssertEqual(app.buttons["servers-watch-downloads"].label, "Watch its 3 downloads")
    }

    // MARK: Helpers

    /// A server's row in Servers: a button for one that can be chosen, a plain row for the one in use.
    @MainActor
    private func row(_ app: XCUIApplication, _ id: String) -> XCUIElement {
        app.descendants(matching: .any)["servers-row-\(id)"].firstMatch
    }

    /// A search result by its server and id: "srv-cabin/movie/800", or "/show/100" on the server in use.
    @MainActor
    private func result(_ app: XCUIApplication, _ id: String) -> XCUIElement {
        app.buttons["search-result-\(id)"].firstMatch
    }

    /// Whether `words` are in what VoiceOver reads of `element`: its label or its value.
    @MainActor
    private func describes(_ element: XCUIElement, _ words: String) -> Bool {
        element.label.contains(words) || (element.value as? String)?.contains(words) == true
    }

    @MainActor
    private func text(_ app: XCUIApplication, _ text: String) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", text)).firstMatch
    }

    /// Closes Servers: its Close on a sheet, a tap outside a popover.
    @MainActor
    private func closeServers(_ app: XCUIApplication) {
        let close = app.buttons["servers-close"].firstMatch
        if close.exists && close.isHittable {
            close.tap()
        } else if app.otherElements["PopoverDismissRegion"].firstMatch.exists {
            app.otherElements["PopoverDismissRegion"].firstMatch.tap()
        } else {
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.2, dy: 0.5)).tap()
        }
        XCTAssertTrue(waitUntil { !app.descendants(matching: .any)["servers"].firstMatch.exists }, "Servers closes")
    }

    /// Answers a question with the button that keeps things. On iOS 27 the
    /// dialog grows from the button that asked and shows no such button: a
    /// tap outside it is the same answer.
    @MainActor
    private func keep(_ app: XCUIApplication) {
        let button = app.popovers.firstMatch.buttons["Keep"]
        if button.exists && button.isHittable {
            button.tap()
        } else if app.otherElements["PopoverDismissRegion"].firstMatch.exists {
            app.otherElements["PopoverDismissRegion"].firstMatch.tap()
        } else {
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.08)).tap()
        }
        XCTAssertTrue(waitUntil { !app.popovers.firstMatch.exists }, "the question goes")
    }

    @MainActor
    private func scroll(_ app: XCUIApplication, to element: XCUIElement) {
        for _ in 0..<8 where !(element.exists && element.isHittable) { app.swipeUp(velocity: .slow) }
    }

    @MainActor
    private func tap(_ element: XCUIElement) {
        // A row low on a long page is below the fold until scrolled to.
        if element.waitForExistence(timeout: 15) {
            for _ in 0..<6 where !element.isHittable { XCUIApplication().swipeUp(velocity: .slow) }
        }
        let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == true AND hittable == true AND enabled == true"), object: element)
        XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 15), .completed, "\(element) to tap")
        element.tap()
    }

    /// Types into a field once it has the keyboard: on a busy iPad a tap can
    /// land before the field takes focus, and typing then goes nowhere.
    @MainActor
    private func type(_ text: String, into field: XCUIElement) {
        XCTAssertTrue(field.waitForExistence(timeout: 10))
        for _ in 0..<4 {
            field.tap()
            if waitUntil(timeout: 3, { (field.value(forKey: "hasKeyboardFocus") as? Bool) == true }) { break }
        }
        field.typeText(text)
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
