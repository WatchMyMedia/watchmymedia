package media.watchmy.client.core.data

import app.cash.turbine.test
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import media.watchmy.client.core.data.TestServer.Companion.json
import media.watchmy.client.core.data.TestServer.Companion.noContent
import media.watchmy.client.model.DELETE_API_LEVEL
import media.watchmy.client.model.DownloadState
import media.watchmy.client.model.MediaType
import media.watchmy.client.model.ProblemKind
import media.watchmy.client.model.REQUESTS_API_LEVEL
import media.watchmy.client.model.RequestBackend
import media.watchmy.client.model.RequestOptions
import media.watchmy.client.model.RequestStatus
import media.watchmy.client.model.RequestableState
import media.watchmy.client.model.message
import media.watchmy.client.model.SeasonAvailability
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestRepositoryTest {
    private val h = Harness()

    @After
    fun tearDown() = h.close()

    /** Signs in to a server at [level]; the sign-in's refresh reads the level from GET /api/server. */
    private fun signInAt(level: Int, admin: Boolean = false) = runBlocking {
        if (admin) h.server.always("GET /api/auth/me", json("""{"id":2,"name":"viewer","isAdmin":true}"""))
        h.server.always("GET /api/server", json(SERVER.format(level)))
        h.services.servers.select(h.testServer.copy(apiLevel = level))
        h.server.on("POST /api/auth/login", json(TestServer.SIGN_IN.replace("\"isAdmin\":false", "\"isAdmin\":$admin")))
        h.services.auth.signIn("viewer", "fixture-viewer-1")
        withTimeout(5_000) { h.services.servers.current.first { it?.server?.apiLevel == level } }
    }

    @Test
    fun `below level 5 nothing is offered and nothing is asked`() = runBlocking {
        signInAt(REQUESTS_API_LEVEL - 1)
        assertEquals(RequestOptions.None, h.services.requests.options())
        assertEquals(emptyList<Any>(), h.services.requests.search("harbor"))
        assertEquals(emptyList<Any>(), h.services.requests.mine())
        assertTrue(h.server.requests.none { it.requestUrl!!.encodedPath.startsWith("/api/requests") })
    }

    @Test
    fun `options say what this person may do`() = runBlocking {
        signInAt(REQUESTS_API_LEVEL)
        h.server.on("GET /api/requests/options", json("""{"movies":true,"shows":false,"needsApproval":true,"problems":true,"manage":false,"backend":"arr","inbox":0}"""))
        val o = h.services.requests.options()
        assertTrue(o.movies)
        assertFalse(o.shows)
        assertTrue(o.needsApproval)
        assertEquals(RequestBackend.Arr, o.backend)
        assertTrue(o.canRequest(MediaType.Movie))
        assertFalse(o.missingEpisodes)
        // A server with integrations off answers 503: nothing to offer, no error.
        h.server.on("GET /api/requests/options", TestServer.problem(503, "integrations are off"))
        assertEquals(RequestOptions.None, h.services.requests.options())
    }

    @Test
    fun `Seerr and unknown backends keep server capabilities without requiring enum updates`() = runBlocking {
        signInAt(REQUESTS_API_LEVEL)
        for ((backend, expected) in listOf("seerr" to RequestBackend.Seerr, "future" to RequestBackend.Other)) {
            h.server.on("GET /api/requests/options", json("""{"movies":true,"shows":false,"needsApproval":true,"problems":true,"manage":false,"backend":"$backend","inbox":0}"""))
            val options = h.services.requests.options()
            assertEquals(expected, options.backend)
            assertTrue(options.movies)
            assertFalse(options.shows)
            assertFalse(options.canOpenInbox)
            assertEquals(backend == "seerr", options.viaSeerr)
        }
        h.server.on("GET /api/requests/options", json("""{"movies":false,"shows":false,"needsApproval":false,"problems":true,"manage":false,"backend":"seerr","inbox":0}"""))
        assertFalse(h.services.requests.options().canRequest)
    }

    @Test
    fun `Seerr-only admin Inbox respects authenticated identity and network permission`() = runBlocking {
        signInAt(REQUESTS_API_LEVEL, admin = true)
        h.server.always("GET /api/requests/options", json("""{"movies":true,"shows":true,"needsApproval":false,"problems":true,"manage":false,"backend":"seerr","inbox":2}"""))
        for ((allowed, admin) in listOf(true to true, false to true, true to false)) {
            h.server.on("GET /api/auth/status", json("""{"setupRequired":false,"adminAllowed":$allowed,"user":{"id":2,"name":"viewer","isAdmin":$admin}}"""))
            val options = h.services.requests.options()
            assertEquals(allowed && admin, options.canOpenInbox)
            assertFalse(options.manage)
            assertTrue(options.canRequest)
        }
        h.server.on("GET /api/auth/status", TestServer.problem(503, "unavailable"))
        assertFalse(h.services.requests.options().canOpenInbox)
    }

    @Test
    fun `Seerr Inbox metadata preserves local requests and optional pending count`() = runBlocking {
        signInAt(REQUESTS_API_LEVEL)
        h.server.on("GET /api/requests/inbox", json("""{"requests":[$REQUEST],"problems":[],"seerr":{"requestsUrl":"https://seerr.example/requests","pending":3}}"""))
        val inbox = h.services.requests.inbox()
        assertEquals(12L, inbox.requests.single().id)
        assertEquals(3, inbox.seerr?.pending)
        assertEquals("https://seerr.example/requests", inbox.seerr?.safeRequestsUrl)
        h.server.on("GET /api/requests/inbox", json("""{"requests":[],"problems":[],"seerr":{"requestsUrl":"https://seerr.example/requests"}}"""))
        assertNull(h.services.requests.inbox().seerr?.pending)
        h.server.on("GET /api/requests/inbox", json("""{"requests":[],"problems":[]}"""))
        assertNull(h.services.requests.inbox().seerr)
    }

    @Test
    fun `report result distinguishes Seerr issues from local fallback`() = runBlocking {
        signInAt(REQUESTS_API_LEVEL)
        val body = """{"id":0,"reportedBy":"viewer","itemType":"movie","itemId":500,"kind":"audio","note":"","details":"","title":"Harbor of Glass","createdAt":"2026-10-31T12:00:00Z","seerrIssueId":24}"""
        h.server.on("POST /api/problems", json(body, 201), json(body.replace(",\"seerrIssueId\":24", ""), 201))
        assertEquals(24L, h.services.requests.report(false, 500, null, ProblemKind.Audio, "").seerrIssueId)
        assertNull(h.services.requests.report(false, 500, null, ProblemKind.Audio, "").seerrIssueId)
    }

    @Test
    fun `search and lookup map TMDB's items and a show's seasons`() = runBlocking {
        signInAt(REQUESTS_API_LEVEL)
        h.server.on(
            "GET /api/requests/search",
            json(
                """{"items":[
                {"mediaType":"tv","tmdbId":70101,"title":"The Harborline","year":2021,"overview":"A ferry line.","state":"requestable"},
                {"mediaType":"movie","tmdbId":70203,"title":"Harbor of Glass","year":2023,"overview":"","posterPath":"/p.jpg","state":"requested",
                 "request":{"id":4,"status":"pending","requestedBy":"Sam","mine":true,"seasons":[],"createdAt":"2026-10-31T12:00:00Z"}},
                {"mediaType":"music","tmdbId":1,"title":"Not ours","overview":"","state":"requestable"}
                ]}""",
            ),
        )
        val found = h.services.requests.search("  harbor ")
        assertEquals("harbor", h.server.last("GET /api/requests/search").requestUrl!!.queryParameter("q"))
        assertEquals(listOf("The Harborline", "Harbor of Glass"), found.map { it.title })
        assertEquals(RequestableState.Requested, found[1].state)
        assertEquals(RequestStatus.Pending, found[1].request?.status)
        assertTrue(found[1].request!!.mine)

        h.server.on(
            "GET /api/requests/lookup",
            json(
                """{"mediaType":"tv","tmdbId":70101,"title":"The Harborline","overview":"A ferry line.","state":"requestable","seasons":[
                {"number":1,"name":"Season 1","episodeCount":8,"state":"available"},
                {"number":2,"name":"Season 2","episodeCount":8,"state":"requested"},
                {"number":3,"name":"Season 3","episodeCount":10,"airDate":"2024-04-15","state":"partial"}
                ]}""",
            ),
        )
        val show = h.services.requests.lookup(MediaType.Tv, 70101)
        val url = h.server.last("GET /api/requests/lookup").requestUrl!!
        assertEquals("tv", url.queryParameter("mediaType"))
        assertEquals("70101", url.queryParameter("tmdbId"))
        assertEquals(listOf(SeasonAvailability.Available, SeasonAvailability.Requested, SeasonAvailability.Partial), show.seasons.map { it.availability })
    }

    @Test
    fun `asking, cancelling, deciding and reporting send what the server takes`() = runBlocking {
        signInAt(REQUESTS_API_LEVEL)
        h.server.on("POST /api/requests", json("""{"created":true,"request":$REQUEST}""", 201))
        val created = h.services.requests.request(MediaType.Tv, 70101, listOf(1, 3))
        assertTrue(created.created)
        assertEquals(listOf(2), created.request.seasons)
        val asked = h.body(h.server.last("POST /api/requests"))
        assertEquals("tv", asked["mediaType"]!!.jsonPrimitive.content)
        assertEquals(listOf(1L, 3L), asked["seasons"]!!.jsonArray.map { it.jsonPrimitive.long })

        h.server.on("DELETE /api/requests/12", noContent())
        h.services.requests.cancel(12)
        assertEquals(1, h.server.requests("DELETE /api/requests/12").size)

        h.server.on("POST /api/requests/12/decision", json(REQUEST), json(REQUEST))
        h.services.requests.decide(12, approve = false, note = "  Already on the other server ")
        assertEquals("Already on the other server", h.body(h.server.last("POST /api/requests/12/decision"))["note"]!!.jsonPrimitive.content)
        h.services.requests.decide(12, approve = true, note = "ignored")
        val approved = h.body(h.server.last("POST /api/requests/12/decision"))
        assertTrue(approved["approve"]!!.jsonPrimitive.boolean)
        // Huma takes null on an optional field as absent.
        assertTrue(approved["note"] == null || approved["note"] is JsonNull)

        h.server.on(
            "POST /api/problems",
            json("""{"id":3,"reportedBy":"viewer","itemType":"episode","itemId":401,"kind":"audio","note":"No sound","details":"x.mkv · HEVC","title":"Harbor Lights · S03E07","createdAt":"2026-10-31T12:00:00Z"}""", 201),
        )
        val p = h.services.requests.report(episode = true, itemId = 401, fileId = 77, kind = ProblemKind.Audio, note = " No sound ")
        assertEquals(ProblemKind.Audio, p.kind)
        val reported = h.body(h.server.last("POST /api/problems"))
        assertEquals("episode", reported["itemType"]!!.jsonPrimitive.content)
        assertEquals(77L, reported["fileId"]!!.jsonPrimitive.long)
        assertEquals("No sound", reported["note"]!!.jsonPrimitive.content)

        h.server.on("POST /api/shows/9/search", MockResponse().setResponseCode(202))
        h.services.requests.searchMissing(9, season = 2, episodes = listOf(4))
        assertEquals(listOf(4L), h.body(h.server.last("POST /api/shows/9/search"))["episodes"]!!.jsonArray.map { it.jsonPrimitive.long })
        h.server.on("POST /api/shows/9/monitor", noContent())
        h.services.requests.monitor(9, season = 2)
        val monitor = h.body(h.server.last("POST /api/shows/9/monitor"))
        assertTrue(monitor["monitored"]!!.jsonPrimitive.boolean)
        assertTrue(monitor["episodes"] == null || monitor["episodes"] is JsonNull)
    }

    @Test
    fun `Your requests and the Inbox carry state, downloads and notes`() = runBlocking {
        signInAt(REQUESTS_API_LEVEL)
        h.server.on("GET /api/requests/mine", json("""{"items":[$REQUEST]}"""))
        val mine = h.services.requests.mine().single()
        assertEquals(RequestStatus.Approved, mine.status)
        assertEquals(DownloadState.Downloading, mine.download?.state)
        assertEquals(0.45f, mine.download!!.progress, 0.001f)
        h.server.on("GET /api/requests/inbox", json("""{"requests":[$REQUEST],"problems":[]}"""))
        assertEquals(1, h.services.requests.inbox().requests.size)
    }

    @Test
    fun `Show missing is remembered per show on this server`() = runBlocking {
        signInAt(REQUESTS_API_LEVEL)
        assertFalse(h.services.requests.showMissing(9))
        h.services.requests.setShowMissing(9, true)
        assertTrue(h.services.requests.showMissing(9))
        withTimeout(5_000) { h.dataStore.data.first { 9L in LocalStore.decode(it).current?.showMissing.orEmpty() } }
        assertTrue(h.reopen().requests.showMissing(9))
        h.services.requests.setShowMissing(9, false)
        assertFalse(h.services.requests.showMissing(9))
    }

    @Test
    fun `a season's episodes say why they are missing`() = runBlocking {
        signInAt(REQUESTS_API_LEVEL)
        h.server.on("GET /api/shows/9/seasons/2", json(SEASON))
        val season = h.services.library.season(9, 2)
        assertEquals(false, season.season.monitored)
        assertEquals(RequestStatus.Pending, season.season.request?.status)
        val (have, downloading, unmonitored) = season.episodes
        assertNull(have.download)
        assertEquals(DownloadState.Downloading, downloading.download?.state)
        assertEquals(epochMs("2026-10-31T12:12:00Z"), downloading.download?.finishesAtMs)
        assertEquals(false, unmonitored.monitored)
        assertEquals("Sam", unmonitored.request?.requestedBy)
    }

    @Test
    fun `request changes come from requests and problems events only`() = runBlocking {
        signInAt(REQUESTS_API_LEVEL)
        h.server.on(
            "GET /api/events",
            MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "event: ping\ndata: {}\n\n" +
                    "event: watch.changed\ndata: {}\n\n" +
                    "event: requests.changed\ndata: {\"requestId\":4,\"status\":\"approved\"}\n\n" +
                    "event: problems.changed\ndata: {\"problemId\":3}\n\n",
            ),
        )
        h.services.requests.changes.test(timeout = 5.seconds) {
            awaitItem() // requests.changed
            awaitItem() // problems.changed
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Delete episode is offered from level 6, when the server says so`() = runBlocking {
        val reply = """{"movies":true,"shows":true,"needsApproval":false,"problems":true,"manage":true,"backend":"arr","inbox":0,"delete":true}"""
        signInAt(REQUESTS_API_LEVEL, admin = true)
        h.server.on("GET /api/requests/options", json(reply))
        assertFalse("a level 5 server sending the field doesn't count", h.services.requests.options().delete)
        signInAt(DELETE_API_LEVEL, admin = true)
        h.server.on("GET /api/requests/options", json(reply))
        assertTrue(h.services.requests.options().delete)
        h.server.on("GET /api/requests/options", json(reply.replace(",\"delete\":true", "")))
        assertFalse("a reply without the field is no", h.services.requests.options().delete)
    }

    @Test
    fun `deleting an episode asks what goes, then sends the apps that answered`() = runBlocking {
        signInAt(REQUESTS_API_LEVEL, admin = true)
        h.server.on("GET /api/delete/preview", json(DELETE_PREVIEW))
        val preview = h.services.requests.previewDeleteEpisode(42)
        val asked = h.server.requests("GET /api/delete/preview").single().requestUrl!!
        assertEquals("episode", asked.queryParameter("type"))
        assertEquals("42", asked.queryParameter("id"))
        assertTrue(preview.managed)
        assertEquals(listOf("Sonarr", "Sonarr 4K"), preview.apps.map { it.name })
        assertEquals("/data/.recycle", preview.apps[0].recycleBin)
        assertNull("an empty bin is none", preview.apps[1].recycleBin)
        assertEquals(listOf(8), preview.apps[0].otherEpisodes)
        assertEquals("timeout", preview.apps[1].error)
        assertEquals(listOf(1L), preview.reachable.map { it.connectionId })

        h.server.on("POST /api/episodes/42/delete", json("""{"apps":[{"connection":"Sonarr","connectionId":1,"files":1,"size":4500000000,"error":"disk full"}],"requests":0}"""))
        val done = h.services.requests.deleteEpisode(42, listOf(1))
        assertEquals(listOf("Sonarr couldn’t delete: disk full"), done.failures)
        val sent = h.body(h.server.last("POST /api/episodes/42/delete"))
        assertEquals(listOf(1L), sent["connections"]!!.jsonArray.map { it.jsonPrimitive.long })
    }

    @Test
    fun `a file no app manages has no delete`() = runBlocking {
        signInAt(REQUESTS_API_LEVEL, admin = true)
        h.server.on("GET /api/delete/preview", json("""{"apps":[],"choices":{"airing":false,"exclude":false,"keep":false,"keepMonitored":false},"files":0,"kept":1,"managed":false,"size":0}"""))
        assertFalse(h.services.requests.previewDeleteEpisode(42).canDelete)
    }

    @Test
    fun `Replace file says who searches again`() = runBlocking {
        signInAt(REQUESTS_API_LEVEL, admin = true)
        h.server.on("POST /api/files/7/replace", json("""{"blocklisted":false,"connection":"Sonarr","note":"Imported by hand: no release to block."}"""))
        val r = h.services.requests.replaceFile(7)
        assertEquals("Sonarr is searching for another copy.", r.message())
        assertEquals("Imported by hand: no release to block.", r.note)
    }

    private companion object {
        /** An episode two Sonarrs hold; the 4K one didn't answer. */
        const val DELETE_PREVIEW = """{"apps":[{"connection":"Sonarr","connectionId":1,"extra":0,"files":1,"folder":"/tv/Harbor Lights/Season 03","kind":"sonarr","localFolder":"/media/tv/Harbor Lights/Season 03","size":4500000000,"otherEpisodes":[8],"recycleBin":"/data/.recycle","recycleDays":7},{"connection":"Sonarr 4K","connectionId":2,"extra":0,"files":0,"folder":"","kind":"sonarr","localFolder":"","size":0,"recycleBin":"","error":"timeout"}],"choices":{"airing":true,"exclude":false,"keep":false,"keepMonitored":true},"files":1,"kept":0,"managed":true,"size":4500000000}"""
        const val SERVER = """{"id":"srv-test","name":"Test server","version":"1.3.0","apiLevel":%d,"setupRequired":false,"turnstile":false}"""
        const val REQUEST = """{"id":12,"mediaType":"tv","tmdbId":70104,"title":"Harborside","status":"approved","requestedBy":"viewer","mine":true,
            "seasons":[2],"createdAt":"2026-10-28T12:00:00Z","download":{"state":"downloading","progress":0.45,"finishesAt":"2026-10-31T12:12:00Z"}}"""
        const val REQ_STATE = """{"id":5,"status":"pending","requestedBy":"Sam","mine":false,"seasons":[2],"createdAt":"2026-10-31T10:00:00Z"}"""
        const val EP = """"overview":"","files":[],"aired":true,"isNew":false"""
        val SEASON = """{"id":92,"showId":9,"number":2,"name":"Season 2","overview":"","episodeCount":3,"availableCount":1,"watchedCount":0,
            "monitored":false,"request":$REQ_STATE,"show":{"id":9,"libraryId":2,"tmdbId":1,"title":"Harbor Lights","overview":"","genres":[],"episodeCount":1,"credits":[]},
            "episodes":[
            {"id":1,"seasonId":92,"number":1,"title":"One",$EP,"hasFile":true},
            {"id":2,"seasonId":92,"number":2,"title":"Two",$EP,"hasFile":false,"monitored":true,"download":{"state":"downloading","progress":0.45,"finishesAt":"2026-10-31T12:12:00Z"}},
            {"id":3,"seasonId":92,"number":3,"title":"Three",$EP,"hasFile":false,"monitored":false,"request":$REQ_STATE}
            ]}"""
    }
}
