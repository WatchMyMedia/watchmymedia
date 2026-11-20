import Foundation
import Observation
import WMMModel

/// The rows at the end of a movie's or a show's page: the
/// movie's collection, then More like this, as the server works them out,
/// drawn with Home's poster rows.
///
/// The page asks once its details are in, and only a server at API level 30
/// (``ServerFeatures/related``) is asked; an older one is asked nothing. The
/// reply is kept with the page: its later reloads (a mark, the player's
/// progress) ask again only after pull to refresh or a mark on a title in
/// the rows. A reply that fails changes nothing: the page keeps the rows it
/// had, or ends as it did without them, and the next reload asks again.
///
/// The request is the page's own, not the screen's: leaving the page while
/// it is on its way doesn't cancel it, so the rows are there on coming back.
@MainActor
@Observable
public final class RelatedRows {
    /// What the page draws at its end, in the server's order: rows of
    /// posters (or 16:9 cards) that hold something, without See all.
    public private(set) var rows: [ScreenRow] = []

    @ObservationIgnored private let title: RelatedTitle
    @ObservationIgnored private let library: any LibraryRepository
    /// A reply is in.
    @ObservationIgnored private var held = false
    /// The reply is to be asked again with the next load.
    @ObservationIgnored private var stale = false
    @ObservationIgnored private var asking: Task<Void, Never>?

    init(_ title: RelatedTitle, library: any LibraryRepository) {
        self.title = title
        self.library = library
    }

    /// After the page's details arrived: asks when nothing is held yet or it
    /// went stale, one request at a time. Below API level 30 the rows go and
    /// nothing is asked.
    func load() async {
        guard library.offersRelated else {
            rows = []
            held = false
            return
        }
        while let asking { await asking.value }
        guard !held || stale else { return }
        stale = false
        let task = Task { [weak self] in _ = await self?.ask() }
        asking = task
        await task.value
    }

    private func ask() async {
        let reply = await Loadable.run { try await library.relatedRows(title) }
        asking = nil
        guard case .loaded(let found)? = reply else { return }
        rows = Self.drawn(found)
        held = true
    }

    /// The next load asks again: pull to refresh, or a title in the rows was marked.
    func expire() {
        stale = true
    }

    /// Whether `change` names a title in the rows, whose card then shows an
    /// old mark: a movie's watched state or progress, or a whole show marked.
    func touches(_ change: LibraryChange) -> Bool {
        switch change {
        case .watched(.movie, let ids, _):
            rows.contains { $0.items.contains { $0.kind == .movie && ids.contains($0.id) } }
        case .markedShow(let id, _, _):
            rows.contains { $0.items.contains { $0.kind == .show && $0.id == id } }
        case .watched(.episode, _, _), .library, .scan, .unknown:
            false
        }
    }

    /// The rows a page draws from a reply: those of cards, with cards in
    /// them, and no See all, which has nothing to open.
    static func drawn(_ reply: [ScreenRow]) -> [ScreenRow] {
        reply.compactMap { row in
            guard row.style == .poster || row.style == .wide, !row.items.isEmpty else { return nil }
            var drawn = row
            drawn.seeAll = nil
            return drawn
        }
    }
}
