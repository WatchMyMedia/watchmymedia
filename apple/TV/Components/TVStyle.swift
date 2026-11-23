import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

// The 10-foot look, at tvOS's own numbers: the screen is 1920 × 1080 pt (a
// 1080p TV draws a point as a pixel, a 4K TV as four), text and controls
// stay 80 pt in from the sides and 60 pt from the top and bottom while
// pictures run to the edges, and text uses only tvOS's text styles
// (``TVText``), from 76 pt down to 23 pt.
//
// Focus is the system's: a card lifts, grows and catches the light; a
// button, chip or row grows and turns white. Nothing is drawn around it.
// Gold marks what is chosen, how far along something is, badges and the
// one fact to notice; burgundy fills the main button; a warning is orange.
//
// Cards and rows (posters, wide cards, episode rows, servers) draw their own
// lift from `isFocused` and use the system's `.borderless` button style: on
// tvOS a long press opens a `.contextMenu` only on the system's styles, not
// on a custom `ButtonStyle`.

enum TVLayout {
    /// The left and right margin of every screen: tvOS's safe area at the sides.
    static let gutter: CGFloat = 80
    /// tvOS's safe area at the top and the bottom.
    static let safeTop: CGFloat = 60
    static let safeBottom: CGFloat = 60
    /// The foot of the system's tab bar (68 pt high, 46 pt from the top).
    static let tabBarBottom: CGFloat = 114
    /// Between the cards of a row and the items of a grid.
    static let cardSpacing: CGFloat = 40
}

/// tvOS's text styles, in points. Every word on a screen is one of these;
/// nothing is smaller than `caption2`.
enum TVText: CGFloat {
    case title1 = 76
    case title2 = 57
    case title3 = 48
    case headline = 38
    case callout = 31
    case body = 29
    case caption1 = 25
    case caption2 = 23

    /// The weight the style takes unless a screen says otherwise.
    var weight: Font.Weight {
        switch self {
        case .title1, .title2, .title3: .bold
        case .headline, .callout: .semibold
        case .body, .caption1, .caption2: .medium
        }
    }
}

extension Font {
    /// One of tvOS's text styles, at its own weight or `weight`.
    static func tv(_ style: TVText, _ weight: Font.Weight? = nil) -> Font {
        .system(size: style.rawValue, weight: weight ?? style.weight)
    }
}

/// The widths of cards, from tvOS's grid: each count across fills the
/// 1760 pt between the margins with 40 pt between items, so the next item
/// shows at the edge.
enum TVCardWidth {
    /// A 2:3 poster on Home and in a library's rows: six across.
    static let poster: CGFloat = 260
    /// A 2:3 poster in a library's grid: seven across.
    static let gridPoster: CGFloat = 217
    /// A 16:9 card: five across.
    static let wide: CGFloat = 320
    /// A 16:9 card four across, a little under tvOS's 410 so the fifth peeks: On now.
    static let wideLarge: CGFloat = 400
}

extension Color {
    /// #E5E5E5: titles under cards and facts.
    static let tvTitle = Color(hex: 0xE5E5E5)
    /// #B42318 and its edge: a destructive button (Delete episode).
    static let tvDanger = Color(hex: 0xB42318)
    static let tvDangerEdge = Color(hex: 0xE5484D)
    /// #FF6B63: the words of a button or row that signs out, on the dark ground.
    static let tvRedText = Color(hex: 0xFF6B63)
    /// #FF9B95: Remove this server's words, a paler red that reads at 4.5:1 on a row and on a question's button.
    static let tvPaleRedText = Color(hex: 0xFF9B95)
    /// #77777D: the dots between facts.
    static let tvDot = Color(hex: 0x77777D)
    /// #4A4A50: a line's words on a focused (white) row.
    static let tvOnWhiteMuted = Color(hex: 0x4A4A50)
    /// #D9D9D9: a button held down.
    static let tvPressed = Color(hex: 0xD9D9D9)
    /// A row, list item or tile at rest: white at 7 %.
    static let tvRow = Color.white.opacity(0.07)
}

/// The system's focus on a card: it lifts, grows and casts a deep shadow,
/// and the picture catches the light (``FocusSheen``). Nothing is drawn
/// around it.
struct FocusLift: ViewModifier {
    let focused: Bool
    var scale: CGFloat = 1.1

    func body(content: Content) -> some View {
        content
            .scaleEffect(focused ? scale : 1)
            .shadow(color: .black.opacity(focused ? 0.9 : 0), radius: focused ? 45 : 0, y: focused ? 40 : 0)
            .animation(.easeOut(duration: 0.18), value: focused)
    }
}

extension View {
    /// The system's lift on a focused card or tile.
    func focusLift(_ focused: Bool, scale: CGFloat = 1.1) -> some View {
        modifier(FocusLift(focused: focused, scale: scale))
    }
}

/// A 10-foot button: 80 pt high with Body text, translucent; burgundy with its
/// rim for the screen's one main action, or red for a destructive one.
/// Focused, it grows and turns white with dark words (red for a destructive
/// one); held down, it is a little greyer at its resting size; unavailable,
/// dimmed and still reachable. `redText` is translucent with red words
/// (Sign out in a question), `paleRedText` the same with the paler red
/// (Remove this server), and `goldText` translucent with a gold edge and
/// words for a control that is on (the show page's Notifications on).
struct TVButtonStyle: ButtonStyle {
    enum Kind { case primary, secondary, danger, redText, paleRedText, goldText }

    var kind: Kind = .secondary
    var height: CGFloat = 80
    var fontSize: CGFloat = TVText.body.rawValue

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
            let pressed = configuration.isPressed && focused
            configuration.label
                .font(.system(size: fontSize, weight: .semibold))
                .lineLimit(1)
                .foregroundStyle(foreground)
                .padding(.horizontal, height * 0.5)
                .frame(height: height)
                .background(pressed ? Color.tvPressed : background, in: RoundedRectangle(cornerRadius: height * 0.225))
                .overlay {
                    if !focused, let edge {
                        RoundedRectangle(cornerRadius: height * 0.225).strokeBorder(edge, lineWidth: kind == .goldText ? 3 : 2)
                    }
                }
                .scaleEffect(focused && !pressed ? 1.08 : 1)
                .shadow(color: .black.opacity(focused ? (pressed ? 0.5 : 0.65) : 0), radius: focused ? (pressed ? 8 : 30) : 0, y: focused ? (pressed ? 6 : 24) : 0)
                .animation(.easeOut(duration: 0.15), value: focused)
                .opacity(enabled ? 1 : 0.42)
        }

        private var edge: Color? {
            switch kind {
            case .primary: Palette.burgundyEdge
            case .danger: .tvDangerEdge
            case .goldText: Palette.gold
            case .secondary, .redText, .paleRedText: nil
            }
        }

        private var foreground: Color {
            switch kind {
            case .danger, .redText, .paleRedText: focused ? .tvDanger : kind == .redText ? .tvRedText : kind == .paleRedText ? .tvPaleRedText : .white
            case .goldText: focused ? Color(hex: 0x8A5A00) : Palette.gold
            case .primary, .secondary: focused ? Palette.ground : .white
            }
        }

        private var background: Color {
            if focused { return Palette.text }
            switch kind {
            case .primary: return Palette.burgundy
            case .secondary, .redText, .paleRedText, .goldText: return .white.opacity(0.14)
            case .danger: return .tvDanger
            }
        }
    }
}

/// A filter, season or menu chip, 64 pt high with Body text. Chosen, it has
/// a gold edge and gold words, so it never looks focused; focused, it grows
/// and turns white.
struct TVChipStyle: ButtonStyle {
    var selected: Bool
    var height: CGFloat = 64
    var fontSize: CGFloat = TVText.body.rawValue

    func makeBody(configuration: Configuration) -> some View {
        Styled(configuration: configuration, selected: selected, height: height, fontSize: fontSize)
    }

    private struct Styled: View {
        let configuration: Configuration
        let selected: Bool
        let height: CGFloat
        let fontSize: CGFloat
        @Environment(\.isFocused) private var focused
        @Environment(\.isEnabled) private var enabled

        var body: some View {
            let pressed = configuration.isPressed && focused
            configuration.label
                .font(.system(size: fontSize, weight: .semibold))
                .lineLimit(1)
                .foregroundStyle(focused ? Palette.ground : selected ? Palette.gold : Palette.textSecondary)
                .padding(.horizontal, height * 0.41)
                .frame(height: height)
                .background(focused ? (pressed ? Color.tvPressed : Palette.text) : selected ? Palette.gold.opacity(0.16) : .white.opacity(0.1), in: Capsule())
                .overlay {
                    if selected && !focused { Capsule().strokeBorder(Palette.gold, lineWidth: 2) }
                }
                .scaleEffect(focused && !pressed ? 1.07 : 1)
                .shadow(color: .black.opacity(focused ? 0.6 : 0), radius: focused ? 25 : 0, y: focused ? 20 : 0)
                .animation(.easeOut(duration: 0.15), value: focused)
                .opacity(enabled ? 1 : 0.42)
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
            Image(systemName: "chevron.down").font(.tv(.caption2, .bold))
        }
    }
}

/// A row in a list, a panel or a sheet: 88 pt high at least, its title in
/// Body, a line under it and a value at its right in the captions' sizes.
/// Focused, it grows a little and turns white. `checked` puts a gold tick
/// before the title, for the chosen one of several.
struct TVListRowStyle: ButtonStyle {
    var checked: Bool?
    var minHeight: CGFloat = 88

    func makeBody(configuration: Configuration) -> some View {
        Styled(configuration: configuration, checked: checked, minHeight: minHeight)
    }

    private struct Styled: View {
        let configuration: Configuration
        let checked: Bool?
        let minHeight: CGFloat
        @Environment(\.isFocused) private var focused
        @Environment(\.isEnabled) private var enabled

        var body: some View {
            let pressed = configuration.isPressed && focused
            HStack(spacing: 18) {
                if let checked {
                    Image(systemName: "checkmark")
                        .font(.tv(.caption1, .bold))
                        .foregroundStyle(focused ? Palette.ground : Palette.gold)
                        .frame(width: 32)
                        .opacity(checked ? 1 : 0)
                }
                configuration.label
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .font(.tv(.body, .semibold))
            .foregroundStyle(focused ? Palette.ground : Palette.text)
            .environment(\.tvRowFocused, focused)
            .padding(.horizontal, 28)
            .padding(.vertical, 12)
            .frame(minHeight: minHeight)
            .background(focused ? (pressed ? Color.tvPressed : Palette.text) : Color.tvRow, in: RoundedRectangle(cornerRadius: 18))
            .scaleEffect(focused && !pressed ? 1.04 : 1)
            .shadow(color: .black.opacity(focused ? 0.6 : 0), radius: focused ? 25 : 0, y: focused ? 20 : 0)
            .animation(.easeOut(duration: 0.15), value: focused)
            .opacity(enabled ? 1 : 0.45)
            .accessibilityAddTraits(checked == true ? .isSelected : [])
        }
    }
}

extension EnvironmentValues {
    /// Inside a ``TVListRowStyle`` row that has focus: its line and value turn dark.
    @Entry var tvRowFocused = false
}

/// A list row's words: the title, a line under it and a value at the right.
struct TVRowLabel: View {
    let title: String
    var line: String?
    var value: String?
    /// The line in orange: something to notice (a clash, "Won't record").
    var warns = false
    @Environment(\.tvRowFocused) private var focused

    var body: some View {
        HStack(spacing: 18) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).lineLimit(2)
                if let line {
                    Text(line)
                        .font(.tv(.caption2))
                        .foregroundStyle(focused ? Color.tvOnWhiteMuted : warns ? Palette.warning : Palette.muted)
                        .lineLimit(2)
                }
            }
            Spacer(minLength: 0)
            if let value {
                Text(value)
                    .font(.tv(.caption1))
                    .foregroundStyle(focused ? Color.tvOnWhiteMuted : Palette.muted)
                    .lineLimit(1)
            }
        }
    }
}

/// A group's name over its rows in a panel: "SOUND", "SUBTITLES".
struct TVGroupLabel: View {
    let title: String

    init(_ title: String) { self.title = title }

    var body: some View {
        Text(title.uppercased())
            .font(.tv(.caption2, .bold))
            .tracking(1.8)
            .foregroundStyle(Palette.muted)
            .padding(.top, 10)
            .accessibilityAddTraits(.isHeader)
    }
}

/// The gold words above a title: "NEW · ADDED YESTERDAY".
struct TVKicker: View {
    let text: String
    var color: Color = Palette.gold
    /// A live programme's red dot before the words.
    var live = false

    var body: some View {
        HStack(spacing: 12) {
            if live { Circle().fill(Color(hex: 0xFF4A3D)).frame(width: 14, height: 14) }
            Text(text.uppercased())
                .font(.tv(.caption1, .bold))
                .tracking(2)
                .foregroundStyle(color)
                .lineLimit(1)
        }
    }
}

/// A panel from the right, 800 pt wide, over a dimmed screen: a title's More,
/// File details. Menu closes it, as the screen under it says.
struct TVSidePanel<Content: View>: View {
    @ViewBuilder let content: () -> Content

    var body: some View {
        ZStack(alignment: .trailing) {
            Color(hex: 0x080809).opacity(0.72).ignoresSafeArea()
            VStack(alignment: .leading, spacing: 22) {
                content()
            }
            .padding(.top, TVLayout.safeTop)
            .padding(.bottom, TVLayout.safeBottom)
            .padding(.leading, 56)
            .padding(.trailing, TVLayout.gutter)
            .frame(width: 800, alignment: .topLeading)
            .frame(maxHeight: .infinity, alignment: .top)
            .background(Color(hex: 0x18181B).opacity(0.97))
            .shadow(color: .black.opacity(0.6), radius: 45, x: -40)
            .ignoresSafeArea()
        }
    }
}

/// A choice in the middle of the screen, 1040 pt wide: a programme's
/// Record and Remind me, a clash.
struct TVSheet<Content: View>: View {
    var width: CGFloat = 1040
    @ViewBuilder let content: () -> Content

    var body: some View {
        ZStack {
            Color(hex: 0x080809).opacity(0.72).ignoresSafeArea()
            VStack(alignment: .leading, spacing: 24) {
                content()
            }
            .padding(.horizontal, 64)
            .padding(.vertical, 56)
            .frame(width: width, alignment: .leading)
            .background(Color(hex: 0x1E1E22).opacity(0.98), in: RoundedRectangle(cornerRadius: 32))
            .shadow(color: .black.opacity(0.75), radius: 60, y: 50)
        }
    }
}

/// A content rating in its outlined box: "TV-14", "PG-13".
struct TVRatingBox: View {
    let rating: String

    init(_ rating: String) { self.rating = rating }

    var body: some View {
        Text(rating)
            .font(.tv(.caption2, .bold))
            .foregroundStyle(Palette.textSecondary)
            .lineLimit(1)
            .padding(.horizontal, 10)
            .frame(height: 36)
            .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(Color(hex: 0x8E8E94), lineWidth: 2))
            .accessibilityLabel("Rated \(rating)")
    }
}

/// A label on artwork: "New" (gold), "Not in library" and "Won't record" (orange).
struct TVBadge: View {
    let text: String
    var style: Badge.Style = .plain
    var size: CGFloat = TVText.caption2.rawValue

    init(_ text: String, style: Badge.Style = .plain, size: CGFloat = TVText.caption2.rawValue) {
        self.text = text
        self.style = style
        self.size = size
    }

    var body: some View {
        Text(text)
            .font(.system(size: size, weight: .bold))
            .lineLimit(1)
            .padding(.horizontal, 12)
            .frame(height: 38)
            .foregroundStyle(style == .plain ? .white : Palette.ground)
            .background(background, in: RoundedRectangle(cornerRadius: 8))
    }

    private var background: Color {
        switch style {
        case .plain: Color(hex: 0x0A0A0B).opacity(0.82)
        case .gold: Palette.gold
        case .warning: Palette.warning
        }
    }
}

/// An outlined tag: kept for the few places a format is named on purpose (File details).
struct TVTag: View {
    let text: String
    var gold = false

    init(_ text: String, gold: Bool = false) {
        self.text = text
        self.gold = gold
    }

    var body: some View {
        Text(text)
            .font(.tv(.caption2, .bold))
            .lineLimit(1)
            .padding(.horizontal, 10)
            .frame(height: 36)
            .foregroundStyle(gold ? Palette.ground : Palette.textSecondary)
            .background(gold ? Palette.gold : .clear, in: RoundedRectangle(cornerRadius: 8))
            .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(gold ? Palette.gold : Color(hex: 0x8E8E94), lineWidth: 2))
    }
}

/// A row's heading, "Continue watching", with a muted line or count after it
/// ("1 of 7" on Home's screens, a list's "18 of 50").
struct RowHeading: View {
    let title: String
    var detail: String?
    var counter: String?

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 18) {
            Text(title).font(.tv(.callout)).foregroundStyle(Palette.text).lineLimit(1)
            if let detail { Text(detail).font(.tv(.caption1)).foregroundStyle(Palette.muted).lineLimit(1) }
            if let counter { Text(counter).font(.tv(.caption1)).foregroundStyle(Palette.muted).lineLimit(1) }
        }
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isHeader)
    }
}

/// "Continuing · next Nov 4" in gold, or "Ended" in grey.
struct TVStatusPill: View {
    let pill: StatusPill

    var body: some View {
        let color = pill.active ? Palette.gold : Palette.muted
        HStack(spacing: 10) {
            Circle().fill(color).frame(width: 10, height: 10)
            Text(pill.text).font(.tv(.caption2, .semibold))
        }
        .foregroundStyle(color)
        .padding(.horizontal, 14)
        .frame(height: 40)
        .overlay(Capsule().strokeBorder(color, lineWidth: 2))
        .accessibilityElement(children: .combine)
    }
}

/// A title's facts with dots between them, in Body: `lead` first in gold (the
/// one fact to notice, "23 min left"), then `parts` ("Ends at 9:41 PM",
/// "2024", "1 h 52 min"), the content rating in its box, then `after`
/// ("Thriller").
struct FactsLine: View {
    var lead: String?
    let parts: [String]
    var rating: String?
    var after: [String] = []

    init(lead: String? = nil, parts: [String], rating: String? = nil, after: [String] = []) {
        self.lead = lead
        self.parts = parts
        self.rating = rating
        self.after = after
    }

    var body: some View {
        let words: [Item] = [lead.map { Item.lead($0) }].compactMap { $0 } + parts.map(Item.part) + [rating.map(Item.rating)].compactMap { $0 } + after.map(Item.part)
        HStack(spacing: 14) {
            ForEach(Array(words.enumerated()), id: \.offset) { index, item in
                if index > 0 { Text("·").foregroundStyle(Color.tvDot) }
                switch item {
                case .lead(let text): Text(text).font(.tv(.body, .semibold)).foregroundStyle(Palette.gold)
                case .part(let text): Text(text)
                case .rating(let text): TVRatingBox(text)
                }
            }
        }
        .font(.tv(.body))
        .foregroundStyle(Palette.textSecondary)
        .lineLimit(1)
        .accessibilityElement(children: .combine)
    }

    /// Older screens' facts, with format tags after the parts: drawn as plain parts.
    init(parts: [String], tags: [String], after: String? = nil) {
        self.init(parts: parts + tags, after: [after].compactMap { $0 })
    }

    private enum Item {
        case lead(String)
        case part(String)
        case rating(String)
    }
}

extension View {
    /// A brief notice at the foot of the screen ("Marked as watched"): a pill, as tvOS has no toasts.
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
    func pinned(_ alignment: Alignment, padding: CGFloat = 12) -> some View {
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
                        .font(.tv(.body, .semibold))
                        .foregroundStyle(Palette.text)
                        .padding(.horizontal, 34)
                        .padding(.vertical, 20)
                        .background(Color(hex: 0x18181B).opacity(0.96), in: RoundedRectangle(cornerRadius: 24))
                        .overlay(RoundedRectangle(cornerRadius: 24).strokeBorder(Color(hex: 0x34343A), lineWidth: 2))
                        .shadow(color: .black.opacity(0.6), radius: 25, y: 20)
                        .frame(maxWidth: 1500)
                        .padding(.bottom, TVLayout.safeBottom)
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
            TVSpinner().frame(maxWidth: .infinity, maxHeight: .infinity)
        case .failed(let message):
            TVErrorState(message: message, retry: retry)
        case .loaded(let value):
            content(value)
        }
    }
}

/// The spinner while something loads: the system's, at TV size.
struct TVSpinner: View {
    var body: some View {
        ProgressView()
            .controlSize(.large)
            .accessibilityLabel("Loading")
    }
}

/// "Couldn't load this", why, and Try again: at the left of the screen, as a
/// state that replaces a screen's content.
struct TVErrorState: View {
    var title = "Couldn't load this"
    let message: String
    var retry: (() async -> Void)?

    var body: some View {
        VStack(alignment: .leading, spacing: 24) {
            Text(title).font(.tv(.title2)).foregroundStyle(Palette.text)
            Text(message).font(.tv(.body)).foregroundStyle(Palette.textSecondary).frame(maxWidth: 860, alignment: .leading)
            if let retry {
                Button("Try again") { Task { await retry() } }
                    .buttonStyle(TVButtonStyle(kind: .primary))
                    .padding(.top, 16)
            }
        }
        .padding(.horizontal, TVLayout.gutter)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .focusSection()
    }
}

/// A quiet empty state: an icon, a title and a line.
struct TVEmptyState: View {
    let systemImage: String
    let title: String
    let message: String

    var body: some View {
        VStack(spacing: 20) {
            Image(systemName: systemImage)
                .font(.system(size: 56, weight: .medium))
                .foregroundStyle(Palette.muted)
                .frame(width: 120, height: 120)
                .background(.white.opacity(0.06), in: Circle())
            Text(title).font(.tv(.title3)).foregroundStyle(Palette.text).padding(.top, 6)
            Text(message).font(.tv(.body)).foregroundStyle(Palette.textSecondary).multilineTextAlignment(.center).frame(maxWidth: 1100)
        }
        .frame(maxWidth: .infinity)
    }
}
