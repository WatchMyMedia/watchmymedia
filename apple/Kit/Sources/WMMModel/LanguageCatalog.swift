import Foundation

/// Every language a person can add to their audio or subtitle languages,
/// named by the system, so no list is shipped or fetched and no code is
/// typed. It is the languages the system
/// has locale data for, with the ISO 639-1 ones (Latin, Norwegian, Aymara)
/// that files are tagged with too, and the written varieties people choose
/// between: Portuguese and Chinese only as their variants, Spanish also as
/// Latin American Spanish. Dead and constructed languages without locale
/// data (Old English, Klingon) are not offered.
public enum LanguageCatalog {
    /// The region and script variants offered (BCP 47, as the server keeps them).
    public static let variants = ["pt-BR", "pt-PT", "zh-Hans", "zh-Hant", "es-419"]

    /// Base languages offered only as their variants.
    static let replacedByVariants: Set<String> = ["pt", "zh"]

    /// Codes that name no language.
    static let notLanguages: Set<String> = ["und", "zxx", "mis", "mul", "root"]

    /// Every tag offered, in no order.
    public static let tags: [String] = {
        let english = Locale(identifier: "en")
        var codes = Set(Locale.LanguageCode.isoLanguageCodes.map(\.identifier).filter { $0.count == 2 })
        for identifier in Locale.availableIdentifiers {
            if let code = Locale(identifier: identifier).language.languageCode?.identifier { codes.insert(code) }
        }
        codes.subtract(replacedByVariants)
        codes.subtract(notLanguages)
        let named = codes.filter { code in
            guard let name = english.localizedString(forLanguageCode: code) else { return false }
            return !name.isEmpty && name.lowercased() != code
        }
        return Array(named) + variants
    }()

    /// Every tag offered, by its name in `locale`, A to Z as that language sorts.
    public static func sorted(locale: Locale = .current) -> [(tag: String, name: String)] {
        tags.map { ($0, languageTagName($0, locale: locale)) }
            .sorted { $0.name.compare($1.name, options: [.caseInsensitive, .diacriticInsensitive], locale: locale) == .orderedAscending }
    }

    /// Whether `query` starts a word of the language's name in `locale` or
    /// in English ("portu" finds Portuguese (Brazil), "brazil" too), whatever
    /// the case and accents. An empty query matches everything.
    public static func matches(_ tag: String, query: String, locale: Locale = .current) -> Bool {
        let q = fold(query.trimmingCharacters(in: .whitespacesAndNewlines))
        guard !q.isEmpty else { return true }
        let names = [languageTagName(tag, locale: locale), languageTagName(tag, locale: Locale(identifier: "en"))]
        return names.contains { name in
            let folded = fold(name)
            if folded.hasPrefix(q) { return true }
            return folded.split(whereSeparator: { !$0.isLetter && !$0.isNumber }).contains { $0.hasPrefix(q) }
                || folded.components(separatedBy: " ").contains { $0.hasPrefix(q) }
        }
    }

    private static func fold(_ text: String) -> String {
        text.folding(options: [.caseInsensitive, .diacriticInsensitive, .widthInsensitive], locale: nil)
    }
}
