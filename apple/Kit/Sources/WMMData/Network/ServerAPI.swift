import Foundation
import HTTPTypes
import OpenAPIRuntime
import OpenAPIURLSession
import WMMAPI
import WMMModel

/// One server's API: the generated client with the bearer token on every
/// request, and every failure turned into ``APIError`` or ``SignedOutError``
/// (android/core/data/.../Http.kt). Repositories call it as
/// `try await api.call { try await $0.getHome().ok.body.json }`.
///
/// It is the one way a sign-in goes on a request (``RequestSigner``) and the
/// one place a 401 is read (``revokes(status:signed:operationID:)``): a
/// refused token calls `onSignedOut` before ``SignedOutError`` reaches the
/// caller, whether the request was the generated client's, a plain fetch
/// (``data(_:session:)``) or the event stream's (``tokenRefused()``).
public struct ServerAPI: Sendable {
    public let baseURL: URL
    public let client: Client
    private let session: URLSession
    let signer: RequestSigner
    private let onSignedOut: @Sendable () async -> Void

    /// - Parameters:
    ///   - token: the bearer token to send, read on every request; nil sends none.
    ///   - onSignedOut: called when the server refuses the token (401 on a
    ///     signed-in call), and awaited before the call throws ``SignedOutError``.
    public init(
        baseURL: URL,
        session: URLSession = .shared,
        userAgent: String? = nil,
        token: @escaping @Sendable () -> String? = { nil },
        onSignedOut: @escaping @Sendable () async -> Void = {}
    ) {
        self.baseURL = baseURL
        self.session = session
        let signer = RequestSigner(baseURL: baseURL, token: token, userAgent: userAgent)
        self.signer = signer
        self.onSignedOut = onSignedOut
        client = Client(
            serverURL: baseURL,
            configuration: Configuration(dateTranscoder: LenientISO8601()),
            transport: URLSessionTransport(configuration: .init(session: session)),
            middlewares: [
                HeadersMiddleware(signer: signer),
                ErrorMiddleware(),
                AlreadyCreatedMiddleware(),
            ]
        )
    }

    /// Runs an API call, turning what can go wrong into ``APIError``: the
    /// server's message for a refused request, ``SignedOutError`` for a 401 on
    /// a signed-in call (once `onSignedOut` has run), and a plain sentence for
    /// network trouble.
    public func call<T: Sendable>(_ operation: @Sendable (Client) async throws -> T) async throws -> T {
        do {
            return try await operation(client)
        } catch {
            let translated = Self.translate(error)
            if translated is SignedOutError { await onSignedOut() }
            throw translated
        }
    }

    /// A URL on this server with the path appended (images, subtitles, event stream).
    public func url(_ path: String) -> URL {
        URL(string: baseURL.absoluteString + (path.hasPrefix("/") ? path : "/" + path)) ?? baseURL
    }

    /// A request for `url` with the user agent, and the bearer token only when
    /// `url` is on this server, for what the generated client does not cover
    /// (images, the event stream, subtitle text).
    public func authorizedRequest(_ url: URL) -> URLRequest {
        signer.request(url)
    }

    /// Fetches `url` (on `session`, else this API's) with the bearer token,
    /// failing as ``call`` does: a 401 with the token signs out.
    public func data(_ url: URL, session: URLSession? = nil) async throws -> Data {
        let request = authorizedRequest(url)
        let data: Data, response: URLResponse
        do {
            (data, response) = try await (session ?? self.session).data(for: request)
        } catch {
            throw Self.translate(error)
        }
        if let http = response as? HTTPURLResponse, http.statusCode >= 400 {
            if Self.revokes(status: http.statusCode, signed: RequestSigner.signed(request)) {
                await onSignedOut()
                throw SignedOutError()
            }
            throw Self.error(status: http.statusCode, body: data)
        }
        return data
    }

    /// The server refused this API's token on a request of the caller's own
    /// (the event stream's): signs out as ``call`` would.
    func tokenRefused() async {
        await onSignedOut()
    }

    /// Where 401 means wrong credentials or a code not yet approved, not a lost sign-in.
    static let credentialOperations: Set<String> = ["login", "claimPairing", "getServer", "getAuthStatus", "startPairing"]

    /// Whether a refusal means the sign-in is gone (revoked or run out): a 401
    /// to a request that carried the token, other than one of the
    /// ``credentialOperations``. Every path that reads a 401 asks this.
    static func revokes(status: Int, signed: Bool, operationID: String? = nil) -> Bool {
        status == 401 && signed && !(operationID.map(credentialOperations.contains) ?? false)
    }

    /// True when `url` is on the server at `base` (same origin, under its path).
    public static func owns(_ base: URL, _ url: URL) -> Bool {
        base.scheme == url.scheme && base.host == url.host && base.port == url.port
            && url.path.hasPrefix(base.path.hasSuffix("/") ? base.path : base.path + "/")
    }

    /// Credential scope includes the proxy path, scheme, host and effective port.
    static func sameEndpoint(_ a: URL, _ b: URL) -> Bool {
        func port(_ u: URL) -> Int? { u.port ?? (u.scheme?.lowercased() == "https" ? 443 : 80) }
        func path(_ u: URL) -> String {
            var p = u.path(percentEncoded: true)
            while p.hasSuffix("/") { p.removeLast() }
            return p
        }
        return a.scheme?.lowercased() == b.scheme?.lowercased() && a.host?.lowercased() == b.host?.lowercased()
            && port(a) == port(b) && path(a) == path(b)
    }

    static func translate(_ error: Error) -> Error {
        switch error {
        case is CancellationError, is APIError, is SignedOutError, is ServerTooOldError:
            return error
        case let client as ClientError:
            return translate(client.underlyingError)
        case let url as URLError:
            return url.code == .cancelled ? CancellationError() : network(url)
        case is DecodingError:
            return APIError(status: nil, message: "The server sent a reply this app doesn't understand. Update the app or the server.")
        default:
            return APIError(status: nil, message: "The server sent a reply this app doesn't understand.")
        }
    }

    private static func network(_ error: URLError) -> APIError {
        switch error.code {
        case .appTransportSecurityRequiresSecureConnection:
            APIError(status: nil, message: "This address needs https. Use the server's IP address or .local name on your network, or an https address such as Tailscale Serve gives.")
        case .serverCertificateUntrusted, .serverCertificateHasBadDate, .serverCertificateNotYetValid, .serverCertificateHasUnknownRoot:
            APIError(status: nil, message: "The server's certificate isn't trusted.")
        default:
            APIError(status: nil, message: "Couldn't reach the server. Check the connection and try again.")
        }
    }

    /// The error for a refused request: the problem+json `detail` as a sentence, or a plain one.
    static func error(status: Int, body: Data?) -> APIError {
        let detail = body.flatMap { try? JSONDecoder().decode(Problem.self, from: $0) }?.detail
        if let detail, !detail.trimmingCharacters(in: .whitespaces).isEmpty {
            return APIError(status: status, message: sentence(detail))
        }
        let message = switch status {
        case 401: "Sign in required."
        case 403: "Not allowed."
        case 404: "Not found."
        case 429: "Too many attempts. Wait a while and try again."
        case 500...: "The server had a problem (\(status)). Try again."
        default: "The server refused the request (\(status))."
        }
        return APIError(status: status, message: message)
    }

    /// "invalid name or password" → "Invalid name or password."
    static func sentence(_ text: String) -> String {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let first = t.first else { return t }
        let capitalised = first.uppercased() + t.dropFirst()
        return [".", "!", "?"].contains(where: capitalised.hasSuffix) ? capitalised : capitalised + "."
    }

    private struct Problem: Decodable {
        var detail: String?
    }
}

/// How a sign-in goes on a request, in one place (PLAN-APPLE-REVIEW.md
/// §5.6): the user agent on every request, and the bearer token only on one
/// to the server at ``baseURL`` (``ServerAPI/owns(_:_:)``: the same scheme,
/// host and port, under its path), so a token never reaches another host.
/// The generated client's requests, plain fetches, the event stream, images
/// and the offline routes all go through it.
struct RequestSigner: Sendable {
    let baseURL: URL
    /// Read on every request; nil sends none.
    let token: @Sendable () -> String?
    let userAgent: String?

    init(baseURL: URL, token: @escaping @Sendable () -> String?, userAgent: String?) {
        self.baseURL = baseURL
        self.token = token
        self.userAgent = userAgent
    }

    init(baseURL: URL, token: String, userAgent: String?) {
        self.init(baseURL: baseURL, token: { token }, userAgent: userAgent)
    }

    /// A request for `url` with the user agent, and the token when `url` is on this server.
    func request(_ url: URL) -> URLRequest {
        var request = URLRequest(url: url)
        if let userAgent { request.setValue(userAgent, forHTTPHeaderField: "User-Agent") }
        if let t = token(), ServerAPI.owns(baseURL, url) { request.setValue("Bearer \(t)", forHTTPHeaderField: "Authorization") }
        return request
    }

    /// ``request(_:)`` when it carries the token; nil for an address that isn't
    /// on this server, or with no token, for a caller that needs the sign-in.
    func signedRequest(_ url: URL) -> URLRequest? {
        let request = request(url)
        return Self.signed(request) ? request : nil
    }

    /// A generated call to `path` on `server` (the client's own server URL):
    /// the user agent, and the token unless it is the password sign-in or the
    /// address isn't on this server.
    func sign(_ fields: inout HTTPFields, path: String?, server: URL, operationID: String) {
        if let userAgent { fields[.userAgent] = userAgent }
        if let authorization = authorization(path: path, server: server, operationID: operationID) { fields[.authorization] = authorization }
    }

    /// The Authorization header for a generated call to `path` on `server`, or nil.
    func authorization(path: String?, server: URL, operationID: String) -> String? {
        guard operationID != "login", let t = token(), let path,
              let url = URL(string: server.absoluteString + path), ServerAPI.owns(baseURL, url) else { return nil }
        return "Bearer \(t)"
    }

    static func signed(_ request: URLRequest) -> Bool {
        request.value(forHTTPHeaderField: "Authorization") != nil
    }
}

/// Signs the generated client's requests (``RequestSigner``).
struct HeadersMiddleware: ClientMiddleware {
    let signer: RequestSigner

    func intercept(
        _ request: HTTPRequest, body: HTTPBody?, baseURL: URL, operationID: String,
        next: @Sendable (HTTPRequest, HTTPBody?, URL) async throws -> (HTTPResponse, HTTPBody?)
    ) async throws -> (HTTPResponse, HTTPBody?) {
        var request = request
        signer.sign(&request.headerFields, path: request.path, server: baseURL, operationID: operationID)
        return try await next(request, body, baseURL)
    }
}

/// Turns a refused request into ``APIError`` before the generated code sees it,
/// so the server's message survives; a 401 that ``ServerAPI/revokes(status:signed:operationID:)``
/// is ``SignedOutError``, which ``ServerAPI/call(_:)`` signs out on.
struct ErrorMiddleware: ClientMiddleware {
    func intercept(
        _ request: HTTPRequest, body: HTTPBody?, baseURL: URL, operationID: String,
        next: @Sendable (HTTPRequest, HTTPBody?, URL) async throws -> (HTTPResponse, HTTPBody?)
    ) async throws -> (HTTPResponse, HTTPBody?) {
        let (response, responseBody) = try await next(request, body, baseURL)
        let status = response.status.code
        guard status >= 400 else { return (response, responseBody) }
        if ServerAPI.revokes(status: status, signed: request.headerFields[.authorization] != nil, operationID: operationID) {
            throw SignedOutError()
        }
        let data: Data? = if let responseBody { try? await Data(collecting: responseBody, upTo: 64 * 1024) } else { nil }
        throw ServerAPI.error(status: status, body: data)
    }
}

/// createRequest answers 200 when the movie or seasons were asked for already,
/// a status the API document does not list (only 201), so the generated client
/// would refuse the reply; its body is the same, so it is read as a 201.
struct AlreadyCreatedMiddleware: ClientMiddleware {
    func intercept(
        _ request: HTTPRequest, body: HTTPBody?, baseURL: URL, operationID: String,
        next: @Sendable (HTTPRequest, HTTPBody?, URL) async throws -> (HTTPResponse, HTTPBody?)
    ) async throws -> (HTTPResponse, HTTPBody?) {
        var (response, responseBody) = try await next(request, body, baseURL)
        if operationID == "createRequest", response.status.code == 200 { response.status = .created }
        return (response, responseBody)
    }
}
