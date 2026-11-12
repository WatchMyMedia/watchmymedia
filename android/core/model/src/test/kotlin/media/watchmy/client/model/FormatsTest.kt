package media.watchmy.client.model

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatsTest {
    @Test
    fun formats() {
        assertEquals("S03 E07", episodeCode(3, 7))
        assertEquals("52:10", formatClock(52 * 60_000L + 10_000))
        assertEquals("1:02:03", formatClock(3_723_000))
        assertEquals("1 h 12 min", formatDuration(72 * 60_000L))
        assertEquals("23 min left", formatRemaining(23 * 60_000L))
        assertEquals("18.4 TB", formatBytes(18_400_000_000_000L * 1024 / 1000 * 1024 / 1000 * 1024 / 1000 * 1024 / 1000))
        assertEquals("12 min ago", formatAgo(0, 12 * 60_000L))
        assertEquals("yesterday", formatAgo(0, 30 * 3_600_000L))
        assertEquals("v0.4.1", versionLabel("v0.4.1"))
        assertEquals("v0.4.1", versionLabel("0.4.1"))
    }

    @Test
    fun streamQuality() {
        assertEquals("4K", StreamVideo(3840, 2160, null).quality)
        assertEquals("4K", StreamVideo(3840, 1600, null).quality)
        assertEquals("1080p", StreamVideo(1920, 1080, null).quality)
        assertEquals("1080p", StreamVideo(1920, 800, null).quality)
        assertEquals("720p", StreamVideo(1280, 720, null).quality)
        assertEquals("SD", StreamVideo(720, 480, null).quality)
    }

    @Test
    fun upNextCountdown() {
        val next = UpNext(2, 20, 1, "Show", 1, 2, "Two", null)
        assertEquals(0f, UpNextPrompt(next, null).countdownProgress, 0f)
        assertEquals(0.25f, UpNextPrompt(next, 7_500).countdownProgress, 1e-6f)
        assertEquals(1f, UpNextPrompt(next, 0, totalMs = 5_000).countdownProgress, 0f)
    }

    @Test
    fun preferencesDefaults() {
        val p = PlaybackPreferences()
        assertEquals(SkipMode.Ask, p.skipMode(SegmentKind.Intro))
        assertEquals(SkipMode.Ask, p.skipMode(SegmentKind.Commercial))
    }

    @Test
    fun playbackPlanLabels() {
        val video = StreamVideo(3840, 2160, DOLBY_VISION)
        assertEquals("Direct play", PlaybackPlan(1, PlaybackMode.Direct, video, null, null, null).label)
        assertEquals("Remux to fMP4", PlaybackPlan(1, PlaybackMode.Remux, video, null, null, null).label)
        assertEquals("Remux to fMP4 · audio to AAC 5.1", PlaybackPlan(1, PlaybackMode.AudioTranscode, video, null, "AAC", 6).label)
        assertEquals("Remux to fMP4 · audio to AAC", PlaybackPlan(1, PlaybackMode.AudioTranscode, video, null, "AAC", 1).label)
        assertEquals(
            "Transcode to 1080p H.264 · audio to AAC Stereo",
            PlaybackPlan(1, PlaybackMode.Transcode, StreamVideo(1920, 1080, null), "H.264", "AAC", 2).label,
        )
        assertEquals("Transcode to 720p", PlaybackPlan(1, PlaybackMode.Transcode, StreamVideo(1280, 720, null), null, null, null).label)
    }

    @Test
    fun dolbyVisionTag() {
        val v = MediaVersion(1, "a.mkv", 1, "MKV", "hevc", 3840, 2160, DOLBY_VISION, null, null, dvProfile = 8)
        assertEquals("DV P8", v.hdrTag)
        assertEquals("DV", v.copy(dvProfile = null).hdrTag)
        assertEquals("DV", v.copy(dvProfile = 0).hdrTag)
        assertEquals("HDR10", v.copy(hdr = "HDR10", dvProfile = null).hdrTag)
        assertEquals(null, v.copy(hdr = null).hdrTag)
        // The TV writes Dolby Vision out.
        assertEquals("Dolby Vision · P8", v.hdrTagSpelledOut)
        assertEquals(DOLBY_VISION, v.copy(dvProfile = null).hdrTagSpelledOut)
        assertEquals("HDR10", v.copy(hdr = "HDR10").hdrTagSpelledOut)
    }
}
