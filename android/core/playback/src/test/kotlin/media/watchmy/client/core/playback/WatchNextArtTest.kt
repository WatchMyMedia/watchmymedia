package media.watchmy.client.core.playback

import android.app.Application
import android.net.Uri
import media.watchmy.client.core.playback.watchnext.WatchNextArt
import media.watchmy.client.core.playback.watchnext.WatchNextArtProvider
import media.watchmy.client.core.playback.watchnext.WatchNextArtSource
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.FileNotFoundException

/**
 * The launcher's Watch Next pictures (PLAN-ANDROID-REVIEW.md §2.4): an
 * address names the account and the server's image path and carries this
 * app's signature, and the provider answers only what this app signed, for
 * the account signed in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = WatchNextArtTest.App::class)
// Robolectric's Conscrypt can't load the JDK's HmacSHA256 (android/AGENTS.md, "Crypto under Robolectric").
@ConscryptMode(ConscryptMode.Mode.OFF)
class WatchNextArtTest {
    /** An app signed in as [signedIn], whose server has one picture. */
    class App : Application(), WatchNextArtSource {
        var signedIn = "server-1:7"
        val asked = mutableListOf<String>()

        override suspend fun watchNextArt(account: String, path: String): ByteArray? {
            asked += path
            return if (account == signedIn && path == "/still.jpg") byteArrayOf(1, 2, 3) else null
        }
    }

    private val app get() = RuntimeEnvironment.getApplication() as App

    private fun provider() = Robolectric.buildContentProvider(WatchNextArtProvider::class.java).create(WatchNextArt.authority(app)).get()

    @Test fun `an address this app signed names its account and path`() {
        val uri = WatchNextArt.uri(app, "server-1:7", "/still.jpg")
        assertEquals("content", uri.scheme)
        assertEquals("${app.packageName}.watchnext.art", uri.authority)
        assertEquals("server-1:7" to "/still.jpg", WatchNextArt.open(app, uri))
    }

    @Test fun `an address changed by anyone else opens nothing`() {
        val uri = WatchNextArt.uri(app, "server-1:7", "/still.jpg")
        val otherPath = uri.buildUpon().clearQuery()
            .appendQueryParameter("path", "/other.jpg")
            .appendQueryParameter("s", uri.getQueryParameter("s"))
            .build()
        assertNull(WatchNextArt.open(app, otherPath))
        val otherAccount = Uri.Builder().scheme("content").authority(uri.authority).appendPath("server-1:8")
            .appendQueryParameter("path", "/still.jpg").appendQueryParameter("s", uri.getQueryParameter("s")).build()
        assertNull(WatchNextArt.open(app, otherAccount))
        assertNull(WatchNextArt.open(app, uri.buildUpon().clearQuery().appendQueryParameter("path", "/still.jpg").build()))
    }

    @Test fun `the provider reads the picture for the account signed in, and refuses the rest`() {
        val provider = provider()
        val read = provider.openFile(WatchNextArt.uri(app, "server-1:7", "/still.jpg"), "r")
        awaitWriters()
        val bytes = android.os.ParcelFileDescriptor.AutoCloseInputStream(read).use { it.readBytes() }
        assertArrayEquals(byteArrayOf(1, 2, 3), bytes)

        app.signedIn = "server-1:8"
        assertTrue(fails { provider.openFile(WatchNextArt.uri(app, "server-1:7", "/still.jpg"), "r") })
        val asked = app.asked.size
        assertTrue(fails { provider.openFile(Uri.parse("content://${app.packageName}.watchnext.art/server-1:8?path=/still.jpg&s=forged"), "r") })
        assertEquals("A forged address never reaches the server", asked, app.asked.size)
        assertTrue(runCatching { provider.openFile(WatchNextArt.uri(app, "server-1:8", "/still.jpg"), "rw") }.exceptionOrNull() is SecurityException)
    }

    /**
     * Robolectric's pipe is a file, which reads as empty until the provider's
     * writer has finished; a device's pipe waits for the writer.
     */
    private fun awaitWriters() {
        Thread.getAllStackTraces().keys.filter { it.name == "WatchNextArt" }.forEach { it.join(5_000) }
    }

    private fun fails(block: () -> Unit): Boolean = runCatching(block).exceptionOrNull() is FileNotFoundException
}
