import XCTest

/// Favourites, search and reminders against the synthetic fixture with
/// listings (`apple/scripts/live-tv-fixture.py --listings`), opt-in with
/// TEST_RUNNER_WMM_LIVE_SERVER on a fresh install of a task-owned simulator.
/// The test's own sign-in plays the person's other device: what it changes
/// reaches the app through the server's private events.
final class LiveExtrasFixtureTests: XCTestCase {
    override func setUp() async throws { continueAfterFailure = false }

    @MainActor
    private func snap(_ name: String) {
        let shot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        shot.name = name
        shot.lifetime = .keepAlways
        add(shot)
    }

    @MainActor
    func testStarsRemindersAndTheLeadTimeReachTheServerAndComeBackFromIt() async throws {
        guard let host = ProcessInfo.processInfo.environment["WMM_LIVE_SERVER"], !host.isEmpty else {
            throw XCTSkip("Set TEST_RUNNER_WMM_LIVE_SERVER to a disposable localhost Live TV server with listings.")
        }
        let base = try XCTUnwrap(URL(string: host.contains("://") ? host : "http://\(host)"))
        guard ["localhost", "127.0.0.1", "::1"].contains(base.host ?? "") else {
            XCTFail("Live TV UI tests only use disposable loopback servers")
            return
        }
        let api = ExtrasFixtureAPI(base: base)
        try await api.verifyDisposableFixture()
        try await api.login()
        // Start from no reminders on the disposable fixture, so the bells are this run's.
        for reminder in try await api.reminders() { try await api.removeReminder(reminder.id) }

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
        tab.tap()

        // A star goes to the server, whichever way it was.
        let stars = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'live-star-'"))
        XCTAssertTrue(stars.firstMatch.waitForExistence(timeout: 30), "The fixture's level 15 guide has stars")
        let first = stars.element(boundBy: 0)
        let number = String(first.identifier.dropFirst("live-star-".count))
        let wasFavourite = first.label == "Remove \(number) from Favourites"
        first.tap()
        try await eventually("the server keeps the star") {
            (try await api.channels().first { $0.number == number }?.favourite ?? false) == !wasFavourite
        }
        snap("fixture-star")

        // Another device stars a channel this one hasn't: the event brings it here.
        let unstarred = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'live-star-' AND label BEGINSWITH 'Add '"))
            .allElementsBoundByIndex.first { $0.identifier != first.identifier }
        let other = try XCTUnwrap(unstarred, "A channel without a star")
        let otherNumber = String(other.identifier.dropFirst("live-star-".count))
        let channels = try await api.channels()
        let otherID = try XCTUnwrap(channels.first { $0.number == otherNumber }?.id)
        try await api.setFavourite(otherID)
        XCTAssertTrue(app.buttons["Remove \(otherNumber) from Favourites"].waitForExistence(timeout: 20), "live.favourites.changed reaches the app")
        try await api.removeFavourite(otherID)
        XCTAssertTrue(app.buttons["Add \(otherNumber) to Favourites"].waitForExistence(timeout: 20), "and so does its removal")

        // Search listings and remind.
        app.buttons["live-guide-search"].tap()
        let field = app.searchFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 10))
        field.typeText("Quiz")
        let bell = app.buttons["live-search-bell"].firstMatch
        XCTAssertTrue(bell.waitForExistence(timeout: 20), "A later Quiz Parlor offers Remind me")
        snap("fixture-search")
        let before = Set(try await api.reminders().map(\.id))
        bell.tap()
        let notNow = app.buttons["live-reminder-ask-not-now"]
        if notNow.waitForExistence(timeout: 5) { notNow.tap() }
        XCTAssertTrue(app.buttons["live-search-bell-set"].firstMatch.waitForExistence(timeout: 10))
        var added: [ExtrasFixtureAPI.Reminder] = []
        try await eventually("the server keeps the reminder") {
            added = try await api.reminders().filter { !before.contains($0.id) }
            return added.count == 1
        }
        XCTAssertEqual(added.first?.title, "Quiz Parlor")
        XCTAssertEqual(added.first?.state, "set")
        snap("fixture-reminded")

        // Another device removes it: the bell goes back.
        try await api.removeReminder(try XCTUnwrap(added.first?.id))
        let gone = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: app.buttons["live-search-bell-set"].firstMatch)
        XCTAssertEqual(XCTWaiter().wait(for: [gone], timeout: 20), .completed, "live.reminders.changed reaches the app")

        // The lead time is the server's preference.
        app.navigationBars.buttons.firstMatch.tap()
        app.buttons["Settings"].firstMatch.tap()
        let row = app.buttons["settings-liveTV"]
        XCTAssertTrue(row.waitForExistence(timeout: 10))
        // The iPad's Settings sheet keeps its rows disabled while it comes up.
        let enabled = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true"), object: row)
        XCTAssertEqual(XCTWaiter().wait(for: [enabled], timeout: 10), .completed)
        row.tap()
        let ten = app.buttons["live-lead-10"]
        XCTAssertTrue(ten.waitForExistence(timeout: 10))
        ten.tap()
        try await eventually("the server keeps the lead time") { try await api.leadMinutes() == 10 }
        snap("fixture-lead")
        app.buttons["live-lead-5"].tap()
        try await eventually("and back") { try await api.leadMinutes() == 5 }

        // A wide iPad's panel beside the grid, with the fixture's listings.
        guard UIDevice.current.userInterfaceIdiom == .pad else { return }
        app.buttons["Done"].firstMatch.tap()
        XCUIDevice.shared.orientation = .landscapeLeft
        defer { XCUIDevice.shared.orientation = .portrait }
        // Landscape may bring the sidebar, which leaves the grid too narrow for its panel.
        let hide = app.buttons["Hide Sidebar"].firstMatch
        if hide.waitForExistence(timeout: 3) { hide.tap() }
        let guide = app.buttons["Guide"].firstMatch
        XCTAssertTrue(guide.waitForExistence(timeout: 10))
        guide.tap()
        let search = app.buttons["live-guide-search"]
        XCTAssertTrue(search.waitForExistence(timeout: 10))
        search.tap()
        let panelField = app.textFields["live-search-field"]
        XCTAssertTrue(panelField.waitForExistence(timeout: 10), "Search listings opens in the panel")
        panelField.tap(); panelField.typeText("Quiz")
        XCTAssertTrue(app.buttons["live-search-bell"].firstMatch.waitForExistence(timeout: 20))
        snap("fixture-pad-search")
        app.buttons["live-guide-reminders"].tap()
        XCTAssertTrue(app.otherElements["live-reminders-panel"].waitForExistence(timeout: 10))
        snap("fixture-pad-reminders")
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

/// The fixture's API as the person's other device.
private final class ExtrasFixtureAPI: @unchecked Sendable {
    struct Channel: Decodable { let id: String; let number: String; let favourite: Bool? }
    struct Channels: Decodable { let items: [Channel] }
    struct Reminder: Decodable { let id: String; let title: String; let state: String }
    struct Reminders: Decodable { let items: [Reminder]; let leadMinutes: Int }
    private struct Health: Decodable { let version: String }
    private struct SignIn: Decodable { let token: String }

    private let base: URL
    private var token = ""
    init(base: URL) { self.base = base }

    func verifyDisposableFixture() async throws {
        guard try JSONDecoder().decode(Health.self, from: try await request("health")).version == "live-tv-fixture" else {
            throw XCTSkip("Live TV integration requires the synthetic live-tv-fixture server")
        }
    }
    func login() async throws {
        let body: [String: Any] = ["name": "admin", "password": "fixture-admin-1",
                                  "client": ["deviceId": UUID().uuidString, "name": "Live TV extras UI test observer", "platform": "ios"]]
        token = try JSONDecoder().decode(SignIn.self, from: try await request("auth/login", method: "POST", body: JSONSerialization.data(withJSONObject: body))).token
    }
    func channels() async throws -> [Channel] { try JSONDecoder().decode(Channels.self, from: try await request("live-tv/channels")).items }
    func setFavourite(_ id: String) async throws { _ = try await request("live-tv/channels/\(id)/favourite", method: "PUT") }
    func removeFavourite(_ id: String) async throws { _ = try await request("live-tv/channels/\(id)/favourite", method: "DELETE") }
    func reminders() async throws -> [Reminder] { try JSONDecoder().decode(Reminders.self, from: try await request("live-tv/reminders")).items }
    func leadMinutes() async throws -> Int { try JSONDecoder().decode(Reminders.self, from: try await request("live-tv/reminders")).leadMinutes }
    func removeReminder(_ id: String) async throws { _ = try await request("live-tv/reminders/\(id)", method: "DELETE") }

    private func request(_ path: String, method: String = "GET", body: Data? = nil) async throws -> Data {
        var request = URLRequest(url: base.appendingPathComponent("api").appendingPathComponent(path))
        request.httpMethod = method; request.httpBody = body
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if !token.isEmpty { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        let (data, response) = try await URLSession.shared.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else {
            throw NSError(domain: "LiveExtrasFixtureTests", code: status, userInfo: [NSLocalizedDescriptionKey: "Synthetic fixture API returned \(status) for \(path)"])
        }
        return data
    }
}
