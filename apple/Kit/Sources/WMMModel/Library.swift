import Foundation

public enum LibraryType: String, Hashable, Sendable { case movie, tv }

public struct Library: Hashable, Sendable, Identifiable {
    public var id: Int
    public var name: String
    public var type: LibraryType
    /// Present only while this library is being scanned.
    public var scanning: Bool
    /// The last completed full scan; older servers may omit it.
    public var lastScannedAt: Date?
    /// Why the most recent scan failed, until a full scan succeeds (API 12).
    public var lastScanError: String?

    public init(id: Int, name: String, type: LibraryType, scanning: Bool = false, lastScannedAt: Date? = nil, lastScanError: String? = nil) {
        self.id = id
        self.name = name
        self.type = type
        self.scanning = scanning
        self.lastScannedAt = lastScannedAt
        self.lastScanError = lastScanError
    }
}

public enum ItemKind: String, Hashable, Sendable { case movie, show, unmatched }

/// "4K", "1080p", "720p" or "SD".
public typealias QualityLabel = String

/// A movie or show card in a grid or row.
public struct BrowseItem: Hashable, Sendable, Identifiable {
    public var kind: ItemKind
    public var id: Int
    public var libraryID: Int
    public var title: String
    public var year: Int?
    public var overview: String
    public var genres: [String]
    public var rating: Double?
    public var posterPath: String?
    public var backdropPath: String?
    public var quality: QualityLabel?
    public var hdr: Bool
    public var isNew: Bool
    /// A row-specific line such as "S03E05" or "added today".
    public var subtitle: String?
    public var watched: Bool
    /// 0–1 for a movie partway through, else nil.
    public var progress: Double?
    /// Shows: episodes on disk.
    public var episodeCount: Int?
    public var runtimeMin: Int?
    public var releaseDate: String?
    /// Unmatched: the file or folder name.
    public var fileName: String?
    /// Movies: versions on disk. Unmatched: the files in the group (a show
    /// folder has several); an unmatched item's `id` is its first file's ID.
    public var fileCount: Int?
    /// Recently added shows: the one episode added (the subtitle is its
    /// code), whose page the card opens; nil from older servers.
    public var episodeID: Int?
    /// That episode's season.
    public var seasonNumber: Int?

    public init(kind: ItemKind, id: Int, libraryID: Int, title: String, year: Int? = nil, overview: String = "", genres: [String] = [], rating: Double? = nil, posterPath: String? = nil, backdropPath: String? = nil, quality: QualityLabel? = nil, hdr: Bool = false, isNew: Bool = false, subtitle: String? = nil, watched: Bool = false, progress: Double? = nil, episodeCount: Int? = nil, runtimeMin: Int? = nil, releaseDate: String? = nil, fileName: String? = nil, fileCount: Int? = nil, episodeID: Int? = nil, seasonNumber: Int? = nil) {
        self.kind = kind
        self.id = id
        self.libraryID = libraryID
        self.title = title
        self.year = year
        self.overview = overview
        self.genres = genres
        self.rating = rating
        self.posterPath = posterPath
        self.backdropPath = backdropPath
        self.quality = quality
        self.hdr = hdr
        self.isNew = isNew
        self.subtitle = subtitle
        self.watched = watched
        self.progress = progress
        self.episodeCount = episodeCount
        self.runtimeMin = runtimeMin
        self.releaseDate = releaseDate
        self.fileName = fileName
        self.fileCount = fileCount
        self.episodeID = episodeID
        self.seasonNumber = seasonNumber
    }
}

/// An episode card (Recently aired, search results).
public struct EpisodeCard: Hashable, Sendable, Identifiable {
    public var episodeID: Int
    public var showID: Int
    public var showTitle: String
    public var season: Int
    public var episode: Int
    public var title: String
    public var overview: String
    /// ISO date, such as 2026-10-28.
    public var airDate: String?
    public var stillPath: String?
    public var showPosterPath: String?
    public var showBackdropPath: String?
    /// False shows "Not in library".
    public var hasFile: Bool
    public var fileID: Int?
    public var isNew: Bool
    /// The signed-in user has watched it.
    public var watched: Bool

    public init(episodeID: Int, showID: Int, showTitle: String, season: Int, episode: Int, title: String, overview: String = "", airDate: String? = nil, stillPath: String? = nil, showPosterPath: String? = nil, showBackdropPath: String? = nil, hasFile: Bool, fileID: Int?, isNew: Bool, watched: Bool = false) {
        self.episodeID = episodeID
        self.showID = showID
        self.showTitle = showTitle
        self.season = season
        self.episode = episode
        self.title = title
        self.overview = overview
        self.airDate = airDate
        self.stillPath = stillPath
        self.showPosterPath = showPosterPath
        self.showBackdropPath = showBackdropPath
        self.hasFile = hasFile
        self.fileID = fileID
        self.isNew = isNew
        self.watched = watched
    }

    public var id: Int { episodeID }
}

public enum WatchKind: String, Hashable, Sendable { case movie, episode }

/// An item partway through, for Continue watching.
public struct ContinueItem: Hashable, Sendable, Identifiable {
    public var kind: WatchKind
    public var title: String
    public var subtitle: String?
    public var posterPath: String?
    public var backdropPath: String?
    public var stillPath: String?
    public var fileID: Int
    public var movieID: Int?
    public var showID: Int?
    public var episodeID: Int?
    public var season: Int?
    public var episode: Int?
    public var positionMs: Int
    public var durationMs: Int

    public init(kind: WatchKind, title: String, subtitle: String? = nil, posterPath: String? = nil, backdropPath: String? = nil, stillPath: String? = nil, fileID: Int, movieID: Int? = nil, showID: Int? = nil, episodeID: Int? = nil, season: Int? = nil, episode: Int? = nil, positionMs: Int, durationMs: Int) {
        self.kind = kind
        self.title = title
        self.subtitle = subtitle
        self.posterPath = posterPath
        self.backdropPath = backdropPath
        self.stillPath = stillPath
        self.fileID = fileID
        self.movieID = movieID
        self.showID = showID
        self.episodeID = episodeID
        self.season = season
        self.episode = episode
        self.positionMs = positionMs
        self.durationMs = durationMs
    }

    public var id: Int { fileID }
    public var progress: Double { durationMs > 0 ? min(max(Double(positionMs) / Double(durationMs), 0), 1) : 0 }
    public var remainingMs: Int { max(durationMs - positionMs, 0) }
}

/// The big item at the top of Home.
public struct Hero: Hashable, Sendable {
    public enum Kind: String, Hashable, Sendable { case episode, movie, show }

    public var kind: Kind
    /// "New episode · aired yesterday".
    public var kicker: String
    public var title: String
    /// "S03 E07 · The Long Dark · 52 min".
    public var facts: String
    public var overview: String
    public var backdropPath: String?
    public var posterPath: String?
    public var showID: Int?
    public var movieID: Int?
    public var episodeID: Int?
    public var fileID: Int?
    /// The certification ("TV-14", "R"); nil when it has none.
    public var contentRating: String?
    public var quality: QualityLabel?
    public var hdr: Bool

    public init(kind: Kind, kicker: String, title: String, facts: String, overview: String, backdropPath: String? = nil, posterPath: String? = nil, showID: Int? = nil, movieID: Int? = nil, episodeID: Int? = nil, fileID: Int? = nil, contentRating: String? = nil, quality: QualityLabel? = nil, hdr: Bool = false) {
        self.kind = kind
        self.kicker = kicker
        self.title = title
        self.facts = facts
        self.overview = overview
        self.backdropPath = backdropPath
        self.posterPath = posterPath
        self.showID = showID
        self.movieID = movieID
        self.episodeID = episodeID
        self.fileID = fileID
        self.contentRating = contentRating
        self.quality = quality
        self.hdr = hdr
    }
}

public struct LibraryTotals: Hashable, Sendable {
    public var movies: Int
    public var shows: Int
    public var episodes: Int
    public var files: Int
    public var totalSizeBytes: Int64

    public init(movies: Int, shows: Int, episodes: Int, files: Int, totalSizeBytes: Int64) {
        self.movies = movies
        self.shows = shows
        self.episodes = episodes
        self.files = files
        self.totalSizeBytes = totalSizeBytes
    }
}

public struct HomeData: Hashable, Sendable {
    public var hero: Hero?
    public var continueWatching: [ContinueItem]
    public var recentlyAddedShows: [BrowseItem]
    public var recentlyAired: [EpisodeCard]
    public var recentlyAddedMovies: [BrowseItem]
    public var recentlyReleased: [BrowseItem]
    public var totals: LibraryTotals
    public var lastScannedAt: Date?
    public var movieLibraryID: Int?
    public var tvLibraryID: Int?
    /// Home's rows in the person's order, from servers at ``rowsAPILevel``
    /// (the fixed arrays above are then empty); nil from older servers, whose
    /// Home keeps its fixed rows.
    public var rows: [ScreenRow]?

    public init(hero: Hero?, continueWatching: [ContinueItem], recentlyAddedShows: [BrowseItem], recentlyAired: [EpisodeCard], recentlyAddedMovies: [BrowseItem], recentlyReleased: [BrowseItem], totals: LibraryTotals, lastScannedAt: Date?, movieLibraryID: Int?, tvLibraryID: Int?, rows: [ScreenRow]? = nil) {
        self.hero = hero
        self.continueWatching = continueWatching
        self.recentlyAddedShows = recentlyAddedShows
        self.recentlyAired = recentlyAired
        self.recentlyAddedMovies = recentlyAddedMovies
        self.recentlyReleased = recentlyReleased
        self.totals = totals
        self.lastScannedAt = lastScannedAt
        self.movieLibraryID = movieLibraryID
        self.tvLibraryID = tvLibraryID
        self.rows = rows
    }
}

public enum ItemSort: String, CaseIterable, Hashable, Sendable {
    case title, added, year, rating, released, runtime, aired

    public var label: String {
        switch self {
        case .title: "Title"
        case .added: "Date added"
        case .year: "Year"
        case .rating: "Rating"
        case .released: "Release date"
        case .runtime: "Runtime"
        case .aired: "Last aired"
        }
    }

    /// The sorts offered for a library type (PLAN.md §10).
    public static func options(for type: LibraryType) -> [ItemSort] {
        switch type {
        case .tv: [.title, .added, .aired, .year, .rating]
        case .movie: [.title, .added, .released, .year, .rating, .runtime]
        }
    }
}

public enum ItemFilter: String, CaseIterable, Hashable, Sendable {
    case all, continuing, ended, fourK = "4k", hdr, unwatched

    public var label: String {
        switch self {
        case .all: "All"
        case .continuing: "Continuing"
        case .ended: "Ended"
        case .fourK: "4K"
        case .hdr: "HDR"
        case .unwatched: "Unwatched"
        }
    }

    /// The filters offered for a library type.
    public static func options(for type: LibraryType) -> [ItemFilter] {
        switch type {
        case .tv: [.all, .continuing, .ended, .unwatched, .fourK, .hdr]
        case .movie: [.all, .unwatched, .fourK, .hdr]
        }
    }
}

public struct ItemQuery: Hashable, Sendable {
    public var libraryID: Int
    public var sort: ItemSort
    public var filter: ItemFilter
    public var genre: String?
    /// Such as 1990.
    public var decade: Int?
    /// Title contains.
    public var text: String?
    public var offset: Int
    public var limit: Int

    public init(libraryID: Int, sort: ItemSort = .title, filter: ItemFilter = .all, genre: String? = nil, decade: Int? = nil, text: String? = nil, offset: Int = 0, limit: Int = 100) {
        self.libraryID = libraryID
        self.sort = sort
        self.filter = filter
        self.genre = genre
        self.decade = decade
        self.text = text
        self.offset = offset
        self.limit = limit
    }
}

/// An index supplied by the server for the entire filtered title-sorted list.
public struct LetterOffset: Hashable, Sendable {
    public var letter: String
    public var offset: Int

    public init(letter: String, offset: Int) {
        self.letter = letter
        self.offset = offset
    }
}

public struct ItemPage: Hashable, Sendable {
    public var items: [BrowseItem]
    /// Items matching the query.
    public var total: Int
    public var offset: Int
    /// Genres and decades present in the library, for the filter chips.
    public var genres: [String]
    public var decades: [Int]
    /// The whole library whatever the filter; nil from servers that do not count it.
    public var summary: LibrarySummary?
    /// Nil on older servers or when not sorted by title.
    public var letters: [LetterOffset]?
    /// Counts under the current status/quality, decade and text, before selecting a genre.
    public var genreCounts: [String: Int]?

    public init(items: [BrowseItem], total: Int, offset: Int, genres: [String], decades: [Int], summary: LibrarySummary? = nil, letters: [LetterOffset]? = nil, genreCounts: [String: Int]? = nil) {
        self.items = items
        self.total = total
        self.offset = offset
        self.genres = genres
        self.decades = decades
        self.summary = summary
        self.letters = letters
        self.genreCounts = genreCounts
    }
}

/// A library's counts for its header ("142 shows · 3,418 episodes").
public struct LibrarySummary: Hashable, Sendable {
    public var items: Int
    public var episodes: Int
    public var continuing: Int
    public var unmatched: Int
    public var totalSizeBytes: Int64

    public init(items: Int, episodes: Int, continuing: Int, unmatched: Int, totalSizeBytes: Int64) {
        self.items = items
        self.episodes = episodes
        self.continuing = continuing
        self.unmatched = unmatched
        self.totalSizeBytes = totalSizeBytes
    }
}

public struct Person: Hashable, Sendable {
    public var name: String
    public var role: String?
    public var profilePath: String?

    public init(name: String, role: String? = nil, profilePath: String? = nil) {
        self.name = name
        self.role = role
        self.profilePath = profilePath
    }
}

public struct SeasonSummary: Hashable, Sendable, Identifiable {
    public var id: Int
    public var number: Int
    public var name: String
    public var overview: String
    public var airDate: String?
    public var posterPath: String?
    public var episodeCount: Int
    public var availableCount: Int
    public var watchedCount: Int
    /// Sonarr monitors the season; nil when no Sonarr has the show (or the server is older).
    public var monitored: Bool?
    /// The newest request for the season.
    public var request: RequestState?

    public init(id: Int, number: Int, name: String, overview: String = "", airDate: String? = nil, posterPath: String? = nil, episodeCount: Int, availableCount: Int, watchedCount: Int, monitored: Bool? = nil, request: RequestState? = nil) {
        self.id = id
        self.number = number
        self.name = name
        self.overview = overview
        self.airDate = airDate
        self.posterPath = posterPath
        self.episodeCount = episodeCount
        self.availableCount = availableCount
        self.watchedCount = watchedCount
        self.monitored = monitored
        self.request = request
    }

    /// The chip's count: the episodes on disk, "7".
    public var countLabel: String { "\(availableCount)" }
}

/// The first unwatched episode on disk, for "Play next".
public struct NextUp: Hashable, Sendable {
    public var episodeID: Int
    public var season: Int
    public var episode: Int
    public var title: String
    public var fileID: Int

    public init(episodeID: Int, season: Int, episode: Int, title: String, fileID: Int) {
        self.episodeID = episodeID
        self.season = season
        self.episode = episode
        self.title = title
        self.fileID = fileID
    }
}

public struct ShowDetail: Hashable, Sendable, Identifiable {
    public var id: Int
    public var libraryID: Int
    public var title: String
    public var year: Int?
    public var overview: String
    /// "Returning Series", "Ended".
    public var status: String?
    public var genres: [String]
    public var rating: Double?
    public var posterPath: String?
    public var backdropPath: String?
    public var quality: QualityLabel?
    public var hdr: Bool
    public var seasons: [SeasonSummary]
    public var totalEpisodes: Int
    public var episodesOnDisk: Int
    public var totalSizeBytes: Int64
    public var nextAirDate: String?
    public var nextUp: NextUp?
    public var cast: [Person]
    /// "TV-14"; nil when TMDB has none.
    public var contentRating: String?
    /// TMDB's ID, which requests name the show by; nil from older servers.
    public var tmdbID: Int?
    /// The Sonarr connection that has the show.
    public var managedBy: String?

    public init(id: Int, libraryID: Int, title: String, year: Int?, overview: String, status: String?, genres: [String], rating: Double?, posterPath: String?, backdropPath: String?, quality: QualityLabel?, hdr: Bool, seasons: [SeasonSummary], totalEpisodes: Int, episodesOnDisk: Int, totalSizeBytes: Int64, nextAirDate: String?, nextUp: NextUp?, cast: [Person], contentRating: String? = nil, tmdbID: Int? = nil, managedBy: String? = nil) {
        self.id = id
        self.libraryID = libraryID
        self.title = title
        self.year = year
        self.overview = overview
        self.status = status
        self.genres = genres
        self.rating = rating
        self.posterPath = posterPath
        self.backdropPath = backdropPath
        self.quality = quality
        self.hdr = hdr
        self.seasons = seasons
        self.totalEpisodes = totalEpisodes
        self.episodesOnDisk = episodesOnDisk
        self.totalSizeBytes = totalSizeBytes
        self.nextAirDate = nextAirDate
        self.nextUp = nextUp
        self.cast = cast
        self.contentRating = contentRating
        self.tmdbID = tmdbID
        self.managedBy = managedBy
    }

    /// Not ended: the server says "Returning Series", "In Production" and so on.
    public var continuing: Bool { status.map { $0 != "Ended" && $0 != "Canceled" } ?? false }

    /// The show with only the seasons that have episodes on disk, and `keep` (the one asked for).
    public func seasonsOnDisk(keep: Int? = nil) -> ShowDetail {
        var copy = self
        copy.seasons = seasons.filter { $0.availableCount > 0 || $0.number == keep }
        return copy
    }
}

/// One version (file) of a movie or episode.
public struct MediaVersion: Hashable, Sendable, Identifiable {
    public var recording: RecordingProvenance?
    public var fileID: Int
    /// File name, for telling versions apart.
    public var label: String
    public var sizeBytes: Int64
    /// "MKV", "MP4".
    public var container: String?
    public var videoCodec: String?
    public var width: Int?
    public var height: Int?
    public var hdr: String?
    /// "TrueHD 7.1 · English", the default audio track.
    public var audioSummary: String?
    public var durationMs: Int?
    /// Every audio track in the file; empty when the server has not probed it.
    public var audio: [AudioOption]
    /// The file's own subtitle tracks; ``SubtitleOption/burnInOnly`` marks image ones.
    public var subtitles: [SubtitleOption]
    /// The Dolby Vision profile (5, 7, 8); nil for other files.
    public var dvProfile: Int?

    public init(fileID: Int, label: String, sizeBytes: Int64, container: String? = nil, videoCodec: String? = nil, width: Int? = nil, height: Int? = nil, hdr: String? = nil, audioSummary: String? = nil, durationMs: Int? = nil, audio: [AudioOption] = [], subtitles: [SubtitleOption] = [], dvProfile: Int? = nil, recording: RecordingProvenance? = nil) {
        self.fileID = fileID
        self.label = label
        self.sizeBytes = sizeBytes
        self.container = container
        self.videoCodec = videoCodec
        self.width = width
        self.height = height
        self.hdr = hdr
        self.audioSummary = audioSummary
        self.durationMs = durationMs
        self.audio = audio
        self.subtitles = subtitles
        self.dvProfile = dvProfile
        self.recording = recording
    }

    public var id: Int { fileID }

    /// The HDR tag: "DV P8" when the profile is known, else "DV", "HDR10", "HLG"; nil for SDR.
    public var hdrTag: String? {
        guard let label = hdrLabel(hdr) else { return nil }
        guard label == dolbyVision else { return label }
        if let p = dvProfile, p > 0 { return "DV P\(p)" }
        return "DV"
    }
}

/// ``MediaVersion/hdr`` and ``StreamVideo/hdr`` for Dolby Vision.
public let dolbyVision = "Dolby Vision"

/// Where the signed-in user is in a movie or episode.
public struct WatchProgress: Hashable, Sendable {
    public var positionMs: Int
    public var durationMs: Int
    public var completed: Bool

    public init(positionMs: Int, durationMs: Int, completed: Bool) {
        self.positionMs = positionMs
        self.durationMs = durationMs
        self.completed = completed
    }

    /// Partway through and worth resuming (more than 5 s in, not finished).
    public var resumable: Bool { !completed && positionMs > 5_000 }
    public var progress: Double { durationMs > 0 ? min(max(Double(positionMs) / Double(durationMs), 0), 1) : 0 }
    public var remainingMs: Int { max(durationMs - positionMs, 0) }
}

public struct EpisodeDetail: Hashable, Sendable, Identifiable {
    public var id: Int
    public var season: Int
    public var number: Int
    public var title: String
    public var overview: String
    public var airDate: String?
    public var stillPath: String?
    public var runtimeMin: Int?
    public var rating: Double?
    public var hasFile: Bool
    /// False before its air date.
    public var aired: Bool
    public var isNew: Bool
    public var versions: [MediaVersion]
    public var watch: WatchProgress?
    /// Sonarr monitors it; nil when no Sonarr has the show (or the server is older).
    public var monitored: Bool?
    /// Its download in Sonarr's queue.
    public var download: QueueDownload?
    /// The newest request for its season, on an episode without a file.
    public var request: RequestState?

    public init(id: Int, season: Int, number: Int, title: String, overview: String = "", airDate: String? = nil, stillPath: String? = nil, runtimeMin: Int? = nil, rating: Double? = nil, hasFile: Bool, aired: Bool, isNew: Bool = false, versions: [MediaVersion] = [], watch: WatchProgress? = nil, monitored: Bool? = nil, download: QueueDownload? = nil, request: RequestState? = nil) {
        self.id = id
        self.season = season
        self.number = number
        self.title = title
        self.overview = overview
        self.airDate = airDate
        self.stillPath = stillPath
        self.runtimeMin = runtimeMin
        self.rating = rating
        self.hasFile = hasFile
        self.aired = aired
        self.isNew = isNew
        self.versions = versions
        self.watch = watch
        self.monitored = monitored
        self.download = download
        self.request = request
    }

    public var watched: Bool { watch?.completed ?? false }
    /// The file "Play" plays: the first version.
    public var fileID: Int? { versions.first?.fileID }
}

public struct SeasonDetail: Hashable, Sendable {
    public var showID: Int
    public var showTitle: String
    public var season: SeasonSummary
    public var episodes: [EpisodeDetail]
    public var showBackdropPath: String?

    public init(showID: Int, showTitle: String, season: SeasonSummary, episodes: [EpisodeDetail], showBackdropPath: String? = nil) {
        self.showID = showID
        self.showTitle = showTitle
        self.season = season
        self.episodes = episodes
        self.showBackdropPath = showBackdropPath
    }

    /// The season with only the episodes on disk, and `keep` (the one opened
    /// from Home, which may be missing or yet to air).
    public func episodesOnDisk(keep: Int? = nil) -> SeasonDetail {
        var copy = self
        copy.episodes = episodes.filter { $0.hasFile || $0.id == keep }
        return copy
    }
}

public struct MovieDetail: Hashable, Sendable, Identifiable {
    public var id: Int
    public var libraryID: Int
    public var title: String
    public var year: Int?
    public var tagline: String?
    public var overview: String
    public var releaseDate: String?
    public var runtimeMin: Int?
    public var genres: [String]
    public var rating: Double?
    public var posterPath: String?
    public var backdropPath: String?
    public var quality: QualityLabel?
    public var hdr: Bool
    public var versions: [MediaVersion]
    public var cast: [Person]
    public var watch: WatchProgress?
    /// "R", "PG-13"; nil when TMDB has none.
    public var contentRating: String?
    /// The Radarr connection that has the movie.
    public var managedBy: String?
    /// A download of it in Radarr's queue, such as an upgrade.
    public var download: QueueDownload?
    /// Its trailers, best first: files in the library, then YouTube's (PLAN-TRAILERS.md §5.3).
    public var trailers: [MovieTrailer]

    public init(id: Int, libraryID: Int, title: String, year: Int?, tagline: String?, overview: String, releaseDate: String?, runtimeMin: Int?, genres: [String], rating: Double?, posterPath: String?, backdropPath: String?, quality: QualityLabel?, hdr: Bool, versions: [MediaVersion], cast: [Person], watch: WatchProgress?, contentRating: String? = nil, managedBy: String? = nil, download: QueueDownload? = nil, trailers: [MovieTrailer] = []) {
        self.id = id
        self.libraryID = libraryID
        self.title = title
        self.year = year
        self.tagline = tagline
        self.overview = overview
        self.releaseDate = releaseDate
        self.runtimeMin = runtimeMin
        self.genres = genres
        self.rating = rating
        self.posterPath = posterPath
        self.backdropPath = backdropPath
        self.quality = quality
        self.hdr = hdr
        self.versions = versions
        self.cast = cast
        self.watch = watch
        self.contentRating = contentRating
        self.managedBy = managedBy
        self.download = download
        self.trailers = trailers
    }

    public var watched: Bool { watch?.completed ?? false }
}

public struct SearchResults: Hashable, Sendable {
    public var movies: [BrowseItem]
    public var shows: [BrowseItem]
    public var episodes: [EpisodeCard]

    public init(movies: [BrowseItem] = [], shows: [BrowseItem] = [], episodes: [EpisodeCard] = []) {
        self.movies = movies
        self.shows = shows
        self.episodes = episodes
    }

    public var isEmpty: Bool { movies.isEmpty && shows.isEmpty && episodes.isEmpty }
}

/// TMDB image sizes the server's /api/images route serves.
public enum ImageSize: String, Hashable, Sendable {
    case w92, w154, w185, w300, w342, w500, w780, w1280, original
}

/// Browsing the current server's libraries. Methods throw ``APIError``; a
/// revoked or expired sign-in throws ``SignedOutError``.
@MainActor
public protocol LibraryRepository: AnyObject {
    func home() async throws -> HomeData

    func libraries() async throws -> [Library]

    func items(_ query: ItemQuery) async throws -> ItemPage

    func show(id: Int) async throws -> ShowDetail

    func season(showID: Int, number: Int) async throws -> SeasonDetail

    func movie(id: Int) async throws -> MovieDetail

    func search(_ text: String) async throws -> SearchResults

    func setWatched(_ kind: WatchKind, id: Int, watched: Bool) async throws

    func setSeasonWatched(showID: Int, season: Int, watched: Bool) async throws

    func setShowWatched(showID: Int, watched: Bool) async throws

    /// A request to play `fileID`, named from the server's records, for a
    /// `watchmymedia://play/{fileId}` link that carries no title.
    func playRequest(fileID: Int) async throws -> PlayRequest

    /// The full URL of a TMDB image path at `size`, or nil for no image. The
    /// image loader adds the sign-in, so the URL carries no token.
    func imageURL(_ path: String?, size: ImageSize) -> URL?

    /// Yields when watch state or the library changed (a mark watched here, or
    /// the server's watch.changed and library.changed events), so screens
    /// refetch. Each call is a new listener; it ends when the task is cancelled.
    func changes() -> AsyncStream<Void>
}
