package media.watchmy.client.fake

import kotlinx.coroutines.test.runTest
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.DownloadChoice
import media.watchmy.client.model.DownloadMark
import media.watchmy.client.model.DownloadQuality
import media.watchmy.client.model.DownloadStatus
import media.watchmy.client.model.OfflineItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** The fake downloads hold every state the boards draw, and a new one goes through them. */
class FakeDownloadsTest {
    @Before
    fun fast() {
        fakeLatencyMs = 0
    }

    @Test
    fun `the sample has every state of the Downloads board`() {
        val held = FakeDownloadRepository().downloads.value
        assertTrue(held.any { it.status is DownloadStatus.Downloading })
        assertTrue(held.any { it.status == DownloadStatus.Waiting(DownloadStatus.Reason.Wifi) })
        assertTrue(held.any { it.mark == DownloadMark.RemovedFromServer })
        assertTrue(held.any { it.mark == DownloadMark.NewVersion && it.replacedBy != null })
        assertEquals(2, held.count { it.item.kind == OfflineItem.Kind.Movie })
        assertEquals(listOf("Harbor Lights", "Harbor Lights", "Harbor Lights"), held.filter { it.item.showId == 100L }.map { it.item.showTitle })
    }

    @Test
    fun `a download waits, prepares, goes up and finishes`() = runTest {
        val r = FakeDownloadRepository(sample = false)
        r.download(DownloadChoice(9400 + 503, DownloadQuality.P720))
        val d = r.downloads.value.single()
        assertEquals("about 4.4 GB", media.watchmy.client.model.downloadSize(d.sizeBytes, d.sizeEstimated))
        assertEquals(DownloadStatus.Waiting(DownloadStatus.Reason.Turn), d.status)
        r.advance()
        assertEquals(DownloadStatus.Preparing, r.downloads.value.single().status)
        r.advance()
        assertEquals(DownloadStatus.Downloading(7, d.sizeBytes * 7 / 100), r.downloads.value.single().status)
        r.pause(d.id)
        assertTrue(r.downloads.value.single().status is DownloadStatus.Paused)
        r.resume(d.id)
        repeat(20) { r.advance() }
        assertEquals(DownloadStatus.Downloaded, r.downloads.value.single().status)
        assertFalse(r.downloads.value.single().sizeEstimated)
    }

    @Test
    fun `Who can download is admins`() = runTest {
        var admin = false
        val r = FakeDownloadRepository({ admin }, sample = false)
        assertFalse(r.canDownload.value)
        assertFalse(r.enabled.value)
        try {
            r.plan(9101)
            fail("expected a refusal")
        } catch (e: ApiException) {
            assertEquals(403, e.status)
        }
        admin = true
        r.refresh()
        assertTrue(r.canDownload.value)
    }

    @Test
    fun `an episode's original is the file, a movie's 4K converts`() = runTest {
        val r = FakeDownloadRepository(sample = false)
        assertFalse(r.plan(9300 + 100 * 100 + 37).options.first().estimated)
        val movie = r.plan(9400 + 500)
        assertTrue(movie.options.first().estimated)
        assertEquals(2, movie.audio.size)
        assertEquals(1, movie.imageSubtitles.size)
    }
}
