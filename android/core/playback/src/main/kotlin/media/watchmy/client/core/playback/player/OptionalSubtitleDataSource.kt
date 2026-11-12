package media.watchmy.client.core.playback.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Optional side-loaded text is bounded and buffered before extraction. A failed
 * track becomes an empty WebVTT source, so its HTTP/parser failure never becomes
 * a fatal video-source error. Main media and HLS requests retain normal errors.
 */
@UnstableApi
internal class OptionalSubtitleDataSource(
    private val upstream: DataSource,
    private val subtitle: (Uri) -> SideloadedSubtitle?,
    private val failed: (SideloadedSubtitle) -> Unit,
) : DataSource {
    private var source: DataSource = upstream

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val track = subtitle(dataSpec.uri) ?: return upstream.open(dataSpec)
        val bytes = try {
            val request = dataSpec.buildUpon().setPosition(0).setLength(C.LENGTH_UNSET.toLong()).build()
            val length = upstream.open(request)
            if (length > MAX_BYTES) throw IOException("Subtitle exceeds the size limit")
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val read = upstream.read(buffer, 0, buffer.size)
                if (read == C.RESULT_END_OF_INPUT) break
                if (output.size() + read > MAX_BYTES) throw IOException("Subtitle exceeds the size limit")
                output.write(buffer, 0, read)
            }
            output.toByteArray().also {
                val header = it.take(32).toByteArray().toString(Charsets.UTF_8).removePrefix("\uFEFF")
                if (!Regex("^WEBVTT(?:[ \t\r\n]|$)").containsMatchIn(header)) throw IOException("Invalid WebVTT subtitle")
            }
        } catch (_: IOException) {
            failed(track)
            EMPTY_VTT
        } finally {
            upstream.close()
        }
        source = ByteArrayDataSource(bytes)
        // A failed partial-range load must also finish harmlessly.
        val local = if (dataSpec.position >= bytes.size) dataSpec.buildUpon().setPosition(0).setLength(C.LENGTH_UNSET.toLong()).build() else dataSpec
        return source.open(local)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = source.read(buffer, offset, length)
    override fun getUri(): Uri? = source.uri
    override fun getResponseHeaders(): Map<String, List<String>> = source.responseHeaders
    override fun close() = source.close()

    companion object {
        private const val MAX_BYTES = 16 * 1024 * 1024
        private val EMPTY_VTT = "WEBVTT\n\n".toByteArray()
    }
}
