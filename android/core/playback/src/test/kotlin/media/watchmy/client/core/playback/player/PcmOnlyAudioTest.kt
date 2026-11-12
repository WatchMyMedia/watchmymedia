package media.watchmy.client.core.playback.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioOutputProvider.FormatConfig
import androidx.media3.exoplayer.audio.AudioOutputProvider.FormatSupport
import media.watchmy.client.model.AudioPassthrough
import media.watchmy.client.model.DevicePlayback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Audio passthrough Off: the player's audio output takes PCM alone, so Media3 decodes Dolby rather than passing it on. */
@OptIn(UnstableApi::class)
class PcmOnlyAudioTest {
    private val direct = FormatSupport.Builder().setFormatSupportLevel(AudioOutputProvider.FORMAT_SUPPORTED_DIRECTLY).build()

    /** An output that takes everything as it is, as a receiver that decodes Dolby says it does. */
    private val takesAll = object : AudioOutputProvider {
        override fun getFormatSupport(formatConfig: FormatConfig): FormatSupport = direct

        override fun getOutputConfig(formatConfig: FormatConfig): AudioOutputProvider.OutputConfig = throw UnsupportedOperationException()

        override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput = throw UnsupportedOperationException()

        override fun addListener(listener: AudioOutputProvider.Listener) = Unit

        override fun removeListener(listener: AudioOutputProvider.Listener) = Unit

        override fun release() = Unit
    }

    private fun support(mime: String, pcm: Int = Format.NO_VALUE): FormatSupport =
        PcmOnlyOutput(takesAll).getFormatSupport(FormatConfig.Builder(Format.Builder().setSampleMimeType(mime).setPcmEncoding(pcm).setChannelCount(6).build()).build())

    @Test
    fun `Dolby and DTS are refused as bitstreams, so they are decoded`() {
        for (mime in listOf(MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC, MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_TRUEHD)) {
            assertEquals(mime, AudioOutputProvider.FORMAT_UNSUPPORTED, support(mime).supportLevel)
        }
    }

    @Test
    fun `PCM goes to the output as before`() {
        assertSame(direct, support(MimeTypes.AUDIO_RAW, C.ENCODING_PCM_16BIT))
    }

    @Test
    fun `the setting picks the renderers`() {
        assertTrue(PlayerOptions.of(DevicePlayback(), tv = true).passthrough)
        assertFalse(PlayerOptions.of(DevicePlayback(audioPassthrough = AudioPassthrough.Off), tv = true).passthrough)
    }
}
