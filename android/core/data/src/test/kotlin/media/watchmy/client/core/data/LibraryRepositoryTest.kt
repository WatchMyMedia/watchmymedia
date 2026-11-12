package media.watchmy.client.core.data

import kotlinx.coroutines.runBlocking
import media.watchmy.client.core.data.TestServer.Companion.fixture
import media.watchmy.client.core.data.TestServer.Companion.json
import media.watchmy.client.core.data.TestServer.Companion.noContent
import media.watchmy.client.model.ContinueItem
import media.watchmy.client.model.Hero
import media.watchmy.client.model.EpisodeContext
import media.watchmy.client.model.LetterOffset
import media.watchmy.client.model.ImageSize
import media.watchmy.client.model.ItemFilter
import media.watchmy.client.model.ItemKind
import media.watchmy.client.model.ItemQuery
import media.watchmy.client.model.ItemSort
import media.watchmy.client.model.LibrarySummary
import media.watchmy.client.model.LibraryTotals
import media.watchmy.client.model.NextUp
import media.watchmy.client.model.Person
import media.watchmy.client.model.WatchProgress
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LibraryRepositoryTest {
    private companion object {
        /** GET /api/files/{id}: the movie link, the guessed kind and title, and more of the guess. */
        const val FILE = """{"id": 4, "libraryId": 2, "path": "/media/x.mkv", "relativePath": "x.mkv", "size": 1, "modifiedAt": "2026-10-01T00:00:00Z",
            "status": "present", "firstSeenAt": "2026-10-01T00:00:00Z", "addedAt": "2026-10-01T00:00:00Z", "lastSeenAt": "2026-10-01T00:00:00Z",
            "matchStatus": "matched", "matchLocked": false, "matchIgnored": false%s, "parsed": {"kind": "%s", "title": "%s"%s}}"""
    }

    private val h = Harness()
    private val server get() = h.server
    private val library get() = h.services.library

    @Before
    fun signIn() {
        h.signIn()
    }

    @After
    fun tearDown() = h.close()

    @Test
    fun `home maps every row and leaves out kinds this app does not know`() = runBlocking {
        server.on("GET /api/home", json(fixture("home.json")))
        val home = library.home()

        val hero = home.hero!!
        assertEquals(Hero.Kind.Episode, hero.kind)
        assertEquals("New episode · Harbor Lights", hero.kicker)
        assertEquals(11L, hero.showId)
        assertEquals(501L, hero.episodeId)
        assertEquals(77L, hero.fileId)
        assertNull(hero.movieId)

        assertEquals(2, home.continueWatching.size)
        val episode = home.continueWatching[0]
        assertEquals(ContinueItem.Kind.Episode, episode.kind)
        assertEquals(1, episode.season)
        assertEquals(3, episode.episode)
        assertEquals(0.25f, episode.progress)
        assertEquals("/hl-s1e3.jpg", episode.stillPath)
        assertEquals(ContinueItem.Kind.Movie, home.continueWatching[1].kind)
        assertEquals(21L, home.continueWatching[1].movieId)

        val show = home.recentlyAddedShows.single()
        assertEquals(ItemKind.Show, show.kind)
        assertEquals("4K", show.quality)
        assertTrue(show.hdr)
        assertEquals(16, show.episodeCount)
        assertEquals(listOf("Drama", "Mystery"), show.genres)

        assertEquals(2, home.recentlyAired.size)
        val aired = home.recentlyAired[0]
        assertEquals(2, aired.season)
        assertEquals(1, aired.episode)
        assertEquals("2026-10-26", aired.airDate)
        assertEquals(77L, aired.fileId)
        assertFalse(home.recentlyAired[1].hasFile)
        assertNull(home.recentlyAired[1].fileId)

        val movie = home.recentlyAddedMovies[0]
        assertEquals(0.25f, movie.progress)
        assertEquals(104, movie.runtimeMin)
        assertEquals("1080p", movie.quality)
        val unmatched = home.recentlyAddedMovies[1]
        assertEquals(ItemKind.Unmatched, unmatched.kind)
        assertNull("\"\" is no quality", unmatched.quality)
        assertEquals("Glass Tide (2023)/Glass Tide (2023).mkv", unmatched.fileName)
        assertNull(unmatched.year)

        assertTrue(home.recentlyReleased.isEmpty())
        assertEquals(LibraryTotals(4, 1, 6, 10, 227_327_054), home.totals)
        assertEquals(epochMs("2026-10-29T17:07:37.475Z"), home.lastScannedAtMs)
        assertEquals(1L, home.movieLibraryId)
        assertEquals(2L, home.tvLibraryId)
    }

    @Test
    fun `title offsets map the whole result and remain optional on older servers`() = runBlocking {
        server.on("GET /api/libraries/2/items", json("""{"items":[],"total":900,"offset":0,"limit":2,"facets":{"genres":[],"decades":[]},"letters":[{"letter":"A","offset":0},{"letter":"Z","offset":870}]}"""))
        assertEquals(listOf(LetterOffset("A", 0), LetterOffset("Z", 870)), library.items(ItemQuery(2, sort = ItemSort.Title)).letters)
        server.on("GET /api/libraries/2/items", json("""{"items":[],"total":900,"offset":0,"limit":2,"facets":{"genres":[],"decades":[]}}"""))
        assertNull(library.items(ItemQuery(2, sort = ItemSort.Title)).letters)
    }

    @Test
    fun `genre count aggregates distinguish absent and empty replies`() = runBlocking {
        val base = """{"items":[],"total":0,"offset":0,"limit":2,"facets":{"genres":["Drama","Comedy"],"decades":[]}"""
        server.on("GET /api/libraries/2/items", json(base + """, "genreCounts":{"Drama":42,"Comedy":0}}"""))
        assertEquals(mapOf("Drama" to 42, "Comedy" to 0), library.items(ItemQuery(2)).genreCounts)
        server.on("GET /api/libraries/2/items", json(base + """, "genreCounts":{}}"""))
        assertEquals(emptyMap<String, Int>(), library.items(ItemQuery(2)).genreCounts)
        server.on("GET /api/libraries/2/items", json(base + "}"))
        assertNull(library.items(ItemQuery(2)).genreCounts)
    }

    @Test
    fun `items send the query as parameters`() = runBlocking {
        server.always(
            "GET /api/libraries/2/items",
            json("""{"items":[],"total":42,"offset":20,"limit":20,"facets":{"genres":["Drama","Mystery"],"decades":[2020,2010]}}"""),
        )
        val page = library.items(ItemQuery(2, ItemSort.Added, ItemFilter.FourK, genre = "Drama", decade = 1990, text = " light ", offset = 20, limit = 20))
        val url = server.last("GET /api/libraries/2/items").requestUrl!!
        assertEquals("added", url.queryParameter("sort"))
        assertEquals("4k", url.queryParameter("filter"))
        assertEquals("Drama", url.queryParameter("genre"))
        assertEquals("1990", url.queryParameter("decade"))
        assertEquals("light", url.queryParameter("q"))
        assertEquals("20", url.queryParameter("offset"))
        assertEquals("20", url.queryParameter("limit"))
        assertEquals(42, page.total)
        assertEquals(20, page.offset)
        assertEquals(listOf("Drama", "Mystery"), page.genres)
        assertEquals(listOf(2020, 2010), page.decades)

        library.items(ItemQuery(2))
        val plain = server.last("GET /api/libraries/2/items").requestUrl!!
        assertEquals("title", plain.queryParameter("sort"))
        assertNull("All sends no filter", plain.queryParameter("filter"))
        assertNull(plain.queryParameter("genre"))
        assertNull(plain.queryParameter("decade"))
        assertNull(plain.queryParameter("q"))
    }

    @Test
    fun `show maps seasons, cast and what to play next`() = runBlocking {
        server.on("GET /api/shows/11", json(fixture("show.json")))
        val show = library.show(11)
        assertEquals("Harbor Lights", show.title)
        assertEquals("Returning Series", show.status)
        assertEquals(2019, show.year)
        assertEquals("4K", show.quality)
        assertEquals(16, show.totalEpisodes)
        assertEquals(9, show.episodesOnDisk)
        assertEquals(45_000_000_000L, show.totalSizeBytes)
        assertEquals("2026-11-02", show.nextAirDate)
        assertEquals(NextUp(404, 1, 4, "Breakwater", 71), show.nextUp)
        assertEquals(listOf(Person("Ada Brook", "Mara", "/ada.jpg"), Person("Tom Vale", null, null)), show.cast)
        assertEquals(2, show.seasons.size)
        val s1 = show.seasons[0]
        assertEquals(1, s1.number)
        assertEquals(8, s1.availableCount)
        assertEquals(3, s1.watchedCount)
        assertNull(show.seasons[1].posterPath)
    }

    @Test
    fun `season maps episodes, their files and where the user is`() = runBlocking {
        server.on("GET /api/shows/11/seasons/1", json(fixture("season.json")))
        val season = library.season(11, 1)
        assertEquals("Harbor Lights", season.showTitle)
        assertEquals(1, season.season.number)
        assertEquals(2, season.episodes.size)
        val pilot = season.episodes[0]
        assertEquals(1, pilot.season)
        assertEquals(1, pilot.number)
        assertEquals(47, pilot.runtimeMin)
        assertTrue(pilot.aired)
        assertEquals(WatchProgress(2_820_000, 2_820_000, true), pilot.watch)
        val version = pilot.versions.single()
        assertEquals(4L, version.fileId)
        assertEquals("Harbor Lights - S01E01 - Pilot.mkv", version.label)
        assertEquals("MKV", version.container)
        assertEquals("h264", version.videoCodec)
        assertEquals(1920, version.width)
        assertEquals("TrueHD", version.audioSummary)
        assertEquals(3_573_959L, version.sizeBytes)
        val next = season.episodes[1]
        assertFalse(next.hasFile)
        assertFalse(next.aired)
        assertTrue(next.versions.isEmpty())
        assertNull(next.watch)
    }

    @Test
    fun `movie maps its version from the probe`() = runBlocking {
        server.on("GET /api/movies/21", json(fixture("movie.json")))
        val movie = library.movie(21)
        assertEquals("Every map lies a little.", movie.tagline)
        assertEquals(104, movie.runtimeMin)
        assertEquals(WatchProgress(1000, 4000, false), movie.watch)
        assertEquals(listOf(Person("Ada Brook", "June", null)), movie.cast)
        val v = movie.versions.single()
        assertEquals("Paper Moons (2021).mkv", v.label)
        assertEquals("MKV", v.container)
        assertEquals("hevc", v.videoCodec)
        assertEquals(3840, v.width)
        assertEquals(2160, v.height)
        assertEquals("HDR10", v.hdr)
        assertEquals("TrueHD 7.1 · English", v.audioSummary)
        assertEquals(150_021L, v.durationMs)
        assertEquals(listOf("Spanish · AAC Stereo", "English · TrueHD 7.1 (Default)"), v.audio.map { it.label })
        assertEquals(listOf(1, 2), v.audio.map { it.index })
        assertEquals(listOf("s3", "s4"), v.subtitles.map { it.id })
        assertEquals(listOf("English · SDH", "English"), v.subtitles.map { it.label })
        assertEquals(listOf("SRT", "PGS"), v.subtitles.map { it.format })
        assertEquals(listOf(false, true), v.subtitles.map { it.burnInOnly })
    }

    @Test
    fun `a bare file is named by its movie`() = runBlocking {
        server.on("GET /api/files/7", json(FILE.format(", \"movieId\": 21", "movie", "Paper Moons", "")))
        server.on("GET /api/movies/21", json(fixture("movie.json")))
        val request = library.playRequest(7)
        assertEquals("Paper Moons", request.title)
        assertEquals("2021", request.subtitle)
    }

    @Test
    fun `an episode file the server names is found in its season`() = runBlocking {
        server.on("GET /api/files/4", json(FILE.format(", \"episodeId\": 401, \"showId\": 11, \"seasonNumber\": 1, \"episodeNumber\": 1", "episode", "Harbr Lites", "")))
        server.on("GET /api/shows/11/seasons/1", json(fixture("season.json")))
        val request = library.playRequest(4)
        assertEquals("Harbor Lights", request.title)
        assertTrue(request.subtitle!!.startsWith("S01 E01 · "))
        assertEquals(EpisodeContext(11, 1), request.episodeContext)
        assertTrue("no search for the guessed title", server.requests("GET /api/search").isEmpty())
    }

    @Test
    fun `a bare episode file is found through its show, else named by its guess`() = runBlocking {
        server.always("GET /api/files/4", json(FILE.format("", "episode", "Harbor Lights", ", \"season\": 1, \"episodes\": [1], \"episodeTitle\": \"Pilot\"")))
        server.on(
            "GET /api/search",
            json("""{"movies":[],"shows":[{"id":11,"libraryId":2,"tmdbId":9001,"title":"Harbor Lights","overview":"","genres":[],"credits":[],"episodeCount":9}],"episodes":[]}"""),
        )
        server.on("GET /api/shows/11/seasons/1", json(fixture("season.json")))
        val found = library.playRequest(4)
        assertEquals("Harbor Lights", found.title)
        assertTrue(found.subtitle!!.startsWith("S01 E01 · "))
        assertEquals(EpisodeContext(11, 1), found.episodeContext)
        assertEquals(1, server.requests("GET /api/shows/11/seasons/1").size)

        // The show is not found: the file name's guess.
        server.on("GET /api/search", json("""{"movies":[],"shows":[],"episodes":[]}"""))
        val guessed = library.playRequest(4)
        assertEquals("Harbor Lights", guessed.title)
        assertEquals("S01 E01 · Pilot", guessed.subtitle)
        assertNull(guessed.episodeContext)
    }

    @Test
    fun `search maps all three kinds and skips the server for blank text`() = runBlocking {
        server.on(
            "GET /api/search",
            json(
                """{"movies":[{"id":21,"libraryId":1,"tmdbId":7001,"title":"Paper Moons","year":2021,"overview":"","genres":[],"credits":[],"fileCount":1}],
                "shows":[{"id":11,"libraryId":2,"tmdbId":9001,"title":"Harbor Lights","overview":"","genres":[],"credits":[],"episodeCount":9}],
                "episodes":[{"id":401,"showId":11,"showTitle":"Harbor Lights","seasonNumber":1,"number":1,"title":"Pilot","fileId":4}]}""",
            ),
        )
        val results = library.search("  harbor ")
        assertEquals("harbor", server.last("GET /api/search").requestUrl!!.queryParameter("q"))
        assertEquals(ItemKind.Movie, results.movies.single().kind)
        assertEquals(ItemKind.Show, results.shows.single().kind)
        assertEquals(9, results.shows.single().episodeCount)
        val ep = results.episodes.single()
        assertEquals(401L, ep.episodeId)
        assertEquals(4L, ep.fileId)
        assertTrue(ep.hasFile)

        // Counts searches alone: the session check and preferences load can land meanwhile.
        val before = server.requests("GET /api/search").size
        assertTrue(library.search("   ").movies.isEmpty())
        assertEquals(before, server.requests("GET /api/search").size)
    }

    @Test
    fun `watched marks use the right routes`() = runBlocking {
        server.always("POST /api/watch/movie/21/watched", noContent())
        server.always("DELETE /api/watch/episode/401/watched", noContent())
        server.always("POST /api/shows/11/seasons/2/watched", noContent())
        server.always("DELETE /api/shows/11/seasons/2/watched", noContent())
        library.setWatched(ContinueItem.Kind.Movie, 21, true)
        library.setWatched(ContinueItem.Kind.Episode, 401, false)
        library.setSeasonWatched(11, 2, true)
        library.setSeasonWatched(11, 2, false)
        assertEquals(1, server.requests("POST /api/watch/movie/21/watched").size)
        assertEquals(1, server.requests("DELETE /api/watch/episode/401/watched").size)
        assertEquals(1, server.requests("POST /api/shows/11/seasons/2/watched").size)
        assertEquals(1, server.requests("DELETE /api/shows/11/seasons/2/watched").size)

        // A whole show in one call.
        server.on("POST /api/shows/11/watched", noContent())
        server.on("DELETE /api/shows/11/watched", noContent())
        library.setShowWatched(11, true)
        library.setShowWatched(11, false)
        assertEquals(1, server.requests("POST /api/shows/11/watched").size)
        assertEquals(1, server.requests("DELETE /api/shows/11/watched").size)
        assertTrue(server.requests("GET /api/shows/11").isEmpty())

        // A server without that call (404 here): a season at a time.
        server.on("GET /api/shows/11", json(fixture("show.json")))
        server.always("POST /api/shows/11/seasons/1/watched", noContent())
        library.setShowWatched(11, true)
        assertEquals(1, server.requests("POST /api/shows/11/seasons/1/watched").size)
        assertEquals(2, server.requests("POST /api/shows/11/seasons/2/watched").size)
    }

    @Test
    fun `newer servers' ratings, hero quality, watched cards, totals and HDR map`() = runBlocking {
        val home = fixture("home.json")
            .replace("\"kicker\": \"New episode · Harbor Lights\",", "\"kicker\": \"New episode · Harbor Lights\", \"contentRating\": \"TV-14\", \"quality\": \"4K\", \"hdr\": true,")
            .replace("\"recentlyAired\": [\n    {", "\"recentlyAired\": [\n    {\"watched\": true,")
        server.on("GET /api/home", json(home))
        val h = library.home()
        assertEquals("TV-14", h.hero!!.contentRating)
        assertEquals("4K", h.hero!!.quality)
        assertTrue(h.hero!!.hdr)
        assertTrue(h.recentlyAired[0].watched)
        assertFalse(h.recentlyAired[1].watched)

        server.on("GET /api/shows/11", json(fixture("show.json").replace("\"tmdbId\": 9001,", "\"tmdbId\": 9001, \"contentRating\": \"TV-14\",")))
        assertEquals("TV-14", library.show(11).contentRating)
        server.on("GET /api/movies/21", json(fixture("movie.json").replace("\"tmdbId\": 7001,", "\"tmdbId\": 7001, \"contentRating\": \"\",")))
        val movie = library.movie(21)
        assertNull("\"\" is no rating", movie.contentRating)

        server.on(
            "GET /api/libraries/2/items",
            json("""{"items":[],"total":3,"offset":0,"limit":100,"facets":{"genres":[],"decades":[]},"totals":{"items":142,"episodes":3418,"continuing":12,"unmatched":2,"totalSize":5}}"""),
        )
        assertEquals(LibrarySummary(142, 3418, 12, 2, 5), library.items(ItemQuery(2)).summary)

        val season = fixture("season.json").replace("\"relativePath\":", "\"hdr\": \"dolbyvision\", \"dvProfile\": 8, \"relativePath\":")
        server.on("GET /api/shows/11/seasons/1", json(season))
        val v = library.season(11, 1).episodes[0].versions.single()
        assertEquals("Dolby Vision", v.hdr)
        assertEquals(8, v.dvProfile)
        assertEquals("DV P8", v.hdrTag)

        server.on(
            "GET /api/search",
            json("""{"movies":[],"shows":[],"episodes":[{"id":401,"showId":11,"showTitle":"Harbor Lights","seasonNumber":1,"number":1,"title":"Pilot","fileId":4,"watched":true}]}"""),
        )
        assertTrue(library.search("pilot").episodes.single().watched)
    }

    @Test
    fun `the fixture server's replies leave the newer fields at their defaults`() = runBlocking {
        server.on("GET /api/home", json(fixture("home.json")))
        val h = library.home()
        assertNull(h.hero!!.contentRating)
        assertNull(h.hero!!.quality)
        assertFalse(h.hero!!.hdr)
        assertFalse(h.recentlyAired[0].watched)
        server.on("GET /api/libraries/2/items", json("""{"items":[],"total":0,"offset":0,"limit":100,"facets":{"genres":[],"decades":[]}}"""))
        assertNull(library.items(ItemQuery(2)).summary)
    }

    @Test
    fun `image URLs point at the current server without a token`() {
        assertEquals("${server.baseUrl}/api/images/w342/abc.jpg", library.imageUrl("/abc.jpg", ImageSize.W342))
        assertEquals("${server.baseUrl}/api/images/original/abc.jpg", library.imageUrl("abc.jpg", ImageSize.Original))
        assertNull(library.imageUrl(null, ImageSize.W342))
        assertNull(library.imageUrl("", ImageSize.W342))
    }

    @Test
    fun `the image client signs requests to the current server only`() {
        server.always("GET /api/images/w342/abc.jpg", noContent())
        val url = library.imageUrl("/abc.jpg", ImageSize.W342)!!
        h.services.imageHttpClient.newCall(okhttp3.Request.Builder().url(url).build()).execute().close()
        assertEquals("Bearer tok-viewer", server.last("GET /api/images/w342/abc.jpg").getHeader("Authorization"))

        TestServer().use { other ->
            other.always("GET /x.jpg", noContent())
            h.services.imageHttpClient.newCall(okhttp3.Request.Builder().url("${other.baseUrl}/x.jpg").build()).execute().close()
            assertNull(other.last("GET /x.jpg").getHeader("Authorization"))
        }
    }
}
