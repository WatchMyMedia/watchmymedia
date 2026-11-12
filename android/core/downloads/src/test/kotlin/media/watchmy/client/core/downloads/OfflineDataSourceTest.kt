package media.watchmy.client.core.downloads

import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import kotlinx.coroutines.runBlocking
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.MediaToken
import media.watchmy.client.model.OfflineOwner
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The download's fetches: its token on each request, a fresh one after a 401, 503s waited out, 409 and refusals. */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OfflineDataSourceTest {
    private val web = MockWebServer().apply { start() }
    private val id = "abcdefghijklmnop"
    private val owner = OfflineOwner("srv", 1, web.url("/").toString(), 10)
    private var issued = 0
    private var tokenError: Exception? = null
    private val tokens = OfflineTokens(
        fetch = { _, _ ->
            tokenError?.let { throw it }
            issued++
            MediaToken("o1.t$issued", System.currentTimeMillis() + 6 * 3_600_000L)
        },
        owner = { owner },
    )
    private val seen = mutableListOf<String>()
    private val slept = mutableListOf<Long>()
    private val events = object : FetchEvents {
        override fun preparing(id: String) { seen += "preparing" }
        override fun fetching(id: String) { seen += "fetching" }
        override fun refused(id: String, status: Int) { seen += "refused $status" }
        override fun restart(id: String) { seen += "restart" }
    }

    private fun source(): OfflineDataSource =
        OfflineDataSource(DefaultHttpDataSource.Factory().createDataSource(), tokens, events, sleep = { slept += it })

    /** A segment URL as a playlist lists it, with the token that fetched the playlist. */
    private fun segment() = web.url("/api/offline/$id/hls/v00003.m4s?access_token=o1.old").toString().toUri()

    @After
    fun tearDown() = web.shutdown()

    @Test
    fun `each request carries the download's current token in place of the playlist's`() {
        web.enqueue(MockResponse().setBody("bytes"))
        val s = source()
        s.open(DataSpec(segment()))
        s.close()
        val sent = web.takeRequest().requestUrl!!
        assertEquals(listOf("o1.t1"), sent.queryParameterValues("access_token"))
        assertEquals(listOf("fetching"), seen)
    }

    @Test
    fun `other URLs go as they are`() {
        web.enqueue(MockResponse().setBody("bytes"))
        source().apply { open(DataSpec(web.url("/api/files/9/stream").toString().toUri())); close() }
        assertNull(web.takeRequest().requestUrl!!.queryParameter("access_token"))
        assertEquals(0, issued)
    }

    @Test
    fun `a 401 gets a fresh token once`() {
        web.enqueue(MockResponse().setResponseCode(401))
        web.enqueue(MockResponse().setBody("bytes"))
        source().apply { open(DataSpec(segment())); close() }
        assertEquals("o1.t1", web.takeRequest().requestUrl!!.queryParameter("access_token"))
        assertEquals("o1.t2", web.takeRequest().requestUrl!!.queryParameter("access_token"))
    }

    @Test
    fun `a second 401 refuses the download`() {
        web.enqueue(MockResponse().setResponseCode(401))
        web.enqueue(MockResponse().setResponseCode(401))
        expectRefused(401)
        assertEquals(listOf("refused 401"), seen)
    }

    @Test
    fun `a 503 is waited out for as long as Retry-After says`() {
        web.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "10"))
        web.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "30"))
        web.enqueue(MockResponse().setBody("bytes"))
        source().apply { open(DataSpec(segment())); close() }
        assertEquals(listOf(10_000L, 30_000L), slept)
        assertEquals(listOf("preparing", "preparing", "fetching"), seen)
        assertEquals(3, web.requestCount)
    }

    @Test
    fun `a 503 that goes on past the limit fails as a network error, for Media3 to retry`() {
        var clock = 0L
        val s = OfflineDataSource(DefaultHttpDataSource.Factory().createDataSource(), tokens, events, sleep = { clock += it }, elapsed = { clock })
        repeat(7) { web.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "300")) }
        try {
            s.open(DataSpec(segment()))
            fail("expected a failure")
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            assertEquals(503, e.responseCode)
        }
        assertTrue("It waited up to 30 minutes", clock <= OfflineDataSource.MAX_WAIT_MS)
        assertEquals("fetching", seen.last())
    }

    @Test
    fun `409 starts the download over`() {
        web.enqueue(MockResponse().setResponseCode(409))
        expectRefused(409)
        assertEquals(listOf("restart"), seen)
    }

    @Test
    fun `403 and 410 end the download for the sync to explain`() {
        web.enqueue(MockResponse().setResponseCode(403))
        expectRefused(403)
        web.enqueue(MockResponse().setResponseCode(410))
        expectRefused(410)
        assertEquals(listOf("refused 403", "refused 410"), seen)
    }

    @Test
    fun `a token the server refuses ends the download, and no answer is a network error`() {
        tokenError = ApiException(403, "this download was revoked")
        expectRefused(403)
        tokenError = ApiException(null, "Couldn't reach the server.")
        try {
            source().open(DataSpec(segment()))
            fail("expected a network error")
        } catch (e: java.io.IOException) {
            assertTrue(e !is HttpDataSource.InvalidResponseCodeException)
        }
    }

    @Test
    fun `a token is kept until near its end`() = runBlocking {
        val first = tokens.token(id)
        assertEquals(first, tokens.token(id))
        assertEquals("o1.t2", tokens.token(id, fresh = true).token)
    }

    @Test
    fun `cache keys leave the token out`() {
        val a = DataSpec(segment())
        val b = DataSpec(web.url("/api/offline/$id/hls/v00003.m4s?access_token=o1.other").toString().toUri())
        assertEquals(OfflineDataSource.cacheKeys.buildCacheKey(a), OfflineDataSource.cacheKeys.buildCacheKey(b))
        assertEquals(web.url("/api/offline/$id/hls/v00003.m4s").toString(), OfflineDataSource.cacheKeys.buildCacheKey(a))
        assertEquals("A file's own key wins", "k", OfflineDataSource.cacheKeys.buildCacheKey(a.buildUpon().setKey("k").build()))
    }

    @Test
    fun `an offline route names its download`() {
        assertEquals(id, OfflineDataSource.downloadIdOf("http://h/api/offline/$id/stream".toUri()))
        assertEquals(id, OfflineDataSource.downloadIdOf("http://h/wmm/api/offline/$id/subtitles/s2.vtt".toUri()))
        assertNull(OfflineDataSource.downloadIdOf("http://h/api/offline/downloads/$id/token".toUri()))
        assertNull(OfflineDataSource.downloadIdOf("http://h/api/files/9/stream".toUri()))
    }

    private fun expectRefused(status: Int) {
        try {
            source().open(DataSpec(segment()))
            fail("expected a refusal")
        } catch (e: OfflineRefusedException) {
            assertEquals(status, e.status)
        }
    }
}
