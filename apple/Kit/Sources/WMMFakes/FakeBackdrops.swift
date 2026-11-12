import CoreGraphics
import Foundation
import ImageIO
import WMMModel

/// A handful of fictional 16:9 backdrops for the fake library (PLAN-HOME.md
/// §12, question 6), drawn in code as the fake listings' pictures are: no
/// real title or art, no words. Home's row screens are mostly picture, so
/// the fakes carry one for every show, movie, episode and Continue watching
/// item, chosen from these six scenes by title. Posters stay the seeded
/// gradients. ``FakeLibraryRepository/imageURL(_:size:)`` turns a
/// `fake-backdrop:` path into a URL ``FakeImageLoader`` answers.
public enum FakeBackdrops {
    static let scheme = "fake-backdrop"
    /// The scenes: a harbour at dusk, ridges, a skyline, dunes, a night sky and a forest.
    public static let scenes = ["harbour", "ridges", "skyline", "dunes", "night", "forest"]

    /// The image path for a title, as the server would send one: `/fake-backdrop/harbour`.
    public static func path(for title: String) -> String {
        let seed = title.unicodeScalars.reduce(UInt32(11)) { ($0 &* 31) &+ $1.value }
        return "/\(scheme)/\(scenes[Int(seed % UInt32(scenes.count))])"
    }

    /// The URL for a fake backdrop path at `size`; nil for any other path.
    public static func url(_ path: String?, size: ImageSize) -> URL? {
        guard let path, path.hasPrefix("/\(scheme)/") else { return nil }
        var c = URLComponents()
        c.scheme = scheme
        c.host = String(path.dropFirst(scheme.count + 2))
        c.queryItems = [URLQueryItem(name: "size", value: size.rawValue)]
        return c.url
    }

    /// The scene a `fake-backdrop:` URL names, as a PNG; nil for any other URL.
    static func image(_ url: URL) -> Data? {
        guard url.scheme == scheme, let c = URLComponents(url: url, resolvingAgainstBaseURL: false), let scene = c.host else { return nil }
        let size = c.queryItems?.first { $0.name == "size" }?.value ?? ImageSize.w780.rawValue
        let width = Int(size.dropFirst()) ?? 1280
        return draw(scene, width: min(max(width, 160), 1920))
    }

    private static func draw(_ scene: String, width w: Int) -> Data? {
        let h = w * 9 / 16
        guard let space = CGColorSpace(name: CGColorSpace.sRGB),
              let context = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: 0, space: space, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)
        else { return nil }
        let W = CGFloat(w), H = CGFloat(h)
        func color(_ hex: UInt32, _ alpha: CGFloat = 1) -> CGColor {
            CGColor(srgbRed: CGFloat(hex >> 16 & 0xFF) / 255, green: CGFloat(hex >> 8 & 0xFF) / 255, blue: CGFloat(hex & 0xFF) / 255, alpha: alpha)
        }
        func sky(_ top: UInt32, _ bottom: UInt32) {
            let gradient = CGGradient(colorsSpace: space, colors: [color(top), color(bottom)] as CFArray, locations: [0, 1])!
            context.drawLinearGradient(gradient, start: CGPoint(x: 0, y: H), end: CGPoint(x: 0, y: 0), options: [])
        }
        func disc(_ x: CGFloat, _ y: CGFloat, _ r: CGFloat, _ hex: UInt32, _ alpha: CGFloat = 1) {
            context.setFillColor(color(hex, alpha))
            context.fillEllipse(in: CGRect(x: W * x - r, y: H * y - r, width: 2 * r, height: 2 * r))
        }
        /// A silhouette along the bottom: points as fractions of the width and height.
        func ground(_ points: [(CGFloat, CGFloat)], _ hex: UInt32, _ alpha: CGFloat = 1) {
            context.setFillColor(color(hex, alpha))
            context.move(to: CGPoint(x: 0, y: 0))
            for (x, y) in points { context.addLine(to: CGPoint(x: W * x, y: H * y)) }
            context.addLine(to: CGPoint(x: W, y: 0))
            context.closePath()
            context.fillPath()
        }
        switch scene {
        case "harbour":
            sky(0x1F3A4D, 0xC9743A)
            disc(0.68, 0.30, H * 0.09, 0xF2D3A6)
            ground([(0, 0.34), (1, 0.34)], 0x0B161D, 0.92)
            // A breakwater and two masts.
            ground([(0, 0.36), (0.42, 0.38), (0.55, 0.37), (1, 0.33)], 0x08111A)
            for (x, top) in [(0.30, 0.62), (0.36, 0.55)] {
                context.setFillColor(color(0x08111A))
                context.fill(CGRect(x: W * x, y: H * 0.34, width: max(W * 0.004, 2), height: H * (top - 0.34)))
            }
        case "ridges":
            sky(0x2B2F4A, 0x8C6B7A)
            disc(0.22, 0.72, H * 0.07, 0xF5F5F5, 0.9)
            ground([(0, 0.46), (0.18, 0.62), (0.34, 0.50), (0.52, 0.70), (0.70, 0.52), (0.86, 0.60), (1, 0.48)], 0x36304A, 0.9)
            ground([(0, 0.30), (0.15, 0.42), (0.33, 0.33), (0.50, 0.48), (0.66, 0.36), (0.84, 0.44), (1, 0.32)], 0x1B1826)
            ground([(0, 0.18), (0.20, 0.26), (0.45, 0.20), (0.70, 0.28), (1, 0.16)], 0x0F0F10)
        case "skyline":
            sky(0x0F111C, 0x4A2C3A)
            disc(0.80, 0.62, H * 0.05, 0xF5F5F5, 0.8)
            var x: CGFloat = 0
            var n: UInt32 = 5
            while x < W {
                n = n &* 1_103_515_245 &+ 12_345
                let bw = W * (0.03 + CGFloat(n >> 16 & 0x3F) / 1800)
                let bh = H * (0.16 + CGFloat(n >> 8 & 0xFF) / 700)
                context.setFillColor(color(0x12101A))
                context.fill(CGRect(x: x, y: 0, width: bw + 1, height: bh))
                // Lit windows.
                context.setFillColor(color(0xFFB612, 0.55))
                var wy = H * 0.04
                while wy < bh - H * 0.03 {
                    if (UInt32(wy) &+ n) % 3 != 0 { context.fill(CGRect(x: x + bw * 0.3, y: wy, width: max(W * 0.004, 1.5), height: max(H * 0.006, 1.5))) }
                    wy += H * 0.03
                }
                x += bw + W * 0.008
            }
            ground([(0, 0.06), (1, 0.06)], 0x0F0F10)
        case "dunes":
            sky(0x4D3A1F, 0xE2B07A)
            disc(0.30, 0.68, H * 0.11, 0xFFE0B0, 0.95)
            ground([(0, 0.40), (0.30, 0.52), (0.60, 0.36), (1, 0.46)], 0x8A5A2B)
            ground([(0, 0.24), (0.25, 0.30), (0.55, 0.20), (0.80, 0.32), (1, 0.22)], 0x5A3A1A)
            ground([(0, 0.10), (0.40, 0.16), (0.75, 0.08), (1, 0.14)], 0x1C140A)
        case "night":
            sky(0x05070F, 0x1F3A4D)
            var n: UInt32 = 9
            for _ in 0..<140 {
                n = n &* 1_103_515_245 &+ 12_345
                let x = CGFloat(n >> 8 & 0xFFFF) / 65_535
                n = n &* 1_103_515_245 &+ 12_345
                let y = 0.3 + CGFloat(n >> 8 & 0xFFFF) / 65_535 * 0.7
                disc(x, y, max(W * 0.0012, 0.8) * (1 + CGFloat(n & 3) / 2), 0xF5F5F5, 0.85)
            }
            disc(0.76, 0.74, H * 0.08, 0xF5F0E0)
            disc(0.73, 0.76, H * 0.075, 0x05070F)
            ground([(0, 0.22), (0.30, 0.28), (0.55, 0.20), (0.80, 0.30), (1, 0.18)], 0x0A1A13)
        default:
            sky(0x1F4A3A, 0x3D4A2B)
            disc(0.55, 0.78, H * 0.10, 0xF2D3A6, 0.5)
            var x: CGFloat = -W * 0.02
            var n: UInt32 = 3
            while x < W {
                n = n &* 1_103_515_245 &+ 12_345
                let tw = W * (0.04 + CGFloat(n >> 16 & 0x1F) / 1000)
                let th = H * (0.30 + CGFloat(n >> 8 & 0xFF) / 600)
                context.setFillColor(color(n & 1 == 0 ? 0x0E1B18 : 0x131A0E))
                context.move(to: CGPoint(x: x, y: 0)); context.addLine(to: CGPoint(x: x + tw / 2, y: th)); context.addLine(to: CGPoint(x: x + tw, y: 0))
                context.closePath(); context.fillPath()
                x += tw * 0.6
            }
            ground([(0, 0.08), (1, 0.08)], 0x0A1A13)
        }
        // The brand's warm light at the top right, as the hero draws it.
        let glow = CGGradient(colorsSpace: space, colors: [color(0xFFB612, 0.14), color(0xFFB612, 0)] as CFArray, locations: [0, 1])!
        context.drawRadialGradient(glow, startCenter: CGPoint(x: W * 0.72, y: H * 0.78), startRadius: 0, endCenter: CGPoint(x: W * 0.72, y: H * 0.78), endRadius: W * 0.35, options: [])
        guard let image = context.makeImage() else { return nil }
        let data = NSMutableData()
        guard let out = CGImageDestinationCreateWithData(data, "public.png" as CFString, 1, nil) else { return nil }
        CGImageDestinationAddImage(out, image, nil)
        return CGImageDestinationFinalize(out) ? data as Data : nil
    }
}
