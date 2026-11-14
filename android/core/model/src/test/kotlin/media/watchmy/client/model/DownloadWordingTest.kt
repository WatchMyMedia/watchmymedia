package media.watchmy.client.model

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadWordingTest {
    private fun download(sizeBytes: Long, estimated: Boolean, bytes: Long, percent: Int = 0) = OfflineDownload(
        id = "d1", fileId = 1, item = OfflineItem(OfflineItem.Kind.Movie, "Paper Moons"), quality = DownloadQuality.Original,
        mode = PlaybackMode.Direct, protocol = StreamProtocol.File, sizeBytes = sizeBytes, sizeEstimated = estimated,
        status = DownloadStatus.Downloading(percent, bytes),
    )

    @Test
    fun `progress is in the size's unit`() {
        val mb = 1_048_576L
        val gb = 1_073_741_824L
        // Under a tenth of a GB the size is in MB; the progress was "0.0 of 93.2 MB".
        assertEquals("Downloading 26% · 24.2 of 93.2 MB", downloadRowState(download((93.2 * mb).toLong(), false, (24.2 * mb).toLong(), percent = 26)))
        assertEquals("1.8 of 4.2 GB", progressBytes((1.8 * gb).toLong(), download((4.2 * gb).toLong(), false, 0)))
        assertEquals("19.4 of about 46 GB", progressBytes((19.4 * gb).toLong(), download(46 * gb, true, 0)))
        assertEquals("512.0 of about 900 KB", progressBytes(512 * 1024, download(900 * 1024, true, 0)))
        assertEquals("An unknown size shows what has come", "12.6 MB", progressBytes((12.6 * mb).toLong(), download(0, true, 0)))
    }
}
