import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// What the remote's channel keys put over Live TV: Go to channel's keypad,
/// from the 123 key or the Channels tab. Page Up and Page Down need nothing
/// of their own: the banner names the new channel at once.
struct LiveChannelKeysLayer: View {
    let model: TVLivePlayerModel

    var body: some View {
        ZStack {
            if let goTo = model.goTo {
                GoToChannelDialog(model: goTo, listings: model.listings, onClose: { model.closeGoToChannel() })
                    .transition(.opacity)
            }
        }
        .animation(.easeOut(duration: 0.2), value: model.goTo != nil)
    }
}

/// Go to channel: what was typed, the keypad, and the matching channels
/// with what is on each. The live player, the guide and the channels page open it.
struct GoToChannelDialog: View {
    let model: GoToChannelModel
    let listings: Bool
    let onClose: () -> Void
    @FocusState private var focus: String?

    var body: some View {
        ZStack {
            Color(hex: 0x080809).opacity(0.72).ignoresSafeArea()
            HStack(alignment: .top, spacing: 64) {
                VStack(alignment: .leading, spacing: 22) {
                    Text(GoToChannelModel.title)
                        .font(.tv(.headline, .bold))
                        .accessibilityAddTraits(.isHeader)
                    HStack(spacing: 6) {
                        Text(model.typed)
                            .font(.system(size: 52, weight: .bold))
                            .monospacedDigit()
                            .tracking(2)
                            .accessibilityIdentifier("live-goto-typed")
                        Rectangle().fill(Palette.gold).frame(width: 3, height: 56).accessibilityHidden(true)
                        Spacer(minLength: 0)
                    }
                    .padding(.horizontal, 28)
                    .frame(width: 316, height: 96)
                    .background(.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 18))
                    .overlay(RoundedRectangle(cornerRadius: 18).strokeBorder(Palette.gold.opacity(0.6), lineWidth: 2))
                    LazyVGrid(columns: Array(repeating: GridItem(.fixed(96), spacing: 14), count: 3), spacing: 14) {
                        ForEach(GoToChannelModel.keys, id: \.self) { key in
                            Button { model.press(key) } label: {
                                Text(String(key)).font(.tv(.callout))
                            }
                            .buttonStyle(KeyStyle())
                            .focused($focus, equals: "key-\(key)")
                            .accessibilityLabel(key == "⌫" ? "Delete" : key == "." ? "Dot" : String(key))
                            .accessibilityIdentifier("live-goto-key-\(key == "⌫" ? "delete" : key == "." ? "dot" : String(key))")
                        }
                    }
                    .frame(width: 316)
                    .focusSection()
                }
                VStack(alignment: .leading, spacing: 10) {
                    TVGroupLabel(GoToChannelModel.matchesHeading)
                        .padding(.bottom, 10)
                    TimelineView(.periodic(from: .now, by: 30)) { context in
                        VStack(alignment: .leading, spacing: 10) {
                            ForEach(Array(model.matches.enumerated()), id: \.element.id) { index, channel in
                                Button { model.choose(channel) } label: {
                                    MatchRow(channel: channel, first: index == 0, now: listings ? context.date : nil)
                                }
                                .buttonStyle(MatchStyle(first: index == 0))
                                .focused($focus, equals: "match-\(channel.id)")
                                .accessibilityIdentifier("live-goto-match-\(channel.id)")
                            }
                            if let note = model.emptyNote {
                                Text(note).font(.tv(.body)).foregroundStyle(Palette.muted).padding(.horizontal, 20)
                            }
                        }
                    }
                    Spacer(minLength: 0)
                    Text(GoToChannelModel.help)
                        .font(.tv(.caption2))
                        .foregroundStyle(Palette.muted)
                        .lineSpacing(6)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
                // The whole column, so Right from any key reaches the matches.
                .focusSection()
            }
            .foregroundStyle(Palette.text)
            .padding(.horizontal, 56)
            .padding(.vertical, 52)
            .frame(width: 1400, height: 760, alignment: .topLeading)
            .background(Palette.sheet, in: RoundedRectangle(cornerRadius: 32))
            .shadow(color: .black.opacity(0.7), radius: 60, y: 40)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("live-goto")
        .defaultFocus($focus, "key-1", priority: .userInitiated)
        // Digit keys on a remote or keyboard type here, whatever has focus.
        .onKeyPress(characters: CharacterSet(charactersIn: "0123456789.")) { press in
            press.characters.forEach(model.press)
            return .handled
        }
        // Choosing among the matches: nothing tunes by itself meanwhile.
        .onChange(of: focus) { _, new in
            if new?.hasPrefix("match-") == true { model.hold() } else if new != nil { model.moved() }
        }
        .onExitCommand(perform: onClose)
        .onAppear { focus = "key-1" }
        .onDisappear { model.cancel() }
    }
}

private struct MatchRow: View {
    let channel: LiveChannel
    let first: Bool
    /// Now, when the channels have listings.
    let now: Date?
    @Environment(\.isFocused) private var focused

    var body: some View {
        HStack(spacing: 18) {
            Text(channel.number)
                .font(.tv(.callout, .bold))
                .monospacedDigit()
                .frame(width: 96, alignment: .leading)
            VStack(alignment: .leading, spacing: 2) {
                Text(channel.name).font(.tv(.body, .semibold)).lineLimit(1)
                if let line = onNow {
                    Text(line)
                        .font(.tv(.caption2))
                        .foregroundStyle(focused ? Color.tvOnWhiteMuted : Palette.muted)
                        .lineLimit(1)
                }
            }
            Spacer(minLength: 0)
        }
        .foregroundStyle(focused ? Palette.ground : Palette.text)
    }

    /// "Valley Bake-Off · until 9:00 PM".
    private var onNow: String? {
        guard let now, let programme = channel.programme(at: now) else { return nil }
        return "\(programme.title) · until \(LiveClock.device.time(programme.end))"
    }
}

/// A match: the first one lighter, as it is what tunes; focused, white.
private struct MatchStyle: ButtonStyle {
    let first: Bool

    func makeBody(configuration: Configuration) -> some View {
        Styled(configuration: configuration, first: first)
    }

    private struct Styled: View {
        let configuration: Configuration
        let first: Bool
        @Environment(\.isFocused) private var focused

        var body: some View {
            configuration.label
                .padding(.horizontal, 20)
                .frame(maxWidth: .infinity, minHeight: 88, alignment: .leading)
                .background(focused ? Palette.text : .white.opacity(first ? 0.10 : 0), in: RoundedRectangle(cornerRadius: 14))
                .focusLift(focused, scale: 1.02)
        }
    }
}

/// A key: 96×80; focused, it grows and turns white.
private struct KeyStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        Styled(configuration: configuration)
    }

    private struct Styled: View {
        let configuration: Configuration
        @Environment(\.isFocused) private var focused

        var body: some View {
            configuration.label
                .foregroundStyle(focused ? Palette.ground : Palette.text)
                .frame(width: 96, height: 80)
                .background(focused ? Palette.text : .white.opacity(0.10), in: RoundedRectangle(cornerRadius: 16))
                .focusLift(focused, scale: 1.08)
                .scaleEffect(configuration.isPressed ? 0.95 : 1)
        }
    }
}
