import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// Name this Apple TV (TvSettings2Rename.dc.html): room names to choose
/// from, so most people never type with the remote, and Type another name
/// for the rest. Select on a name saves it; Menu goes back and keeps the
/// name it had.
struct TVRenameScreen: View {
    let current: String
    /// "Apple TV".
    let family: String
    let onChoose: (String) -> Void
    let onClose: () -> Void
    @State private var typing = false
    @State private var typed = ""
    @FocusState private var focused: String?

    /// The rooms, with this one's name first when it isn't among them.
    private var names: [String] {
        let rooms = SettingsWords.rooms.map { "\($0) \(family)" }
        return rooms.contains(current) || current.isEmpty ? rooms : [current] + rooms
    }

    var body: some View {
        TVTaskFrame(
            crumb: "Settings › Account & devices", title: "Name this \(family)",
            lede: "Shown in Signed-in devices and in the server’s Activity, so you can tell your devices apart.",
            hint: "Select a name to save it · Menu goes back to Account & devices and keeps \(current)"
        ) {
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 14), count: 4), alignment: .leading, spacing: 14) {
                ForEach(names, id: \.self) { name in
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
                SettingsRow(title: "Type another name…", detail: "Opens the keyboard. An iPhone nearby can type for you, or hold the microphone to say it.") { EmptyView() }
                    .frame(minHeight: 76)
            }
            .buttonStyle(SettingsRowStyle())
            .frame(width: 813)
            .focused($focused, equals: "type")
            .accessibilityIdentifier("rename-type")
            .focusSection()
        }
        .defaultFocus($focused, names.contains(current) ? current : names.first)
        .onExitCommand(perform: onClose)
        .alert("Name this \(family)", isPresented: $typing) {
            TextField("Name", text: $typed)
                .autocorrectionDisabled()
            Button("Rename") {
                let name = typed.trimmingCharacters(in: .whitespacesAndNewlines)
                if !name.isEmpty { onChoose(name) }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Shown in Signed-in devices and in the server’s Activity.")
        }
    }
}

/// Something done on a phone or computer, as a code to scan with its
/// address beside it (Change password, Server settings): typing with the
/// remote is slow, and Apple TV has no browser.
struct TVScanCodeScreen: View {
    let crumb: String
    let title: String
    let lede: String
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
                    Text(SettingsWords.shown(url))
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
                    Button("Done", action: onClose)
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
}

/// Add a language (TvSettings2AddLanguage.dc.html): names in a grid, the
/// ones already in the list marked Added. Select adds one to the end of the
/// list, which saves at once, and goes back.
struct TVAddLanguageScreen: View {
    let list: LanguageList
    let model: SettingsModel
    let onClose: () -> Void
    @FocusState private var focused: String?

    private var tags: [String] {
        SettingsWords.languages.sorted { languageTagName($0).localizedStandardCompare(languageTagName($1)) == .orderedAscending }
    }

    private func added(_ tag: String) -> Bool {
        model.languages(list).contains { $0.caseInsensitiveCompare(tag) == .orderedSame }
    }

    var body: some View {
        let full = !model.canAdd(to: list)
        TVTaskFrame(
            crumb: "Settings › \(model.languagesTitle)", title: list == .audio ? "Add an audio language" : "Add a subtitle language",
            lede: full ? "Your list holds 16 languages, the most it can. Remove one to add another."
                : "Choose a language to add it to the end of your list. It saves at once. You can keep up to 16.",
            hint: "Select adds it · Menu goes back to \(model.languagesTitle)"
        ) {
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 14), count: 4), alignment: .leading, spacing: 14) {
                ForEach(tags, id: \.self) { tag in
                    let have = added(tag)
                    Button {
                        guard !have, !full else { return }
                        onClose()
                        Task { await model.addLanguage(tag, to: list) }
                    } label: {
                        ChipLabel(title: languageTagName(tag), mark: have ? "Added" : nil)
                    }
                    .buttonStyle(TVGridChipStyle(dimmed: have || full))
                    .focused($focused, equals: tag)
                    .accessibilityValue(have ? "Added" : "")
                    .accessibilityIdentifier("add-language-\(tag)")
                }
            }
            .frame(maxWidth: 1640)
            .focusSection()
        }
        .defaultFocus($focused, tags.first { !added($0) })
        .onExitCommand(perform: onClose)
    }
}
