import SwiftUI

/// The brand palette (brand/README.md) as the mockups use it. Gold marks
/// progress, selection and badges; burgundy fills primary buttons; warnings
/// are orange so they do not read as the accent.
public enum Palette {
    /// The near-black ground.
    public static let ground = Color(hex: 0x0F0F10)
    /// Cards, sheets and grouped rows.
    public static let surface = Color(hex: 0x151517)
    public static let surfaceRaised = Color(hex: 0x1A1A1D)
    public static let sheet = Color(hex: 0x1C1C1F)
    public static let separator = Color(hex: 0x222225)
    public static let outline = Color(hex: 0x2A2A2E)
    public static let outlineStrong = Color(hex: 0x3A3A3F)
    public static let burgundy = Color(hex: 0x5A1414)
    /// The thin lighter rim on burgundy buttons, so they read on the dark ground.
    public static let burgundyEdge = Color(hex: 0xB04545)
    public static let gold = Color(hex: 0xFFB612)
    public static let warning = Color(hex: 0xFB923C)
    /// Live TV's LIVE mark, and the light red of a programme's Live tag and "On now".
    public static let liveRed = Color(hex: 0xB42318)
    public static let liveText = Color(hex: 0xFCA5A5)
    public static let text = Color(hex: 0xF5F5F5)
    public static let textSecondary = Color(hex: 0xD4D4D4)
    /// Muted text: 7.9:1 on the ground.
    public static let muted = Color(hex: 0xA3A3A3)
    /// Faint text (footers): 5.9:1 on the ground.
    public static let faint = Color(hex: 0x8C8C8C)

    /// The placeholder gradients the mockups draw where artwork goes, by seed.
    static let artwork: [(Color, Color)] = [
        (0x1F3A4D, 0x0B161D), (0x4A2C3A, 0x1A0E14), (0x3D4A2B, 0x131A0E), (0x4D3A1F, 0x1C140A), (0x2B2F4A, 0x0F111C),
        (0x4A2B2B, 0x1A0E0E), (0x2F4A44, 0x0E1B18), (0x4A4530, 0x1A1810), (0x36304A, 0x120F1A), (0x1F4A3A, 0x0A1A13),
    ].map { (Color(hex: $0.0), Color(hex: $0.1)) }

    /// A stable index for `seed` (String.hashValue changes every launch).
    static func index(for seed: String, count: Int) -> Int {
        var hash: UInt32 = 2_166_136_261
        for byte in seed.utf8 {
            hash ^= UInt32(byte)
            hash = hash &* 16_777_619
        }
        return Int(hash % UInt32(count))
    }
}

extension Color {
    /// A colour from 0xRRGGBB.
    public init(hex: UInt32, opacity: Double = 1) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: opacity
        )
    }
}

extension View {
    /// The app's dark ground behind a whole screen.
    public func groundBackground() -> some View {
        background(Palette.ground.ignoresSafeArea())
    }
}
