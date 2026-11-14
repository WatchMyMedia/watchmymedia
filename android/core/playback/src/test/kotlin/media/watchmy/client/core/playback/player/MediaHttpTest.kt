package media.watchmy.client.core.playback.player

import java.io.File
import okhttp3.Cache
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class MediaHttpTest {
    @Test
    fun `the players share the app's connections but not its queue or cache`() {
        val shared = OkHttpClient.Builder().cache(Cache(File(System.getProperty("java.io.tmpdir"), "wmm-media-http-test"), 1_000_000)).build()
        val media = mediaHttpClient(shared)
        assertSame("one connection pool", shared.connectionPool, media.connectionPool)
        assertNotSame("segments don't queue the API's calls", shared.dispatcher, media.dispatcher)
        assertNull("media is never cached", media.cache)
        assertFalse("no redirect between http and https", media.followSslRedirects)
        assertEquals(15_000, media.connectTimeoutMillis)
        assertEquals(45_000, media.readTimeoutMillis)
    }
}
