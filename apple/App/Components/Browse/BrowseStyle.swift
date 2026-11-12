import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// The mockups' point sizes, scaled with Dynamic Type from the text style
/// nearest each, so a 15 pt title is 15 pt at the default size and grows
/// as the subheadline style does.
struct ScaledFont: ViewModifier {
    @ScaledMetric private var size: CGFloat
    private let weight: Font.Weight

    init(size: CGFloat, weight: Font.Weight) {
        _size = ScaledMetric(wrappedValue: size, relativeTo: Self.style(for: size))
        self.weight = weight
    }

    func body(content: Content) -> some View {
        content.font(.system(size: size, weight: weight))
    }

    static func style(for size: CGFloat) -> Font.TextStyle {
        switch size {
        case ..<11.5: .caption2
        case ..<12.5: .caption
        case ..<13.5: .footnote
        case ..<15.5: .subheadline
        case ..<16.5: .callout
        case ..<18.5: .body
        case ..<21: .title3
        case ..<25: .title2
        case ..<31: .title
        default: .largeTitle
        }
    }
}

extension View {
    /// The system font at a mockup's `size`, scaled with Dynamic Type.
    func scaledFont(_ size: CGFloat, weight: Font.Weight = .regular) -> some View {
        modifier(ScaledFont(size: size, weight: weight))
    }

    /// Fills the parent and sits at `alignment`, inset by `padding`: a badge in a corner of artwork.
    func pinned(_ alignment: Alignment, padding: CGFloat = 6) -> some View {
        self.padding(padding).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: alignment)
    }

    /// A brief note at the bottom of the screen ("Marked as watched"), read out by VoiceOver.
    func messageToast(_ message: Binding<String?>) -> some View {
        modifier(MessageToast(message: message))
    }

    /// An alert for a ``Notice`` (an unmatched folder that has nowhere to go).
    func noticeAlert(_ notice: Binding<Notice?>) -> some View {
        alert(
            notice.wrappedValue?.title ?? "",
            isPresented: Binding(get: { notice.wrappedValue != nil }, set: { if !$0 { notice.wrappedValue = nil } }),
            presenting: notice.wrappedValue
        ) { _ in
            Button("OK", role: .cancel) {}
        } message: { notice in
            Text(notice.message)
        }
    }
}

/// Cards dim and shrink a little while pressed.
struct CardButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .opacity(configuration.isPressed ? 0.85 : 1)
            .animation(.easeOut(duration: 0.15), value: configuration.isPressed)
    }
}

/// "Mark watched" or "Mark unwatched" in a context menu.
struct WatchToggleButton: View {
    let watched: Bool
    let action: (Bool) async -> Void

    var body: some View {
        Button {
            Task { await action(!watched) }
        } label: {
            Label(watchToggleLabel(watched: watched), systemImage: watched ? "eye.slash" : "checkmark.circle")
        }
    }
}

/// The dashed box where artwork would be: an episode yet to air, or one not in the library.
struct DashedPlaceholder: View {
    var text: String?
    var cornerRadius: CGFloat = 8
    var fontSize: CGFloat = 11

    var body: some View {
        RoundedRectangle(cornerRadius: cornerRadius)
            .fill(Palette.surface)
            .overlay(RoundedRectangle(cornerRadius: cornerRadius).strokeBorder(Palette.outlineStrong, style: StrokeStyle(lineWidth: 1, dash: [4, 3])))
            .overlay {
                if let text {
                    Text(text).scaledFont(fontSize, weight: .semibold).foregroundStyle(Palette.muted)
                }
            }
    }
}

/// The thin line between rows.
struct RowSeparator: View {
    var body: some View {
        Rectangle().fill(Palette.separator).frame(height: 1).accessibilityHidden(true)
    }
}

/// Shows `message` for a moment over the bottom of the screen.
private struct MessageToast: ViewModifier {
    @Binding var message: String?

    func body(content: Content) -> some View {
        content
            .overlay(alignment: .bottom) {
                if let message {
                    Text(message)
                        .scaledFont(15, weight: .medium)
                        .foregroundStyle(Palette.text)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 10)
                        .background(Palette.surfaceRaised, in: Capsule())
                        .overlay(Capsule().strokeBorder(Palette.outline))
                        .shadow(color: .black.opacity(0.4), radius: 12, y: 4)
                        .padding(.horizontal, 24)
                        .padding(.bottom, 16)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                        .task(id: message) {
                            AccessibilityNotification.Announcement(message).post()
                            try? await Task.sleep(for: .seconds(2.5))
                            self.message = nil
                        }
                }
            }
            .animation(.easeOut(duration: 0.25), value: message)
    }
}

/// A simple wrapping row: tags, and the hero's facts.
struct FlowLayout: Layout {
    var spacing: CGFloat = 4
    var lineSpacing: CGFloat = 4

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let lines = arrange(subviews, width: proposal.width ?? .infinity)
        let width = lines.map(\.width).max() ?? 0
        let height = lines.map(\.height).reduce(0, +) + lineSpacing * CGFloat(max(lines.count - 1, 0))
        return CGSize(width: proposal.width.map { min($0, width) } ?? width, height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var y = bounds.minY
        for line in arrange(subviews, width: bounds.width) {
            var x = bounds.minX
            for (index, size) in line.items {
                subviews[index].place(at: CGPoint(x: x, y: y + (line.height - size.height) / 2), proposal: ProposedViewSize(size))
                x += size.width + spacing
            }
            y += line.height + lineSpacing
        }
    }

    private struct Line {
        var items: [(Int, CGSize)] = []
        var width: CGFloat = 0
        var height: CGFloat = 0
    }

    private func arrange(_ subviews: Subviews, width: CGFloat) -> [Line] {
        var lines: [Line] = []
        var line = Line()
        for (index, subview) in subviews.enumerated() {
            let size = subview.sizeThatFits(ProposedViewSize(width: width, height: nil))
            let needed = line.items.isEmpty ? size.width : line.width + spacing + size.width
            if needed > width, !line.items.isEmpty {
                lines.append(line)
                line = Line()
            }
            line.width = line.items.isEmpty ? size.width : line.width + spacing + size.width
            line.height = max(line.height, size.height)
            line.items.append((index, size))
        }
        if !line.items.isEmpty { lines.append(line) }
        return lines
    }
}
