import Combine
import Foundation
import XCTest
@testable import WatchMyMedia

final class StatusProtocol: URLProtocol {
    static var reply = #"{"version":"fixture","setupRequired":false,"setupIncomplete":true,"sessions":[],"plays":[]}"#
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(Self.reply.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

@MainActor
final class ServerControllerTests: XCTestCase {
    private var temporary: URL!
    private var defaults: UserDefaults!
    private var suite: String!
    private var session: URLSession!

    override func setUp() async throws {
        temporary = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: temporary, withIntermediateDirectories: true)
        suite = "media.watchmy.tests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suite)!
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [StatusProtocol.self]
        session = URLSession(configuration: configuration)
        StatusProtocol.reply = #"{"version":"fixture","setupRequired":false,"setupIncomplete":true,"sessions":[],"plays":[]}"#
        try writeHelper("exec /bin/cat >/dev/null")
    }

    override func tearDown() async throws {
        session.invalidateAndCancel()
        defaults.removePersistentDomain(forName: suite)
        try FileManager.default.removeItem(at: temporary)
    }

    private func writeHelper(_ body: String) throws {
        let helper = temporary.appendingPathComponent("watchmymedia")
        try Data(("#!/bin/sh\n" + body + "\n").utf8).write(to: helper)
        try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: helper.path)
    }

    private func makeController(port: Int, open: @escaping (URL) -> Bool) -> ServerController {
        ServerController(port: port, dataDir: temporary.appendingPathComponent("data"),
            logURL: temporary.appendingPathComponent("logs/server.log"), helpers: temporary,
            defaults: defaults, session: session, openURL: open)
    }

    private func stop(_ controller: ServerController) async {
        await withCheckedContinuation { continuation in
            controller.shutdown { continuation.resume() }
        }
    }

    func testFirstIncompleteStatusOpensSetupOnceAcrossRelaunches() async throws {
        let port = try ServerStartup.unusedPort()
        let opened = expectation(description: "setup opened")
        var destinations: [URL] = []
        let first = makeController(port: port) { url in
            destinations.append(url)
            opened.fulfill()
            return true
        }
        first.start()
        await fulfillment(of: [opened], timeout: 5)
        XCTAssertTrue(first.setupRequired)
        XCTAssertEqual(destinations.map(\.absoluteString), ["http://localhost:\(port)/setup"])
        XCTAssertTrue(defaults.bool(forKey: "openedInitialSetup"))
        await stop(first)

        let running = expectation(description: "restarted")
        let second = makeController(port: port) { _ in
            XCTFail("relaunch must not open setup again")
            return true
        }
        let observer = second.$state.sink { state in
            if case .running = state { running.fulfill() }
        }
        second.start()
        await fulfillment(of: [running], timeout: 5)
        XCTAssertTrue(second.setupRequired)
        observer.cancel()
        await stop(second)
    }

    func testOccupiedPortStopsWithoutOpeningAnotherServiceAndCanRecover() async throws {
        let held = try BoundPort()
        defer { held.close() }
        StatusProtocol.reply = #"{"version":"fixture","setupRequired":false,"setupIncomplete":false,"sessions":[]}"#
        var destinations: [URL] = []
        let controller = makeController(port: held.port) { url in
            destinations.append(url)
            return true
        }
        controller.start()
        XCTAssertEqual(controller.state, .portInUse(held.port))
        XCTAssertFalse(controller.canOpenInBrowser)
        // Menu shortcuts and future actions share the same guard, rather
        // than depending only on a disabled menu item's appearance.
        controller.openInBrowser()
        controller.openInBrowser(path: "setup")
        controller.openInBrowser(path: "activity")
        XCTAssertTrue(destinations.isEmpty)
        let running = expectation(description: "alternative port started")
        let observer = controller.$state.sink { state in
            if case .running = state { running.fulfill() }
        }
        controller.useAnotherPort()
        await fulfillment(of: [running], timeout: 5)
        XCTAssertNotEqual(controller.port, held.port)
        XCTAssertEqual(defaults.integer(forKey: "port"), controller.port)
        XCTAssertEqual(controller.url.port, controller.port)
        XCTAssertTrue(controller.canOpenInBrowser)
        controller.openInBrowser()
        controller.openInBrowser(path: "setup")
        XCTAssertEqual(destinations.map(\.absoluteString), [
            "http://localhost:\(controller.port)/", "http://localhost:\(controller.port)/setup",
        ])
        observer.cancel()
        await stop(controller)
    }

    func testBindRaceStopsRetriesAndReadsOnlyThisLaunchLog() async throws {
        let port = try ServerStartup.unusedPort()
        try writeHelper("echo 'error: listen tcp :\(port): bind: address already in use' >&2\nexit 1")
        let failed = expectation(description: "bind failure")
        let controller = makeController(port: port) { _ in false }
        let observer = controller.$state.sink { state in
            if case .portInUse = state { failed.fulfill() }
        }
        controller.start()
        await fulfillment(of: [failed], timeout: 5)
        XCTAssertEqual(controller.state, .portInUse(port))
        observer.cancel()
        await stop(controller)

        // The old collision remains in the log, but it must not suppress
        // recovery from a different failure on the next launch.
        try writeHelper("echo 'error: fixture failure' >&2\nexit 1")
        let otherFailure = expectation(description: "unrelated failure")
        let second = makeController(port: port) { _ in false }
        let secondObserver = second.$state.sink { state in
            if case .failed = state { otherFailure.fulfill() }
        }
        second.start()
        await fulfillment(of: [otherFailure], timeout: 5)
        if case .failed = second.state {} else { XCTFail("old log must not report a collision") }
        secondObserver.cancel()
        await stop(second)
    }

    func testRealServerFirstRunAndPortRecovery() async throws {
        guard let binary = ProcessInfo.processInfo.environment["WATCHMYMEDIA_MAC_TEST_SERVER"] else {
            throw XCTSkip("set WATCHMYMEDIA_MAC_TEST_SERVER to a built server for the loopback smoke")
        }
        // The helper and data belong only to this test. No app state,
        // Bonjour discovery, browser launch or real media is involved.
        let escaped = "'" + binary.replacingOccurrences(of: "'", with: "'\\''") + "'"
        try writeHelper("exec /usr/bin/env -i PATH=\"$PATH\" WATCHMYMEDIA_DATA_DIR=\"$WATCHMYMEDIA_DATA_DIR\" WATCHMYMEDIA_PORT=\"$WATCHMYMEDIA_PORT\" WATCHMYMEDIA_EXIT_ON_STDIN_EOF=1 WATCHMYMEDIA_HOST=127.0.0.1 WATCHMYMEDIA_DISCOVERY=false WATCHMYMEDIA_HWACCEL=none \(escaped) \"$@\"")
        let configuration = URLSessionConfiguration.ephemeral
        let realSession = URLSession(configuration: configuration)
        defer { realSession.invalidateAndCancel() }
        let held = try BoundPort()
        defer { held.close() }
        let opened = expectation(description: "real server opens setup")
        var destination: URL?
        let controller = ServerController(port: held.port,
            dataDir: temporary.appendingPathComponent("real-data"),
            logURL: temporary.appendingPathComponent("real-logs/server.log"), helpers: temporary,
            defaults: defaults, session: realSession, openURL: { url in
                destination = url
                opened.fulfill()
                return true
            })
        controller.start()
        XCTAssertEqual(controller.state, .portInUse(held.port))
        controller.openInBrowser()
        controller.openInBrowser(path: "setup")
        XCTAssertNil(destination)
        controller.useAnotherPort()
        await fulfillment(of: [opened], timeout: 15)
        XCTAssertTrue(controller.setupRequired)
        XCTAssertNotEqual(controller.port, held.port)
        XCTAssertEqual(destination?.port, controller.port)
        XCTAssertEqual(destination?.path, "/setup")
        XCTAssertTrue(FileManager.default.fileExists(atPath: temporary.appendingPathComponent("real-data/watchmymedia.db").path))
        await stop(controller)
        // Actual HTTP requests may leave TIME_WAIT connections. The bind
        // probe must agree with Go's reusable listener on a clean restart.
        XCTAssertTrue(ServerStartup.portIsAvailable(controller.port))
    }
}
