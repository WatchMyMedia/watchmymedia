import SwiftUI
import WMMUI

// The grouped look the account screens share (ServerPicker, Main, Settings*,
// LinkTv artboards): uppercase muted headings, #1A1A1D groups with 12 pt
// corners and hairline separators, faint footnotes, square icon tiles.

/// A square icon tile at the start of a row.
struct RowIcon: View {
    enum Style { case burgundy, neutral, gold }

    let systemImage: String
    var style: Style = .neutral
    var size: CGFloat = 30

    var body: some View {
        Image(systemName: systemImage)
            .font(.system(size: size * 0.52, weight: .semibold))
            .foregroundStyle(style == .gold ? Palette.gold : Palette.text)
            .frame(width: size, height: size)
            .background(background, in: RoundedRectangle(cornerRadius: size * 0.27))
            .accessibilityHidden(true)
    }

    private var background: Color {
        switch style {
        case .burgundy: Palette.burgundy
        case .neutral: Palette.outline
        case .gold: Palette.gold.opacity(0.14)
        }
    }
}

/// "ON THIS NETWORK": a group's heading.
struct GroupHeading: View {
    let text: String

    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text.uppercased())
            .font(.footnote.weight(.semibold))
            .tracking(0.7)
            .foregroundStyle(Palette.muted)
            .accessibilityAddTraits(.isHeader)
    }
}

/// The faint note under a group.
struct GroupFootnote: View {
    let text: String

    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text)
            .font(.footnote)
            .foregroundStyle(Palette.faint)
            .fixedSize(horizontal: false, vertical: true)
    }
}

/// Why something failed, in the warning colour, with an optional dismiss.
struct ErrorLine: View {
    let message: String
    var dismiss: (() -> Void)?

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Image(systemName: "exclamationmark.circle.fill")
                .accessibilityHidden(true)
            Text(message)
                .frame(maxWidth: .infinity, alignment: .leading)
                .fixedSize(horizontal: false, vertical: true)
            if let dismiss {
                Button(action: dismiss) {
                    Image(systemName: "xmark")
                        .font(.footnote.weight(.bold))
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Dismiss")
                .padding(.vertical, -12)
            }
        }
        .font(.subheadline)
        .foregroundStyle(Palette.warning)
        .accessibilityElement(children: .combine)
    }
}

/// A rounded group of rows with hairlines between them, outside a List.
struct RoundedGroup<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        VStack(spacing: 0) {
            Group(subviews: content) { rows in
                ForEach(Array(rows.enumerated()), id: \.element.id) { index, row in
                    if index > 0 { Rectangle().fill(Palette.separator).frame(height: 1) }
                    row
                }
            }
        }
        .background(Palette.surfaceRaised)
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(Palette.separator))
    }
}

/// A row's trailing chevron.
struct RowChevron: View {
    var body: some View {
        Image(systemName: "chevron.right")
            .font(.footnote.weight(.semibold))
            .foregroundStyle(Palette.faint)
            .accessibilityHidden(true)
    }
}

/// A gold text button: Change, Get a new code.
struct GoldTextButtonStyle: ButtonStyle {
    var font: Font = .subheadline.weight(.semibold)

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(font)
            .foregroundStyle(Palette.gold.opacity(configuration.isPressed ? 0.6 : 1))
            .frame(minWidth: 44, minHeight: 44)
            .contentShape(Rectangle())
    }
}

/// The label of a primary button that shows progress while `busy`.
struct BusyLabel: View {
    let title: String
    let busy: Bool

    var body: some View {
        ZStack {
            Text(title).opacity(busy ? 0 : 1)
            if busy { ProgressView().tint(.white) }
        }
        .accessibilityLabel(busy ? "\(title), in progress" : title)
    }
}

extension View {
    /// An inset grouped List in the app's colours.
    func accountList() -> some View {
        listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
            .listSectionSpacing(20)
            .groundBackground()
    }

    /// A List row on the group colour, with the mockups' separators.
    func accountRow() -> some View {
        listRowBackground(Palette.surfaceRaised)
            .listRowSeparatorTint(Palette.separator)
    }

    /// A List row with nothing behind it: a button or a line under the groups.
    func clearListRow() -> some View {
        listRowBackground(Color.clear)
            .listRowInsets(EdgeInsets())
            .listRowSeparator(.hidden)
    }
}
