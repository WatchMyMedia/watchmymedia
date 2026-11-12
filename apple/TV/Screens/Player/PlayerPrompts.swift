import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// The Next Episode card (TvPlayerNext.dc.html), in the corner while the
/// transport bar is hidden: the still, the episode, and a button that fills
/// as the countdown runs ("Next episode in 7", or "Play now" with autoplay
/// off), with focus; Watch credits puts it away until the end, as Menu does.
struct NextEpisodeCard: View {
    let prompt: UpNextPrompt
    let still: URL?
    let onPlay: () -> Void
    let onWatchCredits: () -> Void
    @FocusState private var focus: Choice?

    private enum Choice { case play, credits }

    var body: some View {
        let next = prompt.next
        VStack(alignment: .leading, spacing: 0) {
            Color.clear
                .frame(width: 440, height: 248)
                .overlay { Artwork(url: still, seed: "\(next.showTitle) \(next.title)", maxPixel: 880) }
                .overlay(alignment: .topLeading) { TVBadge("Next episode").padding(14) }
                .overlay(alignment: .bottomTrailing) {
                    if let runtime = next.runtimeMs { TVBadge(formatDuration(ms: runtime)).padding(14) }
                }
                .clipShape(UnevenRoundedRectangle(topLeadingRadius: 22, topTrailingRadius: 22))
                .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 6) {
                Text(next.cardTitle).font(.system(size: 24, weight: .bold)).foregroundStyle(Palette.text)
                Text(next.cardMeta).font(.system(size: 18)).foregroundStyle(Palette.muted)
                HStack(spacing: 12) {
                    Button(action: onPlay) {
                        HStack(spacing: 10) {
                            Image(systemName: "play.fill").font(.system(size: 18))
                            Text(prompt.buttonLabel).monospacedDigit().minimumScaleFactor(0.8)
                        }
                    }
                    .buttonStyle(CountdownButtonStyle(progress: prompt.countdownProgress))
                    .focused($focus, equals: .play)
                    Button(action: onWatchCredits) { Text("Watch credits").fixedSize().padding(.horizontal, -10) }
                        .buttonStyle(TVButtonStyle(height: 60, fontSize: 20))
                        .focused($focus, equals: .credits)
                }
                .padding(.top, 16)
            }
            .padding(EdgeInsets(top: 20, leading: 22, bottom: 22, trailing: 22))
        }
        .frame(width: 440)
        .background(Color(hex: 0x1C1C1E).opacity(0.92), in: RoundedRectangle(cornerRadius: 22))
        .shadow(color: .black.opacity(0.7), radius: 40, y: 30)
        .focusSection()
        .defaultFocus($focus, .play)
        .onAppear { focus = .play }
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Next episode")
    }
}

/// The card's main button: burgundy fills it from the left as the countdown runs.
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
            configuration.label
                .font(.system(size: 20, weight: .semibold))
                .lineLimit(1)
                .foregroundStyle(focused ? Palette.ground : Palette.text)
                .padding(.horizontal, 16)
                .frame(maxWidth: .infinity, minHeight: 60)
                .background {
                    GeometryReader { geo in
                        ZStack(alignment: .leading) {
                            focused ? Palette.text : .white.opacity(0.14)
                            Palette.burgundy.opacity(focused ? 0.35 : 1).frame(width: geo.size.width * progress)
                        }
                    }
                    .animation(.linear(duration: 0.25), value: progress)
                }
                .clipShape(RoundedRectangle(cornerRadius: 16))
                .focusRim(focused, cornerRadius: 16, scale: 1.06)
                .scaleEffect(configuration.isPressed ? 0.96 : 1)
        }
    }
}

/// "Are you still watching?" (TvPlayerStillWatching.dc.html): nothing plays
/// until the viewer answers. Focus starts on Keep watching; Menu and
/// Play/Pause answer it too.
struct StillWatchingPrompt: View {
    let state: PlayerState
    let onAnswer: (Bool) -> Void
    @FocusState private var focus: Bool?

    var body: some View {
        ZStack {
            Color.black.opacity(0.5).accessibilityHidden(true)
            VStack(spacing: 16) {
                if !state.title.isEmpty {
                    Text(state.title.uppercased())
                        .font(.system(size: 18, weight: .bold))
                        .tracking(2.2)
                        .foregroundStyle(Palette.gold)
                }
                Text("Are you still watching?")
                    .font(.system(size: 48, weight: .bold))
                    .foregroundStyle(Palette.text)
                    .accessibilityAddTraits(.isHeader)
                Text(stillWatchingMessage(next: state.nextEpisode))
                    .font(.system(size: 22))
                    .foregroundStyle(Palette.muted)
                    .lineSpacing(9)
                    .frame(maxWidth: 680)
                HStack(spacing: 24) {
                    Button { onAnswer(true) } label: { Label("Keep watching", systemImage: "play.fill") }
                        .buttonStyle(TVButtonStyle(kind: .primary))
                        .focused($focus, equals: true)
                    Button("Stop") { onAnswer(false) }
                        .buttonStyle(TVButtonStyle())
                        .focused($focus, equals: false)
                }
                .padding(.top, 18)
                Text(stillWatchingRemoteNote)
                    .font(.system(size: 16))
                    .foregroundStyle(Palette.faint)
                    .padding(.top, 6)
            }
            .multilineTextAlignment(.center)
            .padding(EdgeInsets(top: 48, leading: 56, bottom: 44, trailing: 56))
            .frame(width: 880)
            .background(Color(hex: 0x1C1C1E).opacity(0.96), in: RoundedRectangle(cornerRadius: 28))
            .shadow(color: .black.opacity(0.7), radius: 50, y: 40)
            .focusSection()
            .defaultFocus($focus, true)
            .onAppear { focus = true }
            .accessibilityElement(children: .contain)
            .accessibilityAddTraits(.isModal)
        }
    }
}

/// The stopped and can't-play states (TvPlayerStopped.dc.html), on their
/// own with the video gone: the admin's message with Back, or why it failed
/// with Try again and Back.
struct PlayerEndState: View {
    enum Kind {
        case stopped(message: String, note: String?)
        case failed(message: String, note: String?)
    }

    let kind: Kind
    let title: String
    let onBack: () -> Void
    let onRetry: () -> Void
    @FocusState private var focus: Choice?

    private enum Choice { case primary, back }

    var body: some View {
        ZStack(alignment: .top) {
            Color(hex: 0x070708)
            Text(title)
                .font(.system(size: 20, weight: .semibold))
                .foregroundStyle(Color.tvTitle)
                .lineLimit(1)
                .opacity(0.6)
                .padding(.top, 48)
                .padding(.horizontal, TVLayout.gutter)

            VStack(spacing: 16) {
                icon
                Text(heading)
                    .font(.system(size: 48, weight: .bold))
                    .foregroundStyle(Palette.text)
                    .padding(.top, 8)
                    .accessibilityAddTraits(.isHeader)
                Text(message)
                    .font(.system(size: 24))
                    .foregroundStyle(Palette.textSecondary)
                    .lineSpacing(10)
                if let note {
                    Text(note).font(.system(size: 18)).foregroundStyle(Palette.faint)
                }
                buttons.padding(.top, 14)
            }
            .multilineTextAlignment(.center)
            .frame(maxWidth: 1120)
            .padding(.top, 300)
        }
        .defaultFocus($focus, .primary)
        .onAppear { focus = .primary }
    }

    private var heading: String {
        switch kind {
        case .stopped: "Playback stopped"
        case .failed: "Can’t play this"
        }
    }

    private var message: String {
        switch kind {
        case .stopped(let message, _): "“\(message)”"
        case .failed(let message, _): message
        }
    }

    private var note: String? {
        switch kind {
        case .stopped(_, let note), .failed(_, let note): note
        }
    }

    @ViewBuilder
    private var icon: some View {
        switch kind {
        case .stopped:
            Image(systemName: "stop.fill")
                .font(.system(size: 40))
                .foregroundStyle(Palette.text)
                .frame(width: 96, height: 96)
                .background(Palette.burgundy, in: Circle())
                .accessibilityHidden(true)
        case .failed:
            Image(systemName: "exclamationmark.triangle")
                .font(.system(size: 44, weight: .semibold))
                .foregroundStyle(Palette.ground)
                .frame(width: 96, height: 96)
                .background(Palette.warning, in: Circle())
                .accessibilityHidden(true)
        }
    }

    @ViewBuilder
    private var buttons: some View {
        switch kind {
        case .stopped:
            Button(action: onBack) { Text("Back").frame(minWidth: 140) }
                .buttonStyle(TVButtonStyle(kind: .primary))
                .focused($focus, equals: .primary)
        case .failed:
            HStack(spacing: 24) {
                Button(action: onRetry) { Text("Try again").frame(minWidth: 140) }
                    .buttonStyle(TVButtonStyle(kind: .primary))
                    .focused($focus, equals: .primary)
                Button(action: onBack) { Text("Back").frame(minWidth: 140) }
                    .buttonStyle(TVButtonStyle())
                    .focused($focus, equals: .back)
            }
        }
    }
}
