import Foundation
import Observation
import WMMModel

// Account & devices:
// Rename as a
// task in a sheet, what each sign-out says, and the page's lines.

/// Renaming this device in a short sheet: Cancel
/// keeps the name, and Rename is off, saying why, until there is a name. The
/// name no longer saves by leaving a page.
@MainActor
@Observable
public final class RenameModel {
    /// The name now, which Cancel keeps.
    public let current: String
    /// "iPhone", "iPad", "Apple TV".
    public let device: String
    /// The name as typed.
    public var name: String

    /// The server keeps at most this many characters of a name.
    public static let maxLength = 64

    public init(current: String, device: String) {
        self.current = current
        self.device = device
        name = current
    }

    /// "Rename this iPhone".
    public var title: String { SettingsWords.renameTitle(device: device) }
    public var buttonTitle: String { SettingsWords.rename }

    /// The name that would be saved: trimmed, and cut to what the server keeps.
    public var cleanName: String { String(name.trimmingCharacters(in: .whitespacesAndNewlines).prefix(Self.maxLength)) }

    public var canRename: Bool { !cleanName.isEmpty }

    /// Why Rename is off: "Rename is off until there is a name. Cancel keeps Sam’s iPhone."
    public var offReason: String? { canRename ? nil : SettingsWords.renameOff(keeping: current) }

    /// Something was typed, so swiping the sheet away asks first.
    public var hasChanges: Bool { name != current }

    /// What swiping the sheet away with a change asks.
    public var discardQuestion: ConfirmWords { SettingsWords.discardTyped(stays: "The name stays \(current).") }
}

extension SettingsWords {
    /// Swiping away a sheet with something typed: "Discard what you typed?"
    public static func discardTyped(stays: String) -> ConfirmWords {
        ConfirmWords(title: "Discard what you typed?", lines: [stays], confirm: "Discard", keep: "Keep typing")
    }
}

extension SettingsModel {
    /// Rename for this device, starting from its name.
    public func renameTask() -> RenameModel { RenameModel(current: deviceName, device: facts.family) }

    /// Rename's button: saves the typed name and says whether the sheet may close
    /// (false while Rename is off). The same name closes without saving anything.
    @discardableResult
    public func rename(_ task: RenameModel) async -> Bool {
        guard task.canRename else { return false }
        await rename(task.cleanName)
        return true
    }

    /// Apple TV's names to choose from, this one's first.
    public var roomNames: [String] { SettingsWords.roomNames(device: facts.family, current: deviceName) }

    /// Account & devices' line under its title: "Sam, an admin on Living room
    /// server. 4 devices are signed in.", counting this account's devices once loaded.
    public var accountLede: String {
        SettingsWords.accountLede(user: user, serverName: server?.name, devices: accountDeviceCount, device: facts.family)
    }

    /// Under This iPhone's name: "iPhone 16 · iOS 27.1 · WatchMyMedia 1.8.6. The name shows …".
    public var thisDeviceLine: String {
        SettingsWords.thisDeviceLine(model: facts.model, system: facts.system, appVersion: facts.appVersionLabel)
    }

    /// The other devices signed in as this account, by name: those a new password signs out.
    public var otherDevices: [DeviceSession] {
        (sessions.value ?? []).filter { !$0.current && $0.userName == user?.name }
    }

    /// What signing another device out asks, naming the person when it is someone else's.
    public func signOutWords(for session: DeviceSession) -> ConfirmWords {
        SettingsWords.signOutDevice(session, me: user?.name)
    }

    /// What signing this device out asks: what stays on the server, what is
    /// deleted from the device with its count and size (`downloads`, `bytes`,
    /// from the device's downloads), and what signing in again needs. On
    /// Apple TV, which keeps no downloads, its own words.
    public func signOutThisDeviceWords(downloads: Int = 0, bytes: Int64 = 0) -> ConfirmWords {
        facts.family == "Apple TV"
            ? SettingsWords.signOutThisAppleTV(serverName: server?.name)
            : SettingsWords.signOutThisDevice(serverName: server?.name, device: facts.family, downloads: downloads, bytes: bytes)
    }

    /// The red row's title and the line under it: "Sign out of this iPhone",
    /// "Deletes this iPhone’s 7 downloads. Your account, watch history and settings stay on the server."
    public func signOutThisDeviceRow(downloads: Int = 0) -> (title: String, line: String) {
        (SettingsWords.signOutOfThis(facts.family), SettingsWords.signOutOfThisLine(device: facts.family, downloads: downloads))
    }
}
