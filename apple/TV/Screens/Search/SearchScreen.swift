import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// Search: the tvOS search field and keyboard (Siri dictation works on the
/// field), then what was found. Before anything is typed, the searches made
/// lately (kept on this Apple TV for the person on this server), one move
/// down from the keyboard, with Clear last. Movies and TV shows share one
/// row of posters, where only the focused poster carries words; then
/// Episodes as 16:9 cards; then, from API level 15 with listings, On live
/// TV, where Select on a programme on now watches it; then, set apart, Not
/// in your library (from API level 5), each poster with Request, Requested
/// or Downloading on it, Request opening the full-screen request dialog.
/// An episode opens its show with its row expanded.
///
/// Signed in to several servers, it looks in all of them (`SearchModel`):
/// the server in use's cards come first and carry no server; a card from
/// another server names it on its picture, and Select on one switches this
/// Apple TV to that server and opens the title there. A server still being
/// asked, and one whose titles aren't listed, are a tile at the end of the
/// last row, with Try again where it didn't answer.
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

/// What a search row holds: a title or an episode found, and at the end of
/// the last row the servers still being asked and those not listed.
private enum SearchCard: Identifiable, Hashable {
    case title(FoundTitle)
    case episode(FoundEpisode)
    /// "Still asking Cabin server…"
    case asking(String)
    case unlisted(UnlistedServer)

    var id: String {
        switch self {
        case .title(let found): "title/\(found.id)"
        case .episode(let found): "episode/\(found.id)"
        case .asking: "asking"
        case .unlisted(let server): "unlisted/\(server.id)"
        }
    }
}

private struct SearchView: View {
    @State var model: SearchModel
    @State var live: LiveSearchModel
    @Environment(AppServices.self) private var services
    @Environment(Navigator.self) private var navigator
    @Environment(\.scenePhase) private var scenePhase
    /// Search is the screen showing, not a page over it or another tab.
    @State private var onScreen = false

    /// Between rows: room under a row of posters for the focused one's words.
    private static let rowSpacing: CGFloat = 60

    var body: some View {
        TabPage {
            VStack(alignment: .leading, spacing: Self.rowSpacing) {
                content
                // On live TV, after the library's rows.
                TVSearchLiveRow(model: live)
                if !model.notInLibrary.isEmpty {
                    // Set apart: these are not here yet.
                    ShelfRow(title: "Not in your library", detail: model.notInLibraryDetail, items: model.notInLibrary) { requestCard($0) }
                        .padding(.top, 40)
                }
            }
            .padding(.top, 20)
            .padding(.bottom, TVLayout.safeBottom)
        }
        .fullScreenCover(item: $model.requestSheet) { sheet in
            RequestDialog(model: sheet) {
                model.requestSheetClosed(sheet)
                model.requestSheet = nil
            }
        }
        .searchable(text: Binding(get: { model.query }, set: { model.setQuery($0); live.setQuery($0) }),
                    prompt: live.available ? "Shows, movies, episodes, live TV" : "Shows, movies, episodes")
        .autocorrectionDisabled()
        .messagePill($model.message)
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
        .onChange(of: live.available, initial: true) { _, _ in live.availabilityChanged() }
        .task {
            // After a switch from a result on another server: the same words, asked again here.
            if let words = navigator.takeSearchWords() {
                model.selectRecentQuery(words)
                live.setQuery(words)
            }
            #if DEBUG
            // `-search harbor` searches at launch, for screenshots and UI checks.
            let arguments = ProcessInfo.processInfo.arguments
            if model.query.isEmpty, let i = arguments.firstIndex(of: "-search"), arguments.indices.contains(i + 1) {
                model.setQuery(arguments[i + 1]); live.setQuery(arguments[i + 1])
            }
            #endif
        }
        .noticeAlert($model.notice)
    }

    private func url(_ path: String?, _ size: ImageSize, on server: SearchSource?) -> URL? {
        services.library.imageURL(path, size: size, on: server)
    }

    /// The servers still being asked and those not listed, as tiles for the end of the last row.
    private var tail: [SearchCard] {
        (model.stillAsking.map { [SearchCard.asking($0)] } ?? []) + model.unlisted.map(SearchCard.unlisted)
    }

    @ViewBuilder private var content: some View {
        switch model.phase {
        case .idle:
            start
        case .loading:
            TVSpinner().frame(maxWidth: .infinity).padding(.top, 120)
        case .noMatches(let query):
            // Nothing in the library, but On live TV may still have some.
            if case .results = live.phase {} else {
                let words = model.noMatchesWords(query)
                VStack(alignment: .leading, spacing: 16) {
                    Text(words.title).font(.tv(.headline)).foregroundStyle(Palette.text)
                    Text(words.message).font(.tv(.body)).foregroundStyle(Palette.textSecondary).frame(maxWidth: 1100, alignment: .leading)
                }
                .padding(.horizontal, TVLayout.gutter)
                .padding(.top, 20)
                .accessibilityElement(children: .combine)
                .accessibilityIdentifier("search-no-matches")
            }
            // A server that didn't answer may have had it: its line, with Try again.
            if !tail.isEmpty {
                HStack(alignment: .top, spacing: TVLayout.cardSpacing) {
                    ForEach(tail) { card($0) }
                }
                .padding(.horizontal, TVLayout.gutter)
                .frame(maxWidth: .infinity, alignment: .leading)
                .focusSection()
            }
        case .failed(let message):
            TVErrorState(title: "Couldn't search", message: message, retry: model.retry)
                .padding(.top, 40)
        case .results(let listing):
            results(listing)
        }
    }

    /// Before anything is typed: the searches made lately, one move down from
    /// the keyboard, with Clear last; else what Search finds.
    @ViewBuilder private var start: some View {
        let words = model.idleWords
        if model.recentQueries.isEmpty {
            VStack(alignment: .leading, spacing: 16) {
                Text(words.title).font(.tv(.headline)).foregroundStyle(Palette.text)
                Text(words.message).font(.tv(.body)).foregroundStyle(Palette.textSecondary)
            }
            .padding(.horizontal, TVLayout.gutter)
            .padding(.top, 20)
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("search-start")
        } else {
            VStack(alignment: .leading, spacing: 24) {
                RowHeading(title: SearchWords.recentSearches)
                RecentSearchChips(queries: model.recentQueries, choose: { query in
                    model.selectRecentQuery(query)
                    live.setQuery(query)
                }, clear: model.clearRecentQueries)
                Text(words.message).font(.tv(.body)).foregroundStyle(Palette.muted).padding(.top, 24)
            }
            .padding(.horizontal, TVLayout.gutter)
            .padding(.top, 20)
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("search-recent")
        }
    }

    /// Movies and TV shows in one row, then Episodes, the tail's tiles at the end of the last.
    @ViewBuilder private func results(_ listing: SearchListing) -> some View {
        let titles = listing.titles.map(SearchCard.title)
        let episodes = listing.episodes.map(SearchCard.episode)
        VStack(alignment: .leading, spacing: Self.rowSpacing) {
            if !titles.isEmpty {
                ShelfRow(title: SearchWords.moviesAndShows, detail: formatCount(titles.count), items: titles + (episodes.isEmpty ? tail : [])) { card($0) }
            }
            if !episodes.isEmpty {
                ShelfRow(title: "Episodes", detail: formatCount(episodes.count), items: episodes + tail) { card($0) }
            }
        }
    }

    @ViewBuilder private func card(_ card: SearchCard) -> some View {
        switch card {
        case .title(let found): posterButton(found)
        case .episode(let found): episodeButton(found)
        case .asking(let line): StillAskingTile(line: line)
        case .unlisted(let server): unlistedTile(server)
        }
    }

    private func posterButton(_ found: FoundTitle) -> some View {
        Button { model.open(found) } label: {
            GridPoster(item: found.item, imageURL: url(found.posterPath, .w500, on: found.server), line: found.item.tvSearchLine,
                       server: found.server?.name, centred: false)
        }
        .buttonStyle(.borderless)
        // Play/Pause plays on the server in use; on another server it opens, as Select does.
        .playPauseCard(found.server == nil ? .card(found.item) : .select) { model.open(found) }
        .accessibilityHint(found.server.map { "Switches to \($0.name)" } ?? "")
        .contextMenu {
            // Saving and marking watched are the server in use's.
            if found.server == nil { TVWatchlistMenuButton(found.item) }
            if found.server == nil, found.item.watchTarget != nil {
                WatchToggleButton(watched: found.watched) { await model.setWatched(found.item, $0) }
            }
        }
    }

    /// A title not in the library: its poster, dimmed, with Request, Requested or Downloading on it.
    private func requestCard(_ item: Requestable) -> some View {
        Button { model.openRequest(item) } label: {
            RequestCard(item: item, imageURL: services.library.imageURL(item.posterPath, size: .w500))
        }
        .buttonStyle(.borderless)
        .accessibilityHint(item.state == .requestable ? "Opens the request" : "")
    }

    private func episodeButton(_ found: FoundEpisode) -> some View {
        let episode = found.episode
        return Button { model.open(found) } label: {
            WideCard(
                title: episode.title,
                line: episode.searchLine(),
                imageURL: url(episode.stillPath ?? episode.showBackdropPath, .w780, on: found.server),
                seed: episode.showTitle,
                missing: !episode.hasFile,
                server: found.server?.name
            ) {
                if !episode.hasFile {
                    TVBadge("Not in library", style: .warning).pinned(.topLeading)
                } else if episode.isNew {
                    TVBadge("New", style: .gold).pinned(.topLeading)
                }
                if episode.watched { WatchedCheck(size: 40).pinned(.topTrailing) }
                TVBadge(episode.codeBadge).pinned(.bottomLeading)
            }
        }
        .buttonStyle(.borderless)
        .accessibilityHint(found.server.map { "Switches to \($0.name)" } ?? "Opens the episode on its show")
        .playPauseCard(found.server == nil ? .card(episode) : .select) { model.open(found) }
        .contextMenu {
            if found.server == nil {
                if episode.playRequest != nil {
                    Button { model.play(episode) } label: { Label("Play", systemImage: "play.fill") }
                }
                WatchToggleButton(watched: episode.watched) { await model.setWatched(episode, $0) }
            }
        }
    }

    /// A server whose titles aren't listed: its line, and Try again on Select where it was asked and didn't answer.
    @ViewBuilder private func unlistedTile(_ server: UnlistedServer) -> some View {
        if server.canRetry {
            Button { model.retry(server) } label: {
                VStack(alignment: .leading, spacing: 14) {
                    Text(server.line).fixedSize(horizontal: false, vertical: true)
                    Text(SearchWords.tryAgain).font(.tv(.caption2, .bold))
                }
            }
            .buttonStyle(SearchTileStyle())
            .accessibilityLabel("\(server.line) \(SearchWords.tryAgain)")
            .accessibilityIdentifier("search-unlisted-\(server.id)")
        } else {
            Text(server.line)
                .fixedSize(horizontal: false, vertical: true)
                .modifier(SearchTile(focused: false, takesFocus: false))
                .accessibilityIdentifier("search-unlisted-\(server.id)")
        }
    }
}

/// The recent searches as chips, newest first, then Clear. A view of its
/// own, so a focus move along it redraws it and not the whole of Search.
private struct RecentSearchChips: View {
    let queries: [String]
    let choose: (String) -> Void
    let clear: () -> Void
    /// The search with focus, or `clearKey` for Clear.
    @FocusState private var focus: String?
    private static let clearKey = "\u{0}clear"

    var body: some View {
        // Across the width, so Down from any key of the keyboard lands here.
        HStack(spacing: 16) {
            ForEach(queries, id: \.self) { query in
                Button(query) { choose(query) }
                    .buttonStyle(TVChipStyle(selected: false))
                    .focused($focus, equals: query)
                    .accessibilityIdentifier("search-recent-\(query)")
            }
            Button(SearchWords.clearRecent, action: clear)
                .buttonStyle(ClearChipStyle())
                .padding(.leading, 24)
                .focused($focus, equals: Self.clearKey)
                .accessibilityLabel(SearchWords.clearRecentLabel)
                .accessibilityIdentifier("search-recent-clear")
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .focusSection()
        .onChange(of: focus) { old, new in
            // Coming down from the keyboard lands on the newest search, never on Clear:
            // the focus engine would pick whichever is under the key that had focus.
            guard old == nil, let new, let first = queries.first, new != first else { return }
            focus = first
        }
    }
}

/// Clear, after the recent searches: a chip drawn as an outline, set apart from the searches.
private struct ClearChipStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View { Styled(configuration: configuration) }

    private struct Styled: View {
        let configuration: Configuration
        @Environment(\.isFocused) private var focused

        var body: some View {
            configuration.label
                .font(.tv(.body, .semibold))
                .lineLimit(1)
                .foregroundStyle(focused ? Palette.ground : Palette.textSecondary)
                .padding(.horizontal, 26)
                .frame(height: 64)
                .background(focused ? Palette.text : .clear, in: Capsule())
                .overlay { if !focused { Capsule().strokeBorder(Color(hex: 0x8C8C92), lineWidth: 2) } }
                .scaleEffect(focused && !configuration.isPressed ? 1.07 : 1)
                .shadow(color: .black.opacity(focused ? 0.6 : 0), radius: focused ? 25 : 0, y: focused ? 20 : 0)
                .animation(.easeOut(duration: 0.15), value: focused)
        }
    }
}

/// "Still asking Cabin server…", with a spinner: a tile at the end of the last row.
private struct StillAskingTile: View {
    let line: String

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            ProgressView()
            Text(line).fixedSize(horizontal: false, vertical: true)
        }
        .modifier(SearchTile(focused: false, takesFocus: false))
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("search-still-asking")
    }
}

/// The tile at the end of a search row, for a server still being asked or
/// one that isn't listed: a 16:9 card's size, with a line of words. One
/// that takes focus (Try again) looks like a card; one that doesn't is
/// plain words, dashed round, so nobody expects it to.
private struct SearchTile: ViewModifier {
    let focused: Bool
    var takesFocus = true

    func body(content: Content) -> some View {
        content
            .font(.tv(.caption2))
            .lineSpacing(4)
            .foregroundStyle(focused ? Palette.ground : Color.tvTitle)
            .padding(.horizontal, 26)
            .frame(width: TVCardWidth.wide, height: 180, alignment: .leading)
            .background(focused ? Palette.text : takesFocus ? Palette.surfaceRaised : .clear, in: RoundedRectangle(cornerRadius: 14))
            .overlay {
                if !focused {
                    RoundedRectangle(cornerRadius: 14).strokeBorder(Palette.outlineStrong, style: StrokeStyle(lineWidth: 2, dash: takesFocus ? [] : [8, 6]))
                }
            }
            .focusLift(focused, scale: 1.08)
    }
}

/// A tile that takes focus (Try again).
private struct SearchTileStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View { Styled(configuration: configuration) }

    private struct Styled: View {
        let configuration: Configuration
        @Environment(\.isFocused) private var focused

        var body: some View {
            configuration.label
                .modifier(SearchTile(focused: focused))
                .scaleEffect(configuration.isPressed ? 0.97 : 1)
        }
    }
}

/// A title not in the library: its poster, dimmed, with Request, Requested or
/// Downloading on it; focused, the title and its line under it.
private struct RequestCard: View {
    let item: Requestable
    let imageURL: URL?
    @Environment(\.isFocused) private var focused
    @Environment(\.displayScale) private var scale

    private let width = TVCardWidth.gridPoster

    var body: some View {
        let height = (width * 1.5).rounded()
        Artwork(url: imageURL, seed: item.title, maxPixel: cardPixels(width: width, height: height, scale: scale))
            .frame(width: width, height: height)
            .overlay { if imageURL == nil { PlaceholderTitle(title: item.title) } }
            .opacity(focused ? 1 : 0.6)
            .overlay { tag.pinned(.topLeading) }
            .overlay { FocusSheen(focused: focused) }
            .clipShape(RoundedRectangle(cornerRadius: 14))
            .focusLift(focused)
            .overlay(alignment: .topLeading) {
                if focused { FocusedCaption(title: item.title, line: item.searchLine, centred: false).offset(y: height + 26) }
            }
            .frame(width: width, alignment: .leading)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel([item.title, item.searchLine, stateWords].joined(separator: ", "))
    }

    private var stateWords: String {
        switch item.state {
        case .requestable: "Request"
        case .requested, .available: "Requested"
        case .downloading: "Downloading"
        }
    }

    @ViewBuilder private var tag: some View {
        switch item.state {
        case .requestable: TVBadge("Request", style: .gold)
        case .requested, .available: TVBadge("Requested")
        case .downloading: TVBadge("Downloading")
        }
    }
}
