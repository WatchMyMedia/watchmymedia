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

    /// `code` comes from a watchmymedia://link?code= link (a scanned QR code).
    public init(auth: any AuthRepository, code: String? = nil, serverName: String? = nil) {
        self.auth = auth
        self.code = normalizePairingCode(code ?? "")
        self.serverName = serverName
    }

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
        }
    }
}
