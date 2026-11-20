import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// Add a language:
/// every language by name (the Kit's ``LanguagePicker``), to search,
/// in place of a menu of fifteen and a box for a code. The device's language
/// is suggested at the top while the list lacks it; those already in the
/// list are marked Added; choosing another adds it at the end of the list,
/// saves it at once and closes.
struct AddLanguageSheet: View {
    let model: SettingsModel
    let list: LanguageList
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""

    var body: some View {
        let picker = model.languagePicker(for: list)
        let matches = picker.options(matching: query)
        NavigationStack {
            List {
                if query.isEmpty, let suggested = picker.suggested {
                    Section {
                        row(suggested)
                    } header: {
                        GroupHeading(LanguagePicker.suggestedHeading)
                    }
                }
                Section {
                    ForEach(matches) { row($0) }
                } header: {
                    if !matches.isEmpty {
                        GroupHeading(query.isEmpty ? LanguagePicker.allLanguages : LanguagePicker.resultsLine(matches.count))
                    }
                } footer: {
                    if matches.isEmpty {
                        Text(LanguagePicker.noMatch(query))
                            .font(.subheadline)
                            .foregroundStyle(Palette.muted)
                    } else {
                        GroupFootnote(picker.limitReason ?? LanguagePicker.footnote)
                    }
                }
            }
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
            .background(Palette.sheet)
            .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: LanguagePicker.searchPrompt)
            .navigationTitle(picker.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(SettingsWords.cancel) { dismiss() }
                        .keyboardShortcut(.cancelAction)
                }
            }
        }
        .taskSheet()
    }

    private func row(_ option: LanguagePicker.Option) -> some View {
        Button { choose(option) } label: {
            HStack {
                Text(option.name)
                    .font(.body.weight(.medium))
                    .foregroundStyle(Palette.text)
                    .opacity(option.added ? 0.45 : 1)
                Spacer(minLength: 8)
                if option.added { Text(LanguagePicker.added).font(.subheadline).foregroundStyle(Palette.muted) }
            }
            .contentShape(Rectangle())
        }
        .disabled(option.added)
        .accessibilityLabel(option.added ? "\(option.name), \(LanguagePicker.added.lowercased())" : option.name)
        .accessibilityIdentifier("add-language-\(option.tag)")
        .listRowBackground(Palette.surfaceRaised)
    }

    private func choose(_ option: LanguagePicker.Option) {
        guard !option.added else { return }
        dismiss()
        Task { await model.addLanguage(option.tag, to: list) }
    }
}
