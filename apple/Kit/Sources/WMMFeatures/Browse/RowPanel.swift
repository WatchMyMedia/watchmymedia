import Foundation
import WMMModel

// Home one row a screen on Apple TV and iPad (PLAN-HOME.md §7; TvHomeStart,
// TvHomeRow, Pad2HomeStart, Pad2HomeRow and the portrait boards): every row
// after the first fills a screen, and the row's current card (the focused
// one on TV, the leading one on iPad) fills the top with its picture and a
// panel. The panel's words are decided here, by row style, so both apps say
// the same and the wording is tested.

/// What the panel above a row says about the row's current card, and what it does.
public struct RowPanel: Hashable, Sendable {
    /// What a panel button, or Select on TV, does.
    public enum Action: Hashable, Sendable {
        /// The card's own opening: its page, or an unmatched film's file.
        case open(BrowseItem)
        /// The show a one-episode card belongs to.
        case openShow(id: Int)
        case openEpisode(EpisodeCard)
        case playEpisode(EpisodeCard)
        case resume(ContinueItem)
        /// The show or movie a Continue watching item belongs to.
        case openPage(ContinueItem)
        case watchChannel(id: String)
        case openGuide
    }

    /// A button on the iPad's panel: the two the hero would have for the item.
    public struct Button: Hashable, Sendable {
        public var label: String
        public var action: Action

        public init(_ label: String, _ action: Action) {
            self.label = label
            self.action = action
        }
    }

    /// "Recently added · S03 E07 · added today", "New episode · aired yesterday", "#1 on the list".
    public var kicker: String
    public var title: String
    /// "2022", "27 episodes", "Drama": drawn with dots between them.
    public var facts: [String]
    /// "4K", "HDR": outlined tags after the facts.
    public var tags: [String]
    /// "★ 7.0", after the tags; nil when the item has none.
    public var rating: String?
    /// Two lines on screen; may be empty.
    public var overview: String
    /// The 16:9 picture's library path (backdrop or still), for `imageURL(_:size:)`.
    public var picturePath: String?
    /// A picture by absolute URL instead: a Live TV programme's.
    public var pictureURL: URL?
    /// The placeholder gradient's seed while the picture loads.
    public var seed: String
    /// What Select does, for the TV hint: "opens the episode", "resumes", "watches 7.1".
    public var opens: String
    /// The iPad's buttons, the main one first; empty for a card with nowhere to go.
    public var buttons: [Button]

    public init(kicker: String, title: String, facts: [String], tags: [String] = [], rating: String? = nil, overview: String = "", picturePath: String? = nil, pictureURL: URL? = nil, seed: String, opens: String, buttons: [Button]) {
        self.kicker = kicker
        self.title = title
        self.facts = facts
        self.tags = tags
        self.rating = rating
        self.overview = overview
        self.picturePath = picturePath
        self.pictureURL = pictureURL
        self.seed = seed
        self.opens = opens
        self.buttons = buttons
    }

    /// The TV's hint after the Select key: "opens the episode · ◀ ▶ more in this row · ▼ next row".
    /// `moreInRow` is false for a row of one card.
    public func hint(moreInRow: Bool = true) -> String {
        [opens, moreInRow ? "◀ ▶ more in this row" : nil, "▼ next row"].compactMap { $0 }.joined(separator: " · ")
    }

    /// What VoiceOver says for the panel: the kicker, title, facts, tags and overview, in that order.
    public var accessibilityLabel: String {
        [kicker, title, (facts + tags + [rating].compactMap { $0 }).joined(separator: ", "), overview].filter { !$0.isEmpty }.joined(separator: ". ")
    }

    /// The main button's action: what Select does on TV and the picture does on iPad.
    public var mainAction: Action? { buttons.first?.action }
}

extension RowPanel {
    /// A show or movie card in a `poster` or `wide` row. `position` is the
    /// card's place in the row (0 first), for a list's "#1 on the list".
    public init(row: ScreenRow, item: BrowseItem, position: Int, today: Date = .now) {
        let heading = row.heading.title
        let kicker: String
        if row.isList {
            kicker = "#\(position + 1) on the list"
        } else if row.style == .wide, let released = formatDate(item.releaseDate) {
            kicker = "Released \(released)"
        } else if let line = item.subtitle, !line.isEmpty {
            // "Recently added · S03 E07 · added today"; a movie's "added today" reads "Recently added · today".
            kicker = "\(heading) · \(item.kind == .movie && line.hasPrefix("added ") ? String(line.dropFirst(6)) : line)"
        } else {
            kicker = heading
        }
        var facts: [String] = []
        switch item.kind {
        case .show:
            facts = [item.year.map(String.init), item.episodeCount.map { plural($0, "episode") }, item.genres.first].compactMap { $0 }
        case .movie:
            facts = [item.year.map(String.init), item.runtimeMin.map { formatDuration(ms: $0 * 60_000) }, item.genres.first].compactMap { $0 }
        case .unmatched:
            facts = [item.fileName ?? item.gridSubtitle].compactMap { $0 }
        }
        let opens: String
        var buttons: [Button] = []
        switch item.opening {
        case .page(.episode):
            opens = "opens the episode"
            buttons = [Button("Go to episode", .open(item)), Button("Go to show", .openShow(id: item.id))]
        case .page(.movie):
            opens = "opens the movie"
            buttons = [Button("Go to movie", .open(item))]
        case .page:
            opens = "opens the show"
            buttons = [Button("Go to show", .open(item))]
        case .play:
            opens = "plays the file"
            buttons = [Button("Play", .open(item))]
        case .needsMatching:
            opens = "is not matched yet"
        }
        self.init(
            kicker: kicker, title: item.title, facts: facts,
            tags: item.kind == .unmatched ? [] : [item.quality, item.hdr ? "HDR" : nil].compactMap { $0 },
            rating: formatRating(item.rating), overview: item.overview,
            picturePath: item.backdropPath, seed: item.title, opens: opens, buttons: buttons
        )
    }

    /// An episode card in an `episode` row (Recently aired).
    public init(row: ScreenRow, episode: EpisodeCard, today: Date = .now) {
        let aired = formatDay(episode.airDate, today: today).map { "aired \($0)" }
        var buttons: [Button] = []
        if episode.playRequest != nil { buttons.append(Button("Play", .playEpisode(episode))) }
        buttons.append(Button("Go to episode", .openEpisode(episode)))
        self.init(
            kicker: [episode.isNew ? "New episode" : episode.watched ? "Watched" : "Episode", aired].compactMap { $0 }.joined(separator: " · "),
            title: episode.title.isEmpty ? "Episode \(episode.episode)" : episode.title,
            facts: [episode.showTitle, episodeCode(season: episode.season, episode: episode.episode), episode.hasFile ? nil : "Not in library"].compactMap { $0 },
            overview: episode.overview,
            picturePath: episode.stillPath ?? episode.showBackdropPath, seed: episode.showTitle,
            opens: "opens the episode", buttons: buttons
        )
    }

    /// A Continue watching item, when its row is not the first.
    public init(row: ScreenRow, continuing item: ContinueItem) {
        var buttons = [Button("Resume", .resume(item))]
        if item.pageRoute != nil { buttons.append(Button(item.pageLabel, .openPage(item))) }
        self.init(
            kicker: "\(row.heading.title) · \(formatRemaining(ms: item.remainingMs))",
            title: item.title,
            facts: item.subtitle.map { $0.components(separatedBy: " · ") } ?? [],
            picturePath: item.stillPath ?? item.backdropPath, seed: item.title,
            opens: "resumes", buttons: buttons
        )
    }

    /// A channel in the Live TV row: what is on now, or the channel alone without listings.
    public init(channel: LiveChannel, now: Date, clock: LiveClock = .device) {
        let programme = channel.programme(at: now)
        var kicker = "On now · \(channel.number) \(channel.name)"
        if let programme { kicker += " · \(clock.range(programme.start, programme.end))" }
        self.init(
            kicker: kicker,
            title: programme?.title ?? channel.name,
            facts: programme.map { [liveTimeLeft($0, now: now), $0.categoryLine].compactMap { $0 } } ?? ["No listings"],
            overview: programme?.description ?? "",
            pictureURL: programme?.picture(wide: true)?.url, seed: programme?.title ?? channel.name,
            opens: "watches \(channel.number)",
            buttons: [Button(liveWatchLabel(channel, programme: programme, now: now), .watchChannel(id: channel.id)), Button("Full guide", .openGuide)]
        )
    }

    /// The Live TV row's Full guide card.
    public static let fullGuide = RowPanel(
        kicker: "Live TV", title: "Full guide", facts: ["Every channel and what’s next"], seed: "Full guide",
        opens: "opens the guide", buttons: [Button("Full guide", .openGuide)]
    )

    /// The panel for the card with `key` in `row` (``BrowseItem/cardID``,
    /// ``EpisodeCard/cardKey``, ``ContinueItem/cardKey``), or for the row's
    /// first card when `key` is nil or not in the row; nil for an empty or
    /// Live TV row, whose channels the app supplies.
    public static func panel(for row: ScreenRow, card key: String?, today: Date = .now) -> RowPanel? {
        switch row.style {
        case .poster, .wide:
            guard !row.items.isEmpty else { return nil }
            let index = row.items.firstIndex { $0.cardID == key } ?? 0
            return RowPanel(row: row, item: row.items[index], position: index, today: today)
        case .episode:
            guard let episode = row.episodes.first(where: { $0.cardKey == key }) ?? row.episodes.first else { return nil }
            return RowPanel(row: row, episode: episode, today: today)
        case .continue:
            guard let item = row.continueItems.first(where: { $0.cardKey == key }) ?? row.continueItems.first else { return nil }
            return RowPanel(row: row, continuing: item)
        case .live:
            return nil
        }
    }
}

extension RowPanel {
    /// The panels of the cards either side of the one with `key`, whose
    /// pictures a screen fetches ahead so moving along the row does not flash
    /// the placeholder (PLAN-HOME.md §7.2). Empty for a Live TV row.
    public static func neighbours(for row: ScreenRow, card key: String?, today: Date = .now) -> [RowPanel] {
        let keys: [String]
        switch row.style {
        case .poster, .wide: keys = row.items.map(\.cardID)
        case .episode: keys = row.episodes.map(\.cardKey)
        case .continue: keys = row.continueItems.map(\.cardKey)
        case .live: return []
        }
        let index = keys.firstIndex { $0 == key } ?? 0
        return [index - 1, index + 1].filter { keys.indices.contains($0) }.compactMap { panel(for: row, card: keys[$0], today: today) }
    }
}

extension EpisodeCard {
    /// Its identity among a screen's cards, beside ``BrowseItem/cardID``.
    public var cardKey: String { "episode-\(episodeID)" }
}

extension ContinueItem {
    /// Its identity among a screen's cards, beside ``BrowseItem/cardID``.
    public var cardKey: String { "continue-\(fileID)" }
}

extension ScreenRow {
    /// The row's name along the bottom of the screen before it, and in Row N
    /// of M's neighbour: "Recently added · TV Shows", a list's "Top rated this
    /// year · 18 of 50", "Live TV".
    public var screenName: String {
        guard isList, let subtitle else { return title }
        let count = subtitle.components(separatedBy: " · ").first { $0.contains(" of ") }
        return count.map { "\(title) · \($0)" } ?? title
    }

    /// The row's cards have a card after the first, so ◀ ▶ moves along it.
    public var hasSeveralCards: Bool {
        switch style {
        case .poster, .wide: items.count > 1
        case .episode: episodes.count > 1
        case .continue: continueItems.count > 1
        case .live: true
        }
    }
}

/// "Row 2 of 7", beside a row's heading: `index` counts the drawn rows from 0.
public func rowCounter(index: Int, of total: Int) -> String { "Row \(index + 1) of \(total)" }

/// The last screen of Home (TvHomeRow and Pad2HomeRow's `end`).
public enum HomeEndScreen {
    public static let title = "That's everything on Home"
    public static let message = "Move, hide and add rows. Your order follows you to every device."
    public static let editRows = "Edit rows"
    public static let backToTop = "Back to the top"
    /// The next-row line on the last row screen.
    public static let name = "End of Home"
}
