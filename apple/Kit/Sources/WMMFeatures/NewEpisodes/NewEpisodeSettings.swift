import Foundation
import Observation
import WMMModel

/// How Settings › Notifications' "On this iPhone" section stands, from iOS's
/// answer, Background App Refresh and this device's switch (the boards'
/// `settings`): refused outranks Background App Refresh; while iOS refuses,
/// the switch shows off, dimmed, keeping the person's choice for when iOS
/// allows them again; while iOS hasn't asked, it shows off and turning it on
/// asks.
public struct NewEpisodeSection: Hashable, Sendable {
    public enum Notice: Hashable, Sendable {
        /// "Notifications are off for WatchMyMedia. Turn them on in Settings." with Open Settings.
        case refused
        /// "Background App Refresh is off, …" with Open Settings.
        case backgroundRefreshOff

        public var line: String {
            switch self {
            case .refused: NewEpisodeWords.systemRefused
            case .backgroundRefreshOff: NewEpisodeWords.backgroundRefreshOff
            }
        }
    }

    /// The switch as drawn.
    public var switchOn: Bool
    /// False while iOS refuses: only iOS Settings can change it then.
    public var switchEnabled: Bool
    public var notice: Notice?
    /// The limits line under the switch; left out while iOS refuses.
    public var showsLimits: Bool

    init(switchOn: Bool, switchEnabled: Bool, notice: Notice?, showsLimits: Bool) {
        self.switchOn = switchOn
        self.switchEnabled = switchEnabled
        self.notice = notice
        self.showsLimits = showsLimits
    }

    public init(permission: NotificationPermission, backgroundRefresh: Bool, notifyHere: Bool) {
        switchOn = permission == .allowed && notifyHere
        switchEnabled = permission != .refused
        notice = permission == .refused ? .refused : backgroundRefresh ? nil : .backgroundRefreshOff
        showsLimits = permission != .refused
    }
}

/// The show page's line under the actions while the bell is on and iOS
/// refuses: not while iOS hasn't asked, and not when this device's switch is
/// off, which was the person's choice.
public func bellShowsSystemRefused(following: Bool, permission: NotificationPermission, notifyHere: Bool) -> Bool {
    following && permission == .refused && notifyHere
}

/// This device's New episodes switch (Settings › Notifications): kept on the
/// device, so it can't fail; says Saved with what the change did for a few
/// seconds. Turned on while iOS hasn't asked,
/// it asks iOS at once, with no explainer; Don’t Allow leaves it off.
@MainActor @Observable
public final class NewEpisodeSwitch {
    @ObservationIgnored private let memory: NewEpisodeMemory
    @ObservationIgnored private let device: String
    private let saves: SaveStates<Int>

    /// - Parameters:
    ///   - device: "iPhone" or "iPad", as the Saved line names it.
    ///   - sleep: waits out how long Saved stays; tests pass a clock they move.
    public init(memory: NewEpisodeMemory, device: String, linger: Duration = .seconds(4),
                sleep: @escaping @MainActor (Duration) async throws -> Void = { try await Task.sleep(for: $0) }) {
        self.memory = memory
        self.device = device
        saves = SaveStates(linger: linger, sleep: sleep)
    }

    /// The switch's line: Saved's for a few seconds, else nil (its usual line).
    public var save: SaveState { saves[0] }

    /// The person turned the switch. `ask` is iOS's question, asked only while
    /// it hasn't been; it answers what iOS says after. Returns whether the
    /// device now notifies, so the app can check at once (and start from now).
    @discardableResult
    public func set(_ on: Bool, permission: NotificationPermission, ask: @MainActor () async -> NotificationPermission) async -> Bool {
        var permission = permission
        if on, permission == .notAsked { permission = await ask() }
        guard permission == .allowed else { return false }
        let token = saves.begin(0)
        memory.notifyHere = on
        if !on { memory.forgetCursor() }
        saves.saved(0, token, NewEpisodeWords.switchSaved(on, device: device))
        return on
    }
}
