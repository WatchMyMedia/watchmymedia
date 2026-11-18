import SwiftUI
import WMMUI

/// Where a row of cards starts (RowStartBest.dc.html; Android TV's `RowStart`).
/// Moving back along a row, tvOS keeps the focused card at the row's leading
/// margin, so the 2nd card sat where the 1st does and only a sliver of the
/// 1st showed at the screen's edge: nothing said whether the row went on.
/// Here the card before the focused one stays whole in view, so at the start
/// focus itself moves into the first place; cards scrolled past fade at the
/// screen's edge; and Left on the first card, which has nowhere to go (the
/// tabs are at the top), gives the row a small bump instead of nothing.
///
/// A row puts ``rowStartCard(_:in:)`` on each card and this on its
/// horizontal `ScrollView`, passing its cards' ids in order, the focused one,
/// and a count that goes up with each Left on the first card.
struct RowStartScrolling<ID: Hashable>: ViewModifier {
    let ids: [ID]
    let focused: ID?
    let cards: RowStartCards<ID>
    let bumps: Int

    @State private var position = ScrollPosition()
    @State private var scrolledPast = false
    @State private var give: CGFloat = 0

    func body(content: Content) -> some View {
        content
            .scrollPosition($position)
            // From the row's start: ScrollPosition's x counts from there, while contentOffset is -margin at the start.
            .onScrollGeometryChange(for: CGFloat.self) { $0.contentOffset.x + $0.contentInsets.leading } action: { _, x in cards.offset = x }
            .onScrollGeometryChange(for: Bool.self) { $0.contentOffset.x + $0.contentInsets.leading > 1 } action: { _, past in scrolledPast = past }
            .onChange(of: focused) { old, new in keepCardBeforeInView(from: old, to: new) }
            .onChange(of: bumps) { giveAndSpringBack() }
            .offset(x: give)
            .overlay(alignment: .leading) {
                if scrolledPast {
                    LinearGradient(colors: [Palette.ground, Palette.ground.opacity(0)], startPoint: .leading, endPoint: .trailing)
                        .frame(width: RowStartCards<ID>.fade)
                        .allowsHitTesting(false)
                        .accessibilityHidden(true)
                }
            }
    }

    /// Focus moved back along the row onto a card whose neighbour before it is
    /// partly or wholly past the leading margin: scroll that neighbour to the
    /// margin. Moving on, or onto a card whose neighbour shows whole, the
    /// system's own scrolling stands.
    private func keepCardBeforeInView(from old: ID?, to new: ID?) {
        guard let old, let new, let from = ids.firstIndex(of: old), let to = ids.firstIndex(of: new), to < from, to > 0,
              let x = cards.minX[ids[to - 1]], x < -0.5 else { return }
        // A card's place is measured from the leading margin (the scroll view's
        // visible bounds start inside its content margins), so x is how far past
        // the margin the card before has gone.
        let target = cards.offset + x
        withAnimation(.easeOut(duration: 0.25)) { position.scrollTo(x: target) }
    }

    private func giveAndSpringBack() {
        withAnimation(.easeOut(duration: 0.13)) { give = RowStartCards<ID>.give } completion: {
            withAnimation(.spring(response: 0.35, dampingFraction: 0.55)) { give = 0 }
        }
    }
}

/// Where each card of a row sits, kept out of SwiftUI's sight: it changes on
/// every frame of a scroll, and the row needs it only when focus moves.
final class RowStartCards<ID: Hashable> {
    /// Each card's leading edge from the row's leading margin: negative once it is past it, into the gutter.
    var minX: [ID: CGFloat] = [:]
    /// How far the row has scrolled from its start.
    var offset: CGFloat = 0

    /// How far the row gives when Left meets its first card.
    static var give: CGFloat { 34 }
    /// The fade over cards scrolled past, at the screen's edge: inside the
    /// gutter, short of the focused card's lift.
    static var fade: CGFloat { 64 }
}

extension View {
    /// A card in a row with ``RowStartScrolling``: it tells the row where it
    /// sits.
    func rowStartCard<ID: Hashable>(_ id: ID, in cards: RowStartCards<ID>) -> some View {
        onGeometryChange(for: CGFloat.self) { $0.frame(in: .scrollView(axis: .horizontal)).minX } action: { cards.minX[id] = $0 }
    }

    /// Left on the row's first card, where focus has nowhere to go, calls `bump`.
    func rowStartBump(first: Bool, _ bump: @escaping () -> Void) -> some View {
        onMoveCommand(perform: first ? { direction in if direction == .left { bump() } } : nil)
    }
}
