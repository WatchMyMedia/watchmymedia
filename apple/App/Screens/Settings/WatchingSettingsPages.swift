import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// Settings › Playback (SettingsPlayback.dc.html): the quality, kept on this
/// device, and autoplay, kept on the server.
struct PlaybackSettingsPage: View {
    let model: SettingsModel

    var body: some View {
        List {
            Section {
                ForEach(model.qualities, id: \.self) { quality in
                    let selected = model.quality == quality
                    Button { model.setQuality(quality) } label: {
                        HStack(spacing: 12) {
                            TitleAndDetail(title: quality.checklistTitle, detail: quality.checklistDetail)
                            Spacer(minLength: 8)
                            if selected { SelectedCheck() }
                        }
                        .contentShape(Rectangle())
                    }
                    .accessibilityAddTraits(selected ? .isSelected : [])
                    .accountRow()
                }
            } header: {
                GroupHeading("Quality")
            } footer: {
                GroupFootnote(model.qualityNote)
            }

            Section {
                Toggle(isOn: Binding(get: { model.autoplayNext }, set: { on in Task { await model.setAutoplayNext(on) } })) {
                    TitleAndDetail(title: "Autoplay next episode", detail: "Counts down 10 s from the credits")
                }
                .tint(Palette.gold)
                .accountRow()
            } header: {
                GroupHeading("Next episode")
            } footer: {
                VStack(alignment: .leading, spacing: 8) {
                    if let error = model.playbackError { ErrorLine(message: error) }
                    GroupFootnote("Kept on the server, so every app you sign in to follows it.")
                }
            }
        }
        .accountList()
        .navigationTitle("Playback")
        .animation(.default, value: model.autoplayNext)
    }
}

/// Settings › Skip (SettingsSkip.dc.html): a menu of the four modes for each kind of segment.
struct SkipSettingsPage: View {
    let model: SettingsModel

    var body: some View {
        List {
            Section {
                ForEach(SegmentKind.allCases, id: \.self) { kind in
                    Picker(selection: Binding(get: { model.skipMode(kind) }, set: { mode in Task { await model.setSkip(kind, mode: mode) } })) {
                        ForEach(SkipMode.allCases, id: \.self) { mode in
                            Text(mode.label).tag(mode)
                        }
                    } label: {
                        Text(kind.settingsLabel)
                            .font(.body.weight(.medium))
                            .foregroundStyle(Palette.text)
                    }
                    .pickerStyle(.menu)
                    .tint(Palette.gold)
                    .accountRow()
                }
            } footer: {
                VStack(alignment: .leading, spacing: 8) {
                    if let error = model.skipError { ErrorLine(message: error) }
                    GroupFootnote("Ask shows a Skip button. Auto skips after one second. Auto after 3 s fills the button, then skips. Kept on the server, so every app you sign in to follows them.")
                }
            }
        }
        .accountList()
        .navigationTitle("Skip")
    }
}

/// A row's title with a smaller muted line under it.
struct TitleAndDetail: View {
    let title: String
    let detail: String

    var body: some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(title)
                .font(.body.weight(.medium))
                .foregroundStyle(Palette.text)
            Text(detail)
                .font(.caption)
                .foregroundStyle(Palette.muted)
        }
        .accessibilityElement(children: .combine)
    }
}

/// The gold check on the chosen row of a checklist.
struct SelectedCheck: View {
    var body: some View {
        Image(systemName: "checkmark")
            .font(.caption.weight(.heavy))
            .foregroundStyle(Palette.ground)
            .frame(width: 22, height: 22)
            .background(Palette.gold, in: Circle())
            .accessibilityHidden(true)
    }
}
