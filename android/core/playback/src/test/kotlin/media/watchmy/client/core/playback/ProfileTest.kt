package media.watchmy.client.core.playback

import media.watchmy.client.core.playback.profile.DecoderInfo
import media.watchmy.client.core.playback.profile.DeviceDescription
import media.watchmy.client.core.playback.profile.DisplayInfo
import media.watchmy.client.core.playback.profile.buildProfile
import media.watchmy.client.core.playback.profile.decodedAudioCodec
import media.watchmy.client.core.playback.profile.shownHdrTypes
import media.watchmy.client.model.HDR_PROFILE_API_LEVEL
import media.watchmy.client.model.PlaybackProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileTest {
    private val basicAudio = listOf("aac", "mp3", "opus", "flac", "vorbis")

    @Test
    fun googleTvStreamer() {
        val p = buildProfile(
            DeviceDescription(
                tv = true,
                decoders = listOf(
                    DecoderInfo("video/avc", hardware = true),
                    DecoderInfo("video/hevc", hardware = true, main10 = true),
                    DecoderInfo("video/av01", hardware = true),
                    DecoderInfo("video/x-vnd.on2.vp9", hardware = true),
                    DecoderInfo("audio/ac3", hardware = false),
                    DecoderInfo("audio/eac3", hardware = false),
                ),
                passthrough = setOf("ac3", "eac3"),
                maxPcmChannels = 8,
                display = DisplayInfo(3840, 2160, hdr10 = true, hlg = true),
            ),
        )
        assertEquals("androidtv", p.name)
        assertEquals(listOf("mkv", "mp4", "webm", "mpegts"), p.containers)
        assertEquals(listOf("h264", "hevc", "hevc10", "av1", "vp9"), p.videoCodecs)
        assertEquals(basicAudio + listOf("ac3", "eac3"), p.audioCodecs)
        assertEquals(3840, p.maxWidth)
        assertEquals(2160, p.maxHeight)
        assertEquals(null, p.maxBitrate)
        assertEquals(8, p.maxAudioChannels)
        assertTrue(p.hdr)
        assertEquals(listOf("hdr10", "hlg"), p.hdrTypes)
        assertEquals(listOf("webvtt", "ass"), p.subtitleFormats)
    }

    @Test
    fun older1080pBoxWithoutDolby() {
        val p = buildProfile(
            DeviceDescription(
                tv = true,
                decoders = listOf(
                    DecoderInfo("video/avc", hardware = true),
                    DecoderInfo("video/hevc", hardware = true),
                    // Software only: not offered.
                    DecoderInfo("video/x-vnd.on2.vp9", hardware = false),
                    DecoderInfo("video/av01", hardware = false),
                ),
                passthrough = emptySet(),
                maxPcmChannels = 2,
                display = DisplayInfo(1920, 1080, hdr10 = false),
            ),
        )
        assertEquals(listOf("h264", "hevc"), p.videoCodecs)
        assertEquals(basicAudio, p.audioCodecs)
        assertEquals(1920, p.maxWidth)
        assertEquals(1080, p.maxHeight)
        assertEquals(2, p.maxAudioChannels)
        assertFalse(p.hdr)
        assertEquals(emptyList<String>(), p.hdrTypes)
    }

    @Test
    fun receiverPassesThroughDtsAndTrueHd() {
        val p = buildProfile(
            DeviceDescription(
                tv = true,
                decoders = listOf(DecoderInfo("video/avc", true), DecoderInfo("video/hevc", true, main10 = true)),
                passthrough = setOf("ac3", "eac3", "dts", "truehd"),
                // The receiver's PCM limit does not hold back bitstreams.
                maxPcmChannels = 2,
                display = DisplayInfo(3840, 2160, hdr10 = false, hlg = true),
            ),
        )
        assertEquals(basicAudio + listOf("ac3", "eac3", "dts", "truehd"), p.audioCodecs)
        assertEquals(8, p.maxAudioChannels)
        // HLG alone still counts as HDR, but not as HDR10.
        assertEquals(listOf("h264", "hevc", "hevc10"), p.videoCodecs)
        assertTrue(p.hdr)
        assertEquals(listOf("hlg"), p.hdrTypes)
        // An older server gets 10-bit HEVC only for an HDR10 display.
        assertEquals(listOf("h264", "hevc"), p.forApiLevel(HDR_PROFILE_API_LEVEL - 1).videoCodecs)
    }

    @Test
    fun dtsDecoderWithoutPassthroughIsNotEnough() {
        val p = buildProfile(
            DeviceDescription(
                tv = true,
                decoders = listOf(DecoderInfo("video/avc", true), DecoderInfo("audio/vnd.dts", true)),
                passthrough = emptySet(),
                maxPcmChannels = 8,
                display = DisplayInfo(1920, 1080, hdr10 = false),
            ),
        )
        assertFalse("dts" in p.audioCodecs)
    }

    @Test
    fun hdrPhone() {
        val p = buildProfile(
            DeviceDescription(
                tv = false,
                decoders = listOf(
                    DecoderInfo("video/avc", true),
                    DecoderInfo("video/hevc", true, main10 = true),
                    DecoderInfo("video/x-vnd.on2.vp9", true),
                    DecoderInfo("audio/eac3-joc", false),
                ),
                passthrough = emptySet(),
                maxPcmChannels = 10,
                display = DisplayInfo(2400, 1080, hdr10 = true),
            ),
        )
        assertEquals("android", p.name)
        assertEquals(listOf("h264", "hevc", "hevc10", "vp9"), p.videoCodecs)
        assertEquals(basicAudio + "eac3", p.audioCodecs)
        assertEquals(2400, p.maxWidth)
        assertEquals(1080, p.maxHeight)
        assertTrue(p.hdr)
    }

    @Test
    fun failedDecodersAreLeftOutUnlessPassedThrough() {
        val pixel = DeviceDescription(
            tv = false,
            decoders = listOf(
                DecoderInfo("video/hevc", true),
                DecoderInfo("audio/ac3", false),
                DecoderInfo("audio/eac3", false),
                DecoderInfo("audio/eac3-joc", false),
            ),
            passthrough = emptySet(),
            maxPcmChannels = 6,
            display = DisplayInfo(2992, 1344, hdr10 = true),
            failedDecoders = setOf("eac3"),
        )
        assertEquals(basicAudio + "ac3", buildProfile(pixel).audioCodecs)
        // A receiver takes the bitstream, so the decoder is not used.
        assertEquals(basicAudio + listOf("ac3", "eac3"), buildProfile(pixel.copy(passthrough = setOf("eac3"))).audioCodecs)
        assertEquals(listOf("hevc"), buildProfile(pixel).videoCodecs)
    }

    @Test
    fun sdrPhoneWithMain10OffersHevc10() {
        val p = buildProfile(
            DeviceDescription(
                tv = false,
                decoders = listOf(DecoderInfo("video/avc", true), DecoderInfo("video/hevc", true, main10 = true)),
                passthrough = emptySet(),
                maxPcmChannels = 2,
                display = DisplayInfo(2340, 1080, hdr10 = false),
            ),
        )
        // 10-bit SDR HEVC plays as it is; the server keeps HDR10 off a screen that lists none.
        assertEquals(listOf("h264", "hevc", "hevc10"), p.videoCodecs)
        assertFalse(p.hdr)
        assertEquals(emptyList<String>(), p.hdrTypes)
    }

    @Test
    fun hdr10PhoneWithoutDolbyVisionDisplay() {
        val p = buildProfile(
            DeviceDescription(
                tv = false,
                decoders = listOf(
                    DecoderInfo("video/hevc", true, main10 = true),
                    // A decoder for profile 8, but a screen without Dolby Vision.
                    DecoderInfo("video/dolby-vision", true, doviProfiles = setOf(8)),
                ),
                passthrough = emptySet(),
                maxPcmChannels = 2,
                display = DisplayInfo(2400, 1080, hdr10 = true),
            ),
        )
        assertEquals(listOf("hevc", "hevc10"), p.videoCodecs)
        assertTrue(p.hdr)
        assertEquals(listOf("hdr10"), p.hdrTypes)
    }

    @Test
    fun boxOnDolbyVisionTvAndOnHdr10Tv() {
        val box = DeviceDescription(
            tv = true,
            decoders = listOf(
                DecoderInfo("video/avc", true),
                DecoderInfo("video/hevc", true, main10 = true),
                DecoderInfo("video/av01", true),
                DecoderInfo("video/dolby-vision", true, doviProfiles = setOf(5, 8)),
                DecoderInfo("video/dolby-vision", true, doviProfiles = setOf(8)),
            ),
            passthrough = setOf("ac3", "eac3"),
            maxPcmChannels = 8,
            display = DisplayInfo(3840, 2160, hdr10 = true, hlg = true, dolbyVision = true),
        )
        val dv = buildProfile(box)
        assertEquals(listOf("h264", "hevc", "hevc10", "av1", "dovi5", "dovi8"), dv.videoCodecs)
        assertEquals(listOf("hdr10", "hlg", "dovi"), dv.hdrTypes)
        assertTrue(dv.hdr)

        val hdr10 = buildProfile(box.copy(display = box.display.copy(dolbyVision = false)))
        assertEquals(listOf("h264", "hevc", "hevc10", "av1"), hdr10.videoCodecs)
        assertEquals(listOf("hdr10", "hlg"), hdr10.hdrTypes)

        val dvDecoderForProfile7 = box.copy(decoders = box.decoders + DecoderInfo("video/dolby-vision", true, doviProfiles = setOf(7)))
        assertEquals(listOf("dovi5", "dovi7", "dovi8"), buildProfile(dvDecoderForProfile7).videoCodecs.filter { it.startsWith("dovi") })
    }

    @Test
    fun olderServerGetsTheOldProfile() {
        val box = DeviceDescription(
            tv = true,
            decoders = listOf(
                DecoderInfo("video/avc", true),
                DecoderInfo("video/hevc", true, main10 = true),
                DecoderInfo("video/av01", true),
                DecoderInfo("video/dolby-vision", true, doviProfiles = setOf(5, 8)),
            ),
            passthrough = setOf("eac3"),
            maxPcmChannels = 8,
            display = DisplayInfo(3840, 2160, hdr10 = true, hlg = true, dolbyVision = true),
        )
        val level7 = HDR_PROFILE_API_LEVEL - 1
        // What the app sent before this plan: hevc10 for an HDR10 display, no dovi keys, no hdrTypes.
        assertEquals(
            PlaybackProfile(
                name = "androidtv",
                containers = listOf("mkv", "mp4", "webm", "mpegts"),
                videoCodecs = listOf("h264", "hevc", "hevc10", "av1"),
                audioCodecs = basicAudio + "eac3",
                maxWidth = 3840,
                maxHeight = 2160,
                maxAudioChannels = 8,
                hdr = true,
            ),
            buildProfile(box).forApiLevel(level7),
        )
        val sdrBox = box.copy(display = DisplayInfo(1920, 1080, hdr10 = false))
        val old = buildProfile(sdrBox).forApiLevel(level7)
        assertEquals(listOf("h264", "hevc", "av1"), old.videoCodecs)
        assertFalse(old.hdr)
        assertNull(old.hdrTypes)
    }

    @Test
    fun hdrTypesFromAndroid14() {
        val dv = 1
        val hdr10 = 2
        val hlg = 3
        // A box's UI mode at 4K60 and a 24 Hz mode with Dolby Vision: any mode's types count.
        assertEquals(setOf(dv, hdr10, hlg), shownHdrTypes(listOf(setOf(hdr10, hlg), setOf(dv, hdr10, hlg)), setOf(hdr10, hlg), setOf(hdr10, hlg)))
        // Dolby Vision turned off in the box's settings: the display's own list leaves it out of the current mode.
        val modes = listOf(setOf(dv, hdr10, hlg), setOf(dv, hdr10, hlg))
        assertEquals(setOf(hdr10, hlg), shownHdrTypes(modes, setOf(dv, hdr10, hlg), setOf(hdr10, hlg)))
        // No list of its own takes nothing away.
        assertEquals(setOf(dv, hdr10, hlg), shownHdrTypes(modes, setOf(dv, hdr10, hlg), null))
    }

    @Test
    fun decodedAudioCodecs() {
        assertEquals("eac3", decodedAudioCodec("audio/eac3"))
        assertEquals("eac3", decodedAudioCodec("audio/eac3-joc"))
        assertEquals("ac3", decodedAudioCodec("audio/ac3"))
        assertEquals(null, decodedAudioCodec("audio/mp4a-latm"))
    }
}
