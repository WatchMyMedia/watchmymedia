import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// Settings › Audio & subtitles (SettingsLanguages.dc.html, and
/// Pad2SettingsLanguages.dc.html in two columns where the page is wide;
/// PLAN-LANGUAGES.md §5.6): the audio languages, how subtitles start, the
/// subtitle languages and automatic download. Each change saves at once.
struct LanguageSettingsPage: View {
    let model: SettingsModel
    @State private var width: CGFloat = 0
    /// The list "Other language…" adds to.
    @State private var typing: LanguageList?
    @State private var languageCode = ""

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                if !model.supportsSubtitlePreferences {
                    Text("Update the server to use subtitle preferences.").foregroundStyle(Palette.muted)
                } else if model.supportsAudioLanguages && width >= 680 {
                    notice
                    HStack(alignment: .top, spacing: 24) {
                        VStack(alignment: .leading, spacing: 22) { audioSection; startSection }
                        VStack(alignment: .leading, spacing: 22) { subtitleSection; onlineSection }
                    }
                } else {
                    if model.supportsAudioLanguages { audioSection }
                    notice
                    startSection
                    subtitleSection
                    onlineSection
                }
                if let error = model.languageError { ErrorLine(message: error) }
            }
            .padding(.horizontal, width >= 680 ? 32 : 16)
            .padding(.top, 8)
            .padding(.bottom, 24)
            .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width = $0 }
        }
        .scrollBounceBehavior(.basedOnSize)
        .groundBackground()
        .navigationTitle(model.languagesTitle)
        .task { await model.loadPreferences() }
        .alert("Add a language", isPresented: Binding(get: { typing != nil }, set: { if !$0 { typing = nil } }), presenting: typing) { list in
            TextField("Language code", text: $languageCode)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            Button("Add") { Task { await model.addLanguage(languageCode, to: list) } }
            Button("Cancel", role: .cancel) {}
        } message: { _ in Text("Use a language code such as en or pt-BR.") }
    }

    // MARK: Sections

    private var audioSection: some View {
        section("Audio languages", "Videos start in the first of these languages they have. If they have none, they play the file’s default.",
                note: "Commentary and audio description are never chosen for you. You can pick them in the player.") {
            languageRows(.audio)
            if model.audio.languages.isEmpty {
                Text("No audio language yet. Videos play the file’s default audio.")
                    .font(.subheadline)
                    .foregroundStyle(Palette.muted)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                    .frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
                if let suggested = model.suggestion(for: .audio) {
                    HStack(spacing: 12) {
                        Text("Your \(model.facts.family) uses \(languageTagName(suggested)).")
                            .foregroundStyle(Palette.textSecondary)
                        Spacer(minLength: 8)
                        Button("Use \(languageTagName(suggested))") { Task { await model.addLanguage(suggested, to: .audio) } }
                            .buttonStyle(GoldTextButtonStyle(font: .body.weight(.semibold)))
                            .accessibilityIdentifier("audio-use-device-language")
                    }
                    .padding(.horizontal, 14)
                    .frame(minHeight: 48)
                }
            }
            addRow(.audio)
        }
    }

    @ViewBuilder private var notice: some View {
        if model.foreignAudioNotice {
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: "info.circle").foregroundStyle(Palette.gold).accessibilityHidden(true)
                Text("Subtitles now start when the audio isn’t in your languages. You can change this under Starting subtitles.")
                    .font(.subheadline)
                    .foregroundStyle(Palette.text)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Palette.gold.opacity(0.10), in: RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(Palette.gold.opacity(0.32)))
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("foreign-audio-notice")
        }
    }

    private var startSection: some View {
        section("Starting subtitles", "When a video starts", note: "A track you choose in the player always wins.") {
            ForEach(model.subtitleChoices, id: \.self) { choice in
                let selected = model.subtitleChoice == choice
                Button { Task { await model.setSubtitleChoice(choice) } } label: {
                    HStack(spacing: 12) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(choice.label)
                                .font(.body.weight(.medium))
                                .foregroundStyle(Palette.text)
                            Text(choice.line)
                                .font(.caption)
                                .foregroundStyle(Palette.muted)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        .multilineTextAlignment(.leading)
                        Spacer(minLength: 8)
                        if selected { SelectedCheck() }
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 8)
                    .frame(minHeight: 48)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityElement(children: .combine)
                .accessibilityAddTraits(selected ? [.isButton, .isSelected] : .isButton)
                .accessibilityIdentifier("subtitle-mode-\(choice.rawValue)")
            }
        }
    }

    private var subtitleSection: some View {
        section("Subtitle languages", "Use the first of these languages that has a subtitle.",
                note: model.subtitlesUseAudioLanguages ? "Until you add one, subtitles use your audio languages." : nil) {
            languageRows(.subtitles)
            if let suggested = model.suggestion(for: .subtitles) {
                Button { Task { await model.addLanguage(suggested, to: .subtitles) } } label: {
                    Text("Use \(languageTagName(suggested))")
                        .font(.body.weight(.medium))
                        .foregroundStyle(Palette.gold)
                        .padding(.horizontal, 14)
                        .frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("subtitle-use-device-language")
            }
            addRow(.subtitles)
        }
    }

    private var onlineSection: some View {
        section("Online subtitles", nil, note: model.automaticDownloadAvailable
                ? "When subtitles would start and no local track matches, download the exact-file match in your first subtitle language. Uses the server’s OpenSubtitles quota and download permissions. Kept on the server for apps that support subtitle preferences."
                : model.supportsAudioLanguages ? "Choose On or When the audio isn’t in my languages, and add a subtitle language." : "Choose On and add a subtitle language.") {
            Toggle(isOn: Binding(get: { model.subtitles.automaticDownload && model.automaticDownloadAvailable },
                                 set: { on in Task { await model.setAutomaticSubtitles(on) } })) {
                Text("Automatic download")
                    .font(.body.weight(.medium))
                    .foregroundStyle(Palette.text)
                    .opacity(model.automaticDownloadAvailable ? 1 : 0.4)
            }
            .tint(Palette.gold)
            .disabled(!model.automaticDownloadAvailable)
            .padding(.horizontal, 14)
            .frame(minHeight: 48)
            .accessibilityIdentifier("subtitle-automatic-download")
        }
    }

    // MARK: Rows

    private func languageRows(_ list: LanguageList) -> some View {
        let languages = model.languages(list)
        return ForEach(Array(languages.enumerated()), id: \.element) { index, tag in
            LanguageRow(model: model, list: list, tag: tag, number: index + 1, first: index == 0, last: index == languages.count - 1)
        }
    }

    private func addRow(_ list: LanguageList) -> some View {
        Menu {
            ForEach(model.addable(to: list), id: \.self) { tag in
                Button(languageTagName(tag)) { Task { await model.addLanguage(tag, to: list) } }
            }
            Button("Other language…") { languageCode = ""; typing = list }
        } label: {
            HStack(spacing: 12) {
                Image(systemName: "plus").font(.body.weight(.semibold))
                Text("Add language").font(.body.weight(.medium))
            }
            .foregroundStyle(Palette.gold)
            .padding(.horizontal, 14)
            .frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
            .contentShape(Rectangle())
        }
        .disabled(!model.canAdd(to: list))
        .accessibilityIdentifier("\(list.rawValue)-add-language")
    }

    private func section(_ title: String, _ description: String?, note: String?, @ViewBuilder rows: () -> some View) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            GroupHeading(title).padding(.horizontal, 16)
            if let description {
                Text(description)
                    .font(.footnote)
                    .foregroundStyle(Palette.muted)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.horizontal, 16)
                    .padding(.bottom, 2)
            }
            RoundedGroup(content: rows)
            if let note { GroupFootnote(note).padding(.horizontal, 16).padding(.top, 2) }
        }
    }
}

/// A numbered language with its ⋯; tapping it asks Move up, Move down or
/// Remove, as an action sheet on iPhone and a popover beside the row on iPad.
private struct LanguageRow: View {
    let model: SettingsModel
    let list: LanguageList
    let tag: String
    let number: Int
    let first: Bool
    let last: Bool
    @State private var asking = false

    var body: some View {
        Button { asking = true } label: {
            HStack(spacing: 12) {
                Text("\(number)")
                    .font(.footnote.weight(.semibold).monospacedDigit())
                    .foregroundStyle(Palette.faint)
                    .frame(width: 16, alignment: .leading)
                Text(languageTagName(tag))
                    .font(.body.weight(.medium))
                    .foregroundStyle(Palette.text)
                Spacer(minLength: 8)
                Image(systemName: "ellipsis")
                    .foregroundStyle(Palette.faint)
                    .accessibilityHidden(true)
            }
            .padding(.horizontal, 14)
            .frame(minHeight: 48)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(number). \(languageTagName(tag))")
        .accessibilityHint("Move or remove")
        .accessibilityIdentifier("\(list.rawValue)-language-\(tag)")
        // The dialog closes before a button's task runs, so each action names its language.
        .confirmationDialog(languageTagName(tag), isPresented: $asking, titleVisibility: .visible) { [model, list, tag] in
            if !first { Button("Move up") { Task { await model.moveLanguage(tag, in: list, by: -1) } } }
            if !last { Button("Move down") { Task { await model.moveLanguage(tag, in: list, by: 1) } } }
            Button("Remove", role: .destructive) { Task { await model.removeLanguage(tag, from: list) } }
        }
    }
}
