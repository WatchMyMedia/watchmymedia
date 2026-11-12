import SwiftUI
import WMMModel

/// A small uppercase label on artwork: "NEW" (gold), "4K", "NOT IN LIBRARY" (orange).
public struct Badge: View {
    public enum Style: Sendable { case plain, gold, warning }

    let text: String
    let style: Style

    public init(_ text: String, style: Style = .plain) {
        self.text = text
        self.style = style
    }

    public var body: some View {
        Text(text.uppercased())
            .font(.system(size: 10, weight: .bold))
            .tracking(0.5)
            .padding(.horizontal, 6)
            .padding(.vertical, 3)
            .foregroundStyle(style == .plain ? Palette.text : Palette.ground)
            .background(background, in: RoundedRectangle(cornerRadius: 5))
    }

    private var background: Color {
        switch style {
        case .plain: .black.opacity(0.72)
        case .gold: Palette.gold
        case .warning: Palette.warning
        }
    }
}

/// An outlined tag: "4K", "HEVC", "EAC3 5.1".
public struct Tag: View {
    let text: String

    public init(_ text: String) { self.text = text }

    public var body: some View {
        Text(text.uppercased())
            .font(.system(size: 10, weight: .bold))
            .tracking(0.4)
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .foregroundStyle(Palette.text)
            .overlay(RoundedRectangle(cornerRadius: 4).stroke(Palette.outlineStrong))
    }
}

/// The gold watch-progress strip along the bottom of artwork.
public struct ProgressStrip: View {
    let progress: Double
    var height: CGFloat = 4

    public init(progress: Double, height: CGFloat = 4) {
        self.progress = progress
        self.height = height
    }

    public var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Rectangle().fill(.black.opacity(0.6))
                Rectangle().fill(Palette.gold).frame(width: geo.size.width * min(max(progress, 0), 1))
            }
        }
        .frame(height: height)
        .accessibilityElement()
        .accessibilityLabel("\(Int((progress * 100).rounded()))% watched")
    }
}

/// The gold check on watched artwork.
public struct WatchedCheck: View {
    var size: CGFloat = 18

    public init(size: CGFloat = 18) { self.size = size }

    public var body: some View {
        Image(systemName: "checkmark")
            .font(.system(size: size * 0.55, weight: .heavy))
            .foregroundStyle(Palette.ground)
            .frame(width: size, height: size)
            .background(Palette.gold, in: Circle())
            .accessibilityLabel("Watched")
    }
}

/// The reel mark: a film reel with a play triangle for its hub and the film's
/// tail running out along the bottom, white with burgundy details
/// (brand/README.md). Below 32 pt it draws the small artwork: bigger holes,
/// no play or sprockets.
public struct BrandMark: View {
    var size: CGFloat

    public init(size: CGFloat = 64) { self.size = size }

    public var body: some View {
        let small = size < 32
        Canvas { context, canvasSize in
            let s = canvasSize.width / 512
            // The tail runs under the wheel; the wheel's rim parts them.
            let tail = Path(roundedRect: CGRect(x: 216 * s, y: 401 * s, width: 274 * s, height: 48 * s), cornerRadius: 10 * s)
            context.fill(tail, with: .color(Palette.text))
            context.stroke(tail, with: .color(Palette.burgundy), lineWidth: 14 * s)
            if !small {
                for x in [343.0, 391, 439] {
                    let sprocket = Path(roundedRect: CGRect(x: x * s, y: 416 * s, width: 18 * s, height: 18 * s), cornerRadius: 4 * s)
                    context.fill(sprocket, with: .color(Palette.burgundy))
                }
            }
            // The wheel, drawn as if it filled the grid and scaled to r 200 about (216, 256).
            let k = 200.0 / 244.0
            var wheel = context
            wheel.translateBy(x: (216 - 256 * k) * s, y: (256 - 256 * k) * s)
            wheel.scaleBy(x: k, y: k)
            wheel.fill(Self.circle(256, 256, 244, s), with: .color(Palette.text))
            wheel.stroke(Self.circle(256, 256, 233, s), with: .color(Palette.burgundy), lineWidth: 22 * s)
            let (ring, hole): (CGFloat, CGFloat) = small ? (136, 64) : (126, 56)
            for i in 0..<5 {
                let a = (-90 + 72 * CGFloat(i)) * .pi / 180
                wheel.fill(Self.circle(256 + ring * cos(a), 256 + ring * sin(a), hole, s), with: .color(Palette.burgundy))
            }
            if !small {
                var play = Path()
                play.move(to: CGPoint(x: 232 * s, y: 214 * s))
                play.addLine(to: CGPoint(x: 232 * s, y: 298 * s))
                play.addLine(to: CGPoint(x: 300 * s, y: 256 * s))
                play.closeSubpath()
                wheel.fill(play, with: .color(Palette.burgundy))
                wheel.stroke(play, with: .color(Palette.burgundy), style: StrokeStyle(lineWidth: 18 * s, lineJoin: .round))
            }
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }

    private static func circle(_ cx: CGFloat, _ cy: CGFloat, _ r: CGFloat, _ s: CGFloat) -> Path {
        Path(ellipseIn: CGRect(x: (cx - r) * s, y: (cy - r) * s, width: 2 * r * s, height: 2 * r * s))
    }
}

/// "WatchMy" bold and "Media" muted, as the app lockup.
public struct Wordmark: View {
    var size: CGFloat

    public init(size: CGFloat = 28) { self.size = size }

    public var body: some View {
        (Text("WatchMy").fontWeight(.bold).foregroundStyle(Palette.text) + Text("Media").fontWeight(.semibold).foregroundStyle(Palette.muted))
            .font(.system(size: size))
            .tracking(-0.02 * size)
            .accessibilityLabel("WatchMyMedia")
    }
}
