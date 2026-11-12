import XCTest

/// The request screens use the same models on iPhone and iPad. These checks
/// catch navigation and rendering regressions that repository tests cannot.
final class RequestsTests: XCTestCase {
    override func setUp() async throws { continueAfterFailure = false }

    /// Opt-in, task-owned fixture with Seerr and a missing movie assigned to
    /// this test. It withdraws only the request it creates.
    @MainActor
    func testFixtureSeerrRequestCanBeFoundAndCancelled() throws {
        let env = ProcessInfo.processInfo.environment
        guard let server = env["WMM_ARR_SERVER"], let title = env["WMM_ARR_MOVIE"] else {
            throw XCTSkip("Set WMM_ARR_SERVER and WMM_ARR_MOVIE for a disposable Seerr fixture.")
        }
        let app = XCUIApplication()
        addUIInterruptionMonitor(withDescription: "Local network") { alert in
            for title in ["Allow", "OK"] where alert.buttons[title].exists {
                alert.buttons[title].tap()
                return true
            }
            return false
        }
        app.launch()
        let address = app.textFields.firstMatch
        XCTAssertTrue(address.waitForExistence(timeout: 15), "start with a fresh install")
        address.tap()
        address.typeText(server)
        app.buttons["Connect"].firstMatch.tap()
        let username = app.textFields["Username"]
        XCTAssertTrue(username.waitForExistence(timeout: 15))
        username.tap()
        username.typeText(env["WMM_ARR_USER"] ?? "viewer")
        let password = app.secureTextFields["Password"]
        password.tap()
        password.typeText(env["WMM_ARR_PASSWORD"] ?? "fixture-viewer-1")
        app.buttons["Sign in"].firstMatch.tap()
        XCTAssertTrue(app.navigationBars["Home"].waitForExistence(timeout: 20))
        app.buttons["Search"].firstMatch.tap()
        let search = app.searchFields.firstMatch
        XCTAssertTrue(search.waitForExistence(timeout: 15))
        search.tap()
        search.typeText(title)
        let result = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", title)).firstMatch
        XCTAssertTrue(result.waitForExistence(timeout: 20))
        result.tap()
        let request = app.buttons["Request movie"]
        XCTAssertTrue(request.waitForExistence(timeout: 15))
        request.tap()
        let confirmation = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "Sent to Seerr.")).firstMatch
        XCTAssertTrue(confirmation.waitForExistence(timeout: 15))
        confirmation.tap()
        XCTAssertTrue(app.navigationBars["Your requests"].waitForExistence(timeout: 15))
        // Name the exact movie, preserving requests owned by other smoke tests.
        let titleElement = app.staticTexts[title]
        XCTAssertTrue(titleElement.waitForExistence(timeout: 15))
        let cancel = app.buttons["Cancel request for \(title)"]
        XCTAssertTrue(cancel.waitForExistence(timeout: 10))
        cancel.tap()
        XCTAssertTrue(titleElement.waitForNonExistence(timeout: 10))
    }

    @MainActor
    func testSeerrInboxKeepsLocalRequestsVisible() {
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-fakesSeerr", "-open", "watchmymedia://inbox"]
        app.launch()
        XCTAssertTrue(app.staticTexts["Requests are approved in Seerr"].waitForExistence(timeout: 15))
        XCTAssertTrue(app.links["Open Seerr"].exists || app.buttons["Open Seerr"].exists)
        XCTAssertTrue(app.buttons["Approve"].firstMatch.exists, "legacy local requests still need decisions")
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.lifetime = .keepAlways
        add(shot)
    }

    /// Opt-in because window resizing depends on the simulator's multitasking
    /// setting. The same request list must stay usable below the iPad breakpoint.
    @MainActor
    func testPadInboxAdaptsToACompactWindow() throws {
        guard ProcessInfo.processInfo.environment["WMM_ARR_PAD_RESIZE"] == "1" else {
            throw XCTSkip("Set WMM_ARR_PAD_RESIZE=1 on a disposable iPad simulator.")
        }
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-fakesSeerr", "-open", "watchmymedia://inbox"]
        app.launch()
        XCTAssertTrue(app.staticTexts["Requests are approved in Seerr"].waitForExistence(timeout: 15))
        XCTAssertGreaterThan(app.windows.firstMatch.frame.width, 600)
        let wide = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        wide.name = "iPad regular Inbox"
        wide.lifetime = .keepAlways
        add(wide)
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.99, dy: 0.99)).press(
            forDuration: 1,
            thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.52, dy: 0.85))
        )
        for _ in 0..<20 where app.windows.firstMatch.frame.width >= 600 {
            Thread.sleep(forTimeInterval: 0.25)
        }
        XCTAssertLessThan(app.windows.firstMatch.frame.width, 600)
        XCTAssertTrue(app.buttons["Approve"].firstMatch.waitForExistence(timeout: 10))
        let narrow = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        narrow.name = "iPad compact Inbox"
        narrow.lifetime = .keepAlways
        add(narrow)
    }

    @MainActor
    func testUnlinkedSeerrYourRequestsHasAnEmptyList() {
        let app = XCUIApplication()
        app.launchArguments = ["-fakesMember", "-fakesSeerr", "-fakesSeerrUnlinked", "-open", "watchmymedia://requests"]
        app.launch()
        XCTAssertTrue(app.staticTexts["No requests yet"].waitForExistence(timeout: 15))
        XCTAssertTrue(app.staticTexts["Your requests will appear here."].exists)
    }
}
