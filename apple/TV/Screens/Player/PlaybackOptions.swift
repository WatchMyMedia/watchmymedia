import SwiftUI
import UIKit
import WMMFeatures
import WMMModel
import WMMUI

/// The info panel's Audio, Subtitles and Quality (TvPlayerPicker.dc.html):
/// three columns side by side under the system's tabs, with focus starting
/// on the chosen row of `tab`'s column. Subtitles lists Off, then the
/// file's own under "In this file", then the OpenSubtitles group
/// (downloaded, found, the credit); a PGS track says it burns in and
/// restarts the stream. Picking applies at once and the panel stays; the quality picked
/// is this Apple TV's default from then on.
struct PlaybackOptions: View {
    /// The panel's height under the system's tab bar.
    static let height: CGFloat = 480

    let model: TVPlayerModel
    let tab: PickerTab
    @FocusState private var focus: String?

    var body: some View {
        let state = model.state
        VStack(alignment: .leading, spacing: 16) {
            HStack(alignment: .top, spacing: 40) {
                ForEach(PickerTab.allCases, id: \.self) { column in
                    ScrollView(.vertical) {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(column.title.uppercased())
                                .font(.system(size: 16, weight: .bold))
                                .tracking(1.6)
                                .foregroundStyle(Palette.muted)
                                .padding(.horizontal, 16)
                                .padding(.bottom, 4)
                                .accessibilityAddTraits(.isHeader)
                            switch column {
                            case .audio: audio(state)
                            case .subtitles: subtitles(state)
                            case .quality: quality(state)
                            }
                        }
                        .padding(.horizontal, 10)
                        .padding(.vertical, 8)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .focusSection()
                    .frame(maxWidth: .infinity, alignment: .topLeading)
                }
            }
            .frame(maxHeight: .infinity, alignment: .top)
            Text(infoPanelFootnote)
                .font(.system(size: 16))
                .foregroundStyle(Palette.faint)
                .padding(.horizontal, 16)
        }
        .padding(.horizontal, TVLayout.gutter - 26)
        .padding(.top, 12)
        .padding(.bottom, 28)
        // tvOS draws nothing behind a custom tab: the artboard's panel, over the video.
        .background(Color(hex: 0x161618).opacity(0.94), in: RoundedRectangle(cornerRadius: 28))
        // tvOS sizes the tab to its content: the columns scroll within this height instead.
        .frame(height: Self.height)
        .defaultFocus($focus, initialFocus(state), priority: .userInitiated)
    }

    /// The chosen row of this tab's column.
    private func initialFocus(_ state: PlayerState) -> String? {
        switch tab {
        case .audio: (state.selectedAudio ?? state.audio.first?.index).map { "audio:\($0)" }
        case .subtitles: "subtitle:\(state.selectedSubtitle ?? "off")"
        case .quality: "quality:\(state.quality.rawValue)"
        }
    }

    @ViewBuilder
    private func audio(_ state: PlayerState) -> some View {
        if state.audio.isEmpty {
            note("No other audio")
        }
        ForEach(state.audio) { a in
            Button { model.pickAudio(a.index) } label: { OptionRow(label: audioOptionLabel(a), mark: a.index == state.selectedAudio ? .check : nil) }
                .buttonStyle(OptionRowStyle())
                .focused($focus, equals: "audio:\(a.index)")
                .accessibilityAddTraits(a.index == state.selectedAudio ? .isSelected : [])
        }
    }

    @ViewBuilder
    private func subtitles(_ state: PlayerState) -> some View {
        ForEach(Array(subtitleEntries(state).enumerated()), id: \.offset) { _, entry in
            switch entry {
            case .heading(let text):
                Text(text.uppercased())
                    .font(.system(size: 14, weight: .bold))
                    .tracking(1.4)
                    .foregroundStyle(Palette.faint)
                    .padding(.horizontal, 16)
                    .padding(.top, 8)
                    .accessibilityAddTraits(.isHeader)
            case .track(let id, let label, let selected):
                Button { model.pickSubtitle(id) } label: { OptionRow(label: label, mark: selected ? .check : nil) }
                    .buttonStyle(OptionRowStyle())
                    .focused($focus, equals: "subtitle:\(id ?? "off")")
                    .accessibilityAddTraits(selected ? .isSelected : [])
            case .online(let id, let label, let enabled, let downloading):
                Button { model.download(id) } label: { OptionRow(label: label, mark: downloading ? .downloading : .download) }
                    .buttonStyle(OptionRowStyle())
                    .disabled(!enabled || downloading)
                    .focused($focus, equals: "online:\(id)")
                    .accessibilityLabel(["Download and turn on \(label.label)", label.detail].compactMap { $0 }.joined(separator: ", "))
            case .note(let text):
                note(text)
            }
        }
    }

    @ViewBuilder
    private func quality(_ state: PlayerState) -> some View {
        ForEach(state.qualities, id: \.self) { q in
            Button { model.pickQuality(q) } label: { OptionRow(label: qualityOptionLabel(q, in: state), mark: q == state.quality ? .check : nil) }
                .buttonStyle(OptionRowStyle())
                .focused($focus, equals: "quality:\(q.rawValue)")
                .accessibilityAddTraits(q == state.quality ? .isSelected : [])
        }
        note(qualityRememberedNote(device: UIDevice.current.model)).padding(.top, 6)
        if let reason = streamReason(state) { note(reason) }
    }

    private func note(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 14))
            .foregroundStyle(Palette.faint)
            .padding(.horizontal, 16)
            .padding(.vertical, 2)
            .fixedSize(horizontal: false, vertical: true)
    }
}

/// A row's name, its muted detail, and at the end the gold check, a download mark, or "downloading…".
private struct OptionRow: View {
    enum Mark { case check, download, downloading }

    let label: OptionLabel
    let mark: Mark?
    @Environment(\.isFocused) private var focused
    @Environment(\.isEnabled) private var enabled

    var body: some View {
        HStack(spacing: 14) {
            Text(label.label)
                .font(.system(size: 20, weight: .semibold))
                .lineLimit(1)
                .fixedSize()
            if let detail = label.detail {
                Text(detail)
                    .font(.system(size: 16))
                    .foregroundStyle(focused ? Color(hex: 0x525252) : Palette.muted)
                    .lineLimit(1)
            }
            Spacer(minLength: 0)
            switch mark {
            case .check:
                Image(systemName: "checkmark")
                    .font(.system(size: 14, weight: .heavy))
                    .foregroundStyle(Palette.ground)
                    .frame(width: 28, height: 28)
                    .background(Palette.gold, in: Circle())
                    .accessibilityHidden(true)
            case .download:
                Image(systemName: "arrow.down.to.line")
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(Palette.text)
                    .frame(width: 28, height: 28)
                    .background(focused ? Palette.ground : .white.opacity(0.14), in: Circle())
                    .accessibilityHidden(true)
            case .downloading:
                Text("downloading…").font(.system(size: 16)).foregroundStyle(Palette.gold).lineLimit(1)
            case nil:
                EmptyView()
            }
        }
        // A result downloading now is not dimmed: it is what is happening.
        .opacity(enabled || mark == .downloading ? 1 : 0.5)
    }
}

/// The artboard's `.opt`: 50 pt tall; focused, white with dark text, lifted, with the gold rim.
private struct OptionRowStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        Styled(configuration: configuration)
    }

    private struct Styled: View {
        let configuration: Configuration
        @Environment(\.isFocused) private var focused

        var body: some View {
            configuration.label
                .foregroundStyle(focused ? Palette.ground : Palette.text)
                .padding(.horizontal, 16)
                .frame(maxWidth: .infinity, minHeight: 50, alignment: .leading)
                .background(focused ? Palette.text : .clear, in: RoundedRectangle(cornerRadius: 14))
                .focusRim(focused, cornerRadius: 14, scale: 1.04)
                .scaleEffect(configuration.isPressed ? 0.98 : 1)
        }
    }
}
