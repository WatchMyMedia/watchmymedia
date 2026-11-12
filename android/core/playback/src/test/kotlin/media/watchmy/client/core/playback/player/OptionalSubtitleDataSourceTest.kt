package media.watchmy.client.core.playback.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OptionalSubtitleDataSourceTest {
    private val uri = Uri.parse("https://fixture.example/api/files/1/subtitles/s2.vtt")
    private val track = SideloadedSubtitle("s2", uri.toString(), "text/vtt", "English", "en")

    @Test fun `a failed optional text source finishes without propagating its failure to video`() {
        val failures = mutableListOf<String>()
        val broken = object : DataSource by ByteArrayDataSource(byteArrayOf(1)) {
            override fun open(dataSpec: DataSpec): Long = throw IOException("Subtitle unavailable")
        }
        val source = OptionalSubtitleDataSource(broken, { track.takeIf { it.url == uri.toString() } }, { failures += it.id })
        assertEquals(8L, source.open(DataSpec(uri)))
        val bytes = ByteArray(32)
        val count = source.read(bytes, 0, bytes.size)
        assertEquals("WEBVTT\n\n", bytes.copyOf(count).toString(Charsets.UTF_8))
        assertEquals(C.RESULT_END_OF_INPUT, source.read(bytes, 0, bytes.size))
        assertEquals(listOf("s2"), failures)
        source.close()
    }

    @Test(expected = IOException::class) fun `main media failures keep their normal error semantics`() {
        val broken = object : DataSource by ByteArrayDataSource(byteArrayOf(1)) {
            override fun open(dataSpec: DataSpec): Long = throw IOException("Video unavailable")
        }
        OptionalSubtitleDataSource(broken, { null }, { fail("Main media is not optional") }).open(DataSpec(uri))
    }

    @Test fun `invalid text is dropped before it reaches extraction`() {
        val failures = mutableListOf<String>()
        val source = OptionalSubtitleDataSource(ByteArrayDataSource("<html>proxy failure</html>".toByteArray()), { track }, { failures += it.id })
        assertEquals(8L, source.open(DataSpec(uri)))
        assertEquals(listOf("s2"), failures)
        source.close()
    }
}
