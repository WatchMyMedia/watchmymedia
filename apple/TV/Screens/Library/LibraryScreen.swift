import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// The TV Shows or Movies grid: the heading with its count and four
/// controls on one line (Sort, Unwatched, Genre and More filters, which
/// holds the rest of the filters and the decade), then seven posters a row,
/// a page at a time, the third row's top in sight. Only the focused poster
/// carries words, under it. Long press marks a poster watched. From API
/// level 22 the library's rows come first, then Edit rows, then the grid as
/// All movies or All shows.
/// Sorted by title, the letter index runs down the right edge, inside the
/// safe area: right from the last column enters it at the focused poster's
/// letter, the grid follows the letters up and down, Select or left lands on
/// the letter's first title, and Menu goes back to the poster. Page Up and
/// Page Down move two rows in any sort. Down from the controls lands on the
/// poster focused last, or the first. A row's See all, on Home or in the
/// rows above, opens the grid sorted the row's way, with focus on its first
/// poster. The sort and filters the person leaves the grid with are kept on
/// this Apple TV, for this server and person, and the grid opens with them.
struct LibraryScreen: View {
    let type: LibraryType
    @Environment(AppServices.self) private var services
    @Environment(Navigator.self) private var navigator

    var body: some View {
        LibraryView(model: LibraryModel(library: services.library, navigator: navigator, type: type, rows: services.rows, letterJump: .extend,
                                        oneCard: OneCardContext(services: services, choices: CopyChoices()),
                                        choices: LibraryChoices(services: services, defaults: LibraryChoiceDefaults.shared)))
    }
}

/// Where a library's kept sort and filters live. On the fakes they last the
/// launch, so one UI test's choice doesn't open the next test's grid sorted
/// or filtered; `-fakesKeepLibraryChoices` keeps them between launches, as
/// a real server's are.
private enum LibraryChoiceDefaults {
    @MainActor static let shared: UserDefaults = {
        #if DEBUG
        let arguments = ProcessInfo.processInfo.arguments
        if arguments.contains(where: { $0.hasPrefix("-fakes") }), !arguments.contains("-fakesKeepLibraryChoices") {
            let name = "media.watchmy.fakes.libraryChoices"
            let defaults = UserDefaults(suiteName: name) ?? .standard
            defaults.removePersistentDomain(forName: name)
            return defaults
        }
        #endif
        return .standard
    }()
}

private struct LibraryView: View {
    @State var model: LibraryModel
    @Environment(AppServices.self) private var services
    @State private var editingRows = false
    @FocusState private var focus: GridFocus?
    /// The poster focus left for the letter index; Menu there goes back to it.
    @State private var returnID: Int?
    /// The poster focused last, where focus lands coming back into the grid.
    @State private var lastItem: Int?
    /// Focus the screen moved itself, which the index's rules leave alone.
    @State private var placedFocus: GridFocus?
    /// A letter's jump, waiting for focus to rest on it.
    @State private var follow: Task<Void, Never>?
    /// The posters the grid has drawn, which `focusItem` waits for.
    @State private var drawn = DrawnPosters()

    private static let columnCount = 7
    /// Between the grid's rows: room for the focused poster to grow and its words to sit under it.
    private static let rowSpacing: CGFloat = 100

    /// Seven posters at tvOS's seven-across width; a little closer beside the letter index, to make room for it.
    private var columns: [GridItem] {
        let spacing = model.letterIndex == nil ? TVLayout.cardSpacing : 32
        return Array(repeating: GridItem(.fixed(TVCardWidth.gridPoster), spacing: spacing, alignment: .top), count: Self.columnCount)
    }

    var body: some View {
        ScrollViewReader { proxy in
            page(proxy)
                .overlay { strip(proxy) }
                .onChange(of: model.scrollRequestID) {
                    // The grid follows the letters: the letter's row near the top.
                    if let target = model.scrollTargetID { withAnimation { proxy.scrollTo(target, anchor: UnitPoint(x: 0.5, y: 0.32)) } }
                }
                .onChange(of: focus) { old, new in focusMoved(from: old, to: new, proxy) }
                // Home's See all opened the tab: the grid has loaded sorted the row's way, so focus goes to
                // its first poster, below the tab's own rows.
                .onChange(of: model.gridJump) { Task { await focusFirstItem(proxy) } }
                .onPageKeys(enabled: gridFocused) { page(by: $0, proxy) }
        }
        #if DEBUG
        .task {
            if let value = ProcessInfo.processInfo.arguments.drop(while: { $0 != "-librarySort" }).dropFirst().first,
               let sort = ItemSort(rawValue: value) { await model.setSort(sort) }
        }
        #endif
    }

    private func page(_ proxy: ScrollViewProxy) -> some View {
        TabPage {
            VStack(alignment: .leading, spacing: 0) {
                if model.canEditRows {
                    arrangedTop(proxy)
                }
                controls
                    .padding(.top, model.canEditRows ? 40 : 16)
                grid
            }
            .padding(.bottom, TVLayout.safeBottom)
        }
        .task { await model.run() }
        .messagePill($model.message)
        .noticeAlert($model.notice)
        .fullScreenCover(isPresented: $editingRows) {
            if let screen = model.screen { TVRowEditor(screen: screen, screenName: model.title) }
        }
    }

    /// From API level 22: the tab's name, its rows and Edit rows, above the grid.
    private func arrangedTop(_ proxy: ScrollViewProxy) -> some View {
        VStack(alignment: .leading, spacing: 40) {
            Text(model.title)
                .font(.tv(.title3))
                .foregroundStyle(Palette.text)
                .accessibilityAddTraits(.isHeader)
                .padding(.horizontal, TVLayout.gutter)
                .padding(.top, 16)
            ForEach(model.shownRows) { row in
                TVArrangedRow(row: row, actions: rowActions(proxy))
            }
            TVEditRowsButton { editingRows = true }
                .padding(.horizontal, TVLayout.gutter)
                .frame(maxWidth: .infinity, alignment: .leading)
                .focusSection()
        }
    }

    /// The rows' card actions. A row's See all opens a list's page, or sets
    /// the grid below the row's way and moves focus to its first poster.
    private func rowActions(_ proxy: ScrollViewProxy) -> TVRowActions {
        TVRowActions(
            open: { model.open($0) },
            openEpisode: { model.open($0) },
            playEpisode: { model.play($0) },
            resume: { model.resume($0) },
            setWatched: { await model.setWatched($0, $1) },
            seeAll: { row in
                guard let link = row.seeAll, let words = model.seeAll(row) else { return nil }
                return TVRowSeeAll(words: words) {
                    Task {
                        await model.open(link)
                        if case .library = link { await focusFirstItem(proxy) }
                    }
                }
            }
        )
    }

    /// The grid's heading and count, then Sort, Unwatched, Genre and More
    /// filters, on one line: across the width, so Up from any poster of the
    /// first row reaches the control nearest above it.
    private var controls: some View {
        HStack(alignment: .center, spacing: 16) {
            Text(model.gridHeading)
                .font(.tv(.title3))
                .foregroundStyle(Palette.text)
                .lineLimit(1)
                .fixedSize()
                .accessibilityAddTraits(.isHeader)
                .padding(.trailing, 8)
            Text(model.gridCountLine ?? " ")
                .font(.tv(.body))
                .foregroundStyle(Palette.muted)
                .lineLimit(1)
                .layoutPriority(-1)
                .padding(.trailing, 20)
            if !model.noLibrary {
                // Each control whole: the count gives way first.
                sort.fixedSize()
                if model.filters.contains(.unwatched) { unwatched.fixedSize() }
                if !model.genres.isEmpty { genre.fixedSize() }
                if !model.moreFilters.isEmpty || !model.decades(includingShows: true).isEmpty || !model.ratingOptions.isEmpty { moreFilters.fixedSize() }
            }
        }
        .padding(.horizontal, TVLayout.gutter)
        .frame(maxWidth: .infinity, minHeight: 64, alignment: .leading)
        .focusSection()
        .overlay(alignment: .bottomLeading) { otherServersLine.offset(y: 52) }
        // The grid sits lower by the line's height in every state, so it doesn't jump when the line comes and goes.
        .padding(.bottom, model.merged == nil ? 0 : 52)
    }

    /// Other servers, under the count: still asking, one that didn't answer
    /// with Try again beside it, or one that can't apply a filter.
    @ViewBuilder private var otherServersLine: some View {
        if let line = model.otherServersLine {
            HStack(spacing: 24) {
                Text(line).font(.tv(.caption1)).foregroundStyle(Palette.muted).lineLimit(1)
                    .accessibilityIdentifier("library-other-servers")
                if model.merged?.retryable != nil {
                    Button("Try again") { Task { await model.retryOtherServer() } }
                        .buttonStyle(TVChipStyle(selected: false))
                        .accessibilityIdentifier("library-other-servers-retry")
                }
            }
            .padding(.horizontal, TVLayout.gutter)
            .focusSection()
        }
    }

    private var sort: some View {
        Menu {
            Picker("Sort by", selection: Binding(get: { model.sort }, set: { sort in Task { await model.setSort(sort) } })) {
                ForEach(model.sorts, id: \.self) { Text($0.label).tag($0) }
            }
        } label: {
            ChipMenuLabel(title: model.sortLabel)
        }
        .buttonStyle(TVChipStyle(selected: false))
        .accessibilityLabel("Sort by")
        .accessibilityValue(model.sort.label)
    }

    private var unwatched: some View {
        Button(model.unwatchedChipLabel) { Task { await model.setUnwatchedOnly(!model.unwatchedOnly) } }
            .buttonStyle(TVChipStyle(selected: model.unwatchedOnly))
            .accessibilityIdentifier("library-unwatched")
    }

    private var genre: some View {
        Menu {
            Picker(TVLibraryWords.genre, selection: Binding(get: { model.genre }, set: { genre in Task { await model.chooseGenre(genre) } })) {
                Text(TVLibraryWords.anyGenre).tag(String?.none)
                ForEach(model.genres, id: \.self) { Text($0).tag(Optional($0)) }
            }
        } label: {
            ChipMenuLabel(title: model.genreChipLabel)
        }
        .buttonStyle(TVChipStyle(selected: model.genre != nil))
        .accessibilityLabel(TVLibraryWords.genre)
        .accessibilityValue(model.genre ?? TVLibraryWords.anyGenre)
    }

    /// The filters that aren't Unwatched, as switches, then the decade.
    private var moreFilters: some View {
        Menu {
            ForEach(model.moreFilters, id: \.self) { filter in
                Toggle(filter.label, isOn: Binding(get: { model.filter == filter }, set: { on in Task { await model.setMoreFilter(filter, on: on) } }))
            }
            let decades = model.decades(includingShows: true)
            if !decades.isEmpty {
                Section(TVLibraryWords.decade) {
                    Picker(TVLibraryWords.decade, selection: Binding(get: { model.decade }, set: { decade in Task { await model.chooseDecade(decade) } })) {
                        Text(TVLibraryWords.anyDecade).tag(Int?.none)
                        ForEach(decades, id: \.self) { Text(decadeLabel($0)).tag(Optional($0)) }
                    }
                }
            }
            // From API level 34: one switch a rating, any number on.
            if !model.ratingOptions.isEmpty {
                Section(TVLibraryWords.rating) {
                    ForEach(model.ratingOptions, id: \.self) { rating in
                        Toggle(model.ratingChoiceLabel(rating), isOn: Binding(get: { model.contentRatings.contains(rating) }, set: { on in Task { await model.setRating(rating, on: on) } }))
                            .accessibilityLabel(model.ratingChoiceLabel(rating))
                    }
                }
            }
        } label: {
            Text(model.moreFiltersLabel).font(.tv(.body, .semibold))
        }
        .buttonStyle(TVChipStyle(selected: model.moreFiltersOn))
        .accessibilityLabel(TVLibraryWords.moreFilters)
        .accessibilityValue(moreFiltersValue)
        .accessibilityIdentifier("library-more-filters")
    }

    /// What More filters has on, for VoiceOver: "Continuing, 2010s", or "None".
    private var moreFiltersValue: String {
        let on = (model.moreFilters.contains(model.filter) ? [model.filter.label] : []) + [model.decade.map(decadeLabel)].compactMap { $0 }
            + model.contentRatings.map(contentRatingName)
        return on.isEmpty ? "None" : on.joined(separator: ", ")
    }

    @ViewBuilder private var grid: some View {
        switch model.state {
        case .loading:
            TVSpinner().frame(maxWidth: .infinity).padding(.top, 160)
        case .failed(let message):
            TVErrorState(message: message, retry: model.retry).padding(.top, 60)
        case .loaded(let grid):
            if grid.items.isEmpty {
                empty
            } else {
                LazyVGrid(columns: columns, alignment: .leading, spacing: Self.rowSpacing) {
                    ForEach(model.gridItems, id: \.gridID) { item in
                        Button { model.open(item) } label: {
                            // A poster from another server says so in its line ("On Cabin · 2024 · 1 h 46 min").
                            GridPoster(item: item, imageURL: services.library.imageURL(item.posterPath, size: .w500, on: item.server),
                                       line: [item.server.flatMap { model.serverName($0) }.map(OneCardWords.on), item.tvGridLine()].compactMap { $0 }.joined(separator: " · "))
                                .overlay(alignment: .top) {
                                    // Where Select on the focused letter lands.
                                    if item.id == landingID {
                                        RoundedRectangle(cornerRadius: 14).stroke(Palette.gold.opacity(0.55), lineWidth: 3)
                                            .frame(height: (TVCardWidth.gridPoster * 1.5).rounded())
                                    }
                                }
                        }
                        .buttonStyle(.borderless)
                        .focused($focus, equals: item.server == nil ? .item(item.id) : .other(item.cardID))
                        .playPauseCard(.card(item)) { model.open(item) }
                        .contextMenu {
                            TVWatchlistMenuButton(item)
                            if item.watchTarget != nil {
                                WatchToggleButton(watched: item.watched) { await model.setWatched(item, $0) }
                            }
                        }
                        .onAppear {
                            // The posters focus is asked for are the server in use's, by id.
                            if item.server == nil { drawn.ids.insert(item.id) }
                            Task { await model.itemAppeared(item) }
                        }
                        .onDisappear { if item.server == nil { drawn.ids.remove(item.id) } }
                    }
                }
                .padding(.leading, TVLayout.gutter)
                .padding(.top, 46)
                // The grid's own width, not the screen's: as a focus section it then lies left of
                // the letter index, so Left from a letter level with the gap between two rows
                // still finds the grid.
                .focusSection()
                more
            }
        }
    }

    @ViewBuilder private var more: some View {
        if let error = model.moreError {
            VStack(spacing: 20) {
                Text(error).font(.tv(.body)).foregroundStyle(Palette.textSecondary)
                Button("Try again") { Task { await model.retryMore() } }.buttonStyle(TVButtonStyle())
            }
            .frame(maxWidth: .infinity)
            .padding(.top, 100)
        } else if model.loadingMore {
            TVSpinner().frame(maxWidth: .infinity).padding(.top, 100)
        }
    }

    // MARK: The letter index

    private var gridFocused: Bool {
        if case .item = focus { return true }
        return false
    }

    private var items: [BrowseItem] { model.state.value?.items ?? [] }

    /// The letter of the poster `id`, from its place in the whole list.
    private func letter(of id: Int) -> String? {
        guard let grid = model.state.value, let place = grid.items.firstIndex(where: { $0.id == id }) else { return nil }
        return model.letterIndex?.letter(at: grid.offset + place)
    }

    /// The focused poster's letter.
    private var currentLetter: String? {
        guard case .item(let id) = focus else { return nil }
        return letter(of: id)
    }

    /// The first title of the focused letter, when it is held: where Select lands.
    private var landingID: Int? {
        guard case .letter(let letter) = focus else { return nil }
        return firstID(of: letter)
    }

    private func firstID(of letter: String) -> Int? {
        guard let grid = model.state.value, let offset = model.letterIndex?.entry(letter)?.offset,
              offset >= grid.offset, offset < grid.offset + grid.items.count else { return nil }
        return grid.items[offset - grid.offset].id
    }

    @ViewBuilder private func strip(_ proxy: ScrollViewProxy) -> some View {
        // With rows above the grid, the index shows once the grid has focus.
        if let index = model.letterIndex, !model.noLibrary, !model.canEditRows || focus != nil {
            LetterStrip(
                index: index,
                noun: model.noun,
                current: currentLetter,
                active: focus != nil,
                focus: $focus,
                select: { entry in land(on: entry, proxy) },
                back: { goBack(proxy) }
            )
        }
    }

    /// Focus moved by the remote: into the index it starts at the poster's
    /// letter, along it the grid follows, out of it to the left it lands on
    /// the letter's first title, and coming into the grid from above it lands
    /// on the poster focused last.
    private func focusMoved(from old: GridFocus?, to new: GridFocus?, _ proxy: ScrollViewProxy) {
        let previous = lastItem
        if case .item(let id) = new { lastItem = id }
        if let new, new == placedFocus {
            placedFocus = nil
            return
        }
        if case .item = placedFocus {
            // Asked for a poster from the index, focus went to another one:
            // tvOS 27.2 did that on CI's slower runner, before the poster was
            // drawn. Not a swipe left, so `focusItem` goes on asking.
            if case .letter = old, case .item = new { return }
            // The remote moved focus: `focusItem` stops asking.
            placedFocus = nil
        }
        switch (old, new) {
        case (.item(let id), .letter(let entered)):
            returnID = id
            if let current = letter(of: id), current != entered, model.letterIndex?.entry(current)?.isEmpty == false {
                place(.letter(current))
            }
        case (.letter, .letter(let letter)):
            follow?.cancel()
            guard let jump = model.letterIndex?.entry(letter)?.letterOffset else { return }
            follow = Task {
                // Let focus rest before loading: a swipe down the index passes many letters.
                try? await Task.sleep(for: .milliseconds(200))
                guard !Task.isCancelled else { return }
                await model.jump(to: jump)
            }
        case (.letter(let letter), .item):
            if let entry = model.letterIndex?.entry(letter) { land(on: entry, proxy) }
        case (nil, .item(let id)):
            // Coming down from the controls (or the rows above): the poster focused last,
            // where the focus engine would pick the one nearest the way it came.
            if let previous, previous != id, drawn.ids.contains(previous), items.contains(where: { $0.id == previous }) {
                lastItem = previous
                place(.item(previous))
            }
        default:
            break
        }
    }

    /// Select, or left, on a letter: its first title takes focus, once loaded.
    private func land(on entry: LetterIndex.Entry, _ proxy: ScrollViewProxy) {
        follow?.cancel()
        Task {
            if firstID(of: entry.letter) == nil, let jump = entry.letterOffset { await model.jump(to: jump) }
            guard let id = firstID(of: entry.letter) else { return }
            await focusItem(id, proxy)
        }
    }

    /// Menu on the index: back to the poster focus came from.
    private func goBack(_ proxy: ScrollViewProxy) {
        follow?.cancel()
        guard let id = returnID.flatMap({ id in items.contains { $0.id == id } ? id : nil }) ?? items.first?.id else { return }
        Task { await focusItem(id, proxy) }
    }

    /// Page Up or Page Down: two rows back or on, in the same column.
    private func page(by pages: Int, _ proxy: ScrollViewProxy) {
        guard case .item(let id) = focus, let place = items.firstIndex(where: { $0.id == id }),
              let target = GridPaging.target(from: place, pages: pages, columns: Self.columnCount, count: items.count) else { return }
        if target >= items.count - Self.columnCount * GridPaging.rows { Task { await model.loadMore() } }
        Task { await focusItem(items[target].id, proxy) }
    }

    /// The grid's first poster takes focus, once the grid has one.
    private func focusFirstItem(_ proxy: ScrollViewProxy) async {
        guard let id = items.first?.id else { return }
        await focusItem(id, proxy)
    }

    /// Scrolls the poster into the grid (it may not be drawn yet), then focuses it.
    /// Focus asked of a poster the grid hasn't drawn yet (Menu from the index
    /// once a letter's jump had scrolled the poster away) is dropped,
    /// unanswered, on tvOS 26.5, and went to another poster on tvOS 27.2 on
    /// CI's slower runner. So it scrolls the poster in at once, asks once the
    /// grid has drawn it (or after a second), and asks again until focus
    /// lands there, for up to two seconds, unless the remote moves focus first.
    private func focusItem(_ id: Int, _ proxy: ScrollViewProxy) async {
        let anchor = UnitPoint(x: 0.5, y: 0.32)
        withAnimation { proxy.scrollTo(id, anchor: anchor) }
        await Task.yield()
        let from = focus
        guard from != .item(id) else { return }
        for _ in 0..<20 where !drawn.ids.contains(id) {
            try? await Task.sleep(for: .milliseconds(50))
            guard !Task.isCancelled, focus == from else { return }
        }
        place(.item(id))
        // Still waiting: neither landed nor moved by the remote (`focusMoved` clears what was placed for both).
        func waiting() -> Bool { !Task.isCancelled && placedFocus == .item(id) }
        for _ in 0..<20 {
            try? await Task.sleep(for: .milliseconds(100))
            guard waiting() else { return }
            proxy.scrollTo(id, anchor: anchor)
            await Task.yield()
            guard waiting() else { return }
            place(.item(id))
        }
        if waiting() { placedFocus = nil }
    }

    private func place(_ target: GridFocus) {
        placedFocus = target
        focus = target
    }

    /// Nothing in the grid: why, and Clear filters when filters hid it all.
    private var empty: some View {
        let words = model.emptyMessage
        return VStack(alignment: .leading, spacing: 24) {
            Text(words.title).font(.tv(.title3)).foregroundStyle(Palette.text)
            Text(words.message).font(.tv(.body)).foregroundStyle(Palette.textSecondary).frame(maxWidth: 860, alignment: .leading)
            if model.isFiltered && !model.noLibrary {
                Button(TVLibraryWords.clearFilters) { Task { await model.clearFilters() } }
                    .buttonStyle(TVButtonStyle(kind: .primary))
                    .padding(.top, 16)
                    .accessibilityIdentifier("library-clear-filters")
            }
        }
        .padding(.horizontal, TVLayout.gutter)
        .padding(.top, 80)
        // Across the width, so Down from any control lands on Clear filters.
        .frame(maxWidth: .infinity, alignment: .leading)
        .focusSection()
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("library-empty")
    }
}

/// The ids of the posters the grid has drawn, kept outside SwiftUI's
/// observation: a poster scrolling in or out must not redraw the page.
@MainActor private final class DrawnPosters {
    var ids: Set<Int> = []
}
