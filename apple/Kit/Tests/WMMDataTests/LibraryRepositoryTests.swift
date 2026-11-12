import Foundation
import Testing
@testable import WMMData
import WMMModel

@MainActor
@Suite(.timeLimit(.minutes(1)))
struct LibraryRepositoryTests {
    let h = Harness()
    var library: any LibraryRepository { h.services.library }

    init() async throws {
        try await h.signIn()
    }

    @Test func homeMapsEveryRow() async throws {
        h.router.on("GET /api/home", .json(Fixtures.home))
        let home = try await library.home()

        let hero = try #require(home.hero)
        #expect(hero.kind == .episode)
        #expect(hero.kicker == "New episode · Harbor Lights")
        #expect(hero.showID == 11)
        #expect(hero.episodeID == 501)
        #expect(hero.fileID == 77)
        #expect(hero.movieID == nil)
        #expect(hero.backdropPath == "/hl-backdrop.jpg")

        #expect(home.continueWatching.count == 2)
        let episode = home.continueWatching[0]
        #expect(episode.kind == .episode)
        #expect(episode.season == 1)
        #expect(episode.episode == 3)
        #expect(episode.progress == 0.25)
        #expect(episode.stillPath == "/hl-s1e3.jpg")
        #expect(episode.subtitle == "S01E03 · Undertow")
        #expect(home.continueWatching[1].kind == .movie)
        #expect(home.continueWatching[1].movieID == 21)
        #expect(home.continueWatching[1].subtitle == nil)

        let show = try #require(home.recentlyAddedShows.first)
        #expect(home.recentlyAddedShows.count == 1)
        #expect(show.kind == .show)
        #expect(show.quality == "4K")
        #expect(show.hdr)
        #expect(show.isNew)
        #expect(show.episodeCount == 16)
        #expect(show.genres == ["Drama", "Mystery"])
        #expect(show.subtitle == "2 seasons")
        #expect(show.rating == 7.9)

        #expect(home.recentlyAired.count == 2)
        let aired = home.recentlyAired[0]
        #expect(aired.season == 2)
        #expect(aired.episode == 1)
        #expect(aired.airDate == "2026-10-26")
        #expect(aired.fileID == 77)
        #expect(aired.hasFile)
        #expect(aired.isNew)
        #expect(!home.recentlyAired[1].hasFile)
        #expect(home.recentlyAired[1].fileID == nil)
        #expect(home.recentlyAired[1].overview == "")

        let movie = home.recentlyAddedMovies[0]
        #expect(movie.progress == 0.25)
        #expect(movie.runtimeMin == 104)
        #expect(movie.quality == "1080p")
        #expect(movie.releaseDate == "2021-03-04")
        #expect(movie.fileName == nil)

        // An unmatched item: its ID is its first file's, and it carries the file's name and count.
        let unmatched = home.recentlyAddedMovies[1]
        #expect(unmatched.kind == .unmatched)
        #expect(unmatched.id == 1)
        #expect(unmatched.quality == nil, "\"\" is no quality")
        #expect(unmatched.fileName == "Glass Tide (2023)/Glass Tide (2023).mkv")
        #expect(unmatched.fileCount == 1)
        #expect(unmatched.subtitle == "Not looked up yet")
        #expect(unmatched.year == nil)
        #expect(unmatched.unmatchedPlayRequest == PlayRequest(fileID: 1, title: "Glass Tide (2023).mkv"))

        #expect(home.recentlyReleased.isEmpty)
        #expect(home.totals == LibraryTotals(movies: 4, shows: 1, episodes: 6, files: 10, totalSizeBytes: 227_327_054))
        #expect(abs((home.lastScannedAt?.timeIntervalSince1970 ?? 0) - 1_793_293_657.475) < 0.001)
        #expect(home.movieLibraryID == 1)
        #expect(home.tvLibraryID == 2)
        #expect(try h.router.last("GET /api/home").value(forHTTPHeaderField: "Authorization") == "Bearer tok-viewer")
    }

    @Test func librariesMapTheirTypes() async throws {
        h.router.on("GET /api/libraries", .json("""
            {"items":[
              {"id":1,"name":"Movies","path":"/media/Movies","type":"movie","fileCount":4,"missingCount":0,"pendingCount":0,"unmatchedCount":1,
               "createdAt":"2026-10-01T00:00:00Z","updatedAt":"2026-10-01T00:00:00Z"},
              {"id":2,"name":"TV Shows","path":"/media/TV","type":"tv","fileCount":6,"missingCount":0,"pendingCount":0,"unmatchedCount":0,
               "createdAt":"2026-10-01T00:00:00Z","updatedAt":"2026-10-01T00:00:00Z"}
            ]}
            """))
        #expect(try await library.libraries() == [Library(id: 1, name: "Movies", type: .movie), Library(id: 2, name: "TV Shows", type: .tv)])
    }

    @Test func librariesKeepCurrentScanAndSavedFailureEvidence() async throws {
        h.router.on("GET /api/libraries", .json("""
            {"items":[
              {"id":1,"name":"Movies","path":"/media/Movies","type":"movie","fileCount":0,"missingCount":0,"pendingCount":0,"unmatchedCount":0,
               "scan":{"libraryId":1,"scanned":2,"total":12},"lastScanError":"Folder not found.","lastScannedAt":"2026-10-01T00:00:00Z",
               "createdAt":"2026-10-01T00:00:00Z","updatedAt":"2026-10-01T00:00:00Z"}
            ]}
            """))
        let current = try #require(try await library.libraries().first)
        #expect(current.scanning)
        #expect(current.lastScanError == "Folder not found.")
        #expect(current.lastScannedAt != nil)
    }

    @Test func itemsSendTheQueryAsParametersAndMapFacetsAndTotals() async throws {
        h.router.always(
            "GET /api/libraries/2/items",
            .json(#"{"items":[],"total":42,"offset":20,"limit":20,"facets":{"genres":["Drama","Mystery"],"decades":[2020,2010]},"totals":{"items":142,"episodes":3418,"continuing":12,"unmatched":2,"totalSize":5}}"#)
        )
        let page = try await library.items(ItemQuery(libraryID: 2, sort: .added, filter: .fourK, genre: "Drama", decade: 1990, text: " light ", offset: 20, limit: 20))
        let sent = query(try h.router.last("GET /api/libraries/2/items"))
        #expect(sent == ["sort": "added", "filter": "4k", "genre": "Drama", "decade": "1990", "q": "light", "offset": "20", "limit": "20"])
        #expect(page.total == 42)
        #expect(page.offset == 20)
        #expect(page.genres == ["Drama", "Mystery"])
        #expect(page.decades == [2020, 2010])
        #expect(page.summary == LibrarySummary(items: 142, episodes: 3418, continuing: 12, unmatched: 2, totalSizeBytes: 5))

        _ = try await library.items(ItemQuery(libraryID: 2, text: "  "))
        let plain = query(try h.router.last("GET /api/libraries/2/items"))
        #expect(plain["sort"] == "title")
        #expect(plain["filter"] == nil, "All sends no filter")
        #expect(plain["genre"] == nil)
        #expect(plain["decade"] == nil)
        #expect(plain["q"] == nil)
    }

    @Test func itemsWithAnUnmatchedFileMapItsNameAndCount() async throws {
        h.router.on("GET /api/libraries/1/items", .json("""
            {"items":[{"kind":"unmatched","id":1,"libraryId":1,"title":"Glass Tide (2023).mkv","overview":"","genres":[],"quality":"","hdr":false,
              "addedAt":"2026-10-29T17:01:00.612Z","isNew":true,"totalSize":12798425,"subtitle":"Not looked up yet","watched":false,"fileCount":1,
              "fileName":"Glass Tide (2023)/Glass Tide (2023).mkv","libraryType":"movie"}],
             "total":1,"offset":0,"limit":100,"facets":{"genres":[],"decades":[]}}
            """))
        let page = try await library.items(ItemQuery(libraryID: 1))
        let item = try #require(page.items.first)
        #expect(item == BrowseItem(
            kind: .unmatched, id: 1, libraryID: 1, title: "Glass Tide (2023).mkv", isNew: true, subtitle: "Not looked up yet",
            fileName: "Glass Tide (2023)/Glass Tide (2023).mkv", fileCount: 1
        ))
        #expect(page.summary == nil, "a server that does not count the library")
    }

    @Test func showMapsSeasonsCastAndWhatToPlayNext() async throws {
        h.router.on("GET /api/shows/11", .json(Fixtures.show))
        let show = try await library.show(id: 11)
        #expect(show.title == "Harbor Lights")
        #expect(show.status == "Returning Series")
        #expect(show.continuing)
        #expect(show.year == 2019)
        #expect(show.quality == "4K")
        #expect(show.hdr)
        #expect(show.totalEpisodes == 16)
        #expect(show.episodesOnDisk == 9)
        #expect(show.totalSizeBytes == 45_000_000_000)
        #expect(show.nextAirDate == "2026-11-02")
        #expect(show.nextUp == NextUp(episodeID: 404, season: 1, episode: 4, title: "Breakwater", fileID: 71))
        #expect(show.cast == [Person(name: "Ada Brook", role: "Mara", profilePath: "/ada.jpg"), Person(name: "Tom Vale")])
        #expect(show.contentRating == nil)
        #expect(show.seasons.count == 2)
        let s1 = show.seasons[0]
        #expect(s1.number == 1)
        #expect(s1.availableCount == 8)
        #expect(s1.watchedCount == 3)
        #expect(s1.overview == "")
        #expect(show.seasons[1].posterPath == nil)
        #expect(show.seasons[1].countLabel == "1")
    }

    @Test func seasonMapsEpisodesTheirFilesAndWhereTheUserIs() async throws {
        h.router.on("GET /api/shows/11/seasons/1", .json(Fixtures.season))
        let season = try await library.season(showID: 11, number: 1)
        #expect(season.showID == 11)
        #expect(season.showTitle == "Harbor Lights")
        #expect(season.showBackdropPath == nil)
        #expect(season.season.number == 1)
        #expect(season.episodes.count == 2)
        let pilot = season.episodes[0]
        #expect(pilot.season == 1)
        #expect(pilot.number == 1)
        #expect(pilot.runtimeMin == 47)
        #expect(pilot.aired)
        #expect(pilot.hasFile)
        #expect(pilot.watch == WatchProgress(positionMs: 2_820_000, durationMs: 2_820_000, completed: true))
        #expect(pilot.watched)
        #expect(pilot.fileID == 4)
        let version = try #require(pilot.versions.first)
        #expect(pilot.versions.count == 1)
        #expect(version.fileID == 4)
        #expect(version.label == "Harbor Lights - S01E01 - Pilot.mkv")
        #expect(version.container == "MKV")
        #expect(version.videoCodec == "h264")
        #expect(version.width == 1920)
        #expect(version.quality == "1080p")
        #expect(version.audioSummary == "TrueHD")
        #expect(version.sizeBytes == 3_573_959)
        #expect(version.hdr == nil)
        let next = season.episodes[1]
        #expect(!next.hasFile)
        #expect(!next.aired)
        #expect(next.versions.isEmpty)
        #expect(next.watch == nil)
        #expect(next.airDate == nil)
    }

    @Test func movieMapsItsVersionFromTheProbe() async throws {
        h.router.on("GET /api/movies/21", .json(Fixtures.movie))
        let movie = try await library.movie(id: 21)
        #expect(movie.tagline == "Every map lies a little.")
        #expect(movie.runtimeMin == 104)
        #expect(movie.watch == WatchProgress(positionMs: 1000, durationMs: 4000, completed: false))
        #expect(movie.cast == [Person(name: "Ada Brook", role: "June")])
        let v = try #require(movie.versions.first)
        #expect(v.label == "Paper Moons (2021).mkv")
        #expect(v.container == "MKV")
        #expect(v.videoCodec == "hevc")
        #expect(v.width == 3840)
        #expect(v.height == 2160)
        #expect(v.hdr == "HDR10")
        #expect(v.title == "4K HDR10")
        #expect(v.audioSummary == "TrueHD 7.1 · English")
        #expect(v.durationMs == 150_021)
        #expect(v.audio.map(\.label) == ["Spanish · AAC Stereo", "English · TrueHD 7.1 (Default)"])
        #expect(v.audio.map(\.index) == [1, 2])
        #expect(v.audio.map(\.isDefault) == [false, true])
        #expect(v.subtitles.map(\.id) == ["s3", "s4"])
        #expect(v.subtitles.map(\.label) == ["English · SDH", "English"])
        #expect(v.subtitles.map(\.format) == ["SRT", "PGS"])
        #expect(v.subtitles.map(\.burnInOnly) == [false, true])
        #expect(v.tags == ["MKV", "HEVC", "HDR10", "TrueHD 7.1", "93.2 MB"])
    }

    @Test func newerServersFieldsMap() async throws {
        let home = Fixtures.home
            .replacingOccurrences(of: #""kicker": "New episode · Harbor Lights","#, with: #""kicker": "New episode · Harbor Lights", "contentRating": "TV-14", "quality": "4K", "hdr": true,"#)
            .replacingOccurrences(of: "\"recentlyAired\": [\n    {", with: "\"recentlyAired\": [\n    {\"watched\": true,")
        h.router.on("GET /api/home", .json(home))
        let mapped = try await library.home()
        #expect(mapped.hero?.contentRating == "TV-14")
        #expect(mapped.hero?.quality == "4K")
        #expect(mapped.hero?.hdr == true)
        #expect(mapped.recentlyAired[0].watched)
        #expect(!mapped.recentlyAired[1].watched)

        h.router.on("GET /api/shows/11", .json(Fixtures.show.replacingOccurrences(of: #""tmdbId": 9001,"#, with: #""tmdbId": 9001, "contentRating": "TV-14","#)))
        #expect(try await library.show(id: 11).contentRating == "TV-14")
        h.router.on("GET /api/movies/21", .json(Fixtures.movie.replacingOccurrences(of: #""tmdbId": 7001,"#, with: #""tmdbId": 7001, "contentRating": "","#)))
        #expect(try await library.movie(id: 21).contentRating == nil, "\"\" is no rating")

        let season = Fixtures.season.replacingOccurrences(of: #""relativePath":"#, with: #""hdr": "dolbyvision", "dvProfile": 8, "relativePath":"#)
        h.router.on("GET /api/shows/11/seasons/1", .json(season))
        let v = try #require(try await library.season(showID: 11, number: 1).episodes[0].versions.first)
        #expect(v.hdr == "Dolby Vision")
        #expect(v.dvProfile == 8)
        #expect(v.hdrTag == "DV P8")
    }

    @Test func aMoviesTrailersMapBestFirstWithoutTheOnesThisAppCantPlay() async throws {
        let trailers = #"""
            "trailers": [
              {"source": "file", "name": "Trailer", "fileId": 77, "durationMs": 151000},
              {"source": "vimeo", "name": "Teaser", "key": "123456789"},
              {"source": "youtube", "name": "Official Trailer", "key": "YE7VzlLtp-4"},
              {"source": "youtube", "name": "Bad", "key": "x\"y"},
              {"source": "file", "name": "No file"}
            ],
            """#
        h.router.on("GET /api/movies/21", .json(Fixtures.movie.replacingOccurrences(of: #""tmdbId": 7001,"#, with: #""tmdbId": 7001, "# + trailers)))
        let movie = try await library.movie(id: 21)
        #expect(movie.trailers == [
            MovieTrailer(name: "Trailer", source: .file(fileID: 77), durationMs: 151_000),
            MovieTrailer(name: "Official Trailer", source: .youTube(YouTubeTrailer(name: "Official Trailer", key: "YE7VzlLtp-4")!)),
        ])
        #expect(movie.trailer?.fileID == 77)
    }

    @Test func theFixtureServersRepliesLeaveTheNewerFieldsAtTheirDefaults() async throws {
        h.router.on("GET /api/home", .json(Fixtures.home))
        let home = try await library.home()
        #expect(home.hero?.contentRating == nil)
        #expect(home.hero?.quality == nil)
        #expect(home.hero?.hdr == false)
        #expect(!home.recentlyAired[0].watched)
        h.router.on("GET /api/movies/21", .json(Fixtures.movie))
        #expect(try await library.movie(id: 21).trailers.isEmpty, "a server before API level 20 lists none")
    }

    @Test func searchMapsAllThreeKindsAndSkipsTheServerForBlankText() async throws {
        h.router.on("GET /api/search", .json("""
            {"movies":[{"id":21,"libraryId":1,"tmdbId":7001,"title":"Paper Moons","year":2021,"overview":"","genres":[],"credits":[],"fileCount":1}],
             "shows":[{"id":11,"libraryId":2,"tmdbId":9001,"title":"Harbor Lights","overview":"","genres":[],"credits":[],"episodeCount":9}],
             "episodes":[{"id":401,"showId":11,"showTitle":"Harbor Lights","seasonNumber":1,"number":1,"title":"Pilot","fileId":4,"watched":true}]}
            """))
        let results = try await library.search("  harbor ")
        #expect(query(try h.router.last("GET /api/search"))["q"] == "harbor")
        #expect(results.movies.map(\.kind) == [.movie])
        #expect(results.movies.first?.year == 2021)
        #expect(results.shows.map(\.kind) == [.show])
        #expect(results.shows.first?.episodeCount == 9)
        let episode = try #require(results.episodes.first)
        #expect(episode.episodeID == 401)
        #expect(episode.fileID == 4)
        #expect(episode.hasFile)
        #expect(episode.watched)
        #expect(episode.playRequest?.subtitle == "S01 E01 · Pilot")

        // Only searches count: the sign-in's session refresh may land meanwhile.
        let searches = { self.h.server.seen.filter { $0.url?.path == "/api/search" }.count }
        let before = searches()
        #expect(try await library.search("   ").isEmpty)
        #expect(searches() == before)
    }

    @Test func watchedMarksUseTheRightRoutesAndTellListeners() async throws {
        h.router.always("POST /api/watch/movie/21/watched", .noContent)
        h.router.always("DELETE /api/watch/episode/401/watched", .noContent)
        h.router.always("POST /api/shows/11/seasons/2/watched", .noContent)
        h.router.always("DELETE /api/shows/11/seasons/2/watched", .noContent)
        let changes = Counter()
        let listening = Task { for await _ in library.changes() { changes.count += 1 } }
        defer { listening.cancel() }
        try await Task.sleep(for: .milliseconds(20))

        try await library.setWatched(.movie, id: 21, watched: true)
        try await library.setWatched(.episode, id: 401, watched: false)
        try await library.setSeasonWatched(showID: 11, season: 2, watched: true)
        try await library.mark(.season(showID: 11, number: 2), watched: false)
        #expect(h.router.requests("POST /api/watch/movie/21/watched").count == 1)
        #expect(h.router.requests("DELETE /api/watch/episode/401/watched").count == 1)
        #expect(h.router.requests("POST /api/shows/11/seasons/2/watched").count == 1)
        #expect(h.router.requests("DELETE /api/shows/11/seasons/2/watched").count == 1)
        #expect(await eventually { changes.count >= 1 })

        // A whole show in one call.
        h.router.on("POST /api/shows/11/watched", .noContent)
        h.router.on("DELETE /api/shows/11/watched", .noContent)
        try await library.setShowWatched(showID: 11, watched: true)
        try await library.setShowWatched(showID: 11, watched: false)
        #expect(h.router.requests("POST /api/shows/11/watched").count == 1)
        #expect(h.router.requests("DELETE /api/shows/11/watched").count == 1)
        #expect(h.router.requests("GET /api/shows/11").isEmpty)

        // A server without that call (404 here): a season at a time.
        h.router.on("GET /api/shows/11", .json(Fixtures.show))
        h.router.always("POST /api/shows/11/seasons/1/watched", .noContent)
        try await library.setShowWatched(showID: 11, watched: true)
        #expect(h.router.requests("POST /api/shows/11/seasons/1/watched").count == 1)
        #expect(h.router.requests("POST /api/shows/11/seasons/2/watched").count == 2)
    }

    @Test func aRefusedMarkThrowsAndTellsNoOne() async throws {
        h.router.on("POST /api/watch/movie/21/watched", .problem(404, "movie 21 not found"))
        await #expect(throws: APIError(status: 404, message: "Movie 21 not found.")) {
            try await library.setWatched(.movie, id: 21, watched: true)
        }
    }

    @Test func aBareFileIsNamedByItsMovie() async throws {
        h.router.on("GET /api/files/7", .json(Fixtures.file(links: #", "movieId": 21"#, kind: "movie", title: "Paper Moons")))
        h.router.on("GET /api/movies/21", .json(Fixtures.movie))
        let request = try await library.playRequest(fileID: 7)
        #expect(request == PlayRequest(fileID: 7, title: "Paper Moons", subtitle: "2021", backdropPath: "/pm-backdrop.jpg", posterPath: "/pm-poster.jpg"))
    }

    @Test func anEpisodeFileTheServerNamesIsFoundInItsSeason() async throws {
        h.router.on("GET /api/files/4", .json(Fixtures.file(links: #", "episodeId": 401, "showId": 11, "seasonNumber": 1, "episodeNumber": 1"#, kind: "episode", title: "Harbr Lites")))
        h.router.on("GET /api/shows/11/seasons/1", .json(Fixtures.season))
        let request = try await library.playRequest(fileID: 4)
        #expect(request.title == "Harbor Lights")
        #expect(request.subtitle == "S01 E01 · Pilot")
        #expect(request.backdropPath == "/e1.jpg")
        #expect(request.stillPath == "/e1.jpg", "Now Playing shows the episode's still")
        #expect(h.router.requests("GET /api/search").isEmpty, "no search for the guessed title")
    }

    @Test func aBareEpisodeFileIsFoundThroughItsShowElseNamedByItsGuess() async throws {
        h.router.always("GET /api/files/4", .json(Fixtures.file(kind: "episode", title: "Harbor Lights", guess: #", "season": 1, "episodes": [1], "episodeTitle": "Pilot""#)))
        h.router.on("GET /api/search", .json(#"{"movies":[],"shows":[{"id":11,"libraryId":2,"tmdbId":9001,"title":"Harbor Lights","overview":"","genres":[],"credits":[],"episodeCount":9}],"episodes":[]}"#))
        h.router.on("GET /api/shows/11/seasons/1", .json(Fixtures.season))
        let found = try await library.playRequest(fileID: 4)
        #expect(found.title == "Harbor Lights")
        #expect(found.subtitle == "S01 E01 · Pilot")
        #expect(h.router.requests("GET /api/shows/11/seasons/1").count == 1)

        // The show is not found: the file name's guess.
        h.router.on("GET /api/search", .json(#"{"movies":[],"shows":[],"episodes":[]}"#))
        let guessed = try await library.playRequest(fileID: 4)
        #expect(guessed == PlayRequest(fileID: 4, title: "Harbor Lights", subtitle: "S01 E01 · Pilot"))
    }

    @Test func aFileWithNothingToGoOnIsNamedByItsPath() async throws {
        h.router.on("GET /api/files/9", .json(Fixtures.file(kind: "unknown", title: "")))
        #expect(try await library.playRequest(fileID: 9) == PlayRequest(fileID: 9, title: "x.mkv"))
    }

    @Test func imageURLsPointAtTheCurrentServerWithoutAToken() {
        let base = h.router.baseURL.absoluteString
        #expect(library.imageURL("/abc.jpg", size: .w342)?.absoluteString == "\(base)/api/images/w342/abc.jpg")
        #expect(library.imageURL("abc.jpg", size: .original)?.absoluteString == "\(base)/api/images/original/abc.jpg")
        #expect(library.imageURL(nil, size: .w342) == nil)
        #expect(library.imageURL("", size: .w342) == nil)
        #expect(library.imageURL("  ", size: .w1280) == nil)
    }

    @Test func imagesAreSignedForTheCurrentServerOnly() async throws {
        h.router.always("GET /api/images/w342/abc.jpg", Reply(status: 200, body: Data([1, 2, 3])))
        let url = try #require(library.imageURL("/abc.jpg", size: .w342))
        #expect(try await h.services.images.data(for: url) == Data([1, 2, 3]))
        #expect(try h.router.last("GET /api/images/w342/abc.jpg").value(forHTTPHeaderField: "Authorization") == "Bearer tok-viewer")

        // The loader on another host's session: no token goes there.
        let other = StubServer()
        other.respond { _, _, _ in (200, Data([9])) }
        let loader = CachedImageLoader(session: other.session, current: { [store = h.services.store] in try? store.signedIn() }, rejected: { _ in })
        #expect(try await loader.data(for: other.baseURL.appending(path: "x.jpg")) == Data([9]))
        #expect(other.seen.first?.value(forHTTPHeaderField: "Authorization") == nil)
    }

    @Test func aRefusedImageTokenSignsOut() async throws {
        h.router.always("GET /api/images/w342/abc.jpg", .problem(401, "sign in required"))
        let url = try #require(library.imageURL("/abc.jpg", size: .w342))
        await #expect(throws: URLError.self) { try await h.services.images.data(for: url) }
        #expect(h.services.auth.user == nil)
    }
}

/// A count a task can bump on the main actor.
@MainActor
final class Counter {
    var count = 0
}
