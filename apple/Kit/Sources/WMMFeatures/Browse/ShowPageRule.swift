import Foundation
import WMMModel

// One page for a show, its seasons and episodes (PLAN-SHOW-PAGE.md §2 and
// §3): which episode goes on top, why, and the page's wording, as the
// Android app words it.

/// How the person came to the show's page: the show's link, a season's, or an episode's.
public enum ShowArrival: Hashable, Sendable {
    case show
    case season(Int)
    /// `season` is nil when the link didn't say (Home's hero); the page looks for it.
    case episode(id: Int, season: Int?)

    /// From a route's parts: an episode wins over a season, a season over the show.
    public init(season: Int?, episode: Int?) {
        if let episode {
            self = .episode(id: episode, season: season)
        } else if let season {
            self = .season(season)
        } else {
            self = .show
        }
    }
}

/// Why the episode on top was chosen for the person; picking another drops it.
public enum OnTopReason: Hashable, Sendable {
    /// The server's next episode.
    case upNext
    /// The server's next episode, partway through.
    case resume
    /// Nothing of the show watched yet.
    case startHere
    /// Every episode on disk watched: the first one is on top.
    case watchedItAll

    /// The chip by the eyebrow: "Up next", "Up next · resume", "Start here", "Watched it all".
    public var label: String {
        switch self {
        case .upNext: "Up next"
        case .resume: "Up next · resume"
        case .startHere: "Start here"
        case .watchedItAll: "Watched it all"
        }
    }
}

/// The episode on top, and why.
public struct OnTop: Hashable, Sendable {
    public var episodeID: Int
    public var reason: OnTopReason?
    /// The episode the link named isn't in its season any more; where the season starts is on top instead.
    public var gone: Bool

    public init(episodeID: Int, reason: OnTopReason? = nil, gone: Bool = false) {
        self.episodeID = episodeID
        self.reason = reason
        self.gone = gone
    }
}

public enum ShowPageRule {
    /// The season to open on: the up-next episode's for the show (else the
    /// lowest season above 0 with something on disk), the one asked for, or
    /// the episode's. Nil for an episode whose season the link didn't say.
    public static func season(for arrival: ShowArrival, show: ShowDetail) -> Int? {
        let numbers = Set(show.seasons.map(\.number))
        switch arrival {
        case .episode(let id, let season):
            if let season, numbers.contains(season) { return season }
            if let next = show.nextUp, next.episodeID == id, numbers.contains(next.season) { return next.season }
            return nil
        case .season(let number) where numbers.contains(number):
            return number
        case .season, .show:
            if let next = show.nextUp, numbers.contains(next.season) { return next.season }
            return firstSeason(show)
        }
    }

    /// The lowest season above 0 with episodes on disk, else the lowest above 0, else the first tab.
    public static func firstSeason(_ show: ShowDetail) -> Int? {
        let tabs = show.tabSeasons
        return tabs.first { $0.number > 0 && $0.availableCount > 0 }?.number
            ?? tabs.first { $0.number > 0 }?.number
            ?? tabs.first?.number
    }

    /// The episode on top once `season` (as the server sent it) is in:
    /// - the show: the server's next episode, as "Up next", "Up next · resume"
    ///   or "Start here"; with none, the season's first episode on disk, as
    ///   "Watched it all" when every episode on disk above season 0 is watched;
    /// - a season: its first episode on disk not finished, else its first;
    /// - an episode: that one, or where the season starts (as above) when it's gone.
    public static func onTop(for arrival: ShowArrival, show: ShowDetail, season: SeasonDetail) -> OnTop? {
        switch arrival {
        case .show:
            if let next = show.nextUp, let episode = season.episodes.first(where: { $0.id == next.episodeID }) {
                let reason: OnTopReason = episode.resumable ? .resume : nothingWatched(show) ? .startHere : .upNext
                return OnTop(episodeID: episode.id, reason: reason)
            }
            guard let first = season.firstEpisode else { return nil }
            return OnTop(episodeID: first.id, reason: watchedItAll(show) ? .watchedItAll : nil)
        case .season:
            return season.arrivalEpisode.map { OnTop(episodeID: $0.id) }
        case .episode(let id, _):
            if season.episodes.contains(where: { $0.id == id }) { return OnTop(episodeID: id) }
            return season.arrivalEpisode.map { OnTop(episodeID: $0.id, gone: true) }
        }
    }

    /// After deleting the episode on top: the next one in the row, else the
    /// one before; `next` says which. Nil when the row had only that one.
    public static func afterDelete(_ id: Int, in row: [EpisodeDetail]) -> (id: Int, next: Bool)? {
        guard let i = row.firstIndex(where: { $0.id == id }) else { return nil }
        if i + 1 < row.count { return (row[i + 1].id, true) }
        if i > 0 { return (row[i - 1].id, false) }
        return nil
    }

    /// Nothing watched or started on disk.
    private static func nothingWatched(_ show: ShowDetail) -> Bool {
        show.seasons.allSatisfy { $0.watchedCount == 0 }
    }

    /// Every episode on disk outside the specials has been watched.
    private static func watchedItAll(_ show: ShowDetail) -> Bool {
        let onDisk = show.seasons.filter { $0.number > 0 && $0.availableCount > 0 }
        return !onDisk.isEmpty && onDisk.allSatisfy(\.allWatched)
    }
}

extension ShowDetail {
    /// The season tabs in order, the specials last.
    public var tabSeasons: [SeasonSummary] {
        seasons.filter { $0.number > 0 }.sorted { $0.number < $1.number } + seasons.filter { $0.number <= 0 }
    }
}

extension SeasonDetail {
    /// The first episode on disk, else the first.
    public var firstEpisode: EpisodeDetail? { episodes.first(where: \.hasFile) ?? episodes.first }

    /// Arriving at the season: its first episode on disk not finished, else its first.
    public var arrivalEpisode: EpisodeDetail? {
        episodes.first { $0.hasFile && !$0.watched } ?? firstEpisode
    }

    /// Beside the season's tabs: "8 episodes · 4 watched · E09 airs Nov 4",
    /// or "· all watched", counting what is on disk.
    public func rowLine(today: Date = .now) -> String {
        let onDisk = episodes.filter(\.hasFile)
        let watched = onDisk.filter(\.watched).count
        var parts = [plural(onDisk.count, "episode")]
        if !onDisk.isEmpty { parts.append(watched == onDisk.count ? "all watched" : "\(formatCount(watched)) watched") }
        if let coming = episodes.first(where: { !$0.aired }), let day = formatDay(coming.airDate, today: today) {
            parts.append(String(format: "E%02d airs ", coming.number) + day)
        }
        return parts.joined(separator: " · ")
    }
}

extension SeasonSummary {
    /// A tab's count: the episodes on disk, "7"; with Show missing on, "4 of 10" or "none" while some are missing.
    public func tabCount(missingShown: Bool) -> String {
        guard missingShown, availableCount < episodeCount else { return countLabel }
        return availableCount == 0 ? "none" : "\(formatCount(availableCount)) of \(formatCount(episodeCount))"
    }
}

extension EpisodeDetail {
    /// Under a card in the row: "52 min", "29 min left", "Watched", "Airs
    /// Nov 18", or for a missing episode the start of why ("Missing",
    /// "Downloading 45%", "Requested by you").
    public func rowCardLine(today: Date = .now) -> String {
        if !hasFile { return String(missingLine(now: today).text.split(separator: " · ").first ?? "") }
        if resumable, let watch { return formatRemaining(ms: watch.remainingMs) }
        if watched { return "Watched" }
        return runtimeLabel ?? meta(today: today)
    }

    /// Over the episode's title: "Harbor Lights · S03 E07", or "Harbor Lights · Specials E02".
    public func eyebrow(showTitle: String) -> String {
        let code = season == 0 ? String(format: "Specials E%02d", number) : episodeCode(season: season, episode: number)
        return "\(showTitle) · \(code)"
    }
}

/// The page's notes over the season row.
public enum ShowPageNote {
    /// The episode a link named isn't in its season any more.
    public static let gone = "That episode isn’t here any more."

    /// After a delete: "Deleted “Neap”. Showing the next episode.", "… Showing the one before.", or the delete alone.
    public static func deleted(_ title: String, next: Bool?) -> String {
        let deleted = "Deleted “\(title)”"
        switch next {
        case true?: return deleted + ". Showing the next episode."
        case false?: return deleted + ". Showing the one before."
        case nil: return deleted
        }
    }
}

/// A watched-it-all page's line under the overview.
public let watchedItAllLine = "You’ve watched every episode. Start again here, or pick one below."
