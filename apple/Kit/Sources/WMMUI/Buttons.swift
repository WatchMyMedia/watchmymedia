import SwiftUI

/// The burgundy primary button: full width by default, 50 pt tall. Its text
/// and height follow the system's text size, so it looks exactly so at the
/// default size and grows with the text (PLAN-APPLE-REVIEW.md §2.8).
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
        let fullWidth: Bool
        @ScaledMetric private var height: CGFloat
        @ScaledMetric private var fontSize: CGFloat
        @Environment(\.isEnabled) private var isEnabled

        init(configuration: Configuration, height: CGFloat, fullWidth: Bool) {
            self.configuration = configuration
            self.fullWidth = fullWidth
            let large = height >= 50
            _height = ScaledMetric(wrappedValue: height, relativeTo: large ? .body : .subheadline)
            _fontSize = ScaledMetric(wrappedValue: large ? 17 : 15, relativeTo: large ? .body : .subheadline)
        }

        var body: some View {
            configuration.label
                .font(.system(size: fontSize, weight: .semibold))
                .multilineTextAlignment(.center)
                .foregroundStyle(.white)
                .padding(.vertical, 4)
                .frame(maxWidth: fullWidth ? .infinity : nil, minHeight: height)
                .padding(.horizontal, fullWidth ? 0 : 18)
                .background(Palette.burgundy, in: RoundedRectangle(cornerRadius: 12))
                .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(Palette.burgundyEdge, lineWidth: 1))
                .opacity(isEnabled ? (configuration.isPressed ? 0.8 : 1) : 0.5)
        }
    }
}

/// The translucent secondary button ("Go to show", "Mark watched"). Its
/// text and height follow the system's text size, as the primary's do.
public struct GhostButtonStyle: ButtonStyle {
    var height: CGFloat
    var fullWidth: Bool

    public init(height: CGFloat = 46, fullWidth: Bool = true) {
        self.height = height
        self.fullWidth = fullWidth
    }

    public func makeBody(configuration: Configuration) -> some View {
        GhostLabel(configuration: configuration, height: height, fullWidth: fullWidth)
    }

    private struct GhostLabel: View {
        let configuration: Configuration
        let fullWidth: Bool
        @ScaledMetric private var height: CGFloat
        @ScaledMetric(relativeTo: .subheadline) private var fontSize: CGFloat = 15

        init(configuration: Configuration, height: CGFloat, fullWidth: Bool) {
            self.configuration = configuration
            self.fullWidth = fullWidth
            _height = ScaledMetric(wrappedValue: height, relativeTo: .subheadline)
        }

        var body: some View {
            configuration.label
                .font(.system(size: fontSize, weight: .semibold))
                .multilineTextAlignment(.center)
                .foregroundStyle(Palette.text)
                .padding(.vertical, 4)
                .frame(maxWidth: fullWidth ? .infinity : nil, minHeight: height)
                .padding(.horizontal, fullWidth ? 0 : 14)
                .background(.white.opacity(configuration.isPressed ? 0.18 : 0.12), in: RoundedRectangle(cornerRadius: 10))
        }
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

/// A filter or season chip; gold when selected. Its text and height follow
/// the system's text size (34 pt at the default size).
public struct Chip: View {
    let title: String
    let detail: String?
    let systemImage: String?
    let selected: Bool
    let action: () -> Void
    @ScaledMetric(relativeTo: .subheadline) private var fontSize: CGFloat = 14
    @ScaledMetric(relativeTo: .subheadline) private var iconSize: CGFloat = 12
    @ScaledMetric(relativeTo: .subheadline) private var height: CGFloat = 34

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
                if let systemImage { Image(systemName: systemImage).font(.system(size: iconSize, weight: .semibold)).accessibilityHidden(true) }
                Text(title).fontWeight(.semibold)
                if let detail { Text(detail).fontWeight(.medium).foregroundStyle(selected ? Palette.ground : Palette.faint) }
            }
            .font(.system(size: fontSize))
            .padding(.horizontal, 13)
            .frame(minHeight: height)
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
