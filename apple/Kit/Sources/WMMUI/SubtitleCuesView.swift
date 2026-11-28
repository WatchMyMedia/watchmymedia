import Foundation
import SwiftUI
import WMMModel
#if canImport(MediaAccessibility)
import MediaAccessibility
#endif

/// How subtitles look: the system's caption settings (Settings ›
/// Accessibility › Subtitles & Captioning) where MediaAccessibility has
/// them, otherwise white text on a dark backing.
public struct CaptionStyle: Equatable, Sendable {
    /// The system's relative text size, 1 by default.
    public var scale: Double
    public var foreground: Color
    /// Behind the text itself.
    public var background: Color
    /// Behind the caption as a whole: the backing of the default style.
    public var window: Color
    /// Outline, raised or drop-shadow text edges draw a shadow.
    public var shadow: Bool

    public init(scale: Double = 1, foreground: Color = .white, background: Color = .clear, window: Color = .black.opacity(0.6), shadow: Bool = false) {
        self.scale = scale
        self.foreground = foreground
        self.background = background
        self.window = window
        self.shadow = shadow
    }

    public static let standard = CaptionStyle()

    /// The person's caption settings now.
    public static func system() -> CaptionStyle {
        #if canImport(MediaAccessibility)
        let domain = MACaptionAppearanceDomain.user
        let foreground = MACaptionAppearanceCopyForegroundColor(domain, nil).takeRetainedValue()
        let foregroundOpacity = MACaptionAppearanceGetForegroundOpacity(domain, nil)
        let background = MACaptionAppearanceCopyBackgroundColor(domain, nil).takeRetainedValue()
        let backgroundOpacity = MACaptionAppearanceGetBackgroundOpacity(domain, nil)
        let window = MACaptionAppearanceCopyWindowColor(domain, nil).takeRetainedValue()
        let windowOpacity = MACaptionAppearanceGetWindowOpacity(domain, nil)
        let scale = MACaptionAppearanceGetRelativeCharacterSize(domain, nil)
        let edge = MACaptionAppearanceGetTextEdgeStyle(domain, nil)
        return CaptionStyle(
            scale: scale > 0 ? Double(scale) : 1,
            foreground: Color(cgColor: foreground).opacity(Double(foregroundOpacity)),
            background: Color(cgColor: background).opacity(Double(backgroundOpacity)),
            window: Color(cgColor: window).opacity(Double(windowOpacity)),
            shadow: edge != .none && edge != .undefined
        )
        #else
        return .standard
        #endif
    }

    /// Posted when the person changes their caption settings.
    static var changed: Notification.Name {
        #if canImport(MediaAccessibility)
        Notification.Name(kMACaptionAppearanceSettingsChangedNotification as String)
        #else
        Notification.Name("WMMCaptionStyleChanged")
        #endif
    }
}

/// Text subtitles over the video: the cues on screen now, bottom centred,
/// a backing behind each line, `bottomPadding` up from the bottom (the
/// player lifts them clear of its controls, animated). Image subtitles
/// never reach here: the server burns them in. A line two cues share shows
/// once, as on Android (android/core/ui/.../SubtitleCues.kt). The text is
/// the player's base size × the system's caption size × the person's
/// Subtitle size (``cueSize(base:caption:size:)``).
public struct SubtitleCuesView: View {
    let cues: [SubtitleCue]
    let bottomPadding: CGFloat
    let sidePadding: CGFloat
    let baseSize: CGFloat
    let size: SubtitleSize
    @State private var style = CaptionStyle.system()

    public init(cues: [SubtitleCue], bottomPadding: CGFloat = 24, sidePadding: CGFloat = 48, baseSize: CGFloat = 20, size: SubtitleSize = .normal) {
        self.cues = cues
        self.bottomPadding = bottomPadding
        self.sidePadding = sidePadding
        self.baseSize = baseSize
        self.size = size
    }

    /// The cue text's size: `base` (the player's own, 20 pt on iPhone and
    /// iPad, 46 on Apple TV) × the system's relative caption size, which
    /// Normal follows, × the Subtitle size step.
    public static func cueSize(base: CGFloat, caption: Double, size: SubtitleSize) -> CGFloat {
        base * CGFloat(caption) * CGFloat(size.scale)
    }

    /// Each cue's lines, in order, each once.
    public static func lines(_ cues: [SubtitleCue]) -> [String] {
        var seen = Set<String>()
        return cues
            .flatMap { $0.text.components(separatedBy: "\n") }
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty && seen.insert($0).inserted }
    }

    public var body: some View {
        let size = Self.cueSize(base: baseSize, caption: style.scale, size: self.size)
        let lines = Self.lines(cues)
        VStack(spacing: size * 0.14) {
            ForEach(Array(lines.enumerated()), id: \.offset) { _, line in
                Text(line)
                    .font(.system(size: size, weight: .medium))
                    .foregroundStyle(style.foreground)
                    .multilineTextAlignment(.center)
                    .shadow(color: style.shadow ? .black.opacity(0.8) : .clear, radius: 2, x: 0, y: 1)
                    .padding(.horizontal, size * 0.32)
                    .padding(.vertical, size * 0.09)
                    .background(style.background, in: RoundedRectangle(cornerRadius: size * 0.14))
                    .background(style.window, in: RoundedRectangle(cornerRadius: size * 0.14))
            }
        }
        .padding(.horizontal, sidePadding)
        .padding(.bottom, bottomPadding)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
        .animation(.easeOut(duration: 0.2), value: bottomPadding)
        .animation(.easeOut(duration: 0.2), value: self.size)
        .allowsHitTesting(false)
        .accessibilityElement(children: .combine)
        .onReceive(NotificationCenter.default.publisher(for: CaptionStyle.changed)) { _ in
            style = .system()
        }
    }
}

/// One cue drawn as ``SubtitleCuesView`` draws it, at its real size: the
/// Subtitle size previews in Settings.
public struct SubtitleCuePreview: View {
    let text: String
    let baseSize: CGFloat
    let size: SubtitleSize
    @State private var style = CaptionStyle.system()

    public init(_ text: String, baseSize: CGFloat = 20, size: SubtitleSize) {
        self.text = text
        self.baseSize = baseSize
        self.size = size
    }

    public var body: some View {
        let points = SubtitleCuesView.cueSize(base: baseSize, caption: style.scale, size: size)
        Text(text)
            .font(.system(size: points, weight: .medium))
            .foregroundStyle(style.foreground)
            .multilineTextAlignment(.center)
            .lineLimit(1)
            .shadow(color: style.shadow ? .black.opacity(0.8) : .clear, radius: 2, x: 0, y: 1)
            .padding(.horizontal, points * 0.32)
            .padding(.vertical, points * 0.09)
            .background(style.background, in: RoundedRectangle(cornerRadius: points * 0.14))
            .background(style.window, in: RoundedRectangle(cornerRadius: points * 0.14))
            .animation(.easeOut(duration: 0.2), value: size)
            .accessibilityLabel("\(text), at \(size.label)")
            .onReceive(NotificationCenter.default.publisher(for: CaptionStyle.changed)) { _ in
                style = .system()
            }
    }
}
