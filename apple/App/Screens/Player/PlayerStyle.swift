import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// The player's own colours and type, from the player artboards
/// (design/mockups/ios/Player*.dc.html).
enum PlayerStyle {
    /// Light text over video.
    static let light = Color(hex: 0xE5E5E5)
    /// The ground behind the credits and the stopped states.
    static let deepGround = Color(hex: 0x070708)
    /// Round buttons over video.
    static let scrim = Color.black.opacity(0.4)
    /// The picker's segmented control and grabber.
    static let control = Color(hex: 0x48484D)
    static let time = Font.system(size: 13, weight: .semibold).monospacedDigit()
    static let title = Font.system(size: 14, weight: .semibold)
}

/// The player's sizes: the iPhone artboards', or the iPad's
/// (PadPlayerControls.dc.html) on a screen that is regular both ways, an
/// iPad full screen or in a wide Split View. An iPhone in landscape, even a
/// large one, keeps the iPhone's, since its height is compact.
struct PlayerMetrics: Equatable {
    /// The round buttons along the top, and their icons.
    var button: CGFloat
    var buttonIcon: CGFloat
    /// Back and forward 10 s, and play/pause.
    var seekButton: CGFloat
    var seekIcon: CGFloat
    var playButton: CGFloat
    var playIcon: CGFloat
    var transportSpacing: CGFloat
    var title: Font
    var time: Font
    var skipFont: Font
    var skipHeight: CGFloat
    /// The least space at the sides, and above the top bar.
    var edge: CGFloat
    var topGap: CGFloat
    /// Under the bottom bar, past the home indicator.
    var bottomGap: CGFloat
    /// How far above the bottom bar Skip sits while the controls show.
    var skipLift: CGFloat
    /// The line under the title saying how the server sends the file ("Direct play · 4K HDR10").
    var streamChip: Bool
    /// The tablet picker is a trailing panel; compact widths retain the bottom sheet.
    var sidePanelPicker: Bool
    /// The seek thumbnail's width while scrubbing (PlayerScrub.dc.html), its corners, and the gap to its time.
    var tileWidth: CGFloat = 200
    var tileRadius: CGFloat = 8
    var tileGap: CGFloat = 6

    static let phone = PlayerMetrics(
        button: 44, buttonIcon: 20, seekButton: 52, seekIcon: 30, playButton: 72, playIcon: 44, transportSpacing: 56,
        title: .system(size: 14, weight: .semibold), time: PlayerStyle.time,
        skipFont: .system(size: 15, weight: .semibold), skipHeight: 44,
        edge: 16, topGap: 16, bottomGap: 5, skipLift: 56, streamChip: false, sidePanelPicker: false
    )

    static let pad = PlayerMetrics(
        button: 48, buttonIcon: 22, seekButton: 64, seekIcon: 40, playButton: 96, playIcon: 60, transportSpacing: 80,
        title: .system(size: 16, weight: .semibold), time: .system(size: 14, weight: .semibold).monospacedDigit(),
        skipFont: .system(size: 16, weight: .semibold), skipHeight: 48,
        edge: 24, topGap: 24, bottomGap: 20, skipLift: 112, streamChip: true, sidePanelPicker: true,
        tileWidth: 256, tileRadius: 10, tileGap: 8
    )

    /// The iPad's on a screen regular both ways, the iPhone's otherwise.
    static func `for`(horizontal: UserInterfaceSizeClass?, vertical: UserInterfaceSizeClass?) -> PlayerMetrics {
        horizontal == .regular && vertical == .regular ? .pad : .phone
    }
}

/// A round icon-only button over the video: 44 pt, with its label for VoiceOver.
struct RoundIconButton: View {
    let systemImage: String
    let label: String
    var size: CGFloat = 44
    var iconSize: CGFloat = 20
    var weight: Font.Weight = .semibold
    var background: Color = PlayerStyle.scrim
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.system(size: iconSize, weight: weight))
                .foregroundStyle(Palette.text)
                .frame(width: size, height: size)
                .background(background, in: Circle())
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }
}

/// The outlined secondary button of the player's cards ("Watch credits", "Stop").
struct OutlineButtonStyle: ButtonStyle {
    var height: CGFloat = 44
    var cornerRadius: CGFloat = 9
    var fullWidth = false

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: height > 44 ? 15 : 14, weight: .semibold))
            .foregroundStyle(PlayerStyle.light)
            .lineLimit(1)
            .padding(.horizontal, 12)
            .frame(maxWidth: fullWidth ? .infinity : nil, minHeight: height)
            .background(.white.opacity(configuration.isPressed ? 0.12 : 0), in: RoundedRectangle(cornerRadius: cornerRadius))
            .overlay(RoundedRectangle(cornerRadius: cornerRadius).strokeBorder(.white.opacity(0.28)))
            .contentShape(RoundedRectangle(cornerRadius: cornerRadius))
    }
}

/// A small label on artwork, as written ("48 min"); ``Badge`` uppercases.
struct PlainBadge: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.system(size: 10, weight: .bold))
            .padding(.horizontal, 7)
            .padding(.vertical, 3)
            .foregroundStyle(PlayerStyle.light)
            .background(.black.opacity(0.7), in: RoundedRectangle(cornerRadius: 5))
    }
}

/// A gold arc turning: loading and buffering. With Reduce Motion it stays
/// still and breathes instead (PLAN-APPLE-REVIEW.md §6.3).
struct SpinnerRing: View {
    var size: CGFloat = 56
    var lineWidth: CGFloat = 3
    @State private var turning = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        Circle()
            .trim(from: 0, to: 0.28)
            .stroke(Palette.gold, style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
            .frame(width: size, height: size)
            .rotationEffect(.degrees(turning && !reduceMotion ? 360 : 0))
            .opacity(reduceMotion && turning ? 0.45 : 1)
            .animation(reduceMotion ? .easeInOut(duration: 1).repeatForever(autoreverses: true)
                                    : .linear(duration: 0.9).repeatForever(autoreverses: false), value: turning)
            .onAppear { turning = true }
            .accessibilityElement()
            .accessibilityLabel("Loading")
    }
}

/// Elapsed, a short bar and the length: beside the Next Episode card and under Still watching.
struct MiniProgress: View {
    let positionMs: Int
    let durationMs: Int

    var body: some View {
        HStack(spacing: 10) {
            Text(formatClock(ms: positionMs))
            ZStack(alignment: .leading) {
                Capsule().fill(.white.opacity(0.18))
                Capsule().fill(Palette.gold).frame(width: 220 * fraction(ofMs: positionMs, durationMs: durationMs))
            }
            .frame(width: 220, height: 4)
            Text(formatClock(ms: durationMs))
        }
        .font(.system(size: 12).monospacedDigit())
        .foregroundStyle(Palette.muted)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(scrubberAccessibilityValue(positionMs: positionMs, durationMs: durationMs))
    }
}
