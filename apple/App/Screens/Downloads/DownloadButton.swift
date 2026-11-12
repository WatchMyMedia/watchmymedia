import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// The movie and episode pages' Download button (MovieDownload.dc.html):
/// Download with the size at the Settings quality, or the download's state
/// with its progress drawn inside; the server's marks under it. Download
/// opens the sheet; a held download opens its menu, grown from the button.
struct DownloadButton: View {
    @Bindable var model: ItemDownloadModel
    /// Play from the menu: the page's own play, which plays the copy.
    let play: () -> Void
    @Environment(\.horizontalSizeClass) private var sizeClass
    @Environment(Navigator.self) private var navigator

    var body: some View {
        if model.offered {
            VStack(alignment: .leading, spacing: 8) {
                Button { model.press() } label: {
                    Label {
                        Text(model.buttonLabel)
                    } icon: {
                        Image(systemName: model.buttonIcon).foregroundStyle(iconColor)
                    }
                }
                .buttonStyle(GhostButtonStyle())
                .overlay(alignment: .bottom) {
                    if let progress = model.progress {
                        GeometryReader { geo in
                            ZStack(alignment: .leading) {
                                Rectangle().fill(Color.black.opacity(0.35))
                                Rectangle().fill(Palette.gold).frame(width: geo.size.width * progress)
                            }
                        }
                        .frame(height: 3)
                        .clipShape(UnevenRoundedRectangle(bottomLeadingRadius: 10, bottomTrailingRadius: 10))
                        .allowsHitTesting(false)
                    }
                }
                .accessibilityHint(model.download == nil ? "Opens the download options" : "Opens this download’s options")
                .confirmationDialog(model.menuTitle, isPresented: $model.menuShown, titleVisibility: .visible) {
                    menu
                } message: {
                    if let line = model.menuLine { Text(line) }
                }
                if let help = model.help {
                    Text(help).font(.footnote).foregroundStyle(Palette.muted).padding(.horizontal, 2)
                }
                marks
            }
            .sheet(isPresented: $model.sheetShown) {
                DownloadSheet(model: model)
            }
            .task { await model.load() }
            .messageToast($model.message)
        }
    }

    private var iconColor: Color {
        switch model.buttonTone {
        case .plain: Palette.text
        case .gold: Palette.gold
        case .warning: Palette.warning
        }
    }

    /// The menu for a held download (Play, Download again, Pause or Resume, Remove or Cancel).
    @ViewBuilder private var menu: some View {
        if let d = model.download {
            if d.downloaded {
                Button(d.mark == .newVersion ? "Play this copy" : "Play", action: play)
                if d.mark == .newVersion {
                    Button("Download again") { model.openSheet() }
                }
            }
            switch d.status {
            case .downloading, .preparing:
                Button("Pause download") { model.pause() }
            case .paused:
                Button("Resume download") { model.resume() }
            case .waiting(.wifi):
                Button("Download settings") { navigator.open(.downloads) }
            default:
                EmptyView()
            }
            Button(d.downloaded ? "Remove download" : "Cancel download", role: .destructive) { model.remove() }
        }
    }

    /// Removed from the server, or A new version is available with Download again.
    @ViewBuilder private var marks: some View {
        switch model.download?.mark {
        case .removedFromServer?:
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Circle().fill(Palette.warning).frame(width: 8, height: 8)
                (Text("Removed from the server").fontWeight(.semibold).foregroundStyle(Palette.warning)
                    + Text(" · this copy plays until you remove it").foregroundStyle(Palette.muted))
                    .font(.footnote)
            }
            .padding(.horizontal, 2)
        case .newVersion?:
            HStack(spacing: 8) {
                Circle().fill(Palette.gold).frame(width: 8, height: 8)
                Text("A new version is available").font(.footnote.weight(.semibold)).foregroundStyle(Palette.gold)
                Spacer(minLength: 8)
                Button("Download again") { model.openSheet() }
                    .buttonStyle(GoldTextButtonStyle(font: .footnote.weight(.semibold)))
                    .frame(minHeight: 44)
            }
            .padding(.horizontal, 2)
        default:
            EmptyView()
        }
    }
}

/// The download sheet (DownloadSheet.dc.html, Pad2DownloadSheet.dc.html):
/// each quality with its size, the audio track and an image subtitle to burn
/// in for an HLS download, Wi-Fi only and the space free; Download.
struct DownloadSheet: View {
    @Bindable var model: ItemDownloadModel
    @Environment(\.dismiss) private var dismiss
    @Environment(Navigator.self) private var navigator
    @Environment(\.horizontalSizeClass) private var sizeClass

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    switch model.plan {
                    case .failed(let message)?:
                        ErrorLine(message: message)
                        Button("Try again") { Task { await model.loadPlan() } }.buttonStyle(GhostButtonStyle())
                    case .loaded?:
                        content
                    default:
                        ProgressView().tint(Palette.muted).frame(maxWidth: .infinity, minHeight: 200)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 16)
            }
            .safeAreaInset(edge: .bottom) {
                if model.plan?.value != nil {
                    Button { Task { await model.start() } } label: {
                        BusyLabel(title: model.downloadButtonLabel, busy: model.starting)
                    }
                    .buttonStyle(PrimaryButtonStyle())
                    .disabled(!model.canStart)
                    .padding(16)
                    .background(Palette.sheet)
                }
            }
            .navigationTitle("Download")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
            .background(Palette.sheet)
        }
        .presentationSizing(.form)
        .presentationDragIndicator(.visible)
        .presentationBackground(Palette.sheet)
        .messageToast($model.message)
    }

    @ViewBuilder private var content: some View {
        VStack(alignment: .leading, spacing: 6) {
            GroupHeading("Quality").padding(.horizontal, 4)
            VStack(spacing: 0) {
                ForEach(Array(model.options.enumerated()), id: \.offset) { index, option in
                    if index > 0 { Divider().overlay(Palette.outline) }
                    qualityRow(option)
                }
            }
            .background(Palette.surfaceRaised, in: RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(Palette.outline))
        }
        if !model.audioChoices.isEmpty {
            choices("Audio", model.audioChoices.map { ($0.index as Int?, $0.label) }, selected: model.selectedAudioIndex) { model.audioIndex = $0 }
        }
        if !model.burnInChoices.isEmpty {
            choices("Burn in subtitles", [(nil, "None")] + model.burnInChoices.map { ($0.id as String?, [$0.label, $0.format].compactMap { $0 }.joined(separator: " · ")) }, selected: model.burnInSubtitleID) { model.burnInSubtitleID = $0 }
        }
        VStack(alignment: .leading, spacing: 8) {
            line("captions.bubble", model.tracksLine)
            HStack(alignment: .firstTextBaseline, spacing: 10) {
                Image(systemName: "wifi").foregroundStyle(Palette.muted).frame(width: 18)
                Text(model.wifiLine).foregroundStyle(Palette.muted)
                Button("Change in Settings") {
                    model.sheetShown = false
                    navigator.open(.downloads)
                }
                .buttonStyle(GoldTextButtonStyle(font: .footnote.weight(.semibold)))
            }
            .font(.footnote)
            let fits = model.chosen.map(model.fits) ?? true
            line(fits ? (model.device == "iPad" ? "ipad" : "iphone") : "exclamationmark.triangle", model.spaceLine, color: fits ? Palette.muted : Palette.warning)
        }
    }

    private func qualityRow(_ option: DownloadOption) -> some View {
        let fits = model.fits(option)
        let selected = model.chosen?.quality == option.quality
        return Button { model.quality = option.quality } label: {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(option.quality.label).font(.body.weight(.medium)).foregroundStyle(fits ? Palette.text : Palette.faint)
                    Text(model.detail(option)).font(.footnote).foregroundStyle(fits ? Palette.muted : Palette.warning)
                }
                Spacer(minLength: 8)
                Text(downloadSize(option.sizeBytes, estimated: option.estimated))
                    .font(.subheadline.monospacedDigit())
                    .foregroundStyle(fits ? Palette.text : Palette.faint)
                Image(systemName: "checkmark")
                    .font(.body.weight(.semibold))
                    .foregroundStyle(Palette.gold)
                    .opacity(selected ? 1 : 0)
                    .frame(width: 20)
            }
            .padding(.horizontal, 14)
            .frame(minHeight: 56)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    private func choices<ID: Hashable>(_ title: String, _ items: [(ID?, String)], selected: ID?, pick: @escaping (ID?) -> Void) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            GroupHeading(title).padding(.horizontal, 4)
            FlowLayout(spacing: 8) {
                ForEach(Array(items.enumerated()), id: \.offset) { _, item in
                    Chip(item.1, selected: item.0 == selected) { pick(item.0) }
                }
            }
        }
    }

    private func line(_ icon: String, _ text: String, color: Color = Palette.muted) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            Image(systemName: icon).frame(width: 18)
            Text(text).fixedSize(horizontal: false, vertical: true)
        }
        .font(.footnote)
        .foregroundStyle(color)
    }
}

/// A page's Download button with its own model, made once per file.
struct ItemDownloadButton: View {
    let fileID: Int
    var otherFiles: [Int] = []
    let play: () -> Void
    @Environment(AppServices.self) private var services

    var body: some View {
        Holder(model: ItemDownloadModel(downloads: services.downloads, fileID: fileID, otherFiles: otherFiles, device: downloadsDevice(services.client)), play: play)
    }

    private struct Holder: View {
        @State var model: ItemDownloadModel
        let play: () -> Void

        var body: some View { DownloadButton(model: model, play: play) }
    }
}
