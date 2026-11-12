import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

// The 10-foot look of design/mockups/apple-tv: the artboards are 1920×1080
// and a tvOS point is one of their pixels, so sizes are the artboards' own.

// Cards and rows (posters, wide cards, episode rows, servers) draw their own
// focus from `isFocused` and use the system's `.borderless` button style: on
// tvOS a long press opens a `.contextMenu` only on the system's styles, not
// on a custom `ButtonStyle`.

enum TVLayout {
    /// The left and right margin of every screen.
    static let gutter: CGFloat = 90
}

extension Color {
    /// #E5E5E5: titles under cards and facts.
    static let tvTitle = Color(hex: 0xE5E5E5)
    /// #B42318 and its edge: a destructive button (Delete episode).
    static let tvDanger = Color(hex: 0xB42318)
    static let tvDangerEdge = Color(hex: 0xE5484D)
}

/// The brand's focus on the native look: lifted, shadowed, and a 3 pt gold
/// rim just outside the shape (PLAN-IOS.md §11.2).
struct FocusRim: ViewModifier {
    let focused: Bool
    var cornerRadius: CGFloat = 16
    var scale: CGFloat = 1.1

    func body(content: Content) -> some View {
        content
            .overlay {
                if focused {
                    RoundedRectangle(cornerRadius: cornerRadius + 3)
                        .strokeBorder(Palette.gold, lineWidth: 3)
                        .padding(-3)
                }
            }
            .scaleEffect(focused ? scale : 1)
            .shadow(color: .black.opacity(focused ? 0.65 : 0), radius: focused ? 32 : 0, y: focused ? 26 : 0)
            .animation(.easeOut(duration: 0.18), value: focused)
    }
}

extension View {
    func focusRim(_ focused: Bool, cornerRadius: CGFloat = 16, scale: CGFloat = 1.1) -> some View {
        modifier(FocusRim(focused: focused, cornerRadius: cornerRadius, scale: scale))
    }
}

/// A 10-foot button (the artboards' `.btn`): translucent, burgundy for the
/// main action, or red for a destructive one; focused, white with dark text
/// (red for a destructive one), lifted, with the gold rim.
struct TVButtonStyle: ButtonStyle {
    enum Kind { case primary, secondary, danger }

    var kind: Kind = .secondary
    var height: CGFloat = 68
    var fontSize: CGFloat = 24

    func makeBody(configuration: Configuration) -> some View {
        Styled(configuration: configuration, kind: kind, height: height, fontSize: fontSize)
    }

    private struct Styled: View {
        let configuration: Configuration
        let kind: Kind
        let height: CGFloat
        let fontSize: CGFloat
        @Environment(\.isFocused) private var focused
        @Environment(\.isEnabled) private var enabled

        var body: some View {
            configuration.label
                .font(.system(size: fontSize, weight: .semibold))
                .lineLimit(1)
                .foregroundStyle(focused ? (kind == .danger ? Color.tvDanger : Palette.ground) : .white)
                .padding(.horizontal, height * 0.5)
                .frame(height: height)
                .background(background, in: RoundedRectangle(cornerRadius: 16))
                .overlay {
                    if kind != .secondary && !focused {
                        RoundedRectangle(cornerRadius: 16).strokeBorder(kind == .danger ? Color.tvDangerEdge : Palette.burgundyEdge, lineWidth: 1)
                    }
                }
                .focusRim(focused, cornerRadius: 16, scale: 1.06)
                .scaleEffect(configuration.isPressed ? 0.96 : 1)
                .opacity(enabled ? 1 : 0.5)
        }

        private var background: Color {
            if focused { return Palette.text }
            switch kind {
            case .primary: return Palette.burgundy
            case .secondary: return .white.opacity(0.14)
            case .danger: return .tvDanger
            }
        }
    }
}

/// A filter, genre or season chip (TvLibrary.dc.html's `.chip`): gold when
/// chosen, white with the rim when focused.
struct TVChipStyle: ButtonStyle {
    var selected: Bool
    var height: CGFloat = 48
    var fontSize: CGFloat = 19

    func makeBody(configuration: Configuration) -> some View {
        Styled(configuration: configuration, selected: selected, height: height, fontSize: fontSize)
    }

    private struct Styled: View {
        let configuration: Configuration
        let selected: Bool
        let height: CGFloat
        let fontSize: CGFloat
        @Environment(\.isFocused) private var focused

        var body: some View {
            configuration.label
                .font(.system(size: fontSize, weight: .semibold))
                .lineLimit(1)
                .foregroundStyle(focused || selected ? Palette.ground : Color.tvTitle)
                .padding(.horizontal, height * 0.46)
                .frame(height: height)
                .background(focused ? Palette.text : selected ? Palette.gold : .white.opacity(0.08), in: Capsule())
                .overlay {
                    if focused { Capsule().strokeBorder(Palette.gold, lineWidth: 3).padding(-3) }
                }
                .scaleEffect(focused ? 1.08 : configuration.isPressed ? 0.96 : 1)
                .shadow(color: .black.opacity(focused ? 0.5 : 0), radius: focused ? 18 : 0, y: focused ? 12 : 0)
                .animation(.easeOut(duration: 0.15), value: focused)
                .accessibilityAddTraits(selected ? .isSelected : [])
        }
    }
}

/// A chip with a menu under it ("Date added ⌄", "Decade ⌄").
struct ChipMenuLabel: View {
    let title: String

    var body: some View {
        HStack(spacing: 12) {
            Text(title)
            Image(systemName: "chevron.down").font(.system(size: 17, weight: .bold))
        }
    }
}

/// An uppercase label on artwork, at TV size: "NEW" (gold), "4K", "NOT IN LIBRARY" (orange).
struct TVBadge: View {
    let text: String
    var style: Badge.Style = .plain
    var size: CGFloat = 14

    init(_ text: String, style: Badge.Style = .plain, size: CGFloat = 14) {
        self.text = text
        self.style = style
        self.size = size
    }

    var body: some View {
        Text(text.uppercased())
            .font(.system(size: size, weight: .bold))
            .tracking(size * 0.05)
            .lineLimit(1)
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .foregroundStyle(style == .plain ? Palette.text : Palette.ground)
            .background(background, in: RoundedRectangle(cornerRadius: 6))
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
struct TVTag: View {
    let text: String
    var gold = false

    init(_ text: String, gold: Bool = false) {
        self.text = text
        self.gold = gold
    }

    var body: some View {
        Text(text.uppercased())
            .font(.system(size: 15, weight: .bold))
            .tracking(0.6)
            .lineLimit(1)
            .padding(.horizontal, 10)
            .padding(.vertical, 4)
            .foregroundStyle(gold ? Palette.ground : Color.tvTitle)
            .background(gold ? Palette.gold : .clear, in: RoundedRectangle(cornerRadius: 6))
            .overlay(RoundedRectangle(cornerRadius: 6).strokeBorder(gold ? Palette.gold : Palette.outlineStrong))
    }
}

/// "RECENTLY ADDED" with a muted "TV Shows" after it: a row's heading. On
/// Home's screens `counter` ("Row 2 of 7") sits at the right (TvHomeRow.dc.html).
struct RowHeading: View {
    let title: String
    var detail: String?
    var counter: String?

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 14) {
            Text(title).font(.system(size: 28, weight: .semibold)).foregroundStyle(Palette.text)
            if let detail { Text(detail).font(.system(size: 19, weight: .medium)).foregroundStyle(Palette.muted) }
            if let counter {
                Spacer(minLength: 16)
                Text(counter).font(.system(size: 19)).foregroundStyle(Palette.muted).lineLimit(1).padding(.trailing, 48)
            }
        }
        .frame(maxWidth: counter == nil ? nil : .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isHeader)
    }
}

/// "Continuing · next Nov 4" in gold, or "Ended" in grey.
struct TVStatusPill: View {
    let pill: StatusPill

    var body: some View {
        let color = pill.active ? Palette.gold : Palette.muted
        HStack(spacing: 8) {
            Circle().fill(color).frame(width: 8, height: 8)
            Text(pill.text).font(.system(size: 16, weight: .semibold))
        }
        .foregroundStyle(color)
        .padding(.horizontal, 12)
        .padding(.vertical, 5)
        .overlay(Capsule().strokeBorder(color))
        .accessibilityElement(children: .combine)
    }
}

/// The page's facts with dots between them: "2022– · 3 seasons · Drama",
/// then the outlined tags, then `after` ("TV-14", "★ 7.0") as plain text.
struct FactsLine: View {
    let parts: [String]
    var tags: [String] = []
    var after: String?

    var body: some View {
        HStack(spacing: 12) {
            ForEach(Array(parts.enumerated()), id: \.offset) { index, part in
                if index > 0 { Text("·").foregroundStyle(Palette.faint) }
                Text(part)
            }
            if !tags.isEmpty {
                if !parts.isEmpty { Text("·").foregroundStyle(Palette.faint) }
                ForEach(tags, id: \.self) { TVTag($0) }
            }
            if let after {
                if !parts.isEmpty || !tags.isEmpty { Text("·").foregroundStyle(Palette.faint) }
                Text(after)
            }
        }
        .font(.system(size: 21))
        .foregroundStyle(Color.tvTitle)
        .lineLimit(1)
        .accessibilityElement(children: .combine)
    }
}

extension View {
    /// A brief notice at the bottom of the screen ("Marked as watched"): a pill, as tvOS has no toasts.
    func messagePill(_ message: Binding<String?>) -> some View {
        modifier(MessagePill(message: message))
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

    /// Fills the parent and sits at `alignment`, inset by `padding`: a badge in a corner of artwork.
    func pinned(_ alignment: Alignment, padding: CGFloat = 8) -> some View {
        self.padding(padding).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: alignment)
    }
}

private struct MessagePill: ViewModifier {
    @Binding var message: String?

    func body(content: Content) -> some View {
        content
            .overlay(alignment: .bottom) {
                if let message {
                    Text(message)
                        .font(.system(size: 22, weight: .semibold))
                        .foregroundStyle(Palette.text)
                        .padding(.horizontal, 28)
                        .padding(.vertical, 14)
                        .background(Palette.surfaceRaised, in: Capsule())
                        .overlay(Capsule().strokeBorder(Palette.outline))
                        .shadow(color: .black.opacity(0.5), radius: 20, y: 8)
                        .padding(.bottom, 40)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                        .task(id: message) {
                            AccessibilityNotification.Announcement(message).post()
                            // Cancelled while the page settles in (after Back, say): the
                            // note stays up rather than going at once.
                            guard (try? await Task.sleep(for: .seconds(3))) != nil else { return }
                            self.message = nil
                        }
                }
            }
            .animation(.easeOut(duration: 0.25), value: message)
    }
}

/// Loading, failed with Try again, or the content.
struct TVLoadable<Value, Content: View>: View {
    let state: Loadable<Value>
    let retry: () async -> Void
    @ViewBuilder let content: (Value) -> Content

    init(_ state: Loadable<Value>, retry: @escaping () async -> Void, @ViewBuilder content: @escaping (Value) -> Content) {
        self.state = state
        self.retry = retry
        self.content = content
    }

    var body: some View {
        switch state {
        case .loading:
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        case .failed(let message):
            TVErrorState(message: message, retry: retry)
        case .loaded(let value):
            content(value)
        }
    }
}

/// "Couldn't load this", the reason, and Try again.
struct TVErrorState: View {
    var title = "Couldn't load this"
    let message: String
    var retry: (() async -> Void)?

    var body: some View {
        VStack(spacing: 16) {
            Text(title).font(.system(size: 34, weight: .bold)).foregroundStyle(Palette.text)
            Text(message).font(.system(size: 22)).foregroundStyle(Palette.muted).multilineTextAlignment(.center).frame(maxWidth: 900)
            if let retry {
                Button("Try again") { Task { await retry() } }
                    .buttonStyle(TVButtonStyle(kind: .primary))
                    .padding(.top, 12)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

/// A quiet empty state: an icon in a circle, a title and a line (TvSearch.dc.html).
struct TVEmptyState: View {
    let systemImage: String
    let title: String
    let message: String

    var body: some View {
        VStack(spacing: 14) {
            Image(systemName: systemImage)
                .font(.system(size: 44, weight: .medium))
                .foregroundStyle(Palette.faint)
                .frame(width: 96, height: 96)
                .background(.white.opacity(0.06), in: Circle())
            Text(title).font(.system(size: 34, weight: .bold)).foregroundStyle(Palette.text).padding(.top, 6)
            Text(message).font(.system(size: 22)).foregroundStyle(Palette.muted).multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
    }
}
