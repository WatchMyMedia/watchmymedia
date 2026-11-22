import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// A list's page on Apple TV, from the See all card at the end of a list row:
/// the name; where it is from,
/// when it was read, how much of it the library has and whose it is; then
/// every title in rank order, seven a row. A title the library has opens its
/// page; selecting a missing one opens the request dialog (where the person
/// may request it); an imported title TMDB couldn't find says Not found. A
/// list whose last fetch failed says so, with Try again for whoever may.
/// Page Up and Page Down move two rows, as on the library grids
/// (`GridPaging`).
///
/// A watchlist's page has Rename, Delete and Remove titles once watched at
/// its top, then All, Movies and TV shows and the sort; its titles carry no
/// rank, and a card's long press removes it.
struct TVMediaListScreen: View {
    let id: Int
    @Environment(AppServices.self) private var services
    @Environment(Navigator.self) private var navigator
    @Environment(WatchlistStore.self) private var watchlists

    var body: some View {
        TVMediaListView(model: MediaListModel(listID: id, rows: services.rows, library: services.library, requests: services.requests, navigator: navigator,
                                              watchlists: watchlists))
    }
}

private struct TVMediaListView: View {
    @State var model: MediaListModel
    @Environment(AppServices.self) private var services
    /// The focused title's rank, for Page Up and Page Down.
    @FocusState private var focusedRank: Int?
    @State private var renaming = false
    @State private var newName = ""
    @State private var confirmingDelete = false

    private static let columnCount = 7
    private let columns = Array(repeating: GridItem(.flexible(), spacing: 24, alignment: .top), count: TVMediaListView.columnCount)

    var body: some View {
        ScrollViewReader { proxy in
            page
                .onPageKeys(enabled: focusedRank != nil) { page(by: $0, proxy) }
        }
    }

    /// Two rows back or on from the focused title, in the same column.
    private func page(by pages: Int, _ proxy: ScrollViewProxy) {
        guard let rank = focusedRank, case let entries = model.entries,
              let place = entries.firstIndex(where: { $0.rank == rank }),
              let target = GridPaging.target(from: place, pages: pages, columns: Self.columnCount, count: entries.count) else { return }
        let id = entries[target].rank
        withAnimation { proxy.scrollTo(id, anchor: UnitPoint(x: 0.5, y: 0.32)) }
        Task {
            await Task.yield()
            focusedRank = id
        }
    }

    private var page: some View {
        TabPage {
            TVLoadable(model.state, retry: model.retry) { page in
                VStack(alignment: .leading, spacing: 28) {
                    HStack(alignment: .top, spacing: 40) {
                        header.frame(maxWidth: .infinity, alignment: .leading)
                        if let failure = model.failureLine() { callout(failure).frame(width: 700) }
                    }
                    .focusSection()
                    if model.isWatchlist { watchlistControls }
                    if model.isWatchlist && model.countsLine == nil {
                        TVEmptyState(systemImage: "bookmark", title: WatchlistWords.emptyTitle, message: WatchlistWords.emptyMessage).padding(.top, 40)
                    } else {
                        LazyVGrid(columns: columns, alignment: .leading, spacing: 36) {
                            ForEach(model.entries) { entry in card(entry).focused($focusedRank, equals: entry.rank) }
                        }
                        .focusSection()
                    }
                }
                .padding(.horizontal, TVLayout.gutter)
                .padding(.top, 16)
                .padding(.bottom, 60)
            }
            .frame(minHeight: 600)
        }
        .task { await model.run() }
        .messagePill($model.message)
        .noticeAlert($model.notice)
        .fullScreenCover(item: $model.requestSheet) { sheet in
            RequestDialog(model: sheet) {
                model.requestSheetClosed(sheet)
                model.requestSheet = nil
            }
        }
    }

    /// The name, then "From TMDB · Updated 3 h ago · 18 of 50 in your library · For everyone";
    /// a watchlist's with Watchlist over it.
    private var header: some View {
        VStack(alignment: .leading, spacing: 12) {
            if model.isWatchlist {
                Text(WatchlistWords.kicker.uppercased())
                    .font(.system(size: 20, weight: .bold))
                    .tracking(1)
                    .foregroundStyle(Palette.gold)
            }
            Text(model.title)
                .font(.system(size: 56, weight: .heavy))
                .tracking(-1.1)
                .foregroundStyle(Palette.text)
                .lineLimit(2)
                .accessibilityAddTraits(.isHeader)
            (Text(model.sourceLine ?? "").foregroundStyle(Palette.gold).fontWeight(.semibold)
                + Text([model.sourceLine == nil ? nil : "", model.whenLine(), model.countsLine].compactMap { $0 }.joined(separator: " · ")).foregroundStyle(Palette.muted))
                .font(.system(size: 23))
                .lineSpacing(4)
        }
    }

    private func callout(_ text: String) -> some View {
        HStack(spacing: 18) {
            Text("\(Image(systemName: "exclamationmark.triangle"))").foregroundStyle(Palette.warning)
            Text(text).font(.system(size: 21)).lineSpacing(4).foregroundStyle(Palette.text).frame(maxWidth: .infinity, alignment: .leading)
            if model.canRefresh {
                Button("Try again") { Task { await model.refreshNow() } }
                    .buttonStyle(TVButtonStyle(height: 56, fontSize: 20))
                    .disabled(model.working)
            }
        }
        .padding(.horizontal, 24)
        .padding(.vertical, 20)
        .background(Palette.warning.opacity(0.10), in: RoundedRectangle(cornerRadius: 18))
        .overlay(RoundedRectangle(cornerRadius: 18).strokeBorder(Palette.warning.opacity(0.4)))
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder private func card(_ entry: MediaListEntry) -> some View {
        let ranked = !model.isWatchlist
        if let item = entry.item {
            Button { model.open(entry) } label: {
                EntryFace(entry: entry, imageURL: services.library.imageURL(item.posterPath, size: .w500), ranked: ranked)
            }
            .buttonStyle(.borderless)
            .playPauseCard(.card(item)) { model.open(entry) }
            .accessibilityLabel(entry.accessibilityLine(ranked: ranked))
            .contextMenu {
                watchlistItem(entry)
                if let target = item.watchTarget {
                    WatchToggleButton(watched: item.watched) { await model.setWatched(target, $0) }
                }
            }
        } else {
            Button { model.openRequest(entry) } label: {
                EntryFace(entry: entry, imageURL: services.library.imageURL(entry.posterPath, size: .w500), canRequest: model.canRequest(entry), ranked: ranked)
            }
            .buttonStyle(.borderless)
            .accessibilityLabel(entry.accessibilityLine(ranked: ranked))
            .accessibilityHint(model.canRequest(entry) ? "Opens the request" : "")
            .accessibilityIdentifier("entry-\(entry.rank)")
            .contextMenu {
                watchlistItem(entry)
                if model.canRequest(entry) {
                    Button { model.openRequest(entry) } label: { Label("Request", systemImage: "plus") }
                }
            }
        }
    }

    /// Remove from this watchlist, first in a card's menu; on another list, Add to Watchlist.
    @ViewBuilder private func watchlistItem(_ entry: MediaListEntry) -> some View {
        if model.isWatchlist {
            Button(role: .destructive) { Task { await model.remove(entry) } } label: {
                Label(WatchlistWords.remove(from: model.title), systemImage: "bookmark.slash")
            }
        } else {
            TVWatchlistMenuButton(title: entry.watchlistTitle, said: entry.watchlisted)
        }
    }

    /// Rename, Delete and the switch, with what it does under them; then All, Movies, TV shows and the sort.
    private var watchlistControls: some View {
        VStack(alignment: .leading, spacing: 24) {
            HStack(spacing: 18) {
                Button { newName = model.title; renaming = true } label: { Text("\(Image(systemName: "pencil"))  \(WatchlistWords.rename)") }
                    .buttonStyle(TVButtonStyle(height: 62, fontSize: 22))
                    .accessibilityIdentifier("watchlist-rename")
                Button { confirmingDelete = true } label: { Text("\(Image(systemName: "trash"))  \(WatchlistWords.delete)") }
                    .buttonStyle(TVButtonStyle(kind: .redText, height: 62, fontSize: 22))
                    .accessibilityIdentifier("watchlist-delete")
                Button { Task { await model.setRemovesWatched(!model.removesWatched) } } label: {
                    HStack(spacing: 14) {
                        Text(WatchlistWords.removeWatched)
                        TVSwitch(on: model.removesWatched, compact: true)
                    }
                }
                .buttonStyle(TVButtonStyle(height: 62, fontSize: 22))
                .accessibilityValue(model.removesWatched ? "On" : "Off")
                .accessibilityHint(WatchlistWords.removeWatchedDetail)
                .accessibilityIdentifier("watchlist-switch")
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .focusSection()
            Text(WatchlistWords.removeWatchedDetail).font(.system(size: 20)).foregroundStyle(Palette.muted)
            if model.countsLine != nil {
                HStack(spacing: 14) {
                    ForEach([nil, MediaType.movie, .tv], id: \.self) { type in
                        Button(WatchlistWords.filterName(type)) { model.filter = type }
                            .buttonStyle(TVChipStyle(selected: model.filter == type))
                            .accessibilityAddTraits(model.filter == type ? .isSelected : [])
                    }
                    Text("Sort").font(.system(size: 19)).foregroundStyle(Palette.muted).padding(.leading, 24)
                    Menu {
                        Picker("Sort", selection: Binding(get: { model.sort }, set: { sort in Task { await model.setSort(sort) } })) {
                            ForEach(ListSort.allCases, id: \.self) { Text(WatchlistWords.sortName($0)).tag($0) }
                        }
                    } label: {
                        ChipMenuLabel(title: WatchlistWords.sortName(model.sort))
                    }
                    .buttonStyle(TVChipStyle(selected: false))
                    .accessibilityLabel("Sort")
                    .accessibilityValue(WatchlistWords.sortName(model.sort))
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .focusSection()
            }
        }
        .alert(WatchlistWords.rename, isPresented: $renaming) {
            TextField(WatchlistWords.name, text: $newName)
            Button(WatchlistWords.cancel, role: .cancel) {}
            Button(WatchlistWords.done) {
                let name = newName
                Task { if let problem = await model.rename(to: name) { model.message = problem } }
            }
        }
        .alert(model.deletePrompt, isPresented: $confirmingDelete) {
            Button(WatchlistWords.cancel, role: .cancel) {}
            Button(WatchlistWords.delete, role: .destructive) { Task { await model.delete() } }
        } message: {
            Text(model.deleteMessage)
        }
    }
}

/// A title's card: the 2:3 artwork with the rank, then the title and its line.
/// A missing title is dimmed, with Request, Requested, Downloading or Not in
/// your library under it; one TMDB couldn't find is a dashed box, Not found.
private struct EntryFace: View {
    let entry: MediaListEntry
    let imageURL: URL?
    var canRequest = false
    /// A list's titles wear their rank; a watchlist's don't.
    var ranked = true
    @Environment(\.isFocused) private var focused
    @Environment(\.displayScale) private var scale

    private let width: CGFloat = 218

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            artwork
                .frame(width: width, height: (width * 1.5).rounded())
                .overlay(alignment: .topLeading) { if ranked { rank.padding(10) } }
                .overlay { FocusSheen(focused: focused) }
                .clipShape(RoundedRectangle(cornerRadius: 16))
                .focusRim(focused, cornerRadius: 16)
            VStack(alignment: .leading, spacing: 4) {
                Text(entry.title)
                    .font(.system(size: 20, weight: .semibold))
                    .foregroundStyle(entry.item == nil ? Palette.muted : focused ? .white : Color.tvTitle)
                    .lineLimit(1)
                Text(entry.item?.gridSubtitle ?? entry.year.map(String.init) ?? " ")
                    .font(.system(size: 17)).foregroundStyle(Palette.muted).lineLimit(1)
                if entry.item == nil && entry.found { state }
            }
            .padding(.top, 12)
            .offset(y: focused ? 12 : 0)
            .animation(.easeOut(duration: 0.18), value: focused)
        }
        .frame(width: width, alignment: .leading)
    }

    @ViewBuilder private var artwork: some View {
        if let item = entry.item {
            Artwork(url: imageURL, seed: item.title, maxPixel: cardPixels(width: width, height: width * 1.5, scale: scale))
                .overlay {
                    if let quality = item.quality { TVBadge(quality).pinned(.topTrailing) }
                    if item.watched {
                        WatchedCheck(size: 28).pinned(.bottomTrailing)
                    } else if let progress = item.progress, progress > 0 {
                        ProgressStrip(progress: progress, height: 6).pinned(.bottom, padding: 0)
                    }
                }
        } else if !entry.found {
            RoundedRectangle(cornerRadius: 16)
                .fill(Palette.surface)
                .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(Palette.outlineStrong, style: StrokeStyle(lineWidth: 2, dash: [8, 5])))
                .overlay { TVBadge("Not found", style: .warning).pinned(.bottomLeading, padding: 10) }
        } else {
            Artwork(url: imageURL, seed: entry.title, maxPixel: cardPixels(width: width, height: width * 1.5, scale: scale))
                .overlay { Color(hex: 0x0F0F10).opacity(focused ? 0.35 : 0.62) }
        }
    }

    private var rank: some View {
        Text("\(entry.rank)")
            .font(.system(size: 16, weight: .bold).monospacedDigit())
            .foregroundStyle(Palette.text)
            .padding(.horizontal, 8)
            .frame(minWidth: 30, minHeight: 30)
            .background(.black.opacity(0.78), in: Capsule())
    }

    @ViewBuilder private var state: some View {
        switch entry.requestState {
        case .requestable? where canRequest:
            Text("Request").font(.system(size: 17, weight: .semibold)).foregroundStyle(Palette.gold)
        case .requested?, .available?:
            Text("\(Image(systemName: "checkmark")) Requested").font(.system(size: 17, weight: .semibold)).foregroundStyle(Palette.textSecondary)
        case .downloading?:
            Text("\(Image(systemName: "arrow.down.to.line")) Downloading").font(.system(size: 17, weight: .semibold)).foregroundStyle(Palette.gold)
        default:
            Text("Not in your library").font(.system(size: 17)).foregroundStyle(Palette.textSecondary)
        }
    }
}
