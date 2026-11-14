import Foundation
import Observation
import WMMModel

/// Ch− and Ch+, Page Up and Page Down, and the arrows on a keyboard: which
/// channel is next (PLAN-APPLE-REVIEW.md §6.2). The channel list's order,
/// only channels that can be watched now, wrapping at either end.
public enum LiveChannelStep {
    /// The channel `delta` steps from `currentID`. A current channel that
    /// can't be watched now (it went off the air) counts from its place in
    /// the list, so Ch+ goes to the one after it, not to the list's second.
    /// Nil when there is nowhere else to go.
    public static func neighbour(of currentID: String?, in channels: [LiveChannel], by delta: Int) -> LiveChannel? {
        let playable = channels.filter(\.available)
        guard !playable.isEmpty, delta != 0 else { return nil }
        let count = playable.count
        let wrap = { (i: Int) in playable[(i % count + count) % count] }
        let target: LiveChannel
        if let index = playable.firstIndex(where: { $0.id == currentID }) {
            target = wrap(index + delta)
        } else {
            // Not watchable now, or unknown: it sits between the playable
            // channels around its place in the list (before them all when unknown).
            let place = channels.firstIndex { $0.id == currentID } ?? -1
            let before = playable.filter { p in (channels.firstIndex { $0.id == p.id } ?? 0) < place }.count
            target = wrap(delta > 0 ? before + delta - 1 : before + delta)
        }
        return target.id == currentID ? nil : target
    }
}

/// Go to channel (TvLiveChannelKeys.dc.html's `goTo`): a keypad and the
/// channels whose number starts with what was typed. The first match tunes
/// two seconds after the last key or move between keys, unless focus went to
/// the matches to choose one, which tunes at once. Digit keys on the remote
/// type too.
@MainActor
@Observable
public final class GoToChannelModel {
    /// What was typed: digits and dots, "7.".
    public private(set) var typed = ""
    /// Long enough for any channel number ("123.45").
    public static let maxLength = 7
    /// How long after the last key the first match tunes.
    public static let waitMs = 2_000
    /// At most this many matches show.
    public static let maxMatches = 5

    public static let title = "Go to channel"
    public static let matchesHeading = "Matching channels"
    public static let help = "Tunes the first match after two seconds, or press Select. Digit keys type here; Back closes."
    /// The keypad, in the order it is drawn: three across.
    public static let keys: [Character] = ["1", "2", "3", "4", "5", "6", "7", "8", "9", ".", "0", "⌫"]

    @ObservationIgnored private let channels: @MainActor () -> [LiveChannel]
    @ObservationIgnored private let tune: @MainActor (LiveChannel) -> Void
    @ObservationIgnored private let sleep: @MainActor (Duration) async throws -> Void
    @ObservationIgnored private var wait: Task<Void, Never>?

    /// `tune` switches to the channel and closes the keypad; `sleep` waits (tests pass their own).
    public init(
        channels: @escaping @MainActor () -> [LiveChannel],
        tune: @escaping @MainActor (LiveChannel) -> Void,
        sleep: (@MainActor (Duration) async throws -> Void)? = nil
    ) {
        self.channels = channels
        self.tune = tune
        self.sleep = sleep ?? { try await Task.sleep(for: $0) }
    }

    /// The channels that can be watched whose number starts with what was
    /// typed ("7." finds 7.1, 7.2 and 7.3), an exact match first, then in
    /// the list's order. A dash in a number reads as a dot. None before a key.
    public var matches: [LiveChannel] {
        guard !typed.isEmpty else { return [] }
        let found = channels().filter { $0.available && Self.normalised($0.number).hasPrefix(typed) }
        let exact = found.filter { Self.normalised($0.number) == typed }
        let rest = found.filter { Self.normalised($0.number) != typed }
        return Array((exact + rest).prefix(Self.maxMatches))
    }

    /// "No channel starts with 7.": typed, and nothing matches.
    public var emptyNote: String? {
        guard !typed.isEmpty, matches.isEmpty else { return nil }
        return "No channel starts with \(typed)"
    }

    /// One of the keypad's keys, or a digit key on the remote: types it
    /// (⌫ deletes) and waits two seconds again.
    public func press(_ key: Character) {
        switch key {
        case "⌫": delete()
        case "0"..."9", ".":
            guard typed.count < Self.maxLength, !(key == "." && (typed.isEmpty || typed.contains("."))) else { return }
            typed.append(key)
            restartWait()
        default:
            break
        }
    }

    public func delete() {
        guard !typed.isEmpty else { return }
        typed.removeLast()
        restartWait()
    }

    /// Tunes `channel` now.
    public func choose(_ channel: LiveChannel) {
        cancel()
        tune(channel)
    }

    /// Tunes the first match now, if there is one.
    public func confirm() {
        guard let first = matches.first else { return }
        choose(first)
    }

    /// Focus moved to another key: the person is still typing, so the two
    /// seconds start again.
    public func moved() {
        guard wait != nil else { return }
        restartWait()
    }

    /// Focus reached the matches: the person is choosing one, so nothing
    /// tunes by itself until another key.
    public func hold() {
        cancel()
    }

    /// The keypad closed: nothing tunes.
    public func cancel() {
        wait?.cancel()
        wait = nil
    }

    private func restartWait() {
        wait?.cancel()
        guard !matches.isEmpty else { wait = nil; return }
        let sleep = sleep
        wait = Task { [weak self] in
            do { try await sleep(.milliseconds(Self.waitMs)) } catch { return }
            guard !Task.isCancelled else { return }
            self?.confirm()
        }
    }

    private static func normalised(_ number: String) -> String {
        number.replacingOccurrences(of: "-", with: ".")
    }
}
