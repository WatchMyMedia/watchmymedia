package media.watchmy.client.core.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import media.watchmy.client.core.data.TestServer.Companion.json
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.ItemKind
import media.watchmy.client.model.RELATED_API_LEVEL
import media.watchmy.client.model.RelatedRowIds
import media.watchmy.client.model.RowStyle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rows at the end of movie and show pages (PLAN-RELATED.md §6) from a server at level 30, and none from one below it. */
class RelatedRepositoryTest {
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
    fun `below level 30 nothing is asked and the pages get no rows`() = runBlocking {
        signInAt(RELATED_API_LEVEL - 1)
        assertEquals(emptyList<Any>(), h.services.library.movieRelated(21))
        assertEquals(emptyList<Any>(), h.services.library.showRelated(11))
        assertTrue(h.server.requests.none { it.requestUrl!!.encodedPath.endsWith("/related") })
    }

    @Test
    fun `a movie's rows come in the server's order, marked as Home's are, and rows of styles this app doesn't know are skipped`() = runBlocking {
        signInAt(RELATED_API_LEVEL)
        h.server.on("GET /api/movies/21/related", json(MOVIE_ROWS))
        val rows = h.services.library.movieRelated(21)
        assertEquals(listOf(RelatedRowIds.COLLECTION, RelatedRowIds.SIMILAR), rows.map { it.id })
        assertEquals(listOf(RowStyle.Poster, RowStyle.Poster), rows.map { it.style })
        assertEquals("Paper Moons Collection", rows[0].title)
        val item = rows[0].items.single()
        assertEquals(ItemKind.Movie, item.kind)
        assertEquals("Paper Moons II", item.title)
        assertTrue(item.watched)
        assertEquals("More like this", rows[1].title)
        assertEquals(0.4f, rows[1].items.single().progress!!, 0.001f)
        assertTrue(rows[1].items.single().isNew)
    }

    @Test
    fun `a show's row, and none when nothing qualifies`() = runBlocking {
        signInAt(RELATED_API_LEVEL)
        h.server.on("GET /api/shows/11/related", json(SHOW_ROWS))
        val row = h.services.library.showRelated(11).single()
        assertEquals(RelatedRowIds.SIMILAR, row.id)
        assertEquals(ItemKind.Show, row.items.single().kind)

        h.server.on("GET /api/shows/12/related", json("""{"rows": []}"""))
        assertEquals(emptyList<Any>(), h.services.library.showRelated(12))
    }

    @Test
    fun `a refusal is the server's message`() = runBlocking {
        signInAt(RELATED_API_LEVEL)
        h.server.on("GET /api/movies/99/related", TestServer.problem(404, "Movie not found"))
        val e = assertThrows(ApiException::class.java) { runBlocking { h.services.library.movieRelated(99) } }
        assertEquals(404, e.status)
    }

    private companion object {
        const val SERVER = """{"id":"srv-test","name":"Test server","version":"1.6.0","apiLevel":%d,"setupRequired":false,"turnstile":false}"""
        const val MOVIE = """{"kind": "movie", "id": 21, "libraryId": 1, "title": "Paper Moons", "year": 2021, "overview": "Two cartographers.", "genres": ["Romance"], "posterPath": "/pm-poster.jpg", "quality": "1080p", "hdr": false, "addedAt": "2026-10-28T10:00:00Z", "isNew": false, "totalSize": 2000, "watched": false, "runtimeMin": 104, "releaseDate": "2021-03-04"}"""
        val SEQUEL = MOVIE.replace("\"id\": 21", "\"id\": 22").replace("Paper Moons", "Paper Moons II").replace("\"watched\": false", "\"watched\": true")
        val SIMILAR = MOVIE.replace("\"id\": 21", "\"id\": 23").replace("Paper Moons", "Low Water").replace("\"isNew\": false", "\"isNew\": true")
            .replace("\"watched\": false", "\"watched\": false, \"progress\": 0.4")
        val MOVIE_ROWS = """{"rows": [
            {"id": "collection", "style": "poster", "title": "Paper Moons Collection", "items": [$SEQUEL]},
            {"id": "shelf", "style": "carousel", "title": "A row from a newer server", "items": [$SEQUEL]},
            {"id": "similar", "style": "poster", "title": "More like this", "items": [$SIMILAR]}
        ]}"""
        const val SHOW = """{"kind": "show", "id": 12, "libraryId": 2, "title": "Harbor Lights", "year": 2022, "overview": "", "genres": ["Drama"], "quality": "1080p", "hdr": false,
            "addedAt": "2026-10-28T10:00:00Z", "isNew": false, "totalSize": 2000, "watched": false, "episodeCount": 6}"""
        const val SHOW_ROWS = """{"rows": [{"id": "similar", "style": "poster", "title": "More like this", "items": [$SHOW]}]}"""
    }
}
