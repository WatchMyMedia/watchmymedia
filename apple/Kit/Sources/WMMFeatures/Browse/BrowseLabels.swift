import Foundation
import WMMModel

// The wording on the browse screens (Home, the libraries, Show, Movie and
// Search), as the Android phone app words it (android/mobile/.../home,
// library, detail, search), built on the shared helpers in WMMModel. Here
// rather than in the views so it is tested, and so the Apple TV screens say
// the same.

/// "Mark watched" or "Mark unwatched": the action that undoes `watched`.
public func watchToggleLabel(watched: Bool) -> String { watched ? "Mark unwatched" : "Mark watched" }

/// The note after a mark: "Marked as watched" or "Marked as unwatched".
public func markedMessage(watched: Bool) -> String { watched ? "Marked as watched" : "Marked as unwatched" }

/// "Resume · 29 min left" when partway through, else "Play".
public func playLabel(_ watch: WatchProgress?) -> String {
    guard let watch, watch.resumable else { return "Play" }
    return "Resume · \(formatRemaining(ms: watch.remainingMs))"
}

/// "1990s".
public func decadeLabel(_ decade: Int) -> String { "\(decade)s" }

/// "Nothing is called “harbr”. Check the spelling, or try fewer words."
public func noMatchesMessage(_ query: String) -> String {
    "Nothing is called “\(query)”. Check the spelling, or try fewer words."
}

/// A titled alert a screen shows instead of navigating.
public struct Notice: Hashable, Sendable, Identifiable {
    public var title: String
    public var message: String

    public init(title: String, message: String) {
        self.title = title
        self.message = message
    }

    public var id: String { title + message }

    /// An unmatched folder has no page and nothing single to play.
    public static let notMatched = Notice(title: "Not matched yet", message: "Match this folder in the web app to see its episodes.")
}

/// A label and value in a detail page's Details section.
public struct DetailRow: Hashable, Sendable {
    public var label: String
    public var value: String

    public init(_ label: String, _ value: String) {
        self.label = label
        self.value = value
    }
}

extension ContinueItem {
    /// "S02 E04 · 23 min left", or "1 h 12 min left" for a movie.
    public var remainingLine: String {
        let left = formatRemaining(ms: remainingMs)
        if kind == .episode, let season, let episode { return "\(episodeCode(season: season, episode: episode)) · \(left)" }
        return left
    }

    /// The show or movie it belongs to.
    public var pageRoute: Route? {
        if let movieID { return .movie(id: movieID) }
        if let showID { return .show(id: showID) }
        return nil
    }

    /// "Go to movie" or "Go to show".
    public var pageLabel: String { kind == .movie ? "Go to movie" : "Go to show" }
}

extension Hero {
    /// The episode's page, or the show or movie it belongs to.
    public var pageRoute: Route? {
        if let movieID { return .movie(id: movieID) }
        if let showID, kind == .episode, let episodeID { return .episode(showID: showID, season: nil, id: episodeID) }
        if let showID { return .show(id: showID) }
        return nil
    }

    /// "Go to episode", "Go to movie" or "Go to show".
    public var pageLabel: String {
        switch pageRoute {
        case .episode?: "Go to episode"
        case .movie?: "Go to movie"
        default: "Go to show"
        }
    }

    /// The facts as separate parts: "S03 E07", "The Long Dark", "52 min".
    public var factParts: [String] {
        facts.components(separatedBy: " · ").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
    }
}

extension HomeData {
    /// Nothing scanned yet: no hero and no rows (a Live TV row doesn't count).
    public var isEmpty: Bool {
        if let rows { return hero == nil && rows.allSatisfy { $0.style == .live || $0.isEmpty } }
        return hero == nil && continueWatching.isEmpty && recentlyAddedShows.isEmpty && recentlyAired.isEmpty && recentlyAddedMovies.isEmpty && recentlyReleased.isEmpty
    }

    /// Home comes as rows in the person's order (``rowsAPILevel``), so it can be arranged.
    public var isArranged: Bool { rows != nil }

    /// The arranged rows to draw, in order. Without Live TV (`liveTV` false)
    /// the Live TV row draws nothing, so it is left out.
    public func drawnRows(liveTV: Bool) -> [ScreenRow] {
        (rows ?? []).filter { !$0.isEmpty && (liveTV || $0.style != .live) }
    }

    /// On iPad, Continue watching sits beside the hero only while it is the
    /// first row drawn (PLAN-ROWS.md Decision 3).
    public func continueBesideHero(liveTV: Bool) -> [ContinueItem]? {
        guard hero != nil, let first = drawnRows(liveTV: liveTV).first, first.style == .continue else { return nil }
        return first.continueItems
    }

    /// Every episode card on Home, for the hero's Play.
    public var allEpisodes: [EpisodeCard] {
        recentlyAired + (rows ?? []).flatMap(\.episodes)
    }

    /// Every show and movie card on Home.
    private var allItems: [BrowseItem] {
        recentlyAddedShows + recentlyAddedMovies + recentlyReleased + (rows ?? []).flatMap(\.items)
    }

    /// The hero's quality tags ("4K", "HDR"): its own, else (from servers
    /// that do not send them) its show's or movie's in Home's rows.
    public var heroBadges: [String] {
        guard let hero else { return [] }
        func badges(_ quality: QualityLabel?, _ hdr: Bool) -> [String] { [quality, hdr ? "HDR" : nil].compactMap { $0 } }
        if hero.quality != nil || hero.hdr { return badges(hero.quality, hero.hdr) }
        let item = hero.kind == .movie
            ? allItems.first { $0.kind == .movie && $0.id == hero.movieID }
            : allItems.first { $0.kind == .show && $0.id == hero.showID }
        return item.map { badges($0.quality, $0.hdr) } ?? []
    }

    /// "214 movies · 142 shows · 3,418 episodes · 16.7 TB · last scan 12 min ago".
    public func totalsLine(now: Date = .now) -> String {
        [
            plural(totals.movies, "movie"),
            plural(totals.shows, "show"),
            plural(totals.episodes, "episode"),
            formatBytes(totals.totalSizeBytes),
            lastScannedAt.map { "last scan \(formatAgo($0, now: now))" },
        ].compactMap { $0 }.joined(separator: " · ")
    }
}

extension EpisodeCard {
    /// "S03 · E07", the badge on a Recently aired card.
    public var codeBadge: String { String(format: "S%02d · E%02d", season, episode) }

    /// "S03E07 The Long Dark · yesterday", under a Recently aired card.
    public func airedLine(today: Date = .now) -> String {
        let code = episodeCode(season: season, episode: episode).replacingOccurrences(of: " ", with: "")
        return ["\(code) \(title)", formatDay(airDate, today: today)].compactMap { $0 }.joined(separator: " · ")
    }

    /// "Harbor Lights · S03 E07 · aired yesterday", or "… · watched", in Search.
    public func searchLine(today: Date = .now) -> String {
        let when = watched ? "watched" : formatDay(airDate, today: today).map { "aired \($0)" }
        return [showTitle, episodeCode(season: season, episode: episode), when].compactMap { $0 }.joined(separator: " · ")
    }

    /// Its page, over its show on its season.
    public var route: Route { .episode(showID: showID, season: season, id: episodeID) }
}

extension BrowseItem {
    /// The line under a poster: the server's ("2022 · 3 seasons", "Not
    /// looked up yet"), else "2026 · 1 h 52 min" for a movie.
    /// A card's identity in a row: a list row mixes movies and shows, whose
    /// IDs come from different tables and can be equal.
    public var cardID: String { "\(kind.rawValue)-\(id)" }

    public var gridSubtitle: String? {
        if let subtitle { return subtitle }
        let line = [year.map(String.init), runtimeMin.map { formatDuration(ms: $0 * 60_000) }].compactMap { $0 }.joined(separator: " · ")
        return line.isEmpty ? nil : line
    }

    /// A search result's line: "2022 · 3 seasons · Drama", "2026 · 1 h 52 min · 4K · watched".
    public var searchLine: String {
        let watchedNote = watched ? "watched" : nil
        switch kind {
        case .show: return [gridSubtitle, genres.first, watchedNote].compactMap { $0 }.joined(separator: " · ")
        case .movie: return [year.map(String.init), runtimeMin.map { formatDuration(ms: $0 * 60_000) }, quality, watchedNote].compactMap { $0 }.joined(separator: " · ")
        case .unmatched: return gridSubtitle ?? ""
        }
    }
}

extension SeasonDetail {
    /// "On disk 4 of 7 · 16.8 GB", beside the season chips on iPad
    /// (PadShow.dc.html) and the season strip on Apple TV: how many of the season's episodes have a file, and
    /// the size of those files.
    public var diskLine: String {
        let bytes = episodes.reduce(Int64(0)) { sum, episode in sum + episode.versions.reduce(0) { $0 + $1.sizeBytes } }
        let count = "On disk \(formatCount(season.availableCount)) of \(formatCount(season.episodeCount))"
        return bytes > 0 ? "\(count) · \(formatBytes(bytes))" : count
    }
}

extension SeasonSummary {
    /// "Season 3", or "Specials" for season 0.
    public var chipName: String { number == 0 ? "Specials" : "Season \(number)" }

    /// The count on Apple TV's season strip: "7 of 8" on the chosen season
    /// while some of it is missing, else the episodes on disk.
    public func stripCount(selected: Bool) -> String {
        selected && availableCount < episodeCount ? "\(availableCount) of \(episodeCount)" : countLabel
    }

    /// Every episode on disk has been watched.
    public var allWatched: Bool { availableCount > 0 && watchedCount >= availableCount }
}

/// Over a detail page's title on Apple TV: "TV Shows › Harbor Lights", or,
/// for an episode opened from Home's Recently aired, "Home › Recently aired ›
/// Harbor Lights".
public func breadcrumb(tab: AppTab, title: String, forEpisode: Bool = false) -> String {
    [tab.title, forEpisode && tab == .home ? "Recently aired" : nil, title].compactMap { $0 }.joined(separator: " › ")
}

/// The pill under a show's facts.
public struct StatusPill: Hashable, Sendable {
    public var text: String
    /// Gold for a continuing show, grey for one that has ended.
    public var active: Bool

    public init(text: String, active: Bool) {
        self.text = text
        self.active = active
    }
}

extension ShowDetail {
    /// "2022– · 3 seasons · 23 episodes · Drama, Crime · TV-14 · ★ 7.0", counting the seasons and episodes on disk
    /// (a season only Show missing lists does not count).
    public var facts: String {
        let seasonCount = seasons.filter { $0.number > 0 && $0.availableCount > 0 }.count
        let ended = status != nil && !continuing
        return [
            year.map { ended ? "\($0)" : "\($0)–" },
            seasonCount > 0 ? plural(seasonCount, "season") : nil,
            episodesOnDisk > 0 ? plural(episodesOnDisk, "episode") : nil,
            genres.isEmpty ? nil : genres.prefix(2).joined(separator: ", "),
            contentRating,
            formatRating(rating),
        ].compactMap { $0 }.joined(separator: " · ")
    }

    /// "Continuing · next Nov 5" (gold), "Ended" or "Cancelled"; nil when the server has no status.
    public var statusPill: StatusPill? {
        guard let status else { return nil }
        if !continuing { return StatusPill(text: status.localizedCaseInsensitiveContains("cancel") ? "Cancelled" : "Ended", active: false) }
        let next = parseDay(nextAirDate)?.formatted(.dateTime.month(.abbreviated).day())
        return StatusPill(text: next.map { "Continuing · next \($0)" } ?? "Continuing", active: true)
    }

    /// Every season on disk has been watched.
    public var allWatched: Bool {
        let onDisk = seasons.filter { $0.availableCount > 0 }
        return !onDisk.isEmpty && onDisk.allSatisfy(\.allWatched)
    }

    /// "Play next · S03 E05".
    public var nextUpLabel: String? {
        nextUp.map { "Play next · \(episodeCode(season: $0.season, episode: $0.episode))" }
    }

    /// Cast & details on Apple TV: the status, genres, rating and what is on disk.
    public var details: [DetailRow] {
        [
            statusPill.map { DetailRow("Status", $0.text) },
            genres.isEmpty ? nil : DetailRow(genres.count == 1 ? "Genre" : "Genres", genres.joined(separator: ", ")),
            contentRating.map { DetailRow("Rated", $0) },
            totalEpisodes > 0 ? DetailRow("Episodes", "\(formatCount(episodesOnDisk)) of \(formatCount(totalEpisodes)) on disk") : nil,
            totalSizeBytes > 0 ? DetailRow("Size", formatBytes(totalSizeBytes)) : nil,
        ].compactMap { $0 }
    }

    /// Plays the first unwatched episode on disk, where the viewer left off.
    public var nextUpRequest: PlayRequest? {
        nextUp.map {
            PlayRequest(fileID: $0.fileID, title: title, subtitle: "\(episodeCode(season: $0.season, episode: $0.episode)) · \($0.title)", backdropPath: backdropPath, posterPath: posterPath, showID: id, seasonNumber: $0.season)
        }
    }
}

/// What an episode sheet's buttons say and do, from the episode's state.
public struct EpisodeActions: Hashable, Sendable {
    /// "Play" or "Resume · 36 min left"; nil when there is nothing to play.
    public var play: String?
    /// Shown, disabled, in place of Play: "Airs Nov 4", "Not in library".
    public var unavailable: String?
    /// A second button plays from 0:00, for an episode left partway.
    public var fromStart: Bool
    /// "Mark watched" or "Mark unwatched"; nil before the episode airs.
    public var watchToggle: String?
}

extension EpisodeDetail {
    /// "Harbor Lights · S03 E07", over the episode's title in its sheet.
    public func kicker(showTitle: String) -> String {
        "\(showTitle) · \(episodeCode(season: season, episode: number))"
    }

    /// "7. The Long Dark".
    public var rowTitle: String { "\(number). \(title.isEmpty ? "Episode \(number)" : title)" }

    /// "Aired yesterday · 52 min", or "Airs Nov 4" for one yet to air.
    public func meta(today: Date = .now) -> String {
        let day = formatDay(airDate, today: today)
        guard aired else { return day.map { "Airs \($0)" } ?? "Not aired yet" }
        return [day.map { "Aired \($0)" }, runtimeLabel].compactMap { $0 }.joined(separator: " · ")
    }

    /// The sheet's line: the meta with the rating, "Aired yesterday · 52 min · ★ 7.8".
    public func sheetMeta(today: Date = .now) -> String {
        [meta(today: today), formatRating(rating)].compactMap { $0 }.joined(separator: " · ")
    }

    /// "52 min", the badge on the still.
    public var runtimeLabel: String? { runtimeMin.map { formatDuration(ms: $0 * 60_000) } }

    /// Partway through: the still shows how far, and Resume offers From the start.
    public var resumable: Bool { watch?.resumable ?? false }

    /// Can be played: on disk with a file.
    public var playable: Bool { hasFile && fileID != nil }

    public func actions(today: Date = .now) -> EpisodeActions {
        EpisodeActions(
            play: playable ? playLabel(watch) : nil,
            unavailable: playable ? nil : (aired ? "Not in library" : meta(today: today)),
            fromStart: playable && resumable,
            watchToggle: aired ? watchToggleLabel(watched: watched) : nil
        )
    }

    /// The sheet's file line after the tags: "2.2 GB", or "Upcoming".
    public var fileNote: String? {
        if !aired { return "Upcoming" }
        if !hasFile { return "Not in library" }
        return versions.first.map { formatBytes($0.sizeBytes) }
    }
}

extension MediaVersion {
    /// "4K", "HEVC", "HDR", "EAC3 5.1": an episode's file at a glance.
    public var episodeTags: [String] {
        [quality, codecLabel(videoCodec), hdrLabel(hdr).map { $0 == dolbyVision ? "DV" : "HDR" }, audioLabel(audioSummary)].compactMap { $0 }
    }
}

/// "7.1" from "TrueHD 7.1 · English".
public func channelsLabel(_ summary: String?) -> String? {
    guard let summary, let match = summary.firstMatch(of: /\b\d\.\d\b/) else { return nil }
    return String(match.output)
}

extension MovieDetail {
    /// "2026 · 1 h 52 min · Thriller · R · ★ 6.5".
    public var facts: String {
        [
            year.map(String.init),
            runtimeMin.map { formatDuration(ms: $0 * 60_000) },
            genres.isEmpty ? nil : genres.prefix(2).joined(separator: ", "),
            contentRating,
            formatRating(rating),
        ].compactMap { $0 }.joined(separator: " · ")
    }

    /// "4K", "Dolby Vision", "7.1", from the movie and its first version.
    public var tags: [String] {
        let v = versions.first
        return [quality, v.flatMap { hdrLabel($0.hdr) } ?? (hdr ? "HDR" : nil), channelsLabel(v?.audioSummary)].compactMap { $0 }
    }

    /// Partway through: Resume, with From the start beside it.
    public var resumable: Bool { watch?.resumable ?? false }

    /// The Details section: release date, runtime, genres, rating and files.
    public var details: [DetailRow] {
        let size = versions.reduce(Int64(0)) { $0 + $1.sizeBytes }
        return [
            formatDate(releaseDate).map { DetailRow("Released", $0) },
            runtimeMin.map { DetailRow("Runtime", formatDuration(ms: $0 * 60_000)) },
            genres.isEmpty ? nil : DetailRow(genres.count == 1 ? "Genre" : "Genres", genres.joined(separator: ", ")),
            contentRating.map { DetailRow("Rated", $0) },
            versions.isEmpty ? nil : DetailRow("Files", "\(plural(versions.count, "version")) · \(formatBytes(size))"),
        ].compactMap { $0 }
    }
}

extension PlaybackPlan {
    /// "Direct play", or "Remux to fMP4 · audio to AAC 5.1 on this phone":
    /// how a version would play on `device`.
    public func versionLine(on device: String = "this phone") -> String {
        mode == .direct ? label : "\(label) on \(device)"
    }
}

/// "4 not matched yet": files the server has not matched, which the grid shows
/// beside the matched titles; nil when there are none.
public func unmatchedCount(_ n: Int) -> String? {
    n > 0 ? "\(formatCount(n)) not matched yet" : nil
}
