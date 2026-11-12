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

    /// `code` comes from a watchmymedia://link?code= link (a scanned QR code).
    public init(auth: any AuthRepository, code: String? = nil) {
        self.auth = auth
        self.code = normalizePairingCode(code ?? "")
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
        step = .enter
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
        await request { try await self.auth.lookUpPairing(code: code) }
    }

    /// Approve: signs the TV in as this account.
    public func approve() async {
        guard case .found(let device) = step else { return }
        await request { try await self.auth.approvePairing(code: device.code) }
    }

    private func request(_ call: () async throws -> PendingDevice) async {
        guard !busy else { return }
        busy = true
        error = nil
        defer { busy = false }
        do {
            let device = try await call()
            step = device.approved ? .approved(device) : .found(device)
        } catch is CancellationError {
        } catch {
            self.error = message(error, fallback: "Couldn't check that code. Try again.")
        }
    }
}
