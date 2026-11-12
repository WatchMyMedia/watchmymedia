import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// Home with no server (HomeOffline.dc.html, Pad2HomeOffline.dc.html):
/// You're offline with Try again, then the downloads to watch: those partway
/// through, and everything on this device. A movie opens its page, which
/// opens from the download; a show opens Downloads.
struct HomeOfflineView: View {
    let retry: () async -> Void
    @State private var model: DownloadsModel
    @State private var trying = false
    @Environment(AppServices.self) private var services
    @Environment(\.horizontalSizeClass) private var sizeClass

    init(downloads: DownloadsModel, retry: @escaping () async -> Void) {
        _model = State(initialValue: downloads)
        self.retry = retry
    }

    private var wide: Bool { sizeClass.isWide }
    private var margin: CGFloat { wide ? PadLayout.margin : 16 }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: wide ? 28 : 22) {
                notice
                if model.all.contains(where: \.downloaded) {
                    if !model.partway.isEmpty { continueRow }
                    onDevice
                } else {
                    VStack(spacing: 8) {
                        Text("Nothing is downloaded on this \(model.device)").font(.title3.weight(.bold))
                        Text("Downloads play without a connection. When you’re back online, download a movie or an episode from its page.")
                            .foregroundStyle(Palette.muted)
                    }
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: .infinity)
                    .padding(.horizontal, margin)
                    .padding(.top, 60)
                }
            }
            .padding(.bottom, 24)
        }
        .refreshable { await tryAgain() }
    }

    private var serverName: String { services.servers.current?.server.name ?? "your server" }

    private var notice: some View {
        let lead = model.all.contains(where: \.downloaded)
            ? "Can’t reach \(serverName). Your downloads still play, and what you watch is sent to the server when you’re back online."
            : "Can’t reach \(serverName)."
        return VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: "wifi.slash")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(Palette.textSecondary)
                    .frame(width: 40, height: 40)
                    .background(Palette.outline, in: Circle())
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 4) {
                    Text("You’re offline").font(.headline)
                    Text(lead).font(.subheadline).foregroundStyle(Palette.muted).fixedSize(horizontal: false, vertical: true)
                }
            }
            Button { Task { await tryAgain() } } label: {
                Label(trying ? "Trying again…" : "Try again", systemImage: "arrow.clockwise")
            }
            .buttonStyle(GhostButtonStyle(height: 44, fullWidth: false))
            .disabled(trying)
        }
        .padding(16)
        .background(Palette.surface, in: RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(Palette.separator))
        .accessibilityElement(children: .contain)
        .padding(.horizontal, margin)
    }

    private func tryAgain() async {
        trying = true
        await model.sync()
        await retry()
        trying = false
    }

    private var continueRow: some View {
        VStack(alignment: .leading, spacing: 10) {
            (Text("Continue watching").font(.title3.weight(.bold)) + Text("  Downloaded").font(.footnote).foregroundStyle(Palette.muted))
                .padding(.horizontal, margin)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 12) {
                    ForEach(model.partway) { d in
                        let width: CGFloat = wide ? 330 : 240
                        Button { model.play(d) } label: {
                            VStack(alignment: .leading, spacing: 4) {
                                Artwork(url: services.library.imageURL(d.item.artPath, size: .w780), seed: d.item.showTitle ?? d.item.title)
                                    .frame(width: width, height: width * 9 / 16)
                                    .overlay {
                                        Image(systemName: "play.fill")
                                            .foregroundStyle(Palette.text)
                                            .frame(width: 44, height: 44)
                                            .background(.black.opacity(0.55), in: Circle())
                                    }
                                    .overlay(alignment: .bottom) { ProgressStrip(progress: d.partway?.progress ?? 0) }
                                    .clipShape(RoundedRectangle(cornerRadius: 8))
                                Text(d.item.showTitle ?? d.item.title).font(.subheadline.weight(.semibold)).lineLimit(1)
                                Text(continueLine(d)).font(.caption).foregroundStyle(Palette.muted).lineLimit(1)
                            }
                            .frame(width: width, alignment: .leading)
                        }
                        .buttonStyle(CardButtonStyle())
                    }
                }
                .padding(.horizontal, margin)
            }
        }
    }

    private func continueLine(_ d: OfflineDownload) -> String {
        let left = formatRemaining(ms: d.partway?.remainingMs ?? 0)
        guard d.item.kind == .episode, let s = d.item.season, let e = d.item.episode else { return left }
        return "\(episodeCode(season: s, episode: e)) · \(left)"
    }

    /// Movies, then shows: what this device holds.
    private var onDevice: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline) {
                Text("On this \(model.device)").font(.title3.weight(.bold))
                Spacer()
                Button("See all") { model.openAll() }.buttonStyle(GoldTextButtonStyle(font: .body.weight(.semibold)))
            }
            .padding(.horizontal, margin)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(alignment: .top, spacing: 12) {
                    let width: CGFloat = wide ? 150 : 112
                    ForEach(model.movies.filter(\.downloaded)) { d in
                        tile(title: d.item.title, line: "Movie", poster: d.item.posterPath, width: width) { model.open(d) }
                    }
                    ForEach(model.shows) { group in
                        tile(title: group.title, line: plural(group.episodes.count, "episode"), poster: group.posterPath, width: width) { model.openAll() }
                    }
                }
                .padding(.horizontal, margin)
            }
        }
    }

    private func tile(title: String, line: String, poster: String?, width: CGFloat, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 4) {
                Artwork(url: services.library.imageURL(poster, size: .w342), seed: title)
                    .frame(width: width, height: width * 1.5)
                    .overlay(alignment: .topTrailing) {
                        Image(systemName: "arrow.down")
                            .font(.system(size: 11, weight: .bold))
                            .foregroundStyle(Palette.gold)
                            .frame(width: 22, height: 22)
                            .background(.black.opacity(0.72), in: Circle())
                            .padding(6)
                            .accessibilityLabel("Downloaded")
                    }
                    .clipShape(RoundedRectangle(cornerRadius: 8))
                Text(title).font(.subheadline.weight(.semibold)).lineLimit(1)
                Text(line).font(.caption).foregroundStyle(Palette.muted)
            }
            .frame(width: width, alignment: .leading)
        }
        .buttonStyle(CardButtonStyle())
    }
}
