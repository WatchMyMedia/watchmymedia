import SwiftUI
import UIKit
import WMMFeatures
import WMMModel
import WMMUI

/// A movie (TvMovie.dc.html): the poster, facts and tags; Resume (with how
/// far it was watched) or Play, Play from start, Trailer (TvMovieTrailer.dc.html)
/// and Mark watched; each version with how it would play on this Apple TV
/// and Play this; the tracks of the version in focus, and the cast.
struct MovieScreen: View {
    let id: Int
    let tab: AppTab
    @Environment(AppServices.self) private var services
    @Environment(Navigator.self) private var navigator

    var body: some View {
        MovieView(
            model: MovieModel(library: services.library, playback: services.playback, navigator: navigator, movieID: id, device: "Apple TV"),
            tab: tab
        )
    }
}

private struct MovieView: View {
    @State var model: MovieModel
    let tab: AppTab
    @FocusState private var focusedVersion: Int?
    @FocusState private var trailerFocused: Bool
    /// The YouTube app takes `youtube://` links (`youtube` is in LSApplicationQueriesSchemes).
    @State private var youTubeApp = false
    @State private var report: ReportProblemModel?
    @Environment(AppServices.self) private var services

    var body: some View {
        TVLoadable(model.state, retry: model.retry) { movie in page(movie) }
            .task { await model.run() }
            .messagePill($model.message)
            .fullScreenCover(item: $report) { report in
                ReportProblemDialog(model: report) { self.report = nil }
            }
            // A YouTube trailer without the YouTube app: a code to scan (TvTrailerQR.dc.html).
            .fullScreenCover(item: $model.trailerCode) { trailer in
                TrailerCodePanel(trailer: trailer) { model.trailerCode = nil }
            }
            .task(id: model.trailer?.youTube?.key) {
                youTubeApp = model.trailer?.youTube.map { UIApplication.shared.canOpenURL($0.appURL) } ?? false
            }
            .onChange(of: focusedVersion) { _, id in
                // The tracks follow the version in focus.
                if let id, let version = model.movie?.versions.first(where: { $0.id == id }) { model.select(version) }
            }
    }

    private func url(_ path: String?, _ size: ImageSize) -> URL? {
        services.library.imageURL(path, size: size)
    }

    private func page(_ movie: MovieDetail) -> some View {
        TabPage {
            VStack(alignment: .leading, spacing: 40) {
                header(movie)
                HStack(alignment: .top, spacing: 70) {
                    if !movie.versions.isEmpty {
                        versions(movie).frame(width: 1080, alignment: .leading)
                    }
                    VStack(alignment: .leading, spacing: 14) {
                        tracks
                        if !movie.cast.isEmpty {
                            Text("Cast").font(.system(size: 24, weight: .semibold)).foregroundStyle(Palette.text).padding(.top, 14)
                            CastRow(cast: movie.cast)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .padding(.horizontal, TVLayout.gutter)
            }
            .padding(.bottom, 60)
            .background(alignment: .top) {
                DetailBackdrop(url: url(movie.backdropPath, .w1280), seed: movie.title)
                    .padding(.top, -150)
            }
        }
    }

    private func header(_ movie: MovieDetail) -> some View {
        HStack(alignment: .top, spacing: 40) {
            DetailPoster(url: url(movie.posterPath, .w500), seed: movie.title)
            VStack(alignment: .leading, spacing: 16) {
                Text(breadcrumb(tab: tab, title: movie.title))
                    .font(.system(size: 18))
                    .foregroundStyle(Palette.muted)
                Text(movie.title)
                    .font(.system(size: 60, weight: .heavy))
                    .tracking(-1.5)
                    .foregroundStyle(Palette.text)
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
                    .accessibilityAddTraits(.isHeader)
                FactsLine(parts: movie.facts.components(separatedBy: " · "), tags: movie.tags)
                if let recording = model.selectedVersion?.recording ?? movie.versions.first?.recording {
                    Label(recording.line(), systemImage: "record.circle").font(.system(size: 19)).foregroundStyle(Palette.liveText)
                }
                if !movie.overview.isEmpty {
                    Text(movie.overview)
                        .font(.system(size: 20))
                        .lineSpacing(6)
                        .foregroundStyle(Palette.textSecondary)
                        .lineLimit(2)
                        .frame(maxWidth: 1000, alignment: .leading)
                }
                HStack(spacing: 18) {
                    if !movie.versions.isEmpty {
                        Button { model.play() } label: { Label(model.playLabel, systemImage: "play.fill") }
                            .buttonStyle(TVButtonStyle(kind: .primary, height: 62, fontSize: 22))
                        if model.resumable {
                            Button("Play from start") { model.play(fromStart: true) }
                                .buttonStyle(TVButtonStyle(height: 62, fontSize: 22))
                        }
                    }
                    if model.trailer != nil {
                        Button { model.playTrailer(openApp: openYouTubeApp) } label: { Text("\(Image(systemName: "film")) Trailer") }
                            .buttonStyle(TVButtonStyle(height: 62, fontSize: 22))
                            .focused($trailerFocused)
                            .accessibilityLabel("Trailer")
                            .accessibilityIdentifier("movie-trailer")
                            .accessibilityHint(trailerHint ?? "")
                    }
                    Button(model.watchToggleLabel) { Task { await model.setWatched(!model.watched) } }
                        .buttonStyle(TVButtonStyle(height: 62, fontSize: 22))
                        .disabled(model.busy)
                    // From API level 5 (PLAN-ARR.md §9).
                    if services.requests.options.problems {
                        Button(reportProblemTitle) { report = ReportProblemModel(requests: services.requests, target: .movie(movie)) }
                            .buttonStyle(TVButtonStyle(height: 62, fontSize: 22))
                    }
                    // 160 pt beside Trailer (TvMovieTrailer.dc.html), and the first to give way
                    // when a long Resume label needs the room, so no button is cut short.
                    if model.resumable, let watch = movie.watch {
                        ProgressStrip(progress: watch.progress, height: 6)
                            .frame(minWidth: 60, maxWidth: 160)
                            .clipShape(Capsule())
                            .padding(.leading, 8)
                            .layoutPriority(-1)
                    }
                }
                .padding(.top, 6)
                .focusSection()
                // Where Trailer goes, while it has the focus; the line keeps its place so the page doesn't move.
                if let hint = trailerHint {
                    Text("\(Image(systemName: youTubeApp ? "arrow.up.right" : "qrcode")) \(hint)")
                        .font(.system(size: 19))
                        .foregroundStyle(Palette.muted)
                        .opacity(trailerFocused ? 1 : 0)
                        .accessibilityHidden(!trailerFocused)
                }
            }
        }
        .padding(.horizontal, TVLayout.gutter)
        .padding(.top, 20)
    }

    private var trailerHint: String? { model.trailerHint(youTubeApp: youTubeApp) }

    /// Opens a YouTube trailer in the YouTube app; false without it.
    private func openYouTubeApp(_ url: URL) -> Bool {
        guard UIApplication.shared.canOpenURL(url) else { return false }
        UIApplication.shared.open(url)
        return true
    }

    private func versions(_ movie: MovieDetail) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Versions").font(.system(size: 24, weight: .semibold)).foregroundStyle(Palette.text)
            ForEach(movie.versions) { version in
                HStack(spacing: 24) {
                    VStack(alignment: .leading, spacing: 8) {
                        HStack(spacing: 12) {
                            Text(version.title).font(.system(size: 22, weight: .bold)).foregroundStyle(Palette.text)
                            if model.isDefault(version) { TVTag("Default", gold: true) }
                        }
                        HStack(spacing: 8) { ForEach(version.tags, id: \.self) { TVTag($0) } }
                        Text(model.planLine(for: version))
                            .font(.system(size: 16))
                            .foregroundStyle(Palette.faint)
                            .lineLimit(1)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    Button { model.play(version) } label: { Label("Play this", systemImage: "play.fill") }
                        .buttonStyle(TVButtonStyle(height: 52, fontSize: 19))
                        .focused($focusedVersion, equals: version.id)
                        .accessibilityLabel("Play \(version.title)")
                }
                .padding(.vertical, 18)
                .padding(.horizontal, 24)
                .background(.white.opacity(0.06), in: RoundedRectangle(cornerRadius: 18))
                .overlay {
                    if movie.versions.count > 1 && model.selectedVersion?.id == version.id {
                        RoundedRectangle(cornerRadius: 18).strokeBorder(Palette.gold.opacity(0.6))
                    }
                }
            }
        }
        .focusSection()
    }

    @ViewBuilder private var tracks: some View {
        if let version = model.selectedVersion {
            let lines = version.trackLines()
            if !lines.isEmpty {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text("Tracks").font(.system(size: 24, weight: .semibold)).foregroundStyle(Palette.text)
                    Text("· \(version.title)").font(.system(size: 17, weight: .medium)).foregroundStyle(Palette.muted)
                }
                VStack(alignment: .leading, spacing: 0) {
                    ForEach(Array(lines.enumerated()), id: \.offset) { index, line in
                        HStack(spacing: 14) {
                            Text(line.kind?.uppercased() ?? "")
                                .font(.system(size: 16))
                                .tracking(1)
                                .foregroundStyle(Palette.muted)
                                .frame(width: 110, alignment: .leading)
                            Text(line.text).font(.system(size: 19)).foregroundStyle(Palette.text).lineLimit(1)
                            Spacer(minLength: 8)
                            if line.isDefault { TVTag("Default", gold: true) }
                            if line.burnIn {
                                Text("Burn-in required").font(.system(size: 15, weight: .semibold)).foregroundStyle(Palette.warning)
                            }
                        }
                        .padding(.vertical, 10)
                        .overlay(alignment: .bottom) {
                            if index < lines.count - 1 { Rectangle().fill(Palette.separator).frame(height: 1) }
                        }
                        .accessibilityElement(children: .combine)
                    }
                }
            }
        }
    }
}
