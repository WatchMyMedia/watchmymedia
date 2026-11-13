import Foundation
import Synchronization
import Testing
@testable import WMMData
import WMMModel

/// A canned answer: a status and a body.
struct Reply: Sendable {
    var status: Int
    var body: Data

    static func json(_ text: String, status: Int = 200) -> Reply { Reply(status: status, body: Data(text.utf8)) }

    /// A problem+json refusal, as the server sends.
    static func problem(_ status: Int, _ detail: String) -> Reply {
        json(#"{"title":"Error","status":\#(status),"detail":"\#(detail)"}"#, status: status)
    }

    static let noContent = Reply(status: 204, body: Data())
}

/// A ``StubServer`` that answers by route ("GET /api/home") rather than in
/// order, since DataServices makes calls of its own in the background (it
/// checks the session and loads preferences after each sign-in), as
/// android/core/data/src/test/.../TestServer.kt does. Unrouted requests get
/// 404; /api/auth/me and /api/me/preferences have defaults.
final class Router: Sendable {
    let server = StubServer()
    /// The stub's session, reading streamed request bodies to the end first.
    let session: URLSession
    private let queued = Mutex<[String: [Reply]]>([:])
    private let fixed = Mutex<[String: Reply]>([:])

    /// A session that answers from the stub servers, with whole request bodies.
    static func stubSession(hanging: Bool = false) -> URLSession {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = (hanging ? [HangingURLProtocol.self] : []) + [BodyReadingURLProtocol.self, StubURLProtocol.self]
        return URLSession(configuration: config)
    }

    init() {
        session = Self.stubSession()
        server.respond { [weak self] method, path, _ in
            let reply = self?.reply(for: "\(method) \(path)") ?? .problem(404, "gone")
            return (reply.status, reply.body)
        }
    }

    var baseURL: URL { server.baseURL }

    /// Answers the next requests on `route` with `replies`, in order, then falls back to ``always(_:_:)``.
    func on(_ route: String, _ replies: Reply...) {
        queued.withLock { $0[route, default: []].append(contentsOf: replies) }
    }

    func always(_ route: String, _ reply: Reply) {
        fixed.withLock { $0[route] = reply }
    }

    func requests(_ route: String) -> [URLRequest] {
        server.seen.filter { "\($0.httpMethod ?? "GET") \($0.url?.path ?? "")" == route }
    }

    func last(_ route: String) throws -> URLRequest {
        try #require(requests(route).last, "no request to \(route)")
    }

    private func reply(for route: String) -> Reply {
        let next: Reply? = queued.withLock { q in
            guard var list = q[route], !list.isEmpty else { return nil }
            let first = list.removeFirst()
            q[route] = list
            return first
        }
        if let next { return next }
        if let fixed = fixed.withLock({ $0[route] }) { return fixed }
        switch route {
        case "GET /api/auth/me": return .json(#"{"id":2,"name":"viewer","isAdmin":false}"#)
        case "GET /api/me/preferences": return .json(Fixtures.preferences)
        default: return .problem(404, "no route for \(route) in this test")
        }
    }
}

/// Keeps a stub server's reply waiting until the test releases it.
actor ReplyGate {
    private var open = false
    private var waiting: [CheckedContinuation<Void, Never>] = []

    func wait() async {
        if open { return }
        await withCheckedContinuation { waiting.append($0) }
    }

    func release() {
        open = true
        let all = waiting
        waiting.removeAll()
        for waiter in all { waiter.resume() }
    }
}

/// A clock tests move by hand.
final class TestClock: Sendable {
    private let value = Mutex(Date(timeIntervalSince1970: 1_800_000_000))

    var now: Date {
        get { value.withLock { $0 } }
        set { value.withLock { $0 = newValue } }
    }
}

/// Discovery the test drives.
@MainActor
final class FakeDiscovery: ServerDiscovery {
    private(set) var running = false
    private var found: (@MainActor ([DiscoveredServer]) -> Void)?
    private var denied: (@MainActor (Bool) -> Void)?

    func start(found: @escaping @MainActor ([DiscoveredServer]) -> Void, denied: @escaping @MainActor (Bool) -> Void) {
        running = true
        self.found = found
        self.denied = denied
    }

    func stop() {
        running = false
        found = nil
        denied = nil
    }

    func announce(_ servers: [DiscoveredServer]) { found?(servers) }
    func deny() { denied?(true) }
}

/// A DataServices on in-memory storage and a stub server, with short timings,
/// as android/core/data/src/test/.../TestServer.kt's Harness.
@MainActor
final class Harness {
    let router = Router()
    let storage = MemoryStorage(deviceID: "dev-1")
    let clock = TestClock()
    let discovery = FakeDiscovery()
    let probeSession: URLSession
    let client = ClientInfo(deviceID: "unused", name: "Sam’s iPhone", product: "WatchMyMedia for iOS", platform: "ios", version: "1.0 (12)", model: "iPhone")
    var tuning: DataTuning
    /// The event stream's session; the main one when nil.
    var eventSession: URLSession?
    /// The device's network path, when a test drives it.
    var network: FakeNetworkPath?
    /// The API's HTTP cache, when a test looks in it.
    var apiCache: URLCache?
    private(set) var services: DataServices!

    init(probeTimeout: Duration = .seconds(2)) {
        probeSession = Router.stubSession(hanging: true)
        let clock = clock
        // The longest wait (after a 404, a server without events) outlasts a test.
        tuning = DataTuning(probeTimeout: probeTimeout, minimumPoll: .milliseconds(5), eventBackoff: { $0 >= 6 ? .seconds(60) : .milliseconds(20) },
                            eventLinger: .zero, now: { clock.now })
        services = make()
    }

    var server: StubServer { router.server }

    /// Another DataServices on the same storage, as after a restart.
    @discardableResult
    func reopen() -> DataServices {
        services = make()
        return services
    }

    private func make() -> DataServices {
        DataServices(
            storage: storage, client: client, userAgent: "WatchMyMedia/1.0 (ios; iOS 18.0)", session: router.session,
            probeSession: probeSession, eventSession: eventSession, discovery: discovery, apiCache: apiCache, network: network, tuning: tuning
        )
    }

    /// Moves the event stream onto one the test writes to as it goes, and
    /// opens the services again on it; keep the stream for the test's length.
    func liveEvents() -> LiveEventStream {
        let stream = LiveEventStream(host: server.host)
        eventSession = stream.session
        reopen()
        return stream
    }

    var testServer: Server { Server(id: "srv-test", name: "Test server", baseURL: router.baseURL, version: "1.0.0", apiLevel: 1) }

    func select() async throws {
        try await services.servers.select(testServer)
    }

    /// Selects the test server and signs in as viewer.
    @discardableResult
    func signIn() async throws -> User {
        try await select()
        router.on("POST /api/auth/login", .json(Fixtures.signIn))
        return try await services.auth.signIn(name: "viewer", password: "fixture-viewer-1")
    }

    /// The JSON body of a request.
    func body(_ request: URLRequest) throws -> [String: Any] {
        let data = try #require(request.httpBody)
        return try #require(try JSONSerialization.jsonObject(with: data) as? [String: Any])
    }
}

/// The query items of a request, by name.
func query(_ request: URLRequest) -> [String: String] {
    let items = request.url.flatMap { URLComponents(url: $0, resolvingAgainstBaseURL: false)?.queryItems } ?? []
    return Dictionary(items.map { ($0.name, $0.value ?? "") }, uniquingKeysWith: { $1 })
}

/// Waits until `condition` holds, for up to `timeout`; returns whether it did.
@MainActor
func eventually(_ timeout: Duration = .seconds(5), _ condition: () -> Bool) async -> Bool {
    let deadline = ContinuousClock.now + timeout
    while ContinuousClock.now < deadline {
        if condition() { return true }
        try? await Task.sleep(for: .milliseconds(10))
    }
    return condition()
}

/// A network path the test turns off and on.
final class FakeNetworkPath: NetworkPath, @unchecked Sendable {
    private var continuation: AsyncStream<Bool>.Continuation?

    func updates() -> AsyncStream<Bool> {
        AsyncStream(bufferingPolicy: .bufferingNewest(1)) { continuation in
            self.continuation = continuation
            continuation.yield(true)
        }
    }

    func set(_ satisfied: Bool) { continuation?.yield(satisfied) }
}
