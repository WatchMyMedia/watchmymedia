import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// The shelves at the end of a show's page on Apple TV: More like this,
/// drawn with Home's poster shelves, without See all. Down from the page's
/// last buttons reaches them and Up leaves them; each shelf is a focus
/// section that lands on the card it had (``ShelfRow``), and the focused
/// card lifts as Home's do. Select opens the title's page over this one,
/// and Menu comes back here, to the card; a long press marks it.
///
/// It compares the rows it shows and the page it belongs to, so the page's
/// focus moving above it (which redraws the page) leaves it be.
struct RelatedShelves: View, Equatable {
    let rows: [ScreenRow]
    /// The page's model, which the actions go to: its identity is what's compared.
    let owner: AnyObject
    let open: (BrowseItem) -> Void
    let setWatched: (WatchTarget, Bool) async -> Void

    nonisolated static func == (a: Self, b: Self) -> Bool {
        a.rows == b.rows && a.owner === b.owner
    }

    var body: some View {
        let actions = TVRowActions(open: open, setWatched: setWatched)
        VStack(alignment: .leading, spacing: 14) {
            ForEach(rows) { row in
                TVArrangedRow(row: row, actions: actions)
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("related-titles")
    }
}

/// A movie's collection and More like this on one line, each under its
/// name: Left and Right run through both, and Down from the buttons above
/// lands on the card last focused, else the first. Menu goes back to the
/// buttons (`back`). Select opens the title's page over this one; Play/Pause
/// does the same; a long press adds it to a watchlist or marks it.
///
/// Like ``RelatedShelves`` it compares what it shows, so focus moving above
/// it leaves it be.
struct RelatedLine: View, Equatable {
    let rows: [ScreenRow]
    let owner: AnyObject
    let open: (BrowseItem) -> Void
    let setWatched: (WatchTarget, Bool) async -> Void
    let back: () -> Void

    nonisolated static func == (a: Self, b: Self) -> Bool {
        a.rows == b.rows && a.owner === b.owner
    }

    /// A card's place on the line: its row and its title.
    private struct Key: Hashable {
        let row: String
        let card: String
    }

    @FocusState private var focused: Key?
    @State private var remembered: Key?
    @Environment(AppServices.self) private var services

    private var keys: [Key] { rows.flatMap { row in row.items.map { Key(row: row.id, card: $0.cardID) } } }

    var body: some View {
        ScrollView(.horizontal) {
            HStack(alignment: .top, spacing: 80) {
                ForEach(rows) { row in
                    VStack(alignment: .leading, spacing: 28) {
                        RowHeading(title: row.title, detail: row.id == "collection" ? "In order" : nil)
                        LazyHStack(alignment: .top, spacing: TVLayout.cardSpacing) {
                            ForEach(row.items) { item in
                                card(item, key: Key(row: row.id, card: item.cardID))
                            }
                        }
                    }
                }
            }
            .padding(.bottom, 40)
        }
        .contentMargins(.horizontal, TVLayout.gutter, for: .scrollContent)
        .scrollClipDisabled()
        .scrollIndicators(.hidden)
        .focusSection()
        .defaultFocus($focused, remembered ?? keys.first, priority: .userInitiated)
        .onChange(of: focused) { old, key in
            guard let key else { return }
            // Coming in from above lands on the card last focused, else the first:
            // the focus engine would pick the card nearest the way it came.
            if old == nil, let wanted = remembered ?? keys.first, wanted != key, keys.contains(wanted) {
                focused = wanted
                return
            }
            remembered = key
        }
        .onExitCommand(perform: back)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("related-titles")
    }

    private func card(_ item: BrowseItem, key: Key) -> some View {
        Button { open(item) } label: {
            PosterCard(item: item, imageURL: services.library.imageURL(item.posterPath, size: .w500), caption: false)
        }
        .buttonStyle(.borderless)
        .focused($focused, equals: key)
        .playPauseCard(.card(item)) { open(item) }
        .contextMenu {
            TVWatchlistMenuButton(item)
            if let target = item.watchTarget {
                WatchToggleButton(watched: item.watched) { await setWatched(target, $0) }
            }
        }
    }
}
