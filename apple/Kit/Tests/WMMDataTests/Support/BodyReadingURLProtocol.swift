import Foundation

/// Reads a streamed request body to its end, then hands the request on to
/// ``StubURLProtocol``. The generated client streams request bodies, and on
/// iOS the bytes may not be there yet when ``StubURLProtocol`` looks, so a
/// test would see an empty body.
final class BodyReadingURLProtocol: URLProtocol, @unchecked Sendable {
    private static let stub: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubURLProtocol.self]
        return URLSession(configuration: config)
    }()

    private var forwarding: URLSessionDataTask?

    override class func canInit(with request: URLRequest) -> Bool { request.httpBodyStream != nil }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        var forwarded = request
        if let stream = request.httpBodyStream {
            forwarded.httpBodyStream = nil
            forwarded.httpBody = Self.readAll(stream)
        }
        let task = Self.stub.dataTask(with: forwarded) { [weak self] data, response, error in
            guard let self else { return }
            if let error {
                client?.urlProtocol(self, didFailWithError: error)
                return
            }
            if let response { client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed) }
            if let data { client?.urlProtocol(self, didLoad: data) }
            client?.urlProtocolDidFinishLoading(self)
        }
        forwarding = task
        task.resume()
    }

    override func stopLoading() {
        forwarding?.cancel()
    }

    /// Blocks until the writer closes the stream. `hasBytesAvailable` can be
    /// false while the async producer prepares its next chunk; that is not EOF.
    static func readAll(_ stream: InputStream) -> Data {
        stream.open()
        defer { stream.close() }
        var data = Data()
        var buffer = [UInt8](repeating: 0, count: 4096)
        while true {
            let n = stream.read(&buffer, maxLength: buffer.count)
            if n <= 0 { break }
            data.append(buffer, count: n)
        }
        return data
    }
}
