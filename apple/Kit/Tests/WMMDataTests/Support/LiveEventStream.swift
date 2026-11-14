import Foundation
import Synchronization

/// GET /api/events as a test drives it: each connection stays open, and
/// the test writes events to it as it goes (``send(_:)``), ends it, or
/// answers the next connection with another status. By host, so tests run
/// in parallel; hand ``session`` to DataServices as the event session.
final class LiveEventStream: Sendable {
    private struct State {
        var open: [LiveEventURLProtocol] = []
        var requests: [URLRequest] = []
        var statuses: [Int] = []
    }

    let session: URLSession
    let host: String
    private let state = Mutex(State())

    /// Answers requests to `host` (the test's stub server's).
    init(host: String) {
        self.host = host
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [LiveEventURLProtocol.self]
        session = URLSession(configuration: config)
        Self.streams.withLock { $0[host] = self }
    }

    deinit { _ = Self.streams.withLock { $0.removeValue(forKey: host) } }

    /// Every connection asked for so far.
    var requests: [URLRequest] { state.withLock { $0.requests } }

    /// Connections open now.
    var openCount: Int { state.withLock { $0.open.count } }

    /// Answers the next connections with these statuses (and no body) before any stays open.
    func answer(_ statuses: Int...) {
        state.withLock { $0.statuses += statuses }
    }

    /// Writes `text` to every open connection.
    func send(_ text: String) {
        let open = state.withLock { $0.open }
        for connection in open { connection.write(Data(text.utf8)) }
    }

    /// One event, as the server writes it.
    func send(event: String, data: String = "{}") {
        send("event: \(event)\ndata: \(data)\n\n")
    }

    /// Ends every open connection, as a server restart does.
    func end() {
        let open = state.withLock { s in
            defer { s.open = [] }
            return s.open
        }
        for connection in open { connection.finish() }
    }

    fileprivate func connect(_ connection: LiveEventURLProtocol) -> Int {
        state.withLock { s in
            s.requests.append(connection.request)
            if !s.statuses.isEmpty { return s.statuses.removeFirst() }
            s.open.append(connection)
            return 200
        }
    }

    fileprivate func disconnect(_ connection: LiveEventURLProtocol) {
        state.withLock { $0.open.removeAll { $0 === connection } }
    }

    fileprivate static let streams = Mutex<[String: LiveEventStream]>([:])
}

/// Hands each request of a ``LiveEventStream``'s session to that stream.
final class LiveEventURLProtocol: URLProtocol, @unchecked Sendable {
    private let lock = Mutex<LiveEventStream?>(nil)

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let url = request.url, let host = url.host, let stream = LiveEventStream.streams.withLock({ $0[host] }) else {
            client?.urlProtocol(self, didFailWithError: URLError(.cannotConnectToHost))
            return
        }
        lock.withLock { $0 = stream }
        let status = stream.connect(self)
        let response = HTTPURLResponse(url: url, statusCode: status, httpVersion: "HTTP/1.1", headerFields: ["Content-Type": status == 200 ? "text/event-stream" : "application/problem+json"])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        if status != 200 { client?.urlProtocolDidFinishLoading(self) }
    }

    override func stopLoading() {
        lock.withLock { $0 }?.disconnect(self)
    }

    func write(_ data: Data) { client?.urlProtocol(self, didLoad: data) }
    func finish() { client?.urlProtocolDidFinishLoading(self) }

}
