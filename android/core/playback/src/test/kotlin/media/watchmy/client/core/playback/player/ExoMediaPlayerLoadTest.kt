package media.watchmy.client.core.playback.player

import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.media3.test.utils.WebServerDispatcher
import java.time.Duration
import java.util.concurrent.TimeUnit
import media.watchmy.client.model.MediaToken
import okhttp3.mockwebserver.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A real ExoMediaPlayer loading a real file over HTTP (Media3's
 * WebServerDispatcher on MockWebServer): the requests go through the app's
 * OkHttp client (Media3's OkHttp data source) with the app's user agent and
 * the renewed media token in place of the URL's, the file is read to its
 * end, and a refusal comes back as its HTTP status. Robolectric has no
 * decoders, so no track is selected and the player ends: the decoder
 * failure itself is covered in ExoMediaPlayerTest.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExoMediaPlayerLoadTest {
    private val context = RuntimeEnvironment.getApplication()
    private val server = MockWebServer()
    private lateinit var player: ExoMediaPlayer
    private val failures = mutableListOf<PlayerFailure>()

    @Before
    fun setUp() {
        val file = checkNotNull(javaClass.classLoader?.getResourceAsStream("h264-320x240.mp4")).use { it.readBytes() }
        server.dispatcher = WebServerDispatcher.forResources(
            listOf(WebServerDispatcher.Resource.Builder().setPath("/api/files/1/stream").setData(file).supportsRangeRequests(true).build()),
        )
        server.start()
        player = ExoMediaPlayer(context, "WatchMyMedia/test (androidtv; Android 15)", mediaHttpClient(OkHttpClient()))
        player.setListener(object : MediaPlayer.Listener {
            override fun onChanged() = Unit

            override fun onError(failure: PlayerFailure) {
                failures += failure
            }
        })
    }

    @After
    fun tearDown() {
        player.release()
        server.shutdown()
    }

    @Test
    fun `a file loads over the app's client with the renewed token and plays to its end`() {
        val base = server.url("/").toString().trimEnd('/')
        player.updateMediaToken(MediaToken("fresh token", Long.MAX_VALUE, serverUrl = base))
        player.load(source("$base/api/files/1/stream?play=a1&access_token=old"))
        until("the file to end") { player.phase == PlayerPhase.Ended }
        assertTrue(failures.isEmpty())

        val request = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("WatchMyMedia/test (androidtv; Android 15)", request.getHeader("User-Agent"))
        val url = checkNotNull(request.requestUrl)
        assertEquals("/api/files/1/stream", url.encodedPath)
        assertEquals("a1", url.queryParameter("play"))
        assertEquals("The renewed token, not the URL's", listOf("fresh token"), url.queryParameterValues("access_token"))
    }

    @Test
    fun `a refused file comes back as its HTTP status`() {
        val base = server.url("/").toString().trimEnd('/')
        player.load(source("$base/api/files/2/stream?access_token=t"))
        until("the player to fail") { failures.isNotEmpty() }
        val failure = failures.single()
        assertEquals(PlayerFailure.Kind.Http, failure.kind)
        assertEquals(404, failure.httpStatus)
    }

    private fun source(url: String) = PlayerSource(
        url = url, hls = false, live = false, startPositionMs = 0, playWhenReady = true,
        subtitles = emptyList(), text = TextSelection.Off, audioOrdinal = null, title = "Test card", subtitle = null,
    )

    /** Runs the main looper, moving Robolectric's clock on, until [done]; the player's own threads run meanwhile. */
    private fun until(what: String, done: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!done()) {
            check(System.nanoTime() < deadline) { "timed out waiting for $what: phase ${player.phase}, ${server.requestCount} requests, failures $failures" }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            Thread.sleep(2)
        }
    }
}
