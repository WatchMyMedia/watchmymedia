import Foundation
import Observation
import WMMModel

/// Change password on iPhone and iPad (PLAN-APPLE-SETTINGS.md Decision 9,
/// §5.4; Settings2Password.dc.html; the web's ChangePassword.tsx): one step
/// that names the other devices it signs out and puts their count on its
/// button. Each refusal says nothing was changed. Once changed, the page's
/// devices load again and a banner says what happened (``SettingsModel/accountBanner``).
/// Apple TV hands it to a phone (``changePasswordURL(_:)``).
@MainActor
@Observable
public final class ChangePasswordModel {
    /// The password now; typing clears what was said about it.
    public var current = "" {
        didSet { if current != oldValue { currentProblem = nil; problem = nil } }
    }

    /// The new password; 8 to 256 characters, as the server allows.
    public var newPassword = "" {
        didSet { if newPassword != oldValue { serverNewProblem = nil; problem = nil } }
    }

    public private(set) var busy = false
    /// Under Current password: it is wrong, or missing.
    public private(set) var currentProblem: String?
    /// Above the button: too many tries, the server unreachable, or another refusal.
    public private(set) var problem: SettingsNotice?
    /// Once changed: the banner Account & devices shows. The sheet closes.
    public private(set) var finished: SettingsNotice?
    /// The account's other signed-in devices, which the change signs out; nil when they couldn't be listed.
    public let others: [DeviceSession]?

    @ObservationIgnored private var serverNewProblem: String?
    @ObservationIgnored private let auth: any AuthRepository
    @ObservationIgnored private let device: String
    @ObservationIgnored private let serverName: String?
    @ObservationIgnored private let onChanged: @MainActor (SettingsNotice) async -> Void

    public static let minLength = 8
    public static let maxLength = 256

    /// - Parameters:
    ///   - others: the account's other devices by name, or nil when the list didn't load.
    ///   - device: "iPhone" or "iPad", which stays signed in.
    ///   - onChanged: runs once the password is changed, with the banner.
    public init(auth: any AuthRepository, others: [DeviceSession]?, device: String, serverName: String?,
                onChanged: @escaping @MainActor (SettingsNotice) async -> Void = { _ in }) {
        self.auth = auth
        self.others = others
        self.device = device
        self.serverName = serverName
        self.onChanged = onChanged
    }

    public static let title = "Change password"
    public static let currentLabel = "Current password"
    public static let newLabel = "New password"
    public static let newHint = "8 characters or more."

    /// Under New password once something is typed: too short or too long,
    /// or what the server said of it.
    public var newProblem: String? {
        if let serverNewProblem { return serverNewProblem }
        if newPassword.isEmpty { return nil }
        if newPassword.count < Self.minLength { return "Use 8 characters or more." }
        if newPassword.count > Self.maxLength { return "Use 256 characters or fewer." }
        return nil
    }

    /// The button is on once both are typed and the new one is long enough.
    public var canSubmit: Bool {
        !busy && !current.isEmpty && (Self.minLength...Self.maxLength).contains(newPassword.count)
    }

    /// "Change password and sign out 3 devices", or "Change password" when no other device is known.
    public var buttonTitle: String {
        let n = others?.count ?? 0
        return n == 0 ? "Change password" : "Change password and sign out \(plural(n, "device"))"
    }

    /// "Signs out 3 other devices", "No other device is signed in"; nil when they couldn't be listed.
    public var othersHeading: String? {
        guard let others else { return nil }
        return others.isEmpty ? "No other device is signed in" : "Signs out \(plural(others.count, "other device"))"
    }

    /// "Living room TV, Kitchen iPad and Chrome on macOS."
    public var othersNames: String? {
        guard let others, !others.isEmpty else { return nil }
        return andList(others.map(\.name)) + "."
    }

    /// "This iPhone stays signed in. The others need the new password, or a code, to sign in again."
    public var staysLine: String {
        others?.isEmpty == true
            ? "This \(device) stays signed in."
            : "This \(device) stays signed in. The others need the new password, or a code, to sign in again."
    }

    /// When the devices couldn't be listed.
    public var othersUnknown: SettingsNotice? {
        others == nil ? SettingsNotice(title: "Couldn’t list your devices",
                                       body: "Every device except this \(device) is still signed out when you change your password.") : nil
    }

    /// Something is typed, so swiping the sheet away asks first.
    public var hasChanges: Bool { !current.isEmpty || !newPassword.isEmpty }

    public var discardQuestion: ConfirmWords { SettingsWords.discardTyped(stays: "Your password hasn’t been changed.") }

    /// Changes the password, or says why not; on success ``finished`` holds the banner.
    public func submit() async {
        guard !busy, finished == nil else { return }
        guard !current.isEmpty else {
            currentProblem = "Enter your current password."
            return
        }
        guard canSubmit else { return }
        busy = true
        problem = nil
        currentProblem = nil
        defer { busy = false }
        do {
            try await auth.changePassword(current: current, new: newPassword)
            let banner = passwordChangedBanner(others: others?.count, device: device)
            current = ""
            newPassword = ""
            finished = banner
            await onChanged(banner)
        } catch is CancellationError {
        } catch {
            switch (error as? APIError)?.status {
            case 401?: currentProblem = "That password is wrong. Nothing was changed."
            case 400?, 422?: serverNewProblem = "\(error.displayMessage) Nothing was changed."
            case 429?: problem = SettingsNotice(title: "Too many tries", body: "Try again in a minute. Nothing was changed.")
            case nil, (500...)?:
                problem = SettingsNotice(title: "Couldn’t reach \(serverName ?? "the server")",
                                         body: "Your password wasn’t changed. Check your connection and try again.")
            default: problem = SettingsNotice(title: "Your password wasn’t changed", body: error.displayMessage)
            }
        }
    }
}

/// The banner on Account & devices after a change: "3 other devices were
/// signed out and need the new password, or a code, to sign in again. This
/// iPhone stays signed in."
public func passwordChangedBanner(others: Int?, device: String) -> SettingsNotice {
    let stays = "This \(device) stays signed in."
    let body: String = switch others {
    case nil: "Every other device was signed out and needs the new password, or a code, to sign in again. \(stays)"
    case 0?: "No other device was signed in. Use the new password next time you sign in."
    case 1?: "1 other device was signed out and needs the new password, or a code, to sign in again. \(stays)"
    case let n?: "\(formatCount(n)) other devices were signed out and need the new password, or a code, to sign in again. \(stays)"
    }
    return SettingsNotice(title: "Password changed", body: body)
}

/// Where Apple TV sends Change password: the web app's own, as a code to scan.
public func changePasswordURL(_ server: Server) -> URL {
    server.baseURL.appending(path: "settings/account/password")
}

extension SettingsWords {
    /// Apple TV's row (TvSettings2Account.dc.html).
    public static let changePasswordTVLine = "Typing a password with the remote is slow, so this shows a code to scan"
    public static let changePasswordTVValue = "On your phone or computer"

    /// Apple TV's page with the code: where to change it, and that it signs this Apple TV out too.
    public static func changePasswordOnAPhone(_ server: Server) -> SettingsNotice {
        SettingsNotice(
            title: "Change your password on your phone",
            body: "Scan the code to change it in the web app, or go to \(changePasswordURL(server).absoluteString.replacingOccurrences(of: "https://", with: "").replacingOccurrences(of: "http://", with: "")) in a browser. Changing it signs out every other device, this Apple TV too: it will need a new code, or the new password, to sign in again."
        )
    }
}

extension SettingsModel {
    /// Change password for this account, listing the other devices it signs
    /// out (nil until the list has loaded). Once changed, ``accountBanner``
    /// says so and the devices load again.
    public func changePasswordTask() -> ChangePasswordModel {
        ChangePasswordModel(
            auth: auth, others: sessions.value == nil ? nil : otherDevices, device: facts.family, serverName: server?.name,
            onChanged: { [weak self] banner in
                self?.showAccountBanner(banner)
                await self?.loadSessions()
            }
        )
    }
}
