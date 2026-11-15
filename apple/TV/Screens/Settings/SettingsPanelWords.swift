import Foundation
import WMMFeatures
import WMMModel

// The words of the panel at the right of Apple TV's Settings (what the
// focused setting does) and of its hints, where the Kit's `SettingsWords`
// has none; the rest of Settings' words are the Kit's.

enum PanelWords {
    static let hintOpens = "Select opens it · Menu goes back to the list"
    static let hintRenames = "Select renames it · Menu goes back to the list"
    static let hintTriesAgain = "Select tries again · Menu goes back to the list"
    static let hintShowsCode = "Select shows the code · Menu goes back to the list"
    static let hintCodeLarger = "Select shows the code larger · Menu goes back to the list"

    static func qualityHere(device: String) -> String { "Remembered on this \(device) alone; the rest of Playback follows your account." }
    static let leadAbout = "How long before a programme you asked to be reminded of."
    static let remindersAbout = "The programmes you asked to be reminded of, by day. Select one to find it in the guide, or remove it."
    static let savesAtOnce = "Each change saves at once, on every device."
    static let subtitlesUseAudio = "Until you add one, subtitles use your audio languages."

    static func automaticDownloadAbout(available: Bool, audioLanguages: Bool) -> String {
        if available {
            return "Only an exact-file match in your first subtitle language, when no local track matches. Uses the server’s OpenSubtitles quota and download permissions."
        }
        return audioLanguages
            ? "Choose On or When the audio isn’t in my languages, and add a subtitle language."
            : "Choose On and add a subtitle language."
    }

    static let chooseARoom = "Choose a room, or type another name."
    static let everyoneAbout = "On, the list shows every account’s devices, so you can sign any of them out. Off, only yours."
    static let devicesDidNotLoad = "The list didn’t load. Nothing was signed out."

    static func passwordAbout(device: String) -> [String] {
        [
            "Typing a password with the remote is slow, so Select shows a code to scan with your phone. It opens Change password in the web app.",
            "Every device but the one you change it on is signed out, this \(device) too. It signs in again with a code.",
        ]
    }

    static let requestsAbout = "What you asked for, and where it is. Select one that waits to cancel it."
    static let privacyAbout = [
        "WatchMyMedia talks only to your own server, and sends nothing to anyone else.",
        "Select shows a code to read the policy on your phone, with the credits and licences.",
    ]

    static func diagnosticsAbout(device: String) -> [String] {
        [
            "What the app can give you for a bug report, as a code to scan with your phone.",
            "Nothing is sent from this \(device). No account, server address or titles are included.",
        ]
    }

    /// Add a language's hint: Select adds, and the search field takes the microphone.
    static func addLanguageHint(back: String) -> String {
        "Select adds it · in Search languages, hold the microphone to say one · Menu goes back to \(back)"
    }
}
