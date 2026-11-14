import Foundation

/// One programme on a channel, as the listings provider gives it.
public struct LiveProgramme: Hashable, Sendable, Identifiable {
    public enum Kind: String, Hashable, Sendable { case episode, show, movie }

    public var id: String
    public var start: Date
    public var end: Date
    public var title: String
    public var episodeTitle: String?
    /// A season numbered by year is that year, such as 2026.
    public var season: Int?
    public var episode: Int?
    /// The provider's own numbering when it isn't a season and episode.
    public var episodeLabel: String?
    /// "YYYY-MM-DD", or a movie's year.
    public var airDate: String?
    /// Nil for a kind this app doesn't know yet.
    public var kind: Kind?
    public var isNew: Bool
    public var live: Bool
    public var isRepeat: Bool
    public var hd: Bool
    public var categories: [String]
    public var rating: String?
    public var description: String?
    public var descriptionFromTMDB: Bool
    /// The 16:9 picture everywhere but the wide panel: the provider's series card, or TMDB's when it had none.
    public var pictureURL: URL?
    public var pictureFromTMDB: Bool
    /// A TMDB episode still or movie backdrop matched to this programme, shown first in the wide panel and the TV hero.
    public var stillURL: URL?
    public var posterURL: URL?
    /// What will record it, or did, for people who may record (API level 19).
    public var recording: LiveRecordingMark?
    /// The signed-in person has a reminder for it (API level 15), as the guide or search said.
    public var reminder: Bool
    /// The provider's series, which a series recording follows (API level 19). A film
    /// or a special has none, so Record records it at once with no choice to make.
    public var seriesKey: String?

    public init(id: String, start: Date, end: Date, title: String, episodeTitle: String? = nil, season: Int? = nil, episode: Int? = nil,
                episodeLabel: String? = nil, airDate: String? = nil, kind: Kind? = nil, isNew: Bool = false, live: Bool = false,
                isRepeat: Bool = false, hd: Bool = false, categories: [String] = [], rating: String? = nil, description: String? = nil,
                descriptionFromTMDB: Bool = false, pictureURL: URL? = nil, pictureFromTMDB: Bool = false, stillURL: URL? = nil,
                posterURL: URL? = nil, reminder: Bool = false, recording: LiveRecordingMark? = nil, seriesKey: String? = nil) {
        self.recording = recording
        self.seriesKey = seriesKey
        self.reminder = reminder
        self.id = id; self.start = start; self.end = end; self.title = title; self.episodeTitle = episodeTitle
        self.season = season; self.episode = episode; self.episodeLabel = episodeLabel; self.airDate = airDate; self.kind = kind
        self.isNew = isNew; self.live = live; self.isRepeat = isRepeat; self.hd = hd; self.categories = categories
        self.rating = rating; self.description = description; self.descriptionFromTMDB = descriptionFromTMDB
        self.pictureURL = pictureURL; self.pictureFromTMDB = pictureFromTMDB; self.stillURL = stillURL; self.posterURL = posterURL
    }

    public func isOn(at date: Date) -> Bool { start <= date && date < end }
    public func hasEnded(at date: Date) -> Bool { end <= date }

    /// How far through it is at `date`, 0–1.
    public func progress(at date: Date) -> Double {
        let length = end.timeIntervalSince(start)
        guard length > 0 else { return 0 }
        return min(max(date.timeIntervalSince(start) / length, 0), 1)
    }

    /// The picture for a slot: the wide details panel and the TVs' hero take a
    /// matched TMDB still first; everywhere else shows the provider's.
    public func picture(wide: Bool) -> LivePicture? {
        if wide, let stillURL { return .init(url: stillURL, fromTMDB: true) }
        return pictureURL.map { .init(url: $0, fromTMDB: pictureFromTMDB) }
    }

    /// New, Live and HD, in that order.
    public var tags: [String] {
        (isNew ? ["New"] : []) + (live ? ["Live"] : []) + (hd ? ["HD"] : [])
    }

    /// "S2 E4 · The Low Tide", "2026 E14 · Owls of the Valley", "Movie · 1958",
    /// "Ridgeway at Lakeport"; nil when there is nothing to say.
    public var episodeLine: String? {
        if kind == .movie {
            let year = airDate.map { String($0.prefix(4)) }.flatMap { $0.count == 4 && Int($0) != nil ? $0 : nil }
            return ["Movie", year].compactMap(\.self).joined(separator: " · ")
        }
        var number: String?
        if let season, let episode { number = season >= 1000 ? "\(season) E\(episode)" : "S\(season) E\(episode)" }
        else if let season { number = season >= 1000 ? "\(season)" : "S\(season)" }
        else if let episode { number = "E\(episode)" }
        else { number = Self.nonBlank(episodeLabel) }
        let parts = [number, Self.nonBlank(episodeTitle)].compactMap(\.self)
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }

    /// "Series · Mystery · Drama".
    public var categoryLine: String? { categories.isEmpty ? nil : categories.joined(separator: " · ") }

    /// Whether it belongs under a guide filter: a movie's kind counts as Movies.
    public func matches(_ filter: LiveGuideFilter) -> Bool {
        if filter == .movies, kind == .movie { return true }
        return categories.contains { filter.categories.contains($0.lowercased()) }
    }

    private static func nonBlank(_ s: String?) -> String? {
        guard let s = s?.trimmingCharacters(in: .whitespacesAndNewlines), !s.isEmpty else { return nil }
        return s
    }
}

public struct LivePicture: Hashable, Sendable {
    public var url: URL
    /// Credited "Picture: TMDB" where it shows.
    public var fromTMDB: Bool
    public init(url: URL, fromTMDB: Bool) { self.url = url; self.fromTMDB = fromTMDB }
}

/// The guide's category chips, from the feed's categories.
public enum LiveGuideFilter: String, CaseIterable, Hashable, Sendable {
    case movies, sports, news, kids
    public var title: String {
        switch self {
        case .movies: "Movies"
        case .sports: "Sports"
        case .news: "News"
        case .kids: "Kids"
        }
    }
    var categories: Set<String> {
        switch self {
        case .movies: ["movie", "movies"]
        case .sports: ["sports", "sport", "sports event", "sports talk"]
        case .news: ["news"]
        case .kids: ["children", "kids", "family"]
        }
    }
}

/// When listings were fetched and how far they reach.
public struct LiveGuideState: Hashable, Sendable {
    public var enabled: Bool
    /// Not fetched for over 36 hours; channels still play.
    public var stale: Bool
    public var updatedAt: Date?
    public var listedThrough: Date?
    public init(enabled: Bool = true, stale: Bool = false, updatedAt: Date? = nil, listedThrough: Date? = nil) {
        self.enabled = enabled; self.stale = stale; self.updatedAt = updatedAt; self.listedThrough = listedThrough
    }
}

/// A channel's row in the guide: the provider lists it or it shows "No listings" and still plays.
public struct LiveGuideChannel: Hashable, Sendable, Identifiable {
    public var channel: LiveChannel
    public var listings: Bool
    /// In start order; gaps stay gaps.
    public var programmes: [LiveProgramme]
    public var id: String { channel.id }
    public init(channel: LiveChannel, listings: Bool, programmes: [LiveProgramme] = []) {
        self.channel = channel; self.listings = listings; self.programmes = programmes
    }
}

/// GET /api/live-tv/guide: the viewer's channels over a window of up to 6 hours.
public struct LiveGuide: Hashable, Sendable {
    public var start: Date
    public var end: Date
    public var state: LiveGuideState
    public var channels: [LiveGuideChannel]
    public init(start: Date, end: Date, state: LiveGuideState, channels: [LiveGuideChannel]) {
        self.start = start; self.end = end; self.state = state; self.channels = channels
    }
}

extension LiveChannel {
    /// What is on at `date` from the channel list's now and next, so a banner
    /// moves on at the programme's end without asking the server again.
    public func programme(at date: Date) -> LiveProgramme? {
        [now, next].compactMap(\.self).first { $0.isOn(at: date) }
    }

    /// What follows the programme on at `date`, when the channel list says.
    public func programme(after date: Date) -> LiveProgramme? {
        guard let current = programme(at: date) else { return next.flatMap { $0.start > date ? $0 : nil } }
        return [now, next].compactMap(\.self).first { $0.start >= current.end }
    }
}
