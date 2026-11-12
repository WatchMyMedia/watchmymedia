import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// Search (TvSearch.dc.html): the tvOS search field and keyboard (Siri
/// dictation works on the field), then TV Shows and Movies as posters and
/// Episodes as 16:9 cards; "Search your libraries" before anything is typed
/// and "No matches" after. An episode opens its show with its row expanded.
/// From API level 5 (TvSearchRequest.dc.html), a Not in your library row
/// follows, each poster with Request, Requested or Downloading; Request
/// opens the full-screen request dialog. From API level 15 with listings
/// (TvLiveSearch.dc.html), On Live TV follows the library's rows.
struct SearchScreen: View {
    @Environment(AppServices.self) private var services
    @Environment(Navigator.self) private var navigator
    @Environment(LiveExtrasStore.self) private var extras

    var body: some View {
        SearchView(model: SearchModel(library: services.library, navigator: navigator, requests: services.requests),
                   live: LiveSearchModel(repository: services.liveTV, extras: extras))
    }
}

private struct SearchView: View {
    @State var model: SearchModel
    @State var live: LiveSearchModel
    @Environment(AppServices.self) private var services

    var body: some View {
        TabPage {
            VStack(alignment: .leading, spacing: 30) {
                content
                // On Live TV, after the library's rows.
                TVLiveSearchSection(model: live)
                if !model.notInLibrary.isEmpty {
                    ShelfRow(title: "Not in your library", detail: formatCount(model.notInLibrary.count), items: model.notInLibrary, spacing: 40) { requestCard($0) }
                }
            }
            .padding(.bottom, 60)
        }
        .fullScreenCover(item: $model.requestSheet) { sheet in
            RequestDialog(model: sheet) {
                model.requestSheetClosed(sheet)
                model.requestSheet = nil
            }
        }
        .searchable(text: Binding(get: { model.query }, set: { model.setQuery($0); live.setQuery($0) }),
                    prompt: live.available ? "Shows, movies, episodes, Live TV" : "Shows, movies, episodes")
        .autocorrectionDisabled()
        .messagePill($model.message)
        .onChange(of: live.available, initial: true) { _, _ in live.availabilityChanged() }
        .task {
            #if DEBUG
            // `-search harbor` searches at launch, for screenshots and UI checks.
            let arguments = ProcessInfo.processInfo.arguments
            if let i = arguments.firstIndex(of: "-search"), arguments.indices.contains(i + 1) {
                model.setQuery(arguments[i + 1]); live.setQuery(arguments[i + 1])
            }
            #endif
        }
        .noticeAlert($model.notice)
    }

    private func url(_ path: String?, _ size: ImageSize) -> URL? {
        services.library.imageURL(path, size: size)
    }

    @ViewBuilder private var content: some View {
        switch model.phase {
        case .idle:
            TVEmptyState(systemImage: "magnifyingglass", title: "Search your libraries", message: "Find a show, a movie or an episode by its title.")
                .padding(.top, 80)
        case .loading:
            ProgressView().frame(maxWidth: .infinity).padding(.top, 120)
        case .noMatches(let query):
            // Nothing in the library, but On Live TV may still have some.
            if case .results = live.phase {} else {
                VStack(spacing: 14) {
                    Text("No matches").font(.system(size: 34, weight: .bold)).foregroundStyle(Palette.text)
                    Text(noMatchesMessage(query)).font(.system(size: 22)).foregroundStyle(Palette.muted)
                }
                .frame(maxWidth: .infinity)
                .padding(.top, 100)
            }
        case .failed(let message):
            TVErrorState(title: "Couldn't search", message: message, retry: model.retry)
                .padding(.top, 60)
        case .results(let results):
            VStack(alignment: .leading, spacing: 30) {
                if !results.shows.isEmpty && !results.movies.isEmpty && results.shows.count + results.movies.count <= 7 {
                    // Few enough to sit side by side, as the artboard has them.
                    HStack(alignment: .top, spacing: 40) {
                        group("TV Shows", count: results.shows.count) { posters(results.shows) }
                        group("Movies", count: results.movies.count) { posters(results.movies) }
                    }
                    .padding(.horizontal, TVLayout.gutter)
                    .focusSection()
                } else {
                    if !results.shows.isEmpty {
                        ShelfRow(title: "TV Shows", detail: formatCount(results.shows.count), items: results.shows) { posterButton($0) }
                    }
                    if !results.movies.isEmpty {
                        ShelfRow(title: "Movies", detail: formatCount(results.movies.count), items: results.movies) { posterButton($0) }
                    }
                }
                if !results.episodes.isEmpty {
                    ShelfRow(title: "Episodes", detail: formatCount(results.episodes.count), items: results.episodes) { episodeButton($0) }
                }
            }
            .padding(.top, 20)
        }
    }

    private func group(_ title: String, count: Int, @ViewBuilder content: () -> some View) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            RowHeading(title: title, detail: formatCount(count))
            content().padding(.vertical, 30)
        }
    }

    private func posters(_ items: [BrowseItem]) -> some View {
        HStack(alignment: .top, spacing: 24) {
            ForEach(items) { posterButton($0) }
        }
    }

    private func posterButton(_ item: BrowseItem) -> some View {
        Button { model.open(item) } label: {
            PosterCard(item: item, imageURL: url(item.posterPath, .w500), width: 200, line: item.searchLine)
        }
        .buttonStyle(.borderless)
        .contextMenu {
            if item.watchTarget != nil {
                WatchToggleButton(watched: item.watched) { await model.setWatched(item, $0) }
            }
        }
    }

    /// A TMDB result: its poster, title and line, and Request, Requested or Downloading.
    private func requestCard(_ item: Requestable) -> some View {
        Button { model.openRequest(item) } label: {
            RequestCard(item: item, imageURL: url(item.posterPath, .w500))
        }
        .buttonStyle(.borderless)
        .accessibilityHint(item.state == .requestable ? "Opens the request" : "")
    }

    private func episodeButton(_ episode: EpisodeCard) -> some View {
        Button { model.open(episode) } label: {
            WideCard(
                title: episode.title,
                line: episode.searchLine(),
                imageURL: url(episode.stillPath ?? episode.showBackdropPath, .w780),
                seed: episode.showTitle,
                missing: !episode.hasFile
            ) {
                if episode.isNew { TVBadge("New", style: .gold).pinned(.topLeading, padding: 10) }
                if !episode.hasFile { TVBadge("Not in library", style: .warning).pinned(.topLeading, padding: 10) }
                if episode.watched { WatchedCheck(size: 28).pinned(.bottomTrailing, padding: 10) }
                TVBadge(episode.codeBadge).pinned(.bottomLeading, padding: 10)
            }
        }
        .buttonStyle(.borderless)
        .accessibilityHint("Opens the episode on its show")
        .contextMenu {
            if episode.playRequest != nil {
                Button { model.play(episode) } label: { Label("Play", systemImage: "play.fill") }
            }
            WatchToggleButton(watched: episode.watched) { await model.setWatched(episode, $0) }
        }
    }
}

/// A not-in-library poster (TvSearchRequest.dc.html's card): the poster, the
/// title and line, and its pill.
private struct RequestCard: View {
    let item: Requestable
    let imageURL: URL?
    @Environment(\.isFocused) private var focused

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Artwork(url: imageURL, seed: item.title, maxPixel: 480)
                .frame(width: 200, height: 300)
                .overlay { FocusSheen(focused: focused) }
                .clipShape(RoundedRectangle(cornerRadius: 16))
                .focusRim(focused, cornerRadius: 16)
            CardCaption(title: item.title, line: item.searchLine, focused: focused)
            pill.padding(.top, 10).offset(y: focused ? 12 : 0)
        }
        .frame(width: 200, alignment: .leading)
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder private var pill: some View {
        switch item.state {
        case .requestable:
            label(Text("Request"), color: Palette.gold, fill: Palette.gold.opacity(0.14))
        case .requested, .available:
            label(Text("\(Image(systemName: "checkmark")) Requested"), color: Palette.textSecondary, fill: Palette.outline)
        case .downloading:
            label(Text("\(Image(systemName: "arrow.down.to.line")) Downloading"), color: Palette.gold, fill: .clear)
        }
    }

    private func label(_ text: Text, color: Color, fill: Color) -> some View {
        text
            .font(.system(size: 17, weight: .semibold))
            .foregroundStyle(color)
            .padding(.horizontal, fill == .clear ? 0 : 16)
            .frame(height: 38)
            .background(fill, in: Capsule())
    }
}
