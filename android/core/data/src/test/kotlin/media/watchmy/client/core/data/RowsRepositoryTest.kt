package media.watchmy.client.core.data

import app.cash.turbine.test
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import media.watchmy.client.core.data.TestServer.Companion.json
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.ItemFilter
import media.watchmy.client.model.ItemSort
import media.watchmy.client.model.ListOwner
import media.watchmy.client.model.MediaType
import media.watchmy.client.model.ROWS_API_LEVEL
import media.watchmy.client.model.RequestableState
import media.watchmy.client.model.RowLayoutEntry
import media.watchmy.client.model.RowLink
import media.watchmy.client.model.RowPlacement
import media.watchmy.client.model.RowStyle
import media.watchmy.client.model.RowsChange
import media.watchmy.client.model.fromLine
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Arranged rows, the layout and lists (PLAN-ROWS.md §6) against a server that speaks the contract, and one too old for it. */
class RowsRepositoryTest {
    private val h = Harness()

    @After
    fun tearDown() = h.close()

    private fun signInAt(level: Int) = runBlocking {
        h.server.always("GET /api/server", json(SERVER.format(level)))
        h.services.servers.select(h.testServer.copy(apiLevel = level))
        h.server.on("POST /api/auth/login", json(TestServer.SIGN_IN))
        h.services.auth.signIn("viewer", "fixture-viewer-1")
        withTimeout(5_000) { h.services.servers.current.first { it?.server?.apiLevel == level } }
    }

    @Test
    fun `below level 22 Home keeps its fixed rows and nothing new is asked for`() = runBlocking {
        signInAt(ROWS_API_LEVEL - 1)
        h.server.on("GET /api/home", json(TestServer.fixture("home.json")))
        val home = h.services.library.arrangedHome()
        assertNull("the fixed rows", home.rows)
        assertEquals(2, home.continueWatching.size)
        assertNull(h.server.last("GET /api/home").requestUrl!!.queryParameter("arranged"))

        assertEquals(emptyList<Any>(), h.services.library.libraryRows(1))
        assertFalse(h.services.rows.supported)
        assertEquals(emptyList<Any>(), h.services.rows.lists())
        assertThrows(ApiException::class.java) { runBlocking { h.services.rows.layout("home") } }
        assertTrue(h.server.requests.none { it.requestUrl!!.encodedPath.let { p -> p.startsWith("/api/me/rows") || p.startsWith("/api/lists") || p.endsWith("/rows") } })
    }

    @Test
    fun `from level 22 Home comes in the person's order and rows of styles this app doesn't know are skipped`() = runBlocking {
        signInAt(ROWS_API_LEVEL)
        h.server.on("GET /api/home", json(ARRANGED_HOME))
        val home = h.services.library.arrangedHome()
        assertEquals("true", h.server.last("GET /api/home").requestUrl!!.queryParameter("arranged"))

        val rows = home.rows!!
        assertEquals(listOf("live", "list:7", "continue", "recentlyAddedMovies"), rows.map { it.id })
        assertEquals(listOf(RowStyle.Live, RowStyle.Poster, RowStyle.Continue, RowStyle.Wide), rows.map { it.style })
        assertTrue("the fixed arrays come back empty", home.continueWatching.isEmpty() && home.recentlyAddedMovies.isEmpty())

        val list = rows[1]
        assertEquals("18 of 50 · From TMDB", list.subtitle)
        assertEquals(RowLink.ToList(7), list.seeAll)
        assertEquals(7L, list.listId)
        assertEquals("TMDB", list.source?.name)
        assertEquals(listOf("Paper Moons"), list.items.map { it.title })
        assertEquals(403L, rows[2].continueItems.single().episodeId)
        assertEquals(RowLink.Library(1, ItemSort.Released, ItemFilter.Unwatched, decade = 2020), rows[3].seeAll)
    }

    @Test
    fun `a See all of a kind this app doesn't know is dropped, and the row stays`() = runBlocking {
        signInAt(ROWS_API_LEVEL)
        h.server.on("GET /api/libraries/1/rows", json(LIBRARY_ROWS))
        val rows = h.services.library.libraryRows(1)
        assertEquals("true", h.server.last("GET /api/libraries/1/rows").requestUrl!!.queryParameter("arranged"))
        assertEquals(listOf("recentlyAdded"), rows.map { it.id })
        assertNull(rows.single().seeAll)
    }

    @Test
    fun `every row hidden still reads as an arranged Home`() = runBlocking {
        signInAt(ROWS_API_LEVEL)
        h.server.on("GET /api/home", json(ARRANGED_EMPTY))
        assertEquals(emptyList<Any>(), h.services.library.arrangedHome().rows)
    }

    @Test
    fun `the layout is read and saved in order, hidden rows marked`() = runBlocking {
        signInAt(ROWS_API_LEVEL)
        assertTrue(h.services.rows.supported)
        h.server.on("GET /api/me/rows/home", json(LAYOUT))
        val layout = h.services.rows.layout("home")
        assertEquals(listOf("continue", "list:7", "mystery"), layout.rows.map { it.id })
        assertEquals(RowLayoutEntry.Kind.List, layout.rows[1].kind)
        assertEquals(ListOwner.Everyone, layout.rows[1].owner)
        assertEquals(RowLayoutEntry.Kind.Other, layout.rows[2].kind)
        assertFalse("a style from a newer server", layout.rows[2].drawable)
        assertTrue(layout.customized)

        h.server.on("PUT /api/me/rows/home", json(LAYOUT))
        h.services.rows.saveLayout("home", listOf(RowPlacement("list:7", hidden = false), RowPlacement("continue", hidden = true)))
        val sent = h.body(h.server.last("PUT /api/me/rows/home"))["rows"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("list:7", "continue"), sent.map { it["id"]!!.jsonPrimitive.content })
        assertNull("shown rows send no hidden field", sent[0]["hidden"])
        assertTrue(sent[1]["hidden"]!!.jsonPrimitive.boolean)

        h.server.on("DELETE /api/me/rows/library-2", json(LAYOUT.replace("\"customized\": true", "\"customized\": false")))
        assertFalse(h.services.rows.resetLayout("library-2").customized)
    }

    @Test
    fun `a list's entries carry the library's items, what Request would do, and imports' unmatched titles`() = runBlocking {
        signInAt(ROWS_API_LEVEL)
        h.server.on("GET /api/lists/7", json(LIST))
        h.server.on("GET /api/lists/7/entries", json(ENTRIES))
        val list = h.services.rows.list(7)
        assertEquals("Science fiction", list.name)
        assertEquals(50, list.entries)
        assertEquals(18, list.inLibrary)
        assertEquals("MDBList refused the key", list.error)
        assertEquals("From TMDB", list.fromLine())
        assertEquals("https://www.themoviedb.org/list/7", list.sourceUrl)

        val page = h.services.rows.entries(7)
        assertEquals("250", h.server.last("GET /api/lists/7/entries").requestUrl!!.queryParameter("limit"))
        val (have, missing, unknown, notFound) = page.items
        assertEquals("Paper Moons", have.item?.title)
        assertEquals(RequestableState.Requestable, missing.requestState)
        assertTrue(missing.requestable)
        assertEquals(MediaType.Tv, missing.mediaType)
        assertNull("a state this app doesn't know offers nothing", unknown.requestState)
        assertFalse(notFound.found)
        assertEquals("Not found on TMDB", notFound.missingLine)
    }

    @Test
    fun `rows changed and lists changed reach the screens, and both again after a reconnect`() = runBlocking {
        signInAt(ROWS_API_LEVEL)
        h.server.on(
            "GET /api/events",
            stream(
                "event: ping\ndata: {}\n\n" +
                    "event: rows.changed\ndata: {\"screen\":\"library-2\"}\n\n" +
                    "event: lists.changed\ndata: {\"listId\":7}\n\n",
            ),
            stream("event: ping\ndata: {}\n\n"),
        )
        h.services.rows.changes.test(timeout = 5.seconds) {
            assertEquals(RowsChange.Layout("library-2"), awaitItem())
            assertEquals(RowsChange.Lists(7), awaitItem())
            assertEquals(RowsChange.Layout(null), awaitItem())
            assertEquals(RowsChange.Lists(null), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun stream(body: String) = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body)

    private companion object {
        const val SERVER = """{"id":"srv-test","name":"Test server","version":"1.3.0","apiLevel":%d,"setupRequired":false,"turnstile":false}"""
        const val MOVIE = """{"kind": "movie", "id": 21, "libraryId": 1, "title": "Paper Moons", "year": 2021, "overview": "Two cartographers.", "genres": ["Romance"], "posterPath": "/pm-poster.jpg", "quality": "1080p", "hdr": false, "addedAt": "2026-10-28T10:00:00Z", "isNew": false, "totalSize": 2000, "watched": false, "runtimeMin": 104, "releaseDate": "2021-03-04"}"""
        const val CONTINUE = """{"kind": "episode", "title": "Harbor Lights", "subtitle": "S01E03 · Undertow", "fileId": 70, "showId": 11, "episodeId": 403, "seasonNumber": 1, "episodeNumber": 3, "positionMs": 600000, "durationMs": 2400000, "updatedAt": "2026-10-28T20:15:00Z"}"""
        const val TOTALS = """{"movies": 4, "shows": 1, "episodes": 6, "files": 10, "totalSize": 227327054}"""
        val ARRANGED_HOME = """{
            "continueWatching": [], "recentlyAddedShows": [], "recentlyAired": [], "recentlyAddedMovies": [], "recentlyReleased": [],
            "totals": $TOTALS,
            "rows": [
              {"id": "live", "style": "live", "title": "Live TV"},
              {"id": "list:7", "style": "poster", "title": "Science fiction", "subtitle": "18 of 50 · From TMDB", "items": [$MOVIE],
               "seeAll": {"kind": "list", "listId": 7}, "source": {"kind": "tmdb", "name": "TMDB", "url": "https://www.themoviedb.org/"}, "listId": 7},
              {"id": "shelf", "style": "carousel", "title": "A row from a newer server", "items": [$MOVIE]},
              {"id": "continue", "style": "continue", "title": "Continue watching", "continue": [$CONTINUE]},
              {"id": "recentlyAired", "style": "episode", "title": "Recently aired", "episodes": []},
              {"id": "recentlyAddedMovies", "style": "wide", "title": "Recently released · Movies", "items": [$MOVIE],
               "seeAll": {"kind": "library", "libraryId": 1, "sort": "released", "filter": "unwatched", "decade": 2020}}
            ]
        }"""
        val ARRANGED_EMPTY = """{"continueWatching": [], "recentlyAddedShows": [], "recentlyAired": [], "recentlyAddedMovies": [], "recentlyReleased": [], "totals": $TOTALS}"""
        val LIBRARY_ROWS = """{"recentlyAdded": [], "recentlyAired": [], "recentlyReleased": [],
            "rows": [{"id": "recentlyAdded", "style": "poster", "title": "Recently added", "items": [$MOVIE], "seeAll": {"kind": "collection", "listId": 3}}]}"""
        const val LAYOUT = """{"screen": "home", "customized": true, "rows": [
            {"id": "continue", "title": "Continue watching", "style": "continue", "kind": "builtin", "hidden": false, "shown": true},
            {"id": "list:7", "title": "Science fiction", "style": "poster", "kind": "list", "hidden": true, "shown": false, "listId": 7, "owner": "everyone"},
            {"id": "mystery", "title": "Something new", "style": "carousel", "kind": "shelf", "hidden": false, "shown": true}
        ]}"""
        const val LIST = """{"id": 7, "name": "Science fiction", "mediaType": "all", "source": {"kind": "tmdb", "chart": "discover"}, "owner": "everyone",
            "canEdit": false, "screen": "home", "limit": 50, "entries": 50, "inLibrary": 18, "fetching": false, "fetchedAt": "2026-11-06T10:00:00Z",
            "sourceName": "TMDB", "sourceUrl": "https://www.themoviedb.org/list/7",
            "error": "MDBList refused the key", "createdAt": "2026-11-01T10:00:00Z", "updatedAt": "2026-11-06T10:00:00Z"}"""
        val ENTRIES = """{"total": 4, "inLibrary": 1, "limit": 250, "offset": 0, "items": [
            {"rank": 1, "mediaType": "movie", "tmdbId": 600, "title": "Paper Moons", "year": 2021, "found": true, "item": $MOVIE},
            {"rank": 2, "mediaType": "tv", "tmdbId": 601, "title": "The Harborline", "year": 2021, "posterPath": "/hb.jpg", "found": true, "requestState": "requestable"},
            {"rank": 3, "mediaType": "movie", "tmdbId": 602, "title": "Harbor Saints", "found": true, "requestState": "pondering"},
            {"rank": 4, "mediaType": "movie", "title": "Lighthouse Keepers (1987)", "found": false}
        ]}"""
    }
}
