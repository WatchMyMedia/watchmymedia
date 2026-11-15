import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// Add a language (Settings2AddLanguage.dc.html, PLAN-APPLE-SETTINGS.md
/// §5.3): every language by name, to search, in place of a menu of fifteen
/// and a box for a code. Those already in the list are marked Added; choosing
/// another adds it at the end of the list, saves it at once and closes.
struct AddLanguageSheet: View {
    let model: SettingsModel
    let list: LanguageList
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(matches) { choice in
                        let added = have.contains { $0.caseInsensitiveCompare(choice.tag) == .orderedSame }
                        Button { choose(choice.tag) } label: {
                            HStack {
                                Text(choice.name)
                                    .font(.body.weight(.medium))
                                    .foregroundStyle(Palette.text)
                                    .opacity(added ? 0.45 : 1)
                                Spacer(minLength: 8)
                                if added { Text("Added").font(.subheadline).foregroundStyle(Palette.muted) }
                            }
                            .contentShape(Rectangle())
                        }
                        .disabled(added)
                        .accessibilityLabel(added ? "\(choice.name), added" : choice.name)
                        .accessibilityIdentifier("add-language-\(choice.tag)")
                        .listRowBackground(Palette.surfaceRaised)
                    }
                } header: {
                    if !matches.isEmpty {
                        GroupHeading(query.isEmpty ? "All languages" : plural(matches.count, "language"))
                    }
                } footer: {
                    if matches.isEmpty {
                        Text("No language matches “\(query)”. Check the spelling, or search for its name in English.")
                            .font(.subheadline)
                            .foregroundStyle(Palette.muted)
                    } else {
                        GroupFootnote("Choosing a language adds it to the end of your list and saves it at once. You can keep up to 16.")
                    }
                }
            }
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
            .background(Palette.sheet)
            .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "Search languages")
            .navigationTitle(list == .audio ? "Add an audio language" : "Add a subtitle language")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                        .keyboardShortcut(.cancelAction)
                }
            }
        }
        .taskSheet()
    }

    private var have: [String] { model.languages(list) }

    private var matches: [LanguageChoice] {
        let all = LanguageChoice.all
        let q = query.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { return all }
        return all.filter { $0.matches(q) }
    }

    private func choose(_ tag: String) {
        dismiss()
        Task { await model.addLanguage(tag, to: list) }
    }
}

/// A language to add, by its name in the device's language, found by that
/// name, its English name or its code.
struct LanguageChoice: Identifiable, Hashable {
    let tag: String
    let name: String
    let english: String
    var id: String { tag }

    func matches(_ query: String) -> Bool {
        [name, english, tag].contains { $0.range(of: query, options: [.caseInsensitive, .diacriticInsensitive]) != nil }
    }

    /// Every language the system can name: the two-letter ones, with
    /// Chinese by script and Portuguese by region, as the server keeps them.
    @MainActor static let all: [LanguageChoice] = {
        let english = Locale(identifier: "en")
        var tags = Locale.LanguageCode.isoLanguageCodes.map(\.identifier).filter { $0.count == 2 }
        tags.removeAll { $0 == "zh" || $0 == "pt" }
        tags += ["zh-Hans", "zh-Hant", "pt-BR", "pt-PT"]
        let choices = tags.compactMap { tag -> LanguageChoice? in
            let name = languageTagName(tag)
            guard name != tag else { return nil }
            return LanguageChoice(tag: tag, name: name, english: languageTagName(tag, locale: english))
        }
        return choices.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
    }()
}
