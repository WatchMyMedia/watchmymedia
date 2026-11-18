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

    @Test fun `pcm and maxPcmChannels go only to servers that read them`() {
        val soundbar = dolbyVisionTv.copy(audioCodecs = listOf("aac", "pcm", "eac3"), maxAudioChannels = 8, maxPcmChannels = 2)
        assertSame(soundbar, soundbar.forApiLevel(PCM_PROFILE_API_LEVEL))
        val older = soundbar.forApiLevel(PCM_PROFILE_API_LEVEL - 1)
        assertEquals(listOf("aac", "eac3"), older.audioCodecs)
        assertNull(older.maxPcmChannels)
        assertEquals(8, older.maxAudioChannels)
        // pcm alone is taken out too, and the rest of the old profile still applies below the HDR level.
        assertEquals(listOf("aac"), dolbyVisionTv.copy(audioCodecs = listOf("aac", "pcm")).forApiLevel(PCM_PROFILE_API_LEVEL - 1).audioCodecs)
        assertEquals(
            dolbyVisionTv.forApiLevel(HDR_PROFILE_API_LEVEL - 1).copy(audioCodecs = listOf("aac", "eac3"), maxAudioChannels = 8),
            soundbar.forApiLevel(HDR_PROFILE_API_LEVEL - 1),
        )
    }
}
