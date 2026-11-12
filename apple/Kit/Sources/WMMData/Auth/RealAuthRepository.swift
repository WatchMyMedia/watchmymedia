import Foundation
import Observation
import WMMAPI
import WMMModel

/// Signing in on the server in use, by password or by code, and the
/// account's sessions (android/core/data/.../RealAuthRepository.kt).
@MainActor
@Observable
final class RealAuthRepository: AuthRepository {
    var user: User? { store.user }
    var deviceName: String { store.deviceName }

    @ObservationIgnored private let store: LocalStore
    @ObservationIgnored private let minimumPoll: Duration
    @ObservationIgnored private let now: @Sendable () -> Date
    @ObservationIgnored private var signOutHooks: [@MainActor @Sendable () async -> Void] = []

    private static let maximumPoll: Duration = .seconds(10)

    /// - Parameter minimumPoll: the shortest wait between pairing polls, whatever the server asks.
    init(store: LocalStore, minimumPoll: Duration = .milliseconds(250), now: @escaping @Sendable () -> Date) {
        self.store = store
        self.minimumPoll = minimumPoll
        self.now = now
    }

    func setDeviceName(_ name: String) async {
        guard let clean = ClientIdentity.cleanName(name) else { return }
        store.update { $0.deviceName = clean }
    }

    func signIn(name: String, password: String) async throws -> User {
        guard let server = store.current?.server else { throw APIError(status: nil, message: "Choose a server first.") }
        let revision = store.revision
        let body = Components.Schemas.CredentialsInputBody(client: store.client.api, name: name.trimmingCharacters(in: .whitespacesAndNewlines), password: password)
        let reply: Components.Schemas.SignIn
        do {
            reply = try await store.api(baseURL: server.baseURL).call { try await $0.login(body: .json(body)).ok.body.json }
        } catch let error as APIError where error.status == 403 {
            // Turnstile on the server: only the web page can show the check, so the app signs in with a code.
            throw APIError(status: 403, message: "This server asks for a browser check before password sign-in. Sign in with a code instead.")
        }
        guard let token = reply.token else { throw APIError(status: nil, message: "The server did not send a sign-in token. Update the server.") }
        let user = User(id: Int(reply.id), name: reply.name, isAdmin: reply.isAdmin)
        guard keep(server: server, user: user, sessionID: reply.sessionId, token: token, revision: revision) else { throw CancellationError() }
        return user
    }

    func pair() -> AsyncStream<PairingState> {
        AsyncStream { continuation in
            let task = Task { @MainActor [weak self] in
                guard let self else { return continuation.finish() }
                await self.runPairing { continuation.yield($0) }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    /// Starts a pairing and polls it until it is approved, expires or fails; stops when cancelled.
    private func runPairing(_ emit: (PairingState) -> Void) async {
        emit(.starting)
        guard let server = store.current?.server else { return emit(.failed("Choose a server first.")) }
        let revision = store.revision
        let api = store.api(baseURL: server.baseURL)
        let start: Components.Schemas.PairingStart
        do {
            let body = Components.Schemas.StartPairingInputBody(client: store.client.api)
            start = try await api.call { try await $0.startPairing(body: .json(body)).ok.body.json }
        } catch is CancellationError {
            return
        } catch {
            return emit(.failed(error.displayMessage))
        }
        let interval = min(max(Duration.milliseconds(start.intervalMs), minimumPoll), Self.maximumPoll)
        let link = URL(string: server.baseURL.absoluteString + start.linkPath) ?? server.baseURL
        emit(.waiting(PairingCode(code: start.code, expiresAt: start.expiresAt, linkURL: link, pollInterval: interval)))
        let claim = Components.Schemas.ClaimPairingInputBody(secret: start.secret)
        while true {
            do {
                try await Task.sleep(for: interval)
            } catch {
                return
            }
            if now() >= start.expiresAt { return emit(.expired) }
            let reply: Components.Schemas.PairingClaim
            do {
                reply = try await api.call { try await $0.claimPairing(body: .json(claim)).ok.body.json }
            } catch is CancellationError {
                return
            } catch let error as APIError {
                switch error.status {
                case 404?:
                    // Expired, or the token was already handed out: start again.
                    return emit(.expired)
                case nil, (500...)?:
                    // A dropped connection or a busy server: keep polling while the code lasts.
                    continue
                default:
                    return emit(.failed(error.message))
                }
            } catch {
                return emit(.failed(error.displayMessage))
            }
            guard !Task.isCancelled else { return }
            guard reply.status == .approved else { continue }
            guard let token = reply.token, let account = reply.user else {
                return emit(.failed("The server approved the code but sent no sign-in. Update the server."))
            }
            let user = account.model
            guard keep(server: server, user: user, sessionID: reply.sessionId, token: token, revision: revision) else { return }
            return emit(.approved(user))
        }
    }

    /// Runs `hook` before a sign-out ends the session, while the server still
    /// takes it (offline downloads report themselves removed); each gets 5 s.
    func beforeSignOut(_ hook: @escaping @MainActor @Sendable () async -> Void) {
        signOutHooks.append(hook)
    }

    func signOut() async {
        guard let server = store.current?.server, let credential = store.currentCredential else { return }
        for hook in signOutHooks {
            _ = try? await withDeadline(.seconds(5)) { await hook() }
        }
        guard store.credentials[server.id]?.token == credential.token else { return }
        store.setCredential(nil, serverID: server.id)
        await store.signOutQuietly(baseURL: server.baseURL, token: credential.token)
    }

    func sessions(all: Bool) async throws -> [DeviceSession] {
        try await store.call { try await $0.listSessions(query: .init(all: all ? true : nil)).ok.body.json }.items.compactMap(\.deviceSession)
    }

    func revoke(sessionID: String) async throws {
        _ = try await store.call { try await $0.revokeSession(path: .init(id: sessionID)).noContent }
        // Revoking this device's own session signs it out.
        if let server = store.current?.server, let credential = store.currentCredential, credential.sessionID == sessionID {
            store.setCredential(nil, serverID: server.id)
        }
    }

    func lookUpPairing(code: String) async throws -> PendingDevice {
        let code = try Self.normaliseCode(code)
        return try await store.call { try await $0.getPairing(path: .init(code: code)).ok.body.json }.pendingDevice
    }

    func approvePairing(code: String) async throws -> PendingDevice {
        let code = try Self.normaliseCode(code)
        return try await store.call { try await $0.approvePairing(path: .init(code: code)).ok.body.json }.pendingDevice
    }

    /// Refreshes the signed-in user from GET /api/auth/me (a renamed account, a
    /// change of admin rights). A revoked session signs out through the 401;
    /// being offline changes nothing.
    func refreshUser() async {
        guard let server = store.current?.server, let credential = store.currentCredential else { return }
        guard let me = try? await store.call({ try await $0.getMe().ok.body.json }) else { return }
        guard store.credentials[server.id]?.token == credential.token else { return }
        var updated = credential
        updated.user = me.model
        if updated != credential { store.setCredential(updated, serverID: server.id) }
    }

    /// Keeps a new sign-in; the session it replaces is ended on the server.
    private func keep(server: Server, user: User, sessionID: String?, token: String, revision: Int) -> Bool {
        guard store.revision == revision, let saved = store.state.servers.first(where: { $0.server.id == server.id }),
              ServerAPI.sameEndpoint(saved.server.baseURL, server.baseURL) else {
            let store = store
            Task { await store.signOutQuietly(baseURL: server.baseURL, token: token) }
            return false
        }
        let replaced = store.credentials[server.id]
        store.setCredential(StoredCredential(user: user, sessionID: sessionID, token: token, baseURL: server.baseURL), serverID: server.id)
        if let replaced, replaced.token != token {
            let store = store
            Task { await store.signOutQuietly(baseURL: server.baseURL, token: replaced.token) }
        }
        return true
    }

    /// "abc-234" → "ABC234"; the server is as forgiving, this keeps the path tidy.
    static func normaliseCode(_ code: String) throws -> String {
        let clean = code.filter { $0.isASCII && ($0.isLetter || $0.isNumber) }.uppercased()
        guard clean.count == 6 else { throw APIError(status: nil, message: "A code has six letters and digits, like KX7P4M.") }
        return clean
    }
}
