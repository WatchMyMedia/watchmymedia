import Foundation
import Observation
import WMMModel

/// Link a TV (LinkTv.dc.html), the phone's side of pairing: type the TV's
/// code, check which device it is, approve it (android/.../LinkTvViewModel.kt).
@MainActor
@Observable
public final class LinkTVModel {
    public enum Step: Hashable, Sendable {
        case enter
        case found(PendingDevice)
        case approved(PendingDevice)
    }

    private let auth: any AuthRepository

    /// The code as far as it is typed: capitals and digits, at most six.
    public private(set) var code: String
    public private(set) var step: Step = .enter
    public private(set) var busy = false
    public private(set) var error: String?
    /// The last failure as its step says it, ending in what was done:
    /// "Nothing was linked." (PLAN-APPLE-SETTINGS.md §5.5).
    public private(set) var problem: SettingsNotice?
    /// The server's name, for "Couldn’t reach Living room server".
    public var serverName: String?
    /// The server refused a code with too many wrong ones (429): Check this
    /// code is off, and says why, until ``retryAfter`` has passed.
    public private(set) var tooManyCodes = false

    /// How long Check this code stays off after too many wrong codes.
    @ObservationIgnored public let retryAfter: Duration
    @ObservationIgnored private let sleep: @MainActor (Duration) async throws -> Void
    @ObservationIgnored private var cooling: Task<Void, Never>?

    /// `code` comes from a watchmymedia://link?code= link (a scanned QR code).
    /// - Parameter sleep: waits out `retryAfter`; tests pass a clock they move.
    public init(auth: any AuthRepository, code: String? = nil, serverName: String? = nil,
                retryAfter: Duration = .seconds(60),
                sleep: @escaping @MainActor (Duration) async throws -> Void = { try await Task.sleep(for: $0) }) {
        self.auth = auth
        self.code = normalizePairingCode(code ?? "")
        self.serverName = serverName
        self.retryAfter = retryAfter
        self.sleep = sleep
    }

    /// Check this code is on: six characters, not while the server is
    /// refusing codes, and not while one is being checked.
    public var canCheck: Bool { complete && !tooManyCodes && !busy }

    /// Who the TV will be signed in as.
    public var userName: String { auth.user?.name ?? "you" }

    public var complete: Bool { code.count == pairingCodeLength }

    /// Takes the code as typed, in any case and with spaces or dashes; typing clears the error.
    public func setCode(_ raw: String) {
        let clean = normalizePairingCode(raw)
        guard clean != code else { return }
        code = clean
        error = nil
        problem = nil
        step = .enter
    }

    /// Back from Check it's yours to the code, which stays as typed.
    public func backToCode() {
        guard case .found = step, !busy else { return }
        step = .enter
        problem = nil
    }

    /// Link another TV, from Linked: the first step with nothing typed.
    public func startAgain() {
        guard !busy else { return }
        code = ""
        step = .enter
        error = nil
        problem = nil
    }

    /// Looks a code from a link up at once, as the QR code means to.
    public func start() async {
        if complete, step == .enter { await lookUp() }
    }

    /// Continue: the device behind the code.
    public func lookUp() async {
        guard !tooManyCodes else { return }
        guard complete else {
            error = "The code has \(pairingCodeLength) characters."
            return
        }
        let code = code
        await request(approving: nil) { try await self.auth.lookUpPairing(code: code) }
    }

    /// Approve: signs the TV in as this account.
    public func approve() async {
        guard case .found(let device) = step else { return }
        await request(approving: device) { try await self.auth.approvePairing(code: device.code) }
    }

    private func request(approving: PendingDevice?, _ call: () async throws -> PendingDevice) async {
        guard !busy else { return }
        busy = true
        error = nil
        problem = nil
        defer { busy = false }
        do {
            let device = try await call()
            step = device.approved ? .approved(device) : .found(device)
        } catch is CancellationError {
        } catch {
            self.error = message(error, fallback: "Couldn't check that code. Try again.")
            problem = approving.map { linkApproveProblem(error, device: $0, serverName: serverName) }
                ?? linkLookUpProblem(error, serverName: serverName)
            if approving == nil, (error as? APIError)?.status == 429 { refuseCodesForAWhile() }
        }
    }

    /// Too many wrong codes: no code is sent until ``retryAfter`` has passed,
    /// and then the problem it left goes too.
    private func refuseCodesForAWhile() {
        tooManyCodes = true
        cooling?.cancel()
        cooling = Task { [weak self, sleep, retryAfter] in
            do { try await sleep(retryAfter) } catch { return }
            guard let self else { return }
            self.tooManyCodes = false
            if self.problem == linkTooManyCodes { self.problem = nil }
        }
    }
}
