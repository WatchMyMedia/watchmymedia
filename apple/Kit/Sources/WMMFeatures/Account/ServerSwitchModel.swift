import Foundation
import Observation
import WMMModel

/// Keeps an in-app server switch inside Settings until it is signed in or
/// cancelled. Credentials stay in the repositories; cancellation selects the
/// original saved endpoint, never a newly discovered address with its ID.
@MainActor
@Observable
public final class ServerSwitchModel {
    public enum Phase: Equatable { case picking, connecting, signingIn, restoring, finished }
    public enum Outcome: Equatable { case switched, cancelled, accountChanged }

    public let originalServer: Server
    public let originalUser: User
    public let picker: ServerPickerModel
    public private(set) var phase: Phase = .picking
    public private(set) var outcome: Outcome?
    public private(set) var signIn: SignInModel?
    public private(set) var error: String?

    private let servers: any ServerRepository
    private let auth: any AuthRepository
    private let selectionPermission: SelectionPermission
    private var target: Server?

    private final class SelectionPermission {
        var valid = true
    }

    public init?(servers: any ServerRepository, auth: any AuthRepository) {
        guard let original = servers.current, original.signedIn, let user = auth.user else { return nil }
        self.servers = servers
        self.auth = auth
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

    public func choose(_ row: ServerPickerModel.Row) async {
        await connect { await self.picker.choose(row) }
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
        signIn = SignInModel(servers: servers, auth: auth)
        accountChanged()
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
            if servers.current?.signedIn == true, auth.user != nil { finish(.switched) }
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
            signIn = SignInModel(servers: servers, auth: auth)
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
        signIn = nil
        self.outcome = outcome
        phase = .finished
        picker.stopDiscovery()
    }
}
