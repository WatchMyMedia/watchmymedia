import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// "Are you still watching?" (PlayerStillWatching.dc.html): nothing plays
/// until the viewer answers.
struct StillWatchingDialog: View {
    let state: PlayerState
    let onAnswer: (Bool) -> Void

    var body: some View {
        ZStack {
            Color.black.opacity(0.45).accessibilityHidden(true)
            VStack(spacing: 8) {
                if !state.title.isEmpty {
                    Text(state.title.uppercased())
                        .font(.system(size: 11, weight: .bold))
                        .tracking(1.3)
                        .foregroundStyle(Palette.gold)
                }
                Text("Are you still watching?")
                    .font(.system(size: 24, weight: .bold))
                    .foregroundStyle(Palette.text)
                    .accessibilityAddTraits(.isHeader)
                Text(stillWatchingMessage(next: state.nextEpisode))
                    .font(.system(size: 14))
                    .foregroundStyle(Palette.muted)
                    .lineSpacing(3)
                HStack(spacing: 10) {
                    Button { onAnswer(true) } label: {
                        Label("Keep watching", systemImage: "play.fill")
                    }
                    .buttonStyle(PrimaryButtonStyle(height: 46))
                    Button("Stop") { onAnswer(false) }
                        .buttonStyle(OutlineButtonStyle(height: 46, cornerRadius: 10, fullWidth: true))
                }
                .padding(.top, 10)
            }
            .multilineTextAlignment(.center)
            .padding(EdgeInsets(top: 22, leading: 24, bottom: 20, trailing: 24))
            .frame(maxWidth: 440)
            .background(Palette.ground.opacity(0.96), in: RoundedRectangle(cornerRadius: 16))
            .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(.white.opacity(0.16)))
            .shadow(color: .black.opacity(0.7), radius: 25, y: 20)
            .padding(24)
            .accessibilityElement(children: .contain)
            .accessibilityAddTraits(.isModal)
            .accessibilityAction(.escape) { onAnswer(true) }
        }
    }
}

/// The stopped and can't-play states (PlayerStopped.dc.html): the admin's
/// message with Back, or why it failed with Try again and Back.
struct PlayerEndState: View {
    enum Kind {
        case stopped(message: String, note: String?)
        case failed(message: String, note: String?)
    }

    let kind: Kind
    let title: String
    let insets: PlayerInsets
    let onBack: () -> Void
    let onRetry: () -> Void

    var body: some View {
        ZStack(alignment: .top) {
            PlayerStyle.deepGround.opacity(0.94)
            ZStack {
                Text(title)
                    .font(PlayerStyle.title)
                    .foregroundStyle(PlayerStyle.light)
                    .lineLimit(1)
                    .padding(.horizontal, 120)
                    .opacity(0.6)
                HStack {
                    RoundIconButton(systemImage: "xmark", label: "Close", background: .white.opacity(0.1), action: onBack)
                    Spacer()
                }
            }
            .padding(.leading, insets.leading)
            .padding(.trailing, insets.trailing)
            .padding(.top, insets.top)

            VStack(spacing: 10) {
                icon
                Text(heading)
                    .font(.system(size: 24, weight: .bold))
                    .foregroundStyle(Palette.text)
                    .padding(.top, 4)
                    .accessibilityAddTraits(.isHeader)
                Text(message)
                    .font(.system(size: 15))
                    .foregroundStyle(Palette.textSecondary)
                    .lineSpacing(3)
                if let note {
                    Text(note).font(.system(size: 12)).foregroundStyle(Palette.faint)
                }
                buttons.padding(.top, 10)
            }
            .multilineTextAlignment(.center)
            .frame(maxWidth: 540)
            .padding(.horizontal, 24)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .accessibilityAction(.escape, onBack)
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
                .font(.system(size: 24))
                .foregroundStyle(Palette.text)
                .frame(width: 60, height: 60)
                .background(Palette.burgundy, in: Circle())
                .accessibilityHidden(true)
        case .failed:
            Image(systemName: "exclamationmark.triangle")
                .font(.system(size: 26, weight: .semibold))
                .foregroundStyle(Palette.ground)
                .frame(width: 60, height: 60)
                .background(Palette.warning, in: Circle())
                .accessibilityHidden(true)
        }
    }

    @ViewBuilder
    private var buttons: some View {
        switch kind {
        case .stopped:
            Button(action: onBack) { Text("Back").frame(minWidth: 114) }
                .buttonStyle(PrimaryButtonStyle(height: 46, fullWidth: false))
        case .failed:
            HStack(spacing: 10) {
                Button("Try again", action: onRetry)
                    .buttonStyle(PrimaryButtonStyle(height: 46, fullWidth: false))
                Button("Back", action: onBack)
                    .buttonStyle(GhostButtonStyle(height: 46, fullWidth: false))
            }
        }
    }
}

/// "Auto · 720p" (PlayerControls.dc.html): the step Automatic quality is at, in gold.
struct AutoChip: View {
    let text: String
    let size: CGFloat

    var body: some View {
        Text(text)
            .font(.system(size: size, weight: .semibold))
            .foregroundStyle(Palette.gold)
            .lineLimit(1)
            .padding(.horizontal, size - 3)
            .padding(.vertical, 2)
            .background(.black.opacity(0.45), in: Capsule())
            .overlay(Capsule().strokeBorder(Palette.gold.opacity(0.7)))
            .accessibilityLabel(text.replacingOccurrences(of: "Auto ·", with: "Automatic quality,"))
    }
}

/// "Lowered to 720p for your connection." under the top bar for a few
/// seconds (PlayerControls.dc.html), with or without the controls.
struct QualityNote: View {
    let text: String
    let large: Bool

    var body: some View {
        Text(text)
            .font(.system(size: large ? 15 : 13, weight: .medium))
            .foregroundStyle(Palette.text)
            .lineLimit(1)
            .padding(.horizontal, large ? 16 : 14)
            .padding(.vertical, large ? 9 : 7)
            .background(Palette.ground.opacity(0.88), in: Capsule())
            .overlay(Capsule().strokeBorder(.white.opacity(0.18)))
            .shadow(color: .black.opacity(0.5), radius: 12, y: 8)
            .accessibilityAddTraits(.isStaticText)
    }
}
