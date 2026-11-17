import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// The rows at the end of a movie's or a show's page (PLAN-RELATED.md §7;
/// MovieRelated, ShowRelated, Pad2MovieRelated and Pad2ShowRelated.dc.html):
/// the movie's collection and More like this, drawn with Home's poster rows
/// and cards, without See all. A card opens its title's page over this one,
/// so Back comes back here, to the row; a long press marks it. With no rows
/// nothing is drawn and the page ends as it did.
struct RelatedSection: View {
    let rows: [ScreenRow]
    /// The page's side margin, which the headings and the first cards line up with.
    let margin: CGFloat
    /// Above the first row and below the last, when there is one.
    var top: CGFloat = 24
    var bottom: CGFloat = 0
    let open: (BrowseItem) -> Void
    let setWatched: (WatchTarget, Bool) async -> Void
    @Environment(\.horizontalSizeClass) private var sizeClass

    var body: some View {
        if !rows.isEmpty {
            let actions = RowActions(open: open, setWatched: setWatched, seeAll: { _ in nil })
            VStack(alignment: .leading, spacing: sizeClass.isWide ? 26 : 24) {
                ForEach(rows) { row in
                    ArrangedRow(row: row, actions: actions, margin: margin)
                }
            }
            .padding(.top, top)
            .padding(.bottom, bottom)
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("related-titles")
        }
    }
}
