import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// A movie (Movie.dc.html): backdrop, poster, facts and tags; Resume or
/// Play, From the start, Trailer (MovieTrailer.dc.html) and Mark watched;
/// each version with how it would play here and Play this; the chosen
/// version's tracks; cast and details.
/// On iPad (Pad2Movie.dc.html) the information column scrolls independently
/// from versions, tracks and cast. Portrait stacks the same content.
struct MovieScreen: View {
    let id: Int
    @Environment(AppServices.self) private var services
    @Environment(Navigator.self) private var navigator

    var body: some View {
        // How a version would play is said for this device: "on this iPad", else "on this phone".
        let device = deviceFamily(model: services.client.model) == "iPad" ? "this iPad" : "this phone"
        MovieView(model: MovieModel(library: services.library, playback: services.playback, navigator: navigator, movieID: id, device: device, downloads: services.downloads))
    }
}

private struct MovieView: View {
    @State var model: MovieModel
    @State private var scrolled = false
    @State private var report: ReportProblemModel?
    @Environment(AppServices.self) private var services
    @Environment(\.horizontalSizeClass) private var sizeClass

    private var wide: Bool { sizeClass.isWide }

    var body: some View {
        GeometryReader { geo in
            switch model.state {
            case .loading:
                ProgressView().tint(Palette.muted).frame(maxWidth: .infinity, maxHeight: .infinity)
            case .failed(let message):
                ErrorState(message: message, retry: model.retry)
            case .loaded(let movie):
                page(movie, topInset: geo.safeAreaInsets.top, width: geo.size.width)
                    // Both of iPad's side-by-side columns pull to refresh.
                    .refreshable { await model.refresh() }
            }
        }
        .groundBackground()
        .navigationTitle(scrolled ? (model.movie?.title ?? "") : "")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(scrolled ? .automatic : .hidden, for: .navigationBar)
        .tabBarHiddenOnPhone(sizeClass)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                // Report a problem (ReportProblem.dc.html), from API level 5.
                if let movie = model.movie, services.requests.options.problems {
                    Menu {
                        Button {
                            report = ReportProblemModel(requests: services.requests, target: .movie(movie))
                        } label: {
                            Label(reportProblemTitle, systemImage: "exclamationmark.bubble")
                        }
                    } label: {
                        Image(systemName: "ellipsis")
                    }
                    .accessibilityLabel("More")
                }
            }
        }
        .sheet(item: $report) { report in
            ReportProblemSheet(model: report, centred: wide) { self.report = nil }
        }
        // A YouTube trailer, full screen in YouTube's player (PLAN-TRAILERS.md §6.3).
        .fullScreenCover(item: $model.trailerPlayer) { player in
            TrailerPlayerScreen(model: player) { model.trailerPlayer = nil }
        }
        .task { await model.run() }
        // Offline, the page follows what is watched on the copy.
        .background { DownloadsChangeWatcher(downloads: services.downloads) { model.refreshOffline() } }
        .messageToast($model.message)
    }

    private func url(_ path: String?, _ size: ImageSize) -> URL? {
        services.library.imageURL(path, size: size)
    }

    @ViewBuilder private func page(_ movie: MovieDetail, topInset: CGFloat, width: CGFloat) -> some View {
        if wide && width >= PadLayout.sideBySide {
            HStack(alignment: .top, spacing: 0) {
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        DetailColumnHeader(title: movie.title, backdropURL: url(movie.backdropPath, .w1280), posterURL: url(movie.posterPath, .w342)) {
                            DetailTitle(text: movie.title, size: 30)
                            Text(movie.facts).scaledFont(14).foregroundStyle(Palette.textSecondary)
                            FlowLayout(spacing: 4) { ForEach(movie.tags, id: \.self) { Tag($0) } }
                        }
                        VStack(alignment: .leading, spacing: 22) {
                            if let download = movie.download { StatusLineView(download.line(), fontSize: 13) }
                            playButtons(movie)
                            details(movie)
                        }
                        .padding(.horizontal, PadLayout.margin)
                        .padding(.bottom, 32)
                    }
                }
                .frame(width: PadLayout.detailColumn)
                .overlay(alignment: .trailing) { Rectangle().fill(Palette.separator).frame(width: 1) }
                ScrollView {
                    VStack(alignment: .leading, spacing: 24) {
                        if !movie.overview.isEmpty {
                            VStack(alignment: .leading, spacing: 8) {
                                DetailHeading(title: "Overview")
                                OverviewText(text: movie.overview, lines: 6, size: 15)
                            }
                        }
                        if !movie.versions.isEmpty { versions(movie, tiled: true) }
                        tracks
                        CastRow(cast: movie.cast, margin: 0, large: true)
                    }
                    .padding(PadLayout.margin)
                    .padding(.bottom, 8)
                }
                .frame(maxWidth: .infinity)
            }
        } else {
            ScrollView {
                Group {
                    if wide {
                        widePage(movie, topInset: topInset)
                    } else {
                        phonePage(movie, topInset: topInset)
                    }
                }
                .padding(.bottom, 32)
            }
            .ignoresSafeArea(edges: .top)
            .onScrollGeometryChange(for: Bool.self) { $0.contentOffset.y > (wide ? 200 : 110) } action: { _, past in
                scrolled = past
            }
        }
    }

    private func phonePage(_ movie: MovieDetail, topInset: CGFloat) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            DetailHeader(title: movie.title, backdropURL: url(movie.backdropPath, .w1280), posterURL: url(movie.posterPath, .w342), topInset: topInset) {
                DetailTitle(text: movie.title)
                Text(movie.facts)
                    .scaledFont(13)
                    .foregroundStyle(Palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                if !movie.tags.isEmpty {
                    FlowLayout(spacing: 4) { ForEach(movie.tags, id: \.self) { Tag($0) } }
                        .accessibilityElement(children: .combine)
                }
                // In Radarr's queue, such as an upgrade (PLAN-ARR.md §6).
                if let download = movie.download { StatusLineView(download.line(), fontSize: 12) }
            }
            VStack(alignment: .leading, spacing: 14) {
                if !movie.overview.isEmpty { OverviewText(text: movie.overview) }
                playButtons(movie)
                if !movie.versions.isEmpty { versions(movie) }
                tracks
            }
            .padding(.horizontal, 16)
            .padding(.top, 16)
            CastRow(cast: movie.cast).padding(.top, 24)
            details(movie)
                .padding(.horizontal, 16)
                .padding(.top, 24)
        }
    }

    /// The portrait iPad page keeps the larger header, then stacks the contents.
    private func widePage(_ movie: MovieDetail, topInset: CGFloat) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            WideDetailHeader(title: movie.title, backdropURL: url(movie.backdropPath, .w1280), posterURL: url(movie.posterPath, .w342), topInset: topInset, portrait: true) {
                DetailTitle(text: movie.title, size: 36)
                FlowLayout(spacing: 8, lineSpacing: 6) {
                    Text(movie.facts).scaledFont(14).foregroundStyle(Palette.textSecondary)
                    ForEach(movie.tags, id: \.self) { Tag($0) }
                }
                .accessibilityElement(children: .combine)
                if let download = movie.download { StatusLineView(download.line(), fontSize: 13).frame(maxWidth: 440, alignment: .leading) }
                if !movie.overview.isEmpty { OverviewText(text: movie.overview, lines: 3, size: 15) }
            }
            VStack(alignment: .leading, spacing: 24) {
                playButtons(movie)
                if !movie.versions.isEmpty { versions(movie, tiled: true) }
                tracks
                CastRow(cast: movie.cast, margin: 0, large: true)
                details(movie)
            }
            .padding(.horizontal, PadLayout.margin)
            .padding(.top, 24)
        }
    }

    @ViewBuilder private func details(_ movie: MovieDetail) -> some View {
        if let recording = model.selectedVersion?.recording ?? movie.versions.first?.recording {
            Label(recording.line(), systemImage: "record.circle").font(.footnote).foregroundStyle(Palette.liveText)
        }
        if !movie.details.isEmpty {
            VStack(alignment: .leading, spacing: 6) {
                DetailHeading(title: "Details")
                DetailRows(rows: movie.details)
            }
        }
    }

    /// Resume (or Play), then From the start and Trailer side by side, then
    /// Mark watched (MovieTrailer.dc.html, Pad2MovieTrailer.dc.html). Without
    /// From the start, Trailer and Mark watched share the row.
    @ViewBuilder private func playButtons(_ movie: MovieDetail) -> some View {
        let toggle = Button(model.watchToggleLabel) { Task { await model.setWatched(!model.watched) } }
            .buttonStyle(GhostButtonStyle())
            .disabled(model.busy)
        let trailer = model.trailer.map { _ in
            Button { model.playTrailer() } label: { Label("Trailer", systemImage: "film") }
                .buttonStyle(GhostButtonStyle())
                .accessibilityIdentifier("movie-trailer")
        }
        VStack(spacing: 8) {
            if !movie.versions.isEmpty {
                Button { model.play() } label: { Label(model.playLabel, systemImage: "play.fill") }
                    .buttonStyle(PrimaryButtonStyle(height: 46))
            }
            if model.resumable, let watch = movie.watch {
                ProgressStrip(progress: watch.progress)
                    .clipShape(Capsule())
                    .padding(.horizontal, 2)
                    .padding(.top, -2)
                HStack(spacing: 8) {
                    Button("From the start") { model.play(fromStart: true) }
                        .buttonStyle(GhostButtonStyle())
                    if let trailer { trailer } else { toggle }
                }
                if trailer != nil { toggle }
            } else if let trailer {
                HStack(spacing: 8) {
                    trailer
                    toggle
                }
            } else {
                toggle
            }
            // Download (MovieDownload.dc.html): of the version Play plays first; a download of any version shows.
            if let first = movie.versions.first {
                ItemDownloadButton(fileID: first.fileID, otherFiles: movie.versions.dropFirst().map(\.fileID)) { model.play() }
                    .id(first.fileID)
            }
        }
    }

    private func versions(_ movie: MovieDetail, tiled: Bool = false) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            DetailHeading(title: "Versions").padding(.top, 4)
            if tiled {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 240), spacing: 14, alignment: .top)], alignment: .leading, spacing: 14) {
                    versionCards(movie, tiled: true)
                }
            } else {
                versionCards(movie)
            }
        }
    }

    private func versionCards(_ movie: MovieDetail, tiled: Bool = false) -> some View {
        ForEach(movie.versions) { version in
            VersionCard(
                version: version,
                planLine: model.planLine(for: version),
                isDefault: model.isDefault(version),
                selected: movie.versions.count > 1 && model.selectedVersion?.id == version.id,
                tiled: tiled,
                onSelect: { model.select(version) },
                onPlay: { model.play(version) }
            )
        }
    }

    @ViewBuilder private var tracks: some View {
        if let version = model.selectedVersion {
            let lines = version.trackLines()
            if !lines.isEmpty {
                VStack(alignment: .leading, spacing: 4) {
                    DetailHeading(title: "Tracks", detail: version.title)
                    VStack(spacing: 0) {
                        ForEach(Array(lines.enumerated()), id: \.offset) { index, line in
                            HStack(spacing: 8) {
                                Text(line.kind?.uppercased() ?? "")
                                    .scaledFont(10)
                                    .tracking(0.6)
                                    .foregroundStyle(Palette.muted)
                                    .frame(width: 72, alignment: .leading)
                                Text(line.text)
                                    .scaledFont(13)
                                    .foregroundStyle(Palette.text)
                                    .lineLimit(1)
                                Spacer(minLength: 4)
                                if line.isDefault { Badge("Default", style: .gold) }
                                if line.burnIn {
                                    Text("Burn-in required").scaledFont(11, weight: .semibold).foregroundStyle(Palette.warning)
                                }
                            }
                            .padding(.vertical, 8)
                            .overlay(alignment: .bottom) { if index < lines.count - 1 { RowSeparator() } }
                            .accessibilityElement(children: .combine)
                        }
                    }
                }
                .padding(.top, 4)
            }
        }
    }
}

/// One file of a movie: its title and tags, how it would play here, and
/// Play this. Tapping the card shows its tracks.
private struct VersionCard: View {
    let version: MediaVersion
    let planLine: String
    let isDefault: Bool
    let selected: Bool
    var tiled = false
    let onSelect: () -> Void
    let onPlay: () -> Void

    var body: some View {
        let layout = tiled ? AnyLayout(VStackLayout(alignment: .leading, spacing: 12)) : AnyLayout(HStackLayout(spacing: 12))
        layout {
            Button(action: onSelect) {
                VStack(alignment: .leading, spacing: 5) {
                    HStack(spacing: 8) {
                        Text(version.title).scaledFont(15, weight: .bold).foregroundStyle(Palette.text)
                        if isDefault { Badge("Default", style: .gold) }
                    }
                    FlowLayout(spacing: 4) { ForEach(version.tags, id: \.self) { Tag($0) } }
                    Text(planLine)
                        .scaledFont(11)
                        .foregroundStyle(Palette.faint)
                        .lineLimit(2)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityElement(children: .combine)
            .accessibilityHint("Shows its tracks")
            .accessibilityAddTraits(selected ? .isSelected : [])
            Button("Play this", action: onPlay)
                .buttonStyle(SmallGhostButtonStyle())
                .accessibilityLabel("Play \(version.title)")
        }
        .padding(.vertical, 12)
        .padding(.horizontal, 14)
        .background(Palette.surface, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(selected ? Palette.gold : Palette.separator))
    }
}
