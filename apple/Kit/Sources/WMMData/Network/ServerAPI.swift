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
///
/// An API made for a sign-in kept on the device also carries a
/// ``SignInCheck``: its sign-in goes out freely only while its server is the
/// one in use, and otherwise only after the server has said, asked with no
/// sign-in, that it is the one remembered.
public struct ServerAPI: Sendable {
    public let baseURL: URL
    public let client: Client
    private let session: URLSession
    let signer: RequestSigner
    private let onSignedOut: @Sendable () async -> Void
    /// What discovery last heard of Local Network access, for why a server on this network can't be reached.
    private let localNetwork: LocalNetworkAccess?

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
        self.init(baseURL: baseURL, session: session, userAgent: userAgent, token: token, onSignedOut: onSignedOut, localNetwork: nil)
    }

    init(
        baseURL: URL,
        session: URLSession,
        userAgent: String?,
        token: @escaping @Sendable () -> String?,
        onSignedOut: @escaping @Sendable () async -> Void = {},
        localNetwork: LocalNetworkAccess?,
        check: SignInCheck? = nil
    ) {
        self.localNetwork = localNetwork
        self.baseURL = baseURL
        self.session = session
        let signer = RequestSigner(baseURL: baseURL, token: token, userAgent: userAgent, check: check)
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
            let translated = translate(error)
            if translated is SignedOutError { await onSignedOut() }
            throw translated
        }
    }

    /// ``translate(_:localNetworkOff:)`` for this server: a network failure
    /// on this network says so when Local Network access is off.
    func translate(_ error: Error) -> Error {
        Self.translate(error, localNetworkOff: localNetwork?.denied == true && isLANHost(baseURL.host() ?? ""))
    }

    /// A URL on this server with the path appended (images, subtitles, event stream).
    public func url(_ path: String) -> URL {
        URL(string: baseURL.absoluteString + (path.hasPrefix("/") ? path : "/" + path)) ?? baseURL
    }

    /// A request for `url` with the user agent, and the bearer token only when
    /// `url` is on this server, for what the generated client does not cover
    /// (images, the event stream, subtitle text). It can't wait for a
    /// server to say which one it is, so for a server that isn't the one in
    /// use it carries the token only while that server's answer stands.
    public func authorizedRequest(_ url: URL) -> URLRequest {
        signer.request(url)
    }

    /// Fetches `url` (on `session`, else this API's) with the bearer token,
    /// failing as ``call`` does: a 401 with the token signs out.
    public func data(_ url: URL, session: URLSession? = nil) async throws -> Data {
        let request = try await signer.askedRequest(url)
        let data: Data, response: URLResponse
        do {
            (data, response) = try await (session ?? self.session).data(for: request)
        } catch {
            throw translate(error)
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

    /// POST /api/auth/media-token: a token for media on this server's addresses only.
    public func mediaToken() async throws -> MediaToken {
        try await call { try await $0.createMediaToken().ok.body.json }.model(server: baseURL)
    }

    /// The server refused this API's token on a request of the caller's own
    /// (the event stream's): signs out as ``call`` would.
    func tokenRefused() async {
        await onSignedOut()
    }

    /// Where 401 means wrong credentials or a code not yet approved, not a
    /// lost sign-in. changePassword's 401 is a wrong current password.
    static let credentialOperations: Set<String> = ["login", "claimPairing", "getServer", "getAuthStatus", "startPairing", "changePassword"]

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

    /// - Parameter localNetworkOff: the server is on this network and the
    ///   person said no to Local Network, so a failure to connect says so.
    static func translate(_ error: Error, localNetworkOff: Bool = false) -> Error {
        switch error {
        case is CancellationError, is APIError, is SignedOutError, is ServerTooOldError:
            return error
        case let client as ClientError:
            return translate(client.underlyingError, localNetworkOff: localNetworkOff)
        case let url as URLError:
            return url.code == .cancelled ? CancellationError() : network(url, localNetworkOff: localNetworkOff)
        case is TimedOut:
            return network(URLError(.timedOut), localNetworkOff: localNetworkOff)
        case is DecodingError:
            return APIError(status: nil, message: "The server sent a reply this app doesn't understand. Update the app or the server.")
        default:
            return APIError(status: nil, message: "The server sent a reply this app doesn't understand.")
        }
    }

    private static func network(_ error: URLError, localNetworkOff: Bool = false) -> APIError {
        switch error.code {
        case .appTransportSecurityRequiresSecureConnection:
            APIError(status: nil, message: "This address needs https. Use the server's IP address or .local name on your network, or an https address such as Tailscale Serve gives.")
        case .serverCertificateUntrusted, .serverCertificateHasBadDate, .serverCertificateNotYetValid, .serverCertificateHasUnknownRoot:
            APIError(status: nil, message: "The server's certificate isn't trusted.")
        default:
            APIError(status: nil, message: localNetworkOff ? localNetworkOffMessage : "Couldn't reach the server. Check the connection and try again.")
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

/// How a sign-in goes on a request, in one place:
/// the user agent on every request, and the bearer token only on one
/// to the server at ``baseURL`` (``ServerAPI/owns(_:_:)``: the same scheme,
/// host and port, under its path), so a token never reaches another host.
/// The generated client's requests, plain fetches, the event stream, images
/// and the offline routes all go through it.
///
/// It is also where a kept sign-in waits for its server (``SignInCheck``).
/// The address kept for a server is only where it was last seen: on another
/// network it can be someone else's machine. So the token of a server that
/// is not, or is no longer, the one in use goes out only once that address
/// has said, asked with no sign-in, that it is the server remembered. A
/// request that can wait asks first (``asked(for:)``, which the generated
/// client's requests and plain fetches do); one that can't carries the
/// token only while an answer stands. Every sign-in the app keeps is put on
/// requests here, so no call has to remember to ask.
struct RequestSigner: Sendable {
    let baseURL: URL
    /// Read on every request; nil sends none.
    let token: @Sendable () -> String?
    let userAgent: String?
    /// When the token may go out; nil for a signer that keeps no sign-in of
    /// the device's (signing in, a test), whose token goes freely.
    let check: SignInCheck?

    init(baseURL: URL, token: @escaping @Sendable () -> String?, userAgent: String?, check: SignInCheck? = nil) {
        self.baseURL = baseURL
        self.token = token
        self.userAgent = userAgent
        self.check = check
    }

    init(baseURL: URL, token: String, userAgent: String?, check: SignInCheck? = nil) {
        self.init(baseURL: baseURL, token: { token }, userAgent: userAgent, check: check)
    }

    /// The token may go on a request made now: no check to pass, the server
    /// is the one in use, its answer stands, or it was `asked` for this request.
    private func free(_ asked: Bool) -> Bool {
        asked || (check?.stands() ?? true)
    }

    /// A request for `url` with the user agent, and the token when `url` is
    /// on this server and the token may go there now (`asked`: the server
    /// was asked which one it is for this request, ``asked(for:)``).
    func request(_ url: URL, asked: Bool = false) -> URLRequest {
        var request = URLRequest(url: url)
        if let userAgent { request.setValue(userAgent, forHTTPHeaderField: "User-Agent") }
        if free(asked), let t = token(), ServerAPI.owns(baseURL, url) { request.setValue("Bearer \(t)", forHTTPHeaderField: "Authorization") }
        return request
    }

    /// ``request(_:asked:)`` when it carries the token; nil for an address
    /// that isn't on this server, with no token, or for a server that would
    /// have to be asked first, for a caller that needs the sign-in.
    func signedRequest(_ url: URL, asked: Bool = false) -> URLRequest? {
        let request = request(url, asked: asked)
        return Self.signed(request) ? request : nil
    }

    /// ``request(_:asked:)`` for a caller that can wait: a server that
    /// isn't the one in use is asked which one it is first. Throws when it
    /// doesn't answer as itself, and nothing is sent.
    func askedRequest(_ url: URL) async throws -> URLRequest {
        request(url, asked: try await asked(for: url))
    }

    /// Before a request for `url` that would carry the token: asks the
    /// server which one it is, with no sign-in, unless the token may go
    /// freely. True when it asked and the server answered as itself; throws
    /// when it didn't, so the request isn't sent at all.
    func asked(for url: URL) async throws -> Bool {
        guard let check, token() != nil, ServerAPI.owns(baseURL, url), !check.stands() else { return false }
        try await check.ask()
        return true
    }

    /// ``asked(for:)`` for a generated call to `path` on `server`.
    func asked(path: String?, server: URL, operationID: String) async throws -> Bool {
        guard operationID != "login", let path, let url = URL(string: server.absoluteString + path) else { return false }
        return try await asked(for: url)
    }

    /// A generated call to `path` on `server` (the client's own server URL):
    /// the user agent, and the token unless it is the password sign-in, the
    /// address isn't on this server, or the token may not go there now.
    func sign(_ fields: inout HTTPFields, path: String?, server: URL, operationID: String, asked: Bool = false) {
        if let userAgent { fields[.userAgent] = userAgent }
        if let authorization = authorization(path: path, server: server, operationID: operationID, asked: asked) { fields[.authorization] = authorization }
    }

    /// The Authorization header for a generated call to `path` on `server`, or nil.
    func authorization(path: String?, server: URL, operationID: String, asked: Bool = false) -> String? {
        guard operationID != "login", free(asked), let t = token(), let path,
              let url = URL(string: server.absoluteString + path), ServerAPI.owns(baseURL, url) else { return nil }
        return "Bearer \(t)"
    }

    static func signed(_ request: URLRequest) -> Bool {
        request.value(forHTTPHeaderField: "Authorization") != nil
    }
}

/// When a kept sign-in may go out to its server (``RequestSigner``).
struct SignInCheck: Sendable {
    /// The server is the one in use, or it has said it is the one
    /// remembered and that answer stands: the sign-in may go now. Read on
    /// any thread, for every request.
    let stands: @Sendable () -> Bool
    /// Asks the server which one it is, at the address kept for it and
    /// with no sign-in. Throws when nothing answers there, or another
    /// server does.
    let ask: @Sendable () async throws -> Void
}

/// Signs the generated client's requests (``RequestSigner``), asking a
/// server that isn't the one in use which one it is first.
struct HeadersMiddleware: ClientMiddleware {
    let signer: RequestSigner

    func intercept(
        _ request: HTTPRequest, body: HTTPBody?, baseURL: URL, operationID: String,
        next: @Sendable (HTTPRequest, HTTPBody?, URL) async throws -> (HTTPResponse, HTTPBody?)
    ) async throws -> (HTTPResponse, HTTPBody?) {
        var request = request
        let asked = try await signer.asked(path: request.path, server: baseURL, operationID: operationID)
        signer.sign(&request.headerFields, path: request.path, server: baseURL, operationID: operationID, asked: asked)
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
