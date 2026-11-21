import Foundation
import WMMModel

/// Cabin server's library on the fakes: a second server signed in to
/// (``FakeServers/cabin``), with a few shows and movies of its own, so a
/// test or a screenshot can tell whose title it is looking at. Every title
/// is fictional. Its ids are its own (shows from 700, movies from 800), as
/// two real servers' ids never stand for each other. Harbor Lights is on
/// both servers, so a search across them lists it twice.
public enum FakeCabin {
    public static let tvLibrary = 2
    public static let movieLibrary = 1

    private struct S {
        var title: String, year: Int, seasons: Int, q: String
        var isNew = false, watched = false, genres = ["Drama"]
    }

    private struct M {
        var title: String, year: Int, q: String, released: String
        var isNew = false, runtime = 108, watched = false, pct: Double? = nil
    }

    private static let showRows: [S] = [
        S(title: "Harbour Run", year: 2024, seasons: 1, q: "1080p", isNew: true),
        S(title: "Harbor Lights", year: 2022, seasons: 2, q: "1080p", genres: ["Drama", "Crime"]),
        S(title: "Tideline", year: 2021, seasons: 2, q: "1080p"),
        S(title: "Lowland Radio", year: 2023, seasons: 2, q: "1080p", isNew: true, genres: ["Comedy"]),
    ]

    private static let movieRows: [M] = [
        M(title: "Harbor Nights", year: 2015, q: "1080p", released: "2015-04-17", pct: 0.42),
        M(title: "Safe Harbour", year: 1998, q: "1080p", released: "1998-10-02", runtime: 125, watched: true),
        M(title: "The Ferry Line", year: 2019, q: "720p", released: "2019-06-14", isNew: true),
    ]

    public static let shows: [BrowseItem] = showRows.enumerated().map { i, s in
        BrowseItem(
            kind: .show, id: 700 + i, libraryID: tvLibrary, title: s.title, year: s.year,
            overview: "With the trawler impounded, the Marsh brothers borrow a boat they can’t afford to lose.",
            genres: s.genres, rating: 7.1 + Double(i) / 10, backdropPath: FakeBackdrops.path(for: s.title), quality: s.q, isNew: s.isNew,
            subtitle: "\(s.year) · \(plural(s.seasons, "season"))", watched: s.watched, episodeCount: s.seasons * 6
        )
    }

    public static let movies: [BrowseItem] = movieRows.enumerated().map { i, m in
        BrowseItem(
            kind: .movie, id: 800 + i, libraryID: movieLibrary, title: m.title, year: m.year,
            overview: "One night on the quay, and nobody who was there tells it the same way.",
            genres: ["Drama"], rating: 6.8 + Double(i) / 10, backdropPath: FakeBackdrops.path(for: m.title), quality: m.q, isNew: m.isNew,
            watched: m.watched, progress: m.pct, runtimeMin: m.runtime, releaseDate: m.released
        )
    }

    /// Whether `id` is one of this library's shows or movies.
    static func holds(showID id: Int) -> Bool { shows.contains { $0.id == id } }
    static func holds(movieID id: Int) -> Bool { movies.contains { $0.id == id } }

    private static let episodeTitles = ["Low Water", "Out of Harbor", "Borrowed Boat", "Slack Water", "Spring Tide", "Dead Air"]

    /// A movie's one file.
    static func fileID(movieID: Int) -> Int { 80_000 + movieID }
    /// An episode's file.
    static func fileID(episodeID: Int) -> Int { 6_000_000 + episodeID }

    private static func episodeRows(showID: Int, number: Int) -> [EpisodeDetail] {
        guard let index = shows.firstIndex(where: { $0.id == showID }) else { return [] }
        let show = shows[index], row = showRows[index]
        let last = number == row.seasons
        return (1...6).map { episode in
            let id = show.id * 1000 + number * 10 + episode
            let onDisk = !last || episode <= 4
            let watched = !last || episode <= 2
            let runtime = 44 + episode
            return EpisodeDetail(
                id: id, season: number, number: episode, title: episodeTitles[(episode - 1) % episodeTitles.count],
                overview: "The season’s last tide, and a boat that has to be back by morning.",
                airDate: onDisk ? String(format: "2026-10-%02d", episode + 12) : "2026-11-24",
                runtimeMin: runtime, rating: 7.4, hasFile: onDisk, aired: onDisk, isNew: last && episode == 4 && row.isNew,
                versions: onDisk ? [MediaVersion(fileID: fileID(episodeID: id), label: "\(show.title) - S0\(number)E0\(episode).mkv", sizeBytes: 1_400_000_000, container: "MKV", videoCodec: "h264", width: 1920, height: 1080, audioSummary: "AAC 5.1 · English", durationMs: runtime * 60_000)] : [],
                watch: onDisk && watched ? WatchProgress(positionMs: 0, durationMs: runtime * 60_000, completed: true) : nil
            )
        }
    }

    public static func show(id: Int) -> ShowDetail {
        let index = shows.firstIndex { $0.id == id } ?? 0
        let s = shows[index], row = showRows[index]
        let seasons = (1...row.seasons).map { number in
            let episodes = episodeRows(showID: s.id, number: number)
            return SeasonSummary(
                id: s.id * 100 + number, number: number, name: "Season \(number)", airDate: "\(row.year + number - 1)-09-14",
                episodeCount: episodes.count, availableCount: episodes.filter(\.hasFile).count,
                watchedCount: episodes.filter { $0.hasFile && $0.watched }.count
            )
        }
        let next = episodeRows(showID: s.id, number: row.seasons).first { $0.hasFile && !$0.watched }
        let available = seasons.reduce(0) { $0 + $1.availableCount }
        return ShowDetail(
            id: s.id, libraryID: s.libraryID, title: s.title, year: s.year, overview: s.overview, status: "Returning Series",
            genres: s.genres, rating: s.rating, posterPath: nil, backdropPath: s.backdropPath, quality: s.quality, hdr: false,
            seasons: seasons,
            totalEpisodes: seasons.reduce(0) { $0 + $1.episodeCount }, episodesOnDisk: available, totalSizeBytes: Int64(available) * 1_400_000_000,
            nextAirDate: "2026-11-24",
            nextUp: next.flatMap { episode in
                episode.fileID.map { NextUp(episodeID: episode.id, season: episode.season, episode: episode.number, title: episode.title, fileID: $0) }
            },
            cast: [Person(name: "Odile Marsh", role: "Inés"), Person(name: "Corin Vale", role: "Tam Marsh")],
            contentRating: "TV-14"
        )
    }

    public static func season(showID: Int, number: Int) -> SeasonDetail {
        let show = show(id: showID)
        let summary = show.seasons.first { $0.number == number } ?? show.seasons[0]
        return SeasonDetail(showID: show.id, showTitle: show.title, season: summary, episodes: episodeRows(showID: show.id, number: summary.number))
    }

    public static func movie(id: Int) -> MovieDetail {
        let index = movies.firstIndex { $0.id == id } ?? 0
        let m = movies[index]
        let runtime = m.runtimeMin ?? 108
        return MovieDetail(
            id: m.id, libraryID: m.libraryID, title: m.title, year: m.year, tagline: "Everyone saw it. Nobody agrees.", overview: m.overview,
            releaseDate: m.releaseDate, runtimeMin: m.runtimeMin, genres: m.genres, rating: m.rating, posterPath: nil, backdropPath: m.backdropPath,
            quality: m.quality, hdr: false,
            versions: [
                MediaVersion(
                    fileID: fileID(movieID: m.id), label: "\(m.title) (\(m.year ?? 2015)) - 1080p.mp4", sizeBytes: 8_200_000_000, container: "MP4", videoCodec: "h264",
                    width: 1920, height: 1080, audioSummary: "AAC 5.1 · English", durationMs: runtime * 60_000,
                    audio: [AudioOption(index: 1, label: "English · AAC 5.1 (Default)", language: "eng", isDefault: true)],
                    subtitles: [SubtitleOption(id: "s2", label: "English", language: "eng", format: "TX3G")]
                ),
            ],
            cast: [Person(name: "Odile Marsh", role: "The harbourmaster"), Person(name: "Corin Vale", role: "Lew")],
            watch: m.progress.map { WatchProgress(positionMs: Int($0 * Double(runtime * 60_000)), durationMs: runtime * 60_000, completed: false) }
                ?? (m.watched ? WatchProgress(positionMs: 0, durationMs: runtime * 60_000, completed: true) : nil),
            contentRating: "PG-13"
        )
    }

    /// The episodes lately aired here, as cards.
    public static func recentlyAired() -> [EpisodeCard] {
        [(700, 1, 4, true), (703, 2, 4, true), (702, 2, 4, false), (700, 1, 2, false)].compactMap { showID, season, number, isNew in
            guard let show = shows.first(where: { $0.id == showID }),
                  let episode = episodeRows(showID: showID, number: season).first(where: { $0.number == number }) else { return nil }
            return EpisodeCard(
                episodeID: episode.id, showID: showID, showTitle: show.title, season: season, episode: number, title: episode.title,
                overview: episode.overview, airDate: episode.airDate, showBackdropPath: show.backdropPath,
                hasFile: episode.hasFile, fileID: episode.fileID, isNew: isNew, watched: episode.watched
            )
        }
    }

    public static func home(now: Date = .now) -> HomeData {
        let harbourRun = shows[0]
        let nights = movies[0]
        let tideline = episodeRows(showID: 702, number: 2)[2]
        return HomeData(
            hero: Hero(
                kind: .episode, kicker: "New episode · aired Tuesday", title: harbourRun.title, facts: "S01 E04 · Slack Water · 48 min",
                overview: "With the trawler impounded, the Marsh brothers borrow a boat they can’t afford to lose and take it out on the season’s last tide.",
                backdropPath: harbourRun.backdropPath,
                showID: harbourRun.id, episodeID: harbourRun.id * 1000 + 14, fileID: fileID(episodeID: harbourRun.id * 1000 + 14), contentRating: "TV-14", quality: "1080p", hdr: false
            ),
            continueWatching: [
                ContinueItem(kind: .movie, title: nights.title, backdropPath: nights.backdropPath, fileID: fileID(movieID: nights.id), movieID: nights.id, positionMs: 46 * 60_000, durationMs: 108 * 60_000),
                ContinueItem(kind: .episode, title: "Tideline", subtitle: "S02 E03 · Borrowed Boat", backdropPath: FakeBackdrops.path(for: "Tideline"), fileID: fileID(episodeID: tideline.id), showID: 702, episodeID: tideline.id, season: 2, episode: 3, positionMs: 28 * 60_000, durationMs: 47 * 60_000),
            ],
            recentlyAddedShows: shows.enumerated().map { i, s in
                var c = s
                c.subtitle = ["S01 E04 · Tuesday", "Season 2 · 3 days ago", "S02 E04 · 4 days ago", "S02 E04 · 5 days ago"][i % 4]
                return c
            },
            recentlyAired: recentlyAired(),
            recentlyAddedMovies: movies.enumerated().map { i, m in
                var c = m
                c.subtitle = ["added today", "added 3 days ago", "added last week"][i % 3]
                return c
            },
            recentlyReleased: movies.sorted { ($0.releaseDate ?? "") > ($1.releaseDate ?? "") },
            totals: LibraryTotals(movies: movies.count, shows: shows.count, episodes: 42, files: 45, totalSizeBytes: 84_000_000_000),
            lastScannedAt: now.addingTimeInterval(-40 * 60),
            movieLibraryID: movieLibrary,
            tvLibraryID: tvLibrary
        )
    }

    public static func search(_ text: String) -> SearchResults {
        SearchResults(
            movies: movies.filter { $0.title.localizedCaseInsensitiveContains(text) },
            shows: shows.filter { $0.title.localizedCaseInsensitiveContains(text) },
            episodes: recentlyAired().filter { $0.title.localizedCaseInsensitiveContains(text) || $0.showTitle.localizedCaseInsensitiveContains(text) }
        )
    }

    static func playbackEpisode(fileID: Int) -> (show: BrowseItem, episode: EpisodeDetail)? {
        for (index, show) in shows.enumerated() {
            for number in 1...showRows[index].seasons {
                if let episode = episodeRows(showID: show.id, number: number).first(where: { $0.fileID == fileID }) { return (show, episode) }
            }
        }
        return nil
    }

    public static func playRequest(fileID: Int) -> PlayRequest {
        if let m = movies.first(where: { fileID == self.fileID(movieID: $0.id) }) {
            return PlayRequest(fileID: fileID, title: m.title, subtitle: m.year.map(String.init))
        }
        if let found = playbackEpisode(fileID: fileID) {
            return PlayRequest(fileID: fileID, title: found.show.title,
                               subtitle: "\(episodeCode(season: found.episode.season, episode: found.episode.number)) · \(found.episode.title)",
                               showID: found.show.id, seasonNumber: found.episode.season)
        }
        return PlayRequest(fileID: fileID, title: "")
    }

    static func filePage(fileID: Int) -> FilePage? {
        if let m = movies.first(where: { fileID == self.fileID(movieID: $0.id) }) { return .movie(id: m.id) }
        if let found = playbackEpisode(fileID: fileID) {
            return .episode(showID: found.show.id, season: found.episode.season, episode: found.episode.id)
        }
        return nil
    }

    static func summary(libraryID: Int) -> LibrarySummary {
        let movieLib = libraryID == movieLibrary
        let all = movieLib ? movies : shows
        return LibrarySummary(items: all.count, episodes: all.reduce(0) { $0 + ($1.episodeCount ?? 0) }, continuing: movieLib ? 0 : shows.count,
                              unmatched: 0, totalSizeBytes: movieLib ? 24_000_000_000 : 60_000_000_000)
    }
}
