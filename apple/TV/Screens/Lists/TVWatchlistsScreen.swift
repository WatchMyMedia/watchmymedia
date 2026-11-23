import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// Watchlists on Apple TV, from Servers: New watchlist as the first tile,
/// then the person's watchlists in the order made, each with its first four
/// posters, its counts and Keeps watched titles. Select opens one. With
/// none yet it says how to start one, with New watchlist under it.
struct TVWatchlistsScreen: View {
    @Environment(WatchlistStore.self) private var watchlists
    @Environment(AppServices.self) private var services
    @Environment(Navigator.self) private var navigator
    @State private var naming = false
    @State private var name = ""

    private let columns = Array(repeating: GridItem(.fixed(380), spacing: 40, alignment: .top), count: 4)

    var body: some View {
        TabPage {
            VStack(alignment: .leading, spacing: 40) {
                VStack(alignment: .leading, spacing: 10) {
                    Text(WatchlistWords.title)
                        .font(.system(size: 56, weight: .heavy))
                        .tracking(-1.1)
                        .foregroundStyle(Palette.text)
                        .accessibilityAddTraits(.isHeader)
                    if let user = services.auth.user, !watchlists.lists.isEmpty {
                        Text(WatchlistWords.tvSummary(person: user.name, count: watchlists.lists.count))
                            .font(.system(size: 23))
                            .foregroundStyle(Palette.muted)
                    }
                }
                if watchlists.loaded && watchlists.lists.isEmpty {
                    VStack(spacing: 30) {
                        TVEmptyState(systemImage: "bookmark", title: WatchlistWords.noneTitle, message: WatchlistWords.noneMessage)
                        newButton
                    }
                    .padding(.top, 60)
                } else {
                    LazyVGrid(columns: columns, alignment: .leading, spacing: 50) {
                        if watchlists.canMakeMore { NewTile { startNaming() } }
                        ForEach(watchlists.lists) { list in
                            Button { navigator.open(.mediaList(id: list.id)) } label: { WatchlistTile(list: list) }
                                .buttonStyle(.borderless)
                                .accessibilityLabel(WatchlistWords.rowLabel(list))
                                .accessibilityIdentifier("watchlist-\(list.id)")
                        }
                    }
                    .focusSection()
                }
            }
            .padding(.horizontal, TVLayout.gutter)
            .padding(.top, 16)
            .padding(.bottom, 60)
        }
        .task { await watchlists.refresh() }
        .alert(WatchlistWords.newWatchlist, isPresented: $naming) {
            TextField(WatchlistWords.name, text: $name)
            Button(WatchlistWords.cancel, role: .cancel) {}
            Button(WatchlistWords.create) {
                let name = name
                Task {
                    switch await watchlists.create(name: name) {
                    case .success(let list): navigator.open(.mediaList(id: list.id))
                    case .failure(let refusal): if !refusal.line.isEmpty { watchlists.message = refusal.line }
                    }
                }
            }
        }
    }

    private var newButton: some View {
        Button(WatchlistWords.newWatchlist) { startNaming() }
            .buttonStyle(TVButtonStyle(kind: .primary, height: 62, fontSize: 22))
            .accessibilityIdentifier("watchlists-new")
    }

    private func startNaming() {
        name = ""
        naming = true
    }
}

/// The first tile: a bookmark with a small plus, and New watchlist.
private struct NewTile: View {
    let action: () -> Void

    var body: some View {
        Button(action: action) { Face() }
            .buttonStyle(.borderless)
            .accessibilityLabel(WatchlistWords.newWatchlist)
            .accessibilityIdentifier("watchlists-new")
    }

    private struct Face: View {
        @Environment(\.isFocused) private var focused

        var body: some View {
            VStack(alignment: .leading, spacing: 14) {
                RoundedRectangle(cornerRadius: 18)
                    .fill(focused ? Palette.text : .white.opacity(0.06))
                    .frame(width: 380, height: 214)
                    .overlay {
                        Image(systemName: "bookmark")
                            .font(.system(size: 56, weight: .medium))
                            .foregroundStyle(focused ? Palette.ground : Palette.gold)
                            .overlay(alignment: .bottomTrailing) {
                                Image(systemName: "plus.circle.fill").font(.system(size: 26)).foregroundStyle(focused ? Palette.ground : Palette.gold).offset(x: 14, y: 6)
                            }
                    }
                    .focusLift(focused)
                Text(WatchlistWords.newWatchlist).font(.system(size: 24, weight: .semibold)).foregroundStyle(Palette.text)
            }
        }
    }
}

/// A watchlist's tile: its first four posters overlapping on a shelf, its name, counts and Keeps watched titles.
private struct WatchlistTile: View {
    let list: Watchlist
    @Environment(AppServices.self) private var services
    @Environment(\.isFocused) private var focused

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            RoundedRectangle(cornerRadius: 18)
                .fill(.white.opacity(focused ? 0.16 : 0.06))
                .frame(width: 380, height: 214)
                .overlay(alignment: .leading) {
                    ZStack(alignment: .leading) {
                        ForEach(Array(list.posters.prefix(4).enumerated()), id: \.offset) { i, path in
                            Artwork(url: services.library.imageURL(path, size: .w342), seed: path, maxPixel: 300)
                                .frame(width: 120, height: 180)
                                .clipShape(RoundedRectangle(cornerRadius: 10))
                                .shadow(color: .black.opacity(0.5), radius: 8, y: 4)
                                .offset(x: 16 + CGFloat(i) * 79)
                                .zIndex(Double(4 - i))
                        }
                    }
                }
                .focusLift(focused)
            VStack(alignment: .leading, spacing: 4) {
                Text(list.name).font(.system(size: 24, weight: .semibold)).foregroundStyle(Palette.text).lineLimit(1)
                Text(WatchlistWords.counts(titles: list.titles, inLibrary: list.inLibrary)).font(.system(size: 19)).foregroundStyle(Palette.muted)
                if !list.removeWatched {
                    Text(WatchlistWords.keepsWatched)
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(Palette.gold)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 4)
                        .background(Palette.gold.opacity(0.14), in: Capsule())
                }
            }
            .offset(y: focused ? 10 : 0)
            .animation(.easeOut(duration: 0.18), value: focused)
        }
        .frame(width: 380, alignment: .leading)
    }
}
