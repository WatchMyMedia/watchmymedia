package media.watchmy.client.core.playback

import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaFormat
import android.os.Looper
import android.provider.Settings
import android.view.Display
import android.view.Display.HdrCapabilities
import media.watchmy.client.core.playback.profile.DeviceProfileProvider
import media.watchmy.client.core.playback.profile.VideoSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.MediaCodecInfoBuilder
import org.robolectric.shadows.MediaCodecInfoBuilder.CodecCapabilitiesBuilder
import org.robolectric.shadows.ShadowMediaCodecList

/**
 * [DeviceProfileProvider] on a box whose decoders take Dolby Vision 5 and 8
 * (read from MediaCodecList), moved between TVs: the HDR types come from the
 * display, and the profile follows the display when it changes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeviceProfileProviderTest {
    private val context = RuntimeEnvironment.getApplication()
    private val display: Display get() = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)

    @Before
    fun decoders() {
        ShadowMediaCodecList.reset()
        decoder("c2.vendor.hevc.decoder", MediaFormat.MIMETYPE_VIDEO_HEVC, CodecProfileLevel.HEVCProfileMain, CodecProfileLevel.HEVCProfileMain10)
        decoder(
            "c2.vendor.dolby-vision.decoder",
            MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION,
            CodecProfileLevel.DolbyVisionProfileDvheStn,
            CodecProfileLevel.DolbyVisionProfileDvheSt,
            // Profile 4, which the server has no key for.
            CodecProfileLevel.DolbyVisionProfileDvheDtr,
        )
    }

    @Test
    fun `the profile follows the TV the box is plugged into`() {
        shows(HdrCapabilities.HDR_TYPE_HDR10, HdrCapabilities.HDR_TYPE_HLG, HdrCapabilities.HDR_TYPE_DOLBY_VISION)
        val provider = DeviceProfileProvider(context, tv = true)
        val dv = provider.current()
        assertEquals(listOf("hevc", "hevc10", "dovi5", "dovi8"), dv.videoCodecs)
        assertEquals(listOf("hdr10", "hlg", "dovi"), dv.hdrTypes)
        assertTrue(dv.hdr)
        assertEquals(setOf(5, 8), provider.describe().decoders.first { it.mimeType == "video/dolby-vision" }.doviProfiles)

        // Moved to an HDR10 TV: the same decoders, but no Dolby Vision for this screen.
        shows(HdrCapabilities.HDR_TYPE_HDR10)
        val hdr10 = provider.current()
        assertEquals(listOf("hevc", "hevc10"), hdr10.videoCodecs)
        assertEquals(listOf("hdr10"), hdr10.hdrTypes)

        // And to an SDR one: 10-bit HEVC still, with no HDR.
        shows()
        val sdr = provider.current()
        assertEquals(listOf("hevc", "hevc10"), sdr.videoCodecs)
        assertEquals(emptyList<String>(), sdr.hdrTypes)
        assertFalse(sdr.hdr)
    }

    @Test
    fun `the size limit is the largest the decoders take, not the display's`() {
        ShadowMediaCodecList.reset()
        // H.264 in hardware to 1080p, HEVC in hardware to 4K, and a software HEVC that declares more.
        val avc = CodecProfileLevel.AVCProfileHigh to CodecProfileLevel.AVCLevel42
        val hevc = CodecProfileLevel.HEVCProfileMain to CodecProfileLevel.HEVCMainTierLevel62
        videoDecoder("c2.vendor.avc.decoder", MediaFormat.MIMETYPE_VIDEO_AVC, "16x16-1920x1088", 489_600, avc, hardware = true)
        videoDecoder("c2.vendor.hevc.decoder", MediaFormat.MIMETYPE_VIDEO_HEVC, "16x16-4096x2304", 1_000_000, hevc, hardware = true)
        videoDecoder("c2.android.hevc.decoder", MediaFormat.MIMETYPE_VIDEO_HEVC, "16x16-8192x4320", 8_000_000, hevc, hardware = false)
        val provider = DeviceProfileProvider(context, tv = false)
        assertEquals(VideoSize(1920, 1080), provider.videoLimit(MediaFormat.MIMETYPE_VIDEO_AVC))
        assertEquals("The hardware decoder's, not the software one's", VideoSize(4096, 2160), provider.videoLimit(MediaFormat.MIMETYPE_VIDEO_HEVC))
        val p = provider.current()
        assertEquals(listOf("h264", "hevc"), p.videoCodecs)
        assertEquals("Whatever the display", 4096, p.maxWidth)
        assertEquals(2160, p.maxHeight)
    }

    private fun videoDecoder(name: String, mime: String, sizes: String, blocksPerSecond: Int, profileLevel: Pair<Int, Int>, hardware: Boolean) {
        val format = MediaFormat().apply {
            setString(MediaFormat.KEY_MIME, mime)
            setString("size-range", sizes)
            setString("block-size", "16x16")
            setString("blocks-per-second-range", "1-$blocksPerSecond")
            setString("frame-rate-range", "1-60")
        }
        val caps = CodecCapabilitiesBuilder.newBuilder()
            .setMediaFormat(format)
            .setIsEncoder(false)
            .setProfileLevels(arrayOf(CodecProfileLevel().apply { profile = profileLevel.first; level = profileLevel.second }))
            .setColorFormats(intArrayOf(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible))
            .build()
        ShadowMediaCodecList.addCodec(MediaCodecInfoBuilder.newBuilder().setName(name).setIsEncoder(false).setIsHardwareAccelerated(hardware).setCapabilities(caps).build())
    }

    @Test
    fun `passthrough Off leaves the bitstream formats out, from the next profile on`() {
        // An output that takes Dolby as a bitstream (what Media3 reads from these settings off a TV),
        // on a box with no Dolby decoder of its own.
        Settings.Global.putInt(context.contentResolver, "use_external_surround_sound_flag", 1)
        Settings.Global.putInt(context.contentResolver, "external_surround_sound_enabled", 1)
        var allowed = true
        val provider = DeviceProfileProvider(context, tv = true, passthroughAllowed = { allowed })
        assertEquals(setOf("ac3", "eac3"), provider.describe().passthrough)
        assertTrue(provider.current().audioCodecs.containsAll(listOf("ac3", "eac3")))

        allowed = false
        val off = provider.current()
        assertEquals(listOf("aac", "mp3", "opus", "flac", "vorbis"), off.audioCodecs)
        assertEquals("Within what the output takes as PCM", provider.describe().maxPcmChannels, off.maxAudioChannels)
        assertEquals("What the device can do is still described", setOf("ac3", "eac3"), provider.describe().passthrough)

        allowed = true
        assertTrue(provider.current().audioCodecs.containsAll(listOf("ac3", "eac3")))
    }

    private fun shows(vararg types: Int) {
        shadowOf(display).setDisplayHdrCapabilities(Display.DEFAULT_DISPLAY, 1000f, 500f, 0.05f, *types)
        // The display listener runs on the main thread.
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun decoder(name: String, mime: String, vararg profiles: Int) {
        val caps = CodecCapabilitiesBuilder.newBuilder()
            .setMediaFormat(MediaFormat().apply { setString(MediaFormat.KEY_MIME, mime) })
            .setIsEncoder(false)
            .setProfileLevels(profiles.map { p -> CodecProfileLevel().apply { profile = p; level = 0x100 } }.toTypedArray())
            .setColorFormats(intArrayOf(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible))
            .build()
        ShadowMediaCodecList.addCodec(
            MediaCodecInfoBuilder.newBuilder().setName(name).setIsEncoder(false).setIsHardwareAccelerated(true).setCapabilities(caps).build(),
        )
    }
}
