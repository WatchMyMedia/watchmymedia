import Foundation
import WMMModel

/// Saves the fake preferences refuse, as a server without a connection or a
/// newer one would, so a Settings row's failed line can be seen and tested
/// (PLAN-APPLE-SETTINGS.md §3.1; `-fakesSaveFails` in a debug build).
public struct FakeSaveRefusal: Hashable, Sendable {
    /// What is refused: a Skipping part (`intro`, `recap`, `credits`,
    /// `preview`, `commercial` or `ads`), `autoplay`, `languages` (every
    /// Audio & subtitles change) or one part of it (`audioLanguages`,
    /// `subtitleChoice`, `subtitleLanguages`, `automaticDownload`), or `all`.
    public var parts: Set<String>
    /// nil: the server didn't answer. 422: it didn't know the value, so the
    /// row says the app may be older than the server. Any other status as given.
    public var status: Int?

    public init(parts: Set<String>, status: Int? = nil) {
        self.parts = parts
        self.status = status
    }

    /// From a launch argument's value: `recap` (unanswered), `recap:422`,
    /// `recap,autoplay:offline`, `all:422`. Nil for an empty one.
    public init?(argument: String) {
        let halves = argument.split(separator: ":", maxSplits: 1).map(String.init)
        let parts = Set((halves.first ?? "").split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty })
        guard !parts.isEmpty else { return nil }
        self.parts = parts
        status = halves.count > 1 ? Int(halves[1]) : nil
    }

    /// Whether a save of `part` (and of `group`, the part it belongs to) is refused.
    public func refuses(_ part: String, in group: String? = nil) -> Bool {
        let aliases: [String: String] = ["ads": "commercial", "autoplayNext": "autoplay"]
        let named = Set(parts.map { aliases[$0] ?? $0 })
        return named.contains("all") || named.contains(part) || group.map(named.contains) == true
    }

    /// What the refused save throws.
    public var error: APIError {
        switch status {
        case nil: APIError(status: nil, message: "Couldn't reach the server. Check the connection and try again.")
        case 422?: APIError(status: 422, message: "The server doesn’t know that choice.")
        case 429?: APIError(status: 429, message: "Too many attempts. Wait a while and try again.")
        case let code?: APIError(status: code, message: "The server refused the request (\(code)).")
        }
    }
}

extension FakeAuthRepository {
    /// What the next Change password throws for a launch argument's value:
    /// `401` (a wrong current password), `429` (too many tries), `offline`
    /// (the server didn't answer), or another status. Nil for anything else.
    public static func passwordFailure(argument: String) -> APIError? {
        switch argument.lowercased() {
        case "401": APIError(status: 401, message: "Current password is wrong.")
        case "429": APIError(status: 429, message: "Too many attempts. Wait a while and try again.")
        case "offline", "unreachable": APIError(status: nil, message: "Couldn't reach the server. Check the connection and try again.")
        default: Int(argument).map { APIError(status: $0, message: "The server refused the request (\($0)).") }
        }
    }
}

extension AppServices {
    /// Sets the fakes' refusals from launch arguments, for a debug build:
    /// `-fakesSaveFails <parts>[:<status>]` has the preferences refuse those
    /// saves every time (``FakeSaveRefusal/init(argument:)``), and
    /// `-fakesPasswordFails <401|429|offline|status>` the next Change
    /// password (``FakeAuthRepository/passwordFailure(argument:)``).
    /// ``fake(signedIn:chosen:client:admin:seerr:seerrLinked:liveTV:liveGuide:downloads:apiLevel:recordings:liveAutomatic:rows:)``
    /// applies the process's own, so both apps take them with no change to their factories.
    public func applyFakeRefusals(_ arguments: [String]) {
        func value(_ name: String) -> String? {
            guard let i = arguments.firstIndex(of: name), arguments.indices.contains(i + 1), !arguments[i + 1].hasPrefix("-") else { return nil }
            return arguments[i + 1]
        }
        if let save = value("-fakesSaveFails").flatMap(FakeSaveRefusal.init(argument:)) {
            (preferences as? FakePreferencesRepository)?.refusal = save
        }
        if let password = value("-fakesPasswordFails").flatMap(FakeAuthRepository.passwordFailure(argument:)) {
            (auth as? FakeAuthRepository)?.passwordFailure = password
        }
    }
}
