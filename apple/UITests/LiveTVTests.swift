import XCTest
import UIKit

/// Live TV navigation and transport stay separate from library-file playback.
/// Synthetic integration is opt-in with TEST_RUNNER_WMM_LIVE_SERVER. Use a
/// fresh install on a task-owned simulator and a disposable localhost server.
final class LiveTVTests: XCTestCase {
    override func setUp() async throws { continueAfterFailure = false }

    @MainActor
    func testChannelsSearchPlaybackAndSettings() throws {
        let app = launchFake()
        defer { app.terminate() }
        let search = app.textFields["live-channel-search"]
        XCTAssertTrue(search.waitForExistence(timeout: 20), "Live TV opens even while capabilities load")
        XCTAssertTrue(app.buttons["live-channel-2.1"].exists)
        search.tap()
        search.typeText("Northline")
        XCTAssertTrue(app.buttons["live-channel-5.1"].exists)
        XCTAssertTrue(app.buttons["live-channel-5.2"].exists)
        XCTAssertFalse(app.buttons["live-channel-2.1"].exists)
        shot("Live TV channel search")
        app.buttons["live-channel-5.1"].tap()
        waitForPlayer(app)
        XCTAssertTrue(app.staticTexts["5.1 · Northline"].exists)
        assertNoFileControls(app)
        app.buttons["Next channel"].tap()
        XCTAssertTrue(app.staticTexts["5.2 · Northline Extra"].waitForExistence(timeout: 5))
        app.buttons["live-play-pause"].tap()
        expect(app.buttons["live-play-pause"], "label == 'Play live TV'")
        XCTAssertTrue(app.buttons["Go live"].exists)
        shot("Live TV paused with Go live")
        app.buttons["Go live"].tap()
        expect(app.buttons["live-play-pause"], "label == 'Pause live TV'")
        XCTAssertFalse(app.buttons["Go live"].exists)
        app.buttons["live-quality"].tap()
        XCTAssertFalse(app.buttons["Automatic"].exists)
        XCTAssertFalse(app.buttons["Original"].exists)
        app.buttons["480p"].tap()
        app.buttons["Stop and close live TV"].tap()
        XCTAssertTrue(search.waitForExistence(timeout: 10))
        app.buttons["Settings"].firstMatch.tap()
        XCTAssertTrue(app.buttons["Done"].waitForExistence(timeout: 10))
        XCTAssertTrue(element(app, containing: "Signed-in devices").exists)
        shot("Live TV profile opens Settings")
        app.buttons["Done"].tap()
        XCTAssertTrue(app.navigationBars["Home"].waitForExistence(timeout: 10))
    }

    @MainActor
    func testOlderServerKeepsSettingsAndHidesLiveTV() {
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-tab", "settings"]
        app.launch()
        defer { app.terminate() }
        XCTAssertTrue(element(app, containing: "Signed-in devices").waitForExistence(timeout: 20))
        XCTAssertFalse(app.buttons["Live TV"].exists)
        XCTAssertFalse(app.buttons["live-channel-2.1"].exists)
        XCTAssertFalse(app.buttons["Done"].exists, "older servers retain the Settings tab")
    }

    @MainActor
    func testLiveAutomaticReportsActualOutputAndKeepsFixedChoices() {
        let app = launchFake(extra: ["-fakesLiveAutomatic", "-open", "watchmymedia://live-tv/live-1"])
        defer { app.terminate() }
        waitForPlayer(app)
        let quality = app.buttons["live-quality"]
        XCTAssertTrue(quality.label.contains("Auto · Original"))
        quality.tap()
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "Auto · Original")).firstMatch.exists)
        XCTAssertTrue(app.buttons["480p"].exists)
        app.buttons["480p"].tap()
        XCTAssertTrue(quality.label.contains("480p"))
        quality.tap()
        app.buttons["Automatic"].tap()
        XCTAssertTrue(quality.label.contains("Auto · Original"))

        app.terminate()
        app.launchArguments = ["-fakes", "-fakesLiveAutomatic", "-open", "watchmymedia://live-tv/live-1", "-liveAutoLow"]
        app.launch()
        waitForPlayer(app)
        XCTAssertTrue(app.buttons["live-quality"].label.contains("Auto · 480p"))
    }

    @MainActor
    func testStillWatchingAndStoppedPrompts() {
        let app = launchFake(extra: ["-open", "watchmymedia://live-tv/live-1", "-liveStillWatching"])
        XCTAssertTrue(app.staticTexts["Are you still watching?"].waitForExistence(timeout: 20))
        shot("Live TV still watching")
        app.buttons["Keep watching"].tap()
        XCTAssertFalse(app.staticTexts["Are you still watching?"].exists)
        waitForPlayer(app)
        app.terminate()

        app.launchArguments = ["-fakes", "-fakesLiveTV", "-open", "watchmymedia://live-tv/live-1", "-livePauseExpired"]
        app.launch()
        XCTAssertTrue(app.staticTexts["Live TV stopped"].waitForExistence(timeout: 20))
        XCTAssertTrue(element(app, containing: "five minutes paused").exists)
        XCTAssertFalse(app.scrollViews["live-player-channels"].buttons["live-channel-2.1"].label.contains(", playing"))
        shot("Live TV pause expiry")
        app.buttons["Watch live"].tap()
        waitForPlayer(app)
        app.terminate()

        app.launchArguments = ["-fakes", "-fakesLiveTV", "-open", "watchmymedia://live-tv/live-1", "-liveBusy"]
        app.launch()
        defer { app.terminate() }
        XCTAssertTrue(app.staticTexts["Can't play this channel"].waitForExistence(timeout: 20))
        XCTAssertTrue(element(app, containing: "All tuners are in use").exists)
        shot("Live TV tuner busy")
        app.buttons["Channels"].tap()
        XCTAssertTrue(app.buttons["Live TV"].firstMatch.waitForExistence(timeout: 10))
    }

    @MainActor
    func testTabletRegularPortraitAndCompactWindow() throws {
        guard UIDevice.current.userInterfaceIdiom == .pad else { throw XCTSkip("Requires an iPad.") }
        guard #available(iOS 26, *) else { throw XCTSkip("Requires iPadOS window resizing.") }
        let app = launchFake()
        XCUIDevice.shared.orientation = .landscapeLeft
        defer { app.terminate(); XCUIDevice.shared.orientation = .portrait }
        XCTAssertTrue(app.buttons["live-channel-2.1"].waitForExistence(timeout: 20))
        shot("iPad regular Live TV browser")
        let originalFrame = app.windows.firstMatch.frame
        XCTAssertGreaterThanOrEqual(originalFrame.width, 1000)
        app.buttons["live-channel-2.1"].tap()
        waitForPlayer(app)
        app.buttons["Channels"].tap()
        XCTAssertTrue(app.buttons["Close channels"].exists)
        app.scrollViews["live-player-channels"].buttons["live-channel-5.1"].tap()
        XCTAssertTrue(app.staticTexts["5.1 · Northline"].waitForExistence(timeout: 5))
        XCUIDevice.shared.orientation = .portrait
        XCTAssertTrue(app.scrollViews["live-player-channels"].waitForExistence(timeout: 10))
        shot("iPad portrait Live TV video and channels")
        assertNoFileControls(app)
        app.buttons["Stop and close live TV"].tap()
        XCUIDevice.shared.orientation = .landscapeLeft
        XCTAssertTrue(app.textFields["live-channel-search"].waitForExistence(timeout: 10))
        let corner = app.coordinate(withNormalizedOffset: CGVector(dx: 1, dy: 1)).withOffset(CGVector(dx: -3, dy: -3))
        corner.press(forDuration: 0.5, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.45, dy: 0.9)))
        defer {
            let window = app.windows.firstMatch
            let restore = window.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(
                dx: originalFrame.maxX - window.frame.minX - 3, dy: originalFrame.maxY - window.frame.minY - 3))
            window.coordinate(withNormalizedOffset: CGVector(dx: 1, dy: 1)).withOffset(CGVector(dx: -3, dy: -3))
                .press(forDuration: 0.5, thenDragTo: restore)
        }
        let compact = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in app.windows.firstMatch.frame.width < 600 }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [compact], timeout: 10), .completed)
        XCTAssertTrue(app.buttons["live-channel-2.1"].exists)
        XCTAssertTrue(app.textFields["live-channel-search"].exists)
        shot("iPad compact Live TV browser")
        app.buttons["live-channel-2.1"].tap()
        waitForPlayer(app)
        XCTAssertTrue(app.scrollViews["live-player-channels"].exists)
        shot("iPad compact Live TV playback")
        app.buttons["Stop and close live TV"].tap()
    }

    @MainActor
    func testSyntheticPlaybackSwitchCloseReopenAndPauseExpiry() async throws {
        guard let host = ProcessInfo.processInfo.environment["WMM_LIVE_SERVER"], !host.isEmpty else {
            throw XCTSkip("Set TEST_RUNNER_WMM_LIVE_SERVER to a disposable localhost Live TV server.")
        }
        // Never sign in to discovered or real servers from the integration test.
        let base = try XCTUnwrap(URL(string: host.contains("://") ? host : "http://\(host)"))
        guard ["localhost", "127.0.0.1", "::1"].contains(base.host ?? "") else {
            XCTFail("Live TV UI tests only use disposable loopback servers")
            return
        }
        let api = FixtureLiveAPI(base: base)
        try await api.verifyDisposableFixture()
        try await api.login()
        let priorSignIns = Set(try await api.signIns().map(\.id))
        let baseline = Set(try await api.active().map(\.id))
        let app = XCUIApplication()
        addUIInterruptionMonitor(withDescription: "Local network") { alert in
            for title in ["Allow", "OK"] where alert.buttons[title].exists {
                alert.buttons[title].tap()
                return true
            }
            return false
        }
        XCUIDevice.shared.orientation = .portrait
        app.launch()
        defer { app.terminate() }
        let address = app.textFields.firstMatch
        XCTAssertTrue(address.waitForExistence(timeout: 15), "Run on a fresh task-owned simulator install")
        address.tap(); address.typeText(host)
        app.buttons["Connect"].firstMatch.tap()
        let name = app.textFields["Username"]
        XCTAssertTrue(name.waitForExistence(timeout: 15))
        name.tap(); name.typeText("admin")
        app.secureTextFields["Password"].tap(); app.secureTextFields["Password"].typeText("fixture-admin-1")
        app.buttons["Sign in"].firstMatch.tap()
        let tab = app.buttons["Live TV"].firstMatch
        XCTAssertTrue(tab.waitForExistence(timeout: 20))
        let appSignIns = try await api.signIns().filter { !priorSignIns.contains($0.id) }
        XCTAssertEqual(appSignIns.count, 1, "identify this test app's new sign-in before revoking it")
        let appSignIn = try XCTUnwrap(appSignIns.first)
        tab.tap()
        let channels = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'live-channel-'"))
        XCTAssertTrue(channels.firstMatch.waitForExistence(timeout: 20))
        let firstID = channels.element(boundBy: 0).identifier
        let secondID = channels.element(boundBy: 1).identifier
        app.buttons[firstID].tap()
        waitForPlayer(app, timeout: 45)
        let first = try await api.waitForActive(excluding: baseline)
        XCTAssertGreaterThan(first.bufferBytes, 0, "the tuner produced HLS media")
        assertNoFileControls(app)
        shot("Synthetic live playback")
        // Attach two video frames, excluding transport/text overlays, and verify
        // actual rendered video changes while the server produces media.
        let before = try videoSample(app)
        try await Task.sleep(for: .seconds(3))
        let after = try videoSample(app)
        XCTAssertNotEqual(before, after, "the synthetic video renders advancing frames")
        shot("Synthetic live playback advancing")
        for id in [secondID, firstID, secondID] { app.scrollViews["live-player-channels"].buttons[id].tap() }
        waitForPlayer(app, timeout: 45)
        let current = try await api.waitForActive(excluding: baseline.union([first.id]))
        try await api.waitUntilReleased(first.id)
        app.buttons["live-play-pause"].tap()
        expect(app.buttons["live-play-pause"], "label == 'Play live TV'")
        XCTAssertTrue(app.buttons["Go live"].exists)
        app.buttons["Go live"].tap()
        waitForPlayer(app, timeout: 45)
        app.buttons["Stop and close live TV"].tap()
        try await api.waitUntilReleased(current.id)
        XCTAssertTrue(app.buttons[firstID].waitForExistence(timeout: 10))
        app.buttons[firstID].tap()
        waitForPlayer(app, timeout: 45)
        let reopened = try await api.waitForActive(excluding: baseline.union([first.id, current.id]))
        XCTAssertNotEqual(reopened.id, current.id)
        if ProcessInfo.processInfo.environment["WMM_LIVE_PAUSE_EXPIRY"] == "1" {
            app.buttons["live-play-pause"].tap()
            expect(app.buttons["live-play-pause"], "label == 'Play live TV'")
            XCTAssertTrue(app.staticTexts["Live TV stopped"].waitForExistence(timeout: 330), "five-minute pause releases the real synthetic tuner")
            XCTAssertTrue(element(app, containing: "five minutes paused").exists)
            try await api.waitUntilReleased(reopened.id)
            shot("Synthetic pause expiry releases tuner")
            app.buttons["Watch live"].tap()
            waitForPlayer(app, timeout: 45)
        }
        // Revoke only the sign-in created above, while it owns the current live
        // stream. Foreground refresh must rebuild account views and release it.
        try await api.revokeSignIn(appSignIn.id)
        let revokedAt = Date()
        XCUIDevice.shared.press(.home)
        app.activate()
        XCTAssertTrue(app.textFields["Username"].waitForExistence(timeout: 30), "revoked live account returns to sign in")
        XCTAssertFalse(app.buttons["live-play-pause"].exists)
        try await api.waitForBaseline(baseline)
        shot("Synthetic account revocation releases live tuner")
        app.textFields["Username"].tap()
        app.textFields["Username"].typeText("admin")
        app.secureTextFields["Password"].tap()
        app.secureTextFields["Password"].typeText("fixture-admin-1")
        app.buttons["Sign in"].firstMatch.tap()
        let reconnectedTab = app.buttons.matching(NSPredicate(format: "label == 'Live TV'")).firstMatch
        XCTAssertTrue(reconnectedTab.waitForExistence(timeout: 20))
        expect(reconnectedTab, "hittable == true")
        reconnectedTab.tap()
        XCTAssertTrue(app.textFields["live-channel-search"].waitForExistence(timeout: 20))
        shot("Synthetic reconnect channels")
        XCTAssertTrue(app.buttons[firstID].waitForExistence(timeout: 20))
        app.buttons[firstID].tap()
        shot("Synthetic reconnect channel selected")
        let releaseBarrier = element(app, containing: "Could not release the previous channel")
        if releaseBarrier.waitForExistence(timeout: 5) {
            // Revoked credentials cannot confirm DELETE. The shared controller
            // conservatively retains ownership until the old 65-second lease
            // deadline, even when the observer already sees server cleanup.
            XCTAssertTrue(app.buttons["Try again"].exists)
            shot("Synthetic reconnect waits for original lease")
            while Date().timeIntervalSince(revokedAt) < 70 {
                try await Task.sleep(for: .seconds(5))
            }
            app.buttons["Try again"].tap()
        }
        waitForPlayer(app, timeout: 45)
        app.buttons["Stop and close live TV"].tap()
        try await api.waitForBaseline(baseline)
        try await api.logout()
    }

    @MainActor private func launchFake(extra: [String] = []) -> XCUIApplication {
        XCUIDevice.shared.orientation = .portrait
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-fakesLiveTV", "-tab", "liveTV"] + extra
        app.launch()
        return app
    }
    @MainActor private func waitForPlayer(_ app: XCUIApplication, timeout: TimeInterval = 20) {
        let ready = app.buttons["live-play-pause"].waitForExistence(timeout: timeout)
        if !ready {
            shot("Live player did not reach playable state")
            add(XCTAttachment(string: app.debugDescription))
        }
        XCTAssertTrue(ready, "channel reaches playable state")
        XCTAssertTrue(app.buttons["Stop and close live TV"].exists)
    }
    @MainActor private func assertNoFileControls(_ app: XCUIApplication) {
        for label in ["Playback position", "Back 10 seconds", "Forward 10 seconds", "Skip Intro", "Next episode", "Cast", "Download"] {
            XCTAssertFalse(app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", label)).firstMatch.exists, "Live TV omits \(label)")
        }
        XCTAssertEqual(app.sliders.count, 0)
    }
    @MainActor private func expect(_ element: XCUIElement, _ predicate: String) {
        XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: predicate), object: element)], timeout: 10), .completed)
    }
    @MainActor private func element(_ app: XCUIApplication, containing text: String) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS[c] %@", text)).firstMatch
    }
    @MainActor private func shot(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name; attachment.lifetime = .keepAlways; add(attachment)
    }
    @MainActor private func videoSample(_ app: XCUIApplication) throws -> Data {
        let image = app.screenshot().image
        let pixels = try XCTUnwrap(image.cgImage)
        // A horizontal stripe between the top controls and central transport.
        let rect = CGRect(x: CGFloat(pixels.width) * 0.2, y: 110 * image.scale,
                          width: CGFloat(pixels.width) * 0.6, height: 22 * image.scale)
        let crop = try XCTUnwrap(pixels.cropping(to: rect))
        return try XCTUnwrap(UIImage(cgImage: crop).pngData())
    }
}

private actor FixtureLiveAPI {
    struct Session: Decodable, Sendable { let id: String; let state: String; let bufferBytes: Int }
    private struct Activity: Decodable { let items: [Session] }
    private struct SignIn: Decodable { let token: String }
    struct SignInSession: Decodable, Sendable { let id: String }
    private struct SignInSessions: Decodable { let items: [SignInSession] }
    private struct Health: Decodable { let version: String }
    let base: URL
    private var token = ""
    init(base: URL) { self.base = base }
    func verifyDisposableFixture() async throws {
        let data = try await request("health")
        guard try JSONDecoder().decode(Health.self, from: data).version == "live-tv-fixture" else {
            throw XCTSkip("Live TV integration requires the synthetic live-tv-fixture server")
        }
    }
    func signIns() async throws -> [SignInSession] {
        let data = try await request("auth/sessions")
        return try JSONDecoder().decode(SignInSessions.self, from: data).items
    }
    func revokeSignIn(_ id: String) async throws { _ = try await request("auth/sessions/\(id)", method: "DELETE") }
    func logout() async throws { _ = try await request("auth/logout", method: "POST") }
    func login() async throws {
        let body: [String: Any] = ["name": "admin", "password": "fixture-admin-1",
                                  "client": ["deviceId": UUID().uuidString, "name": "Live TV UI test observer", "platform": "ios"]]
        let data = try await request("auth/login", method: "POST", body: JSONSerialization.data(withJSONObject: body))
        token = try JSONDecoder().decode(SignIn.self, from: data).token
    }
    func active() async throws -> [Session] {
        let data = try await request("live-tv/activity")
        return try JSONDecoder().decode(Activity.self, from: data).items.filter { ["starting", "playing", "stopping"].contains($0.state) }
    }
    func waitForActive(excluding: Set<String>) async throws -> Session {
        for _ in 0..<45 {
            if let session = try await active().first(where: { !excluding.contains($0.id) && $0.state == "playing" && $0.bufferBytes > 0 }) { return session }
            try await Task.sleep(for: .seconds(1))
        }
        throw NSError(domain: "LiveTVTests", code: 1, userInfo: [NSLocalizedDescriptionKey: "No new playing synthetic live session"])
    }
    func waitUntilReleased(_ id: String) async throws {
        for _ in 0..<20 {
            if try await !active().contains(where: { $0.id == id }) { return }
            try await Task.sleep(for: .seconds(1))
        }
        XCTFail("Original live session was not released")
    }
    func waitForBaseline(_ baseline: Set<String>) async throws {
        for _ in 0..<20 {
            if Set(try await active().map(\.id)).isSubset(of: baseline) { return }
            try await Task.sleep(for: .seconds(1))
        }
        XCTFail("Closing the live player left an active tuner")
    }
    private func request(_ path: String, method: String = "GET", body: Data? = nil) async throws -> Data {
        var request = URLRequest(url: base.appendingPathComponent("api").appendingPathComponent(path))
        request.httpMethod = method; request.httpBody = body
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if !token.isEmpty { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        let (data, response) = try await URLSession.shared.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else {
            throw NSError(domain: "LiveTVTests", code: status, userInfo: [NSLocalizedDescriptionKey: "Synthetic fixture API returned \(status)"])
        }
        return data
    }
}
