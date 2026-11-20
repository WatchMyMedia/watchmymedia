import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// The shelves at the end of a movie's or a show's page
/// on Apple TV: the
/// movie's collection and More like this, drawn with Home's poster shelves,
/// without See all. Down from the page's last buttons reaches them and Up
/// leaves them; each shelf is a focus section that lands on the card it had
/// (``ShelfRow``), and the focused card lifts as Home's do. Select opens the
/// title's page over this one, and Menu comes back here, to the card; a long
/// press marks it.
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

/// What lies below the first screen, along its bottom edge: "⌄ Carrow Coast
/// Collection · More like this", as Home
/// names its next row. VoiceOver reaches the shelves themselves.
struct ShelvesBelowLine: View {
    let text: String

    var body: some View {
        Label(text, systemImage: "chevron.down")
            .font(.system(size: 19, weight: .semibold))
            .foregroundStyle(Palette.muted)
            .lineLimit(1)
            .accessibilityHidden(true)
    }
}
