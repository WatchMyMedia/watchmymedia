import SwiftUI
import WMMCast
import WMMFeatures
import WMMModel
import WMMUI

// The film player's pieces on the iPhone Duo (PLAN-IPHONE-DUO.md §5): the
// panel beside the picture on the inner screen held wide (`DuoOpenPlayer`)
// and the controls on the half lying flat (`DuoTabletopPlayer`). Where they
// go is ``PlayerArrangement``'s; which panel or tab shows is
// ``PlayerPanelChoice``'s, one choice for both.

/// The panel beside the picture: the season's episodes, or the audio,
/// subtitles and quality scrolled to the one chosen. The video keeps playing
/// in what is left of the screen, nothing laid over it.
struct PlayerSidePanelView: View {
    let model: PlayerChromeModel
    let episodes: PlayerEpisodesModel
    let panel: PlayerSidePanel
    /// The panel's own share of the safe area.
    let safe: EdgeInsets
    let onClose: () -> Void

    private var playingEpisode: EpisodeDetail.ID? {
        episodes.season?.episodes.first { $0.versions.contains { $0.fileID == model.request.fileID } }?.id
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text(panel.title)
                    .font(.system(size: 19, weight: .bold))
                    .foregroundStyle(Palette.text)
                    .accessibilityAddTraits(.isHeader)
                Spacer(minLength: 8)
                FoldRoundButton(systemImage: "xmark", label: "Close the panel", action: onClose)
            }
            switch panel {
            case .episodes:
                // The one playing in view, as the season loads and as the next one starts.
                ScrollViewReader { proxy in
                    ScrollView { PlayerEpisodeRows(model: model, episodes: episodes, compact: true, dense: true) }
                        .scrollIndicators(.hidden)
                        .onChange(of: playingEpisode, initial: true) { _, id in
                            if let id { proxy.scrollTo(id, anchor: .center) }
                        }
                }
            case .options(let tab):
                ScrollViewReader { proxy in
                    ScrollView {
                        VStack(alignment: .leading, spacing: 18) {
                            ForEach(model.pickerTabs, id: \.self) { section in
                                PlayerOptionSections(model: model, tab: section, large: true).id(section)
                            }
                        }
                    }
                    .scrollIndicators(.hidden)
                    .onChange(of: tab, initial: true) { _, tab in proxy.scrollTo(tab, anchor: .top) }
                }
            }
        }
        .padding(.top, max(safe.top, 20))
        .padding(.bottom, max(safe.bottom, 16))
        .padding(.leading, 16)
        .padding(.trailing, 16 + safe.trailing)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(Color(hex: 0x121214))
        .overlay(alignment: .leading) { Rectangle().fill(Palette.separator).frame(width: 1) }
        .accessibilityElement(children: .contain)
        .accessibilityLabel(panel.title)
        .accessibilityAction(.escape, onClose)
    }
}

/// The half lying flat while the picture stands on the other: Close, the
/// title, Cast and Use the whole screen; the scrubber with the intro and
/// credits marks; back 10 s, play and forward 10 s; and Up next, Audio &
/// subtitles and Quality as tabs, shown in place rather than in a sheet.
struct TabletopPlayerControls: View {
    let model: PlayerChromeModel
    let episodes: PlayerEpisodesModel
    @Binding var panels: PlayerPanelChoice
    /// The half's own share of the safe area.
    let safe: EdgeInsets
    let onWholeScreen: () -> Void
    @Environment(CastService.self) private var cast

    var body: some View {
        let state = model.state
        let hasEpisodes = episodes.season != nil || model.request.showID != nil
        let tabs = PlayerPanelChoice.tabs(episodes: hasEpisodes, offline: state.isOffline)
        let tab = panels.tab(episodes: hasEpisodes, offline: state.isOffline)
        VStack(spacing: 12) {
            header(state)
            scrubber(state)
            transport(state)
            TabletopTabs(tabs: tabs, selected: tab, title: \.title) { panels.choose($0) }
            ScrollView { tabContent(tab) }
                .scrollIndicators(.hidden)
                .frame(maxHeight: .infinity, alignment: .top)
        }
        .padding(.top, 16 + safe.top)
        .padding(.bottom, max(18, safe.bottom))
        .padding(.leading, max(20, safe.leading))
        .padding(.trailing, max(20, safe.trailing))
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(Palette.ground)
        .foregroundStyle(Palette.text)
    }

    private func header(_ state: PlayerState) -> some View {
        let title = tabletopTitle(title: state.title.isEmpty ? model.request.title : state.title, subtitle: state.subtitle)
        return HStack(spacing: 12) {
            FoldRoundButton(systemImage: "chevron.down", label: "Close", action: { model.close() })
            VStack(alignment: .leading, spacing: 2) {
                if let kicker = title.kicker {
                    Text(kicker.uppercased())
                        .font(.system(size: 12, weight: .bold))
                        .tracking(0.96)
                        .foregroundStyle(Palette.gold)
                        .lineLimit(1)
                }
                HStack(spacing: 8) {
                    Text(title.name).font(.system(size: 18, weight: .heavy)).lineLimit(1)
                    if let chip = autoChip(state) { AutoChip(text: chip, size: 12).fixedSize() }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(.isHeader)
            if cast.available && !state.isOffline {
                CastHandoverButton(model: model)
                    .frame(width: 44, height: 44)
                    .background(FoldRoundButton.fill, in: Circle())
            }
            FoldRoundButton(systemImage: "arrow.up.left.and.arrow.down.right", label: "Use the whole screen", action: onWholeScreen)
                .accessibilityIdentifier("player-whole-screen")
        }
    }

    private func scrubber(_ state: PlayerState) -> some View {
        HStack(spacing: 12) {
            PlayerTime(model: model, remaining: false)
                .font(PlayerStyle.time)
                .frame(minWidth: 40, alignment: .leading)
                .accessibilityHidden(true)
            PlayerScrubber(model: model)
            if state.growing != nil { GoToNowButton(model: model) }
            PlayerTime(model: model, remaining: true)
                .font(PlayerStyle.time)
                .frame(minWidth: 48, alignment: .trailing)
                .accessibilityHidden(true)
        }
    }

    private func transport(_ state: PlayerState) -> some View {
        let playing = state.status == .playing || state.status == .buffering || state.status == .loading
        return HStack(spacing: 44) {
            RoundIconButton(systemImage: "gobackward.10", label: "Back 10 seconds", size: 56, iconSize: 30, weight: .medium, background: .clear) {
                model.seek(byMs: -DoubleTapSeek.stepMs)
            }
            ZStack {
                if model.isWaiting { SpinnerRing(size: 78, lineWidth: 3) }
                Button { model.togglePlayPause() } label: {
                    Image(systemName: playing ? "pause.fill" : "play.fill")
                        .font(.system(size: 28, weight: .semibold))
                        .foregroundStyle(Palette.ground)
                        .frame(width: 68, height: 68)
                        .background(Palette.text, in: Circle())
                        .contentShape(Circle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(playing ? "Pause" : "Play")
                .accessibilityIdentifier("player-play-pause")
            }
            RoundIconButton(systemImage: "goforward.10", label: "Forward 10 seconds", size: 56, iconSize: 30, weight: .medium, background: .clear) {
                model.seek(byMs: DoubleTapSeek.stepMs)
            }
        }
    }

    @ViewBuilder
    private func tabContent(_ tab: TabletopTab) -> some View {
        switch tab {
        case .upNext:
            PlayerEpisodeRows(model: model, episodes: episodes, compact: true, upNext: true, dense: true)
        case .tracks:
            HStack(alignment: .top, spacing: 14) {
                PlayerOptionSections(model: model, tab: .audio, large: true)
                PlayerOptionSections(model: model, tab: .subtitles, large: true)
            }
        case .quality:
            PlayerOptionSections(model: model, tab: .quality, large: true, heading: false)
        }
    }
}

/// Tabs in a dark track, the chosen one raised (`DuoTabletopPlayer`,
/// `DuoTabletopLive`): what shows under the controls on the half lying flat.
struct TabletopTabs<Tab: Hashable>: View {
    let tabs: [Tab]
    let selected: Tab
    let title: (Tab) -> String
    let choose: (Tab) -> Void

    var body: some View {
        HStack(spacing: 0) {
            ForEach(tabs, id: \.self) { tab in
                let on = tab == selected
                Button { choose(tab) } label: {
                    Text(title(tab))
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(on ? Palette.text : Palette.muted)
                        .lineLimit(1)
                        .frame(maxWidth: .infinity, minHeight: 38)
                        .background(on ? Palette.outline : .clear, in: RoundedRectangle(cornerRadius: 9))
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(on ? [.isSelected, .isButton] : .isButton)
            }
        }
        .padding(3)
        .frame(minHeight: 44)
        .background(Palette.surfaceRaised, in: RoundedRectangle(cornerRadius: 12))
    }
}

/// A round 44 pt button on the dark halves and pages of the folded layouts.
struct FoldRoundButton: View {
    static let fill = Color(hex: 0x1F1F23)
    let systemImage: String
    let label: String
    var tint = Palette.text
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(tint)
                .frame(width: 44, height: 44)
                .background(Self.fill, in: Circle())
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }
}
