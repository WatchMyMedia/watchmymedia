import XCTest
import UIKit

/// New episodes against a real server, one step at a time, with the server's
/// side (a file added, a scan, the two-minute hold, the background task)
/// done between steps from outside (PLAN-NEW-EPISODES.md §10, Apple). Each
/// step is its own test, run alone with `-only-testing`, on a fresh
/// simulator. Skipped unless `WMM_NE_SERVER` (with `WMM_NE_USER`,
/// `WMM_NE_PASSWORD`, `WMM_NE_SHOW` and `WMM_NE_SHOW_TITLE`, passed to xcodebuild as
/// `TEST_RUNNER_…`) names a throwaway server with a followable show. The
/// README's New episodes section has the recipe.
final class NewEpisodesServerTests: XCTestCase {
    private var env: [String: String] { ProcessInfo.processInfo.environment }
    @MainActor private var springboard: XCUIApplication { XCUIApplication(bundleIdentifier: "com.apple.springboard") }

    override func setUp() async throws {
        continueAfterFailure = false
        guard env["WMM_NE_SERVER"]?.isEmpty == false else {
            throw XCTSkip("Set WMM_NE_SERVER, WMM_NE_USER, WMM_NE_PASSWORD and WMM_NE_SHOW to run against a server.")
        }
    }

    @MainActor
    private func launch(_ arguments: [String] = []) -> XCUIApplication {
        XCUIDevice.shared.orientation = .portrait
        let app = XCUIApplication()
        app.launchArguments = arguments
        app.launch()
        return app
    }

    @MainActor
    private func shot(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    @MainActor
    private func any(_ root: XCUIElement, _ text: String) -> XCUIElement {
        root.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", text)).firstMatch
    }

    /// Notification Center, pulled down over the Home Screen.
    @MainActor
    private func openNotificationCenter() {
        XCUIDevice.shared.press(.home)
        _ = springboard.staticTexts["never"].waitForExistence(timeout: 2)
        let top = springboard.coordinate(withNormalizedOffset: CGVector(dx: 0.3, dy: 0.001))
        top.press(forDuration: 0.1, thenDragTo: springboard.coordinate(withNormalizedOffset: CGVector(dx: 0.3, dy: 0.6)))
        _ = springboard.staticTexts["never"].waitForExistence(timeout: 2)
    }

    @MainActor
    private func scroll(_ app: XCUIApplication, to element: XCUIElement) {
        for _ in 0..<8 where !(element.exists && element.isHittable) { app.swipeUp() }
    }

    /// Step 1: sign in, then Notify me on the show, the explainer, Continue and Allow.
    @MainActor
    func test1_SignInAndNotifyMe() throws {
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
        if !address.waitForExistence(timeout: 15) {
            for title in ["Allow", "OK"] where springboard.buttons[title].waitForExistence(timeout: 2) { springboard.buttons[title].tap() }
        }
        XCTAssertTrue(address.waitForExistence(timeout: 10), "the server picker on a fresh install")
        address.tap()
        address.typeText(env["WMM_NE_SERVER"]!)
        app.buttons["Connect"].firstMatch.tap()
        let name = app.textFields["Username"]
        XCTAssertTrue(name.waitForExistence(timeout: 15))
        name.tap()
        name.typeText(env["WMM_NE_USER"]!)
        let secret = app.secureTextFields["Password"]
        secret.tap()
        secret.typeText(env["WMM_NE_PASSWORD"]!)
        app.buttons["Sign in"].firstMatch.tap()
        XCTAssertTrue(app.navigationBars["Home"].waitForExistence(timeout: 20), "signed in")
        app.terminate()

        let show = launch(["-open", "watchmymedia://shows/\(env["WMM_NE_SHOW"]!)"])
        let bell = show.buttons["show-notify"]
        XCTAssertTrue(bell.waitForExistence(timeout: 20), "the server is at level 29")
        scroll(show, to: bell)
        shot("1 Bell off")
        bell.tap()
        let next = show.buttons["new-episode-ask-continue"]
        XCTAssertTrue(next.waitForExistence(timeout: 10), "the explainer before iOS asks")
        shot("1 Explainer")
        next.tap()
        let allow = springboard.buttons["Allow"]
        XCTAssertTrue(allow.waitForExistence(timeout: 10), "iOS asks")
        allow.tap()
        XCTAssertTrue(any(show, "Notifications on for").waitForExistence(timeout: 10), "followed")
        // The check after Allow keeps the server's cursor.
        _ = show.staticTexts["never"].waitForExistence(timeout: 3)
        shot("1 Bell on")
        show.terminate()
    }

    /// Step 2 (after a file is added and the server's two minutes pass):
    /// opening the app checks, iOS shows the banner, and a tap opens the
    /// show at that episode. WMM_NE_TITLE, WMM_NE_BODY and WMM_NE_CODE say what to expect.
    @MainActor
    func test2_OpeningTheAppNotifiesAndTheTapOpensTheEpisode() throws {
        let app = launch()
        let banner = any(springboard, env["WMM_NE_TITLE"]!)
        XCTAssertTrue(banner.waitForExistence(timeout: 20), "the banner: \(env["WMM_NE_TITLE"]!)")
        XCTAssertTrue(banner.label.contains(env["WMM_NE_BODY"]!), banner.label)
        shot("2 Banner")
        banner.tap()
        XCTAssertTrue(any(app, env["WMM_NE_CODE"]!).waitForExistence(timeout: 15), "the tap opened \(env["WMM_NE_CODE"]!)")
        shot("2 The tap opened the episode")
        app.terminate()
    }

    /// Notification Center holds WMM_NE_TITLE with WMM_NE_BODY (a background check's).
    @MainActor
    func test3_NotificationCenterHasIt() throws {
        openNotificationCenter()
        let note = any(springboard, env["WMM_NE_TITLE"]!)
        XCTAssertTrue(note.waitForExistence(timeout: 10), "in Notification Center: \(env["WMM_NE_TITLE"]!)")
        XCTAssertTrue(note.label.contains(env["WMM_NE_BODY"]!), note.label)
        shot("3 Notification Center")
        XCUIDevice.shared.press(.home)
    }

    /// This iPhone's switch, off or on (WMM_NE_SWITCH), in Settings › Notifications.
    @MainActor
    func test4_TurnTheSwitch() throws {
        let on = env["WMM_NE_SWITCH"] == "on"
        let app = launch(["-tab", "settings"])
        let row = app.buttons["settings-notifications"]
        XCTAssertTrue(row.waitForExistence(timeout: 20))
        row.tap()
        let toggle = app.switches["notifications-new-episodes"].exists ? app.switches["notifications-new-episodes"] : app.switches.firstMatch
        XCTAssertTrue(toggle.waitForExistence(timeout: 10))
        if (toggle.value as? String == "1") != on { toggle.coordinate(withNormalizedOffset: CGVector(dx: 0.93, dy: 0.5)).tap() }
        XCTAssertTrue(any(app, on ? "now shows a notification" : "no longer notifies you").waitForExistence(timeout: 5), "Saved")
        shot("4 Switch \(on ? "on" : "off")")
        // On: the check that follows starts from now.
        _ = app.staticTexts["never"].waitForExistence(timeout: 4)
        app.terminate()
    }

    /// Opening the app shows no banner for WMM_NE_ABSENT.
    @MainActor
    func test5_OpeningTheAppNotifiesNothing() throws {
        let app = launch()
        XCTAssertTrue(app.tabBars.firstMatch.waitForExistence(timeout: 20) || app.buttons.firstMatch.exists)
        XCTAssertFalse(any(springboard, env["WMM_NE_ABSENT"]!).waitForExistence(timeout: 12), "no notification for \(env["WMM_NE_ABSENT"]!)")
        shot("5 Nothing")
        app.terminate()
    }

    /// Sign out: the account's notifications go from Notification Center.
    @MainActor
    func test6_SignOut() throws {
        let app = launch(["-tab", "settings", "-settingsPage", "account"])
        let signOut = app.buttons["account-signOut"]
        XCTAssertTrue(app.navigationBars.firstMatch.waitForExistence(timeout: 20))
        scroll(app, to: signOut)
        XCTAssertTrue(signOut.waitForExistence(timeout: 10))
        signOut.tap()
        let confirm = app.popovers.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Sign out")).firstMatch
        let sheet = app.sheets.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Sign out")).firstMatch
        XCTAssertTrue(confirm.waitForExistence(timeout: 5) || sheet.exists)
        (confirm.exists ? confirm : sheet).tap()
        XCTAssertTrue(app.textFields["Username"].waitForExistence(timeout: 15) || app.textFields.firstMatch.waitForExistence(timeout: 5), "signed out")
        shot("6 Signed out")
        app.terminate()
        openNotificationCenter()
        XCTAssertFalse(any(springboard, env["WMM_NE_SHOW_TITLE"] ?? "\u{0}").waitForExistence(timeout: 4), "the account's notifications went with it")
        shot("6 Notification Center after signing out")
        XCUIDevice.shared.press(.home)
    }
}
