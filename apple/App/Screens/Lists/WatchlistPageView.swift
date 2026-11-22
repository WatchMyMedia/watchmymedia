import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// A watchlist's page on iPhone and iPad: Watchlist over its name and
/// counts, with ⋯ beside the name (Rename, Remove titles once watched with
/// what it does, and Delete, which asks first); All, Movies and TV shows,
/// and the sort; then its titles, newest saved first, without ranks. A
/// missing title is dimmed with Request where the person may ask for it,
/// and every card's long press removes it, with Undo. Where the window is
/// wide enough for two panes, the person's watchlists stay listed beside it.
struct WatchlistPageView: View {
    let model: MediaListModel
    @Binding var scrolled: Bool
    @Environment(WatchlistStore.self) private var watchlists
    @Environment(\.horizontalSizeClass) private var sizeClass
    @State private var width: CGFloat = 0
    @State private var confirmingDelete = false
    @State private var renaming = false

    private var wide: Bool { sizeClass.isWide }
    private var beside: Bool { wide && width >= PadLayout.sideBySide }
    private var margin: CGFloat { wide ? PadLayout.margin : 16 }

    private var columns: [GridItem] {
        wide
            ? [GridItem(.adaptive(minimum: PadLayout.posterMinimum), spacing: 16, alignment: .top)]
            : Array(repeating: GridItem(.flexible(), spacing: 8, alignment: .top), count: 3)
    }

    var body: some View {
        HStack(spacing: 0) {
            if beside {
                WatchlistsPane(current: model.listID)
                    .frame(width: 300)
                Rectangle().fill(Palette.separator).frame(width: 1).ignoresSafeArea(edges: .bottom)
            }
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    header
                    if model.countsLine == nil {
                        EmptyState(systemImage: "bookmark", title: WatchlistWords.emptyTitle, message: WatchlistWords.emptyMessage)
                            .frame(maxWidth: .infinity)
                    } else {
                        controls
                        LazyVGrid(columns: columns, spacing: 16) {
                            ForEach(model.entries) { entry in
                                EntryCard(entry: entry, canRequest: model.canRequest(entry), model: model)
                            }
                        }
                    }
                }
                .padding(.horizontal, margin)
                .padding(.bottom, 24)
            }
            .refreshable { await model.reload() }
            .onScrollGeometryChange(for: Bool.self) { $0.contentOffset.y > 60 } action: { _, past in scrolled = past }
        }
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width = $0 }
        .sheet(isPresented: $renaming) {
            WatchlistNameSheet(title: WatchlistWords.rename, action: WatchlistWords.done, initial: model.title) { await model.rename(to: $0) }
        }
    }

    /// Watchlist, the name with ⋯ beside it, and "12 titles · 9 in your library".
    private var header: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(WatchlistWords.kicker.uppercased())
                .font(.caption.weight(.bold))
                .tracking(0.6)
                .foregroundStyle(Palette.gold)
            HStack(alignment: .firstTextBaseline, spacing: 10) {
                Text(model.title)
                    .font(.largeTitle.weight(.bold))
                    .foregroundStyle(Palette.text)
                    .accessibilityAddTraits(.isHeader)
                    .frame(maxWidth: .infinity, alignment: .leading)
                menu
            }
            if let counts = model.countsLine {
                Text(counts).font(.subheadline).foregroundStyle(Palette.muted)
            }
        }
        .padding(.top, 4)
    }

    private var menu: some View {
        Menu {
            Button { renaming = true } label: { Label(WatchlistWords.rename, systemImage: "pencil") }
            Toggle(isOn: Binding(get: { model.removesWatched }, set: { on in Task { await model.setRemovesWatched(on) } })) {
                Text(WatchlistWords.removeWatched)
                Text(WatchlistWords.removeWatchedDetail)
            }
            Divider()
            Button(role: .destructive) { confirmingDelete = true } label: { Label(WatchlistWords.delete, systemImage: "trash") }
        } label: {
            Image(systemName: "ellipsis")
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(Palette.text)
                .frame(width: 44, height: 44)
                .background(Palette.surfaceRaised, in: Circle())
        }
        .accessibilityLabel(WatchlistWords.moreLabel(model.title))
        .accessibilityIdentifier("watchlist-more")
        .confirmationDialog(model.deletePrompt, isPresented: $confirmingDelete, titleVisibility: .visible) {
            Button(WatchlistWords.delete, role: .destructive) { Task { await model.delete() } }
        } message: {
            Text(model.deleteMessage)
        }
    }

    /// All, Movies and TV shows, then the sort.
    private var controls: some View {
        HStack(spacing: 8) {
            ForEach([nil, MediaType.movie, .tv], id: \.self) { type in
                Chip(WatchlistWords.filterName(type), selected: model.filter == type) { model.filter = type }
            }
            Spacer(minLength: 0)
            Menu {
                ForEach(ListSort.allCases, id: \.self) { sort in
                    Toggle(WatchlistWords.sortName(sort), isOn: Binding(get: { model.sort == sort }, set: { if $0 { Task { await model.setSort(sort) } } }))
                }
            } label: {
                Text("\(WatchlistWords.sortName(model.sort)) \(Image(systemName: "chevron.down"))")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Palette.textSecondary)
                    .frame(minHeight: 44)
            }
            .accessibilityLabel("Sort: \(WatchlistWords.sortName(model.sort))")
        }
    }
}

/// Beside a watchlist's page on a wide window: the person's watchlists,
/// the open one marked, and New watchlist. Choosing one opens it in place.
private struct WatchlistsPane: View {
    let current: Int
    @Environment(WatchlistStore.self) private var watchlists
    @Environment(Navigator.self) private var navigator
    @State private var naming = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text(WatchlistWords.title).font(.title2.weight(.bold)).foregroundStyle(Palette.text).accessibilityAddTraits(.isHeader)
                RoundedGroup {
                    ForEach(watchlists.lists) { list in
                        Button { show(list.id) } label: {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(list.name).font(.body.weight(.semibold)).foregroundStyle(list.id == current ? Palette.gold : Palette.text)
                                Text(WatchlistWords.counts(titles: list.titles, inLibrary: list.inLibrary)).font(.footnote).foregroundStyle(Palette.muted)
                                if !list.removeWatched { Text(WatchlistWords.keepsWatched).font(.footnote).foregroundStyle(Palette.muted) }
                            }
                            .padding(.horizontal, 14)
                            .padding(.vertical, 10)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(list.id == current ? Palette.gold.opacity(0.10) : .clear)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(SettingsRowStyle())
                        .accessibilityAddTraits(list.id == current ? .isSelected : [])
                    }
                }
                if watchlists.canMakeMore {
                    Button { naming = true } label: { Label(WatchlistWords.newWatchlist, systemImage: "plus") }
                        .buttonStyle(GhostButtonStyle(height: 44, fullWidth: false))
                }
            }
            .padding(PadLayout.margin)
        }
        .sheet(isPresented: $naming) {
            WatchlistNameSheet(title: WatchlistWords.newWatchlist, action: WatchlistWords.create, initial: "") { name in
                switch await watchlists.create(name: name) {
                case .success(let list):
                    show(list.id)
                    return nil
                case .failure(let refusal): return refusal.line
                }
            }
        }
    }

    /// Opens another watchlist in place of this one, so Back still leaves the watchlists.
    private func show(_ id: Int) {
        guard id != current else { return }
        var path = navigator.path(for: navigator.tab)
        if path.last == .mediaList(id: current) { path.removeLast() }
        path.append(.mediaList(id: id))
        var quiet = Transaction()
        quiet.disablesAnimations = true
        withTransaction(quiet) { navigator.setPath(path, for: navigator.tab) }
    }
}
