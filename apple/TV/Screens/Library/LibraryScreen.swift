import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// The TV Shows or Movies grid (TvLibrary.dc.html): the title and the
/// library's counts, Sort as a menu, the filter and genre chips with a
/// Decade menu, and seven posters a row, a page at a time. Long press marks
/// a poster watched. From API level 22 the library's rows come first, then
/// Edit rows, then the grid as All movies or All shows (TvLibraryRows.dc.html).
/// Sorted by title, the letter index runs down the right edge
/// (TvLibraryAZ.dc.html): right from the last column enters it at the
/// focused poster's letter, the grid follows the letters up and down, Select
/// or left lands on the letter's first title, and Menu goes back to the
/// poster. Page Up and Page Down move two rows in any sort.
struct LibraryScreen: View {
    let type: LibraryType
    @Environment(AppServices.self) private var services
    @Environment(Navigator.self) private var navigator

    var body: some View {
        LibraryView(model: LibraryModel(library: services.library, navigator: navigator, type: type, rows: services.rows, letterJump: .extend))
    }
}

private struct LibraryView: View {
    @State var model: LibraryModel
    @Environment(AppServices.self) private var services
    @State private var editingRows = false
    @FocusState private var focus: GridFocus?
    /// The poster focus left for the letter index; Menu there goes back to it.
    @State private var returnID: Int?
    /// Focus the screen moved itself, which the index's rules leave alone.
    @State private var placedFocus: GridFocus?
    /// A letter's jump, waiting for focus to rest on it.
    @State private var follow: Task<Void, Never>?

    private static let columnCount = 7
    private let columns = Array(repeating: GridItem(.flexible(), spacing: 24, alignment: .top), count: LibraryView.columnCount)

    var body: some View {
        ScrollViewReader { proxy in
            page(proxy)
                .overlay { strip(proxy) }
                .onChange(of: model.scrollRequestID) {
                    // The grid follows the letters: the letter's row near the top.
                    if let target = model.scrollTargetID { withAnimation { proxy.scrollTo(target, anchor: UnitPoint(x: 0.5, y: 0.32)) } }
                }
                .onChange(of: focus) { old, new in focusMoved(from: old, to: new, proxy) }
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
                    arrangedTop
                } else {
                    header
                        .padding(.leading, TVLayout.gutter)
                        .padding(.trailing, trailingGutter)
                        .padding(.top, 16)
                }
                if !model.noLibrary {
                    chips.padding(.top, 20)
                }
                grid
            }
            .padding(.bottom, 60)
        }
        .task { await model.run() }
        .messagePill($model.message)
        .noticeAlert($model.notice)
        .fullScreenCover(isPresented: $editingRows) {
            if let screen = model.screen { TVRowEditor(screen: screen, screenName: model.title) }
        }
    }

    /// From API level 22: the title and count, the rows, Edit rows, then All movies with Sort.
    private var arrangedTop: some View {
        VStack(alignment: .leading, spacing: 34) {
            HStack(alignment: .firstTextBaseline, spacing: 20) {
                Text(model.title)
                    .font(.system(size: 46, weight: .heavy))
                    .tracking(-0.9)
                    .foregroundStyle(Palette.text)
                    .accessibilityAddTraits(.isHeader)
                Text(model.headerCount ?? " ").font(.system(size: 20)).foregroundStyle(Palette.muted)
            }
            .padding(.horizontal, TVLayout.gutter)
            .padding(.top, 16)
            ForEach(model.shownRows) { row in
                TVArrangedRow(row: row, actions: rowActions)
            }
            TVEditRowsButton { editingRows = true }
                .padding(.horizontal, TVLayout.gutter)
                .frame(maxWidth: .infinity, alignment: .leading)
                .focusSection()
            HStack(alignment: .bottom) {
                HStack(alignment: .firstTextBaseline, spacing: 20) {
                    Text(model.allTitle)
                        .font(.system(size: 40, weight: .heavy))
                        .tracking(-0.8)
                        .foregroundStyle(Palette.text)
                        .accessibilityAddTraits(.isHeader)
                    Text(model.overviewLine ?? " ").font(.system(size: 20)).foregroundStyle(Palette.muted)
                }
                Spacer()
                if !model.noLibrary { sort }
            }
            .padding(.leading, TVLayout.gutter)
            .padding(.trailing, trailingGutter)
        }
    }

    private var rowActions: TVRowActions {
        TVRowActions(
            open: { model.open($0) },
            openEpisode: { model.open($0) },
            playEpisode: { model.play($0) },
            resume: { model.resume($0) },
            setWatched: { await model.setWatched($0, $1) },
            openList: { id in Task { await model.open(.list(id: id)) } }
        )
    }

    private var header: some View {
        HStack(alignment: .bottom) {
            VStack(alignment: .leading, spacing: 8) {
                Text(model.title)
                    .font(.system(size: 46, weight: .heavy))
                    .tracking(-0.9)
                    .foregroundStyle(Palette.text)
                    .accessibilityAddTraits(.isHeader)
                Text(model.overviewLine ?? " ")
                    .font(.system(size: 20))
                    .foregroundStyle(Palette.muted)
            }
            Spacer()
            if !model.noLibrary { sort }
        }
    }

    private var sort: some View {
        HStack(spacing: 16) {
            Text("Sort").font(.system(size: 19)).foregroundStyle(Palette.muted)
            Menu {
                Picker("Sort by", selection: Binding(get: { model.sort }, set: { sort in Task { await model.setSort(sort) } })) {
                    ForEach(model.sorts, id: \.self) { Text($0.label).tag($0) }
                }
            } label: {
                ChipMenuLabel(title: model.sort.label)
            }
            .buttonStyle(TVChipStyle(selected: false))
            .accessibilityLabel("Sort by")
            .accessibilityValue(model.sort.label)
        }
        .focusSection()
    }

    private var chips: some View {
        ScrollView(.horizontal) {
            HStack(spacing: 12) {
                ForEach(model.filters, id: \.self) { filter in
                    Button(filter.label) { Task { await model.setFilter(filter) } }
                        .buttonStyle(TVChipStyle(selected: model.filter == filter))
                }
                if !model.genres.isEmpty {
                    divider
                    ForEach(model.genres, id: \.self) { genre in
                        Button(genre) { Task { await model.toggleGenre(genre) } }
                            .buttonStyle(TVChipStyle(selected: model.genre == genre))
                    }
                }
                if !model.decades(includingShows: true).isEmpty {
                    Menu {
                        Button("Any decade") {
                            if let decade = model.decade { Task { await model.toggleDecade(decade) } }
                        }
                        ForEach(model.decades(includingShows: true), id: \.self) { decade in
                            Button {
                                if model.decade != decade { Task { await model.toggleDecade(decade) } }
                            } label: {
                                if model.decade == decade {
                                    Label(decadeLabel(decade), systemImage: "checkmark")
                                } else {
                                    Text(decadeLabel(decade))
                                }
                            }
                        }
                    } label: {
                        ChipMenuLabel(title: model.decadeChipLabel)
                    }
                    .buttonStyle(TVChipStyle(selected: model.decade != nil))
                    .accessibilityLabel("Decade")
                    .accessibilityValue(model.decade.map(decadeLabel) ?? "Any")
                }
            }
            .padding(.vertical, 12)
        }
        .contentMargins(.leading, TVLayout.gutter, for: .scrollContent)
        .contentMargins(.trailing, model.letterIndex == nil ? TVLayout.gutter : 0, for: .scrollContent)
        .scrollClipDisabled()
        .scrollIndicators(.hidden)
        // Beside the letter index the chips stop where the grid does, with room above and below for the focus lift.
        .mask { Rectangle().padding(.vertical, -40) }
        .padding(.trailing, model.letterIndex == nil ? 0 : trailingGutter)
        .focusSection()
    }

    private var divider: some View {
        Rectangle().fill(Palette.outline).frame(width: 1, height: 32).padding(.horizontal, 10).accessibilityHidden(true)
    }

    @ViewBuilder private var grid: some View {
        switch model.state {
        case .loading:
            ProgressView().frame(maxWidth: .infinity).padding(.top, 160)
        case .failed(let message):
            TVErrorState(message: message, retry: model.retry).padding(.top, 80)
        case .loaded(let grid):
            if grid.items.isEmpty {
                empty
            } else {
                LazyVGrid(columns: columns, alignment: .leading, spacing: 36) {
                    ForEach(grid.items) { item in
                        Button { model.open(item) } label: {
                            PosterCard(item: item, imageURL: services.library.imageURL(item.posterPath, size: .w500), width: 218)
                                .overlay(alignment: .top) {
                                    // Where Select on the focused letter lands (the board's `.card.land`).
                                    if item.id == landingID {
                                        RoundedRectangle(cornerRadius: 16).stroke(Palette.gold.opacity(0.55), lineWidth: 3).frame(height: 327)
                                    }
                                }
                        }
                        .buttonStyle(.borderless)
                        .focused($focus, equals: .item(item.id))
                        .playPauseCard(.card(item)) { model.open(item) }
                        .contextMenu {
                            if item.watchTarget != nil {
                                WatchToggleButton(watched: item.watched) { await model.setWatched(item, $0) }
                            }
                        }
                        .onAppear { Task { await model.itemAppeared(item) } }
                    }
                }
                .padding(.leading, TVLayout.gutter)
                .padding(.trailing, trailingGutter)
                .padding(.top, 28)
                .focusSection()
                more
            }
        }
    }

    @ViewBuilder private var more: some View {
        if let error = model.moreError {
            VStack(spacing: 14) {
                Text(error).font(.system(size: 20)).foregroundStyle(Palette.muted)
                Button("Try again") { Task { await model.retryMore() } }.buttonStyle(TVButtonStyle())
            }
            .frame(maxWidth: .infinity)
            .padding(30)
        } else if model.loadingMore {
            ProgressView().frame(maxWidth: .infinity).padding(30)
        }
    }

    // MARK: The letter index

    /// Beside the index the page is 1,670 wide (TvLibraryAZ.dc.html), else 1,740.
    private var trailingGutter: CGFloat { model.letterIndex == nil ? TVLayout.gutter : 160 }

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
    /// letter, along it the grid follows, and out of it to the left it lands
    /// on the letter's first title.
    private func focusMoved(from old: GridFocus?, to new: GridFocus?, _ proxy: ScrollViewProxy) {
        if let new, new == placedFocus {
            placedFocus = nil
            return
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

    /// Scrolls the poster into the grid (it may not be drawn yet), then focuses it.
    private func focusItem(_ id: Int, _ proxy: ScrollViewProxy) async {
        withAnimation { proxy.scrollTo(id, anchor: UnitPoint(x: 0.5, y: 0.32)) }
        await Task.yield()
        place(.item(id))
    }

    private func place(_ target: GridFocus) {
        placedFocus = target
        focus = target
    }

    private var empty: some View {
        let words = model.emptyMessage
        return VStack(spacing: 24) {
            TVEmptyState(systemImage: model.isFiltered ? "line.3.horizontal.decrease" : (model.type == .tv ? "tv" : "film"), title: words.title, message: words.message)
            if model.isFiltered && !model.noLibrary {
                Button("Clear filters") { Task { await model.clearFilters() } }.buttonStyle(TVButtonStyle())
            }
        }
        .padding(.top, 100)
    }
}
