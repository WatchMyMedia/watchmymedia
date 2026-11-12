import Foundation
import WMMModel

/// The mockups' sample library (every title and name is fictional), ported
/// from android/core/fake. Poster paths are nil so posters draw their
/// placeholder gradients; backdrops and stills are ``FakeBackdrops``' scenes,
/// so Home's row screens have a picture to fill.
public enum FakeData {
    public static let tvLibrary = 2
    public static let movieLibrary = 1

    private struct S {
        var title: String, year: Int, seasons: Int, q: String
        var hdr = false, isNew = false, pct: Double? = nil, watched = false
        var status = "Returning Series", genres = ["Drama"], cert = "TV-14"
    }

    private struct M {
        var title: String, year: Int, q: String
        var isNew = false, released: String, genres = ["Drama"], runtime = 112, cert = "PG-13"
    }

    private static let showRows: [S] = [
        S(title: "Harbor Lights", year: 2022, seasons: 3, q: "4K", hdr: true, isNew: true, pct: 0.40, genres: ["Drama", "Crime"]),
        S(title: "The Quiet Meridian", year: 2021, seasons: 2, q: "4K", hdr: true, pct: 0.55, genres: ["Sci-Fi", "Drama"]),
        S(title: "Saltmarsh", year: 2024, seasons: 1, q: "1080p", pct: 0.85),
        S(title: "Northfield Precinct", year: 2024, seasons: 1, q: "1080p", isNew: true, genres: ["Crime"], cert: "TV-MA"),
        S(title: "Orbital Decay", year: 2024, seasons: 1, q: "4K", hdr: true, isNew: true, genres: ["Sci-Fi"]),
        S(title: "Copperline", year: 2019, seasons: 4, q: "4K", pct: 0.12),
        S(title: "The Lantern House", year: 2020, seasons: 2, q: "1080p", watched: true, status: "Ended"),
        S(title: "Westward Signals", year: 2016, seasons: 5, q: "1080p", watched: true, status: "Ended"),
        S(title: "Glass Country", year: 2024, seasons: 1, q: "4K", hdr: true, genres: ["Documentary"]),
        S(title: "Midnight Cartography", year: 2018, seasons: 2, q: "1080p", genres: ["Animation"], cert: "TV-PG"),
        S(title: "Hollow Pines", year: 2021, seasons: 3, q: "720p", pct: 0.70, genres: ["Comedy"]),
        S(title: "Second Ascent", year: 2025, seasons: 1, q: "4K", hdr: true, genres: ["Documentary"]),
        S(title: "Ferrous", year: 2017, seasons: 3, q: "1080p", watched: true, status: "Ended"),
        S(title: "The Narrow Hours", year: 2023, seasons: 2, q: "1080p"),
        S(title: "Pale Water", year: 2015, seasons: 6, q: "1080p", watched: true, status: "Ended"),
        S(title: "Kestrel Station", year: 2022, seasons: 2, q: "4K", genres: ["Sci-Fi"]),
    ]

    private static let movieRows: [M] = [
        M(title: "Dead Reckoning Bay", year: 2026, q: "4K", isNew: true, released: "2026-09-26", genres: ["Thriller"], cert: "R"),
        M(title: "The Paper Kite", year: 2026, q: "1080p", isNew: true, released: "2026-03-14"),
        M(title: "Ninety Fathoms", year: 2026, q: "4K", released: "2026-08-08", genres: ["Adventure"]),
        M(title: "An Honest Winter", year: 2025, q: "1080p", released: "2025-12-05"),
        M(title: "Signal to Noise", year: 2026, q: "4K", released: "2026-10-17", genres: ["Sci-Fi"]),
        M(title: "Low Tide at Carrow", year: 2024, q: "1080p", released: "2024-06-21"),
        M(title: "The Archivist", year: 2023, q: "1080p", released: "2023-02-10", genres: ["Mystery"]),
        M(title: "Tin Sky", year: 2026, q: "720p", released: "2026-07-03", genres: ["Animation"], cert: "PG"),
        M(title: "Cold Harbour Road", year: 2022, q: "4K", released: "2022-11-18", genres: ["Crime"], cert: "R"),
        M(title: "A Lesser Moon", year: 2026, q: "1080p", released: "2026-05-22"),
    ]

    public static let shows: [BrowseItem] = showRows.enumerated().map { i, s in
        BrowseItem(
            kind: .show, id: 100 + i, libraryID: tvLibrary, title: s.title, year: s.year,
            overview: "A storm closes the harbour for the first time in forty years, and the town has one night to find out who moved the channel markers.",
            genres: s.genres, rating: 7.0 + Double(i % 20) / 10, backdropPath: FakeBackdrops.path(for: s.title), quality: s.q, hdr: s.hdr, isNew: s.isNew,
            subtitle: "\(s.year) · \(plural(s.seasons, "season"))", watched: s.watched, progress: s.pct,
            episodeCount: s.seasons * 8
        )
    }

    public static let movies: [BrowseItem] = movieRows.enumerated().map { i, m in
        BrowseItem(
            kind: .movie, id: 500 + i, libraryID: movieLibrary, title: m.title, year: m.year,
            overview: "A salvage crew finds a ship that sank twice, and only one of them remembers the first time.",
            genres: m.genres, rating: 6.5 + Double(i % 30) / 10, backdropPath: FakeBackdrops.path(for: m.title), quality: m.q, hdr: m.q == "4K", isNew: m.isNew,
            watched: i == 6, progress: i == 1 ? 0.66 : nil, runtimeMin: m.runtime, releaseDate: m.released
        )
    }

    /// What an OpenSubtitles search finds for the fake episode.
    public static let onlineSubtitles = OnlineSubtitles(
        results: [
            OnlineSubtitle(id: "9001", language: "en", label: "English", release: "Harbor.Lights.S03E07.2160p.WEB.H265", hashMatch: true, hearingImpaired: false, forced: false, aiTranslated: false, downloads: 412),
            OnlineSubtitle(id: "9002", language: "en", label: "English", release: "Harbor.Lights.S03E07.1080p.WEB.H264", hashMatch: false, hearingImpaired: true, forced: false, aiTranslated: false, downloads: 980),
            OnlineSubtitle(id: "9003", language: "es", label: "Spanish", release: "Harbor.Lights.S03E07.720p.HDTV", hashMatch: false, hearingImpaired: false, forced: false, aiTranslated: false, downloads: 120),
            OnlineSubtitle(id: "9004", language: "pt-br", label: "Portuguese (Brazil)", release: "Harbor.Lights.S03E07.1080p.WEB", hashMatch: false, hearingImpaired: false, forced: false, aiTranslated: true, downloads: 64),
        ],
        canDownload: true,
        remaining: 18
    )

    public static let continueWatching: [ContinueItem] = [
        ContinueItem(kind: .episode, title: "The Quiet Meridian", subtitle: "S02 E04 · Drift", backdropPath: FakeBackdrops.path(for: "The Quiet Meridian"), fileID: 9001, showID: 101, episodeID: 101024, season: 2, episode: 4, positionMs: 28 * 60_000, durationMs: 51 * 60_000),
        ContinueItem(kind: .movie, title: "Dead Reckoning Bay", backdropPath: FakeBackdrops.path(for: "Dead Reckoning Bay"), fileID: 9002, movieID: 500, positionMs: 31 * 60_000, durationMs: 103 * 60_000),
        ContinueItem(kind: .episode, title: "Saltmarsh", subtitle: "S01 E09 · Undertow", backdropPath: FakeBackdrops.path(for: "Saltmarsh"), fileID: 9003, showID: 102, episodeID: 102019, season: 1, episode: 9, positionMs: 45 * 60_000, durationMs: 53 * 60_000),
        ContinueItem(kind: .episode, title: "Copperline", subtitle: "S04 E01 · Slag", backdropPath: FakeBackdrops.path(for: "Copperline"), fileID: 9004, showID: 105, episodeID: 105041, season: 4, episode: 1, positionMs: 6 * 60_000, durationMs: 47 * 60_000),
        ContinueItem(kind: .movie, title: "The Paper Kite", backdropPath: FakeBackdrops.path(for: "The Paper Kite"), fileID: 9005, movieID: 501, positionMs: 66 * 60_000, durationMs: 100 * 60_000),
    ]

    /// Episode IDs follow `season(showID:number:)`'s (show × 1000 + season × 10 + episode), so a card opens its episode.
    public static func recentlyAired(now: Date = .now) -> [EpisodeCard] {
        [
            EpisodeCard(episodeID: 100037, showID: 100, showTitle: "Harbor Lights", season: 3, episode: 7, title: "The Long Dark", overview: "A storm closes the harbour for the first time in forty years, and Maren has one night to find out who moved the channel markers before the ferry comes in.", airDate: day(now, -1), hasFile: true, fileID: 9101, isNew: true),
            EpisodeCard(episodeID: 103013, showID: 103, showTitle: "Northfield Precinct", season: 1, episode: 3, title: "Night Court", overview: "A night shift ends with a confession nobody asked for.", airDate: day(now, -2), hasFile: true, fileID: 9102, isNew: true),
            EpisodeCard(episodeID: 101026, showID: 101, showTitle: "The Quiet Meridian", season: 2, episode: 6, title: "Drift", airDate: day(now, -3), hasFile: true, fileID: 9103, isNew: false),
            EpisodeCard(episodeID: 105042, showID: 105, showTitle: "Copperline", season: 4, episode: 2, title: "Slag", airDate: day(now, -4), hasFile: false, fileID: nil, isNew: false),
            EpisodeCard(episodeID: 108016, showID: 108, showTitle: "Glass Country", season: 1, episode: 6, title: "Kiln", airDate: day(now, -5), hasFile: true, fileID: 9105, isNew: false, watched: true),
        ].map { card in
            // The show's backdrop stands in for a still, as the server's `showBackdropPath` does.
            var copy = card
            copy.showBackdropPath = FakeBackdrops.path(for: card.showTitle)
            return copy
        }
    }

    public static func home(now: Date = .now) -> HomeData {
        let addedShows = ["S03 E07 · added today", "Season 1 · yesterday", "S02 E10 · 2 days ago", "Complete series · 3 days ago", "S01 E05 · 4 days ago"]
        let addedMovies = ["added today", "added yesterday", "added 3 days ago", "added 4 days ago", "added 5 days ago"]
        // A card for one episode names it, as the server does (PLAN-EPISODE-PAGE.md §10), where the show has it.
        let addedEpisodes: [(season: Int, episode: Int)?] = [(3, 7), nil, (2, 10), nil, (1, 5)]
        return HomeData(
            hero: Hero(
                kind: .episode, kicker: "New episode · aired yesterday", title: "Harbor Lights", facts: "S03 E07 · The Long Dark · 52 min",
                overview: "A storm closes the harbour for the first time in forty years, and Maren has one night to find out who moved the channel markers before the ferry comes in.",
                backdropPath: FakeBackdrops.path(for: "Harbor Lights"),
                showID: 100, episodeID: 100037, fileID: 9101, contentRating: "TV-14", quality: "4K", hdr: true
            ),
            continueWatching: continueWatching,
            recentlyAddedShows: shows.prefix(10).enumerated().map { i, s in
                var c = s
                c.subtitle = addedShows[i % 5]
                if let e = addedEpisodes[i % 5], show(id: s.id).seasons.contains(where: { $0.number == e.season && $0.episodeCount >= e.episode }) {
                    c.episodeID = s.id * 1000 + e.season * 10 + e.episode
                    c.seasonNumber = e.season
                }
                return c
            },
            recentlyAired: recentlyAired(now: now),
            recentlyAddedMovies: movies.enumerated().map { i, m in var c = m; c.subtitle = addedMovies[i % 5]; return c },
            recentlyReleased: Array(movies.sorted { ($0.releaseDate ?? "") > ($1.releaseDate ?? "") }.prefix(6)),
            totals: LibraryTotals(movies: 214, shows: 142, episodes: 3418, files: 3632, totalSizeBytes: 18_400_000_000_000),
            lastScannedAt: now.addingTimeInterval(-12 * 60),
            movieLibraryID: movieLibrary,
            tvLibraryID: tvLibrary
        )
    }

    /// The show TMDB knows as `tmdbID` in the fakes: a library show's is 70000 + its ID.
    public static func tmdbID(showID: Int) -> Int { 70_000 + showID }

    /// Harbor Lights' season 2 is missing its first three episodes, each for
    /// another reason (ShowMissing.dc.html): not monitored, downloading, and
    /// monitored with Sonarr looking.
    static let missing: [Int: [Int: [Int]]] = [100: [2: [1, 2, 3]]]

    /// Sonarr has Harbor Lights and Copperline, monitored.
    static let sonarrShows: Set<Int> = [100, 105]

    public static func show(id: Int) -> ShowDetail {
        let index = shows.firstIndex { $0.id == id } ?? 0
        let s = shows[index], row = showRows[index]
        let seasons = (1...row.seasons).map { number in
            let episodes = episodeRows(showID: s.id, number: number)
            return SeasonSummary(
                id: s.id * 100 + number, number: number, name: "Season \(number)", airDate: "\((s.year ?? 2020) + number - 1)-09-14",
                episodeCount: episodes.count, availableCount: episodes.filter(\.hasFile).count,
                watchedCount: episodes.filter { $0.hasFile && $0.watched }.count,
                monitored: sonarrShows.contains(s.id) ? true : nil
            )
        }
        let next = episodeRows(showID: s.id, number: row.seasons).first { $0.hasFile && !$0.watched }
        let available = seasons.reduce(0) { $0 + $1.availableCount }
        return ShowDetail(
            id: s.id, libraryID: s.libraryID, title: s.title, year: s.year, overview: s.overview, status: row.status,
            genres: s.genres, rating: s.rating, posterPath: nil, backdropPath: s.backdropPath, quality: s.quality, hdr: s.hdr,
            seasons: seasons,
            totalEpisodes: seasons.reduce(0) { $0 + $1.episodeCount }, episodesOnDisk: available, totalSizeBytes: Int64(available) * 2_400_000_000,
            nextAirDate: s.watched ? nil : "2026-11-05",
            nextUp: s.watched ? nil : next.flatMap { episode in
                episode.fileID.map { NextUp(episodeID: episode.id, season: episode.season, episode: episode.number, title: episode.title, fileID: $0) }
            },
            cast: [Person(name: "Ines Varga", role: "Maren Holt"), Person(name: "Tomas Reyes", role: "Harbourmaster"), Person(name: "Adaeze Bell", role: "Wren")],
            contentRating: row.cert,
            tmdbID: tmdbID(showID: s.id),
            managedBy: sonarrShows.contains(s.id) ? "Sonarr" : nil
        )
    }

    /// A library's counts as the server gives them, from the fake rows.
    public static func summary(libraryID: Int) -> LibrarySummary {
        let movieLib = libraryID == movieLibrary
        let all = movieLib ? movies : shows
        return LibrarySummary(
            items: all.count, episodes: all.reduce(0) { $0 + ($1.episodeCount ?? 0) },
            continuing: movieLib ? 0 : showRows.filter { $0.status != "Ended" }.count,
            unmatched: 0, totalSizeBytes: movieLib ? 2_100_000_000_000 : 16_300_000_000_000
        )
    }

    /// How a movie's versions would play on a device that plays HEVC and AAC
    /// but not TrueHD: the 4K remux converts its audio, the 1080p file plays as it is.
    public static func plan(fileID: Int) -> PlaybackPlan? {
        if movies.contains(where: { fileID == 9400 + $0.id }) {
            return PlaybackPlan(fileID: fileID, mode: .audioTranscode, video: StreamVideo(width: 3840, height: 2160, hdr: dolbyVision), audioCodec: "AAC", audioChannels: 6, reasons: ["client cannot decode truehd audio"])
        }
        if movies.contains(where: { fileID == 9500 + $0.id }) {
            return PlaybackPlan(fileID: fileID, mode: .direct, video: StreamVideo(width: 1920, height: 1080, hdr: nil))
        }
        return nil
    }

    public static func season(showID: Int, number: Int, now: Date = .now) -> SeasonDetail {
        let show = show(id: showID)
        let summary = show.seasons.first { $0.number == number } ?? show.seasons[0]
        return SeasonDetail(showID: show.id, showTitle: show.title, season: summary, episodes: episodeRows(showID: show.id, number: summary.number, now: now))
    }

    /// Home's stable file IDs also identify the season rows and the player's current episode.
    /// Harbor keeps a playable eighth episode for the debug Next Episode flow, then an upcoming row.
    private static func episodeRows(showID: Int, number: Int, now: Date = .now) -> [EpisodeDetail] {
        let index = shows.firstIndex { $0.id == showID } ?? 0
        let show = shows[index], row = showRows[index]
        let harbor = show.id == 100 && number == 3
        let saltmarsh = show.id == 102 && number == 1
        let count = harbor || saltmarsh ? 9 : 8
        let available = harbor ? 8 : saltmarsh ? 9 : number == row.seasons && !show.watched ? 7 : 8
        let watchedCount = number < row.seasons ? 8 : 4
        let titles = ["Low Water", "The Ferryman", "Markers", "Undertow", "The Long Dark", "Squall Line", "Lantern Rock", "High Water"]
        let gone = missing[showID]?[number] ?? []
        let sonarr = sonarrShows.contains(showID)
        let airedCards = recentlyAired(now: now).filter { $0.showID == show.id && $0.season == number }
        return (1...count).map { episode in
            let card = airedCards.first { $0.episode == episode }
            let continuing = continueWatching.first { $0.showID == show.id && $0.season == number && $0.episode == episode }
            let missed = gone.contains(episode)
            let onDisk = !missed && (continuing != nil || (card?.hasFile ?? (episode <= available)))
            let runtime = continuing.map { $0.durationMs / 60_000 } ?? (harbor && episode == 7 ? 52 : harbor && episode == 8 ? 48 : 48 + episode % 6)
            let title = continuing?.subtitle?.components(separatedBy: " · ").dropFirst().joined(separator: " · ")
                ?? card?.title ?? (harbor && episode == 8 ? "Squall Line" : titles[(episode - 1) % titles.count])
            let fileID = continuing?.fileID ?? card?.fileID ?? (9300 + show.id * 100 + number * 10 + episode)
            let watch: WatchProgress?
            if let continuing {
                watch = WatchProgress(positionMs: continuing.positionMs, durationMs: continuing.durationMs, completed: false)
            } else if card?.watched ?? (episode <= watchedCount) {
                watch = WatchProgress(positionMs: 0, durationMs: runtime * 60_000, completed: true)
            } else if episode == watchedCount + 1 {
                watch = WatchProgress(positionMs: 21 * 60_000, durationMs: 50 * 60_000, completed: false)
            } else {
                watch = nil
            }
            return EpisodeDetail(
                id: show.id * 1000 + number * 10 + episode, season: number, number: episode, title: title,
                overview: card.flatMap { $0.overview.isEmpty ? nil : $0.overview } ?? "The harbour is closed, and the ferry is two hours out.",
                airDate: card?.airDate ?? (onDisk ? String(format: "2026-10-%02d", episode + 10) : "2026-11-04"),
                runtimeMin: runtime, rating: 7.8, hasFile: onDisk, aired: card != nil || onDisk || missed,
                isNew: card?.isNew ?? (onDisk && episode == available),
                versions: onDisk ? [MediaVersion(fileID: fileID, label: "\(show.title) - S0\(number)E0\(episode).mkv", sizeBytes: 2_400_000_000, container: "MKV", videoCodec: "hevc", width: 3840, height: 2160, hdr: "hdr10", audioSummary: "EAC3 5.1 · English", durationMs: runtime * 60_000)] : [],
                watch: onDisk ? watch : nil,
                monitored: sonarr ? (missed && episode == gone.first ? false : true) : nil,
                download: missed && episode == gone.dropFirst().first
                    ? QueueDownload(state: .downloading, progress: 0.45, finishesAt: now.addingTimeInterval(12 * 60 + 20)) : nil
            )
        }
    }

    static func playbackEpisode(fileID: Int) -> (show: BrowseItem, episode: EpisodeDetail, episodes: [EpisodeDetail])? {
        for (index, show) in shows.enumerated() {
            for number in 1...showRows[index].seasons {
                let episodes = episodeRows(showID: show.id, number: number)
                if let episode = episodes.first(where: { $0.fileID == fileID }) { return (show, episode, episodes) }
            }
        }
        return nil
    }

    /// The next playable row in the same season, never a hard-coded file from another show.
    public static func nextEpisode(afterFileID fileID: Int) -> UpNext? {
        guard let current = playbackEpisode(fileID: fileID),
              let next = current.episodes.first(where: { $0.number > current.episode.number && $0.hasFile }),
              let nextFile = next.fileID else { return nil }
        return UpNext(
            fileID: nextFile, episodeID: next.id, showID: current.show.id, showTitle: current.show.title,
            season: next.season, episode: next.number, title: next.title, stillPath: next.stillPath,
            runtimeMs: next.versions.first?.durationMs, seasonFinale: next.number == current.episodes.last?.number
        )
    }

    public static func movie(id: Int) -> MovieDetail {
        let index = movies.firstIndex { $0.id == id } ?? 0
        let m = movies[index]
        let runtime = m.runtimeMin ?? 110
        return MovieDetail(
            id: m.id, libraryID: m.libraryID, title: m.title, year: m.year, tagline: "Some wrecks want to be found.", overview: m.overview,
            releaseDate: m.releaseDate, runtimeMin: m.runtimeMin, genres: m.genres, rating: m.rating, posterPath: nil, backdropPath: m.backdropPath,
            quality: m.quality, hdr: m.hdr,
            versions: [
                MediaVersion(
                    fileID: 9400 + m.id, label: "\(m.title) (\(m.year ?? 2026)) - 2160p Remux.mkv", sizeBytes: 61_000_000_000, container: "MKV", videoCodec: "hevc",
                    width: 3840, height: 2160, hdr: dolbyVision, audioSummary: "TrueHD 7.1 · English", durationMs: runtime * 60_000,
                    audio: [
                        AudioOption(index: 1, label: "English · TrueHD 7.1 (Default)", language: "eng", isDefault: true),
                        AudioOption(index: 2, label: "English · AC3 5.1", language: "eng", isDefault: false),
                        AudioOption(index: 3, label: "English · AAC Stereo · Commentary", language: "eng", isDefault: false),
                        AudioOption(index: 4, label: "French · AC3 5.1", language: "fre", isDefault: false),
                    ],
                    subtitles: [
                        SubtitleOption(id: "s5", label: "English", language: "eng", format: "SRT"),
                        SubtitleOption(id: "s6", label: "English · SDH", language: "eng", format: "SRT"),
                        SubtitleOption(id: "s7", label: "English", language: "eng", burnInOnly: true, format: "PGS"),
                        SubtitleOption(id: "s8", label: "French (Forced)", language: "fre", forced: true, format: "ASS"),
                    ],
                    dvProfile: 8
                ),
                MediaVersion(
                    fileID: 9500 + m.id, label: "\(m.title) (\(m.year ?? 2026)) - 1080p.mp4", sizeBytes: 9_800_000_000, container: "MP4", videoCodec: "h264",
                    width: 1920, height: 1080, audioSummary: "AAC 5.1 · English", durationMs: runtime * 60_000,
                    audio: [AudioOption(index: 1, label: "English · AAC 5.1 (Default)", language: "eng", isDefault: true)],
                    subtitles: [SubtitleOption(id: "s2", label: "English", language: "eng", format: "TX3G")]
                ),
            ],
            cast: [Person(name: "Hollis Kade", role: "Captain Ruth Abernathy"), Person(name: "Mira Okonjo", role: "Dov")],
            watch: m.progress.map { WatchProgress(positionMs: Int($0 * Double(runtime * 60_000)), durationMs: runtime * 60_000, completed: false) }
                ?? (m.watched ? WatchProgress(positionMs: 0, durationMs: runtime * 60_000, completed: true) : nil),
            contentRating: movieRows[index].cert,
            trailers: trailers(movieID: m.id)
        )
    }

    /// Big Buck Bunny on Blender's channel: Creative Commons (CC BY 3.0), and
    /// it lets other sites embed it, so the fakes can play it in YouTube's player.
    public static let youTubeTrailerKey = "YE7VzlLtp-4"

    /// A trailer file's id: one after each movie's two versions.
    public static func trailerFileID(movieID: Int) -> Int { 9800 + movieID }

    /// Dead Reckoning Bay has a YouTube trailer, The Paper Kite a trailer file
    /// and then the same YouTube trailer, as the server lists files first.
    static func trailers(movieID: Int) -> [MovieTrailer] {
        let youTube = MovieTrailer(name: "Official Trailer", source: .youTube(YouTubeTrailer(name: "Official Trailer", key: youTubeTrailerKey)!))
        switch movieID {
        case movies[0].id: return [youTube]
        case movies[1].id: return [MovieTrailer(name: "Trailer", source: .file(fileID: trailerFileID(movieID: movieID)), durationMs: 151_000), youTube]
        default: return []
        }
    }

    /// Names a file the way the server's records would, for a bare play link.
    public static func playRequest(fileID: Int, now: Date = .now) -> PlayRequest {
        if let c = continueWatching.first(where: { $0.fileID == fileID }) { return c.playRequest }
        if let e = recentlyAired(now: now).first(where: { $0.fileID == fileID }), let r = e.playRequest { return r }
        if let m = movies.first(where: { fileID == 9400 + $0.id || fileID == 9500 + $0.id }) {
            return PlayRequest(fileID: fileID, title: m.title, subtitle: m.year.map(String.init))
        }
        if let m = movies.first(where: { fileID == trailerFileID(movieID: $0.id) }) {
            return movie(id: m.id).trailerRequest(fileID: fileID, name: "Trailer")
        }
        if let found = playbackEpisode(fileID: fileID) {
            return PlayRequest(fileID: fileID, title: found.show.title,
                subtitle: "\(episodeCode(season: found.episode.season, episode: found.episode.number)) · \(found.episode.title)",
                showID: found.show.id, seasonNumber: found.episode.season)
        }
        return PlayRequest(fileID: fileID, title: "")
    }

    private static func day(_ now: Date, _ offsetDays: Int) -> String {
        let d = Calendar.current.date(byAdding: .day, value: offsetDays, to: now) ?? now
        let c = Calendar.current.dateComponents([.year, .month, .day], from: d)
        return String(format: "%04d-%02d-%02d", c.year ?? 2026, c.month ?? 1, c.day ?? 1)
    }
}
