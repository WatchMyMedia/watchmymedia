import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// The TV Shows or Movies grid (Library.dc.html): a title search, the
/// filter, genre and (for movies) decade chips, a Sort menu, and three
/// posters a row, a page at a time. Long-press a poster to mark it. On iPad
/// (Pad2Library.dc.html) a collapsible filter panel sits beside the adaptive
/// grid, and the A–Z bar jumps using the server's whole-list offsets. From
/// API level 22 the library's rows sit above the grid, with Edit rows after
/// them and the grid as All movies or All shows (LibraryRows.dc.html,
/// Pad2LibraryRows.dc.html); a title search hides them.
struct LibraryScreen: View {
    let type: LibraryType
    @Environment(AppServices.self) private var services
    @Environment(Navigator.self) private var navigator

    var body: some View {
        LibraryView(model: LibraryModel(library: services.library, navigator: navigator, type: type, rows: services.rows))
    }
}

private struct LibraryView: View {
    @State var model: LibraryModel
    @Environment(AppServices.self) private var services
    @Environment(\.horizontalSizeClass) private var sizeClass

    @State private var width: CGFloat = 0
    @State private var filtersExpanded = true
    @State private var filterSheet = false
    @State private var editingRows = false

    private var wide: Bool { sizeClass.isWide }
    private var panelAvailable: Bool { wide && width >= PadLayout.sideBySide }
    private var panelVisible: Bool { panelAvailable && filtersExpanded && !model.noLibrary }
    private var margin: CGFloat { wide ? PadLayout.margin : 16 }

    /// Three posters a row on iPhone; on iPad as many as fit at 170 pt or more.
    private var columns: [GridItem] {
        wide
            ? [GridItem(.adaptive(minimum: PadLayout.posterMinimum), spacing: 16, alignment: .top)]
            : Array(repeating: GridItem(.flexible(), spacing: 8, alignment: .top), count: 3)
    }

    var body: some View {
        HStack(alignment: .top, spacing: 0) {
            if panelVisible {
                ScrollView { filterPanel.padding(PadLayout.margin) }
                    .frame(width: 220)
                    .background(Palette.surface)
                    .overlay(alignment: .trailing) { Rectangle().fill(Palette.separator).frame(width: 1) }
            }
            ScrollViewReader { proxy in
                VStack(alignment: .leading, spacing: wide ? 14 : 0) {
                    if wide && !model.noLibrary && !arranged {
                        resultsHeader
                        if !model.letters.isEmpty { alphabet }
                    }
                    ScrollView {
                        VStack(alignment: .leading, spacing: wide ? 14 : 12) {
                            if arranged && !model.isSearching { rowsSection(proxy) }
                            if !wide && !model.noLibrary {
                                chips
                                resultsHeader
                            } else if wide && arranged {
                                resultsHeader
                                if !model.letters.isEmpty { alphabet }
                            }
                            grid
                        }
                        .padding(.bottom, 24)
                    }
                    .refreshable { await model.refresh() }
                    .onChange(of: model.scrollRequestID) {
                        if let target = model.scrollTargetID { proxy.scrollTo(target, anchor: .top) }
                    }
                }
            }
        }
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width = $0 }
        .scrollDismissesKeyboard(.immediately)
        .groundBackground()
        .navigationTitle(model.title)
        .navigationBarTitleDisplayMode(.large)
        .modifier(NavigationSubtitle(text: model.headerCount))
        .searchable(
            text: Binding(get: { model.searchText }, set: { model.setSearchText($0) }),
            placement: wide ? .toolbar : .navigationBarDrawer(displayMode: .always),
            prompt: model.searchPrompt
        )
        .toolbar {
            if wide && !model.noLibrary {
                ToolbarItem(placement: .topBarTrailing) { sortMenu }
            }
        }
        .autocorrectionDisabled()
        .sheet(isPresented: $filterSheet) {
            NavigationStack {
                ScrollView { filterPanel.padding(24) }
                    .groundBackground()
                    .navigationTitle("Filters")
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { filterSheet = false } } }
            }
            .presentationDetents([.large])
        }
        .task { await model.run() }
        .messageToast($model.message)
        .noticeAlert($model.notice)
        .sheet(isPresented: $editingRows) {
            if let screen = model.screen { RowEditorSheet(screen: screen, screenName: model.title) }
        }
        // With rows above the grid, the iPad's filter panel starts folded away (Pad2LibraryRows.dc.html).
        .onChange(of: arranged) { _, now in if now { filtersExpanded = false } }
    }

    /// The library's rows are arranged (API level 22).
    private var arranged: Bool { model.canEditRows }

    /// The rows, Edit rows, and the grid's heading.
    private func rowsSection(_ proxy: ScrollViewProxy) -> some View {
        VStack(alignment: .leading, spacing: 22) {
            ForEach(model.shownRows) { row in
                ArrangedRow(row: row, actions: rowActions(proxy))
            }
            EditRowsButton(title: "Edit rows") { editingRows = true }
                .padding(.horizontal, margin)
            VStack(alignment: .leading, spacing: 12) {
                RowSeparator()
                Text(model.allTitle)
                    .scaledFont(wide ? 26 : 20, weight: wide ? .bold : .semibold)
                    .foregroundStyle(Palette.text)
                    .accessibilityAddTraits(.isHeader)
            }
            .padding(.horizontal, margin)
            .id(Self.gridAnchor)
        }
        .padding(.top, 8)
    }

    private static let gridAnchor = "allItems"

    /// A row's See all: a list's page, or the grid below with the row's sort and filters.
    private func rowActions(_ proxy: ScrollViewProxy) -> RowActions {
        RowActions(
            open: { model.open($0) },
            openEpisode: { model.open($0) },
            playEpisode: { model.play($0) },
            resume: { model.resume($0) },
            setWatched: { await model.setWatched($0, $1) },
            seeAll: { row in
                guard let link = row.seeAll else { return nil }
                return {
                    Task {
                        await model.open(link)
                        if case .library = link { withAnimation { proxy.scrollTo(Self.gridAnchor, anchor: .top) } }
                    }
                }
            }
        )
    }

    private var resultsHeader: some View {
        HStack {
            if wide { filterButton }
            Text(model.resultLine ?? "")
                .scaledFont(13)
                .foregroundStyle(Palette.muted)
                .lineLimit(wide ? 2 : 1)
            Spacer(minLength: 8)
            if !wide { sortMenu }
        }
        .padding(.horizontal, margin)
    }

    private var filterButton: some View {
        Button {
            if panelAvailable { filtersExpanded.toggle() } else { filterSheet = true }
        } label: {
            Label("Filters · \(model.activeFilterCount)", systemImage: "line.3.horizontal.decrease")
                .scaledFont(15, weight: .semibold)
                .foregroundStyle(Palette.gold)
                .frame(minHeight: 44)
        }
        .accessibilityValue(panelVisible ? "Expanded" : "Collapsed")
        .accessibilityHint(panelAvailable ? "Shows or hides the filter panel" : "Opens filters")
    }

    private var alphabet: some View {
        ScrollView(.horizontal) {
            HStack(spacing: 0) {
                ForEach(model.letters, id: \.letter) { letter in
                    Button { Task { await model.jump(to: letter) } } label: {
                        Text(letter.letter)
                            .scaledFont(13, weight: .semibold)
                            .foregroundStyle(model.selectedLetter == letter.letter ? Palette.ground : Palette.muted)
                            .frame(minWidth: 44, minHeight: 44)
                            .background(model.selectedLetter == letter.letter ? Palette.gold : .clear, in: RoundedRectangle(cornerRadius: 8))
                    }
                    .accessibilityLabel("Jump to \(letter.letter)")
                    .accessibilityAddTraits(model.selectedLetter == letter.letter ? .isSelected : [])
                }
            }
        }
        .contentMargins(.horizontal, margin, for: .scrollContent)
        .scrollIndicators(.hidden)
    }

    private var filterPanel: some View {
        VStack(alignment: .leading, spacing: 22) {
            VStack(alignment: .leading, spacing: 4) {
                DetailHeading(title: "Show or quality")
                // The API accepts one of these choices, not independent status and quality filters.
                ForEach(model.filters, id: \.self) { filter in
                    filterOption(filter.label, selected: model.filter == filter) {
                        Task { await model.setFilter(filter) }
                    }
                }
            }
            if !model.genres.isEmpty {
                VStack(alignment: .leading, spacing: 4) {
                    DetailHeading(title: "Genre")
                    ForEach(model.genres, id: \.self) { genre in
                        filterOption(genre, selected: model.genre == genre, count: model.genreCounts[genre]) { Task { await model.toggleGenre(genre) } }
                    }
                }
            }
            if !model.decades(includingShows: true).isEmpty {
                VStack(alignment: .leading, spacing: 4) {
                    DetailHeading(title: "Decade")
                    ForEach(model.decades(includingShows: true), id: \.self) { decade in
                        filterOption(decadeLabel(decade), selected: model.decade == decade) { Task { await model.toggleDecade(decade) } }
                    }
                }
            }
            Button("Clear filters") { Task { await model.clearFilters() } }
                .buttonStyle(GhostButtonStyle(height: 44))
                .disabled(!model.isFiltered)
        }
    }

    private func filterOption(_ text: String, selected: Bool, count: Int? = nil, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 10) {
                Image(systemName: selected ? "checkmark.circle.fill" : "circle")
                    .foregroundStyle(selected ? Palette.gold : Palette.faint)
                Text(text).foregroundStyle(selected ? Palette.gold : Palette.textSecondary)
                Spacer(minLength: 0)
                if let count { Text(formatCount(count)).foregroundStyle(Palette.muted).monospacedDigit() }
            }
            .scaledFont(15)
            .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    private var chips: some View {
        ScrollView(.horizontal) {
            HStack(spacing: 8) {
                ForEach(model.filters, id: \.self) { filter in
                    Chip(filter.label, selected: model.filter == filter) { Task { await model.setFilter(filter) } }
                }
                if !model.genres.isEmpty {
                    chipDivider
                    ForEach(model.genres, id: \.self) { genre in
                        Chip(genre, selected: model.genre == genre) { Task { await model.toggleGenre(genre) } }
                    }
                }
                let decades = model.decades(includingShows: wide)
                if !decades.isEmpty {
                    chipDivider
                    ForEach(decades, id: \.self) { decade in
                        Chip(decadeLabel(decade), selected: model.decade == decade) { Task { await model.toggleDecade(decade) } }
                    }
                }
            }
        }
        .contentMargins(.horizontal, margin, for: .scrollContent)
        .scrollIndicators(.hidden)
    }

    private var chipDivider: some View {
        Rectangle().fill(Palette.outline).frame(width: 1, height: 24).accessibilityHidden(true)
    }

    private var sortMenu: some View {
        Menu {
            Picker("Sort by", selection: Binding(get: { model.sort }, set: { sort in Task { await model.setSort(sort) } })) {
                ForEach(model.sorts, id: \.self) { Text($0.label).tag($0) }
            }
        } label: {
            HStack(spacing: 4) {
                Text(model.sortLabel)
                Image(systemName: "chevron.down").font(.system(size: 12, weight: .bold))
            }
            .scaledFont(15, weight: .semibold)
            .foregroundStyle(Palette.gold)
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .accessibilityLabel("Sort by")
        .accessibilityValue(model.sort.label)
    }

    @ViewBuilder private var grid: some View {
        switch model.state {
        case .loading:
            ProgressView()
                .tint(Palette.muted)
                .frame(maxWidth: .infinity)
                .padding(.top, 80)
        case .failed(let message):
            ErrorState(message: message, retry: model.retry)
                .padding(.top, 40)
        case .loaded(let grid):
            if grid.items.isEmpty {
                empty
            } else {
                LazyVGrid(columns: columns, spacing: wide ? 18 : 14) {
                    ForEach(grid.items) { item in
                        Button { model.open(item) } label: {
                            PosterCard(item: item, imageURL: services.library.imageURL(item.posterPath, size: .w342), compact: true)
                        }
                        .buttonStyle(CardButtonStyle())
                        .contextMenu {
                            if item.watchTarget != nil {
                                WatchToggleButton(watched: item.watched) { await model.setWatched(item, $0) }
                            }
                        }
                        .id(item.id)
                        .onAppear { Task { await model.itemAppeared(item) } }
                    }
                }
                .padding(.horizontal, margin)
                .padding(.top, 2)
                more
            }
        }
    }

    @ViewBuilder private var more: some View {
        if let error = model.moreError {
            VStack(spacing: 8) {
                Text(error).scaledFont(13).foregroundStyle(Palette.muted).multilineTextAlignment(.center)
                Button("Try again") { Task { await model.retryMore() } }
                    .buttonStyle(GhostButtonStyle(height: 44, fullWidth: false))
            }
            .frame(maxWidth: .infinity)
            .padding(16)
        } else if model.loadingMore {
            ProgressView().tint(Palette.muted).frame(maxWidth: .infinity).padding(16)
        }
    }

    private var empty: some View {
        let words = model.emptyMessage
        return VStack(spacing: 4) {
            EmptyState(systemImage: model.isFiltered ? "line.3.horizontal.decrease" : (model.type == .tv ? "tv" : "film"), title: words.title, message: words.message)
            if model.isFiltered && !model.noLibrary {
                Button("Clear filters") { Task { await model.clearFilters() } }
                    .buttonStyle(GhostButtonStyle(height: 44, fullWidth: false))
            }
        }
        .padding(.top, 40)
    }
}

/// The count under a large title ("142 shows"), where the system offers it (iOS 26).
private struct NavigationSubtitle: ViewModifier {
    let text: String?

    func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            content.navigationSubtitle(text ?? "")
        } else {
            content
        }
    }
}
