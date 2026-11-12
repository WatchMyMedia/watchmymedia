package media.watchmy.client.core.downloads

import java.io.File
import java.net.URI
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import media.watchmy.client.model.OfflineItem
import media.watchmy.client.model.OfflineOwner
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class OfflineArtworkTest {
    private val dir = Files.createTempDirectory("wmm-offline-art").toFile()
    private val owner = OfflineOwner("fixture", 1, "https://fixture.example", 18)
    private val item = OfflineItem(OfflineItem.Kind.Movie, "Glass Tide", posterPath = "/poster.jpg", artPath = "/art.jpg")
    @After fun cleanUp() { dir.deleteRecursively() }

    @Test fun `artwork survives reopening and stays scoped to its account endpoint and download`() = runTest {
        val art = OfflineArtwork(dir)
        art.keep(owner, "one", item, { path, _ -> path.toByteArray() }, { true })
        val reopened = OfflineArtwork(dir)
        assertEquals("/poster.jpg", File(URI(reopened.local(owner, "one", "/poster.jpg")!!)).readText())
        assertNull(reopened.local(owner.copy(userId = 2), "one", "/poster.jpg"))
        assertNull(reopened.local(owner.copy(baseUrl = "https://other.example"), "one", "/poster.jpg"))
        assertNull(reopened.local(owner, "two", "/poster.jpg"))
        art.keep(owner, "two", item, { path, _ -> path.toByteArray() }, { true })
        art.remove(owner, "one")
        assertNull(art.local(owner, "one", "/poster.jpg"))
        assertNotNull(art.local(owner, "two", "/poster.jpg"))
    }

    @Test fun `a removed download cannot be recreated by a late artwork response`() = runTest {
        val art = OfflineArtwork(dir)
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<ByteArray>()
        val job = launch { art.keep(owner, "one", item, { _, _ -> started.complete(Unit); response.await() }, { true }) }
        started.await()
        art.remove(owner, "one")
        response.complete(byteArrayOf(1, 2, 3))
        job.join()
        assertNull(art.local(owner, "one", "/poster.jpg"))
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }
}
