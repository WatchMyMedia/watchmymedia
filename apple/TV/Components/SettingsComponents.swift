import SwiftUI
import WMMModel
import WMMUI

// Settings' pieces (TvSettings.dc.html), shared by its screens.

/// A group of settings under an uppercase heading.
struct SettingsGroup<Content: View>: View {
    let title: String
    let content: Content

    init(_ title: String, @ViewBuilder content: () -> Content) {
        self.title = title
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title.uppercased())
                .font(.system(size: 16, weight: .bold))
                .tracking(1.6)
                .foregroundStyle(Palette.muted)
                .accessibilityAddTraits(.isHeader)
            content
        }
    }
}

/// A row: its title, a muted line, and a value or control at the end.
struct SettingsRow<Accessory: View>: View {
    let title: String
    var detail: String?
    var gold = false
    var compact = false
    @ViewBuilder var accessory: () -> Accessory
    @Environment(\.isFocused) private var focused

    var body: some View {
        HStack(spacing: 14) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.system(size: compact ? 18 : 20, weight: .semibold))
                    .foregroundStyle(focused ? Palette.ground : gold ? Palette.gold : Palette.text)
                if let detail {
                    Text(detail).font(.system(size: compact ? 14 : 16)).foregroundStyle(focused ? Color(hex: 0x525252) : Palette.muted)
                }
            }
            .lineLimit(1)
            Spacer(minLength: 12)
            accessory()
                .font(.system(size: 18))
                .foregroundStyle(focused ? Palette.ground : Palette.textSecondary)
        }
        .padding(.horizontal, 18)
        .frame(minHeight: compact ? 56 : 62)
    }
}

/// Settings rows light up white with the gold rim when focused.
struct SettingsRowStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        Styled(configuration: configuration)
    }

    struct Styled: View {
        let configuration: Configuration
        @Environment(\.isFocused) private var focused

        var body: some View {
            configuration.label
                .background(focused ? Palette.text : .white.opacity(0.05), in: RoundedRectangle(cornerRadius: 14))
                .focusRim(focused, cornerRadius: 14, scale: 1.03)
                .scaleEffect(configuration.isPressed ? 0.98 : 1)
        }
    }
}

/// The left column's section buttons (TvSettings.dc.html's `.nav`).
struct SettingsNavStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        Styled(configuration: configuration)
    }

    struct Styled: View {
        let configuration: Configuration
        @Environment(\.isFocused) private var focused

        var body: some View {
            configuration.label
                .font(.system(size: 22, weight: .semibold))
                .foregroundStyle(focused ? Palette.ground : Palette.textSecondary)
                .padding(.horizontal, 24)
                .frame(maxWidth: .infinity, minHeight: 64, alignment: .leading)
                .background(focused ? Palette.text : .clear, in: RoundedRectangle(cornerRadius: 16))
                .focusRim(focused, cornerRadius: 16, scale: 1.04)
        }
    }
}

/// Never / Ask / Auto / Auto after 3 s as one control (the artboard's `.seg`):
/// focus moves along it, Select chooses.
struct SkipControl: View {
    let mode: SkipMode
    let choose: (SkipMode) -> Void

    var body: some View {
        HStack(spacing: 2) {
            ForEach(SkipMode.allCases, id: \.self) { option in
                Button(option.label) { choose(option) }
                    .buttonStyle(TVChipStyle(selected: option == mode, height: 40, fontSize: 16))
            }
        }
        .padding(4)
        .background(.white.opacity(0.08), in: Capsule())
    }
}

/// The on/off switch in a Settings row, or smaller (`compact`) in a button
/// such as the show page's Show missing: gold with the knob right when on.
struct TVSwitch: View {
    let on: Bool
    var compact = false

    var body: some View {
        Capsule()
            .fill(on ? Palette.gold : Palette.outlineStrong)
            .frame(width: compact ? 56 : 64, height: compact ? 32 : 36)
            .overlay(alignment: on ? .trailing : .leading) {
                Circle().fill(on ? Palette.ground : Palette.text).frame(width: compact ? 24 : 28, height: compact ? 24 : 28).padding(4)
            }
            .animation(.easeOut(duration: 0.15), value: on)
            .accessibilityHidden(true)
    }
}

/// A label and its value, not focusable.
struct FactRow: View {
    let label: String
    let value: String

    var body: some View {
        HStack(spacing: 14) {
            Text(label).font(.system(size: 17, weight: .semibold)).foregroundStyle(Palette.text)
            Spacer(minLength: 12)
            Text(value).font(.system(size: 15)).foregroundStyle(Palette.textSecondary).lineLimit(1).minimumScaleFactor(0.8)
        }
        .padding(.horizontal, 18)
        .frame(minHeight: 48)
        .background(.white.opacity(0.05), in: RoundedRectangle(cornerRadius: 14))
        .accessibilityElement(children: .combine)
    }
}

struct ErrorText: View {
    let text: String

    init(_ text: String) { self.text = text }

    var body: some View {
        Label(text, systemImage: "exclamationmark.circle.fill")
            .font(.system(size: 17))
            .foregroundStyle(Palette.warning)
            .padding(.horizontal, 18)
    }
}
