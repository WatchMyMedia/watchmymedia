import Foundation
import WMMModel

/// Add language by name (PLAN-APPLE-SETTINGS.md §5.3; Settings2AddLanguage.dc.html,
/// TvSettings2AddLanguage.dc.html): every language the system can name,
/// A to Z and searchable, the ones already in the list marked Added, and the
/// device's language suggested. Choosing one adds it to the end of the list
/// and saves at once (``SettingsModel/addLanguage(_:to:)``). No codes are typed.
public struct LanguagePicker: Hashable, Sendable {
    public struct Option: Hashable, Sendable, Identifiable {
        /// The BCP 47 tag the list keeps: "en", "pt-BR".
        public var tag: String
        /// "English", "Portuguese (Brazil)".
        public var name: String
        /// Already in the list: shown dimmed with Added, and choosing it does nothing.
        public var added: Bool
        public var id: String { tag }
    }

    public let list: LanguageList
    /// The list as it is now.
    public let chosen: [String]
    public let deviceLanguage: String?
    public let locale: Locale

    public init(list: LanguageList, chosen: [String], deviceLanguage: String? = nil, locale: Locale = .current) {
        self.list = list
        self.chosen = chosen
        self.deviceLanguage = deviceLanguage
        self.locale = locale
    }

    /// "Add an audio language", "Add a subtitle language".
    public var title: String { list == .audio ? "Add an audio language" : "Add a subtitle language" }
    public static let searchPrompt = "Search languages"
    public static let allLanguages = "All languages"
    public static let suggestedHeading = "Suggested"
    public static let footnote = "Choosing a language adds it to the end of your list and saves it at once. You can keep up to 16."
    /// Apple TV's lede over the grid.
    public static let tvLede = "Choose a language to add it to the end of your list. It saves at once. You can keep up to 16."
    public static let tvHint = "Select adds it · hold the microphone to say a language · Menu goes back to Audio & subtitles"
    public static let added = "Added"

    /// Every language, A to Z, with those in the list marked.
    public var options: [Option] {
        LanguageCatalog.sorted(locale: locale).map { Option(tag: $0.tag, name: $0.name, added: isAdded($0.tag)) }
    }

    /// The languages whose name, here or in English, has a word starting with `query`.
    public func options(matching query: String) -> [Option] {
        let all = options
        guard !query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return all }
        return all.filter { LanguageCatalog.matches($0.tag, query: query, locale: locale) }
    }

    /// The device's language at the top, while it isn't in the list.
    public var suggested: Option? {
        guard let tag = deviceLanguage, let match = LanguageCatalog.tags.first(where: { same($0, tag) }), !isAdded(match) else { return nil }
        return Option(tag: match, name: languageTagName(match, locale: locale), added: false)
    }

    /// A list holds at most 16, as the server allows.
    public var canAdd: Bool { chosen.count < maxLanguages }

    /// Why Add language is off, said beside it; nil while it is on.
    public var limitReason: String? { canAdd ? nil : addLanguageLimitReason }

    /// "2 languages", over the results of a search.
    public static func resultsLine(_ count: Int) -> String { plural(count, "language") }

    /// "No language matches “klingo”. Check the spelling, or search for its name in English."
    public static func noMatch(_ query: String) -> String {
        "No language matches “\(query.trimmingCharacters(in: .whitespacesAndNewlines))”. Check the spelling, or search for its name in English."
    }

    public func isAdded(_ tag: String) -> Bool { chosen.contains { same($0, tag) } }

    private func same(_ a: String, _ b: String) -> Bool {
        a.replacingOccurrences(of: "_", with: "-").caseInsensitiveCompare(b.replacingOccurrences(of: "_", with: "-")) == .orderedSame
    }
}

/// Beside Add language when a list holds 16.
public let addLanguageLimitReason = "You can keep up to 16 languages, and this list has 16. Remove one to add another."

extension SettingsModel {
    /// Add language for `list`, with what it holds now marked.
    public func languagePicker(for list: LanguageList, locale: Locale = .current) -> LanguagePicker {
        LanguagePicker(list: list, chosen: languages(list), deviceLanguage: deviceLanguage, locale: locale)
    }

    /// Why Add language is off for `list`; nil while it is on.
    public func addLanguageOffReason(for list: LanguageList) -> String? {
        canAdd(to: list) ? nil : addLanguageLimitReason
    }
}
