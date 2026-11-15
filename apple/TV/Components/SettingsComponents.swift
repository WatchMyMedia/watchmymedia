import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

// Settings' pieces (TvSettings2*.dc.html, PLAN-APPLE-SETTINGS.md §3.3),
// shared by its group pages and its full-screen tasks.

/// A group of rows under an uppercase heading ("SKIPPING"); no heading
/// for a row that stands apart (Sign out of this Apple TV).
struct SettingsGroup<Content: View>: View {
    let title: String?
    let content: Content

    init(_ title: String?, @ViewBuilder content: () -> Content) {
        self.title = title
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let title {
                Text(title.uppercased())
                    .font(.system(size: 16, weight: .bold))
                    .tracking(1.6)
                    .foregroundStyle(Palette.muted)
                    .accessibilityAddTraits(.isHeader)
            }
            content
        }
    }
}

/// What a row that saves at once says under its title while and after it
/// saves (§3.1): Saving, Saved and what the change did, or why it failed
/// and what it still is.
enum RowNote: Equatable {
    case saving
    /// "Saved. Credits now skip after 3 seconds, on every device."
    case saved(String)
    case failed(String)

    /// A control's ``SaveState`` from the Kit; nil at rest, when the row says its usual line.
    init?(_ state: SaveState) {
        switch state {
        case .rest: return nil
        case .saving: self = .saving
        case .saved(let line): self = .saved(line)
        case .failed(let line): self = .failed(line)
        }
    }

    var text: String {
        switch self {
        case .saving: "Saving…"
        case .saved(let line): "✓ \(line)"
        case .failed(let reason): reason
        }
    }
}

/// A row: its title, a muted line (or its ``RowNote``), and a value or
/// control at the end (the board's `.row`).
struct SettingsRow<Accessory: View>: View {
    let title: String
    var detail: String?
    var note: RowNote?
    var red = false
    @ViewBuilder var accessory: () -> Accessory
    @Environment(\.isFocused) private var focused

    var body: some View {
        HStack(spacing: 14) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.system(size: 20, weight: .semibold))
                    .foregroundStyle(red ? (focused ? Color.tvDanger : Color.tvRedText) : focused ? Palette.ground : Palette.text)
                    .lineLimit(1)
                if let line = note?.text ?? detail {
                    Text(line)
                        .font(.system(size: 16))
                        .lineSpacing(2)
                        .foregroundStyle(lineColor)
                        .lineLimit(3)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            Spacer(minLength: 12)
            accessory()
                .font(.system(size: 18))
                .foregroundStyle(focused ? Palette.ground : Palette.textSecondary)
                .lineLimit(1)
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 6)
        .frame(minHeight: 58)
    }

    private var lineColor: Color {
        switch note {
        case .failed: focused ? Color(hex: 0x9A3412) : Palette.warning
        case .saved, .saving: focused ? Color(hex: 0x525252) : Palette.textSecondary
        case nil: focused ? Color(hex: 0x525252) : Palette.muted
        }
    }
}

/// A row's value with the chevron that says Select opens something.
struct RowValue: View {
    let text: String?
    var chevron = true

    var body: some View {
        HStack(spacing: 8) {
            if let text { Text(text) }
            if chevron { Image(systemName: "chevron.right").font(.system(size: 17, weight: .bold)) }
        }
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
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(focused ? Palette.text : .white.opacity(0.05), in: RoundedRectangle(cornerRadius: 14))
                .focusRim(focused, cornerRadius: 14, scale: 1.03)
                .scaleEffect(configuration.isPressed ? 0.98 : 1)
        }
    }
}

/// A group in the list at the left (the board's `.nav`): its name and how
/// things stand in it; gold on a lighter ground while its page is shown,
/// white with the rim when focused.
struct SettingsNavLabel: View {
    let title: String
    let line: String
    @Environment(\.settingsNavLineColor) private var lineColor

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title).font(.system(size: 22, weight: .semibold))
            Text(line).font(.system(size: 15)).foregroundStyle(lineColor).lineLimit(1).opacity(line.isEmpty ? 0 : 1)
        }
    }
}

struct SettingsNavStyle: ButtonStyle {
    var chosen: Bool

    func makeBody(configuration: Configuration) -> some View {
        Styled(configuration: configuration, chosen: chosen)
    }

    struct Styled: View {
        let configuration: Configuration
        let chosen: Bool
        @Environment(\.isFocused) private var focused

        var body: some View {
            configuration.label
                .foregroundStyle(focused ? Palette.ground : chosen ? Palette.gold : Palette.textSecondary)
                // The line under the name stays muted (darker grey when focused).
                .environment(\.settingsNavLineColor, focused ? Color(hex: 0x525252) : Palette.muted)
                .padding(.horizontal, 24)
                .frame(maxWidth: .infinity, minHeight: 72, alignment: .leading)
                .background(focused ? Palette.text : chosen ? .white.opacity(0.10) : .clear, in: RoundedRectangle(cornerRadius: 16))
                .focusRim(focused, cornerRadius: 16, scale: 1.04)
                .accessibilityAddTraits(chosen ? .isSelected : [])
        }
    }
}

extension EnvironmentValues {
    @Entry var settingsNavLineColor: Color = Palette.muted
}

/// What the focused setting does, at the right of Settings (the board's
/// `.help`), in place of footnotes nobody can read from the sofa.
struct SettingsHelp: Equatable {
    var title: String
    var paragraphs: [String]
    /// "Select changes it · Menu goes back to the list".
    var hint: String
}

struct SettingsHelpPanel: View {
    let help: SettingsHelp

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(help.title)
                .font(.system(size: 24, weight: .bold))
                .foregroundStyle(Palette.text)
            ForEach(Array(help.paragraphs.enumerated()), id: \.offset) { _, paragraph in
                Text(paragraph)
                    .font(.system(size: 18))
                    .lineSpacing(5)
                    .foregroundStyle(Palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Text(help.hint)
                .font(.system(size: 16))
                .foregroundStyle(Palette.faint)
                .padding(.top, 8)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.horizontal, 28)
        .padding(.vertical, 26)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.white.opacity(0.05), in: RoundedRectangle(cornerRadius: 20))
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("settings-help")
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

/// A label and its value, not focusable (the board's `.row.fact`).
struct FactRow: View {
    let label: String
    let value: String

    var body: some View {
        HStack(spacing: 14) {
            Text(label).font(.system(size: 18, weight: .semibold)).foregroundStyle(Palette.text)
            Spacer(minLength: 12)
            Text(value).font(.system(size: 18)).foregroundStyle(Palette.textSecondary).lineLimit(1).minimumScaleFactor(0.8)
        }
        .padding(.horizontal, 18)
        .frame(minHeight: 50)
        .background(.white.opacity(0.03), in: RoundedRectangle(cornerRadius: 14))
        .accessibilityElement(children: .combine)
    }
}

/// A line under a group, muted.
struct GroupFoot: View {
    let text: String

    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text)
            .font(.system(size: 16))
            .foregroundStyle(Palette.faint)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 18)
    }
}

/// A failure that belongs to a page or a task rather than one row (the
/// board's `.msg`): what went wrong in bold, then what it means.
struct TVMessage: View {
    let title: String
    var message: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(.system(size: 22, weight: .semibold)).foregroundStyle(Palette.text)
            if let message {
                Text(message).font(.system(size: 20)).lineSpacing(4).foregroundStyle(Palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(.horizontal, 22)
        .padding(.vertical, 18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Palette.warning.opacity(0.10), in: RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(Palette.warning.opacity(0.45)))
        .accessibilityElement(children: .combine)
    }
}

// MARK: Full-screen tasks

/// A task that takes the screen (Name this Apple TV, Add a language, Switch
/// server, a code to scan): where it is in Settings, its title and lede,
/// its content, and what the remote does along the bottom.
struct TVTaskFrame<Content: View>: View {
    let crumb: String
    let title: String
    var lede: String?
    var hint: String?
    @ViewBuilder var content: () -> Content

    var body: some View {
        ZStack(alignment: .topLeading) {
            VStack(alignment: .leading, spacing: 22) {
                Text(crumb).font(.system(size: 19, weight: .semibold)).foregroundStyle(Palette.muted)
                Text(title)
                    .font(.system(size: 56, weight: .heavy))
                    .tracking(-1.1)
                    .foregroundStyle(Palette.text)
                    .accessibilityAddTraits(.isHeader)
                if let lede {
                    Text(lede)
                        .font(.system(size: 23))
                        .lineSpacing(6)
                        .foregroundStyle(Palette.muted)
                        .frame(maxWidth: 1200, alignment: .leading)
                        .fixedSize(horizontal: false, vertical: true)
                }
                content()
            }
            .padding(.horizontal, TVLayout.gutter)
            .padding(.top, 96)
            if let hint {
                Text(hint)
                    .font(.system(size: 18))
                    .foregroundStyle(Palette.faint)
                    .padding(.leading, TVLayout.gutter)
                    .padding(.bottom, 64)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomLeading)
                    .accessibilityIdentifier("task-hint")
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(Palette.ground)
        .ignoresSafeArea()
    }
}

/// A choice in a task's grid (the board's `.chip`): a room name, a
/// language. One already chosen says so in small type at its end.
struct TVGridChipStyle: ButtonStyle {
    var dimmed = false

    func makeBody(configuration: Configuration) -> some View {
        Styled(configuration: configuration, dimmed: dimmed)
    }

    private struct Styled: View {
        let configuration: Configuration
        let dimmed: Bool
        @Environment(\.isFocused) private var focused

        var body: some View {
            configuration.label
                .font(.system(size: 22, weight: .semibold))
                .lineLimit(1)
                .foregroundStyle(focused ? Palette.ground : dimmed ? Palette.faint : Palette.text)
                .padding(.horizontal, 26)
                .frame(maxWidth: .infinity, minHeight: 76, alignment: .leading)
                .background(focused ? Palette.text : .white.opacity(0.07), in: RoundedRectangle(cornerRadius: 16))
                .focusRim(focused, cornerRadius: 16, scale: 1.04)
                .scaleEffect(configuration.isPressed ? 0.97 : 1)
        }
    }
}

/// A grid chip's label: the name, and at its end "Now" or "Added".
struct ChipLabel: View {
    let title: String
    var mark: String?
    @Environment(\.isFocused) private var focused

    var body: some View {
        HStack(spacing: 12) {
            Text(title).lineLimit(1).minimumScaleFactor(0.8)
            Spacer(minLength: 0)
            if let mark {
                Text(mark).font(.system(size: 15, weight: .semibold)).foregroundStyle(Palette.faint)
            }
        }
    }
}

/// A flow's steps named across the top (the board's `.stp`): done ones
/// ticked, the current one gold.
struct TVStepsBar: View {
    let steps: [String]
    /// The step on screen; `steps.count` when all are done.
    let current: Int

    var body: some View {
        HStack(spacing: 14) {
            ForEach(Array(steps.enumerated()), id: \.offset) { index, name in
                if index > 0 {
                    Rectangle().fill(Palette.outlineStrong).frame(width: 60, height: 2)
                }
                let done = index < current || current >= steps.count
                let now = index == current
                HStack(spacing: 10) {
                    Text(done ? "✓" : "\(index + 1)")
                        .font(.system(size: 16, weight: .bold))
                        .foregroundStyle(now ? Palette.ground : done ? Palette.gold : Palette.textSecondary)
                        .frame(width: 34, height: 34)
                        .background(now ? Palette.gold : done ? Palette.gold.opacity(0.18) : Color(hex: 0x2A2A2E), in: Circle())
                    Text(name)
                        .font(.system(size: 19, weight: .semibold))
                        .foregroundStyle(now ? Palette.text : done ? Palette.textSecondary : Palette.faint)
                }
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(current < steps.count ? "Step \(current + 1) of \(steps.count), \(steps[current])" : "All \(steps.count) steps done")
        .accessibilityIdentifier("task-steps")
    }
}

// MARK: Questions

/// A question before something that signs out (TvSettings2SignOut.dc.html):
/// its title and what happens, then the button that keeps things as they
/// are (which has focus) above the one that acts.
struct TVQuestion: Identifiable {
    let id = UUID()
    let title: String
    let paragraphs: [String]
    /// "Stay signed in".
    let keep: String
    /// "Sign out of this Apple TV".
    let act: String
    let action: () async -> Void

    init(title: String, paragraphs: [String], keep: String, act: String, action: @escaping () async -> Void) {
        self.title = title
        self.paragraphs = paragraphs
        self.keep = keep
        self.act = act
        self.action = action
    }

    /// The Kit's words for a question: its title, lines, the keeping button and the acting one.
    init(_ words: ConfirmWords, action: @escaping () async -> Void) {
        self.init(title: words.title, paragraphs: words.lines, keep: words.keep, act: words.confirm, action: action)
    }
}

/// The question over the dimmed screen. Menu is the keeping button; focus
/// is put on it when the card opens, so a stray press changes nothing.
struct TVQuestionCard: View {
    let question: TVQuestion
    let onKeep: () -> Void
    let onAct: () -> Void
    @FocusState private var keepFocused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(question.title)
                .font(.system(size: 36, weight: .bold))
                .tracking(-0.36)
                .foregroundStyle(Palette.text)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityAddTraits(.isHeader)
            ForEach(Array(question.paragraphs.enumerated()), id: \.offset) { _, paragraph in
                Text(paragraph)
                    .font(.system(size: 22))
                    .lineSpacing(6)
                    .foregroundStyle(Palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            VStack(spacing: 12) {
                Button(action: onKeep) { Text(question.keep).frame(maxWidth: .infinity) }
                    .buttonStyle(TVButtonStyle(height: 64, fontSize: 22))
                    .focused($keepFocused)
                    .accessibilityIdentifier("question-keep")
                Button(action: onAct) { Text(question.act).frame(maxWidth: .infinity) }
                    .buttonStyle(TVButtonStyle(kind: .redText, height: 64, fontSize: 22))
                    .accessibilityIdentifier("question-act")
            }
            .padding(.top, 14)
            .focusSection()
            .defaultFocus($keepFocused, true)
        }
        .padding(.horizontal, 48)
        .padding(.vertical, 44)
        .frame(width: 820, alignment: .leading)
        .background(Color(hex: 0x242428).opacity(0.98), in: RoundedRectangle(cornerRadius: 32))
        .shadow(color: .black.opacity(0.7), radius: 50, y: 40)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.black.opacity(0.66))
        .ignoresSafeArea()
        .onExitCommand(perform: onKeep)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("question")
        .task {
            // The keeping button holds the focus when the card opens.
            for _ in 0..<5 where !keepFocused {
                keepFocused = true
                try? await Task.sleep(for: .milliseconds(120))
            }
        }
    }
}

extension View {
    /// Shows `question` over the screen; the keeping button and Menu close
    /// it, the other button closes it and acts.
    func question(_ question: Binding<TVQuestion?>) -> some View {
        fullScreenCover(item: question) { asked in
            TVQuestionCard(question: asked, onKeep: { question.wrappedValue = nil }, onAct: {
                question.wrappedValue = nil
                Task { await asked.action() }
            })
        }
    }
}
