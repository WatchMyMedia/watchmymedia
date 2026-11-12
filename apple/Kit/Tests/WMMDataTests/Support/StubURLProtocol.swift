import Foundation
import Synchronization

/// Answers requests from canned responses, by host, so tests run in
/// parallel without a server: each test makes its own host with
/// ``StubServer`` and a session that routes through this protocol.
final class StubURLProtocol: URLProtocol, @unchecked Sendable {
    typealias Handler = @Sendable (URLRequest) throws -> (Int, [String: String], Data)
    typealias AsyncHandler = @Sendable (URLRequest) async throws -> (Int, [String: String], Data)

    private static let handlers = Mutex<[String: Handler]>([:])
    private static let asyncHandlers = Mutex<[String: AsyncHandler]>([:])
    private var pending: Task<Void, Never>?

    static func register(host: String, _ handler: @escaping Handler) {
        handlers.withLock { $0[host] = handler }
    }

    static func unregister(host: String) {
        _ = handlers.withLock { $0.removeValue(forKey: host) }
        _ = asyncHandlers.withLock { $0.removeValue(forKey: host) }
    }

    static func registerAsync(host: String, _ handler: @escaping AsyncHandler) {
        asyncHandlers.withLock { $0[host] = handler }
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        if let host = request.url?.host, let handler = Self.asyncHandlers.withLock({ $0[host] }) {
            pending = Task.detached { @Sendable [self] in
                do {
                    var req = request
                    if req.httpBody == nil, let stream = req.httpBodyStream { req.httpBody = BodyReadingURLProtocol.readAll(stream) }
                    let (status, headers, body) = try await handler(req)
                    guard !Task.isCancelled, let url = request.url else { return }
                    let response = HTTPURLResponse(url: url, statusCode: status, httpVersion: "HTTP/1.1", headerFields: headers)!
                    client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
                    client?.urlProtocol(self, didLoad: body)
                    client?.urlProtocolDidFinishLoading(self)
                } catch { client?.urlProtocol(self, didFailWithError: error) }
            }
            return
        }
        guard let url = request.url, let host = url.host,
              let handler = Self.handlers.withLock({ $0[host] })
        else {
            client?.urlProtocol(self, didFailWithError: URLError(.cannotConnectToHost))
            return
        }
        do {
            var req = request
            if req.httpBody == nil, let stream = req.httpBodyStream {
                req.httpBody = BodyReadingURLProtocol.readAll(stream)
            }
            let (status, headers, body) = try handler(req)
            let response = HTTPURLResponse(url: url, statusCode: status, httpVersion: "HTTP/1.1", headerFields: headers)!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: body)
            client?.urlProtocolDidFinishLoading(self)
        } catch {
            client?.urlProtocol(self, didFailWithError: error)
        }
    }

    override func stopLoading() { pending?.cancel() }
}

/// A fake server at a host of its own, answering through ``StubURLProtocol``.
final class StubServer: Sendable {
    let host = "stub-\(UUID().uuidString.lowercased()).local"
    let baseURL: URL
    let session: URLSession
    private let requests = Mutex<[URLRequest]>([])

    init(port: Int = 27587) {
        baseURL = URL(string: "http://\(host):\(port)")!
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubURLProtocol.self]
        session = URLSession(configuration: config)
    }

    deinit { StubURLProtocol.unregister(host: host) }

    /// Every request seen so far.
    var seen: [URLRequest] { requests.withLock { $0 } }

    /// Routes requests to `handler`, which gets the method and path.
    func respond(_ handler: @escaping @Sendable (_ method: String, _ path: String, _ request: URLRequest) -> (Int, Any?)) {
        StubURLProtocol.register(host: host) { [weak self] request in
            self?.requests.withLock { $0.append(request) }
            let (status, body) = handler(request.httpMethod ?? "GET", request.url?.path ?? "", request)
            let data: Data
            if let body = body as? Data {
                data = body
            } else if let body {
                data = try JSONSerialization.data(withJSONObject: body)
            } else {
                data = Data()
            }
            let type = status >= 400 ? "application/problem+json" : "application/json"
            return (status, ["Content-Type": type], data)
        }
    }

    /// A controllable suspended reply without blocking the shared URLSession
    /// callback queue or other tests' network traffic.
    func respondAsync(_ handler: @escaping @Sendable (URLRequest) async throws -> (Int, Data)) {
        StubURLProtocol.registerAsync(host: host) { [weak self] request in
            self?.requests.withLock { $0.append(request) }
            let (status, body) = try await handler(request)
            return (status, ["Content-Type": "application/json"], body)
        }
    }
}
