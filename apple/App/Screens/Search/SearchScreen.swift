import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// Search (Search.dc.html): the server's search a moment after typing
/// stops, with TV Shows, Movies and Episodes in groups. A show or movie
/// opens its page; an episode opens its own page.
/// On iPad (Pad2Search.dc.html), shows and movies are posters beside the
/// episode list in landscape; portrait stacks those two sections.
/// Requestable titles follow the library results, retaining the server permissions and Seerr guidance.
/// From API level 15 with listings, On Live TV follows the library's groups
/// (LiveSearch.dc.html's `tab` Tweak, PLAN-LIVE-TV-GUIDE.md §6.5).
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
    @Environment(LiveExtrasStore.self) private var extras
    @Environment(\.horizontalSizeClass) private var sizeClass
    @State private var width: CGFloat = 0

    private var wide: Bool { sizeClass.isWide }

    /// The groups with results, in their order.
    private enum ResultKind: CaseIterable { case shows, movies, episodes }

    var body: some View {
        ScrollView {
            content
                .padding(.bottom, 24)
        }
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width = $0 }
        .scrollDismissesKeyboard(.immediately)
        .groundBackground()
        .navigationTitle("Search")
        .navigationBarTitleDisplayMode(.large)
        .searchable(
            text: Binding(get: { model.query }, set: { model.setQuery($0); live.setQuery($0) }),
            placement: .navigationBarDrawer(displayMode: .always),
            prompt: live.available ? "Shows, movies, episodes, Live TV" : "Shows, movies, episodes"
        )
        .autocorrectionDisabled()
        .textInputAutocapitalization(.never)
        .task {
            // `-search harbor` (DEBUG) searches at launch, for screenshots.
            if let query = ScreenshotLaunch.value(after: "-search") { model.setQuery(query); live.setQuery(query) }
        }
        .liveRemindFlow()
        .messageToast($model.message)
        .onChange(of: live.available, initial: true) { _, _ in live.availabilityChanged() }
        .noticeAlert($model.notice)
        .sheet(item: Binding(get: { RequestSheet.presented(model.requestSheet, centred: wide) }, set: { model.requestSheet = $0 })) { sheet in
            RequestSheet(model: sheet, centred: wide) {
                model.requestSheetClosed(sheet)
                model.requestSheet = nil
            }
        }
    }

    @ViewBuilder private var content: some View {
        VStack(spacing: 22) {
            phaseContent
            // On Live TV, after the library's groups.
            LiveSearchSection(model: live)
                .padding(.horizontal, wide ? PadLayout.margin : 16)
            if !model.notInLibrary.isEmpty {
                notInLibrary(model.notInLibrary)
                    .padding(.horizontal, wide ? PadLayout.margin : 16)
            }
        }
    }

    /// Not in your library: TMDB's matches, with Request, Requested or Downloading.
    private func notInLibrary(_ items: [Requestable]) -> some View {
        ResultGroup(title: "Not in your library", count: items.count) {
            ForEach(Array(items.enumerated()), id: \.element.id) { index, item in
                Group {
                    if item.state == .requestable {
                        Button { model.openRequest(item) } label: { requestableRow(item) }
                            .buttonStyle(ResultRowStyle())
                            .accessibilityHint("Opens the request")
                    } else {
                        requestableRow(item)
                    }
                }
                .overlay(alignment: .top) { if index > 0 { RowSeparator() } }
            }
        }
    }

    private func requestableRow(_ item: Requestable) -> some View {
        HStack(spacing: 12) {
            Artwork(url: services.library.imageURL(item.posterPath, size: .w185), seed: item.title, maxPixel: 200)
                .frame(width: 44, height: 66)
                .clipShape(RoundedRectangle(cornerRadius: 6))
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(item.title).scaledFont(15, weight: .semibold).foregroundStyle(Palette.text).lineLimit(1)
                Text(item.searchLine).scaledFont(13).foregroundStyle(Palette.muted).lineLimit(1)
            }
            Spacer(minLength: 8)
            RequestPill(state: item.state)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .frame(minHeight: 60)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder private var phaseContent: some View {
        switch model.phase {
        case .idle:
            if wide {
                VStack(spacing: 12) {
                    searchPrompt
                    if !model.recentQueries.isEmpty { recentQueries }
                }
                .padding(.horizontal, PadLayout.margin)
                .frame(maxWidth: 608)
                .frame(maxWidth: .infinity)
                .padding(.top, 60)
            } else {
                searchPrompt.padding(.top, 100)
            }
        case .loading:
            ProgressView().tint(Palette.muted).frame(maxWidth: .infinity).padding(.top, 120)
        case .noMatches(let query):
            // Nothing in the library, but Live TV's group may still have some.
            if case .results = live.phase {} else {
                EmptyState(systemImage: "text.magnifyingglass", title: "No matches", message: noMatchesMessage(query))
                    .padding(.top, 100)
            }
        case .failed(let message):
            ErrorState(title: "Couldn't search", message: message, retry: model.retry)
                .padding(.top, 60)
        case .results(let results):
            let groups = ResultKind.allCases.filter { !isEmpty($0, in: results) }
            VStack(alignment: .leading, spacing: 22) {
                if model.loading {
                    ProgressView().tint(Palette.muted).frame(maxWidth: .infinity)
                }
                if wide {
                    if width >= PadLayout.sideBySide && (!results.shows.isEmpty || !results.movies.isEmpty) && !results.episodes.isEmpty {
                        HStack(alignment: .top, spacing: 24) {
                            posters(results)
                                .frame(maxWidth: .infinity, alignment: .topLeading)
                            if !results.episodes.isEmpty {
                                group(.episodes, results).frame(width: min(376, width * 0.34))
                            }
                        }
                    } else {
                        posters(results)
                        if !results.episodes.isEmpty { group(.episodes, results) }
                    }
                } else {
                    ForEach(groups, id: \.self) { group($0, results) }
                }
            }
            .padding(.horizontal, wide ? PadLayout.margin : 16)
            .padding(.top, 8)
        }
    }

    private var searchPrompt: some View {
        EmptyState(systemImage: "magnifyingglass", title: "Search your libraries", message: "Find a show, a movie or an episode by its title.")
    }

    private var recentQueries: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text("Recent").scaledFont(13, weight: .semibold).foregroundStyle(Palette.muted)
                    .accessibilityAddTraits(.isHeader)
                Spacer()
                Button("Clear", action: model.clearRecentQueries)
                    .buttonStyle(GoldTextButtonStyle(font: .subheadline.weight(.semibold)))
                    .accessibilityLabel("Clear recent searches")
            }
            .padding(.horizontal, 6)
            ForEach(model.recentQueries, id: \.self) { query in
                Button { model.selectRecentQuery(query) } label: {
                    HStack(spacing: 12) {
                        Image(systemName: "clock").foregroundStyle(Palette.faint).accessibilityHidden(true)
                        Text(query).foregroundStyle(Palette.text).lineLimit(1)
                        Spacer(minLength: 0)
                    }
                    .scaledFont(17)
                    .padding(.horizontal, 6)
                    .frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
                    .contentShape(Rectangle())
                }
                .buttonStyle(ResultRowStyle())
                .overlay(alignment: .top) { RowSeparator() }
                .accessibilityHint("Searches again")
            }
            RowSeparator()
        }
    }

    @ViewBuilder private func posters(_ results: SearchResults) -> some View {
        let items = results.shows + results.movies
        if !items.isEmpty {
            VStack(alignment: .leading, spacing: 12) {
                SectionHeading("Shows and movies", detail: formatCount(items.count))
                LazyVGrid(columns: [GridItem(.adaptive(minimum: PadLayout.posterMinimum), spacing: 16, alignment: .top)], spacing: 18) {
                    ForEach(items, id: \.self) { item in
                        Button { model.open(item) } label: {
                            VStack(alignment: .leading, spacing: 4) {
                                PosterCard(item: item, imageURL: services.library.imageURL(item.posterPath, size: .w342), compact: true)
                                Text(item.kind == .show ? "TV show" : "Movie")
                                    .scaledFont(11, weight: .semibold)
                                    .foregroundStyle(Palette.muted)
                            }
                        }
                        .buttonStyle(CardButtonStyle())
                        .contextMenu {
                            if item.watchTarget != nil {
                                WatchToggleButton(watched: item.watched) { await model.setWatched(item, $0) }
                            }
                        }
                    }
                }
            }
        }
    }

    private func isEmpty(_ group: ResultKind, in results: SearchResults) -> Bool {
        switch group {
        case .shows: results.shows.isEmpty
        case .movies: results.movies.isEmpty
        case .episodes: results.episodes.isEmpty
        }
    }

    @ViewBuilder private func group(_ group: ResultKind, _ results: SearchResults) -> some View {
        switch group {
        case .shows:
            ResultGroup(title: "TV Shows", count: results.shows.count) {
                ForEach(Array(results.shows.enumerated()), id: \.element.id) { index, item in itemRow(item, first: index == 0) }
            }
        case .movies:
            ResultGroup(title: "Movies", count: results.movies.count) {
                ForEach(Array(results.movies.enumerated()), id: \.element.id) { index, item in itemRow(item, first: index == 0) }
            }
        case .episodes:
            ResultGroup(title: "Episodes", count: results.episodes.count) {
                ForEach(Array(results.episodes.enumerated()), id: \.element.id) { index, episode in episodeRow(episode, first: index == 0) }
            }
        }
    }

    private func itemRow(_ item: BrowseItem, first: Bool) -> some View {
        Button { model.open(item) } label: {
            ResultRow(title: item.title, line: item.searchLine) {
                Artwork(url: services.library.imageURL(item.posterPath, size: .w185), seed: item.title, maxPixel: 200)
                    .frame(width: wide ? 48 : 44, height: wide ? 72 : 66)
                    .overlay { if item.watched { WatchedCheck(size: 16).pinned(.bottomTrailing, padding: 4) } }
                    .clipShape(RoundedRectangle(cornerRadius: 6))
            }
        }
        .buttonStyle(ResultRowStyle())
        .overlay(alignment: .top) { if !first { RowSeparator() } }
        .contextMenu {
            if item.watchTarget != nil {
                WatchToggleButton(watched: item.watched) { await model.setWatched(item, $0) }
            }
        }
    }

    private func episodeRow(_ episode: EpisodeCard, first: Bool) -> some View {
        Button { model.open(episode) } label: {
            ResultRow(title: episode.title, line: episode.searchLine()) {
                Group {
                    if episode.hasFile {
                        Artwork(url: services.library.imageURL(episode.stillPath ?? episode.showBackdropPath, size: .w300), seed: episode.showTitle, maxPixel: 300)
                            .overlay {
                                if episode.isNew { Badge("New", style: .gold).pinned(.topLeading, padding: 4) }
                                if episode.watched { WatchedCheck(size: 16).pinned(.bottomTrailing, padding: 4) }
                            }
                    } else {
                        DashedPlaceholder(cornerRadius: 6)
                            .overlay { Badge("Not in library", style: .warning).lineLimit(1).minimumScaleFactor(0.7).pinned(.topLeading, padding: 4) }
                    }
                }
                .frame(width: wide ? 128 : 96, height: wide ? 72 : 54)
                .clipShape(RoundedRectangle(cornerRadius: 6))
            }
        }
        .buttonStyle(ResultRowStyle())
        .overlay(alignment: .top) { if !first { RowSeparator() } }
        .accessibilityValue(episode.hasFile ? "" : "Not in library")
        .accessibilityHint("Opens the episode")
        .contextMenu {
            if episode.playRequest != nil {
                Button { model.play(episode) } label: { Label("Play", systemImage: "play.fill") }
            }
            WatchToggleButton(watched: episode.watched) { await model.setWatched(episode, $0) }
        }
    }
}

/// "TV Shows 1" and its results in one rounded group.
private struct ResultGroup<Content: View>: View {
    let title: String
    let count: Int
    @ViewBuilder var content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            SectionHeading(title, detail: formatCount(count))
            VStack(spacing: 0) { content() }
                .background(Palette.surfaceRaised, in: RoundedRectangle(cornerRadius: 12))
                .clipShape(RoundedRectangle(cornerRadius: 12))
                .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(Palette.separator))
        }
    }
}

/// A result: its artwork, title and line, and a chevron.
private struct ResultRow<Art: View>: View {
    let title: String
    let line: String
    @ViewBuilder var art: () -> Art

    var body: some View {
        HStack(spacing: 12) {
            art().accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).scaledFont(15, weight: .semibold).foregroundStyle(Palette.text).lineLimit(1)
                Text(line).scaledFont(13).foregroundStyle(Palette.muted).lineLimit(1)
            }
            Spacer(minLength: 0)
            Image(systemName: "chevron.right")
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(Palette.faint)
                .accessibilityHidden(true)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .frame(minHeight: 60)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}

/// A result row lights up while pressed, and under an iPad's pointer.
private struct ResultRowStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background(configuration.isPressed ? Color.white.opacity(0.06) : .clear)
            .hoverEffect(.highlight)
    }
}

/// A not-in-library result's pill: Request (gold), Requested, Downloading.
private struct RequestPill: View {
    let state: Requestable.State

    var body: some View {
        switch state {
        case .requestable:
            pill(Text("Request"), color: Palette.gold, fill: Palette.gold.opacity(0.14))
        case .requested, .available:
            pill(Text("\(Image(systemName: "checkmark")) Requested"), color: Palette.textSecondary, fill: Palette.outline)
        case .downloading:
            pill(Text("\(Image(systemName: "arrow.down.to.line")) Downloading"), color: Palette.gold, fill: .clear)
        }
    }

    private func pill(_ text: Text, color: Color, fill: Color) -> some View {
        text
            .scaledFont(13, weight: .semibold)
            .foregroundStyle(color)
            .lineLimit(1)
            .padding(.horizontal, fill == .clear ? 2 : 12)
            .frame(minHeight: 30)
            .background(fill, in: Capsule())
            .fixedSize()
    }
}
