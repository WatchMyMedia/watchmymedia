import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// Subtitle size, over a picture: the four sizes in a row, and at the
/// bottom a cue drawn as the player draws it at the size focused, so Left
/// and Right show each one where subtitles will be. Select keeps the size
/// focused (it saves at once and the line says so); Menu goes back and
/// keeps the size saved.
struct TVSubtitleSizeScreen: View {
    let model: SettingsModel
    let onClose: () -> Void
    @FocusState private var focused: SubtitleSize?

    /// What the cue shows: the size focused, else the one saved.
    private var shown: SubtitleSize { focused ?? model.subtitleSize }

    var body: some View {
        ZStack(alignment: .topLeading) {
            SizeBackdrop()
            VStack(alignment: .leading, spacing: 18) {
                Text("\(SettingsWords.settings) › \(SettingsWords.audioAndSubtitles)")
                    .font(.system(size: 22, weight: .semibold))
                    .foregroundStyle(Palette.muted)
                Text(SubtitleSizeWords.title)
                    .font(.system(size: 56, weight: .heavy))
                    .foregroundStyle(Palette.text)
                    .accessibilityAddTraits(.isHeader)
                Text(PanelWords.sizeLede)
                    .font(.system(size: 26))
                    .foregroundStyle(Palette.textSecondary)
                HStack(spacing: 18) {
                    ForEach(SubtitleSize.allCases, id: \.self) { size in
                        let saved = size == model.subtitleSize
                        Button { Task { await model.setSubtitleSize(size) } } label: {
                            // The tick in the words, so the focused button doesn't enlarge it.
                            if saved { Text("\(Image(systemName: "checkmark")) \(size.label)") } else { Text(size.label) }
                        }
                        .buttonStyle(SizeChoiceStyle(saved: saved))
                        .focused($focused, equals: size)
                        .accessibilityAddTraits(saved ? .isSelected : [])
                        .accessibilityIdentifier("subtitle-size-\(size.rawValue)")
                    }
                }
                .padding(.top, 20)
                .focusSection()
                line
                Text(PanelWords.sizeHint)
                    .font(.system(size: 20))
                    .foregroundStyle(Palette.faint)
            }
            .padding(.top, 64)
            .padding(.horizontal, TVLayout.gutter)

            SubtitleCuePreview(PanelWords.sizeSample, baseSize: 46, size: shown)
                .padding(.bottom, 80)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
                .accessibilityIdentifier("subtitle-size-preview")
        }
        .ignoresSafeArea()
        .defaultFocus($focused, model.subtitleSize)
        .onExitCommand(perform: onClose)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("subtitle-size-screen")
    }

    /// What Normal follows, or how the last save went.
    @ViewBuilder private var line: some View {
        let state = model.saveState(.subtitleSize)
        Group {
            switch state {
            case .rest: Text(SubtitleSizeWords.normalFollows).foregroundStyle(Palette.textSecondary)
            case .saving: Text("Saving…").foregroundStyle(Palette.textSecondary)
            case .saved(let text): Text("\(Text("✓").foregroundStyle(Palette.gold).fontWeight(.bold)) \(text)").foregroundStyle(Palette.textSecondary)
            case .failed(let text): Text(text).foregroundStyle(Palette.warning)
            }
        }
        .font(.system(size: 22))
        .fixedSize(horizontal: false, vertical: true)
        .accessibilityIdentifier("subtitle-size-line")
    }
}

/// A size's button: gold tinted with a tick when it is the one saved,
/// white when focused.
private struct SizeChoiceStyle: ButtonStyle {
    let saved: Bool

    func makeBody(configuration: Configuration) -> some View {
        Styled(configuration: configuration, saved: saved)
    }

    private struct Styled: View {
        let configuration: Configuration
        let saved: Bool
        @Environment(\.isFocused) private var focused

        var body: some View {
            configuration.label
                .font(.system(size: 26, weight: .semibold))
                .foregroundStyle(focused ? Palette.ground : saved ? Palette.gold : Palette.text)
                .padding(.horizontal, 34)
                .frame(minWidth: 150, minHeight: 64)
                .background(focused ? Palette.text : saved ? Palette.gold.opacity(0.16) : .white.opacity(0.10), in: RoundedRectangle(cornerRadius: 14))
                .focusLift(focused, scale: 1.06)
                .scaleEffect(configuration.isPressed ? 0.97 : 1)
        }
    }
}

/// A dark scene with a horizon and a soft light, where subtitles sit.
private struct SizeBackdrop: View {
    var body: some View {
        ZStack {
            LinearGradient(stops: [.init(color: Color(hex: 0x0F0F10), location: 0), .init(color: Color(hex: 0x0F0F10), location: 0.42),
                                   .init(color: Color(hex: 0x1B3542), location: 0.58), .init(color: Color(hex: 0x0A1216), location: 0.6),
                                   .init(color: Color(hex: 0x06090B), location: 1)],
                           startPoint: .top, endPoint: .bottom)
            // Drawn small and scaled up: a radial light is slow to draw at full size.
            Ellipse()
                .fill(Color(red: 1, green: 0.8, blue: 0.47).opacity(0.14))
                .frame(width: 900, height: 260)
                .blur(radius: 80)
                .offset(x: 120, y: 120)
        }
        .ignoresSafeArea()
        .accessibilityHidden(true)
    }
}
