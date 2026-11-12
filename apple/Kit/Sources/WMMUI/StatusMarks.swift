import SwiftUI
import WMMModel

// How a missing episode's or a request's state looks (ShowMissing,
// MyRequests.dc.html): an icon and a line in its colour, with a gold bar
// under it while downloading. Shared by the iPhone and Apple TV apps.

extension StatusTone {
    public var color: Color {
        switch self {
        case .accent: Palette.gold
        case .plain: Palette.textSecondary
        case .warning: Palette.warning
        case .muted: Palette.muted
        }
    }
}

extension StatusIcon {
    /// The SF Symbol for the icon the boards draw.
    public var systemImage: String {
        switch self {
        case .download: "arrow.down.to.line"
        case .waiting: "clock"
        case .failed: "exclamationmark.triangle"
        case .requested: "person"
        case .approved: "checkmark"
        case .looking: "magnifyingglass"
        case .notMonitored: "eye.slash"
        case .airs: "calendar"
        case .missing: "circle.dashed"
        case .declined: "xmark.circle"
        case .inLibrary: "checkmark.circle"
        }
    }
}

/// A state line: its icon and text in its colour, and the download's bar.
public struct StatusLineView: View {
    let line: StatusLine
    var fontSize: CGFloat
    var barWidth: CGFloat?

    /// `barWidth` caps the bar (Apple TV's is 440 pt); nil fills the width.
    public init(_ line: StatusLine, fontSize: CGFloat = 12, barWidth: CGFloat? = nil) {
        self.line = line
        self.fontSize = fontSize
        self.barWidth = barWidth
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: fontSize * 0.25) {
            // The icon is part of the text, not an Image of its own: on Apple TV
            // a focused borderless button lifts and enlarges the images in its label.
            Text("\(Image(systemName: line.icon.systemImage)) \(line.text)")
                .font(.system(size: fontSize, weight: .semibold))
                .fixedSize(horizontal: false, vertical: true)
                .foregroundStyle(line.tone.color)
                .accessibilityLabel(line.text)
            if let progress = line.progress {
                StatusBar(progress: progress, height: fontSize >= 18 ? 6 : 4)
                    .frame(maxWidth: barWidth ?? .infinity)
            }
        }
        .accessibilityElement(children: .combine)
    }
}

/// The download's progress: gold on a dark track, rounded.
struct StatusBar: View {
    let progress: Double
    let height: CGFloat

    var body: some View {
        Capsule()
            .fill(Palette.outline)
            .frame(height: height)
            .overlay(alignment: .leading) {
                GeometryReader { geo in
                    Capsule().fill(Palette.gold).frame(width: geo.size.width * min(max(progress, 0), 1))
                }
            }
            .clipShape(Capsule())
            .accessibilityHidden(true)
    }
}
