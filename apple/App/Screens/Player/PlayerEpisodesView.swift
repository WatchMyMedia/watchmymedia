import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// Portrait keeps the video above the title, track controls and the current season.
struct PlayerPortraitDetails: View {
    let model: PlayerChromeModel
    let episodes: PlayerEpisodesModel

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                VStack(alignment: .leading, spacing: 6) {
                    Text(model.state.title).font(.title2.bold()).foregroundStyle(Palette.text)
                    if let subtitle = model.state.subtitle {
                        Text(subtitle).font(.body).foregroundStyle(Palette.muted)
                    }
                    if let line = streamLine(model.state) {
                        Text(line).font(.footnote).foregroundStyle(Palette.muted)
                    }
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
                PlayerEpisodeRows(model: model, episodes: episodes)
            }
            .padding(24)
            .frame(maxWidth: 1000)
            .frame(maxWidth: .infinity, alignment: .top)
        }
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
                .padding(.top, geo.safeAreaInsets.top)
                .frame(width: min(520, geo.size.width - 32), height: geo.size.height)
                .background(Palette.surface)
                .accessibilityAddTraits(.isModal)
            }
        }
        .accessibilityAction(.escape, onClose)
    }
}

private struct PlayerEpisodeRows: View {
    let model: PlayerChromeModel
    let episodes: PlayerEpisodesModel
    var onPlay: () -> Void = {}
    var compact = false
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
                Text("Season \(season.season.number)").font(.title3.bold()).foregroundStyle(Palette.text)
                    .accessibilityAddTraits(.isHeader)
                ForEach(season.episodes) { episode in
                    episodeRow(episode)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func episodeRow(_ episode: EpisodeDetail) -> some View {
        let playing = episode.versions.contains { $0.fileID == model.request.fileID }
        return Button {
            guard !playing, let request = episodes.request(for: episode) else { return }
            model.play(request)
            onPlay()
        } label: {
            HStack(spacing: 14) {
                Artwork(url: services.library.imageURL(episode.stillPath, size: .w300), seed: episode.title)
                    .frame(width: compact ? 112 : 160, height: compact ? 63 : 90)
                    .clipped()
                    .clipShape(RoundedRectangle(cornerRadius: 10))
                    .overlay(alignment: .topTrailing) {
                        if episode.watched { Image(systemName: "checkmark.circle.fill").foregroundStyle(Palette.gold).padding(6) }
                    }
                VStack(alignment: .leading, spacing: 5) {
                    Text("\(episode.number). \(episode.title)").font(.body.weight(.semibold)).foregroundStyle(Palette.text)
                    if let minutes = episode.runtimeMin {
                        Text(formatDuration(ms: minutes * 60_000)).font(.footnote).foregroundStyle(Palette.muted)
                    }
                    if playing { Text("Playing").font(.caption.weight(.semibold)).foregroundStyle(Palette.gold) }
                }
                Spacer(minLength: 0)
                Image(systemName: playing ? "speaker.wave.2.fill" : "play.fill").foregroundStyle(playing ? Palette.gold : Palette.text)
            }
            .padding(10)
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
