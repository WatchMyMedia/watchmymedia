import AVFoundation
import SwiftUI
import WMMFeatures
import WMMUI

// Fit and fill on iPhone and iPad (PLAN-APPLE-REVIEW.md §6.3,
// design/mockups/ios/PlayerFitFill.dc.html): the button beside Close, the
// pinch on the picture and the note under the top bar. The rule and the
// remembered choice are ``VideoFitModel``'s; the shell (``PlayerShell``) owns
// one for whichever player it holds and hands it out in ``PlayerFrame``.

/// How the video layer draws the picture: Fit inside the safe area (clear of
/// the Dynamic Island, as the system player keeps it), Fill over the whole
/// screen, cropped and never stretched.
struct VideoLayout: Equatable {
    var gravity: AVLayerVideoGravity = .resizeAspect
    var insets = EdgeInsets()
}

@MainActor
extension PlayerFrame {
    /// The video layer's gravity and insets for the current choice.
    var videoLayout: VideoLayout {
        fit.filled
            ? VideoLayout(gravity: .resizeAspectFill)
            : VideoLayout(gravity: .resizeAspect, insets: EdgeInsets(top: 0, leading: safeArea.leading, bottom: 0, trailing: safeArea.trailing))
    }

    /// Where the picture is drawn: the whole screen, or the 16:9 box at the
    /// top of a tall screen (`portrait`).
    func videoArea(portrait: Bool) -> CGSize {
        portrait ? CGSize(width: size.width, height: size.width * 9 / 16) : size
    }
}

/// The round Fill button beside Close: arrows out while fitted; gold, with
/// arrows in, while filled.
struct VideoFitButton: View {
    let frame: PlayerFrame
    /// The area the picture is drawn in, for how much Fill crops; the whole screen when nil.
    var area: CGSize?
    let metrics: PlayerMetrics
    /// Keeps the controls up, as any other button does.
    let onInput: () -> Void

    var body: some View {
        let fit = frame.fit
        Button {
            onInput()
            fit.toggle(video: frame.videoSize(), screen: area ?? frame.size)
        } label: {
            Image(systemName: fit.buttonSymbol)
                .font(.system(size: metrics.buttonIcon, weight: .semibold))
                .foregroundStyle(fit.filled ? Palette.gold : Palette.text)
                .frame(width: metrics.button, height: metrics.button)
                .background(fit.filled ? Palette.gold.opacity(0.22) : PlayerStyle.scrim, in: Circle())
                .overlay {
                    if fit.filled { Circle().strokeBorder(Palette.gold.opacity(0.55), lineWidth: 1) }
                }
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(fit.buttonLabel)
        .accessibilityAddTraits(fit.filled ? .isSelected : [])
        .accessibilityIdentifier("player-fit-fill")
    }
}

/// "Fill · 9% of the picture is cropped at the edges", centred under the top bar for a moment.
struct VideoFitNote: View {
    let fit: VideoFitModel

    var body: some View {
        ZStack {
            if let note = fit.note {
                Text(note)
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(Palette.text)
                    .lineLimit(1)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 7)
                    .background(Color(hex: 0x0F0F10).opacity(0.88), in: Capsule())
                    .overlay(Capsule().strokeBorder(.white.opacity(0.18)))
                    .shadow(color: .black.opacity(0.5), radius: 12, y: 8)
                    .accessibilityIdentifier("player-fit-note")
                    .accessibilityAddTraits(.updatesFrequently)
                    .transition(.opacity)
            }
        }
        .animation(.easeInOut(duration: 0.2), value: fit.note)
        .allowsHitTesting(false)
        .onChange(of: fit.note) { _, note in
            // The board's note is a status: VoiceOver hears it too.
            if let note { AccessibilityNotification.Announcement(note).post() }
        }
    }
}

extension VideoFitModel {
    /// The players' model: `-playerFitNoteMs 8000` (DEBUG) keeps the note up
    /// longer, so a UI test on a busy machine sees it.
    static func forPlayer() -> VideoFitModel {
        #if DEBUG
        if let ms = ScreenshotLaunch.value(after: "-playerFitNoteMs").flatMap(Int.init) { return VideoFitModel(noteMs: ms) }
        #endif
        return VideoFitModel()
    }
}

extension View {
    /// Pinch out on the picture fills, pinch in fits; taps and double taps
    /// on the same view keep working.
    func pinchToFit(_ frame: PlayerFrame, area: CGSize? = nil) -> some View {
        simultaneousGesture(
            MagnifyGesture()
                .onChanged { frame.fit.pinchChanged(scale: $0.magnification) }
                .onEnded { frame.fit.pinchEnded(scale: $0.magnification, video: frame.videoSize(), screen: area ?? frame.size) }
        )
    }
}
