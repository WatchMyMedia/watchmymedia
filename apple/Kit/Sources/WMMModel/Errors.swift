import Foundation

/// A request that failed. `message` is the server's detail when it sent one,
/// ready to show as it is.
public struct APIError: Error, LocalizedError, Equatable, Sendable {
    /// The HTTP status; nil when the request never got an answer.
    public let status: Int?
    public let message: String

    public init(status: Int?, message: String) {
        self.status = status
        self.message = message
    }

    public var errorDescription: String? { message }
}

/// The server's API level is below ``requiredAPILevel``.
public struct ServerTooOldError: Error, LocalizedError, Equatable, Sendable {
    public let server: Server

    public init(server: Server) { self.server = server }

    public var errorDescription: String? {
        "\(server.name) runs version \(server.version), which is too old for this app. Update the server."
    }
}

/// Not signed in, or the session was revoked: show sign-in.
public struct SignedOutError: Error, LocalizedError, Equatable, Sendable {
    public init() {}

    public var errorDescription: String? { "Signed out" }
}

extension Error {
    /// What a screen shows for this error: the server's message, or the
    /// system's description of a network failure.
    public var displayMessage: String {
        if let described = self as? LocalizedError, let text = described.errorDescription { return text }
        return localizedDescription
    }
}
