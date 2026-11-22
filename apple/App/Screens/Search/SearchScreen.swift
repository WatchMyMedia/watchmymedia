import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// Search: the server's search a moment after typing
/// stops, with TV Shows, Movies and Episodes in groups. A show or movie
/// opens its page; an episode opens its own page.
/// On iPad, shows and movies are posters beside the
/// episode list in landscape; portrait stacks those two sections.
/// Requestable titles follow the library results, retaining the server permissions and Seerr guidance.
/// From API level 15 with listings, On Live TV follows the library's
/// groups.
/// With two or more servers signed in, search looks in all of them: the
/// server in use's results first in each group, the others' after them,
/// each naming its server. A server still being asked, and one that didn't
/// answer (with Try again), are lines at the end. Opening a result on
/// another server switches the app to it. The merging is the Kit's (``SearchModel``).
struct SearchScreen: View {
    @Environment(AppServices.self) private var services
    @Environment(Navigator.self) private var navigator
    @Environment(LiveExtrasStore.self) private var extras
    @Environment(ServersModel.self) private var servers

    var body: some View {
        // Recent searches are kept between launches for this account on this server.
        let recents = services.servers.current.flatMap { current in services.auth.user.map { RecentSearches(server: current.server.id, user: $0.id) } }
        SearchView(model: SearchModel(library: services.library, navigator: navigator, requests: services.requests, recents: recents,
                                      servers: services.servers, switcher: servers),
                   live: LiveSearchModel(repository: services.liveTV, extras: extras))
    }
}

private struct SearchView: View {
    @State var model: SearchModel
    @State var live: LiveSearchModel
    @Environment(AppServices.self) private var services
    @Environment(LiveExtrasStore.self) private var extras
    @Environment(\.horizontalSizeClass) private var sizeClass
    @Environment(Navigator.self) private var navigator
    @Environment(\.scenePhase) private var scenePhase
    /// Search is the screen showing, not a page over it or another tab.
    @State private var onScreen = false
    @State private var width: CGFloat = 0
    @FocusState private var fieldFocused: Bool

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
        .searchFocused($fieldFocused)
        .autocorrectionDisabled()
        .textInputAutocapitalization(.never)
        // ⌘F: the cursor in the field.
        .onChange(of: navigator.searchFieldRequested, initial: true) { _, requested in
            guard requested else { return }
            fieldFocused = true
            navigator.searchFieldRequested = false
        }
        .task {
            // `-search harbor` (DEBUG) searches at launch, for screenshots.
            if let query = ScreenshotLaunch.value(after: "-search") { model.setQuery(query); live.setQuery(query) }
        }
        // A result on another server was opened: the app is on that server now, and
        // the same words are asked again here when Search shows (Back from the title).
        .onAppear {
            guard let words = navigator.takeSearchWords() else { return }
            model.selectRecentQuery(words)
            live.setQuery(words)
        }
        // A visit to Search begins each time Search comes on screen: the first time, back from a
        // title or another tab, back from the background. Each other server says which server it
        // is once for a visit, before its sign-in goes to the address kept for it; a phone
        // changes network while the app is away, so the next visit asks again.
        .onAppear {
            onScreen = true
            model.visitBegan()
        }
        .onDisappear {
            onScreen = false
            model.visitEnded()
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .background { model.visitEnded() } else if phase == .active, onScreen { model.visitBegan() }
        }
        .liveRemindFlow()
        .messageToast($model.message)
        .onChange(of: live.available, initial: true) { _, _ in live.availabilityChanged() }
        .noticeAlert($model.notice)
        .sheet(item: RequestSheet.presenting($model.requestSheet, centred: wide)) { sheet in
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
    /// They stay the server in use's, which the heading names across servers.
    private func notInLibrary(_ items: [Requestable]) -> some View {
        ResultGroup(title: "Not in your library", detail: model.notInLibraryDetail) {
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
                let words = model.noMatchesWords(query)
                EmptyState(systemImage: "text.magnifyingglass", title: words.title, message: words.message)
                    .padding(.top, 100)
            }
            // A server that wasn't asked, or didn't answer, may still have it.
            serverLines.padding(.horizontal, wide ? PadLayout.margin : 16)
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
                serverLines
            }
            .padding(.horizontal, wide ? PadLayout.margin : 16)
            .padding(.top, 8)
        }
    }

    /// At the end of the list: a server asked a while ago that hasn't
    /// answered yet, and each server whose titles aren't listed, with Try
    /// again for one that didn't answer.
    @ViewBuilder private var serverLines: some View {
        if model.stillAsking != nil || !model.unlisted.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                if let asking = model.stillAsking {
                    HStack(spacing: 10) {
                        ProgressView().controlSize(.small).tint(Palette.gold)
                        Text(asking).scaledFont(13).foregroundStyle(Palette.muted)
                    }
                    .frame(minHeight: 32)
                    .accessibilityElement(children: .combine)
                    .accessibilityIdentifier("search-still-asking")
                }
                ForEach(model.unlisted) { server in
                    HStack(alignment: .center, spacing: 10) {
                        Image(systemName: "exclamationmark.circle")
                            .foregroundStyle(Palette.warning)
                            .accessibilityHidden(true)
                        Text(server.line)
                            .scaledFont(13)
                            .foregroundStyle(Palette.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                            .frame(maxWidth: .infinity, alignment: .leading)
                        if server.canRetry {
                            Button(SearchWords.tryAgain) { model.retry(server) }
                                .buttonStyle(SmallGhostButtonStyle())
                                .accessibilityLabel("\(SearchWords.tryAgain), \(server.server.name)")
                                .accessibilityIdentifier("search-retry-\(server.id)")
                        }
                    }
                    .frame(minHeight: 32)
                    .accessibilityElement(children: .contain)
                    .accessibilityIdentifier("search-unlisted-\(server.id)")
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.top, 4)
        }
    }

    private var searchPrompt: some View {
        let words = model.idleWords
        return EmptyState(systemImage: "magnifyingglass", title: words.title, message: words.message)
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

    @ViewBuilder private func posters(_ results: SearchListing) -> some View {
        let items = results.shows + results.movies
        if !items.isEmpty {
            VStack(alignment: .leading, spacing: 12) {
                SectionHeading("Shows and movies", detail: formatCount(items.count))
                LazyVGrid(columns: [GridItem(.adaptive(minimum: PadLayout.posterMinimum), spacing: 16, alignment: .top)], spacing: 18) {
                    ForEach(items) { found in
                        Button { model.open(found) } label: {
                            VStack(alignment: .leading, spacing: 4) {
                                PosterCard(item: found.item, imageURL: services.library.imageURL(found.posterPath, size: .w342, on: found.server), compact: true)
                                Text(found.kind == .show ? "TV show" : "Movie")
                                    .scaledFont(11, weight: .semibold)
                                    .foregroundStyle(Palette.muted)
                                // The server it is on, when that isn't the one in use.
                                if let server = found.server { ServerLabel(name: server.name) }
                            }
                        }
                        .buttonStyle(CardButtonStyle())
                        .accessibilityValue(found.server.map { "On \($0.name)" } ?? "")
                        .accessibilityIdentifier("search-result-\(found.id)")
                        .contextMenu {
                            // Marking watched stays the server in use's.
                            if found.server == nil, found.watchTarget != nil {
                                WatchToggleButton(watched: found.watched) { await model.setWatched(found.item, $0) }
                            }
                            if found.server == nil { WatchlistMenuButton(found.item) }
                        }
                    }
                }
            }
        }
    }

    private func isEmpty(_ group: ResultKind, in results: SearchListing) -> Bool {
        switch group {
        case .shows: results.shows.isEmpty
        case .movies: results.movies.isEmpty
        case .episodes: results.episodes.isEmpty
        }
    }

    @ViewBuilder private func group(_ group: ResultKind, _ results: SearchListing) -> some View {
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

    private func itemRow(_ found: FoundTitle, first: Bool) -> some View {
        Button { model.open(found) } label: {
            ResultRow(title: found.title, line: found.item.searchLine, server: found.server?.name) {
                Artwork(url: services.library.imageURL(found.posterPath, size: .w185, on: found.server), seed: found.title, maxPixel: 200)
                    .frame(width: wide ? 48 : 44, height: wide ? 72 : 66)
                    .overlay { if found.watched { WatchedCheck(size: 16).pinned(.bottomTrailing, padding: 4) } }
                    .clipShape(RoundedRectangle(cornerRadius: 6))
            }
        }
        .buttonStyle(ResultRowStyle())
        .overlay(alignment: .top) { if !first { RowSeparator() } }
        .accessibilityIdentifier("search-result-\(found.id)")
        .contextMenu {
            // Marking watched stays the server in use's.
            if found.server == nil, found.watchTarget != nil {
                WatchToggleButton(watched: found.watched) { await model.setWatched(found.item, $0) }
            }
            if found.server == nil { WatchlistMenuButton(found.item) }
        }
    }

    private func episodeRow(_ found: FoundEpisode, first: Bool) -> some View {
        let episode = found.episode
        return Button { model.open(found) } label: {
            ResultRow(title: episode.title, line: episode.searchLine(), server: found.server?.name) {
                Group {
                    if episode.hasFile {
                        Artwork(url: services.library.imageURL(episode.stillPath ?? episode.showBackdropPath, size: .w300, on: found.server), seed: episode.showTitle, maxPixel: 300)
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
        .accessibilityIdentifier("search-result-\(found.id)")
        .contextMenu {
            // Playing and marking watched stay the server in use's.
            if found.server == nil {
                if episode.playRequest != nil {
                    Button { model.play(episode) } label: { Label("Play", systemImage: "play.fill") }
                }
                WatchToggleButton(watched: episode.watched) { await model.setWatched(episode, $0) }
            }
        }
    }
}

/// The name of the server a result is on, when that isn't the one in use: a
/// small label at the end of its row, or under its poster.
private struct ServerLabel: View {
    let name: String

    var body: some View {
        Text(name)
            .scaledFont(11, weight: .semibold)
            .foregroundStyle(Palette.textSecondary)
            .lineLimit(1)
            .fixedSize()
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .overlay(RoundedRectangle(cornerRadius: 5).strokeBorder(Palette.outlineStrong))
            .accessibilityLabel("On \(name)")
    }
}

/// "TV Shows 1" and its results in one rounded group.
private struct ResultGroup<Content: View>: View {
    let title: String
    let detail: String
    @ViewBuilder var content: () -> Content

    init(title: String, count: Int, @ViewBuilder content: @escaping () -> Content) {
        self.init(title: title, detail: formatCount(count), content: content)
    }

    init(title: String, detail: String, @ViewBuilder content: @escaping () -> Content) {
        self.title = title
        self.detail = detail
        self.content = content
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            SectionHeading(title, detail: detail)
            VStack(spacing: 0) { content() }
                .background(Palette.surfaceRaised, in: RoundedRectangle(cornerRadius: 12))
                .clipShape(RoundedRectangle(cornerRadius: 12))
                .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(Palette.separator))
        }
    }
}

/// A result: its artwork, title and line, the server it is on when that
/// isn't the one in use, and a chevron.
private struct ResultRow<Art: View>: View {
    let title: String
    let line: String
    var server: String?
    @ViewBuilder var art: () -> Art

    private var lines: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title).scaledFont(15, weight: .semibold).foregroundStyle(Palette.text).lineLimit(1)
            Text(line).scaledFont(13).foregroundStyle(Palette.muted).lineLimit(1)
        }
    }

    var body: some View {
        HStack(spacing: 12) {
            art().accessibilityHidden(true)
            if let server {
                // The server's name at the row's end where the title and its line fit
                // beside it whole, else under them, so neither is cut short for it.
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 12) {
                        lines.fixedSize(horizontal: true, vertical: false)
                        Spacer(minLength: 0)
                        ServerLabel(name: server)
                    }
                    VStack(alignment: .leading, spacing: 4) {
                        lines
                        ServerLabel(name: server)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
            } else {
                lines
                Spacer(minLength: 0)
            }
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
