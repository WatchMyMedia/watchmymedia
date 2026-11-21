import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// Search: the tvOS search field and keyboard (Siri
/// dictation works on the field), then TV Shows and Movies as posters and
/// Episodes as 16:9 cards; "Search your libraries" before anything is typed
/// and "No matches" after. An episode opens its show with its row expanded.
/// From API level 5, a Not in your library row
/// follows, each poster with Request, Requested or Downloading; Request
/// opens the full-screen request dialog. From API level 15 with listings,
/// On Live TV follows the library's rows.
///
/// Signed in to several servers, it looks in all of them (`SearchModel`):
/// the server in use's cards come first in each row and carry no server; a
/// card from another server names it under its title, and Select on one
/// switches this Apple TV to that server and opens the title there. A
/// server still being asked, and one whose titles aren't listed, are a tile
/// at the end of the last row, with Try again where it didn't answer.
struct SearchScreen: View {
    @Environment(AppServices.self) private var services
    @Environment(Navigator.self) private var navigator
    @Environment(LiveExtrasStore.self) private var extras
    @Environment(ServersModel.self) private var servers

    var body: some View {
        SearchView(model: SearchModel(library: services.library, navigator: navigator, requests: services.requests,
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

    /// Posters are narrower across servers, to leave room for the server's line under a title.
    private var posterWidth: CGFloat { model.acrossServers ? 160 : 200 }

    var body: some View {
        TabPage {
            VStack(alignment: .leading, spacing: 30) {
                content
                // On Live TV, after the library's rows.
                TVLiveSearchSection(model: live)
                if !model.notInLibrary.isEmpty {
                    ShelfRow(title: "Not in your library", detail: model.notInLibraryDetail, items: model.notInLibrary, spacing: 40) { requestCard($0) }
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
            let words = model.idleWords
            TVEmptyState(systemImage: "magnifyingglass", title: words.title, message: words.message)
                .padding(.top, 80)
        case .loading:
            ProgressView().frame(maxWidth: .infinity).padding(.top, 120)
        case .noMatches(let query):
            // Nothing in the library, but On Live TV may still have some.
            if case .results = live.phase {} else {
                let words = model.noMatchesWords(query)
                VStack(spacing: 14) {
                    Text(words.title).font(.system(size: 34, weight: .bold)).foregroundStyle(Palette.text)
                    Text(words.message).font(.system(size: 22)).foregroundStyle(Palette.muted)
                }
                .frame(maxWidth: .infinity)
                .padding(.top, 100)
                .accessibilityElement(children: .combine)
                .accessibilityIdentifier("search-no-matches")
            }
            // A server that didn't answer may have had it: its line, with Try again.
            if !tail.isEmpty {
                HStack(alignment: .top, spacing: 24) {
                    ForEach(tail) { card($0) }
                }
                .frame(maxWidth: .infinity)
                .padding(.top, 20)
                .focusSection()
            }
        case .failed(let message):
            TVErrorState(title: "Couldn't search", message: message, retry: model.retry)
                .padding(.top, 60)
        case .results(let listing):
            results(listing)
        }
    }

    /// The rows, with the tail's tiles at the end of the last one.
    @ViewBuilder private func results(_ listing: SearchListing) -> some View {
        let shows = listing.shows.map(SearchCard.title), movies = listing.movies.map(SearchCard.title)
        let episodes = listing.episodes.map(SearchCard.episode)
        // The last row with anything in it takes the tiles.
        let last = !episodes.isEmpty ? 2 : !movies.isEmpty ? 1 : 0
        let showCards = shows + (last == 0 ? tail : []), movieCards = movies + (last == 1 ? tail : [])
        VStack(alignment: .leading, spacing: 30) {
            if !shows.isEmpty && !movies.isEmpty && showCards.count + movieCards.count <= 7 {
                // Few enough to sit side by side, as the artboard has them.
                HStack(alignment: .top, spacing: 40) {
                    group("TV Shows", count: shows.count) { cards(showCards) }
                    group("Movies", count: movies.count) { cards(movieCards) }
                }
                .padding(.horizontal, TVLayout.gutter)
                .focusSection()
            } else {
                if !showCards.isEmpty {
                    ShelfRow(title: "TV Shows", detail: formatCount(shows.count), items: showCards) { card($0) }
                }
                if !movieCards.isEmpty {
                    ShelfRow(title: "Movies", detail: formatCount(movies.count), items: movieCards) { card($0) }
                }
            }
            if !episodes.isEmpty {
                ShelfRow(title: "Episodes", detail: formatCount(episodes.count), items: episodes + tail) { card($0) }
            }
        }
        .padding(.top, 20)
    }

    private func group(_ title: String, count: Int, @ViewBuilder content: () -> some View) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            RowHeading(title: title, detail: formatCount(count))
            content().padding(.vertical, 30)
        }
    }

    private func cards(_ items: [SearchCard]) -> some View {
        HStack(alignment: .top, spacing: 24) {
            ForEach(items) { card($0) }
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
            PosterCard(item: found.item, imageURL: url(found.posterPath, .w500, on: found.server), width: posterWidth, line: found.item.searchLine,
                       server: found.server?.name)
        }
        .buttonStyle(.borderless)
        // Play/Pause plays on the server in use; on another server it opens, as Select does.
        .playPauseCard(found.server == nil ? .card(found.item) : .select) { model.open(found) }
        .accessibilityHint(found.server.map { "Switches to \($0.name)" } ?? "")
        .contextMenu {
            // Marking watched is the server in use's.
            if found.server == nil, found.item.watchTarget != nil {
                WatchToggleButton(watched: found.watched) { await model.setWatched(found.item, $0) }
            }
        }
    }

    /// A TMDB result: its poster, title and line, and Request, Requested or Downloading.
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
                if episode.isNew { TVBadge("New", style: .gold).pinned(.topLeading, padding: 10) }
                if !episode.hasFile { TVBadge("Not in library", style: .warning).pinned(.topLeading, padding: 10) }
                if episode.watched { WatchedCheck(size: 28).pinned(.bottomTrailing, padding: 10) }
                TVBadge(episode.codeBadge).pinned(.bottomLeading, padding: 10)
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
                    Text(SearchWords.tryAgain)
                        .font(.system(size: 18, weight: .semibold))
                        .padding(.horizontal, 18)
                        .frame(minHeight: 44)
                        .background(.white.opacity(0.14), in: RoundedRectangle(cornerRadius: 12))
                }
            }
            .buttonStyle(SearchTileStyle())
            .accessibilityLabel("\(server.line) \(SearchWords.tryAgain)")
            .accessibilityIdentifier("search-unlisted-\(server.id)")
        } else {
            Text(server.line)
                .fixedSize(horizontal: false, vertical: true)
                .modifier(SearchTile(focused: false))
                .accessibilityIdentifier("search-unlisted-\(server.id)")
        }
    }
}

/// "Still asking Cabin server…", with a spinner: a tile at the end of the last row.
private struct StillAskingTile: View {
    let line: String

    var body: some View {
        VStack(spacing: 14) {
            ProgressView()
            Text(line).multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity)
        .modifier(SearchTile(focused: false))
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("search-still-asking")
    }
}

/// The tile at the end of a search row, for a server still being asked or
/// one that isn't listed: as wide and tall as an episode card's picture,
/// dashed, with a line of words.
private struct SearchTile: ViewModifier {
    let focused: Bool

    func body(content: Content) -> some View {
        content
            .font(.system(size: 20))
            .lineSpacing(4)
            .foregroundStyle(focused ? Palette.ground : Color.tvTitle)
            .padding(.horizontal, 26)
            .frame(width: 340, height: 180, alignment: .leading)
            .background(focused ? Palette.text : .white.opacity(0.05), in: RoundedRectangle(cornerRadius: 16))
            .overlay {
                if !focused {
                    RoundedRectangle(cornerRadius: 16).strokeBorder(Palette.outlineStrong, style: StrokeStyle(lineWidth: 1, dash: [6, 4]))
                }
            }
            .focusRim(focused, cornerRadius: 16, scale: 1.05)
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

/// A not-in-library poster: the poster, the
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
