import Foundation
import Observation
import WMMModel

/// Signing in to the server in use (Main.dc.html, SignInPairing.dc.html):
/// with a password, or by pairing when the server's sign-in protection
/// (Turnstile) refuses app passwords. A server with no accounts yet sends the
/// person to its web page to make the first one. Success needs no event:
/// ``AuthRepository/user`` changes and the root moves on
/// (android/.../SignInViewModel.kt).
///
/// Apple TV (`pairsByDefault`, TvMain.dc.html) signs in by pairing first:
/// the code and its QR code show at once and a new code replaces one that
/// runs out, with "Sign in with a password instead" while the server allows
/// passwords (TvSignInPassword.dc.html).
@MainActor
@Observable
public final class SignInModel {
    private let servers: any ServerRepository
    private let auth: any AuthRepository

    /// Pairing is the way in, as on Apple TV; the password form is the fallback.
    public let pairsByDefault: Bool

    /// Apple TV: the person chose the password form over the code.
    public private(set) var usingPassword = false

    public var name = "" {
        didSet { if name != oldValue { error = nil } }
    }

    public var password = "" {
        didSet { if password != oldValue { error = nil } }
    }

    public var showPassword = false
    public private(set) var busy = false
    public private(set) var error: String?

    /// The pairing under way while ``codeSignIn``; nil before it starts.
    public private(set) var pairing: PairingState?

    /// Changes whenever a pairing should (re)start: the screen runs ``pair()``
    /// in a task keyed on it, which also stops polling when the screen goes.
    public private(set) var pairingRun = 0

    /// The server refused a password sign-in (403) since it was chosen:
    /// Turnstile was turned on, so sign in by pairing.
    private var refusedPassword = false

    public init(servers: any ServerRepository, auth: any AuthRepository, pairsByDefault: Bool = false) {
        self.servers = servers
        self.auth = auth
        self.pairsByDefault = pairsByDefault
    }

    /// The server being signed in to, for the card at the top.
    public var server: Server? { servers.current?.server }

    /// The server takes passwords from apps: its sign-in protection is off.
    public var passwordAllowed: Bool { !refusedPassword && server?.turnstile != true }

    /// The server has no accounts yet: its first one (the admin) and its
    /// libraries are made on its web page, so the phone sends the person there
    /// rather than to a form that could only say "Wrong name or password".
    /// Apple TV keeps its code, which the web page approves once that account is made.
    public var setupRequired: Bool { !pairsByDefault && server?.setupRequired == true }

    /// The server's web page, where its first account is made.
    public var setupPage: URL? { setupRequired ? server?.baseURL : nil }

    /// Asking the server again whether it is set up.
    public private(set) var checking = false

    /// Show the pairing code instead of the form: the server wants a browser
    /// check before a password sign-in, or (Apple TV) the password was not asked
    /// for. Not before the server has an account to approve it with.
    public var codeSignIn: Bool { !setupRequired && (!passwordAllowed || (pairsByDefault && !usingPassword)) }

    /// "Sign in with a password instead" (Apple TV): the form, while the server allows passwords.
    public func usePassword() {
        guard passwordAllowed else { return }
        usingPassword = true
        pairing = nil
    }

    /// "Use a pairing code instead" (Apple TV): back to the code, a fresh one.
    public func usePairingCode() {
        usingPassword = false
        error = nil
        newCode()
    }

    public var canSubmit: Bool {
        !name.trimmingCharacters(in: .whitespaces).isEmpty && !password.isEmpty && !busy && !codeSignIn && !setupRequired
    }

    /// This device's name, as the footer says.
    public var deviceName: String { auth.deviceName }

    public func toggleShowPassword() { showPassword.toggle() }

    /// Password sign-in. A wrong password and throttling say so under the
    /// fields; a 403 means sign-in protection came on, so it turns to pairing.
    public func signIn() async {
        guard !busy else { return }
        guard canSubmit else {
            if !codeSignIn && !setupRequired { error = "Enter your name and password." }
            return
        }
        busy = true
        error = nil
        defer { busy = false }
        do {
            _ = try await auth.signIn(name: name.trimmingCharacters(in: .whitespaces), password: password)
            password = ""
            showPassword = false
        } catch is CancellationError {
        } catch {
            if (error as? APIError)?.status == 403 {
                refusedPassword = true
                pairing = nil
                pairingRun += 1
            } else {
                self.error = signInMessage(error)
            }
        }
    }

    /// Asks the server again whether it has an account yet, after the person
    /// made one on its web page; once it has, choosing it again saves that and
    /// the sign-in takes over. `quiet` for the check on coming back to the
    /// app, which says nothing while the server is still waiting.
    public func checkSetup(quiet: Bool = false) async {
        guard setupRequired, !checking, let current = server else { return }
        checking = true
        error = nil
        defer { checking = false }
        do {
            let fresh = try await servers.probe(current.baseURL.absoluteString)
            if fresh.id == current.id && !fresh.setupRequired {
                try await servers.select(fresh)
            } else if !quiet {
                error = "No account on this server yet. Make one on its web page first."
            }
        } catch is CancellationError {
        } catch {
            if !quiet { self.error = connectMessage(error, label: current.name) }
        }
    }

    /// Follows a pairing until it is approved, runs out or fails; cancelling
    /// the calling task stops it. Run it from the screen, keyed on ``pairingRun``.
    /// On Apple TV a code that runs out is replaced by a new one at once.
    public func pair() async {
        guard codeSignIn else { return }
        repeat {
            pairing = .starting
            for await state in auth.pair() {
                pairing = state
            }
        } while pairsByDefault && pairing == .expired && !Task.isCancelled
    }

    /// Starts over with a fresh code (after one ran out or failed, or on request).
    public func newCode() {
        pairing = nil
        pairingRun += 1
    }

    /// The code being waited on, when there is one.
    public var waitingCode: PairingCode? {
        if case .waiting(let code) = pairing { return code }
        return nil
    }

    /// The pairing ended without a sign-in: offer a new code.
    public var pairingEnded: Bool {
        switch pairing {
        case .expired, .failed: true
        default: false
        }
    }

    /// The reason the pairing ended, for the card.
    public var pairingProblem: String? {
        switch pairing {
        case .expired: "The code ran out. Get a new one to sign in."
        case .failed(let message): message.trimmingCharacters(in: .whitespaces).isEmpty ? "Couldn't get a code." : message
        default: nil
        }
    }

    // MARK: Link a TV

    /// What tapping Link a TV did, so the screen can follow up.
    public enum LinkTVFollowUp: Equatable, Sendable {
        /// Signing in now, then Link a TV opens.
        case signingIn
        /// The name is empty: put the cursor there.
        case focusName
        /// Remembered; Link a TV opens once signed in.
        case none
    }

    /// Linking a TV needs a signed-in phone, so Link a TV is remembered on the
    /// navigator (Settings › Link a TV opens after sign-in) and signs in when
    /// the form is filled in.
    public func linkTV(navigator: Navigator) async -> LinkTVFollowUp {
        if navigator.pendingLinkTV == nil { navigator.queueLinkTV(code: nil) }
        if codeSignIn { return .none }
        if canSubmit {
            await signIn()
            return .signingIn
        }
        return name.trimmingCharacters(in: .whitespaces).isEmpty ? .focusName : .none
    }

    /// The Link a TV row's line, from whether one is waiting for the sign-in.
    public func linkTVSubtitle(navigator: Navigator) -> String {
        let pending = navigator.pendingLinkTV
        return linkTVRowSubtitle(pending: pending != nil, code: pending?.code, codeSignIn: codeSignIn)
    }
}

/// A Link a TV waiting for the sign-in, with the TV's code if a link brought one.
public struct PendingLinkTV: Hashable, Sendable {
    public var code: String?
}

extension Navigator {
    /// A Link a TV waiting for the sign-in (from the sign-in screen's row or a
    /// watchmymedia://link opened before signing in): Settings › Link a TV is
    /// what the tabs show first. Nil when none is waiting.
    public var pendingLinkTV: PendingLinkTV? {
        guard tab == .settings, case .linkTV(let code) = path(for: .settings).last else { return nil }
        return PendingLinkTV(code: code)
    }

    /// Opens Settings › Link a TV, now or (before sign-in) once the tabs show.
    public func queueLinkTV(code: String?) {
        tab = .settings
        setPath([.linkTV(code: code)], for: .settings)
    }
}

/// A sign-in form kept above the screens that show it, as the server switch
/// is: an iPad window resized during sign-in swaps the iPhone and iPad
/// screens, and each used to make its own form, losing what was typed
/// (PLAN-APPLE-REVIEW.md §3.3.4). One per server; ``forget()`` on a sign-in
/// or sign-out, so the next starts afresh.
@MainActor
public final class SignInForm {
    private var held: (serverID: String?, model: SignInModel)?

    public init() {}

    /// The form for the server in use, the same one each time it is asked for.
    public func model(servers: any ServerRepository, auth: any AuthRepository) -> SignInModel {
        let serverID = servers.current?.server.id
        if let held, held.serverID == serverID { return held.model }
        let model = SignInModel(servers: servers, auth: auth)
        held = (serverID, model)
        return model
    }

    public func forget() { held = nil }
}
