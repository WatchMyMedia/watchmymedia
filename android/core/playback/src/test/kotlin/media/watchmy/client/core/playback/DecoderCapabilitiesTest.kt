package media.watchmy.client.core.playback

import media.watchmy.client.core.playback.player.FailedVideo
import media.watchmy.client.core.playback.player.PlayerFailure
import media.watchmy.client.core.playback.profile.DecoderInfo
import media.watchmy.client.core.playback.profile.DeviceDescription
import media.watchmy.client.core.playback.profile.DisplayInfo
import media.watchmy.client.core.playback.profile.ProfileProvider
import media.watchmy.client.core.playback.profile.VideoSize
import media.watchmy.client.core.playback.profile.buildProfile
import media.watchmy.client.core.playback.profile.videoLimit
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.PlaybackMode
import media.watchmy.client.model.PlayerStatus
import media.watchmy.client.model.StreamProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the device can decode: the size limit
 * is the decoders', not the display's, hardware first; and a video decoder
 * that fails on a stream is asked around once a viewing.
 */
class DecoderCapabilitiesTest {
    private val uhd = VideoSize(3840, 2160)
    private val dci = VideoSize(4096, 2160)
    private val fullHd = VideoSize(1920, 1080)

    private fun device(display: DisplayInfo, vararg decoders: DecoderInfo) =
        DeviceDescription(tv = false, decoders = decoders.toList(), passthrough = emptySet(), maxPcmChannels = 2, display = display)

    @Test
    fun `a phone whose hardware decodes 4K plays 4K files whatever its screen`() {
        val p = buildProfile(
            device(
                DisplayInfo(2400, 1080, hdr10 = false),
                DecoderInfo("video/avc", hardware = true, maxSize = dci),
                DecoderInfo("video/hevc", hardware = true, main10 = true, maxSize = uhd),
            ),
        )
        assertEquals(listOf("h264", "hevc", "hevc10"), p.videoCodecs)
        assertEquals(4096, p.maxWidth)
        assertEquals(2160, p.maxHeight)
    }

    @Test
    fun `a box whose decoders top out below its display is sent what they take`() {
        val p = buildProfile(
            device(
                DisplayInfo(3840, 2160, hdr10 = true),
                DecoderInfo("video/avc", hardware = true, maxSize = fullHd),
                DecoderInfo("video/hevc", hardware = true, maxSize = fullHd),
            ),
        )
        assertEquals(1920, p.maxWidth)
        assertEquals(1080, p.maxHeight)
    }

    @Test
    fun `hardware decoders count first, and a software one counts for no more than 1080p`() {
        val d = device(
            DisplayInfo(1920, 1080, hdr10 = false),
            DecoderInfo("video/avc", hardware = true, maxSize = fullHd),
            DecoderInfo("video/avc", hardware = false, maxSize = VideoSize(4096, 4096)),
            // Main 10 only in software, beside a hardware decoder without it: not offered.
            DecoderInfo("video/hevc", hardware = true, maxSize = uhd),
            DecoderInfo("video/hevc", hardware = false, main10 = true, maxSize = VideoSize(4096, 4096)),
            // No hardware VP9 decoder: a software one's declared 4K is 1080p.
            DecoderInfo("video/x-vnd.on2.vp9", hardware = false, maxSize = VideoSize(4096, 4096)),
        )
        assertEquals(fullHd, d.videoLimit("video/avc"))
        assertEquals(uhd, d.videoLimit("video/hevc"))
        assertEquals(fullHd, d.videoLimit("video/x-vnd.on2.vp9"))
        assertNull(d.videoLimit("video/av01"))
        val p = buildProfile(d)
        assertEquals(listOf("h264", "hevc"), p.videoCodecs)
        assertEquals("The largest any offered codec's decoders take", 3840, p.maxWidth)
    }

    @Test
    fun `decoders that don't say keep the display's size`() {
        val p = buildProfile(device(DisplayInfo(1280, 720, hdr10 = false), DecoderInfo("video/avc", hardware = true)))
        assertEquals(1280, p.maxWidth)
        assertEquals(720, p.maxHeight)
    }

    @Test
    fun `a failed video decoder narrows the next decision once`() {
        val hevc4k = FailedVideo("video/hevc", 3840, 2160)
        assertEquals(VideoNarrowing.Within(fullHd), videoNarrowing(hevc4k, fullHd, copied = true))
        assertEquals("A transcode larger than the decoder takes, too", VideoNarrowing.Within(fullHd), videoNarrowing(hevc4k, fullHd, copied = false))
        assertEquals("Within its size: the codec goes", VideoNarrowing.LeaveOut(setOf("hevc", "hevc10")), videoNarrowing(hevc4k, uhd, copied = true))
        assertEquals(VideoNarrowing.LeaveOut(setOf("h264")), videoNarrowing(FailedVideo("video/avc", 1920, 1080), null, copied = true))
        assertNull("A transcode within the decoder's size is an error as before", videoNarrowing(hevc4k, uhd, copied = false))
        assertNull(videoNarrowing(FailedVideo("video/mpeg2", 720, 576), null, copied = true))

        val left = TV_PROFILE.narrowed(VideoNarrowing.LeaveOut(setOf("hevc", "hevc10")))
        assertEquals(listOf("h264"), left.videoCodecs)
        val smaller = TV_PROFILE.narrowed(VideoNarrowing.Within(fullHd))
        assertEquals(1920, smaller.maxWidth)
        assertEquals(1080, smaller.maxHeight)
        assertEquals(TV_PROFILE, TV_PROFILE.narrowed(null))
    }

    /** A TV that lists HEVC at 1080p only. */
    private val provider = object : ProfileProvider {
        override fun current() = TV_PROFILE
        override fun videoLimit(mimeType: String) = if (mimeType == "video/hevc") fullHd else null
    }

    private fun Harness.failVideo(mime: String, width: Int, height: Int) {
        player.fail(PlayerFailure(PlayerFailure.Kind.Decoder, "DECODING_FAILED", video = FailedVideo(mime, width, height)))
        run()
    }

    @Test
    fun `a direct play larger than the decoder takes is asked for again at its size, once`() {
        val h = Harness(profileProvider = provider)
        h.start(PlayRequest(1, 0, "Lighthouse")) { r ->
            if (r.profile.maxHeight == 1080) decision(sessionId = "s2", mode = PlaybackMode.Transcode)
            else decision(protocol = StreamProtocol.File)
        }
        h.advance(5_000)
        h.failVideo("video/hevc", 3840, 2160)
        assertEquals(2, h.gateway.decisions.size)
        val again = h.gateway.decisions.last()
        assertEquals(1920, again.profile.maxWidth)
        assertEquals(1080, again.profile.maxHeight)
        assertEquals("From where it was", 5_000L, again.startMs)
        assertEquals("http://srv/api/playback/s2/master.m3u8?access_token=tok1", h.player.loads.last().url)
        assertNull(h.state.error)
        // Once a viewing: another failure is an error.
        h.failVideo("video/avc", 1920, 1080)
        assertEquals(2, h.gateway.decisions.size)
        assertNotNull(h.state.error)
        assertEquals(PlayerStatus.Error, h.state.status)
    }

    @Test
    fun `a remux whose decoder fails within its size is asked for again without the codec, for this viewing only`() {
        val h = Harness(profileProvider = provider)
        h.start(PlayRequest(1, 0, "Lighthouse")) { r ->
            if ("hevc" in r.profile.videoCodecs) decision(mode = PlaybackMode.Remux) else decision(sessionId = "s2", mode = PlaybackMode.Transcode)
        }
        h.failVideo("video/hevc", 1280, 720)
        assertEquals(2, h.gateway.decisions.size)
        assertFalse("hevc" in h.gateway.decisions.last().profile.videoCodecs)
        assertEquals("The old session ends", listOf("s1"), h.gateway.stoppedSessions)
        // A track change keeps it out.
        h.controller.selectAudio(2)
        h.run()
        assertFalse("hevc" in h.gateway.decisions.last().profile.videoCodecs)
        // The next file starts with the whole profile again.
        h.start(PlayRequest(2, 0, "Harbor Lights")) { decision(mode = PlaybackMode.Remux) }
        assertTrue("hevc" in h.gateway.decisions.last().profile.videoCodecs)
    }

    @Test
    fun `a transcode the decoder should take fails as before`() {
        val h = Harness(profileProvider = provider)
        h.start(PlayRequest(1, 0, "Lighthouse"), decision(mode = PlaybackMode.Transcode))
        h.failVideo("video/avc", 1280, 720)
        assertEquals(1, h.gateway.decisions.size)
        assertEquals(PlayerStatus.Error, h.state.status)
    }
}
