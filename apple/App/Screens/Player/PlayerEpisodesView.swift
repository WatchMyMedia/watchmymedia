import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// Portrait keeps the video above the title, track controls and the current
/// season, the episode playing scrolled into view (`DuoTallPlayer`): the show
/// and the episode's code over the episode's name, as under the fold.
struct PlayerPortraitDetails: View {
    let model: PlayerChromeModel
    let episodes: PlayerEpisodesModel

    private var playingEpisode: EpisodeDetail.ID? {
        episodes.season?.episodes.first { $0.versions.contains { $0.fileID == model.request.fileID } }?.id
    }

    var body: some View {
        let state = model.state
        let title = tabletopTitle(title: state.title.isEmpty ? model.request.title : state.title, subtitle: state.subtitle)
        VStack(alignment: .leading, spacing: 20) {
                VStack(alignment: .leading, spacing: 6) {
                    if let kicker = title.kicker {
                        Text(kicker.uppercased())
                            .font(.system(size: 13, weight: .bold))
                            .tracking(1.04)
                            .foregroundStyle(Palette.gold)
                            .lineLimit(1)
                    }
                    Text(title.name).font(.system(size: 28, weight: .heavy)).foregroundStyle(Palette.text)
                        .accessibilityAddTraits(.isHeader)
                    HStack(spacing: 8) {
                        if let chip = autoChip(state) { AutoChip(text: chip, size: 12).fixedSize() }
                        if let line = streamLine(state) { Text(line).lineLimit(1) }
                    }
                    .font(.subheadline)
                    .foregroundStyle(Palette.muted)
                }
                HStack(spacing: 12) {
                    ForEach(PickerTab.allCases, id: \.self) { tab in
                        Button { model.openPicker(tab, pausesPlayback: false) } label: {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(tab.title.uppercased()).font(.caption.weight(.semibold)).foregroundStyle(Palette.muted)
                                Text(value(for: tab)).font(.body.weight(.semibold)).foregroundStyle(Palette.text).lineLimit(1)
                            }
                            .frame(maxWidth: .infinity, minHeight: 52, alignment: .leading)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 6)
                            .background(Palette.surface, in: RoundedRectangle(cornerRadius: 12))
                            .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(Palette.separator))
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(tab.title)
                        .accessibilityValue(value(for: tab))
                    }
                }
                if let season = episodes.season {
                    Text("Season \(season.season.number)").font(.title3.bold()).foregroundStyle(Palette.text)
                        .accessibilityAddTraits(.isHeader)
                        .padding(.bottom, -12)
                }
                // Only the season scrolls, to the one playing as it loads and as the next one starts.
                ScrollViewReader { proxy in
                    ScrollView { PlayerEpisodeRows(model: model, episodes: episodes, heading: false) }
                        .scrollIndicators(.hidden)
                        .onChange(of: playingEpisode, initial: true) { _, id in
                            if let id { proxy.scrollTo(id, anchor: .center) }
                        }
                }
        }
        .padding(.horizontal, 24)
        .padding(.top, 20)
        .frame(maxWidth: 1000, maxHeight: .infinity, alignment: .top)
        .frame(maxWidth: .infinity, alignment: .top)
        .background(Palette.ground)
    }

    private func value(for tab: PickerTab) -> String {
        let state = model.state
        switch tab {
        case .audio: return state.audio.first { $0.index == state.selectedAudio }?.label ?? "Default"
        case .subtitles: return state.subtitles.first { $0.id == state.selectedSubtitle }?.label ?? "Off"
        case .quality: return qualityOptionLabel(state.quality, in: state).label
        }
    }
}

/// The landscape Episodes action uses the same season list without interrupting video.
struct PlayerEpisodesPanel: View {
    let model: PlayerChromeModel
    let episodes: PlayerEpisodesModel
    /// The window's safe area: inside the player's shell the reader sees none,
    /// and the trailing edge may hold the camera (the iPhone Duo's outer screen).
    var safe = EdgeInsets()
    let onClose: () -> Void

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .trailing) {
                Color.black.opacity(0.18).onTapGesture(perform: onClose).accessibilityHidden(true)
                VStack(spacing: 16) {
                    HStack {
                        Text("Episodes").font(.headline).foregroundStyle(Palette.text)
                        Spacer()
                        Button("Done", action: onClose).buttonStyle(GoldTextButtonStyle())
                    }
                    ScrollView { PlayerEpisodeRows(model: model, episodes: episodes, onPlay: onClose, compact: true) }
                }
                .padding(24)
                .padding(.top, safe.top)
                .padding(.trailing, safe.trailing)
                .frame(width: min(520 + safe.trailing, geo.size.width - 32), height: geo.size.height)
                .background(Palette.surface)
                .accessibilityAddTraits(.isModal)
            }
        }
        .accessibilityAction(.escape, onClose)
    }
}

/// The season's episodes, the one playing marked; a tap plays another in
/// place. `upNext` starts at the one playing and leaves out the heading (the
/// tab under the fold on the iPhone Duo names them).
struct PlayerEpisodeRows: View {
    let model: PlayerChromeModel
    let episodes: PlayerEpisodesModel
    var onPlay: () -> Void = {}
    var compact = false
    var upNext = false
    /// "Season 3" over the rows; off where the page or tab names them already.
    var heading = true
    /// The iPhone Duo's panel beside the picture and its tab under the fold:
    /// smaller stills, a line each, and the one playing scrolled into view
    /// (`DuoOpenPlayer`, `DuoTabletopPlayer`).
    var dense = false
    @Environment(AppServices.self) private var services

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if episodes.loading {
                ProgressView("Loading episodes…").tint(Palette.gold)
            } else if let error = episodes.error {
                Text(error).font(.footnote).foregroundStyle(Palette.muted)
                Button("Try again") { Task { await episodes.load(for: model.request) } }
                    .buttonStyle(GoldTextButtonStyle())
            } else if let season = episodes.season {
                if heading && !upNext {
                    Text("Season \(season.season.number)").font(.title3.bold()).foregroundStyle(Palette.text)
                        .accessibilityAddTraits(.isHeader)
                }
                ForEach(shown(season.episodes)) { episode in
                    episodeRow(episode).id(episode.id)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// Up next: the one playing and those after it (all of them if none is playing).
    private func shown(_ all: [EpisodeDetail]) -> [EpisodeDetail] {
        guard upNext, let playing = all.firstIndex(where: { $0.versions.contains { $0.fileID == model.request.fileID } }) else { return all }
        return Array(all[playing...])
    }

    private func episodeRow(_ episode: EpisodeDetail) -> some View {
        let playing = episode.versions.contains { $0.fileID == model.request.fileID }
        return Button {
            guard !playing, let request = episodes.request(for: episode) else { return }
            model.play(request)
            onPlay()
        } label: {
            HStack(spacing: dense ? 12 : 14) {
                Artwork(url: services.library.imageURL(episode.stillPath, size: .w300), seed: episode.title)
                    .frame(width: dense ? 104 : compact ? 112 : 160, height: dense ? 58 : compact ? 63 : 90)
                    .clipped()
                    .clipShape(RoundedRectangle(cornerRadius: dense ? 8 : 10))
                    .overlay(alignment: .topTrailing) {
                        if episode.watched { Image(systemName: "checkmark.circle.fill").foregroundStyle(Palette.gold).padding(dense ? 4 : 6) }
                    }
                VStack(alignment: .leading, spacing: dense ? 3 : 5) {
                    Text("\(episode.number). \(episode.title)")
                        .font(dense ? .system(size: 15, weight: .bold) : .body.weight(.semibold))
                        .foregroundStyle(Palette.text)
                        .lineLimit(dense ? 1 : nil)
                    if let minutes = episode.runtimeMin {
                        Text(formatDuration(ms: minutes * 60_000)).font(dense ? .caption : .footnote).foregroundStyle(Palette.muted)
                    }
                    if playing { Text("Playing").font(.caption.weight(.semibold)).foregroundStyle(Palette.gold) }
                }
                Spacer(minLength: 0)
                Image(systemName: playing ? "speaker.wave.2.fill" : "play.fill").foregroundStyle(playing ? Palette.gold : Palette.text)
            }
            .padding(dense ? 8 : 10)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(playing ? Palette.surfaceRaised : .clear, in: RoundedRectangle(cornerRadius: 14))
            .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(playing ? Palette.gold : .clear, lineWidth: 2))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Episode \(episode.number), \(episode.title)\(playing ? ", playing" : "")")
        .accessibilityAddTraits(playing ? .isSelected : [])
    }
}
