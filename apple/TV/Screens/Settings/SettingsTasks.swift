import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// Name this Apple TV: room names to choose
/// from (`SettingsModel.roomNames`, this one's first), so most people never
/// type with the remote, and Type another name for the rest. Select on a
/// name saves it; Menu goes back and keeps the name it had.
struct TVRenameScreen: View {
    let model: SettingsModel
    let onChoose: (String) -> Void
    let onClose: () -> Void
    @State private var typing = false
    @State private var typed = ""
    @FocusState private var focused: String?

    private var current: String { model.deviceName }

    var body: some View {
        TVTaskFrame(
            crumb: "\(SettingsWords.settings) › \(SettingsWords.accountAndDevices)", title: SettingsWords.nameTitle(device: model.facts.family),
            lede: SettingsWords.renameHelp, hint: SettingsWords.roomNamesHint(keeping: current)
        ) {
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 14), count: 4), alignment: .leading, spacing: 14) {
                ForEach(model.roomNames, id: \.self) { name in
                    Button { onChoose(name) } label: { ChipLabel(title: name, mark: name == current ? "Now" : nil) }
                        .buttonStyle(TVGridChipStyle())
                        .focused($focused, equals: name)
                        .accessibilityIdentifier("rename-\(name)")
                }
            }
            .frame(maxWidth: 1640)
            .focusSection()
            Button {
                typed = current
                typing = true
            } label: {
                SettingsRow(title: SettingsWords.typeAnotherName, detail: SettingsWords.typeAnotherNameLine) { EmptyView() }
                    .frame(minHeight: 76)
            }
            .buttonStyle(SettingsRowStyle())
            .frame(width: 813)
            .focused($focused, equals: "type")
            .accessibilityIdentifier("rename-type")
            .focusSection()
        }
        .defaultFocus($focused, current)
        .onExitCommand(perform: onClose)
        .alert(SettingsWords.nameTitle(device: model.facts.family), isPresented: $typing) {
            TextField(SettingsWords.deviceNameLabel, text: $typed)
                .autocorrectionDisabled()
            Button(SettingsWords.rename) {
                let task = model.renameTask()
                task.name = typed
                if task.canRename { onChoose(task.cleanName) }
            }
            Button(SettingsWords.cancel, role: .cancel) {}
        } message: {
            Text(SettingsWords.renameHelp)
        }
    }
}

/// Something done on a phone or computer, as a code to scan with its
/// address beside it (Change password, Server settings): typing with the
/// remote is slow, and Apple TV has no browser.
struct TVScanCodeScreen: View {
    let crumb: String
    let title: String
    var lede: String?
    let url: URL
    let paragraphs: [String]
    let hint: String
    let onClose: () -> Void
    @FocusState private var doneFocused: Bool

    var body: some View {
        TVTaskFrame(crumb: crumb, title: title, lede: lede, hint: hint) {
            HStack(alignment: .top, spacing: 80) {
                VStack(spacing: 18) {
                    QRCodeView(url.absoluteString)
                        .frame(width: 360, height: 360)
                        .clipShape(RoundedRectangle(cornerRadius: 24))
                        .accessibilityLabel("QR code that opens \(title) in the web app")
                        .accessibilityIdentifier("scan-code")
                    Text(Self.shown(url))
                        .font(.system(size: 22, weight: .semibold))
                        .foregroundStyle(Palette.text)
                        .accessibilityIdentifier("scan-address")
                }
                .fixedSize()
                VStack(alignment: .leading, spacing: 20) {
                    ForEach(Array(paragraphs.enumerated()), id: \.offset) { _, paragraph in
                        Text(paragraph)
                            .font(.system(size: 23))
                            .lineSpacing(8)
                            .foregroundStyle(Palette.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    Button(SettingsWords.done, action: onClose)
                        .buttonStyle(TVButtonStyle(height: 64, fontSize: 22))
                        .focused($doneFocused)
                        .accessibilityIdentifier("scan-done")
                        .padding(.top, 8)
                }
                .frame(maxWidth: 900, alignment: .leading)
            }
            .padding(.top, 20)
        }
        .defaultFocus($doneFocused, true)
        .onExitCommand(perform: onClose)
    }

    /// "home.example.net/settings": an address as people type it.
    static func shown(_ url: URL) -> String {
        var s = url.absoluteString
        for prefix in ["https://", "http://"] where s.hasPrefix(prefix) { s.removeFirst(prefix.count) }
        return s
    }
}

/// Add a language (`LanguagePicker`): every
/// language by name in a grid, A to Z, the ones already in the list marked
/// Added and the device's own first. Search languages above it takes the
/// keyboard or the microphone. Select adds one to the end of the list, which
/// saves at once, and goes back.
struct TVAddLanguageScreen: View {
    let list: LanguageList
    let model: SettingsModel
    let onClose: () -> Void
    @State private var query = ""
    @FocusState private var focused: String?

    private static let columns = Array(repeating: GridItem(.flexible(), spacing: 14), count: 4)

    var body: some View {
        let picker = model.languagePicker(for: list)
        let options = picker.options(matching: query)
        TVTaskFrame(
            crumb: "\(SettingsWords.settings) › \(model.languagesTitle)", title: picker.title,
            lede: picker.limitReason ?? LanguagePicker.tvLede,
            hint: PanelWords.addLanguageHint(back: model.languagesTitle)
        ) {
            TextField(LanguagePicker.searchPrompt, text: $query)
                .autocorrectionDisabled()
                .font(.system(size: 22))
                .frame(width: 813)
                .focused($focused, equals: "search")
                .accessibilityIdentifier("add-language-search")
            ScrollView(.vertical) {
                VStack(alignment: .leading, spacing: 14) {
                    if query.isEmpty, let suggested = picker.suggested {
                        heading(LanguagePicker.suggestedHeading)
                        LazyVGrid(columns: Self.columns, alignment: .leading, spacing: 14) {
                            chip(suggested, picker: picker, focusID: "suggested-\(suggested.tag)")
                        }
                        heading(LanguagePicker.allLanguages).padding(.top, 10)
                    } else if !query.isEmpty {
                        heading(options.isEmpty ? LanguagePicker.noMatch(query) : LanguagePicker.resultsLine(options.count))
                    }
                    LazyVGrid(columns: Self.columns, alignment: .leading, spacing: 14) {
                        ForEach(options) { option in chip(option, picker: picker, focusID: option.tag) }
                    }
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 12)
                .frame(maxWidth: 1680, alignment: .leading)
            }
            .padding(.horizontal, -20)
            .frame(maxHeight: 560)
            .focusSection()
        }
        .defaultFocus($focused, picker.suggested.map { "suggested-\($0.tag)" } ?? options.first { !$0.added }?.tag)
        .onExitCommand(perform: onClose)
    }

    private func heading(_ text: String) -> some View {
        Text(text.uppercased())
            .font(.system(size: 16, weight: .bold))
            .tracking(1.6)
            .foregroundStyle(Palette.muted)
    }

    private func chip(_ option: LanguagePicker.Option, picker: LanguagePicker, focusID: String) -> some View {
        let off = option.added || !picker.canAdd
        return Button {
            guard !off else { return }
            onClose()
            Task { await model.addLanguage(option.tag, to: list) }
        } label: {
            ChipLabel(title: option.name, mark: option.added ? LanguagePicker.added : nil)
        }
        .buttonStyle(TVGridChipStyle(dimmed: off))
        .focused($focused, equals: focusID)
        .accessibilityValue(option.added ? LanguagePicker.added : "")
        .accessibilityIdentifier("add-language-\(focusID)")
    }
}
