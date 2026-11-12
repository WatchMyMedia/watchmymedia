import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// A season uses the same quality and storage vocabulary as an individual
/// download, with the complete batch estimate before anything is queued.
struct SeasonDownloadSheet: View {
    @Bindable var model: SeasonDownloadModel
    let completed: () -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    Text("\(model.title) · \(model.seasonTitle)")
                        .font(.headline)
                        .foregroundStyle(Palette.text)
                    switch model.state {
                    case .loading:
                        ProgressView().tint(Palette.muted).frame(maxWidth: .infinity, minHeight: 180)
                    case .failed(let message):
                        ErrorLine(message: message)
                        Button("Try again") { Task { await model.load() } }
                            .buttonStyle(GhostButtonStyle())
                    case .loaded:
                        if model.count == 0 {
                            Text("Every episode in your library is already downloaded or queued.")
                                .foregroundStyle(Palette.muted)
                        } else {
                            content
                        }
                    }
                    if let excluded = model.excludedLine {
                        Text(excluded).font(.footnote).foregroundStyle(Palette.muted)
                    }
                    if let message = model.message {
                        ErrorLine(message: message)
                            .accessibilityIdentifier("season-download-result")
                    }
                }
                .padding(16)
            }
            .safeAreaInset(edge: .bottom) {
                if model.state.value != nil {
                    Group {
                        if model.count == 0 {
                            Button("Done", action: completed)
                                .buttonStyle(PrimaryButtonStyle())
                        } else {
                            Button { Task { await model.start() } } label: {
                                BusyLabel(title: model.downloadLabel, busy: model.starting)
                            }
                            .buttonStyle(PrimaryButtonStyle())
                            .disabled(!model.canStart)
                            .accessibilityIdentifier("download-season-confirm")
                        }
                    }
                    .padding(16)
                    .background(Palette.sheet)
                }
            }
            .navigationTitle("Download season")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() }.disabled(model.starting) }
            }
            .background(Palette.sheet)
        }
        .presentationSizing(.form)
        .presentationDragIndicator(.visible)
        .presentationBackground(Palette.sheet)
        .interactiveDismissDisabled(model.starting)
        .task { await model.load() }
        .onChange(of: model.finished) { _, done in if done { completed() } }
    }

    private var content: some View {
        VStack(alignment: .leading, spacing: 18) {
            Text(model.summary).foregroundStyle(Palette.muted)
                .accessibilityIdentifier("season-download-summary")
            VStack(alignment: .leading, spacing: 6) {
                GroupHeading("Quality").padding(.horizontal, 4)
                VStack(spacing: 0) {
                    ForEach(model.options, id: \.quality) { option in
                        if option.quality != model.options.first?.quality { Divider().overlay(Palette.outline) }
                        Button { model.quality = option.quality } label: {
                            HStack(spacing: 12) {
                                Text(option.quality.label).foregroundStyle(Palette.text)
                                Spacer(minLength: 8)
                                Text(downloadSize(option.sizeBytes, estimated: option.estimated))
                                    .font(.subheadline.monospacedDigit())
                                    .foregroundStyle(model.fits(option) ? Palette.muted : Palette.warning)
                                Image(systemName: "checkmark")
                                    .foregroundStyle(Palette.gold)
                                    .opacity(option.quality == model.chosen?.quality ? 1 : 0)
                            }
                            .padding(.horizontal, 14)
                            .frame(minHeight: 56)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityAddTraits(option.quality == model.chosen?.quality ? .isSelected : [])
                    }
                }
                .background(Palette.surfaceRaised, in: RoundedRectangle(cornerRadius: 12))
                .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(Palette.outline))
            }
            VStack(alignment: .leading, spacing: 10) {
                Label("Original files keep their tracks. Converted downloads use each episode’s audio in your languages, or its default, and its text subtitles.", systemImage: "captions.bubble")
                Label(model.wifiLine, systemImage: "wifi")
                Label(model.spaceLine, systemImage: model.chosen.map(model.fits) == false ? "exclamationmark.triangle" : "internaldrive")
                    .foregroundStyle(model.chosen.map(model.fits) == false ? Palette.warning : Palette.muted)
            }
            .font(.footnote)
            .foregroundStyle(Palette.muted)
            if model.options.isEmpty {
                ErrorLine(message: "No quality is available for every episode. Download the episodes individually.")
            }
        }
    }
}
