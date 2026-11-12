package media.watchmy.client

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import coil3.ImageLoader
import coil3.imageDecoderEnabled
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.google.common.util.concurrent.ListenableFuture
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutionException
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The media notification's pictures come through the app's image loader, so
 * through its signed-in client: here one that signs requests as the app's
 * image client does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArtworkLoaderTest {
    private val context = RuntimeEnvironment.getApplication()
    private val server = MockWebServer().apply { start() }
    private val signedIn = OkHttpClient.Builder()
        .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer token").build()) }
        .build()
    private val images = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { signedIn })) }
        // Robolectric's ImageDecoder reads only some sources; BitmapFactory reads them all.
        .imageDecoderEnabled(false)
        .build()
    private val loader = ArtworkLoader(context) { images }

    @After
    fun stop() = server.shutdown()

    @Test
    fun `a backdrop comes through the signed-in client as a software bitmap`() {
        server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(png(16, 9))))
        val bitmap = await(loader.loadBitmap(Uri.parse(server.url("/api/images/w780/backdrop.jpg").toString())))
        assertEquals(16, bitmap.width)
        assertNotEquals("The session hands it to the system UI", Bitmap.Config.HARDWARE, bitmap.config)
        val request = server.takeRequest()
        assertEquals("/api/images/w780/backdrop.jpg", request.path)
        assertEquals("Bearer token", request.getHeader("Authorization"))
    }

    @Test
    fun `a picture the server refuses fails`() {
        server.enqueue(MockResponse().setResponseCode(404))
        val future = loader.loadBitmap(Uri.parse(server.url("/api/images/w780/gone.jpg").toString()))
        val failed = runCatching { await(future) }.exceptionOrNull()
        assertTrue("$failed", failed is ExecutionException)
    }

    @Test
    fun `embedded artwork is decoded too`() {
        assertEquals(4, await(loader.decodeBitmap(png(4, 4))).height)
    }

    private fun png(width: Int, height: Int): ByteArray =
        ByteArrayOutputStream().also { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    /** Runs the main looper, where the image loader answers, until [future] is done (the fetch itself runs on Coil's threads). */
    private fun <T> await(future: ListenableFuture<T>): T {
        repeat(2_500) {
            if (future.isDone) return future.get()
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(2)
        }
        error("No answer from the image loader")
    }
}
