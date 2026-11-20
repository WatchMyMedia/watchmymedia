package media.watchmy.client.core.playback.player

import android.os.Looper
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.test.utils.WebServerDispatcher
import java.time.Duration
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import media.watchmy.client.model.MediaToken
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
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

    /**
     * An event playlist as the server serves it:
     * live, with EXT-X-START where the asked-for frame is. Its segments never
     * answer, so the player stays paused on the timeline the playlists give.
     * The app loads it where the asked-for frame is in the session; Media3
     * keeps an explicit start, 0 included, over the playlist's default.
     */
    @Test
    fun `an event playlist keeps counting from the stream's start once its head is trimmed`() {
        val trimmed = AtomicBoolean(false)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.requestUrl?.encodedPath?.endsWith("index.m3u8") == true) {
                    MockResponse().setHeader("Content-Type", "application/vnd.apple.mpegurl").setBody(eventPlaylist(if (trimmed.get()) 1 else 0))
                } else {
                    MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                }
        }
        val base = server.url("/").toString().trimEnd('/')
        // The session began on the keyframe 6 s before the frame asked for.
        player.load(source("$base/api/playback/s1/index.m3u8", hls = true, live = true, playWhenReady = false, startPositionMs = 6_000))
        val media3 = checkNotNull(player.media3)
        until("the playlist's timeline") { !media3.currentTimeline.isEmpty && !media3.currentTimeline.getWindow(0, Timeline.Window()).isPlaceholder }
        assertEquals("The tag is the window's default", 6_000L, media3.currentTimeline.getWindow(0, Timeline.Window()).defaultPositionMs)
        assertEquals(6_000L, player.positionMs)

        // The server trims the first segment: Media3's window moves on 4 s.
        trimmed.set(true)
        until("the trimmed playlist") { media3.currentPosition == 2_000L }
        assertEquals("Still 6 s into the stream", 6_000L, player.positionMs)
        player.seekTo(10_000)
        until("the seek") { player.positionMs == 10_000L }
        assertEquals(6_000L, media3.currentPosition)
        assertTrue(failures.isEmpty())
    }

    /** Eight 4-second segments from [first], with the tag the server would give for a frame 6 s into the session. */
    private fun eventPlaylist(first: Int): String = buildString {
        append("#EXTM3U\n#EXT-X-VERSION:7\n")
        append(String.format(Locale.ROOT, "#EXT-X-START:TIME-OFFSET=%.3f,PRECISE=YES\n", 6.0 - first * 4.0))
        append("#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:$first\n#EXT-X-INDEPENDENT-SEGMENTS\n#EXT-X-MAP:URI=\"init.mp4\"\n")
        for (i in first until first + 8) append(String.format(Locale.ROOT, "#EXTINF:4.000000,\nseg%05d.m4s\n", i))
    }

    private fun source(url: String, hls: Boolean = false, live: Boolean = false, playWhenReady: Boolean = true, startPositionMs: Long = 0) = PlayerSource(
        url = url, hls = hls, live = live, startPositionMs = startPositionMs, playWhenReady = playWhenReady,
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
