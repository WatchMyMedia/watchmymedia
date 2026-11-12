package media.watchmy.client.core.data

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import media.watchmy.client.core.data.TestServer.Companion.fixture
import media.watchmy.client.core.data.TestServer.Companion.json
import media.watchmy.client.model.ImageSize
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Posters loading from a slow server don't hold up the screen's API calls. */
class ImageRequestsTest {
    private val h = Harness()
    private val server get() = h.server

    @Before
    fun signIn() {
        h.signIn()
    }

    @After
    fun tearDown() = h.close()

    @Test
    fun `an API call goes out while ten posters are still loading`() = runBlocking {
        val library = h.services.library
        val posters = (1..10).map { library.imageUrl("/p$it.jpg", ImageSize.W342)!! }
        val holds = posters.map { server.hold("GET ${it.substringAfter(server.baseUrl)}") }
        val done = CountDownLatch(posters.size)
        posters.forEach { url ->
            h.services.imageHttpClient.newCall(Request.Builder().url(url).build()).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = done.countDown()

                override fun onResponse(call: Call, response: Response) = response.use { done.countDown() }
            })
        }
        // As many posters as the image client runs at once are at the server, and stuck there.
        holds.take(DataServices.IMAGE_REQUESTS_PER_HOST).forEach { it.awaitArrival() }

        server.on("GET /api/home", json(fixture("home.json")))
        val home = withTimeout(3_000) { library.home() }
        assertTrue(home.continueWatching.isNotEmpty())
        assertEquals("No poster has been answered yet", posters.size.toLong(), done.count)

        holds.forEach { it.release() }
        assertTrue(done.await(5, TimeUnit.SECONDS))
    }
}
