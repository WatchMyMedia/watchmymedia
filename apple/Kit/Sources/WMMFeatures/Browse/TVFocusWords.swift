import Foundation
import WMMModel

/// The words above a row on Apple TV's Home, for the focused card: what it
/// is, where the viewer was and when it would end, as a viewer asks them.
/// No file or stream detail (no "4K", no "HDR"): that is a press away, on
/// the title's page. The iPad's panel keeps ``RowPanel``; this is the TV's.
public struct TVFocusWords: Hashable, Sendable {
    /// "Recently added · today", "New episode · aired yesterday", "On now · 4.1 NFLD"; nil for Continue watching.
    public var kicker: String?
    /// The kicker is a live programme's: white with a red dot rather than gold.
    public var live: Bool
    public var title: String
    /// Under the title: "S02 E04 · Drift", "S1 E3 · Pressure".
    public var subtitle: String?
    /// The one fact to notice, in gold: "23 min left".
    public var lead: String?
    /// "Ends at 9:41 PM", "2024", "1 h 52 min", "8:00–9:00 PM".
    public var facts: [String]
    /// The content rating, drawn in its box: "TV-14".
    public var rating: String?
    /// After the rating: the genre.
    public var after: [String]
    /// Two lines at most on screen; may be empty.
    public var overview: String
    /// What follows on a channel: "Next: Tide Tables at 9:00 PM".
    public var next: String?

    public init(kicker: String? = nil, live: Bool = false, title: String, subtitle: String? = nil, lead: String? = nil, facts: [String] = [], rating: String? = nil, after: [String] = [], overview: String = "", next: String? = nil) {
        self.kicker = kicker
        self.live = live
        self.title = title
        self.subtitle = subtitle
        self.lead = lead
        self.facts = facts
        self.rating = rating
        self.after = after
        self.overview = overview
        self.next = next
    }

    /// What VoiceOver says before the row: every line, in order.
    public var accessibilityLabel: String {
        let line = ([lead].compactMap { $0 } + facts + [rating.map { "Rated \($0)" }].compactMap { $0 } + after).joined(separator: ", ")
        return [kicker, title, subtitle, line, overview, next].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: ". ")
    }
}

extension TVFocusWords {
    /// The words for the card with `key` in `row` (``BrowseItem/cardID``,
    /// ``EpisodeCard/cardKey``, ``ContinueItem/cardKey``), or for the row's
    /// first card when `key` is nil or not in the row; nil for an empty or
    /// Live TV row, whose channels the app supplies.
    public static func words(for row: ScreenRow, card key: String?, now: Date = .now, clock: LiveClock = .device) -> TVFocusWords? {
        switch row.style {
        case .poster, .wide:
            guard !row.items.isEmpty else { return nil }
            let index = row.items.firstIndex { $0.cardID == key } ?? 0
            return TVFocusWords(row: row, item: row.items[index], position: index, now: now, clock: clock)
        case .episode:
            guard let episode = row.episodes.first(where: { $0.cardKey == key }) ?? row.episodes.first else { return nil }
            return TVFocusWords(row: row, episode: episode, now: now)
        case .continue:
            guard let item = row.continueItems.first(where: { $0.cardKey == key }) ?? row.continueItems.first else { return nil }
            return TVFocusWords(continuing: item, now: now, clock: clock)
        case .live:
            return nil
        }
    }

    /// A show or movie card. A film partway through says how long is left
    /// and when it would end.
    public init(row: ScreenRow, item: BrowseItem, position: Int, now: Date = .now, clock: LiveClock = .device) {
        let panel = RowPanel(row: row, item: item, position: position, today: now)
        var lead: String?
        var facts: [String] = []
        switch item.kind {
        case .show:
            facts = [item.year.map(String.init), item.episodeCount.map { plural($0, "episode") }].compactMap { $0 }
        case .movie:
            facts = [item.year.map(String.init), item.runtimeMin.map { formatDuration(ms: $0 * 60_000) }].compactMap { $0 }
            if let runtime = item.runtimeMin, let progress = item.progress, progress > 0, !item.watched {
                let left = Int(Double(runtime * 60_000) * (1 - min(progress, 1)))
                lead = formatRemaining(ms: left)
                facts = [endsAtLine(remainingMs: left, now: now, clock: clock)]
            }
        case .unmatched:
            facts = [item.fileName ?? item.gridSubtitle].compactMap { $0 }
        }
        self.init(
            kicker: panel.kicker, title: item.title, lead: lead, facts: facts,
            after: item.kind == .unmatched ? [] : Array(item.genres.prefix(1)),
            overview: item.overview
        )
    }

    /// An episode card in an `episode` row (Recently aired): the show leads,
    /// the episode under it.
    public init(row: ScreenRow, episode: EpisodeCard, now: Date = .now) {
        let panel = RowPanel(row: row, episode: episode, today: now)
        let code = episodeCode(season: episode.season, episode: episode.episode)
        self.init(
            kicker: panel.kicker, title: episode.showTitle,
            subtitle: episode.title.isEmpty ? code : "\(code) · \(episode.title)",
            facts: episode.hasFile ? [] : ["Not in library"],
            overview: episode.overview
        )
    }

    /// A Continue watching card: where the viewer was, how long is left and when it would end.
    public init(continuing item: ContinueItem, now: Date = .now, clock: LiveClock = .device) {
        self.init(
            title: item.title,
            subtitle: item.kind == .movie ? nil : item.subtitle,
            lead: formatRemaining(ms: item.remainingMs),
            facts: [endsAtLine(remainingMs: item.remainingMs, now: now, clock: clock)]
        )
    }

    /// A channel in the On now row: the programme, how long is left, its
    /// times and what follows; the channel alone without listings.
    public init(channel: LiveChannel, now: Date, clock: LiveClock = .device) {
        let kicker = "On now · \(channel.number) \(channel.name)"
        guard let programme = channel.programme(at: now) else {
            self.init(kicker: kicker, live: true, title: channel.name, facts: ["No listings"])
            return
        }
        let following = channel.programme(after: now)
        self.init(
            kicker: kicker, live: true, title: programme.title,
            subtitle: programme.episodeLine,
            lead: liveTimeLeft(programme, now: now),
            facts: [clock.range(programme.start, programme.end)],
            rating: programme.rating,
            next: following.map { "Next: \($0.title) at \(clock.time($0.start))" }
        )
    }

    /// The On now row's Full guide card.
    public static let fullGuide = TVFocusWords(kicker: "Live TV", title: "Full guide", facts: ["Every channel, today and later"])
}

/// "1 of 7", beside the row's heading on Apple TV's Home: `index` counts the drawn rows from 0.
public func tvRowCounter(index: Int, of total: Int) -> String { "\(index + 1) of \(total)" }

/// What Apple TV's Home says when it has nothing to show: the server didn't
/// answer, or it has nothing yet.
public enum TVHomeWords {
    public static let tryAgain = "Try again"
    public static let switchServer = "Switch server"
    public static let checkAgain = "Check again"
    public static let watchLiveTV = "Watch live TV"

    /// The failure is the server not answering (``APIError`` with no status says so).
    public static func unreachable(_ message: String) -> Bool {
        message.replacingOccurrences(of: "’", with: "'").hasPrefix("Couldn't reach the server")
    }

    /// "Can’t reach Harbour House" when the server didn't answer; else "Couldn’t load Home".
    public static func failedTitle(serverName: String?, message: String) -> String {
        guard unreachable(message) else { return "Couldn’t load Home" }
        return "Can’t reach \(serverName ?? "the server")"
    }

    /// Why, in words: the server not answering said as a viewer sees it, else the reason given.
    public static func failedMessage(_ message: String) -> String {
        guard unreachable(message) else { return message }
        return "This Apple TV is online, but the server isn’t answering. It may be off, restarting, or away from this network."
    }

    /// For whoever runs the server, under an empty Home: "Run this server? Add its folders at harbourhouse.example in a browser."
    public static func setupLine(address: String) -> String {
        "Run this server? Add its folders at \(address) in a browser."
    }
}
