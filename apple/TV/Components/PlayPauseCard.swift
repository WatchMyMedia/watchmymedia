import SwiftUI
import WMMFeatures
import WMMModel

extension View {
    /// Play/Pause on this focused card does what ``CardPlayPause`` says
    /// (PLAN-APPLE-REVIEW.md §6.2): plays it, tunes it, or does `select`
    /// (the card's own Select) when there is nothing single to play. Only the
    /// two players handled the key before; elsewhere it did nothing.
    func playPauseCard(_ card: CardPlayPause, select: @escaping () -> Void) -> some View {
        modifier(PlayPauseCard(card: card, select: select))
    }
}

private struct PlayPauseCard: ViewModifier {
    let card: CardPlayPause
    let select: () -> Void
    @Environment(Navigator.self) private var navigator
    @Environment(AppServices.self) private var services

    func body(content: Content) -> some View {
        content.onPlayPauseCommand {
            // Select's own action, at once; the rest may load a movie first.
            if card == .select { return select() }
            let navigator = navigator, library = services.library, card = card
            Task { await navigator.playPause(card, library: library, select: {}) }
        }
    }
}
