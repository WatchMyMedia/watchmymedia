import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// Tablet playback options stack in a trailing panel while the video plays.
/// Compact widths retain the pausing bottom sheet. Both reuse the same
/// choices (``PlayerOptionSections``), as do the panel beside the picture and
/// the tabs under the fold on the iPhone Duo.
struct PlayerPickerSheet: View {
    let model: PlayerChromeModel
    let tab: PickerTab
    let insets: PlayerInsets
    /// The window's safe area, each edge read on its own: the trailing panel
    /// keeps its content clear of whatever is on its side.
    var safe = EdgeInsets()
    var sidePanel = false
    @State private var drag: CGFloat = 0

    var body: some View {
        if sidePanel { trailingPanel } else { bottomSheet }
    }

    private var trailingPanel: some View {
        GeometryReader { geo in
            ZStack(alignment: .trailing) {
                Color.black.opacity(0.18)
                    .contentShape(Rectangle())
                    .onTapGesture { model.closePicker() }
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 16) {
                    HStack {
                        Text(model.pickerTitle)
                            .font(.headline)
                            .foregroundStyle(Palette.text)
                            .accessibilityAddTraits(.isHeader)
                        Spacer(minLength: 8)
                        Button("Done") { model.closePicker() }
                            .buttonStyle(GoldTextButtonStyle())
                    }
                    ScrollViewReader { proxy in
                        ScrollView {
                            VStack(alignment: .leading, spacing: 24) {
                                ForEach(model.pickerTabs, id: \.self) { section in
                                    PlayerOptionSections(model: model, tab: section, large: true)
                                        .id(section)
                                }
                                Text(pickerFootnote(device: UIDevice.current.model, offline: model.state.isOffline))
                                    .font(.footnote)
                                    .foregroundStyle(Palette.faint)
                            }
                        }
                        .onChange(of: tab, initial: true) { _, selected in
                            proxy.scrollTo(selected, anchor: .top)
                        }
                    }
                }
                // Inside the player's shell the reader sees no safe area: the shell's own, edge by edge.
                .padding(.leading, 24)
                .padding(.trailing, 24 + safe.trailing)
                .padding(.top, max(safe.top, 20))
                .padding(.bottom, max(safe.bottom, 20))
                .frame(width: min(420 + safe.trailing, max(geo.size.width - 32, 0)), height: geo.size.height)
                .background(Palette.surface)
                .overlay(alignment: .leading) { Rectangle().fill(Palette.outline).frame(width: 1) }
                .accessibilityElement(children: .contain)
                .accessibilityLabel("Playback options")
                .accessibilityAddTraits(.isModal)
            }
        }
        .accessibilityAction(.escape) { model.closePicker() }
    }

    private var bottomSheet: some View {
        let state = model.state
        return GeometryReader { geo in
            // As the artboard: the title stays readable above it.
            let height = min(geo.size.height - 47, 560)
            ZStack(alignment: .bottom) {
                Color.black.opacity(0.5)
                    .ignoresSafeArea()
                    .contentShape(Rectangle())
                    .onTapGesture { model.closePicker() }
                    .accessibilityHidden(true)
                VStack {
                    Text(playerTitleLine(title: state.title, subtitle: state.subtitle, paused: state.status == .paused))
                        .font(PlayerStyle.title)
                        .foregroundStyle(PlayerStyle.light)
                        .lineLimit(1)
                        .padding(.top, max(insets.top - 4, 12))
                        .padding(.leading, insets.leading)
                        .padding(.trailing, insets.trailing)
                        .opacity(0.7)
                        .allowsHitTesting(false)
                    Spacer()
                }
                sheet(state, width: geo.size.width - insets.leading - insets.trailing)
                    .frame(height: height)
                    .offset(y: max(drag, 0))
            }
        }
        .accessibilityAction(.escape) { model.closePicker() }
    }

    private func sheet(_ state: PlayerState, width: CGFloat) -> some View {
        VStack(spacing: 8) {
            Capsule().fill(PlayerStyle.control).frame(width: 36, height: 5)
                .frame(maxWidth: .infinity, minHeight: 14)
                .contentShape(Rectangle())
                .gesture(
                    DragGesture()
                        .onChanged { drag = $0.translation.height }
                        .onEnded { value in
                            if value.translation.height > 80 { model.closePicker() }
                            withAnimation(.easeOut(duration: 0.2)) { drag = 0 }
                        }
                )
                .accessibilityLabel("Close")
                .accessibilityAddTraits(.isButton)
                .accessibilityAction { model.closePicker() }
            Picker("Show", selection: Binding(get: { tab }, set: { model.selectTab($0) })) {
                ForEach(model.pickerTabs, id: \.self) { Text($0.title).tag($0) }
            }
            .pickerStyle(.segmented)
            if width >= 600 {
                HStack(alignment: .top, spacing: 20) {
                    ForEach(model.pickerTabs, id: \.self) { t in
                        column(t, state)
                            .opacity(t == tab ? 1 : 0.55)
                            .frame(maxWidth: .infinity)
                    }
                }
            } else {
                column(tab, state)
            }
            Text(pickerFootnote(device: UIDevice.current.model, offline: model.state.isOffline))
                .font(.system(size: 11))
                .foregroundStyle(Palette.faint)
                .multilineTextAlignment(.center)
                .frame(maxWidth: .infinity)
        }
        .padding(.top, 8)
        .padding(.leading, insets.leading)
        .padding(.trailing, insets.trailing)
        .padding(.bottom, max(insets.bottom - 6, 12))
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(Palette.surface, in: UnevenRoundedRectangle(topLeadingRadius: 16, topTrailingRadius: 16))
        .overlay(alignment: .top) {
            UnevenRoundedRectangle(topLeadingRadius: 16, topTrailingRadius: 16)
                .stroke(Palette.outline, lineWidth: 1)
                .mask(alignment: .top) { Rectangle().frame(height: 20) }
        }
        .shadow(color: .black.opacity(0.6), radius: 30, y: -20)
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Playback options")
        .accessibilityAddTraits(.isModal)
    }

    /// A tab's column. The sheet pauses the video, so the subtitles' column
    /// shows the paused frame's cue moving as the timing changes. The chosen
    /// tab's column opens scrolled to its end, where Sound and Subtitle
    /// timing are, under the tracks.
    private func column(_ tab: PickerTab, _ state: PlayerState, large: Bool = false) -> some View {
        let extra = (tab == .audio && state.sound != nil) || (tab == .subtitles && state.timing != nil)
        return ScrollView {
            PlayerOptionSections(model: model, tab: tab, large: large, pausedCue: true)
        }
        .defaultScrollAnchor(extra && tab == self.tab ? .bottom : .top)
    }
}

/// One tab's choices, under its heading: the audio tracks, the subtitles
/// (with OpenSubtitles' results to download), or the qualities. Picking
/// applies at once.
struct PlayerOptionSections: View {
    let model: PlayerChromeModel
    let tab: PickerTab
    var large = false
    /// The heading over the choices; off where a tab or panel names them already.
    var heading = true
    /// Subtitle timing shows the paused frame's cue: the sheet that pauses
    /// covers the picture's own.
    var pausedCue = false

    var body: some View {
        sectionContent(tab, model.state, large: large)
    }

    private func sectionContent(_ tab: PickerTab, _ state: PlayerState, large: Bool) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            if heading {
                Text(tab.title.uppercased())
                    .font(.system(size: large ? 12 : 11, weight: .semibold))
                    .tracking(large ? 0.72 : 0.66)
                    .foregroundStyle(Palette.muted)
                    .padding(.horizontal, large ? 12 : 0)
                    .padding(.bottom, large ? 6 : 4)
                    .accessibilityAddTraits(.isHeader)
            }
            switch tab {
            case .audio: audio(state, large: large)
            case .subtitles: subtitles(state, large: large)
            case .quality: quality(state, large: large)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder
    private func audio(_ state: PlayerState, large: Bool) -> some View {
        if state.audio.isEmpty {
            Text("No other audio").font(.system(size: large ? 13 : 12)).foregroundStyle(Palette.muted).padding(10)
        }
        ForEach(state.audio) { a in
            OptionRow(label: audioOptionLabel(a), selected: a.index == state.selectedAudio, large: large) { model.pickAudio(a.index) }
        }
        if let sound = state.sound {
            SoundOptions(sound: sound, large: large) { model.setSound($0, to: $1) }
        }
    }

    @ViewBuilder
    private func subtitles(_ state: PlayerState, large: Bool) -> some View {
        ForEach(Array(subtitleEntries(state).enumerated()), id: \.offset) { _, entry in
            switch entry {
            case .heading(let text):
                Text(text.uppercased())
                    .font(.system(size: large ? 11 : 10, weight: .semibold))
                    .tracking(0.6)
                    .foregroundStyle(Palette.faint)
                    .padding(.horizontal, large ? 12 : 0)
                    .padding(.top, large ? 10 : 6)
                    .padding(.bottom, 2)
                    .accessibilityAddTraits(.isHeader)
            case .track(let id, let label, let selected):
                OptionRow(label: label, selected: selected, large: large) { model.pickSubtitle(id) }
            case .online(let id, let label, let enabled, let downloading):
                OptionRow(label: label, selected: false, trailing: downloading ? .downloading : .download, large: large) { model.download(id) }
                    .disabled(!enabled || downloading)
            case .note(let text):
                Text(text)
                    .font(.system(size: large ? 11 : 10))
                    .foregroundStyle(Palette.faint)
                    .padding(.horizontal, large ? 12 : 10)
                    .padding(.vertical, 2)
            }
        }
        if let timing = state.timing {
            TimingOptions(timing: timing, large: large, pausedCue: pausedCue ? model.clock : nil,
                          move: { model.moveTiming(later: $0) }, reset: { model.resetTiming() }, share: { model.saveTimingForEveryone() })
        }
    }

    @ViewBuilder
    private func quality(_ state: PlayerState, large: Bool) -> some View {
        ForEach(state.qualities, id: \.self) { q in
            OptionRow(label: qualityOptionLabel(q, in: state), selected: q == state.quality, large: large) { model.pickQuality(q) }
        }
        if let reason = streamReason(state) {
            Text(reason)
                .font(.system(size: large ? 11 : 10))
                .foregroundStyle(Palette.faint)
                .padding(.horizontal, large ? 12 : 10)
                .padding(.top, 6)
        }
    }
}

/// A row of the picker: the name and a muted detail (under the name when
/// they do not fit side by side), and the gold check, a download mark, or
/// "Downloading" at the end. 44 pt tall. `large` is the iPad's type.
private struct OptionRow: View {
    enum Trailing { case check, download, downloading }

    let label: OptionLabel
    let selected: Bool
    var trailing: Trailing = .check
    var large = false
    let action: () -> Void
    @Environment(\.isEnabled) private var isEnabled

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 8) {
                        name
                        detail
                    }
                    VStack(alignment: .leading, spacing: 1) {
                        name
                        detail
                    }
                }
                Spacer(minLength: 0)
                mark
            }
            .padding(.horizontal, large ? 12 : 10)
            .padding(.vertical, 4)
            .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
            .background(selected ? (large ? Color(hex: 0x1F1F23) : Palette.surfaceRaised) : .clear, in: RoundedRectangle(cornerRadius: large ? 10 : 8))
            .contentShape(RoundedRectangle(cornerRadius: large ? 10 : 8))
            .opacity(isEnabled || trailing == .downloading ? 1 : 0.45)
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel([trailing == .download ? "Download and turn on \(label.label)" : label.label, label.detail].compactMap { $0 }.joined(separator: ", "))
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    private var name: some View {
        Text(label.label)
            .font(.system(size: large ? 16 : 15, weight: .semibold))
            .foregroundStyle(Palette.text)
            .lineLimit(1)
            .fixedSize()
    }

    @ViewBuilder
    private var detail: some View {
        if let detail = label.detail {
            Text(detail)
                .font(.system(size: large ? 13 : 12))
                .foregroundStyle(trailing == .downloading ? Palette.gold : Palette.muted)
                .lineLimit(1)
        }
    }

    @ViewBuilder
    private var mark: some View {
        switch trailing {
        case .check:
            if selected {
                Image(systemName: "checkmark")
                    .font(.system(size: large ? 12 : 11, weight: .heavy))
                    .foregroundStyle(Palette.ground)
                    .frame(width: large ? 24 : 22, height: large ? 24 : 22)
                    .background(Palette.gold, in: Circle())
            }
        case .download:
            Image(systemName: "arrow.down.to.line")
                .font(.system(size: 11, weight: .bold))
                .foregroundStyle(Palette.text)
                .frame(width: 22, height: 22)
                .background(.white.opacity(0.12), in: Circle())
        case .downloading:
            ProgressView().controlSize(.small).tint(Palette.gold).frame(width: 22, height: 22)
        }
    }
}

/// A small heading inside a tab's list: SOUND, SUBTITLE TIMING.
struct OptionsSubheading: View {
    let text: String
    let large: Bool

    var body: some View {
        Text(text.uppercased())
            .font(.system(size: large ? 11 : 10, weight: .semibold))
            .tracking(0.6)
            .foregroundStyle(Palette.faint)
            .padding(.horizontal, large ? 12 : 0)
            .padding(.top, large ? 14 : 8)
            .padding(.bottom, 2)
            .accessibilityAddTraits(.isHeader)
    }
}

/// Boost dialogue and Reduce loud sounds under the audio tracks: each a
/// choice of Off, Medium and High with its line, and what turning either
/// on does. A choice is saved and the play reloads where it is; the
/// options stay open and the row says it is changing the sound until it
/// plays again. A download keeps its own sound: the choices are off. The
/// Cast remote shows the same, for the TV's play.
struct SoundOptions: View {
    let sound: PlayerSound
    let large: Bool
    let pick: (SoundSetting, SoundLevel) -> Void

    var body: some View {
        OptionsSubheading(text: SoundWords.heading, large: large)
        ForEach(SoundSetting.allCases, id: \.self) { setting in
            VStack(alignment: .leading, spacing: 4) {
                Text(setting.title)
                    .font(.system(size: large ? 15 : 14, weight: .semibold))
                    .foregroundStyle(Palette.text)
                line(setting)
                Picker(setting.title, selection: Binding(get: { sound[setting] }, set: { level in
                    guard sound.enabled else { return }
                    pick(setting, level)
                })) {
                    ForEach(SoundLevel.allCases, id: \.self) { Text($0.label).tag($0) }
                }
                .pickerStyle(.segmented)
                .controlSize(.small)
                .disabled(sound.download)
                .allowsHitTesting(sound.enabled)
                .accessibilityIdentifier("player-sound-\(setting.rawValue)")
            }
            .padding(.horizontal, large ? 12 : 2)
            .padding(.vertical, 6)
            .accessibilityElement(children: .contain)
        }
        Text(sound.note)
            .font(.system(size: large ? 11 : 10))
            .foregroundStyle(Palette.faint)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, large ? 12 : 2)
            .padding(.top, 2)
            .accessibilityIdentifier("player-sound-note")
    }

    @ViewBuilder private func line(_ setting: SoundSetting) -> some View {
        let text = sound.line(setting)
        HStack(spacing: 5) {
            if sound.busy == setting { ProgressView().controlSize(.mini).tint(Palette.gold) }
            Text(text)
                .font(.system(size: large ? 12 : 11))
                .foregroundStyle(sound.failed == setting ? Palette.warning : sound.busy == setting ? Palette.textSecondary : Palette.muted)
                .fixedSize(horizontal: false, vertical: true)
        }
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("player-sound-\(setting.rawValue)-line")
    }
}

/// Subtitle timing under the subtitle tracks, while one is on: the value
/// and Reset, Earlier and Later (held, they repeat), where it is saved,
/// and an admin's Save for everyone. The cues move as it is pressed; where
/// the options pause the video (`pausedCue`, the player's clock), the
/// paused frame's cue shows here, since the sheet covers the picture's.
/// The Cast remote shows the same, for the TV's cues.
struct TimingOptions: View {
    let timing: SubtitleTimingState
    let large: Bool
    let pausedCue: PlayerClock?
    let move: (Bool) -> Void
    let reset: () -> Void
    let share: () -> Void

    var body: some View {
        OptionsSubheading(text: TimingWords.title, large: large)
        VStack(alignment: .leading, spacing: 4) {
            if let pausedCue, !timing.burnedIn {
                PausedCue(clock: pausedCue)
            }
            if timing.burnedIn {
                Text(TimingWords.burnedIn)
                    .font(.system(size: large ? 12 : 11))
                    .foregroundStyle(Palette.muted)
                    .fixedSize(horizontal: false, vertical: true)
            }
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(timing.value)
                    .font(.system(size: large ? 16 : 15, weight: .semibold).monospacedDigit())
                    .foregroundStyle(Palette.text)
                    .contentTransition(.numericText())
                    .accessibilityIdentifier("player-timing-value")
                Spacer(minLength: 8)
                if timing.canReset {
                    Button(TimingWords.reset, action: reset)
                        .buttonStyle(GoldTextButtonStyle(font: .system(size: large ? 15 : 14, weight: .semibold)))
                        .frame(minHeight: 32)
                        .accessibilityIdentifier("player-timing-reset")
                }
            }
            .frame(minHeight: 32)
            if timing.shared {
                Text(TimingWords.setForEveryone)
                    .font(.system(size: large ? 12 : 11))
                    .foregroundStyle(Palette.textSecondary)
                    .accessibilityIdentifier("player-timing-shared")
            }
            HStack(spacing: 2) {
                stepButton(TimingWords.earlier, later: false, enabled: timing.canMoveEarlier)
                stepButton(TimingWords.later, later: true, enabled: timing.canMoveLater)
            }
            .padding(2)
            .background(Palette.outline, in: RoundedRectangle(cornerRadius: 9))
            .padding(.top, 2)
            Text(timing.line)
                .font(.system(size: timing.failed ? (large ? 12 : 11) : (large ? 11 : 10)))
                .foregroundStyle(timing.failed ? Palette.warning : Palette.faint)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("player-timing-line")
            if timing.canSaveForEveryone {
                Button(action: share) {
                    Text(TimingWords.saveForEveryone)
                        .font(.system(size: large ? 14 : 13, weight: .semibold))
                        .foregroundStyle(Palette.text)
                        .frame(maxWidth: .infinity, minHeight: 32)
                        .background(.white.opacity(0.12), in: RoundedRectangle(cornerRadius: 8))
                        .contentShape(RoundedRectangle(cornerRadius: 8))
                }
                .buttonStyle(.plain)
                .disabled(timing.save == .saving)
                .padding(.top, 4)
                .accessibilityIdentifier("player-timing-everyone")
                Text(TimingWords.saveForEveryoneLine)
                    .font(.system(size: large ? 11 : 10))
                    .foregroundStyle(Palette.faint)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if timing.savedForEveryone {
                Text("\(Text("✓").foregroundStyle(Palette.gold).fontWeight(.bold)) \(TimingWords.savedForEveryone)")
                    .font(.system(size: large ? 13 : 12, weight: .semibold))
                    .foregroundStyle(Palette.textSecondary)
                    .padding(.top, 4)
                    .accessibilityIdentifier("player-timing-saved-everyone")
            }
        }
        .padding(.horizontal, large ? 12 : 2)
        .padding(.top, 4)
        .accessibilityElement(children: .contain)
    }

    /// Earlier or Later: one step a press, repeating while held.
    private func stepButton(_ title: String, later: Bool, enabled: Bool) -> some View {
        Button { move(later) } label: {
            Text(title)
                .font(.system(size: large ? 14 : 13, weight: .semibold))
                .foregroundStyle(Palette.text)
                .frame(maxWidth: .infinity, minHeight: large ? 32 : 28)
                .background(Palette.outlineStrong, in: RoundedRectangle(cornerRadius: 7))
                .contentShape(RoundedRectangle(cornerRadius: 7))
        }
        .buttonStyle(.plain)
        .buttonRepeatBehavior(.enabled)
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.45)
        .accessibilityHint(later ? "Shows each subtitle 0.1 seconds later" : "Shows each subtitle 0.1 seconds sooner")
        .accessibilityIdentifier(later ? "player-timing-later" : "player-timing-earlier")
    }
}

/// The paused frame and its cue, in a small picture, while the sheet that
/// pauses the video covers the picture's own: the time, and the cue drawn
/// at the player's place, moving as the timing does.
private struct PausedCue: View {
    let clock: PlayerClock

    var body: some View {
        ZStack(alignment: .bottom) {
            LinearGradient(stops: [.init(color: Color(hex: 0x14262E), location: 0), .init(color: Color(hex: 0x21404D), location: 0.55),
                                   .init(color: Color(hex: 0x0B1418), location: 1)], startPoint: .top, endPoint: .bottom)
            Text(formatClock(ms: clock.positionMs))
                .font(.system(size: 10, weight: .semibold).monospacedDigit())
                .foregroundStyle(.white.opacity(0.7))
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                .padding(.leading, 6)
                .padding(.top, 4)
            if let line = SubtitleCuesView.lines(clock.cues).first {
                Text(line)
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(.white)
                    .lineLimit(1)
                    .padding(.horizontal, 5)
                    .padding(.vertical, 1)
                    .background(.black.opacity(0.6), in: RoundedRectangle(cornerRadius: 2))
                    .padding(.bottom, 6)
                    .transition(.opacity)
            }
        }
        .frame(height: 50)
        .clipShape(RoundedRectangle(cornerRadius: 8))
        .animation(.easeOut(duration: 0.15), value: clock.cues)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(SubtitleCuesView.lines(clock.cues).first.map { "The subtitle at \(formatClock(ms: clock.positionMs)): \($0)" } ?? "No subtitle at \(formatClock(ms: clock.positionMs))")
        .accessibilityIdentifier("player-timing-cue")
    }
}
