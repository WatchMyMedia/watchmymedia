import SwiftUI

/// The burgundy primary button: full width by default, 50 pt tall.
public struct PrimaryButtonStyle: ButtonStyle {
    var height: CGFloat
    var fullWidth: Bool

    public init(height: CGFloat = 50, fullWidth: Bool = true) {
        self.height = height
        self.fullWidth = fullWidth
    }

    public func makeBody(configuration: Configuration) -> some View {
        PrimaryLabel(configuration: configuration, height: height, fullWidth: fullWidth)
    }

    private struct PrimaryLabel: View {
        let configuration: Configuration
        let height: CGFloat
        let fullWidth: Bool
        @Environment(\.isEnabled) private var isEnabled

        var body: some View {
            configuration.label
                .font(.system(size: height >= 50 ? 17 : 15, weight: .semibold))
                .foregroundStyle(.white)
                .frame(maxWidth: fullWidth ? .infinity : nil, minHeight: height)
                .padding(.horizontal, fullWidth ? 0 : 18)
                .background(Palette.burgundy, in: RoundedRectangle(cornerRadius: 12))
                .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(Palette.burgundyEdge, lineWidth: 1))
                .opacity(isEnabled ? (configuration.isPressed ? 0.8 : 1) : 0.5)
        }
    }
}

/// The translucent secondary button ("Go to show", "Mark watched").
public struct GhostButtonStyle: ButtonStyle {
    var height: CGFloat
    var fullWidth: Bool

    public init(height: CGFloat = 46, fullWidth: Bool = true) {
        self.height = height
        self.fullWidth = fullWidth
    }

    public func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 15, weight: .semibold))
            .foregroundStyle(Palette.text)
            .frame(maxWidth: fullWidth ? .infinity : nil, minHeight: height)
            .padding(.horizontal, fullWidth ? 0 : 14)
            .background(.white.opacity(configuration.isPressed ? 0.18 : 0.12), in: RoundedRectangle(cornerRadius: 10))
    }
}

/// A small translucent button inside a row ("Sign out" on a device, "Play
/// this" on a version): drawn about 34 pt tall, tappable across 44 pt.
public struct SmallGhostButtonStyle: ButtonStyle {
    public init() {}

    public func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.footnote.weight(.semibold))
            .foregroundStyle(Palette.text)
            .padding(.horizontal, 12)
            .frame(minHeight: 34)
            .background(.white.opacity(configuration.isPressed ? 0.2 : 0.12), in: RoundedRectangle(cornerRadius: 8))
            .frame(minHeight: 44)
            .contentShape(Rectangle())
    }
}

/// A filter or season chip; gold when selected.
public struct Chip: View {
    let title: String
    let detail: String?
    let systemImage: String?
    let selected: Bool
    let action: () -> Void

    public init(_ title: String, detail: String? = nil, systemImage: String? = nil, selected: Bool, action: @escaping () -> Void) {
        self.title = title
        self.detail = detail
        self.systemImage = systemImage
        self.selected = selected
        self.action = action
    }

    public var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                if let systemImage { Image(systemName: systemImage).font(.system(size: 12, weight: .semibold)).accessibilityHidden(true) }
                Text(title).fontWeight(.semibold)
                if let detail { Text(detail).fontWeight(.medium).foregroundStyle(selected ? Palette.ground : Palette.faint) }
            }
            .font(.system(size: 14))
            .padding(.horizontal, 13)
            .frame(minHeight: 34)
            .foregroundStyle(selected ? Palette.ground : Palette.textSecondary)
            .background(selected ? Palette.gold : Palette.surfaceRaised, in: Capsule())
            .overlay(Capsule().strokeBorder(selected ? Palette.gold : Palette.outline))
            // Drawn 34 pt tall as the mockups have it, but taps land within 44 pt.
            .padding(.vertical, 5)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}
