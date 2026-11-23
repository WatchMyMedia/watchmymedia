import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// Up next, at the foot of the screen while the credits roll and the
/// transport bar is hidden: the episode's still, "Up next", its title and
/// number, and Play in 8, whose fill follows the countdown, with focus
/// ("Play now" with autoplay off). Watch credits puts it away until the
/// end, as Back does.
struct UpNextCard: View {
    let prompt: UpNextPrompt
    let still: URL?
    let onPlay: () -> Void
    let onWatchCredits: () -> Void
    @FocusState private var focus: Choice?

    private enum Choice { case play, credits }

    var body: some View {
        let next = prompt.next
        HStack(alignment: .center, spacing: 32) {
            Artwork(url: still, seed: "\(next.showTitle) \(next.title)", maxPixel: 720)
                .frame(width: 320, height: 180)
                .clipShape(RoundedRectangle(cornerRadius: 14))
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 8) {
                Text("Up next").font(.tv(.caption1)).foregroundStyle(Palette.muted)
                Text(next.title).font(.tv(.callout)).foregroundStyle(Palette.text).lineLimit(2)
                Text(next.tvMeta).font(.tv(.caption1)).foregroundStyle(Palette.textSecondary)
                HStack(spacing: 16) {
                    Button(action: onPlay) { Text(prompt.tvButtonLabel).monospacedDigit() }
                    .buttonStyle(CountdownButtonStyle(progress: prompt.countdownProgress))
                    .focused($focus, equals: .play)
                    Button("Watch credits", action: onWatchCredits)
                        .buttonStyle(TVButtonStyle(height: 72))
                        .focused($focus, equals: .credits)
                }
                .fixedSize()
                .padding(.top, 14)
            }
            .frame(minWidth: 420, alignment: .leading)
        }
        .padding(32)
        .background(Color(hex: 0x18181B).opacity(0.94), in: RoundedRectangle(cornerRadius: 28))
        .shadow(color: .black.opacity(0.6), radius: 40, y: 30)
        .focusSection()
        .defaultFocus($focus, .play)
        .onAppear { focus = .play }
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Up next")
    }
}

/// Up next's main button: burgundy with its rim, the countdown filling it
/// from the left; focused, white, with the fill a paler burgundy.
private struct CountdownButtonStyle: ButtonStyle {
    let progress: Double

    func makeBody(configuration: Configuration) -> some View {
        Styled(configuration: configuration, progress: progress)
    }

    private struct Styled: View {
        let configuration: Configuration
        let progress: Double
        @Environment(\.isFocused) private var focused

        var body: some View {
            let pressed = configuration.isPressed && focused
            configuration.label
                .font(.tv(.body, .semibold))
                .lineLimit(1)
                .foregroundStyle(focused ? Palette.ground : Palette.text)
                .padding(.horizontal, 28)
                .frame(height: 72)
                .background {
                    GeometryReader { geo in
                        ZStack(alignment: .leading) {
                            focused ? (pressed ? Color.tvPressed : Palette.text) : Palette.burgundy
                            (focused ? Palette.burgundy.opacity(0.3) : Palette.burgundyEdge.opacity(0.55)).frame(width: geo.size.width * progress)
                        }
                    }
                    .animation(.linear(duration: 0.25), value: progress)
                }
                .clipShape(RoundedRectangle(cornerRadius: 16))
                .overlay { if !focused { RoundedRectangle(cornerRadius: 16).strokeBorder(Palette.burgundyEdge, lineWidth: 2) } }
                .scaleEffect(focused && !pressed ? 1.08 : 1)
                .shadow(color: .black.opacity(focused ? 0.65 : 0), radius: focused ? 30 : 0, y: focused ? 24 : 0)
                .animation(.easeOut(duration: 0.15), value: focused)
        }
    }
}

/// "Are you still watching?": nothing plays until the viewer answers, and
/// only a button answers. Focus starts on Keep watching; Back is Stop.
struct StillWatchingPrompt: View {
    /// "Harbor Lights · S03 E07 The Long Dark".
    let title: String
    let onAnswer: (Bool) -> Void
    @FocusState private var focus: Bool?

    var body: some View {
        TVSheet {
            Text("Are you still watching?")
                .font(.tv(.title3))
                .foregroundStyle(Palette.text)
                .accessibilityAddTraits(.isHeader)
            if !title.isEmpty {
                Text(title).font(.tv(.body)).foregroundStyle(Palette.textSecondary).lineLimit(2)
            }
            HStack(spacing: 20) {
                Button { onAnswer(true) } label: { Text("\(Image(systemName: "play.fill")) Keep watching") }
                    .buttonStyle(TVButtonStyle(kind: .primary))
                    .focused($focus, equals: true)
                    .accessibilityLabel("Keep watching")
                Button("Stop") { onAnswer(false) }
                    .buttonStyle(TVButtonStyle())
                    .focused($focus, equals: false)
            }
            .padding(.top, 12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .focusSection()
            Text(tvStillWatchingNote).font(.tv(.caption1)).foregroundStyle(Palette.muted)
        }
        .defaultFocus($focus, true)
        .onAppear { focus = true }
        .accessibilityElement(children: .contain)
        .accessibilityAddTraits(.isModal)
    }
}

/// The stopped and can't-play states, on black with the video gone, each
/// saying why in a sentence and opening with focus on the thing to try.
/// Can't play offers Try again, the quality below as a button (Play at
/// 1080p) and Back; for an admin, Details shows the server's own words,
/// which nobody else sees. Stopped gives the admin's message and Back.
struct PlayerEndState: View {
    enum Kind {
        case stopped(message: String, line: String, note: String?)
        case failed(words: TVCantPlay, lower: StreamQuality?, details: String?)
    }

    let kind: Kind
    let onBack: () -> Void
    var onRetry: () -> Void = {}
    var onLower: () -> Void = {}
    @FocusState private var focus: Choice?
    @State private var showsDetails = false

    private enum Choice { case primary, lower, back, details }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            TVSheet {
                switch kind {
                case .stopped(let message, let line, let note):
                    heading("Playback stopped")
                    Text(line).font(.tv(.body)).foregroundStyle(Palette.textSecondary)
                    Text("“\(message)”").font(.tv(.headline)).foregroundStyle(Palette.text).fixedSize(horizontal: false, vertical: true)
                    buttons
                    if let note { Text(note).font(.tv(.caption1)).foregroundStyle(Palette.muted) }
                case .failed(let words, _, let details):
                    heading(words.title)
                    Text(words.message).font(.tv(.body)).foregroundStyle(Palette.textSecondary).fixedSize(horizontal: false, vertical: true)
                    buttons
                    if showsDetails, let details {
                        Text(details)
                            .font(.tv(.caption2))
                            .foregroundStyle(Palette.muted)
                            .fixedSize(horizontal: false, vertical: true)
                            .accessibilityIdentifier("player-failure-details")
                    }
                }
            }
        }
        .defaultFocus($focus, .primary)
        .onAppear { focus = .primary }
    }

    private func heading(_ text: String) -> some View {
        Text(text)
            .font(.tv(.title3))
            .foregroundStyle(Palette.text)
            .accessibilityAddTraits(.isHeader)
    }

    @ViewBuilder
    private var buttons: some View {
        HStack(spacing: 20) {
            switch kind {
            case .stopped:
                Button("Back", action: onBack)
                    .buttonStyle(TVButtonStyle(kind: .primary))
                    .focused($focus, equals: .primary)
            case .failed(_, let lower, let details):
                Button("Try again", action: onRetry)
                    .buttonStyle(TVButtonStyle(kind: .primary))
                    .focused($focus, equals: .primary)
                if let lower {
                    Button(tvPlayAtLabel(lower), action: onLower)
                        .buttonStyle(TVButtonStyle())
                        .focused($focus, equals: .lower)
                }
                Button("Back", action: onBack)
                    .buttonStyle(TVButtonStyle())
                    .focused($focus, equals: .back)
                if details != nil {
                    Button(showsDetails ? "Hide details" : "Details") { showsDetails.toggle() }
                        .buttonStyle(TVButtonStyle())
                        .focused($focus, equals: .details)
                }
            }
        }
        .padding(.top, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .focusSection()
    }
}
