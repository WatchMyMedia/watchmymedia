import CoreGraphics
import CoreText
import Foundation
import ImageIO
import WMMModel

/// The fake listings' pictures, drawn in code as the boards draw them: a
/// series' 480×270 title card, a TMDB-style still without words, and a
/// channel's 4:3 wordmark logo. ``FakeImageLoader`` answers `fake-live-art:` URLs with them.
enum FakeLiveArt {
    static let scheme = "fake-live-art"

    static func pictureURL(title: String) -> URL? { url("picture", ["t": title]) }
    static func stillURL(title: String) -> URL? { url("still", ["t": title]) }
    static func logoURL(word: String, colour: UInt32, shape: String) -> URL? {
        url("logo", ["w": word, "c": String(colour, radix: 16), "s": shape])
    }

    private static func url(_ kind: String, _ query: [String: String]) -> URL? {
        var c = URLComponents()
        c.scheme = scheme; c.host = kind
        c.queryItems = query.sorted { $0.key < $1.key }.map { URLQueryItem(name: $0.key, value: $0.value) }
        return c.url
    }

    /// The picture a `fake-live-art:` URL names, as a PNG; nil for any other URL.
    static func image(_ url: URL) -> Data? {
        guard url.scheme == scheme, let c = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return nil }
        let q = Dictionary((c.queryItems ?? []).map { ($0.name, $0.value ?? "") }, uniquingKeysWith: { a, _ in a })
        switch c.host {
        case "picture": return card(q["t"] ?? "", still: false)
        case "still": return card(q["t"] ?? "", still: true)
        case "logo": return logo(q["w"] ?? "", colour: UInt32(q["c"] ?? "", radix: 16) ?? 0xFFFFFF, shape: q["s"] ?? "disc")
        default: return nil
        }
    }

    // The boards' picture palette.
    private static let palette: [(UInt32, UInt32)] = [(0x1F3A4D, 0x0B161D), (0x4A2C3A, 0x1A0E14), (0x3D4A2B, 0x131A0E), (0x4D3A1F, 0x1C140A),
                                                      (0x2B2F4A, 0x0F111C), (0x4A2B2B, 0x1A0E0E), (0x2F4A44, 0x0E1B18), (0x4A4530, 0x1A1810),
                                                      (0x36304A, 0x120F1A), (0x1F4A3A, 0x0A1A13)]

    private static func card(_ title: String, still: Bool) -> Data? {
        let (w, h) = still ? (780, 439) : (480, 270)
        guard let context = context(w, h, opaque: true) else { return nil }
        let seed = title.unicodeScalars.reduce(UInt32(7)) { ($0 &* 31) &+ $1.value }
        let (top, bottom) = palette[Int(seed % UInt32(palette.count))]
        let gradient = CGGradient(colorsSpace: CGColorSpace(name: CGColorSpace.sRGB), colors: [color(top), color(bottom)] as CFArray, locations: [0, 1])!
        context.drawLinearGradient(gradient, start: CGPoint(x: still ? w : 0, y: h), end: CGPoint(x: still ? 0 : w / 2, y: 0), options: [])
        if still {
            // A still has no words: a low sun and a horizon, so it reads as a scene.
            context.setFillColor(color(0xF2D3A6, alpha: 0.85))
            context.fillEllipse(in: CGRect(x: Int(seed % 300) + 380, y: 210, width: 130, height: 130))
            context.setFillColor(color(bottom, alpha: 0.9))
            context.fill(CGRect(x: 0, y: 0, width: w, height: 170))
            return png(context)
        }
        context.setFillColor(CGColor(gray: 1, alpha: 0.08))
        context.fillEllipse(in: CGRect(x: -80, y: 140, width: 300, height: 260))
        let font = CTFontCreateWithName("Helvetica-Bold" as CFString, title.count > 14 ? 40 : 48, nil)
        let text = NSAttributedString(string: title.uppercased(), attributes: [
            NSAttributedString.Key(kCTFontAttributeName as String): font,
            NSAttributedString.Key(kCTForegroundColorAttributeName as String): CGColor(gray: 1, alpha: 0.96),
        ])
        let framesetter = CTFramesetterCreateWithAttributedString(text)
        let frame = CTFramesetterCreateFrame(framesetter, CFRange(location: 0, length: 0), CGPath(rect: CGRect(x: 30, y: 26, width: w - 60, height: 120), transform: nil), nil)
        CTFrameDraw(frame, context)
        return png(context)
    }

    private static func logo(_ word: String, colour: UInt32, shape: String) -> Data? {
        let (w, h) = (160, 120)
        guard let context = context(w, h, opaque: false) else { return nil }
        let ink = color(colour)
        context.setFillColor(ink)
        let mark = CGRect(x: 66, y: 72, width: 28, height: 28)
        switch shape {
        case "sq": context.fill(mark.insetBy(dx: 2, dy: 2))
        case "bar": context.fill(CGRect(x: 30, y: 74, width: 100, height: 8))
        case "dia":
            context.move(to: CGPoint(x: mark.midX, y: mark.maxY)); context.addLine(to: CGPoint(x: mark.maxX, y: mark.midY))
            context.addLine(to: CGPoint(x: mark.midX, y: mark.minY)); context.addLine(to: CGPoint(x: mark.minX, y: mark.midY))
            context.fillPath()
        default: context.fillEllipse(in: mark)
        }
        let text = NSAttributedString(string: word, attributes: [
            NSAttributedString.Key(kCTFontAttributeName as String): CTFontCreateWithName("Helvetica-Bold" as CFString, word.count > 4 ? 30 : 36, nil),
            NSAttributedString.Key(kCTForegroundColorAttributeName as String): ink,
        ])
        let line = CTLineCreateWithAttributedString(text)
        let bounds = CTLineGetBoundsWithOptions(line, .useGlyphPathBounds)
        context.textPosition = CGPoint(x: CGFloat(w) / 2 - bounds.midX, y: 22)
        CTLineDraw(line, context)
        return png(context)
    }

    private static func context(_ w: Int, _ h: Int, opaque: Bool) -> CGContext? {
        guard let space = CGColorSpace(name: CGColorSpace.sRGB) else { return nil }
        return CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: 0, space: space,
                         bitmapInfo: (opaque ? CGImageAlphaInfo.noneSkipLast : CGImageAlphaInfo.premultipliedLast).rawValue)
    }

    private static func color(_ hex: UInt32, alpha: CGFloat = 1) -> CGColor {
        CGColor(srgbRed: CGFloat(hex >> 16 & 0xFF) / 255, green: CGFloat(hex >> 8 & 0xFF) / 255, blue: CGFloat(hex & 0xFF) / 255, alpha: alpha)
    }

    private static func png(_ context: CGContext) -> Data? {
        guard let image = context.makeImage() else { return nil }
        let data = NSMutableData()
        guard let out = CGImageDestinationCreateWithData(data, "public.png" as CFString, 1, nil) else { return nil }
        CGImageDestinationAddImage(out, image, nil)
        return CGImageDestinationFinalize(out) ? data as Data : nil
    }
}
