package media.watchmy.client.core.playback.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider

/**
 * Audio passthrough Off: renderers whose audio output takes PCM alone, so
 * Media3 decodes Dolby audio here instead of passing the bitstream to a TV or
 * receiver that said it takes it but doesn't. The profile leaves the
 * passthrough-only formats out in step (DeviceProfileProvider), so the server
 * never sends audio that only passthrough could play.
 */
@OptIn(UnstableApi::class)
internal class PcmOnlyRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink =
        DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
            .setAudioOutputProvider(PcmOnlyOutput(AudioTrackAudioOutputProvider.Builder(context).build()))
            .build()
}

/** An audio output that says it takes no encoded format, only PCM ([MimeTypes.AUDIO_RAW]). */
@OptIn(UnstableApi::class)
internal class PcmOnlyOutput(output: AudioOutputProvider) : ForwardingAudioOutputProvider(output) {
    override fun getFormatSupport(formatConfig: AudioOutputProvider.FormatConfig): AudioOutputProvider.FormatSupport =
        if (formatConfig.format.sampleMimeType == MimeTypes.AUDIO_RAW) super.getFormatSupport(formatConfig) else AudioOutputProvider.FormatSupport.UNSUPPORTED
}
