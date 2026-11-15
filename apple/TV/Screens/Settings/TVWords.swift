import Foundation
import WMMFeatures
import WMMModel

// Settings' words on Apple TV (PLAN-APPLE-SETTINGS.md §6): the web's, from
// web/src/lib/youSettings.ts and the boards. Kept here until the Kit has
// them for both apps; each goes when the Kit's takes its place.

/// The groups in the list at the left, in the web's order (§5.9).
enum SettingsSection: String, CaseIterable, Hashable, Identifiable {
    case playback, languages, homeRows, account, requests, server, about

    var id: String { rawValue }
}

extension SkipMode {
    /// The web's order: Do nothing, Show a Skip button, Skip after 3 seconds, Skip right away.
    static let webOrder: [SkipMode] = [.none, .ask, .autoDelay, .auto]

    /// "Show a Skip button".
    var choiceTitle: String {
        switch self {
        case .none: "Do nothing"
        case .ask: "Show a Skip button"
        case .autoDelay: "Skip after 3 seconds"
        case .auto: "Skip right away"
        }
    }

    /// The line under a choice in its menu.
    var choiceLine: String {
        switch self {
        case .none: "The part plays through"
        case .ask: "For parts 3 seconds or longer"
        case .autoDelay: "Shows the button, then skips by itself"
        case .auto: "Not for a part you seek into"
        }
    }

    /// "skip after 3 seconds", after "Credits now".
    var does: String {
        switch self {
        case .none: "play through"
        case .ask: "show a Skip button"
        case .autoDelay: "skip after 3 seconds"
        case .auto: "skip right away"
        }
    }

    /// What the choice does, for the panel at the right.
    var explanation: String {
        switch self {
        case .none: "Do nothing shows no button; the part plays through."
        case .ask: "Show a Skip button shows one for parts 3 seconds or longer."
        case .autoDelay: "Skip after 3 seconds shows the Skip button, then skips by itself."
        case .auto: "Skip right away skips the part as it starts, but not while the Next Episode card is up."
        }
    }
}

extension SegmentKind {
    /// "Preview", the web's name for a part.
    var partTitle: String {
        switch self {
        case .intro: "Intro"
        case .recap: "Recap"
        case .credits: "Credits"
        case .preview: "Preview"
        case .commercial: "Ads"
        }
    }

    /// "credits", after "now" in the Saved line.
    var plural: String {
        switch self {
        case .intro: "intros"
        case .recap: "recaps"
        case .credits: "credits"
        case .preview: "previews"
        case .commercial: "ads"
        }
    }

    /// What the part is, for the panel at the right.
    var about: String {
        switch self {
        case .intro: "The opening titles and theme of an episode."
        case .recap: "The “Previously on…” at the start of an episode."
        case .credits: "The end titles of an episode or a film."
        case .preview: "The “Next time on…” after an episode."
        case .commercial: "Commercial breaks left in recordings."
        }
    }
}

enum TVWords {
    static let keptOnServer = "Kept on the server, so every device you sign in to follows it."

    /// "Credits now skip after 3 seconds, on every device."
    static func skipSaved(_ kind: SegmentKind, _ mode: SkipMode) -> String {
        "\(kind.plural.prefix(1).uppercased())\(kind.plural.dropFirst()) now \(mode.does), on every device."
    }

    static func autoplaySaved(_ on: Bool) -> String {
        on ? "The next episode now plays by itself, on every device." : "The Next Episode card now waits for you, on every device."
    }

    static func leadSaved(_ minutes: Int) -> String {
        "Reminders now come \(liveLeadChoice(minutes).lowercased()), on every device."
    }

    static func qualitySaved(_ quality: StreamQuality) -> String {
        "Videos on this Apple TV now start at \(quality.checklistTitle)."
    }

    static func renamed(_ name: String) -> String { "This Apple TV is now \(name)." }

    /// Why a choice that saves at once didn't, and what it still is.
    static func notSaved(still: String) -> String {
        "Couldn’t save, so it’s still \(still). Check the connection and choose again."
    }

    /// "4 devices signed in".
    static func devicesSignedIn(_ n: Int) -> String { "\(plural(n, "device")) signed in" }

    /// The page's line under its title (Account & devices).
    static func accountLede(user: User?, serverName: String?, devices: Int?) -> String {
        guard let user else { return "" }
        let who = user.isAdmin ? "\(user.name), an admin" : user.name
        let on = serverName.map { " on \($0)" } ?? ""
        let count = devices.map { $0 == 1 ? " 1 device is signed in." : " \(formatCount($0)) devices are signed in." } ?? ""
        return "\(who)\(on).\(count)"
    }

    /// The rooms Name this Apple TV offers, so most people never type.
    static let rooms = ["Living room", "Bedroom", "Kitchen", "Office", "Den", "Basement", "Kids’ room", "Guest room"]

    /// The languages Add a language offers by name, as the board lists them.
    static let languages = ["ar", "zh-Hans", "zh-Hant", "nl", "en", "fr", "de", "hi", "it", "ja", "ko", "pl", "pt-BR", "pt-PT",
                            "pa", "ru", "es", "es-419", "sv", "tr", "ur", "vi", "cy", "zu"]

    /// Where the web app changes the password, under the server's address.
    static func changePasswordURL(_ server: Server) -> URL { server.baseURL.appending(path: "settings/account/password") }

    /// Where the web app's Settings are, for an admin.
    static func serverSettingsURL(_ server: Server) -> URL { server.baseURL.appending(path: "settings") }

    /// "home.example.net/settings": an address as people type it.
    static func shown(_ url: URL) -> String {
        var s = url.absoluteString
        for prefix in ["https://", "http://"] where s.hasPrefix(prefix) { s.removeFirst(prefix.count) }
        return s
    }
}
