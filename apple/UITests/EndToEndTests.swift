import XCTest
import UIKit

/// The app against a real server, end to end: type the server's address,
/// sign in, browse Home and Movies, play a film and leave the player, and
/// find this phone under Signed-in devices. `make apple-e2e` runs it against
/// the fixture server; without `WMM_E2E_SERVER` (passed to xcodebuild as
/// `TEST_RUNNER_WMM_E2E_SERVER`) it is skipped. Every step leaves a
/// screenshot in the test results.
final class EndToEndTests: XCTestCase {
    private var app: XCUIApplication!

    override func setUp() async throws {
        continueAfterFailure = false
    }

    @MainActor
    func testFindSignInBrowseAndPlay() throws {
        let env = ProcessInfo.processInfo.environment
        guard let server = env["WMM_E2E_SERVER"], !server.isEmpty else {
            throw XCTSkip("Set WMM_E2E_SERVER (and WMM_E2E_USER, WMM_E2E_PASSWORD) to run against a server.")
        }
        let user = env["WMM_E2E_USER"] ?? "viewer"
        let password = env["WMM_E2E_PASSWORD"] ?? "fixture-viewer-1"
        let film = env["WMM_E2E_FILM"] ?? "Paper Moons"

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

        // The server picker: type the address and connect.
        let address = app.textFields.firstMatch
        if !address.waitForExistence(timeout: 15) {
            add(XCTAttachment(string: app.debugDescription))
            allowSystemAlert()
        }
        XCTAssertTrue(address.waitForExistence(timeout: 10), "the server picker shows first on a fresh install")
        shot("1 Server picker")
        address.tap()
        address.typeText(server)
        app.buttons["Connect"].firstMatch.tap()

        // Sign in with the account.
        let name = app.textFields["Username"]
        XCTAssertTrue(name.waitForExistence(timeout: 15), "sign-in follows the server picker")
        name.tap()
        name.typeText(user)
        let secret = app.secureTextFields["Password"]
        secret.tap()
        secret.typeText(password)
        shot("2 Sign in")
        app.buttons["Sign in"].firstMatch.tap()

        // Home, from the server.
        XCTAssertTrue(app.navigationBars["Home"].waitForExistence(timeout: 20), "Home shows once signed in")
        XCTAssertTrue(element(containing: "Recently added").waitForExistence(timeout: 20), "Home has its rows")
        shot("3 Home")

        // Movies, and play a film.
        app.buttons["Movies"].firstMatch.tap()
        let card = element(containing: film)
        XCTAssertTrue(card.waitForExistence(timeout: 20), "the film is in the Movies grid")
        shot("4 Movies")
        card.tap()

        // Matched movies open their detail page; unmatched fixture files play
        // directly. Exercise either route so HEVC fixtures can carry artwork.
        let close = app.buttons["Close"]
        var openedMoviePage = false
        if !close.waitForExistence(timeout: 3) {
            let play = app.buttons.matching(NSPredicate(format: "label == 'Play' OR label BEGINSWITH 'Resume ·'")).firstMatch
            if play.waitForExistence(timeout: 5) {
                openedMoviePage = true
                play.tap()
            }
        }

        // The player: its controls show, then Close leaves it.
        if !close.waitForExistence(timeout: 20) {
            tapPlayerVideo()
        }
        XCTAssertTrue(close.waitForExistence(timeout: 10), "the player opens with its controls")
        let position = app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", "Playback position")).firstMatch
        let initialPosition = try XCTUnwrap(position.value as? String)
        sleep(6)
        // Pause before inspecting progress and taking the screenshot. Otherwise
        // the controls' normal auto-hide timer can remove Close between queries.
        let pause = app.buttons["player-play-pause"]
        for _ in 0..<3 where !pause.exists {
            tapPlayerVideo()
            if pause.waitForExistence(timeout: 1) { break }
        }
        XCTAssertTrue(pause.exists)
        pause.tap()
        let paused = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label == 'Play'"), object: pause)
        XCTAssertEqual(XCTWaiter.wait(for: [paused], timeout: 5), .completed)
        XCTAssertTrue(position.waitForExistence(timeout: 5))
        let progressed = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value != %@", initialPosition), object: position)
        XCTAssertEqual(XCTWaiter.wait(for: [progressed], timeout: 10), .completed, "the fixture video advances, including HEVC playback")
        shot("5 Player")
        XCTAssertTrue(close.waitForExistence(timeout: 5), "paused playback keeps its controls visible")
        close.tap()

        // Phone detail pages hide the tab bar. Return to Movies after a matched
        // film; the unmatched fixture route already returns to the grid.
        if openedMoviePage && UIDevice.current.userInterfaceIdiom != .pad {
            let back = app.navigationBars.buttons.firstMatch
            let backReady = XCTNSPredicateExpectation(predicate: NSPredicate(format: "hittable == true"), object: back)
            XCTAssertEqual(XCTWaiter.wait(for: [backReady], timeout: 15), .completed)
            back.tap()
        }

        // Settings: this phone is among the signed-in devices.
        let settings = app.buttons["Settings"].firstMatch
        // Dismissing the landscape player rotates back asynchronously. An
        // existing tab can still have an off-screen hit point until it settles.
        let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "hittable == true"), object: settings)
        XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 15), .completed)
        settings.tap()
        let devices = element(containing: "Signed-in devices")
        XCTAssertTrue(devices.waitForExistence(timeout: 15), "Settings finished opening")
        shot("6 Settings")
        devices.tap()
        let device = UIDevice.current.userInterfaceIdiom == .pad ? "This iPad" : "This iPhone"
        XCTAssertTrue(element(containing: device).waitForExistence(timeout: 15), "this device is listed as signed in")
        shot("7 Signed-in devices")
    }

    /// Tablet portrait keeps the video above its title and episode area.
    @MainActor
    private func tapPlayerVideo() {
        // The application frame can cover the display even when iPadOS keeps
        // its window compact after a resize. Ignore zero-sized overlay windows.
        guard let window = app.windows.allElementsBoundByIndex.max(by: {
            $0.frame.width * $0.frame.height < $1.frame.width * $1.frame.height
        }) else {
            XCTFail("the player has a window")
            return
        }
        let size = window.frame.size
        // Stay above the central transport buttons even if they appear between
        // the accessibility query and this tap.
        let portraitPad = UIDevice.current.userInterfaceIdiom == .pad && size.width >= 600 && size.height > size.width
        let y = portraitPad ? min(size.width * 9 / 16, size.height) * 0.25 / size.height : 0.3
        window.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: y)).tap()
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
    private func element(containing text: String) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS[c] %@", text)).firstMatch
    }

    @MainActor
    private func shot(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
