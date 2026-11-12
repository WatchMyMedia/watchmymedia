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
public struct ServerAPI: Sendable {
    public let baseURL: URL
    public let client: Client
    private let session: URLSession
    private let token: @Sendable () -> String?
    private let onSignedOut: @Sendable () -> Void

    /// - Parameters:
    ///   - token: the bearer token to send, read on every request; nil sends none.
    ///   - onSignedOut: called when the server refuses the token (401 on a signed-in call).
    public init(
        baseURL: URL,
        session: URLSession = .shared,
        userAgent: String? = nil,
        token: @escaping @Sendable () -> String? = { nil },
        onSignedOut: @escaping @Sendable () -> Void = {}
    ) {
        self.baseURL = baseURL
        self.session = session
        self.token = token
        self.onSignedOut = onSignedOut
        client = Client(
            serverURL: baseURL,
            configuration: Configuration(dateTranscoder: LenientISO8601()),
            transport: URLSessionTransport(configuration: .init(session: session)),
            middlewares: [
                HeadersMiddleware(token: token, userAgent: userAgent),
                ErrorMiddleware(onSignedOut: onSignedOut),
                AlreadyCreatedMiddleware(),
            ]
        )
    }

    /// Runs an API call, turning what can go wrong into ``APIError``: the
    /// server's message for a refused request, ``SignedOutError`` for a 401 on
    /// a signed-in call, and a plain sentence for network trouble.
    public func call<T: Sendable>(_ operation: @Sendable (Client) async throws -> T) async throws -> T {
        do {
            return try await operation(client)
        } catch {
            throw Self.translate(error)
        }
    }

    /// A URL on this server with the path appended (images, subtitles, event stream).
    public func url(_ path: String) -> URL {
        URL(string: baseURL.absoluteString + (path.hasPrefix("/") ? path : "/" + path)) ?? baseURL
    }

    /// A request for `url` with the bearer token, for what the generated client
    /// does not cover (images, the event stream, subtitle text).
    public func authorizedRequest(_ url: URL) -> URLRequest {
        var request = URLRequest(url: url)
        if let t = token(), Self.owns(baseURL, url) { request.setValue("Bearer \(t)", forHTTPHeaderField: "Authorization") }
        return request
    }

    /// Fetches `url` with the bearer token, failing as ``call`` does: a 401
    /// with the token signs out.
    public func data(_ url: URL) async throws -> Data {
        do {
            let request = authorizedRequest(url)
            let (data, response) = try await session.data(for: request)
            if let http = response as? HTTPURLResponse, http.statusCode >= 400 {
                if http.statusCode == 401, request.value(forHTTPHeaderField: "Authorization") != nil {
                    onSignedOut()
                    throw SignedOutError()
                }
                throw Self.error(status: http.statusCode, body: data)
            }
            return data
        } catch {
            throw Self.translate(error)
        }
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

/// Adds the bearer token and the user agent.
struct HeadersMiddleware: ClientMiddleware {
    let token: @Sendable () -> String?
    let userAgent: String?

    func intercept(
        _ request: HTTPRequest, body: HTTPBody?, baseURL: URL, operationID: String,
        next: @Sendable (HTTPRequest, HTTPBody?, URL) async throws -> (HTTPResponse, HTTPBody?)
    ) async throws -> (HTTPResponse, HTTPBody?) {
        var request = request
        if let t = token(), operationID != "login" { request.headerFields[.authorization] = "Bearer \(t)" }
        if let userAgent { request.headerFields[.userAgent] = userAgent }
        return try await next(request, body, baseURL)
    }
}

/// Turns a refused request into ``APIError`` before the generated code sees it,
/// so the server's message survives; a 401 on a signed-in call is ``SignedOutError``.
struct ErrorMiddleware: ClientMiddleware {
    /// Where 401 means wrong credentials or a code not yet approved, not a lost sign-in.
    static let credentialOperations: Set<String> = ["login", "claimPairing", "getServer", "getAuthStatus", "startPairing"]

    let onSignedOut: @Sendable () -> Void

    func intercept(
        _ request: HTTPRequest, body: HTTPBody?, baseURL: URL, operationID: String,
        next: @Sendable (HTTPRequest, HTTPBody?, URL) async throws -> (HTTPResponse, HTTPBody?)
    ) async throws -> (HTTPResponse, HTTPBody?) {
        let (response, responseBody) = try await next(request, body, baseURL)
        let status = response.status.code
        guard status >= 400 else { return (response, responseBody) }
        if status == 401, !Self.credentialOperations.contains(operationID) {
            onSignedOut()
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
