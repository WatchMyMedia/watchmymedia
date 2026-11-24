import Foundation
import WMMModel

/// What leads Apple TV's Home, as it leads Home on every app: one large card
/// for the title the person is most likely to go back to, the first in
/// Continue watching, with how much is left and when it would end. With
/// nothing in progress the server's pick (a new episode, a film just added)
/// takes the card. The rows follow, Continue watching without the title on
/// the card.
public enum HomeLead: Hashable, Sendable {
    /// Something in progress: Select resumes it.
    case resume(ContinueItem)
    /// Nothing in progress: the server's pick. Select opens its page.
    case pick(Hero)

    /// The lead, and the rows to draw under it, from the rows Home draws. The
    /// first Continue watching row with something in it gives its first title
    /// and keeps the rest (or goes, when that was all); without one, the
    /// server's pick leads and the rows stay as they are. Nil with neither.
    public static func split(_ rows: [ScreenRow], hero: Hero?) -> (lead: HomeLead?, rows: [ScreenRow]) {
        guard let index = rows.firstIndex(where: { $0.style == .continue && !$0.continueItems.isEmpty }) else {
            return (hero.map(HomeLead.pick), rows)
        }
        var rest = rows
        var row = rest[index]
        let first = row.continueItems.removeFirst()
        if row.continueItems.isEmpty { rest.remove(at: index) } else { rest[index] = row }
        return (.resume(first), rest)
    }

    /// The card's picture: an episode's still, else the backdrop.
    public var picturePath: String? {
        switch self {
        case .resume(let item): item.stillPath ?? item.backdropPath
        case .pick(let hero): hero.backdropPath
        }
    }

    /// The server the picture is on, when that isn't the one in use.
    public var server: String? {
        if case .resume(let item) = self { return item.server }
        return nil
    }

    /// Seeds the placeholder while the picture loads.
    public var seed: String {
        switch self {
        case .resume(let item): item.title
        case .pick(let hero): hero.title
        }
    }

    /// How far along, for the bar on the card; nil for the server's pick.
    public var progress: Double? {
        if case .resume(let item) = self { return item.progress }
        return nil
    }

    /// Identifies the title on the card, so the screen knows when it changes.
    public var key: String {
        switch self {
        case .resume(let item): "resume-\(item.cardKey)"
        case .pick(let hero): "pick-\(hero.kind.rawValue)-\(hero.episodeID ?? hero.movieID ?? hero.showID ?? 0)"
        }
    }
}

extension TVFocusWords {
    /// The words beside Home's large card: what it is, the episode, how long
    /// is left in gold and when it would end, the rating and the overview.
    /// The server's pick says why it was picked ("New episode · aired
    /// yesterday") and its length. No file or stream detail.
    public init(lead: HomeLead, now: Date = .now, clock: LiveClock = .device) {
        switch lead {
        case .resume(let item):
            self.init(
                kicker: continueWatchingTitle,
                title: item.title,
                subtitle: item.kind == .movie ? nil : item.subtitle,
                lead: formatRemaining(ms: item.remainingMs),
                facts: [endsAtLine(remainingMs: item.remainingMs, now: now, clock: clock)],
                rating: item.contentRating,
                overview: item.overview ?? ""
            )
        case .pick(let hero):
            // "S03 E07 · The Long Dark · 52 min": the episode goes under the title, its length with the facts.
            let parts = hero.factParts
            let episode = hero.kind == .episode && parts.count >= 2
            self.init(
                kicker: hero.kicker.isEmpty ? nil : hero.kicker,
                title: hero.title,
                subtitle: episode ? parts.prefix(2).joined(separator: " · ") : nil,
                facts: episode ? Array(parts.dropFirst(2)) : parts,
                rating: hero.contentRating,
                overview: hero.overview
            )
        }
    }
}
