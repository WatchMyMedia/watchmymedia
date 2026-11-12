import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// Settings › Audio & subtitles on Apple TV (TvSettingsLanguages.dc.html,
/// PLAN-LANGUAGES.md §5.6): a full-screen sheet in three columns, the audio
/// languages, how subtitles start (its four choices in a menu) with
/// automatic download, and the subtitle languages. Each change saves at once.
struct TVLanguageSettings: View {
    let model: SettingsModel
    @Environment(\.dismiss) private var dismiss
    /// The list "Other language…" adds to.
    @State private var typing: LanguageList?
    @State private var languageCode = ""

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 40) {
                HStack {
                    Text(model.languagesTitle).font(.system(size: 56, weight: .heavy))
                    Spacer()
                    Button("Done") { dismiss() }
                        .buttonStyle(TVButtonStyle(height: 64, fontSize: 23))
                }
                .focusSection()
                if model.foreignAudioNotice {
                    HStack(spacing: 16) {
                        Text("\(Image(systemName: "info.circle"))").foregroundStyle(Palette.gold)
                        Text("Subtitles now start when the audio isn’t in your languages. You can change this under Starting subtitles.")
                    }
                    .font(.system(size: 23))
                    .padding(.horizontal, 24)
                    .padding(.vertical, 18)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Palette.gold.opacity(0.10), in: RoundedRectangle(cornerRadius: 16))
                    .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(Palette.gold.opacity(0.32)))
                    .accessibilityElement(children: .combine)
                    .accessibilityIdentifier("foreign-audio-notice")
                }
                HStack(alignment: .top, spacing: 60) {
                    if model.supportsAudioLanguages {
                        column {
                            heading("Audio languages", "Videos start in the first of these languages they have. If they have none, they play the file’s default.")
                            languages(.audio)
                            if model.audio.languages.isEmpty {
                                Text("No audio language yet. Videos play the file’s default audio.")
                                    .font(.system(size: 23)).foregroundStyle(Palette.textSecondary)
                                if let suggested = model.suggestion(for: .audio) {
                                    card {
                                        Text("Your Apple TV uses \(languageTagName(suggested)).")
                                            .font(.system(size: 24, weight: .medium)).foregroundStyle(Palette.textSecondary)
                                        Button("Use \(languageTagName(suggested))") { Task { await model.addLanguage(suggested, to: .audio) } }
                                            .buttonStyle(TVButtonStyle(height: 50, fontSize: 19))
                                            .accessibilityIdentifier("audio-use-device-language")
                                    }
                                }
                            }
                            addLanguage(.audio)
                            foot("Commentary and audio description are never chosen for you. You can pick them in the player.")
                        }
                    }
                    column {
                        heading("Starting subtitles", "When a video starts")
                        Menu {
                            ForEach(model.subtitleChoices, id: \.self) { choice in
                                Button { Task { await model.setSubtitleChoice(choice) } } label: {
                                    if model.subtitleChoice == choice {
                                        Label { Text(choice.label); Text(choice.line) } icon: { Image(systemName: "checkmark") }
                                    } else {
                                        Text(choice.label)
                                        Text(choice.line)
                                    }
                                }
                                .accessibilityIdentifier("subtitle-mode-\(choice.rawValue)")
                            }
                        } label: {
                            HStack {
                                Text(model.subtitleChoice.label).lineLimit(2).multilineTextAlignment(.leading)
                                Spacer(minLength: 12)
                                Text("\(Image(systemName: "chevron.up.chevron.down"))").foregroundStyle(Palette.muted)
                            }
                            .frame(maxWidth: .infinity, minHeight: 60, alignment: .leading)
                        }
                        .accessibilityIdentifier("subtitle-choice")
                        Text(model.subtitleChoice.line)
                            .font(.system(size: 23)).foregroundStyle(Palette.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                        foot("A track you choose in the player always wins.")
                        heading("Online subtitles", nil).padding(.top, 20)
                        Toggle("Automatic download", isOn: Binding(get: { model.subtitles.automaticDownload && model.automaticDownloadAvailable },
                                                                   set: { on in Task { await model.setAutomaticSubtitles(on) } }))
                            .disabled(!model.automaticDownloadAvailable)
                            .accessibilityIdentifier("subtitle-automatic-download")
                        foot(model.automaticDownloadAvailable
                             ? "Only an exact-file match in your first subtitle language, when no local track matches. Uses the server’s OpenSubtitles quota and download permissions."
                             : model.supportsAudioLanguages ? "Choose On or When the audio isn’t in my languages, and add a subtitle language." : "Choose On and add a subtitle language.")
                    }
                    column {
                        heading("Subtitle languages", "Use the first of these languages that has a subtitle.")
                        languages(.subtitles)
                        if let suggested = model.suggestion(for: .subtitles) {
                            Button("Use \(languageTagName(suggested))") { Task { await model.addLanguage(suggested, to: .subtitles) } }
                                .accessibilityIdentifier("subtitle-use-device-language")
                        }
                        addLanguage(.subtitles)
                        if model.subtitlesUseAudioLanguages { foot("Until you add one, subtitles use your audio languages.") }
                    }
                }
                if let error = model.languageError { Text(error).font(.system(size: 23)).foregroundStyle(Palette.warning) }
            }
            .foregroundStyle(Palette.text)
            .padding(.horizontal, TVLayout.gutter)
            .padding(.top, 64)
            .padding(.bottom, 80)
        }
        .background(Palette.ground)
        .task { await model.loadPreferences() }
        .alert("Add a language", isPresented: Binding(get: { typing != nil }, set: { if !$0 { typing = nil } }), presenting: typing) { list in
            TextField("Language code, such as en or pt-BR", text: $languageCode)
                .autocorrectionDisabled()
            Button("Add") { Task { await model.addLanguage(languageCode, to: list) } }
            Button("Cancel", role: .cancel) {}
        } message: { _ in Text("Use a language code such as en or pt-BR.") }
    }

    // MARK: Pieces

    private func column(@ViewBuilder _ content: () -> some View) -> some View {
        VStack(alignment: .leading, spacing: 14, content: content)
            .frame(maxWidth: .infinity, alignment: .leading)
            .focusSection()
    }

    private func heading(_ title: String, _ line: String?) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title.uppercased())
                .font(.system(size: 18, weight: .bold))
                .tracking(1.8)
                .foregroundStyle(Palette.muted)
                .accessibilityAddTraits(.isHeader)
            if let line {
                Text(line).font(.system(size: 23)).foregroundStyle(Palette.muted).fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private func foot(_ text: String) -> some View {
        Text(text).font(.system(size: 19)).foregroundStyle(Palette.faint).fixedSize(horizontal: false, vertical: true)
    }

    private func card(@ViewBuilder _ content: () -> some View) -> some View {
        VStack(alignment: .leading, spacing: 14, content: content)
            .padding(.horizontal, 22)
            .padding(.vertical, 18)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(.white.opacity(0.05), in: RoundedRectangle(cornerRadius: 16))
    }

    /// Each language as a card with Move up, Move down and Remove, which a remote reaches in a row.
    private func languages(_ list: LanguageList) -> some View {
        let tags = model.languages(list)
        return ForEach(Array(tags.enumerated()), id: \.element) { index, tag in
            card {
                HStack(alignment: .firstTextBaseline, spacing: 0) {
                    Text("\(index + 1)")
                        .font(.system(size: 21, weight: .bold).monospacedDigit())
                        .foregroundStyle(Palette.faint)
                        .frame(width: 30, alignment: .leading)
                    Text(languageTagName(tag)).font(.system(size: 24, weight: .semibold))
                }
                .accessibilityElement(children: .combine)
                HStack(spacing: 10) {
                    Button("Move up") { Task { await model.moveLanguage(tag, in: list, by: -1) } }
                        .disabled(index == 0)
                    Button("Move down") { Task { await model.moveLanguage(tag, in: list, by: 1) } }
                        .disabled(index == tags.count - 1)
                    Button("Remove") { Task { await model.removeLanguage(tag, from: list) } }
                        .accessibilityIdentifier("\(list.rawValue)-remove-\(tag)")
                }
                .buttonStyle(TVButtonStyle(height: 50, fontSize: 19))
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            // A container's identifier goes to every child unless it is an element.
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("\(list.rawValue)-language-\(tag)")
        }
    }

    private func addLanguage(_ list: LanguageList) -> some View {
        Menu {
            ForEach(model.addable(to: list), id: \.self) { tag in
                Button(languageTagName(tag)) { Task { await model.addLanguage(tag, to: list) } }
            }
            Button("Other language…") { languageCode = ""; typing = list }
        } label: {
            Text("\(Image(systemName: "plus")) Add language")
                .frame(maxWidth: .infinity, minHeight: 60, alignment: .leading)
        }
        .disabled(!model.canAdd(to: list))
        .accessibilityIdentifier("\(list.rawValue)-add-language")
    }
}
