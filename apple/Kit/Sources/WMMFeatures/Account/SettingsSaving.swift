import Foundation
import Observation
import WMMModel

/// Where one control kept on the server stands (PLAN-APPLE-SETTINGS.md §3.1):
/// at rest, being sent, saved with a sentence saying what the change did, or
/// failed with the control back at the saved value and a sentence naming it.
/// Only that control's row waits or speaks; Saved goes back to rest after a
/// few seconds, a failure stays until the control is changed again.
public enum SaveState: Hashable, Sendable {
    case rest
    case saving
    /// "Saved. Credits now skip after 3 seconds, on every device."
    case saved(String)
    /// "Couldn’t save, so it’s still Show a Skip button. Check your connection and choose again."
    case failed(String)

    public var isSaving: Bool { self == .saving }

    /// The row's line in place of its usual one: Saved's or the failure's; nil at rest and while saving.
    public var line: String? {
        switch self {
        case .saved(let line), .failed(let line): line
        case .rest, .saving: nil
        }
    }

    public var savedLine: String? { if case .saved(let line) = self { line } else { nil } }
    public var failedLine: String? { if case .failed(let line) = self { line } else { nil } }
    public var isFailed: Bool { failedLine != nil }
}

/// Each control on Settings whose value is kept on the server, so each saves,
/// and says so, on its own.
public enum SettingKey: Hashable, Sendable {
    case autoplayNext
    case skip(SegmentKind)
    /// Live TV reminders' Remind me (``LiveExtrasStore/leadSave``).
    case reminderLead
    case audioLanguages
    case subtitleChoice
    case subtitleLanguages
    case automaticDownload
    /// Signing another device out, said under Signed-in devices.
    case signedInDevices
}

/// How a failed control asks to be tried again: a choice is chosen again, a switch or a list tried again.
public enum SaveRetry: Hashable, Sendable {
    case choose, tryAgain

    var words: String { self == .choose ? "choose again" : "try again" }
}

/// Why a save failed, with the control back as it was (the web's
/// `choiceSaveError`). `still` is the clause that names what stayed: "it’s
/// still Show a Skip button", "your audio languages are still English and
/// Urdu". A 422 is a value the server doesn't know, so this app is older
/// than the server; a 429 is too many changes at once.
public func saveFailed(_ error: any Error, still: String, retry: SaveRetry = .choose) -> String {
    switch (error as? APIError)?.status {
    case 422?: "The server didn’t accept this choice, so \(still). This app may be older than the server: update it and \(retry.words)."
    case 429?: "Too many changes at once, so \(still). Wait a moment and \(retry.words)."
    default: "Couldn’t save, so \(still). Check your connection and \(retry.words)."
    }
}

/// ``saveFailed(_:still:retry:)`` for a control showing one value: "Couldn’t
/// save, so it’s still Show a Skip button. Check your connection and choose again."
public func saveFailed(_ error: any Error, stillValue value: String, retry: SaveRetry = .choose) -> String {
    saveFailed(error, still: "it’s still \(value)", retry: retry)
}

/// Each control's ``SaveState``, by key, with Saved going back to rest after
/// ``linger`` through the sleep it was given, so tests move a clock rather
/// than wait. Only the newest save of a control speaks.
@MainActor
@Observable
public final class SaveStates<Key: Hashable & Sendable> {
    private var states: [Key: SaveState] = [:]
    @ObservationIgnored private var tokens: [Key: Int] = [:]
    @ObservationIgnored private var counter = 0
    @ObservationIgnored private var resting: [Key: Task<Void, Never>] = [:]
    @ObservationIgnored public let linger: Duration
    @ObservationIgnored private let sleep: @MainActor (Duration) async throws -> Void

    /// - Parameters:
    ///   - linger: how long Saved stays before the row's usual line returns.
    ///   - sleep: waits out `linger`; tests pass a clock they move.
    public init(linger: Duration = .seconds(4), sleep: @escaping @MainActor (Duration) async throws -> Void = { try await Task.sleep(for: $0) }) {
        self.linger = linger
        self.sleep = sleep
    }

    public subscript(_ key: Key) -> SaveState { states[key] ?? .rest }

    /// Starts a save of `key`; the token says whether it is still the newest when it ends.
    func begin(_ key: Key) -> Int {
        counter += 1
        tokens[key] = counter
        resting[key]?.cancel()
        resting[key] = nil
        states[key] = .saving
        return counter
    }

    func isCurrent(_ key: Key, _ token: Int) -> Bool { tokens[key] == token }

    func saved(_ key: Key, _ token: Int, _ line: String) {
        guard isCurrent(key, token) else { return }
        states[key] = .saved(line)
        resting[key] = Task { [weak self, sleep, linger] in
            do { try await sleep(linger) } catch { return }
            guard let self, self.isCurrent(key, token), self.states[key]?.savedLine != nil else { return }
            self.states[key] = .rest
            self.resting[key] = nil
        }
    }

    func failed(_ key: Key, _ token: Int, _ line: String) {
        guard isCurrent(key, token) else { return }
        states[key] = .failed(line)
    }

    /// Back to rest at once: a save that changed nothing, or one cancelled.
    func rest(_ key: Key, _ token: Int) {
        guard isCurrent(key, token) else { return }
        states[key] = .rest
    }

    /// Forgets every control's state (another server or account).
    func clearAll() {
        for task in resting.values { task.cancel() }
        resting = [:]
        tokens = [:]
        states = [:]
    }
}
