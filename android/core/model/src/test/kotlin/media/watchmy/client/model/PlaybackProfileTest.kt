package media.watchmy.client.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PlaybackProfileTest {
    private val dolbyVisionTv = PlaybackProfile(
        name = "androidtv",
        containers = listOf("mkv", "mp4", "webm", "mpegts"),
        videoCodecs = listOf("h264", "hevc", "hevc10", "av1", "dovi5", "dovi8"),
        audioCodecs = listOf("aac", "eac3"),
        hdr = true,
        hdrTypes = listOf("hdr10", "hlg", "dovi"),
    )

    @Test fun `a server that reads hdrTypes gets the profile as it is`() {
        assertSame(dolbyVisionTv, dolbyVisionTv.forApiLevel(HDR_PROFILE_API_LEVEL))
        assertSame(dolbyVisionTv, dolbyVisionTv.forApiLevel(HDR_PROFILE_API_LEVEL + 1))
    }

    @Test fun `an older server gets no hdrTypes, no dovi keys, and hevc10 only for an HDR10 screen`() {
        val old = dolbyVisionTv.forApiLevel(HDR_PROFILE_API_LEVEL - 1)
        assertNull(old.hdrTypes)
        assertEquals(listOf("h264", "hevc", "hevc10", "av1"), old.videoCodecs)
        assertEquals(dolbyVisionTv.copy(videoCodecs = old.videoCodecs, hdrTypes = null), old)

        val hlgOnly = dolbyVisionTv.copy(hdrTypes = listOf("hlg")).forApiLevel(1)
        assertEquals(listOf("h264", "hevc", "av1"), hlgOnly.videoCodecs)
        val sdr = dolbyVisionTv.copy(videoCodecs = listOf("h264", "hevc", "hevc10"), hdr = false, hdrTypes = emptyList()).forApiLevel(0)
        assertEquals(listOf("h264", "hevc"), sdr.videoCodecs)
        assertNull(sdr.hdrTypes)
    }

    @Test fun `a profile without hdrTypes is already the old one`() {
        val old = dolbyVisionTv.copy(videoCodecs = listOf("h264", "hevc10"), hdrTypes = null)
        assertSame(old, old.forApiLevel(1))
    }

    @Test fun `selectsAudioTrack goes only to servers that read it`() {
        val selecting = dolbyVisionTv.copy(selectsAudioTrack = true)
        assertSame(selecting, selecting.forApiLevel(AUDIO_LANGUAGES_API_LEVEL))
        assertEquals(dolbyVisionTv, selecting.forApiLevel(AUDIO_LANGUAGES_API_LEVEL - 1))
        // Below the HDR level too, with the HDR fields made old as well.
        assertEquals(dolbyVisionTv.forApiLevel(HDR_PROFILE_API_LEVEL - 1), selecting.forApiLevel(HDR_PROFILE_API_LEVEL - 1))
    }
}
