import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// Watchlists, from the account button's Servers: the person's watchlists
/// in the order made, each with how many titles it has and how many the
/// library has, whether it keeps watched titles, and its first posters;
/// then New watchlist. On iPhone a list of rows; on iPad cards, as many a
/// row as fit. With none yet it says how to start one. Each opens its page.
struct WatchlistsScreen: View {
    @Environment(WatchlistStore.self) private var watchlists
    @Environment(Navigator.self) private var navigator
    @Environment(\.horizontalSizeClass) private var sizeClass
    @State private var naming = false

    private var wide: Bool { sizeClass.isWide }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                if watchlists.loaded && watchlists.lists.isEmpty {
                    EmptyState(systemImage: "bookmark", title: WatchlistWords.noneTitle, message: WatchlistWords.noneMessage)
                        .frame(maxWidth: .infinity)
                    newButton.frame(maxWidth: .infinity)
                } else if wide {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 300), spacing: 18, alignment: .top)], spacing: 18) {
                        ForEach(watchlists.lists) { WatchlistCard(list: $0, wide: true) }
                    }
                } else {
                    ForEach(watchlists.lists) { WatchlistCard(list: $0, wide: false) }
                    newButton
                }
            }
            .padding(.horizontal, wide ? PadLayout.margin : 16)
            .padding(.bottom, 24)
        }
        .refreshable { await watchlists.refresh() }
        .groundBackground()
        .navigationTitle(WatchlistWords.title)
        .navigationBarTitleDisplayMode(.large)
        .toolbar {
            // On iPad the page's own button sits by its title, as the toolbar beside the tabs holds little.
            if wide, !watchlists.lists.isEmpty, watchlists.canMakeMore {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { naming = true } label: { Label(WatchlistWords.newWatchlist, systemImage: "plus") }
                        .accessibilityIdentifier("watchlists-new")
                }
            }
        }
        .overlay { if !watchlists.loaded, watchlists.error == nil { ProgressView().tint(Palette.muted) } }
        .task { await watchlists.refresh() }
        .sheet(isPresented: $naming) {
            WatchlistNameSheet(title: WatchlistWords.newWatchlist, action: WatchlistWords.create, initial: "") { name in
                switch await watchlists.create(name: name) {
                case .success(let list):
                    navigator.open(.mediaList(id: list.id))
                    return nil
                case .failure(let refusal): return refusal.line
                }
            }
        }
    }

    private var newButton: some View {
        Button { naming = true } label: { Label(WatchlistWords.newWatchlist, systemImage: "plus") }
            .buttonStyle(GhostButtonStyle(height: 46, fullWidth: false))
            .disabled(!watchlists.canMakeMore)
            .accessibilityIdentifier("watchlists-new")
    }
}

/// One watchlist: its name, counts and Keeps watched titles, with its first
/// four posters; opens its page.
private struct WatchlistCard: View {
    let list: Watchlist
    let wide: Bool
    @Environment(Navigator.self) private var navigator
    @Environment(AppServices.self) private var services

    var body: some View {
        Button { navigator.open(.mediaList(id: list.id)) } label: {
            VStack(alignment: .leading, spacing: 12) {
                if wide { posters }
                HStack(alignment: .top, spacing: 10) {
                    VStack(alignment: .leading, spacing: 3) {
                        Text(list.name).font(.headline).foregroundStyle(Palette.text)
                        Text(WatchlistWords.counts(titles: list.titles, inLibrary: list.inLibrary)).font(.subheadline).foregroundStyle(Palette.muted)
                        if !list.removeWatched {
                            Text("\(Image(systemName: "checkmark.circle")) \(WatchlistWords.keepsWatched)")
                                .font(.footnote.weight(.semibold))
                                .foregroundStyle(Palette.gold)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    if !wide { RowChevron().padding(.top, 4) }
                }
                if !wide { posters }
            }
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Palette.surfaceRaised, in: RoundedRectangle(cornerRadius: 14))
            .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(Palette.separator))
            .contentShape(RoundedRectangle(cornerRadius: 14))
        }
        .buttonStyle(CardButtonStyle())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(WatchlistWords.rowLabel(list))
        .accessibilityAddTraits(.isButton)
        .accessibilityIdentifier("watchlist-\(list.id)")
    }

    /// The first four titles' posters, side by side.
    private var posters: some View {
        HStack(spacing: 6) {
            ForEach(Array(list.posters.prefix(4).enumerated()), id: \.offset) { _, path in
                Color.clear
                    .aspectRatio(2 / 3, contentMode: .fit)
                    .overlay { Artwork(url: services.library.imageURL(path, size: .w185), seed: path) }
                    .clipShape(RoundedRectangle(cornerRadius: 6))
            }
            // Room kept for the posters a short watchlist doesn't have.
            ForEach(0..<max(0, 4 - list.posters.count), id: \.self) { _ in
                RoundedRectangle(cornerRadius: 6).fill(Palette.surface).aspectRatio(2 / 3, contentMode: .fit)
            }
        }
        .frame(maxWidth: wide ? .infinity : 260, alignment: .leading)
        .accessibilityHidden(true)
    }
}

/// New watchlist or Rename: a Name field, with Create (or Save) waiting for
/// a name, and why a name can't be used under the field.
struct WatchlistNameSheet: View {
    let title: String
    let action: String
    let initial: String
    /// Nil once done; else why not.
    let submit: (String) async -> String?
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var problem: String?
    @State private var working = false

    var body: some View {
        NavigationStack {
            WatchlistNameField(name: $name, problem: problem) { Task { await done() } }
                .padding(16)
                .frame(maxHeight: .infinity, alignment: .top)
                .navigationTitle(title)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button(WatchlistWords.cancel) { dismiss() } }
                    ToolbarItem(placement: .confirmationAction) {
                        Button(action) { Task { await done() } }
                            .disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || working)
                            .accessibilityIdentifier("watchlist-name-save")
                    }
                }
        }
        .presentationDetents([.medium])
        .presentationBackground(Palette.sheet)
        .onAppear { name = initial }
        .onChange(of: name) { problem = nil }
    }

    private func done() async {
        guard !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, !working else { return }
        working = true
        defer { working = false }
        if let refusal = await submit(name) { problem = refusal } else { dismiss() }
    }
}

/// The Name field New watchlist and Rename share, with the reason a name can't be used under it.
struct WatchlistNameField: View {
    @Binding var name: String
    let problem: String?
    let onSubmit: () -> Void
    @FocusState private var focused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(WatchlistWords.name).font(.footnote.weight(.semibold)).foregroundStyle(Palette.muted)
            TextField(WatchlistWords.name, text: $name)
                .textInputAutocapitalization(.sentences)
                .submitLabel(.done)
                .focused($focused)
                .onSubmit(onSubmit)
                .padding(.horizontal, 14)
                .frame(minHeight: 48)
                .background(Palette.surfaceRaised, in: RoundedRectangle(cornerRadius: 12))
                .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(problem == nil ? Palette.separator : Palette.warning))
                .accessibilityIdentifier("watchlist-name")
            if let problem {
                Text(problem)
                    .font(.footnote)
                    .foregroundStyle(Palette.warning)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("watchlist-name-problem")
            }
        }
        .onAppear { focused = true }
    }
}
