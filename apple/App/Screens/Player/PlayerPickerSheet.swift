import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// Tablet playback options stack in a trailing panel while the video plays.
/// Compact widths retain the pausing bottom sheet. Both reuse the same choices.
struct PlayerPickerSheet: View {
    let model: PlayerChromeModel
    let tab: PickerTab
    let insets: PlayerInsets
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
                                    sectionContent(section, model.state, large: true)
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
                .padding(.horizontal, 24)
                .padding(.top, max(geo.safeAreaInsets.top, 20))
                .padding(.bottom, max(geo.safeAreaInsets.bottom, 20))
                .frame(width: min(420, max(geo.size.width - 32, 0)), height: geo.size.height)
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
                    .contentShape(Rectangle())
                    .onTapGesture { model.closePicker() }
                    .accessibilityHidden(true)
                VStack {
                    Text(playerTitleLine(title: state.title, subtitle: state.subtitle, paused: state.status == .paused))
                        .font(PlayerStyle.title)
                        .foregroundStyle(PlayerStyle.light)
                        .lineLimit(1)
                        .padding(.top, max(insets.top - 4, 12))
                        .padding(.horizontal, insets.leading)
                        .opacity(0.7)
                        .allowsHitTesting(false)
                    Spacer()
                }
                sheet(state, width: geo.size.width - insets.leading - insets.trailing)
                    .frame(height: height)
                    .offset(y: max(drag, 0))
            }
        }
        .ignoresSafeArea()
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
        .padding(.horizontal, insets.leading)
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

    private func column(_ tab: PickerTab, _ state: PlayerState, large: Bool = false) -> some View {
        ScrollView { sectionContent(tab, state, large: large) }
    }

    private func sectionContent(_ tab: PickerTab, _ state: PlayerState, large: Bool) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(tab.title.uppercased())
                .font(.system(size: large ? 12 : 11, weight: .semibold))
                .tracking(large ? 0.72 : 0.66)
                .foregroundStyle(Palette.muted)
                .padding(.horizontal, large ? 12 : 0)
                .padding(.bottom, large ? 6 : 4)
                .accessibilityAddTraits(.isHeader)
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
