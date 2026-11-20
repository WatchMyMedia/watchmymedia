import Foundation
import Observation
import WMMModel

/// Add a server: Choose a server, Sign in, Added. It runs over the app,
/// which stays on the server it was on until the new one is signed in, and
/// goes back there on Cancel or any failure. Servers this device is
/// registered with already aren't offered again (``found``, ``remembered``,
/// ``alreadyAdded``): switching to one of those needs no steps
/// (``ServersModel``). Credentials stay in the repositories; cancellation
/// selects the original saved endpoint, never a newly discovered address
/// with its ID.
///
/// The same steps sign in again to a registered server whose sign-in ended
/// (``signIn(to:)``), starting on Sign in.
@MainActor
@Observable
public final class AddServerModel {
    public enum Phase: Equatable { case picking, connecting, signingIn, restoring, finished }
    public enum Outcome: Equatable { case added, cancelled, accountChanged }

    public let originalServer: Server
    public let originalUser: User
    public let picker: ServerPickerModel
    public private(set) var phase: Phase = .picking
    public private(set) var outcome: Outcome?
    public private(set) var signIn: SignInModel?
    public private(set) var error: String?
    /// The server and account it ended on, once added.
    public private(set) var addedServer: Server?
    public private(set) var addedUser: User?
    /// The server reached was signed in to already (its address was typed),
    /// so Sign in was skipped: nothing was added, the app switched to it.
    public private(set) var skippedSignIn = false
    /// Signing in again to a registered server whose sign-in ended, not adding one.
    public private(set) var signingInAgain = false

    private let servers: any ServerRepository
    private let auth: any AuthRepository
    private let selectionPermission: SelectionPermission
    private var target: Server?

    private final class SelectionPermission {
        var valid = true
    }

    /// Sign in starts on the pairing code, as Apple TV's does.
    public let pairsByDefault: Bool

    /// - Parameter pairsByDefault: Sign in starts on the pairing code (Apple TV), with the password as the other way.
    public init?(servers: any ServerRepository, auth: any AuthRepository, pairsByDefault: Bool = false) {
        guard let original = servers.current, original.signedIn, let user = auth.user else { return nil }
        self.servers = servers
        self.auth = auth
        self.pairsByDefault = pairsByDefault
        originalServer = original.server
        originalUser = user
        let permission = SelectionPermission()
        selectionPermission = permission
        picker = ServerPickerModel(servers: servers, selectionAllowed: {
            permission.valid && Self.sameEndpoint(servers.current?.server, original.server) && auth.user == user
        })
    }

    /// Password/setup requests finish before Back can change their account.
    /// Pairing is view-scoped and stops when the sign-in pane disappears.
    public var busy: Bool {
        phase == .connecting || phase == .restoring || signIn?.busy == true || signIn?.checking == true
    }

    /// Servers on this network that aren't registered yet.
    public var found: [ServerPickerModel.Row] { picker.found.filter { !registeredIDs.contains($0.id) } }

    /// Servers used before, never signed in to, that aren't on this network now.
    public var remembered: [ServerPickerModel.Row] { picker.remembered.filter { !registeredIDs.contains($0.id) } }

    private var registeredIDs: Set<String> { Set(servers.registered.map(\.server.id)).union([originalServer.id]) }

    /// Under the list: "Already added: Living room server, Cabin server."
    public var alreadyAdded: String {
        let names = servers.registered.map(\.server.name)
        return "Already added: \((names.isEmpty ? [originalServer.name] : names).joined(separator: ", "))."
    }

    public func choose(_ row: ServerPickerModel.Row) async {
        await connect { await self.picker.choose(row) }
    }

    /// Starts on Sign in for a registered server whose sign-in ended, from
    /// its row in Servers or its page in Settings.
    public func signIn(to serverID: String) async {
        guard let saved = servers.saved.first(where: { $0.server.id == serverID }) else { return }
        signingInAgain = true
        await choose(ServerPickerModel.Row(
            id: saved.server.id, kind: .remembered, name: saved.server.name, address: saved.server.baseURL.absoluteString,
            detail: saved.switcherLine, tooOld: saved.server.tooOld, current: false, signedIn: saved.signedIn
        ))
    }

    public func connectToAddress() async {
        await connect { await self.picker.connectToAddress() }
    }

    private func connect(_ operation: () async -> Bool) async {
        guard phase == .picking else { return }
        guard originalAccountIsCurrent else { return finish(.accountChanged) }
        phase = .connecting
        error = nil
        let selected = await operation()
        guard selectionPermission.valid else { return finish(.accountChanged) }
        guard selected, let current = servers.current?.server else {
            phase = .picking
            accountChanged()
            return
        }
        target = current
        phase = .signingIn
        signIn = SignInModel(servers: servers, auth: auth, pairsByDefault: pairsByDefault)
        accountChanged()
        if outcome == .added { skippedSignIn = true }
    }

    /// Called when the repositories change. An unrelated account transition
    /// ends this flow without restoring credentials over that newer context.
    public func accountChanged() {
        switch phase {
        case .picking:
            if !originalAccountIsCurrent { finish(.accountChanged) }
        case .signingIn:
            guard let target, Self.sameEndpoint(servers.current?.server, target) else {
                return finish(.accountChanged)
            }
            if servers.current?.signedIn == true, auth.user != nil { finish(.added) }
        case .connecting:
            // Remember an interruption even if another window returns to
            // the same account before this probe finishes.
            if !originalAccountIsCurrent { selectionPermission.valid = false }
        case .restoring, .finished:
            break
        }
    }

    /// Back from sign-in returns to the list under the original account.
    public func backToPicker() async { await restore(thenPick: true) }

    /// Cancel returns to the initial server using its existing saved sign-in.
    /// If it was revoked or forgotten meanwhile, normal root sign-in takes over.
    public func cancel() async { await restore(thenPick: false) }

    private func restore(thenPick: Bool) async {
        guard !busy, phase != .finished else { return }
        accountChanged()
        guard phase != .finished else { return }
        if originalAccountIsCurrent {
            target = nil
            signIn = nil
            if thenPick { phase = .picking } else { finish(.cancelled) }
            return
        }
        guard let saved = servers.saved.first(where: { Self.sameEndpoint($0.server, originalServer) }) else {
            return finish(.accountChanged)
        }
        phase = .restoring
        signIn = nil
        error = nil
        do {
            try await servers.select(saved.server)
            guard originalAccountIsCurrent else { return finish(.accountChanged) }
            target = nil
            if thenPick { phase = .picking } else { finish(.cancelled) }
        } catch {
            self.error = "Couldn't return to \(originalServer.name). \(error.displayMessage)"
            phase = .signingIn
            signIn = SignInModel(servers: servers, auth: auth, pairsByDefault: pairsByDefault)
            accountChanged()
        }
    }

    private var originalAccountIsCurrent: Bool {
        Self.sameEndpoint(servers.current?.server, originalServer) && servers.current?.signedIn == true && auth.user == originalUser
    }

    private static func sameEndpoint(_ lhs: Server?, _ rhs: Server) -> Bool {
        lhs?.id == rhs.id && lhs?.baseURL == rhs.baseURL
    }

    private func finish(_ outcome: Outcome) {
        if outcome == .added {
            addedServer = servers.current?.server
            addedUser = auth.user
        }
        signIn = nil
        self.outcome = outcome
        phase = .finished
        picker.stopDiscovery()
    }
}
