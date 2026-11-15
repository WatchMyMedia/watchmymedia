import SwiftUI
import UIKit
import WMMFeatures
import WMMUI

// The pieces every Settings page shares (Settings2*.dc.html,
// Pad2Settings2*.dc.html, PLAN-APPLE-SETTINGS.md §3): a page of groups under
// a line that says how things stand, rows that grow with the text size and
// say in their own line whether a change saved, menus from their row, and a
// red row apart at the bottom for what signs out or deletes.

/// What a row says under its title: its usual line, or how its last change went.
enum RowLine: Equatable {
    case plain(String)
    case saving
    case saved(String)
    case failed(String)

    var text: String? {
        switch self {
        case .plain(let text), .saved(let text), .failed(let text): text
        case .saving: "Saving…"
        }
    }
}

extension SaveState {
    /// A row's or section's line for this state: its usual line at rest
    /// (`rest`), Saving, the Saved line, or why it failed.
    func rowLine(rest: String?) -> RowLine? {
        switch self {
        case .rest: rest.map(RowLine.plain)
        case .saving: .saving
        case .saved(let line): .saved(line)
        case .failed(let line): .failed(line)
        }
    }
}

/// A row's title and the line under it. A Saved line has a gold tick, a
/// failed one the warning colour, and Saving a small spinner.
struct RowTitle: View {
    let title: String
    var line: RowLine?
    var titleColor: Color = Palette.text

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
                .font(.body.weight(.medium))
                .foregroundStyle(titleColor)
                .fixedSize(horizontal: false, vertical: true)
            if let line { lineView(line) }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .multilineTextAlignment(.leading)
        .speaksSaving(line)
    }

    @ViewBuilder private func lineView(_ line: RowLine) -> some View {
        switch line {
        case .plain(let text):
            Text(text).font(.footnote).foregroundStyle(Palette.muted).fixedSize(horizontal: false, vertical: true)
        case .saving:
            HStack(spacing: 6) {
                ProgressView().controlSize(.mini).tint(Palette.gold)
                Text("Saving…").font(.footnote).foregroundStyle(Palette.muted)
            }
        case .saved(let text):
            Text("\(Text("✓").foregroundStyle(Palette.gold).fontWeight(.bold)) \(text)")
                .font(.footnote)
                .foregroundStyle(Palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
        case .failed(let text):
            Text(text).font(.footnote).foregroundStyle(Palette.warning).fixedSize(horizontal: false, vertical: true)
        }
    }
}

/// A row's parts side by side, the value whole at the right as the boards
/// draw it; stacked, the value under the title, when the value would take
/// more than half a row it can't share or at the accessibility text sizes, so both wrap
/// rather than cut short.
struct AdaptiveRow<Leading: View, Trailing: View>: View {
    @ViewBuilder let leading: Leading
    @ViewBuilder let trailing: Trailing
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        RowLayout(stacked: typeSize.isAccessibilitySize) {
            leading
            trailing
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
        .contentShape(Rectangle())
    }
}

/// Lays a row's title and value side by side or one under the other.
private struct RowLayout: Layout {
    var stacked: Bool
    var spacing: CGFloat = 12

    /// Side by side when both fit on one line, or the value takes at most about half the row.
    private func sideBySide(_ width: CGFloat, _ subviews: Subviews) -> Bool {
        guard !stacked, subviews.count > 1 else { return subviews.count <= 1 }
        let value = subviews[1].sizeThatFits(.unspecified).width
        return value <= width * 0.55 || subviews[0].sizeThatFits(.unspecified).width + spacing + value <= width
    }

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        guard let first = subviews.first else { return .zero }
        let width = proposal.width ?? subviews.reduce(0) { $0 + $1.sizeThatFits(.unspecified).width } + spacing
        guard subviews.count > 1 else { return CGSize(width: width, height: first.sizeThatFits(ProposedViewSize(width: width, height: nil)).height) }
        if sideBySide(width, subviews) {
            let value = subviews[1].sizeThatFits(.unspecified)
            let title = first.sizeThatFits(ProposedViewSize(width: max(width - value.width - spacing, 0), height: nil))
            return CGSize(width: width, height: max(title.height, value.height))
        }
        let title = first.sizeThatFits(ProposedViewSize(width: width, height: nil))
        let value = subviews[1].sizeThatFits(ProposedViewSize(width: width, height: nil))
        return CGSize(width: width, height: title.height + (value.height > 0 ? 6 + value.height : 0))
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        guard let first = subviews.first else { return }
        guard subviews.count > 1 else {
            first.place(at: CGPoint(x: bounds.minX, y: bounds.midY), anchor: .leading, proposal: ProposedViewSize(width: bounds.width, height: nil))
            return
        }
        if sideBySide(bounds.width, subviews) {
            let value = subviews[1].sizeThatFits(.unspecified)
            first.place(at: CGPoint(x: bounds.minX, y: bounds.midY), anchor: .leading,
                        proposal: ProposedViewSize(width: max(bounds.width - value.width - spacing, 0), height: nil))
            subviews[1].place(at: CGPoint(x: bounds.maxX, y: bounds.midY), anchor: .trailing, proposal: .unspecified)
        } else {
            let title = first.sizeThatFits(ProposedViewSize(width: bounds.width, height: nil))
            first.place(at: CGPoint(x: bounds.minX, y: bounds.minY), anchor: .topLeading, proposal: ProposedViewSize(width: bounds.width, height: nil))
            subviews[1].place(at: CGPoint(x: bounds.minX, y: bounds.minY + title.height + 6), anchor: .topLeading,
                              proposal: ProposedViewSize(width: bounds.width, height: nil))
        }
    }
}

/// The value at the right of a row, and the chevron after it.
struct RowValue: View {
    let value: String?
    var gold = false
    var chevron = true
    var menu = false

    var body: some View {
        HStack(spacing: 6) {
            if let value {
                Text(value)
                    .font(.subheadline)
                    .foregroundStyle(gold ? Palette.gold : menu ? Palette.textSecondary : Palette.muted)
                    .multilineTextAlignment(.trailing)
            }
            if menu {
                Image(systemName: "chevron.up.chevron.down")
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(Palette.faint)
                    .accessibilityHidden(true)
            } else if chevron {
                RowChevron()
            }
        }
    }
}

/// A row that opens a page or a sheet.
struct LinkRowLabel: View {
    let title: String
    var line: RowLine?
    var value: String?
    var icon: (String, RowIcon.Style)?
    var gold = false

    var body: some View {
        AdaptiveRow {
            HStack(spacing: 12) {
                if let icon { RowIcon(systemImage: icon.0, style: icon.1, size: 30) }
                RowTitle(title: title, line: line)
            }
        } trailing: {
            RowValue(value: value, gold: gold)
        }
    }
}

/// A choice opened as a menu from its row: each choice with a line saying
/// what it does, a tick on the chosen one, and the row's value its name.
struct MenuRow<Value: Hashable>: View {
    struct Choice: Identifiable {
        let value: Value
        let title: String
        var line: String?
        var id: Value { value }
    }

    let title: String
    var line: RowLine?
    let choices: [Choice]
    let selection: Value
    var disabled = false
    let choose: (Value) -> Void

    var body: some View {
        Menu {
            // A toggle per choice: the menu ticks the chosen one, and a
            // second Text in a menu item's label is the line under it.
            ForEach(choices) { choice in
                Toggle(isOn: Binding(get: { choice.value == selection }, set: { if $0 { choose(choice.value) } })) {
                    Text(choice.title)
                    if let detail = choice.line { Text(detail) }
                }
            }
        } label: {
            AdaptiveRow {
                RowTitle(title: title, line: line)
            } trailing: {
                RowValue(value: choices.first { $0.value == selection }?.title, menu: true)
            }
        }
        .buttonStyle(SettingsRowStyle())
        .disabled(disabled)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(title)
        .accessibilityValue([choices.first { $0.value == selection }?.title, line?.text].compactMap(\.self).joined(separator: ", "))
        .accessibilityAddTraits(.isButton)
        .accessibilityHint("Opens a menu of choices")
    }
}

/// A switch in a row, with its line under the title.
struct SwitchRow: View {
    let title: String
    var line: RowLine?
    let isOn: Bool
    var disabled = false
    let set: (Bool) -> Void

    var body: some View {
        Toggle(isOn: Binding(get: { isOn }, set: { set($0) })) {
            RowTitle(title: title, line: line)
        }
        .tint(Palette.gold)
        .disabled(disabled)
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
    }
}

/// A label and its value, read-only.
struct FactRow: View {
    let label: String
    let value: String

    var body: some View {
        AdaptiveRow {
            Text(label).font(.body.weight(.medium)).foregroundStyle(Palette.text)
        } trailing: {
            Text(value)
                .font(.subheadline)
                .foregroundStyle(Palette.muted)
                .multilineTextAlignment(.trailing)
                .frame(maxWidth: .infinity, alignment: .trailing)
        }
        .accessibilityElement(children: .combine)
    }
}

/// A row that lights while pressed, as a List row does, and under an iPad's pointer.
struct SettingsRowStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background(configuration.isPressed ? Color.white.opacity(0.08) : .clear)
            .hoverEffect(.highlight)
    }
}

/// A group: its heading, a line saying what it is for, the rows, and a note under them.
struct SettingsSection<Rows: View, Note: View>: View {
    var title: String?
    var description: String?
    @ViewBuilder let rows: Rows
    @ViewBuilder let note: Note

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if let title { GroupHeading(title).padding(.horizontal, 16) }
            if let description {
                Text(description)
                    .font(.footnote)
                    .foregroundStyle(Palette.muted)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.horizontal, 16)
                    .padding(.bottom, 2)
            }
            RoundedGroup { rows }
            note
                .padding(.horizontal, 16)
                .padding(.top, 2)
        }
    }
}

extension SettingsSection where Note == EmptyView {
    init(title: String? = nil, description: String? = nil, @ViewBuilder rows: () -> Rows) {
        self.init(title: title, description: description, rows: rows, note: { EmptyView() })
    }
}

/// The note under a group: plain, a Saved line, or a failure.
struct SectionNote: View {
    let line: RowLine

    init(_ line: RowLine) { self.line = line }
    init(_ text: String) { line = .plain(text) }

    var body: some View {
        content.speaksSaving(line)
    }

    @ViewBuilder private var content: some View {
        switch line {
        case .plain(let text): GroupFootnote(text)
        case .saving:
            HStack(spacing: 6) {
                ProgressView().controlSize(.mini).tint(Palette.gold)
                Text("Saving…").font(.footnote).foregroundStyle(Palette.muted)
            }
        case .saved(let text):
            Text("\(Text("✓").foregroundStyle(Palette.gold).fontWeight(.bold)) \(text)")
                .font(.footnote)
                .foregroundStyle(Palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
        case .failed(let text):
            Text(text).font(.footnote).foregroundStyle(Palette.warning).fixedSize(horizontal: false, vertical: true)
        }
    }
}

/// Whether the page has room for two columns (the iPad's detail beside its sidebar).
private struct SettingsWideKey: EnvironmentKey {
    static let defaultValue = false
}

extension EnvironmentValues {
    var settingsWide: Bool {
        get { self[SettingsWideKey.self] }
        set { self[SettingsWideKey.self] = newValue }
    }
}

/// A Settings page: a line under the large title saying how things stand,
/// then its groups. From 680 pt wide the content can stand in two columns
/// (``SettingsColumns``).
struct SettingsScroll<Content: View>: View {
    let lede: String?
    @ViewBuilder let content: Content
    @State private var width: CGFloat = 0

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                if let lede {
                    Text(lede)
                        .font(.subheadline)
                        .foregroundStyle(Palette.muted)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.horizontal, 4)
                        .accessibilityIdentifier("settings-lede")
                }
                content
            }
            .padding(.horizontal, width >= 680 ? 32 : 16)
            .padding(.top, 4)
            .padding(.bottom, 28)
            .frame(maxWidth: .infinity, alignment: .leading)
            .environment(\.settingsWide, width >= 680)
        }
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width = $0 }
        .scrollBounceBehavior(.basedOnSize)
        .groundBackground()
    }
}

/// Two columns where the page is wide, one under the other where it isn't.
struct SettingsColumns<Left: View, Right: View>: View {
    @ViewBuilder let left: Left
    @ViewBuilder let right: Right
    @Environment(\.settingsWide) private var wide

    var body: some View {
        if wide {
            HStack(alignment: .top, spacing: 24) {
                VStack(alignment: .leading, spacing: 22) { left }.frame(maxWidth: .infinity)
                VStack(alignment: .leading, spacing: 22) { right }.frame(maxWidth: .infinity)
            }
        } else {
            left
            right
        }
    }
}

/// One layout where the page is wide and another where it isn't, for pages
/// whose two columns hold their groups in another order than the phone's.
struct SettingsAdaptive<Wide: View, Narrow: View>: View {
    @ViewBuilder let wide: Wide
    @ViewBuilder let narrow: Narrow
    @Environment(\.settingsWide) private var isWide

    var body: some View {
        if isWide { wide } else { narrow }
    }
}

/// A banner for the page: what happened (gold) or what went wrong (orange).
struct SettingsBanner: View {
    enum Kind { case info, problem }

    let kind: Kind
    let title: String
    let message: String
    var action: (title: String, run: () -> Void)?

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: kind == .info ? "checkmark.circle" : "exclamationmark.circle")
                .foregroundStyle(kind == .info ? Palette.gold : Palette.warning)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.subheadline.weight(.semibold)).foregroundStyle(Palette.text)
                Text(message).font(.footnote).foregroundStyle(Palette.text).fixedSize(horizontal: false, vertical: true)
                if let action {
                    Button(action.title, action: action.run)
                        .buttonStyle(SmallGhostButtonStyle())
                        .padding(.top, 6)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .background((kind == .info ? Palette.gold : Palette.warning).opacity(0.10), in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder((kind == .info ? Palette.gold.opacity(0.32) : Palette.warning.opacity(0.45))))
        .accessibilityElement(children: .combine)
    }
}

/// A red row apart at the bottom of its page: what signs out or deletes.
struct DangerRowLabel: View {
    let title: String

    var body: some View {
        AdaptiveRow {
            Text(title)
                .font(.body.weight(.medium))
                .foregroundStyle(Color(red: 1, green: 0.27, blue: 0.23))
                .frame(maxWidth: .infinity, alignment: .leading)
        } trailing: {
            EmptyView()
        }
    }
}

extension View {
    /// A Saved or a failed line is said to VoiceOver as it appears, with the
    /// system's success tap or error tap (PLAN-APPLE-SETTINGS.md §3.2).
    func speaksSaving(_ line: RowLine?) -> some View {
        onChange(of: line) { _, line in
            switch line {
            case .saved(let text)?, .failed(let text)?: AccessibilityNotification.Announcement(text).post()
            default: break
            }
        }
        .sensoryFeedback(trigger: line) { _, line in
            switch line {
            case .saved?: .success
            case .failed?: .error
            default: nil
            }
        }
    }
}
