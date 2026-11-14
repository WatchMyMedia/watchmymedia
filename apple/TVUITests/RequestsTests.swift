import XCTest

final class RequestsTests: XCTestCase {
    override func setUp() async throws { continueAfterFailure = false }

    /// The runner approves a pairing on a disposable loopback server, as a
    /// phone would. The app itself connects, claims the code and loads the Inbox.
    @MainActor
    func testFixtureSeerrInboxAfterPairing() async throws {
        let env = ProcessInfo.processInfo.environment
        guard let value = env["WMM_ARR_SERVER"], let base = URL(string: value),
              ["localhost", "127.0.0.1"].contains(base.host ?? "") else {
            throw XCTSkip("Set WMM_ARR_SERVER to a disposable loopback Seerr fixture.")
        }
        let app = XCUIApplication()
        let remote = XCUIRemote.shared
        app.launch()
        let address = app.textFields["Server address"]
        XCTAssertTrue(address.waitForExistence(timeout: 15))
        if !address.hasFocus { remote.press(.right) }
        if !address.hasFocus { remote.press(.up) }
        if !address.hasFocus { remote.press(.down) }
        if !address.hasFocus { remote.press(.up) }
        if !address.hasFocus { remote.press(.right) }
        XCTAssertTrue(address.hasFocus)
        remote.press(.select)
        address.typeText(value)
        remote.press(.menu)
        let connect = app.buttons["Connect"]
        for _ in 0..<3 where !connect.hasFocus { remote.press(.down) }
        XCTAssertTrue(connect.hasFocus)
        remote.press(.select)
        let codeElement = app.descendants(matching: .any).matching(NSPredicate(format: "label BEGINSWITH %@", "Code ")).firstMatch
        XCTAssertTrue(codeElement.waitForExistence(timeout: 20))
        let code = String(codeElement.label.dropFirst(5)).filter { $0.isLetter || $0.isNumber }
        XCTAssertEqual(code.count, 6)
        let session = URLSession(configuration: .ephemeral)
        defer { session.invalidateAndCancel() }
        var login = URLRequest(url: base.appendingPathComponent("api/auth/login"))
        login.httpMethod = "POST"
        login.setValue("application/json", forHTTPHeaderField: "Content-Type")
        login.httpBody = try JSONSerialization.data(withJSONObject: [
            "name": "admin", "password": env["WMM_ARR_PASSWORD"] ?? "fixture-admin-1",
            "client": ["deviceId": UUID().uuidString, "name": "ARR R6 pairing check", "platform": "tvOS"]
        ])
        let (data, response) = try await session.data(for: login)
        XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
        let reply = try XCTUnwrap(try JSONSerialization.jsonObject(with: data) as? [String: Any])
        let token = try XCTUnwrap(reply["token"] as? String)
        var approve = URLRequest(url: base.appendingPathComponent("api/auth/pair/\(code)/approve"))
        approve.httpMethod = "POST"
        approve.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        let (_, approved) = try await session.data(for: approve)
        XCTAssertEqual((approved as? HTTPURLResponse)?.statusCode, 200)
        let settings = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Settings")).firstMatch
        XCTAssertTrue(settings.waitForExistence(timeout: 20))
        var logout = URLRequest(url: base.appendingPathComponent("api/auth/logout"))
        logout.httpMethod = "POST"
        logout.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        _ = try await session.data(for: logout)
        app.terminate()
        app.launchArguments = ["-open", "watchmymedia://inbox"]
        app.launch()
        XCTAssertTrue(app.staticTexts["Requests are approved in Seerr"].waitForExistence(timeout: 20))
        XCTAssertTrue(app.buttons["Approve"].firstMatch.exists, "leave shared legacy requests untouched")
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = "Fixture Seerr Inbox"
        shot.lifetime = .keepAlways
        add(shot)
    }

    @MainActor
    func testSeerrInboxKeepsLocalDecisionsVisible() {
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-fakesSeerr", "-open", "watchmymedia://inbox"]
        app.launch()
        XCTAssertTrue(app.staticTexts["Requests are approved in Seerr"].waitUntilExists(timeout: 15))
        XCTAssertTrue(app.buttons["Approve"].firstMatch.exists)
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.lifetime = .keepAlways
        add(shot)
    }
}
