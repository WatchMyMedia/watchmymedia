import Foundation
import Synchronization

/// Where a sign-in kept on this device may go without its server being
/// asked which one it is first: to the server in use, and to a server that
/// has said, asked with no sign-in, that it is the one remembered, for as
/// long as that answer stands (``LocalStore``). Every request's signer
/// reads it (``SignInCheck``), on whatever thread the request is made, so
/// it is kept apart from the store, which lives on the main actor.
final class SignInStanding: Sendable {
    private struct State {
        var inUse: (id: String, baseURL: URL)?
        var answers: [String: URL] = [:]
    }

    private let state = Mutex(State())

    /// Whether `serverID`'s sign-in may go to `baseURL` now.
    func stands(serverID: String, baseURL: URL) -> Bool {
        state.withLock { state in
            if let inUse = state.inUse, inUse.id == serverID, ServerAPI.sameEndpoint(inUse.baseURL, baseURL) { return true }
            return state.answers[serverID].map { ServerAPI.sameEndpoint($0, baseURL) } ?? false
        }
    }

    /// Whether `serverID` answered as itself at `baseURL` and that answer stands.
    func answered(serverID: String, baseURL: URL) -> Bool {
        state.withLock { $0.answers[serverID].map { ServerAPI.sameEndpoint($0, baseURL) } ?? false }
    }

    /// The server in use changed (or none is).
    func use(_ server: (id: String, baseURL: URL)?) {
        state.withLock { $0.inUse = server }
    }

    func note(serverID: String, baseURL: URL) {
        state.withLock { $0.answers[serverID] = baseURL }
    }

    func forget(serverID: String) {
        state.withLock { $0.answers[serverID] = nil }
    }

    func forgetAll() {
        state.withLock { $0.answers = [:] }
    }
}
