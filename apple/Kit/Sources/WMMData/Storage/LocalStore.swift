import Foundation
import Observation
import WMMAPI
import WMMModel

/// A server that isn't the one in use hasn't said yet, asked without a
/// sign-in, that it is the one remembered at its kept address: its sign-in
/// isn't sent (``LocalStore/other(serverID:)``).
struct ServerNotAskedError: Error, Equatable {}

/// The sign-in a request goes out with.
struct SignedInSession: Sendable {
    let serverID: String
    let token: String
    let api: ServerAPI
}

/// The device's servers and sign-ins, in memory and written through to
/// ``LocalStorage`` on every change, and the ``ServerAPI`` of the sign-in in
/// use (android/core/data/.../LocalStore.kt and Http.kt). Every repository
/// reads it, so a sign-out anywhere shows everywhere at once.
@MainActor
@Observable
final class LocalStore {
    private(set) var state: StoredState
    /// Sign-ins by server ID, read from storage when a server is first used.
    private(set) var credentials: [String: StoredCredential]
    private(set) var revision = 0

    @ObservationIgnored let storage: any LocalStorage
    @ObservationIgnored let session: URLSession
    @ObservationIgnored let userAgent: String
    @ObservationIgnored let deviceID: String
    /// Local Network access as discovery last heard it, for the APIs made here.
    @ObservationIgnored let localNetwork = LocalNetworkAccess()
    @ObservationIgnored private let baseClient: ClientInfo
    @ObservationIgnored private var sessionKey: String?
    @ObservationIgnored private var sessionListeners: [(_ signedIn: Bool) -> Void] = []
    @ObservationIgnored private var cachedAPI: (key: String, api: ServerAPI)?
    /// The APIs of the other servers signed in to, by server ID, for search across servers and their pictures.
    @ObservationIgnored private var otherAPIs: [String: (key: String, api: ServerAPI)] = [:]
    /// The servers that aren't in use and have said, asked with no sign-in,
    /// that they are the ones remembered at the addresses kept for them
    /// (``noteAnswer(serverID:)``), each with the address it answered at. A
    /// kept address is only where the server was last seen: on another
    /// network it can be someone else's machine, so ``other(serverID:)``
    /// hands out a sign-in only while its server's answer stands. An answer
    /// stands for a visit to Search and on the network it was given on: it
    /// goes when the visit ends, the network changes, another server comes
    /// into use, or a call to that server fails.
    @ObservationIgnored private var answers: [String: URL] = [:]
    /// Asks the server kept as an id which server it is, with no sign-in,
    /// and says whether it answered as itself (``RealServerRepository/identify(_:)``).
    /// A sign-out asks it once, for all it sends.
    @ObservationIgnored var answersAsItself: @MainActor (_ serverID: String) async -> Bool = { _ in true }
    /// Run before a sign-in is ended on its server (``beforeSignOut(_:)``).
    @ObservationIgnored private var signOutHooks: [SignOutHook] = []
    @ObservationIgnored private var signInRemovedListeners: [() -> Void] = []

    /// What runs before `serverID`'s sign-in ends, with the sign-in that is ending.
    typealias SignOutHook = @MainActor @Sendable (_ server: Server, _ credential: StoredCredential) async -> Void
    /// Servers whose sign-in couldn't be read (a launch before the first
    /// unlock), read again when protected data becomes available.
    @ObservationIgnored private(set) var unreadable: Set<String> = []

    /// - Parameter client: how this device describes itself; its ID is
    ///   replaced by the stored one and its name is the first device name.
    init(storage: any LocalStorage, session: URLSession, userAgent: String, client: ClientInfo) {
        self.storage = storage
        self.session = session
        self.userAgent = userAgent
        deviceID = storage.deviceID()
        baseClient = client
        var loaded = storage.loadState()
        var creds: [String: StoredCredential] = [:]
        var unreadable: Set<String> = []
        for s in loaded.servers {
            switch Self.read(storage, s.server) {
            case .found(let c): creds[s.server.id] = c
            case .unavailable: unreadable.insert(s.server.id)
            case .missing: break
            }
        }
        self.unreadable = unreadable
        if loaded.deviceName == nil {
            loaded.deviceName = ClientIdentity.cleanName(client.name) ?? ClientIdentity.defaultName
            storage.saveState(loaded)
        }
        state = loaded
        credentials = creds
        sessionKey = Self.key(loaded, creds)
    }

    /// `server`'s sign-in as kept, if it was issued by that address.
    private static func read(_ storage: any LocalStorage, _ server: Server) -> CredentialRead {
        switch storage.readCredential(serverID: server.id) {
        case .found(var c):
            // Upgrade an existing install using its previously saved address.
            // An orphan Keychain credential after reinstall has no such evidence.
            if c.baseURL == nil {
                c.baseURL = server.baseURL
                storage.setCredential(c, serverID: server.id)
            }
            return c.baseURL.map({ ServerAPI.sameEndpoint($0, server.baseURL) }) == true ? .found(c) : .missing
        case .missing: return .missing
        case .unavailable: return .unavailable
        }
    }

    /// Protected data is available again (the first unlock after a restart):
    /// the sign-ins that couldn't be read at launch are read now, so a run
    /// that started locked doesn't stay signed out.
    func readUnreadableAgain() {
        guard !unreadable.isEmpty else { return }
        for id in unreadable {
            guard let server = state.servers.first(where: { $0.server.id == id })?.server else {
                unreadable.remove(id)
                continue
            }
            switch Self.read(storage, server) {
            case .found(let c):
                unreadable.remove(id)
                mutate { credentials[id] = c }
            case .missing:
                unreadable.remove(id)
            case .unavailable:
                break
            }
        }
    }

    // MARK: Reading

    /// The server in use, or nil before one is chosen.
    var current: SavedServer? { state.current.map(savedServer) }

    /// Servers used before, most recent first.
    var saved: [SavedServer] { state.servers.map(savedServer) }

    /// The sign-in on the current server, or nil.
    var currentCredential: StoredCredential? { state.currentID.flatMap { credentials[$0] } }

    var user: User? { currentCredential?.user }

    /// What the server in use offers at its level as last refreshed; nothing with no server.
    var features: ServerFeatures { ServerFeatures(apiLevel: state.current?.server.apiLevel) }

    var deviceName: String { state.deviceName ?? baseClient.name }

    /// Automatic until a quality is picked; a value this version does not know is Original.
    var quality: StreamQuality {
        guard let saved = state.quality else { return .auto }
        return StreamQuality(rawValue: saved) ?? .original
    }

    /// How this device describes itself, with the name as it is now.
    var client: ClientInfo {
        var c = baseClient
        c.deviceID = deviceID
        c.name = deviceName
        return c
    }

    private func savedServer(_ s: StoredServer) -> SavedServer {
        let credential = credentials[s.server.id]
        return SavedServer(server: s.server, userName: credential?.user.name ?? s.lastUserName, signedIn: credential != nil, lastUsedAt: s.lastUsedAt)
    }

    // MARK: Changing

    /// Changes the state and saves it.
    func update(_ change: (inout StoredState) -> Void) {
        mutate { change(&state) }
    }

    /// Keeps (or with nil, forgets) the sign-in on a server.
    func setCredential(_ credential: StoredCredential?, serverID: String) {
        unreadable.remove(serverID)
        let before = revision
        let previous = credentials[serverID]
        mutate {
            credentials[serverID] = credential
            storage.setCredential(credential, serverID: serverID)
            if let name = credential?.user.name ?? previous?.user.name {
                state.updateServer(serverID) { $0.lastUserName = name }
            }
        }
        otherAPIs[serverID] = nil
        // The sign-in of a server that isn't in use went: the session in use
        // didn't change, so its listeners heard nothing.
        if credential == nil, previous != nil, revision == before {
            for listener in signInRemovedListeners { listener() }
        }
    }

    /// The sign-in kept for `serverID` (also from before a reinstall), loading it from storage once.
    @discardableResult
    func loadCredential(serverID: String, baseURL: URL? = nil) -> StoredCredential? {
        guard let c = credentials[serverID] ?? storage.credential(serverID: serverID) else { return nil }
        let expected = baseURL ?? state.servers.first(where: { $0.server.id == serverID })?.server.baseURL
        guard let issued = c.baseURL, let expected, ServerAPI.sameEndpoint(issued, expected) else {
            setCredential(nil, serverID: serverID)
            return nil
        }
        mutate { credentials[serverID] = c }
        return c
    }

    /// The server refused `token` (401): the session was revoked or ran out.
    /// Clears that sign-in, unless it has been replaced since.
    func tokenRejected(serverID: String, token: String) {
        guard credentials[serverID]?.token == token else { return }
        setCredential(nil, serverID: serverID)
    }

    /// Calls `listener` whenever the sign-in in use changes (another server,
    /// another account, a new token, or signed out), with whether one is in use.
    func onSessionChange(_ listener: @escaping (_ signedIn: Bool) -> Void) {
        sessionListeners.append(listener)
    }

    /// Calls `listener` when the sign-in kept for a server that isn't the
    /// one in use goes (the server was removed, or its session refused),
    /// which ``onSessionChange(_:)`` doesn't report.
    func onSignInRemoved(_ listener: @escaping () -> Void) {
        signInRemovedListeners.append(listener)
    }

    /// Runs `hook` before a sign-in is ended on its server, while the server
    /// still takes it (offline downloads report themselves removed).
    func beforeSignOut(_ hook: @escaping SignOutHook) {
        signOutHooks.append(hook)
    }

    /// Runs the sign-out hooks for `serverID`'s sign-in, each for at most 5 s;
    /// nothing when no sign-in is kept for it.
    func runSignOutHooks(serverID: String) async {
        guard let server = state.servers.first(where: { $0.server.id == serverID })?.server,
              let credential = credentials[serverID] else { return }
        for hook in signOutHooks {
            _ = try? await withDeadline(.seconds(5)) { await hook(server, credential) }
        }
    }

    private func mutate(_ body: () -> Void) {
        let before = state
        body()
        if state != before { storage.saveState(state) }
        let key = Self.key(state, credentials)
        guard key != sessionKey else { return }
        sessionKey = key
        revision += 1
        // Another server or account in use: which servers are the others changed with it.
        answers = [:]
        let signedIn = currentCredential != nil
        for listener in sessionListeners { listener(signedIn) }
    }

    private static func key(_ state: StoredState, _ credentials: [String: StoredCredential]) -> String? {
        guard let current = state.current else { return nil }
        let c = credentials[current.server.id]
        return "\(current.server.id) \(current.server.baseURL.absoluteString) \(c?.user.id ?? 0) \(c?.token ?? "")"
    }

    // MARK: Requests

    /// The current server's API with its sign-in; ``SignedOutError`` when nobody is signed in.
    func signedIn() throws -> SignedInSession {
        guard let server = state.current?.server, let credential = credentials[server.id] else { throw SignedOutError() }
        let key = "\(server.id) \(server.baseURL.absoluteString) \(credential.token)"
        if let cached = cachedAPI, cached.key == key {
            return SignedInSession(serverID: server.id, token: credential.token, api: cached.api)
        }
        let api = api(serverID: server.id, baseURL: server.baseURL, token: credential.token)
        cachedAPI = (key, api)
        return SignedInSession(serverID: server.id, token: credential.token, api: api)
    }

    /// The server kept as `serverID`, which isn't the one in use, answered
    /// as itself at its kept address a moment ago: its sign-in may go there.
    func noteAnswer(serverID: String) {
        guard let server = state.servers.first(where: { $0.server.id == serverID })?.server else { return }
        answers[serverID] = server.baseURL
    }

    /// Whether `serverID`'s answer stands: given at the address kept for it now, and not forgotten since.
    func answered(_ serverID: String) -> Bool {
        guard let answer = answers[serverID], let server = state.servers.first(where: { $0.server.id == serverID })?.server else { return false }
        return answer == server.baseURL
    }

    /// Every answer is forgotten: the visit to Search ended, or the network
    /// changed. The next sign-in for another server waits for a new one.
    func forgetAnswers() {
        answers = [:]
    }

    /// A call to `serverID` failed: whatever answered before may not be
    /// there now, so it is asked again before its sign-in goes out (Try again).
    func forgetAnswer(_ serverID: String) {
        answers[serverID] = nil
    }

    /// The API of a server this device is signed in to that isn't the one
    /// in use, with its own sign-in on its own address. ``SignedOutError``
    /// when no sign-in is kept for it, ``ServerTooOldError`` when it is too
    /// old, and ``ServerNotAskedError`` until the server has said, without a
    /// sign-in, that it is the one remembered (``answered(_:)``): nothing
    /// gets its sign-in on the strength of a kept address alone.
    func other(serverID: String) throws -> SignedInSession {
        guard let server = state.servers.first(where: { $0.server.id == serverID })?.server,
              let credential = credentials[serverID] else { throw SignedOutError() }
        if server.tooOld { throw ServerTooOldError(server: server) }
        guard answered(serverID) else { throw ServerNotAskedError() }
        let key = "\(server.baseURL.absoluteString) \(credential.token)"
        if let cached = otherAPIs[serverID], cached.key == key {
            return SignedInSession(serverID: serverID, token: credential.token, api: cached.api)
        }
        let api = api(serverID: serverID, baseURL: server.baseURL, token: credential.token)
        otherAPIs[serverID] = (key, api)
        return SignedInSession(serverID: serverID, token: credential.token, api: api)
    }

    /// The sign-in a request for `url` goes out with: the server's that the
    /// address is on, the one in use first, then any other this device is
    /// signed in to whose answer stands (``answered(_:)``). For an address
    /// on none of them, the sign-in in use, which doesn't sign it
    /// (``RequestSigner``).
    func signedIn(for url: URL) -> SignedInSession? {
        let current = try? signedIn()
        if let current, ServerAPI.owns(current.api.baseURL, url) { return current }
        for stored in state.servers where stored.server.id != state.currentID && ServerAPI.owns(stored.server.baseURL, url) {
            if let session = try? other(serverID: stored.server.id) { return session }
        }
        return current
    }

    /// Runs a call as the signed-in user; a refused token signs out (in
    /// ``ServerAPI``) before the ``SignedOutError`` reaches the caller.
    func call<T: Sendable>(_ operation: @Sendable (Client) async throws -> T) async throws -> T {
        let signedIn = try signedIn()
        let started = revision
        let result = try await signedIn.api.call(operation)
        guard revision == started else { throw CancellationError() }
        return result
    }

    /// The API of a kept sign-in: `token` on `baseURL`'s addresses only, and a
    /// 401 that revokes it forgets that sign-in (unless it was replaced
    /// since), wherever the request came from.
    func api(serverID: String, baseURL: URL, token: String, session: URLSession? = nil) -> ServerAPI {
        ServerAPI(
            baseURL: baseURL, session: session ?? self.session, userAgent: userAgent, token: { token },
            onSignedOut: { [weak self] in await self?.tokenRejected(serverID: serverID, token: token) }, localNetwork: localNetwork
        )
    }

    /// An API at `baseURL` that sends `token` (or none) and keeps no sign-in:
    /// for signing in, probing, and signing out of a server being forgotten.
    func api(baseURL: URL, token: String? = nil, session: URLSession? = nil) -> ServerAPI {
        ServerAPI(baseURL: baseURL, session: session ?? self.session, userAgent: userAgent, token: { token }, localNetwork: localNetwork)
    }

    /// How a request to `baseURL` carries `token`, for what isn't an API call (the offline routes' fetches).
    func signer(baseURL: URL, token: String) -> RequestSigner {
        RequestSigner(baseURL: baseURL, token: token, userAgent: userAgent)
    }

    /// POST /api/auth/logout with `token`, ignoring every failure, for at most 5 s.
    func signOutQuietly(baseURL: URL, token: String) async {
        let api = api(baseURL: baseURL, token: token)
        _ = try? await withDeadline(.seconds(5)) {
            try await api.call { _ = try await $0.logout() }
        }
    }
}
