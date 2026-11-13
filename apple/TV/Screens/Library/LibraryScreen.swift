import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// The TV Shows or Movies grid (TvLibrary.dc.html): the title and the
/// library's counts, Sort as a menu, the filter and genre chips with a
/// Decade menu, and seven posters a row, a page at a time. Long press marks
/// a poster watched. From API level 22 the library's rows come first, then
/// Edit rows, then the grid as All movies or All shows (TvLibraryRows.dc.html).
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
    @State private var editingRows = false

    private let columns = Array(repeating: GridItem(.flexible(), spacing: 24, alignment: .top), count: 7)

    var body: some View {
        TabPage {
            VStack(alignment: .leading, spacing: 0) {
                if model.canEditRows {
                    arrangedTop
                } else {
                    header
                        .padding(.horizontal, TVLayout.gutter)
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
            .padding(.horizontal, TVLayout.gutter)
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
        .contentMargins(.horizontal, TVLayout.gutter, for: .scrollContent)
        .scrollClipDisabled()
        .scrollIndicators(.hidden)
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
                        }
                        .buttonStyle(.borderless)
                        .playPauseCard(.card(item)) { model.open(item) }
                        .contextMenu {
                            if item.watchTarget != nil {
                                WatchToggleButton(watched: item.watched) { await model.setWatched(item, $0) }
                            }
                        }
                        .onAppear { Task { await model.itemAppeared(item) } }
                    }
                }
                .padding(.horizontal, TVLayout.gutter)
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
