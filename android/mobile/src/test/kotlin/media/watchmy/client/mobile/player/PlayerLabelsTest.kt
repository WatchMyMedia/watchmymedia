package media.watchmy.client.mobile.player

import media.watchmy.client.model.AudioOption
import media.watchmy.client.model.OptionLabel
import media.watchmy.client.model.PlaybackMode
import media.watchmy.client.model.PlayerTracks
import media.watchmy.client.model.StreamQuality
import media.watchmy.client.model.StreamVideo
import media.watchmy.client.model.SubtitleOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerLabelsTest {
    @Test
    fun `quality splits the cap from the bitrate`() {
        assertEquals(OptionLabel("Original"), qualityLabel(StreamQuality.Original))
        assertEquals(OptionLabel("1080p", "8 Mbps"), qualityLabel(StreamQuality.FullHd))
    }

    @Test
    fun `Automatic says what it sends now, and leads the chip`() {
        val auto = PlayerTracks(quality = StreamQuality.Auto, autoStep = StreamQuality.Hd, bitrate = 4_128_000, mode = PlaybackMode.Transcode, video = StreamVideo(1280, 720, null))
        assertEquals(OptionLabel("Automatic", "Now 720p · 4 Mbps"), qualityOption(StreamQuality.Auto, auto))
        assertEquals(OptionLabel("Automatic", "Now Original · 12 Mbps"), qualityOption(StreamQuality.Auto, auto.copy(autoStep = StreamQuality.Original, bitrate = 12_000_000)))
        assertEquals("picked a fixed step", OptionLabel("Automatic"), qualityOption(StreamQuality.Auto, auto.copy(quality = StreamQuality.Hd)))
        assertEquals(OptionLabel("360p", "1 Mbps"), qualityOption(StreamQuality.Low, auto))
        assertEquals("Auto · 720p", streamChip(auto))
        assertEquals("Auto · 720p", qualitySummary(auto))
        val original = auto.copy(autoStep = StreamQuality.Original, mode = PlaybackMode.Direct, video = StreamVideo(3840, 2160, "HDR10"))
        assertEquals("Auto · Direct play · 4K HDR", streamChip(original))
        assertEquals("Automatic", qualitySummary(original))
        assertEquals("1080p", qualitySummary(PlayerTracks(quality = StreamQuality.FullHd)))
    }

    @Test
    fun `the stream chip names the mode and any quality cap`() {
        assertNull(streamChip(PlayerTracks()))
        assertEquals("Direct play", streamChip(PlayerTracks(mode = PlaybackMode.Direct)))
        assertEquals("Transcode · 720p", streamChip(PlayerTracks(mode = PlaybackMode.Transcode, quality = StreamQuality.Hd)))
    }

    @Test
    fun `the stream chip names the video once the server says`() {
        assertEquals("Direct play · 4K HDR", streamChip(PlayerTracks(mode = PlaybackMode.Direct, video = StreamVideo(3840, 2160, "HDR10"))))
        assertEquals("Direct stream · 4K Dolby Vision", streamChip(PlayerTracks(mode = PlaybackMode.Remux, video = StreamVideo(3840, 1600, "Dolby Vision"))))
        assertEquals("Transcode · 720p", streamChip(PlayerTracks(mode = PlaybackMode.Transcode, quality = StreamQuality.Hd, video = StreamVideo(1280, 720, null))))
    }

    @Test
    fun `the picker says why the server is not sending the original`() {
        assertNull(streamReason(PlayerTracks()))
        assertNull(streamReason(PlayerTracks(mode = PlaybackMode.Direct, reasons = listOf("ignored"))))
        assertNull(streamReason(PlayerTracks(mode = PlaybackMode.Remux)))
        assertEquals(
            "Direct stream: client cannot play the mkv container.",
            streamReason(PlayerTracks(mode = PlaybackMode.Remux, reasons = listOf("client cannot play the mkv container"))),
        )
    }

    @Test
    fun `the bottom bar shows the chosen audio and subtitles`() {
        val s = PlayerTracks(
            audio = listOf(AudioOption(1, "English · EAC3 5.1", "en", true), AudioOption(2, "Spanish · AAC 2.0", "es", false)),
            selectedAudio = 2,
            subtitles = listOf(SubtitleOption("s4", "English", "en", false, false)),
        )
        assertEquals("Spanish", currentAudio(s))
        assertEquals("Off", currentSubtitle(s))
        assertEquals("English", currentSubtitle(s.copy(selectedSubtitle = "s4")))
    }

    @Test
    fun `picture in picture follows the video, within what Android allows`() {
        assertEquals(16 to 9, pipAspectRatio(null, null))
        assertEquals(16 to 9, pipAspectRatio(0f, 0f))
        assertEquals(1778 to 1000, pipAspectRatio(1920f, 1080f))
        assertEquals(2389 to 1000, pipAspectRatio(3000f, 1000f))
        assertEquals(419 to 1000, pipAspectRatio(1000f, 3000f))
    }
}
