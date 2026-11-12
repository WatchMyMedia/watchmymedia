package media.watchmy.client.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import media.watchmy.client.model.Trickplay
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import okio.Path.Companion.toOkioPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Collections
import kotlin.math.abs

/**
 * Seek thumbnails through Coil against a server: a sheet is fetched once,
 * its tiles cut out of it, a failed sheet left alone until the next scrub,
 * and the sheets either side fetched as a scrub starts. Real JPEGs, so
 * Robolectric's native graphics.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoilSeekThumbnailsTest {
    @get:Rule val folder = TemporaryFolder()

    private val server = MockWebServer()
    private val asked: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val missing = mutableSetOf<String>()

    /** 2 × 2 tiles of 32 × 18 a sheet: red, green, then blue, white. */
    private val sheet: Buffer by lazy {
        val bitmap = Bitmap.createBitmap(64, 36, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        listOf(Color.RED, Color.GREEN, Color.BLUE, Color.WHITE).forEachIndexed { cell, color ->
            paint.color = color
            canvas.drawRect((cell % 2) * 32f, (cell / 2) * 18f, (cell % 2 + 1) * 32f, (cell / 2 + 1) * 18f, paint)
        }
        Buffer().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it.outputStream()) }
    }

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path!!
                asked += path
                if (path.substringBefore('?') in missing) return MockResponse().setResponseCode(404)
                return MockResponse().setHeader("Content-Type", "image/jpeg").setBody(sheet.clone())
            }
        }
        server.start()
    }

    private val loader by lazy {
        ImageLoader.Builder(RuntimeEnvironment.getApplication())
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { OkHttpClient() })) }
            .memoryCache { MemoryCache.Builder().maxSizeBytes(4_000_000).build() }
            .diskCache { DiskCache.Builder().directory(folder.newFolder().toOkioPath()).build() }
            .build()
    }
    private val thumbnails by lazy { CoilSeekThumbnails(RuntimeEnvironment.getApplication()) { loader } }

    /** Three sheets of four tiles, 11 tiles in all. */
    private val trickplay by lazy { Trickplay(10_000, 32, 18, 2, 2, 11, 3, server.url("/api/files/4/trickplay/v1/{sheet}.jpg").toString().replace("%7B", "{").replace("%7D", "}")) }

    @After fun stop() = server.shutdown()

    @Test fun `a tile is cut from its sheet, which is fetched once`() = runBlocking(Dispatchers.IO) {
        val white = thumbnails.load(trickplay, trickplay.tileAt(35_000))
        assertNotNull(white)
        assertEquals(32, white!!.width)
        assertEquals(18, white.height)
        assertColor(Color.WHITE, white)
        assertColor(Color.GREEN, thumbnails.load(trickplay, trickplay.tileAt(10_000))!!)
        assertColor(Color.RED, thumbnails.load(trickplay, trickplay.tileAt(0))!!)
        assertEquals(listOf("/api/files/4/trickplay/v1/0.jpg"), asked)

        // The next sheet, and a tile already cut comes from memory.
        assertColor(Color.BLUE, thumbnails.load(trickplay, trickplay.tileAt(60_000))!!)
        assertColor(Color.WHITE, thumbnails.load(trickplay, trickplay.tileAt(39_999))!!)
        assertEquals(listOf("/api/files/4/trickplay/v1/0.jpg", "/api/files/4/trickplay/v1/1.jpg"), asked)
    }

    @Test fun `a sheet that fails is not asked for again until the next scrub`() = runBlocking(Dispatchers.IO) {
        missing += "/api/files/4/trickplay/v1/1.jpg"
        assertNull(thumbnails.load(trickplay, trickplay.tile(4)))
        assertNull(thumbnails.load(trickplay, trickplay.tile(5)))
        assertEquals(1, asked.count { it.endsWith("/1.jpg") })

        missing.clear()
        thumbnails.prefetch(trickplay, trickplay.tile(4))
        assertNotNull(thumbnails.load(trickplay, trickplay.tile(5)))
        assertEquals(2, asked.count { it.endsWith("/1.jpg") })
    }

    @Test fun `a scrub starting fetches its sheet and the sheets either side`() = runBlocking(Dispatchers.IO) {
        thumbnails.prefetch(trickplay, trickplay.tile(5))
        assertEquals(setOf("0", "1", "2").map { "/api/files/4/trickplay/v1/$it.jpg" }.toSet(), asked.toSet())
        assertEquals("each once", 3, asked.size)
        thumbnails.prefetch(trickplay, trickplay.tile(9))
        assertEquals("from the cache after", 3, asked.size)
    }

    @Test fun `a cast's token goes on the request, not into the cache key`() = runBlocking(Dispatchers.IO) {
        val cast = trickplay.copy(url = trickplay.url + "?access_token=c1")
        assertNotNull(thumbnails.load(cast, cast.tile(0)))
        assertEquals(listOf("/api/files/4/trickplay/v1/0.jpg?access_token=c1"), asked)
        assertNotNull(thumbnails.load(trickplay.copy(url = trickplay.url + "?access_token=c2"), cast.tile(1)))
        assertEquals("the renewed token's URL is the same sheet", 1, asked.size)
    }

    /** The middle of [bitmap] is about [color] (JPEG is lossy). */
    private fun assertColor(color: Int, bitmap: Bitmap) {
        val got = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
        val channels = listOf<(Int) -> Int>({ Color.red(it) }, { Color.green(it) }, { Color.blue(it) })
        val close = channels.all { channel -> abs(channel(got) - channel(color)) < 40 }
        assertTrue("expected #%06X, got #%06X".format(color and 0xFFFFFF, got and 0xFFFFFF), close)
    }
}
