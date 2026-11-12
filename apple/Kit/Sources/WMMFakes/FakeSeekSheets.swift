import CoreGraphics
import CoreText
import Foundation
import ImageIO
import WMMModel

/// Seek-thumbnail sheets drawn in code for the fakes (android/core/fake's
/// `FakeSeekThumbnails`): each tile a colour and its time, so the player
/// shows the feature with no server. The fake player's manifest points at
/// `fake-trickplay:` URLs, which ``FakeImageLoader`` answers with these.
enum FakeSeekSheets {
    static let scheme = "fake-trickplay"

    /// The manifest for a fake file of `durationMs`: 320 × 180 tiles every 10 s, 10 × 10 to a sheet.
    static func trickplay(durationMs: Int) -> Trickplay? {
        let count = max((durationMs + 9_999) / 10_000, 1)
        return Trickplay(intervalMs: 10_000, width: 320, height: 180, columns: 10, rows: 10, count: count, sheets: (count + 99) / 100,
                         urlTemplate: "\(scheme)://sheets/{sheet}.jpg")
    }

    /// Sheet `n` of ``trickplay(durationMs:)``'s layout as a JPEG.
    static func sheet(_ url: URL) -> Data? {
        guard url.scheme == scheme, let n = Int(url.deletingPathExtension().lastPathComponent), n >= 0 else { return nil }
        let (w, h, columns, rows) = (320, 180, 10, 10)
        guard let space = CGColorSpace(name: CGColorSpace.sRGB),
              let context = CGContext(data: nil, width: w * columns, height: h * rows, bitsPerComponent: 8, bytesPerRow: 0,
                                      space: space, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)
        else { return nil }
        let font = CTFontCreateWithName("Helvetica-Bold" as CFString, 44, nil)
        for cell in 0..<(columns * rows) {
            let i = n * columns * rows + cell
            // Rows count down from the top; Core Graphics counts up from the bottom.
            let frame = CGRect(x: cell % columns * w, y: (rows - 1 - cell / columns) * h, width: w, height: h)
            let hue = Double(i % 24) / 24
            context.setFillColor(hsb(hue, 0.55, 0.45))
            context.fill(frame)
            let text = NSAttributedString(string: formatClock(ms: i * 10_000), attributes: [
                NSAttributedString.Key(kCTFontAttributeName as String): font,
                NSAttributedString.Key(kCTForegroundColorAttributeName as String): CGColor(gray: 1, alpha: 0.95),
            ])
            let line = CTLineCreateWithAttributedString(text)
            let bounds = CTLineGetBoundsWithOptions(line, .useGlyphPathBounds)
            context.textPosition = CGPoint(x: frame.midX - bounds.midX, y: frame.midY - bounds.midY)
            CTLineDraw(line, context)
        }
        guard let image = context.makeImage() else { return nil }
        let data = NSMutableData()
        guard let out = CGImageDestinationCreateWithData(data, "public.jpeg" as CFString, 1, nil) else { return nil }
        CGImageDestinationAddImage(out, image, [kCGImageDestinationLossyCompressionQuality: 0.7] as CFDictionary)
        return CGImageDestinationFinalize(out) ? data as Data : nil
    }

    /// A colour from hue, saturation and brightness, 0–1.
    private static func hsb(_ h: Double, _ s: Double, _ v: Double) -> CGColor {
        let i = Int(h * 6) % 6
        let f = h * 6 - Double(Int(h * 6))
        let (p, q, t) = (v * (1 - s), v * (1 - f * s), v * (1 - (1 - f) * s))
        let (r, g, b): (Double, Double, Double) = switch i {
        case 0: (v, t, p)
        case 1: (q, v, p)
        case 2: (p, v, t)
        case 3: (p, q, v)
        case 4: (t, p, v)
        default: (v, p, q)
        }
        return CGColor(srgbRed: r, green: g, blue: b, alpha: 1)
    }
}
