package media.watchmy.client.core.data

import app.cash.turbine.test
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import media.watchmy.client.core.api.infrastructure.Serializer
import media.watchmy.client.core.api.models.AuthStatus
import media.watchmy.client.core.api.models.Decision
import media.watchmy.client.core.api.models.HomeData as ApiHomeData
import media.watchmy.client.core.api.models.ItemList
import media.watchmy.client.core.api.models.LibraryList
import media.watchmy.client.core.api.models.MediaFileDetail
import media.watchmy.client.core.api.models.MediaToken as ApiMediaToken
import media.watchmy.client.core.api.models.MovieDetail as ApiMovieDetail
import media.watchmy.client.core.api.models.PairingStart
import media.watchmy.client.core.api.models.PlanList
import media.watchmy.client.core.api.models.Preferences
import media.watchmy.client.core.api.models.RequestOptions as ApiRequestOptions
import media.watchmy.client.core.api.models.SearchResults as ApiSearchResults
import media.watchmy.client.core.api.models.SeasonDetail as ApiSeasonDetail
import media.watchmy.client.core.api.models.ServerInfo
import media.watchmy.client.core.api.models.SessionList
import media.watchmy.client.core.api.models.ShowDetail as ApiShowDetail
import media.watchmy.client.core.api.models.SignIn
import media.watchmy.client.core.api.models.User as ApiUser
import media.watchmy.client.core.data.TestServer.Companion.json
import media.watchmy.client.core.data.TestServer.Companion.noContent
import media.watchmy.client.model.ContinueItem
import media.watchmy.client.model.DecisionRequest
import media.watchmy.client.model.DeviceSession
import media.watchmy.client.model.EpisodeContext
import media.watchmy.client.model.Hero
import media.watchmy.client.model.ItemKind
import media.watchmy.client.model.ItemQuery
import media.watchmy.client.model.LibraryType
import media.watchmy.client.model.LibraryTotals
import media.watchmy.client.model.PairingState
import media.watchmy.client.model.PlaybackMode
import media.watchmy.client.model.PlaybackProfile
import media.watchmy.client.model.RequestBackend
import media.watchmy.client.model.SegmentKind
import media.watchmy.client.model.Server
import media.watchmy.client.model.SkipMode
import media.watchmy.client.model.StreamProtocol
import media.watchmy.client.model.StreamQuality
import media.watchmy.client.model.StreamVideo
import media.watchmy.client.model.SubtitleSource
import media.watchmy.client.model.WatchProgress
import media.watchmy.client.model.episodeCode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Replies captured from a real server on the fixture library
 * (src/test/resources/golden/README.md), decoded by the generated client and
 * mapped as the app maps them. The hand-written fixtures elsewhere test the
 * mapping's corners; these catch the client and the server drifting apart.
 * Each reply is also decoded with every optional field taken out, as the
 * oldest server the generated models accept would send it.
 */
class GoldenRepliesTest {
    private val h = Harness()
    private val server get() = h.server
    private val services get() = h.services

    private val profile = PlaybackProfile(
        name = "android",
        containers = listOf("mp4", "mkv"),
        videoCodecs = listOf("h264", "hevc"),
        audioCodecs = listOf("aac", "eac3"),
        maxWidth = 1920,
        maxHeight = 1080,
        maxAudioChannels = 6,
    )

    @Before
    fun signIn() = runBlocking {
        server.always("GET /api/server", json(golden("server")))
        server.always("GET /api/auth/me", json(golden("me")))
        server.always("GET /api/me/preferences", json(golden("preferences")))
        services.servers.select(services.servers.probe(server.baseUrl))
        server.on("POST /api/auth/login", json(golden("login")))
        services.auth.signIn("viewer", "fixture-viewer-1")
        Unit
    }

    @After
    fun tearDown() = h.close()

    @Test
    fun `every reply decodes through its generated model, with and without the optional fields`() {
        for ((name, serializer) in MODELS) {
            val full = golden(name)
            decode(name, serializer, full)
            val oldest = oldest(full, serializer)
            decode("$name without optional fields", serializer, oldest)
        }
        // The stripping does strip: these replies are mostly optional fields.
        assertNotEquals(golden("home").length, oldest(golden("home"), ApiHomeData.serializer()).length)
        assertNotEquals(golden("decision-hls").length, oldest(golden("decision-hls"), Decision.serializer()).length)
    }

    @Test
    fun `the server and the sign-in`() = runBlocking {
        val found = services.servers.probe(server.baseUrl)
        assertEquals(Server("golden-fixture-server", "Golden Fixture", server.baseUrl, "dev", 7, setupRequired = false, turnstile = false), found)
        assertEquals("viewer", services.auth.user.value!!.name)
        assertEquals(2L, services.auth.user.value!!.id)
        assertFalse(services.auth.user.value!!.isAdmin)
        server.on("GET /api/libraries", json(golden("libraries")))
        services.library.libraries()
        assertEquals("Bearer golden-token", server.last("GET /api/libraries").getHeader("Authorization"))
    }

    @Test
    fun `libraries and their items`() = runBlocking {
        server.on("GET /api/libraries", json(golden("libraries")))
        assertEquals(listOf("Movies" to LibraryType.Movie, "TV Shows" to LibraryType.Tv), services.library.libraries().map { it.name to it.type })

        server.on("GET /api/libraries/1/items", json(golden("items-movies")))
        val movies = services.library.items(ItemQuery(libraryId = 1))
        assertEquals(listOf("Glass Tide", "Harbor Lights", "Paper Moons", "Static Bloom"), movies.items.map { it.title })
        assertTrue(movies.items.all { it.kind == ItemKind.Movie })
        assertEquals(4, movies.total)
        val harbor = movies.items.first { it.title == "Harbor Lights" }
        assertEquals(2019, harbor.year)
        assertEquals("1080p", harbor.quality)
        assertEquals("/hl-poster.jpg", harbor.posterPath)
        assertNull("Static Bloom has no artwork", movies.items.first { it.title == "Static Bloom" }.posterPath)

        server.on("GET /api/libraries/2/items", json(golden("items-tv")))
        val shows = services.library.items(ItemQuery(libraryId = 2))
        val show = shows.items.single()
        assertEquals(ItemKind.Show, show.kind)
        assertEquals("Night Shift Diner", show.title)
        assertEquals(6, show.episodeCount)
        assertEquals(listOf("Comedy", "Drama"), shows.genres)
        assertEquals(listOf(2020), shows.decades)
        assertEquals(1, shows.summary!!.continuing)
        assertEquals(6, shows.summary!!.episodes)
        assertEquals(listOf(media.watchmy.client.model.LetterOffset("N", 0)), shows.letters)
        assertEquals(mapOf("Comedy" to 1, "Drama" to 1), shows.genreCounts)
    }

    @Test
    fun `home`() = runBlocking {
        server.on("GET /api/home", json(golden("home")))
        val home = services.library.home()

        val hero = home.hero!!
        assertEquals(Hero.Kind.Episode, hero.kind)
        assertEquals(1L, hero.showId)
        assertEquals(23L, hero.episodeId)
        assertEquals(10L, hero.fileId)
        assertEquals("720p", hero.quality)
        assertEquals("TV-14", hero.contentRating)

        val movie = home.continueWatching.first { it.kind == ContinueItem.Kind.Movie }
        assertEquals("Paper Moons", movie.title)
        assertEquals(2L, movie.movieId)
        assertEquals(5L, movie.fileId)
        assertEquals(0.25f, movie.progress)
        val episode = home.continueWatching.first { it.kind == ContinueItem.Kind.Episode }
        assertEquals(1, episode.season)
        assertEquals(2, episode.episode)
        assertEquals(2L, episode.fileId)

        assertEquals(listOf("Night Shift Diner"), home.recentlyAddedShows.map { it.title })
        assertEquals(4, home.recentlyAddedMovies.size)
        assertEquals(4, home.recentlyReleased.size)
        val missing = home.recentlyAired.first { it.episodeId == 24L }
        assertFalse("Aired, but no one has the file", missing.hasFile)
        assertNull(missing.fileId)
        assertTrue(home.recentlyAired.filter { it.hasFile }.all { it.fileId != null })
        assertEquals(LibraryTotals(4, 1, 6, 10, 227_327_054), home.totals)
        assertEquals(1L, home.movieLibraryId)
        assertEquals(2L, home.tvLibraryId)
        assertTrue(home.lastScannedAtMs != null)
    }

    @Test
    fun `a show and its seasons`() = runBlocking {
        server.on("GET /api/shows/1", json(golden("show")))
        val show = services.library.show(1)
        assertEquals("Night Shift Diner", show.title)
        assertEquals(2020, show.year)
        assertEquals("Returning Series", show.status)
        assertEquals("TV-14", show.contentRating)
        assertEquals(900001L, show.tmdbId)
        assertEquals(8, show.totalEpisodes)
        assertEquals(6, show.episodesOnDisk)
        assertEquals("2031-01-06", show.nextAirDate)
        assertEquals(listOf(3 to 1, 3 to 0), show.seasons.map { it.availableCount to it.watchedCount })
        assertEquals(listOf(3, 5), show.seasons.map { it.episodeCount })
        assertEquals(media.watchmy.client.model.NextUp(12, 1, 2, "Graveyard Special", 2), show.nextUp)
        assertEquals(listOf("Mae", "Cook"), show.cast.map { it.role })
        assertNull("Theo Park has no photo", show.cast[1].profilePath)

        server.on("GET /api/shows/1/seasons/1", json(golden("season-1")))
        val first = services.library.season(1, 1)
        assertEquals("Night Shift Diner", first.showTitle)
        assertEquals("/nsd-backdrop.jpg", first.showBackdropPath)
        assertEquals(listOf("Pilot", "Graveyard Special", "Last Call"), first.episodes.map { it.title })
        assertTrue(first.episodes[0].watch!!.completed)
        assertEquals(WatchProgress(60_000, 200_000, false), first.episodes[1].watch)
        assertNull(first.episodes[2].watch)
        val version = first.episodes[1].versions.single()
        assertEquals(2L, version.fileId)
        assertEquals("Night Shift Diner (2020) - S01E02 - Graveyard Special.mkv", version.label)
        assertEquals("MKV", version.container)
        assertEquals("h264", version.videoCodec)
        assertEquals(1280 to 720, version.width to version.height)
        assertEquals("AAC", version.audioSummary)

        server.on("GET /api/shows/1/seasons/2", json(golden("season-2")))
        val second = services.library.season(1, 2)
        assertEquals(listOf(true, true, true, false, false), second.episodes.map { it.hasFile })
        assertEquals("Episode 4 aired; episode 5 hasn't", listOf(true, false), second.episodes.drop(3).map { it.aired })
        assertTrue(second.episodes[3].versions.isEmpty())
    }

    @Test
    fun `a movie with its streams`() = runBlocking {
        server.on("GET /api/movies/1", json(golden("movie")))
        val movie = services.library.movie(1)
        assertEquals("Harbor Lights", movie.title)
        assertEquals("Every light goes out.", movie.tagline)
        assertEquals("PG-13", movie.contentRating)
        assertEquals(listOf("Keeper"), movie.cast.map { it.role })
        val version = movie.versions.single()
        assertEquals(8L, version.fileId)
        assertEquals(1920 to 1080, version.width to version.height)
        assertEquals(listOf("s2", "s3"), version.subtitles.map { it.id })
        assertEquals(listOf("English", "Spanish"), version.subtitles.map { it.label })
        assertEquals(1, version.audio.size)
        assertNull(movie.watch)
    }

    @Test
    fun `search and the file behind a play`() = runBlocking {
        server.on("GET /api/search", json(golden("search")))
        val results = services.library.search("t")
        assertEquals(listOf("Glass Tide", "Harbor Lights", "Static Bloom"), results.movies.map { it.title }.sorted())
        assertEquals(listOf("Night Shift Diner"), results.shows.map { it.title })
        assertTrue(results.episodes.isNotEmpty())
        assertTrue(results.episodes.all { it.showId == 1L && it.fileId != null })

        server.on("GET /api/files/2", json(golden("file")))
        server.on("GET /api/shows/1/seasons/1", json(golden("season-1")))
        val play = services.library.playRequest(2)
        assertEquals("Night Shift Diner", play.title)
        assertEquals("${episodeCode(1, 2)} · Graveyard Special", play.subtitle)
        assertEquals(EpisodeContext(1, 1), play.episodeContext)
    }

    @Test
    fun `playback decisions`() = runBlocking {
        val gateway = services.playbackGateway
        server.on("POST /api/files/2/playback", json(golden("decision-episode")))
        val episode = gateway.decide(DecisionRequest(2, profile, services.client, quality = StreamQuality.Auto))
        assertEquals(PlaybackMode.Direct, episode.mode)
        assertEquals(StreamProtocol.File, episode.protocol)
        assertTrue(episode.vod)
        assertEquals("${server.baseUrl}/api/files/2/stream?play=fedcba9876543210", episode.url)
        assertEquals(60_000L, episode.resumeMs)
        assertEquals(StreamVideo(1280, 720, null), episode.video)
        assertEquals(StreamQuality.Original, episode.autoStep)
        assertNull(episode.sessionId)
        val next = episode.next!!
        assertEquals(3L, next.fileId)
        assertEquals("Last Call", next.title)
        assertTrue(next.seasonFinale)

        server.on("POST /api/files/8/playback", json(golden("decision-movie")))
        val movie = gateway.decide(DecisionRequest(8, profile, services.client, quality = StreamQuality.Hd))
        assertEquals(listOf("s2", "s3"), movie.subtitles.map { it.option.id })
        assertEquals("${server.baseUrl}/api/files/8/subtitles/s2.vtt", movie.subtitles[0].url)
        assertEquals(SubtitleSource.Embedded, movie.subtitles[0].option.source)
        assertEquals("webvtt", movie.subtitles[0].format)

        server.on("POST /api/files/6/playback", json(golden("decision-hls")))
        val hls = gateway.decide(DecisionRequest(6, profile, services.client, quality = StreamQuality.Auto))
        assertEquals(PlaybackMode.AudioTranscode, hls.mode)
        assertEquals(StreamProtocol.Hls, hls.protocol)
        assertFalse("An event playlist is in stream time", hls.vod)
        assertEquals("0123456789abcdef", hls.sessionId)
        assertEquals("${server.baseUrl}/api/playback/0123456789abcdef/index.m3u8", hls.url)
        assertEquals(listOf("client cannot decode dts audio"), hls.reasons)
        assertTrue(hls.subtitles.single().option.forced)
        server.on("DELETE /api/playback/0123456789abcdef", noContent())
        gateway.stopSession(hls.sessionId!!)

        server.on("POST /api/playback/plans", json(golden("plans")))
        val plans = gateway.plans(profile, StreamQuality.Auto, listOf(8, 6))
        assertEquals(listOf(8L to PlaybackMode.Direct, 6L to PlaybackMode.AudioTranscode), plans.map { it.fileId to it.mode })
        assertEquals(StreamVideo(1920, 1080, null), plans[0].video)
        assertEquals("AAC", plans[1].audioCodec)
        assertEquals(6, plans[1].audioChannels)

        server.on("POST /api/auth/media-token", json(golden("media-token")))
        val token = gateway.mediaToken()
        assertEquals("golden-token", token.token)
        assertEquals("access_token", token.param)
    }

    @Test
    fun `preferences, request options and sessions`() = runBlocking {
        services.preferences.refresh()
        val prefs = services.preferences.playback.value
        assertEquals(SkipMode.Ask, prefs.skipMode(SegmentKind.Intro))
        assertEquals(SkipMode.None, prefs.skipMode(SegmentKind.Commercial))
        assertTrue(prefs.autoplayNext)

        server.on("GET /api/requests/options", json(golden("request-options")))
        val options = services.requests.options()
        assertFalse(options.canRequest)
        assertTrue(options.problems)
        assertEquals(RequestBackend.None, options.backend)
        assertFalse(options.delete)

        server.on("GET /api/auth/sessions", json(golden("sessions")))
        val sessions = services.auth.sessions()
        assertEquals(listOf(DeviceSession.Kind.Browser, DeviceSession.Kind.Device), sessions.map { it.kind })
        val phone = sessions.single { it.current }
        assertEquals("Golden phone", phone.name)
        assertEquals("Pixel 8", phone.model)
        assertEquals("127.0.0.1", phone.lastIp)
        assertTrue(phone.createdAtMs > 0)
    }

    @Test
    fun `pairing shows the server's code and link`() = runBlocking {
        server.on("POST /api/auth/pair", json(golden("pairing")))
        services.auth.pair().test {
            assertEquals(PairingState.Starting, awaitItem())
            val code = (awaitItem() as PairingState.Waiting).code
            assertEquals("KX7P4M", code.code)
            assertEquals("${server.baseUrl}/link?code=KX7P4M", code.linkUrl)
            assertEquals(2_000L, code.pollIntervalMs)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the oldest server's replies still make the screens`() = runBlocking {
        server.on("GET /api/home", json(oldest(golden("home"), ApiHomeData.serializer())))
        val home = services.library.home()
        assertEquals(2, home.continueWatching.size)
        assertEquals(LibraryTotals(4, 1, 6, 10, 227_327_054), home.totals)

        server.on("GET /api/libraries/2/items", json(oldest(golden("items-tv"), ItemList.serializer())))
        val page = services.library.items(ItemQuery(libraryId = 2))
        assertEquals("Night Shift Diner", page.items.single().title)
        assertNull("No letters from a server without them", page.letters)

        server.on("GET /api/shows/1", json(oldest(golden("show"), ApiShowDetail.serializer())))
        val show = services.library.show(1)
        assertEquals(2, show.seasons.size)
        assertNull(show.nextUp)

        server.on("GET /api/shows/1/seasons/1", json(oldest(golden("season-1"), ApiSeasonDetail.serializer())))
        val season = services.library.season(1, 1)
        assertEquals(3, season.episodes.size)
        assertEquals(1, season.episodes[1].versions.size)

        server.on("GET /api/movies/1", json(oldest(golden("movie"), ApiMovieDetail.serializer())))
        assertEquals("Harbor Lights", services.library.movie(1).title)

        server.on("POST /api/files/6/playback", json(oldest(golden("decision-hls"), Decision.serializer())))
        val decision = services.playbackGateway.decide(DecisionRequest(6, profile, services.client, quality = StreamQuality.Auto))
        assertEquals(StreamProtocol.Hls, decision.protocol)
        assertTrue("No playlist kind: an index.m3u8 is a live one", !decision.vod)
        assertNull(decision.next)

        server.on("GET /api/server", json(oldest(golden("server"), ServerInfo.serializer())))
        assertEquals(7, services.servers.probe(server.baseUrl).apiLevel)
    }

    @Test
    fun `every captured reply is in the table`() {
        val folder = File(GoldenRepliesTest::class.java.getResource("/golden/server.json")!!.toURI()).parentFile!!
        val captured = folder.listFiles { f -> f.extension == "json" }!!.map { it.nameWithoutExtension }
        assertEquals(captured.sorted(), MODELS.keys.sorted())
    }

    private fun <T> decode(what: String, serializer: KSerializer<T>, text: String): T = try {
        Serializer.kotlinxSerializationJson.decodeFromString(serializer, text)
    } catch (e: Exception) {
        throw AssertionError("$what doesn't decode: ${e.message}", e)
    }

    private companion object {
        /** Each golden reply and the generated model the client decodes it with. */
        val MODELS: Map<String, KSerializer<*>> = mapOf(
            "server" to ServerInfo.serializer(),
            "login" to SignIn.serializer(),
            "me" to ApiUser.serializer(),
            "auth-status" to AuthStatus.serializer(),
            "sessions" to SessionList.serializer(),
            "media-token" to ApiMediaToken.serializer(),
            "pairing" to PairingStart.serializer(),
            "preferences" to Preferences.serializer(),
            "request-options" to ApiRequestOptions.serializer(),
            "libraries" to LibraryList.serializer(),
            "items-movies" to ItemList.serializer(),
            "items-tv" to ItemList.serializer(),
            "home" to ApiHomeData.serializer(),
            "show" to ApiShowDetail.serializer(),
            "season-1" to ApiSeasonDetail.serializer(),
            "season-2" to ApiSeasonDetail.serializer(),
            "movie" to ApiMovieDetail.serializer(),
            "search" to ApiSearchResults.serializer(),
            "file" to MediaFileDetail.serializer(),
            "plans" to PlanList.serializer(),
            "decision-episode" to Decision.serializer(),
            "decision-movie" to Decision.serializer(),
            "decision-hls" to Decision.serializer(),
        )

        fun golden(name: String): String =
            GoldenRepliesTest::class.java.getResource("/golden/$name.json")?.readText() ?: error("no golden reply $name")

        /** [text] as the oldest server could send it: without any field [serializer]'s model marks optional. */
        fun oldest(text: String, serializer: KSerializer<*>): String =
            withoutOptionalFields(Json.parseToJsonElement(text), serializer.descriptor).toString()
    }
}

/**
 * [element] without the keys [descriptor] marks optional (a default in the
 * generated model, so not required in the API document), at every depth.
 */
internal fun withoutOptionalFields(element: JsonElement, descriptor: SerialDescriptor): JsonElement = when {
    element is JsonObject && descriptor.kind == StructureKind.CLASS -> JsonObject(
        element.mapNotNull { (key, value) ->
            val index = descriptor.getElementIndex(key)
            if (index == CompositeDecoder.UNKNOWN_NAME || descriptor.isElementOptional(index)) {
                null
            } else {
                key to withoutOptionalFields(value, descriptor.getElementDescriptor(index))
            }
        }.toMap(),
    )
    element is JsonObject && descriptor.kind == StructureKind.MAP ->
        JsonObject(element.mapValues { (_, value) -> withoutOptionalFields(value, descriptor.getElementDescriptor(1)) })
    element is JsonArray && descriptor.kind == StructureKind.LIST ->
        JsonArray(element.map { withoutOptionalFields(it, descriptor.getElementDescriptor(0)) })
    else -> element
}
