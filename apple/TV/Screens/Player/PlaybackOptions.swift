import SwiftUI
import UIKit
import WMMFeatures
import WMMModel
import WMMUI

/// The app's two tabs in the system player's info panel, in the viewer's
/// words. Audio & subtitles is two lists side by side, the chosen one in
/// each ticked in gold; Find more subtitles swaps the right list for what
/// the server's online provider found. Quality is the choice by name, kept
/// for this Apple TV, beside What is playing: a sentence that says how the
/// file reaches the TV, then the picture, the sound, the speed and the
/// server. The file's codecs and the stream's numbers are here and nowhere
/// on the picture. Picking applies at once and the panel stays; focus opens
/// on what is chosen.
struct PlaybackOptions: View {
    /// The panel's height under the system's tabs.
    static let height: CGFloat = 760

    let model: TVPlayerModel
    let tab: TVPlayerTab
    /// The server in use, for What is playing's From line.
    let serverName: String?
    @FocusState private var focus: String?
    /// Find more subtitles is open in place of the subtitle list.
    @State private var findingMore = false

    var body: some View {
        let state = model.state
        HStack(alignment: .top, spacing: 40) {
            switch tab {
            case .tracks:
                column("Audio") {
                    audio(state)
                    if let sound = state.sound { soundRows(sound) }
                }
                column(findingMore ? "Find more subtitles" : "Subtitles") {
                    if findingMore {
                        online(state)
                    } else {
                        subtitles(state)
                        if let timing = state.timing { timingRows(timing) }
                    }
                }
            case .quality:
                column("Quality") { quality(state) }
                WhatIsPlaying(words: TVWhatIsPlaying(state, serverName: serverName))
                    .frame(maxWidth: .infinity, alignment: .topLeading)
            }
        }
        .padding(EdgeInsets(top: 24, leading: 20, bottom: 24, trailing: 20))
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        // tvOS draws nothing behind a custom tab: the panel, over the video.
        .background(Color(hex: 0x1E1E22).opacity(0.94), in: RoundedRectangle(cornerRadius: 32))
        .padding(.horizontal, TVLayout.gutter)
        // tvOS sizes the tab to its content: the lists scroll within this height instead.
        .frame(height: Self.height)
        .defaultFocus($focus, initialFocus(state), priority: .userInitiated)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("player-options-\(tab.rawValue)")
    }

    /// A list with its name, scrolling inside the panel; a focus section, so
    /// Left and Right go to the other list.
    private func column<Rows: View>(_ title: String, @ViewBuilder rows: () -> Rows) -> some View {
        ScrollView(.vertical) {
            VStack(alignment: .leading, spacing: 4) {
                TVGroupLabel(title).padding(.leading, 24).padding(.bottom, 6)
                rows()
            }
            // Room for a focused row to grow and cast its shadow inside the panel.
            .padding(.vertical, 12)
            .padding(.horizontal, 20)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .focusSection()
        .frame(maxWidth: .infinity, alignment: .topLeading)
    }

    /// The chosen row of the tab's first list.
    private func initialFocus(_ state: PlayerState) -> String? {
        switch tab {
        case .tracks: (state.selectedAudio ?? state.audio.first?.index).map { "audio:\($0)" } ?? "subtitle:\(state.selectedSubtitle ?? "off")"
        case .quality: "quality:\(state.quality.rawValue)"
        }
    }

    @ViewBuilder
    private func audio(_ state: PlayerState) -> some View {
        if state.audio.isEmpty {
            Note(text: "This has one sound track.")
        }
        ForEach(state.audio) { a in
            let selected = a.index == state.selectedAudio
            Button { model.pickAudio(a.index) } label: { MenuRow(label: tvAudioRow(a)) }
                .buttonStyle(MenuItemStyle(ticked: selected))
                .focused($focus, equals: "audio:\(a.index)")
                .accessibilityIdentifier("player-audio-\(a.index)")
        }
    }

    @ViewBuilder
    private func subtitles(_ state: PlayerState) -> some View {
        ForEach(tvSubtitleRows(state)) { row in
            switch row.kind {
            case .track(let id):
                Button { model.pickSubtitle(id) } label: { MenuRow(label: row.label) }
                    .buttonStyle(MenuItemStyle(ticked: row.selected))
                    .focused($focus, equals: row.id)
                    .accessibilityIdentifier("player-\(row.id.replacingOccurrences(of: ":", with: "-"))")
            case .findMore:
                Button {
                    findingMore = true
                    focus = "online:back"
                } label: { MenuRow(label: row.label, chevron: true) }
                    .buttonStyle(MenuItemStyle(ticked: nil))
                    .focused($focus, equals: row.id)
                    .accessibilityIdentifier("player-subtitles-find-more")
            }
        }
    }

    /// Sound under the audio tracks: Boost dialogue and Reduce loud sounds,
    /// each its name and three pills (Off, Medium, High), the chosen one in
    /// gold. Select saves it and the play reloads from here, the panel open;
    /// the pill spins until the new sound plays.
    @ViewBuilder
    private func soundRows(_ sound: PlayerSound) -> some View {
        TVGroupLabel(SoundWords.heading).padding(.leading, 24).padding(.top, 20).padding(.bottom, 6)
        ForEach(SoundSetting.allCases, id: \.self) { setting in
            HStack(spacing: 12) {
                Text(setting.title)
                    .font(.tv(.body, .semibold))
                    .foregroundStyle(Palette.text)
                    .lineLimit(1)
                    .frame(maxWidth: .infinity, alignment: .leading)
                ForEach(SoundLevel.allCases, id: \.self) { level in
                    Button { model.setSound(setting, to: level) } label: {
                        SoundPillLabel(level: level, chosen: sound[setting] == level, busy: sound.busy == setting && sound[setting] == level)
                    }
                    .buttonStyle(SoundPillStyle(chosen: sound[setting] == level))
                    .disabled(sound.download)
                    .focused($focus, equals: "sound:\(setting.rawValue):\(level.rawValue)")
                    .accessibilityLabel("\(setting.title), \(level.label)")
                    .accessibilityAddTraits(sound[setting] == level ? .isSelected : [])
                    .accessibilityIdentifier("player-sound-\(setting.rawValue)-\(level.rawValue)")
                }
            }
            .padding(.leading, 24)
            .frame(minHeight: 76)
            // Up and Down reach the row whichever pill is level with the move.
            .focusSection()
            Note(text: sound.line(setting), tone: sound.failed == setting ? .warning : sound.busy == setting ? .strong : .muted)
                .accessibilityIdentifier("player-sound-\(setting.rawValue)-line")
        }
        Note(text: sound.note).padding(.top, 8)
    }

    /// Subtitle timing under the subtitle tracks, while one is on: one row
    /// whose Left is Earlier and Right is Later, a tenth of a second a press,
    /// held to repeat, with the cue under the panel moving as it goes; where
    /// it is saved; and Reset and an admin's Save for everyone, which Select presses.
    @ViewBuilder
    private func timingRows(_ timing: SubtitleTimingState) -> some View {
        TVGroupLabel(TimingWords.title).padding(.leading, 24).padding(.top, 20).padding(.bottom, 6)
        TimingRow(timing: timing) { later in model.moveTiming(later: later) }
        if timing.shared {
            Note(text: TimingWords.setForEveryone, tone: .strong).accessibilityIdentifier("player-timing-shared")
        }
        Note(text: timing.burnedIn && !timing.failed ? TimingWords.burnedIn : timing.line, tone: timing.failed ? .warning : .muted)
            .accessibilityIdentifier("player-timing-line")
        // Reset and Save for everyone go once they would do nothing, but not
        // from under focus: pressed, each stays, dimmed, until focus moves on
        // (a focused button that turns off keeps focus), so focus doesn't
        // jump to the top of the panel.
        let reset = timing.canReset || focus == "timing:reset"
        let everyone = timing.canSaveForEveryone || focus == "timing:everyone"
        if reset || everyone {
            HStack(spacing: 16) {
                if reset {
                    Button(TimingWords.reset) { model.resetTiming() }
                        .buttonStyle(TVButtonStyle(height: 72, fontSize: 29))
                        .disabled(!timing.canReset)
                        .focused($focus, equals: "timing:reset")
                        .accessibilityIdentifier("player-timing-reset")
                }
                if everyone {
                    Button(TimingWords.saveForEveryone) { model.saveTimingForEveryone() }
                        .buttonStyle(TVButtonStyle(height: 72, fontSize: 29))
                        .disabled(!timing.canSaveForEveryone)
                        .focused($focus, equals: "timing:everyone")
                        .accessibilityIdentifier("player-timing-everyone")
                }
            }
            .padding(.top, 12)
            .padding(.leading, 12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .focusSection()
        }
        if timing.canSaveForEveryone {
            Note(text: TimingWords.saveForEveryoneLine)
        }
        if timing.savedForEveryone {
            Text("\(Text("✓").foregroundStyle(Palette.gold).fontWeight(.bold)) \(TimingWords.savedForEveryone)")
                .font(.tv(.caption2, .semibold))
                .foregroundStyle(Palette.textSecondary)
                .padding(.horizontal, 24)
                .padding(.top, 12)
                .accessibilityIdentifier("player-timing-saved-everyone")
        }
    }

    /// What the server's online provider found, its note and its credit,
    /// under a row back to the subtitle list.
    @ViewBuilder
    private func online(_ state: PlayerState) -> some View {
        Button {
            findingMore = false
            focus = "subtitles:more"
        } label: { MenuRow(label: OptionLabel("Subtitles"), back: true) }
            .buttonStyle(MenuItemStyle(ticked: nil))
            .focused($focus, equals: "online:back")
            .accessibilityIdentifier("player-subtitles-back")
        ForEach(Array(tvOnlineSubtitleEntries(state).enumerated()), id: \.offset) { _, entry in
            switch entry {
            case .online(let id, let label, let enabled, let downloading):
                // A result downloading stays bright: it is what is happening.
                Button { if !downloading { model.download(id) } } label: { MenuRow(label: label, downloading: downloading) }
                    .buttonStyle(MenuItemStyle(ticked: nil))
                    .disabled(!enabled && !downloading)
                    .focused($focus, equals: "online:\(id)")
                    .accessibilityLabel(["Download and turn on \(label.label)", label.detail].compactMap { $0 }.joined(separator: ", "))
            case .note(let text):
                Note(text: text)
            case .heading, .track:
                EmptyView()
            }
        }
    }

    @ViewBuilder
    private func quality(_ state: PlayerState) -> some View {
        ForEach(state.qualities, id: \.self) { q in
            Button { model.pickQuality(q) } label: { MenuRow(label: tvQualityRow(q, in: state)) }
                .buttonStyle(MenuItemStyle(ticked: q == state.quality))
                .focused($focus, equals: "quality:\(q.rawValue)")
                .accessibilityIdentifier("player-quality-\(q.rawValue)")
        }
        Note(text: tvQualityKeptNote).padding(.top, 6)
    }
}

/// The Quality tab's right half: a sentence, then the picture, the sound,
/// the speed and the server. Words to read, so nothing in it takes focus.
private struct WhatIsPlaying: View {
    let words: TVWhatIsPlaying

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            TVGroupLabel("What is playing")
            Text(words.sentence)
                .font(.tv(.body))
                .foregroundStyle(Palette.text)
                .fixedSize(horizontal: false, vertical: true)
            Grid(alignment: .leadingFirstTextBaseline, horizontalSpacing: 20, verticalSpacing: 10) {
                ForEach(words.lines, id: \.label) { line in
                    GridRow {
                        Text(line.label).foregroundStyle(Palette.muted)
                        Text(line.value).foregroundStyle(Palette.text).lineLimit(2)
                    }
                }
            }
            .font(.tv(.caption1))
            .padding(.top, 8)
        }
        // Its heading lines up with the list's beside it.
        .padding(.horizontal, 44)
        .padding(.vertical, 12)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("player-what-is-playing")
    }
}

/// A muted line among the rows: the quality note, the provider's credit,
/// a setting's line (brighter while it changes, orange when it couldn't).
private struct Note: View {
    enum Tone { case muted, strong, warning }

    let text: String
    var tone = Tone.muted

    var body: some View {
        Text(text)
            .font(.tv(.caption2))
            .foregroundStyle(tone == .warning ? Palette.warning : tone == .strong ? Palette.textSecondary : Palette.muted)
            .padding(.horizontal, 24)
            .padding(.vertical, 4)
            .fixedSize(horizontal: false, vertical: true)
    }
}

/// A sound level's pill: a tick before the chosen one, a spinner while it is being applied.
private struct SoundPillLabel: View {
    let level: SoundLevel
    let chosen: Bool
    let busy: Bool

    var body: some View {
        HStack(spacing: 10) {
            if busy {
                ProgressView().controlSize(.small).scaleEffect(0.7).frame(width: 22, height: 22)
            } else if chosen {
                // In the words, so the focused button doesn't enlarge an image beside them.
                Text("\(Image(systemName: "checkmark"))").font(.tv(.caption2, .bold))
            }
            Text(level.label)
        }
    }
}

/// A pill beside a sound setting's name, 64 pt high: gold tinted when
/// chosen, white when focused.
private struct SoundPillStyle: ButtonStyle {
    let chosen: Bool

    func makeBody(configuration: Configuration) -> some View {
        Styled(configuration: configuration, chosen: chosen)
    }

    private struct Styled: View {
        let configuration: Configuration
        let chosen: Bool
        @Environment(\.isFocused) private var focused
        @Environment(\.isEnabled) private var enabled

        var body: some View {
            let pressed = configuration.isPressed && focused
            configuration.label
                .font(.tv(.body, .semibold))
                .foregroundStyle(focused ? Palette.ground : chosen ? Palette.gold : Palette.textSecondary)
                .padding(.horizontal, 26)
                .frame(height: 64)
                .background(focused ? (pressed ? Color.tvPressed : Palette.text) : chosen ? Palette.gold.opacity(0.16) : .white.opacity(0.08),
                            in: Capsule())
                .scaleEffect(focused && !pressed ? 1.06 : 1)
                .shadow(color: .black.opacity(focused ? 0.6 : 0), radius: focused ? 20 : 0, y: focused ? 16 : 0)
                .animation(.easeOut(duration: 0.15), value: focused)
                .opacity(enabled ? 1 : 0.45)
        }
    }
}

/// The timing row: Earlier at the left, the value, Later at the right.
/// Left and Right change it rather than move focus (``TimingStepper``);
/// Up and Down leave it.
private struct TimingRow: View {
    let timing: SubtitleTimingState
    let move: (Bool) -> Void
    @State private var focused = false

    var body: some View {
        ZStack {
            TimingStepper(value: timing.value, move: move, focused: $focused)
            HStack(spacing: 16) {
                Text("\(Image(systemName: "chevron.left")) \(TimingWords.earlier)")
                    .font(.tv(.caption1, .semibold))
                    .foregroundStyle(focused ? Color(hex: 0x3A3A3F) : Palette.muted)
                    .frame(width: 170, alignment: .leading)
                Text(timing.value)
                    .font(.tv(.body, .bold).monospacedDigit())
                    .foregroundStyle(focused ? Palette.ground : Palette.text)
                    .contentTransition(.numericText())
                    .frame(maxWidth: .infinity)
                Text("\(TimingWords.later) \(Image(systemName: "chevron.right"))")
                    .font(.tv(.caption1, .semibold))
                    .foregroundStyle(focused ? Color(hex: 0x3A3A3F) : Palette.muted)
                    .frame(width: 170, alignment: .trailing)
            }
            .padding(.horizontal, 28)
            .allowsHitTesting(false)
            .accessibilityHidden(true)
        }
        .frame(minHeight: 76)
        .background(focused ? Palette.text : .white.opacity(0.07), in: RoundedRectangle(cornerRadius: 16))
        .scaleEffect(focused ? 1.03 : 1)
        .shadow(color: .black.opacity(focused ? 0.6 : 0), radius: focused ? 20 : 0, y: focused ? 16 : 0)
        .animation(.easeOut(duration: 0.15), value: focused)
        .padding(.horizontal, 4)
    }
}

/// A row's name, its line at the right, and a chevron for a list inside;
/// a result downloading says so.
private struct MenuRow: View {
    let label: OptionLabel
    var chevron = false
    var back = false
    var downloading = false
    @Environment(\.isFocused) private var focused

    var body: some View {
        HStack(spacing: 16) {
            if back {
                Image(systemName: "chevron.left").font(.tv(.caption2, .bold)).accessibilityHidden(true)
            }
            Text(label.label).lineLimit(1).layoutPriority(1)
            Spacer(minLength: 12)
            if downloading {
                Text("Downloading…").font(.tv(.caption1)).foregroundStyle(focused ? Color.tvOnWhiteMuted : Palette.gold)
            } else if let detail = label.detail {
                Text(detail)
                    .font(.tv(.caption1))
                    .foregroundStyle(focused ? Color.tvOnWhiteMuted : Palette.muted)
                    .lineLimit(1)
                    .truncationMode(.middle)
            }
            if chevron {
                Image(systemName: "chevron.right").font(.tv(.caption2, .bold)).accessibilityHidden(true)
            }
        }
    }
}

/// A row in the panel's lists, 76 pt high: a gold tick before the chosen
/// one (`ticked` nil for a row that isn't a choice), Body words, a line at
/// the right. Focused, it grows a little and turns white, and the tick
/// turns dark.
private struct MenuItemStyle: ButtonStyle {
    let ticked: Bool?

    func makeBody(configuration: Configuration) -> some View {
        Styled(configuration: configuration, ticked: ticked)
    }

    private struct Styled: View {
        let configuration: Configuration
        let ticked: Bool?
        @Environment(\.isFocused) private var focused
        @Environment(\.isEnabled) private var enabled

        var body: some View {
            let pressed = configuration.isPressed && focused
            HStack(spacing: 16) {
                Image(systemName: "checkmark")
                    .font(.tv(.caption1, .bold))
                    .foregroundStyle(focused ? Palette.ground : Palette.gold)
                    .frame(width: 32)
                    .opacity(ticked == true ? 1 : 0)
                    .accessibilityHidden(true)
                configuration.label
            }
            .font(.tv(.body, .semibold))
            .foregroundStyle(focused ? Palette.ground : Palette.text)
            .padding(.horizontal, 24)
            .padding(.vertical, 8)
            .frame(maxWidth: .infinity, minHeight: 76, alignment: .leading)
            .background(focused ? (pressed ? Color.tvPressed : Palette.text) : .clear, in: RoundedRectangle(cornerRadius: 16))
            .scaleEffect(focused && !pressed ? 1.03 : 1)
            .shadow(color: .black.opacity(focused ? 0.6 : 0), radius: focused ? 20 : 0, y: focused ? 16 : 0)
            .animation(.easeOut(duration: 0.15), value: focused)
            .opacity(enabled ? 1 : 0.45)
            .accessibilityAddTraits(ticked == true ? .isSelected : [])
        }
    }
}

/// A focusable row that takes Left and Right itself, for a value changed
/// a step at a time: the focus engine would otherwise move focus to the
/// other column. A press steps once; held, it steps again after half a
/// second and then every tenth of a second. VoiceOver adjusts it as a
/// slider.
private struct TimingStepper: UIViewRepresentable {
    let value: String
    let move: (Bool) -> Void
    @Binding var focused: Bool

    func makeUIView(context: Context) -> StepperView {
        let view = StepperView()
        view.isAccessibilityElement = true
        view.accessibilityLabel = TimingWords.title
        view.accessibilityTraits = .adjustable
        view.accessibilityIdentifier = "player-timing"
        return view
    }

    func updateUIView(_ view: StepperView, context: Context) {
        view.move = move
        view.accessibilityValue = value
        let focused = $focused
        view.focusChanged = { focused.wrappedValue = $0 }
    }

    final class StepperView: UIView {
        var move: ((Bool) -> Void)?
        var focusChanged: ((Bool) -> Void)?
        private var held: UIPress.PressType?
        private var repeating: Timer?

        override var canBecomeFocused: Bool { true }

        override func shouldUpdateFocus(in context: UIFocusUpdateContext) -> Bool {
            if context.previouslyFocusedItem === self, context.focusHeading.contains(.left) || context.focusHeading.contains(.right) {
                return false
            }
            return super.shouldUpdateFocus(in: context)
        }

        override func didUpdateFocus(in context: UIFocusUpdateContext, with coordinator: UIFocusAnimationCoordinator) {
            super.didUpdateFocus(in: context, with: coordinator)
            focusChanged?(isFocused)
            if !isFocused { stop() }
        }

        override func pressesBegan(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
            guard let press = presses.first(where: { $0.type == .leftArrow || $0.type == .rightArrow }) else {
                return super.pressesBegan(presses, with: event)
            }
            let type = press.type
            let later = type == .rightArrow
            move?(later)
            stop()
            held = type
            repeating = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: false) { [weak self] _ in
                MainActor.assumeIsolated {
                    guard let self, self.held == type else { return }
                    self.move?(later)
                    self.repeating = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in
                        MainActor.assumeIsolated { self?.move?(later) }
                    }
                }
            }
        }

        override func pressesEnded(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
            if presses.contains(where: { $0.type == held }) { stop() } else { super.pressesEnded(presses, with: event) }
        }

        override func pressesCancelled(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
            if presses.contains(where: { $0.type == held }) { stop() } else { super.pressesCancelled(presses, with: event) }
        }

        override func accessibilityIncrement() { move?(true) }
        override func accessibilityDecrement() { move?(false) }

        private func stop() {
            held = nil
            repeating?.invalidate()
            repeating = nil
        }
    }
}
