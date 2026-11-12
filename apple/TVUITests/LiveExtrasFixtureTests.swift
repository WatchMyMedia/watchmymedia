import XCTest

/// Favourites and reminders on Apple TV against the synthetic fixture with
/// listings (`apple/scripts/live-tv-fixture.py --listings`), opt-in with
/// TEST_RUNNER_WMM_LIVE_SERVER=http://127.0.0.1:<port> on a fresh install of a
/// task-owned simulator. The test's own sign-in plays the person's phone.
final class LiveExtrasFixtureTests: XCTestCase {
    @MainActor private var remote: XCUIRemote { XCUIRemote.shared }

    override func setUp() async throws { continueAfterFailure = false }

    @MainActor
    private func snap(_ name: String) {
        let shot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        shot.name = name
        shot.lifetime = .keepAlways
        add(shot)
    }

    @MainActor
    private func focus(_ element: XCUIElement, moves: [XCUIRemote.Button]) -> Bool {
        if element.exists && element.hasFocus { return true }
        for move in moves {
            remote.press(move)
            if element.exists && element.hasFocus { return true }
        }
        return false
    }

    @MainActor
    func testAStarAndAReminderSetOnTheTVReachTheServer() async throws {
        guard let host = ProcessInfo.processInfo.environment["WMM_LIVE_SERVER"], let base = URL(string: host),
              base.scheme == "http", ["127.0.0.1", "localhost"].contains(base.host ?? ""), base.port != nil else {
            throw XCTSkip("Set TEST_RUNNER_WMM_LIVE_SERVER to http://127.0.0.1:<port> of a disposable fixture with listings.")
        }
        let api = TVExtrasFixtureAPI(base: base)
        try await api.verifyAndLogin()
        let app = XCUIApplication()
        app.launch()
        defer { app.terminate() }

        // Pair with the owned loopback server; the test approves the code.
        let address = app.textFields["Server address"]
        XCTAssertTrue(address.waitForExistence(timeout: 15), "Use a fresh owned install; never select a remembered or discovered server.")
        XCTAssertTrue(focus(address, moves: [.right, .up, .down, .up, .right]))
        remote.press(.select)
        for character in base.absoluteString { address.typeText(String(character)) }
        remote.press(.menu)
        XCTAssertEqual(address.value as? String, base.absoluteString)
        let connect = app.buttons["Connect"]
        XCTAssertTrue(focus(connect, moves: [.down, .down, .down]))
        remote.press(.select)
        let codeElement = app.descendants(matching: .any).matching(NSPredicate(format: "label BEGINSWITH %@", "Code ")).firstMatch
        XCTAssertTrue(codeElement.waitForExistence(timeout: 20))
        try await api.approve(String(codeElement.label.dropFirst(5)).filter { $0.isLetter || $0.isNumber })

        let liveTab = app.buttons["Live TV"].firstMatch
        XCTAssertTrue(liveTab.waitForExistence(timeout: 30))
        XCTAssertTrue(focus(liveTab, moves: [.up, .up, .up] + Array(repeating: .right, count: 5) + Array(repeating: .left, count: 5)))
        remote.press(.select)
        XCTAssertTrue(app.buttons["live-guide-reminders"].waitForExistence(timeout: 30), "The fixture's level 15 guide has the extras chips")

        // Down to the first programme, then left to its channel, and star it.
        let cells = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'live-cell-'"))
        XCTAssertTrue(cells.firstMatch.waitForExistence(timeout: 20))
        for _ in 0..<6 where !cells.allElementsBoundByIndex.contains(where: \.hasFocus) { remote.press(.down) }
        remote.press(.left)
        let channel = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'live-channel-' AND hasFocus == true")).firstMatch
        XCTAssertTrue(channel.waitForExistence(timeout: 5), "Left from the first programme reaches its channel")
        let number = String(channel.identifier.dropFirst("live-channel-".count))
        let wasFavourite = channel.label.contains("favourite")
        remote.press(.select, forDuration: 1.5)
        let item = app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", wasFavourite ? "Remove from Favourites" : "Add to Favourites")).firstMatch
        XCTAssertTrue(item.waitForExistence(timeout: 5))
        remote.press(.select)
        try await eventually("the server keeps the star") { (try await api.channels().first { $0.number == number }?.favourite ?? false) == !wasFavourite }
        snap("tv-fixture-star")

        // Right to a later programme on that channel, and remind.
        remote.press(.right); remote.press(.right)
        var reminded = false
        for _ in 0..<4 where !reminded {
            remote.press(.right)
            remote.press(.select, forDuration: 1.5)
            let remind = app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", "Remind me")).firstMatch
            if remind.waitForExistence(timeout: 3) { remote.press(.select); reminded = true } else { remote.press(.menu) }
        }
        XCTAssertTrue(reminded, "A later programme's long press offers Remind me")
        try await eventually("the server keeps the reminder") { try await api.reminderCount() == 1 }
        XCTAssertTrue(app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", "Reminder set · 5 min before")).firstMatch.waitForExistence(timeout: 10))
        snap("tv-fixture-reminder")
    }

    @MainActor
    private func eventually(_ what: String, _ condition: @MainActor () async throws -> Bool) async throws {
        for _ in 0..<30 {
            if try await condition() { return }
            try await Task.sleep(for: .milliseconds(500))
        }
        XCTFail("Timed out: \(what)")
    }
}

private final class TVExtrasFixtureAPI: @unchecked Sendable {
    struct Channel: Decodable { let id: String; let number: String; let favourite: Bool? }
    private struct Items<T: Decodable>: Decodable { let items: [T] }
    private struct Server: Decodable { let version: String; let apiLevel: Int }
    private struct Login: Decodable { let token: String }
    private struct Reminder: Decodable { let id: String }

    private let base: URL
    private var token = ""
    init(base: URL) { self.base = base.appendingPathComponent("api") }

    func verifyAndLogin() async throws {
        let server = try JSONDecoder().decode(Server.self, from: await request("server"))
        guard server.version == "live-tv-fixture", server.apiLevel >= 15 else {
            throw XCTSkip("The owned port must serve the synthetic live-tv-fixture at API level 15 or newer.")
        }
        let body = try JSONSerialization.data(withJSONObject: [
            "name": "admin", "password": "fixture-admin-1",
            "client": ["deviceId": UUID().uuidString, "name": "TV extras test observer", "platform": "ios"],
        ])
        token = try JSONDecoder().decode(Login.self, from: await request("auth/login", method: "POST", body: body)).token
    }
    func approve(_ code: String) async throws { _ = try await request("auth/pair/\(code)/approve", method: "POST") }
    func channels() async throws -> [Channel] { try JSONDecoder().decode(Items<Channel>.self, from: await request("live-tv/channels")).items }
    func reminderCount() async throws -> Int { try JSONDecoder().decode(Items<Reminder>.self, from: await request("live-tv/reminders")).items.count }

    private func request(_ path: String, method: String = "GET", body: Data? = nil) async throws -> Data {
        var request = URLRequest(url: base.appendingPathComponent(path))
        request.httpMethod = method; request.httpBody = body
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if !token.isEmpty { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        let (data, response) = try await URLSession.shared.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else {
            throw NSError(domain: "LiveExtrasFixtureTests", code: status, userInfo: [NSLocalizedDescriptionKey: "Fixture API returned \(status) for \(path)"])
        }
        return data
    }
}
