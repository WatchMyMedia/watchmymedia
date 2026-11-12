package media.watchmy.client.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaLabelsTest {
    private val uhd = MediaVersion(
        1, "Film (2026) - 2160p.mkv", 61_000_000_000L, "MKV", "hevc", 3840, 2160, DOLBY_VISION, "TrueHD 7.1 · English", null,
        audio = listOf(
            AudioOption(1, "English · TrueHD 7.1 (Default)", "eng", true),
            AudioOption(2, "English · AC3 5.1", "eng", false),
            AudioOption(3, "English · AAC Stereo · Commentary", "eng", false),
            AudioOption(4, "French · AC3 5.1", "fre", false),
        ),
        subtitles = listOf(
            SubtitleOption("s5", "English", "eng", forced = false, burnInOnly = false, format = "SRT"),
            SubtitleOption("s6", "English · SDH", "eng", forced = false, burnInOnly = false, format = "SRT"),
            SubtitleOption("s7", "English", "eng", forced = false, burnInOnly = true, format = "PGS"),
            SubtitleOption("s8", "French (Forced)", "fre", forced = true, burnInOnly = false, format = "ASS"),
        ),
        dvProfile = 8,
    )

    @Test fun `resolution counts a wide frame by its width, as the server does`() {
        assertEquals("4K", resolutionLabel(3840, 2160))
        // The TV used to call a scope film 1080p from its height.
        assertEquals("4K", resolutionLabel(3840, 1600))
        assertEquals("1080p", resolutionLabel(1920, 1080))
        assertEquals("1080p", resolutionLabel(1920, 800))
        // The phone used to call this 1080p from a width of 1700; Apple and the server start at 1800.
        assertEquals("720p", resolutionLabel(1728, 720))
        assertEquals("720p", resolutionLabel(1280, 720))
        assertEquals("SD", resolutionLabel(720, 576))
        assertEquals("4K", resolutionLabel(null, 2160))
        assertNull(resolutionLabel(null, null))
        assertNull(resolutionLabel(0, 0))
    }

    @Test fun `hdr, codec and audio`() {
        assertEquals(DOLBY_VISION, hdrLabel("dolbyvision"))
        assertEquals(DOLBY_VISION, hdrLabel("Dolby Vision"))
        // The TV used to say "HDR".
        assertEquals("HDR10", hdrLabel("HDR10"))
        assertEquals("HDR10+", hdrLabel("hdr10plus"))
        assertEquals("HLG", hdrLabel("hlg"))
        assertNull(hdrLabel("sdr"))
        assertNull(hdrLabel(" "))
        assertEquals("HEVC", codecLabel("h265"))
        assertEquals("H.264", codecLabel("h264"))
        assertEquals("MPEG-2", codecLabel("mpeg2video"))
        assertEquals("AV1", codecLabel("av1"))
        assertNull(codecLabel(null))
        assertEquals("TrueHD 7.1", audioLabel("TrueHD 7.1 · English"))
        assertNull(audioLabel(" · English"))
        assertEquals("5.1", channelsLabel("EAC3 5.1 · English"))
        assertNull(channelsLabel("AAC Stereo"))
    }

    @Test fun `a version's title`() {
        assertEquals("4K Dolby Vision", versionTitle(uhd))
        assertEquals("4K", versionTitle(uhd.copy(width = 3840, height = 1600, hdr = null)))
        assertEquals("1080p", versionTitle(uhd.copy(width = 1920, height = 1080, hdr = null)))
        assertEquals("MKV", versionTitle(uhd.copy(width = null, height = null, hdr = null)))
        assertEquals("Version", versionTitle(uhd.copy(width = null, height = null, hdr = null, container = null)))
    }

    @Test fun `a version's tags, with Dolby Vision written out on TV`() {
        assertEquals(listOf("MKV", "HEVC", "DV P8", "TrueHD 7.1", "56.8 GB"), versionTags(uhd))
        assertEquals(listOf("MKV", "HEVC", "Dolby Vision · P8", "TrueHD 7.1", "56.8 GB"), versionTags(uhd, spelledOut = true))
        assertEquals(listOf("MKV", "HEVC", "DV", "TrueHD 7.1", "56.8 GB"), versionTags(uhd.copy(dvProfile = null)))
        // The container is in capitals on both.
        assertEquals("MP4", versionTags(uhd.copy(container = "mp4"), spelledOut = true).first())
        assertEquals(listOf("MKV", "HEVC", "HDR10", "TrueHD 7.1", "56.8 GB"), versionTags(uhd.copy(hdr = "HDR10"), spelledOut = true))
    }

    @Test fun `episode and movie tags`() {
        assertEquals(listOf("4K", "HEVC", "DV", "TrueHD 7.1"), episodeTags(uhd))
        // The TV used to say "HDR10" here, and "1080p" for a scope film.
        assertEquals(listOf("4K", "HEVC", "HDR", "TrueHD 7.1"), episodeTags(uhd.copy(hdr = "HDR10", height = 1600)))
        val movie = MovieDetail(
            1, 1, "Film", 2026, null, "", null, 112, emptyList(), null, null, null, "4K", true, listOf(uhd), emptyList(), null,
        )
        assertEquals(listOf("4K", "Dolby Vision", "7.1"), movie.tags())
        assertEquals(listOf("4K", "HDR"), movie.copy(versions = emptyList()).tags())
    }

    @Test fun `tracks list three of each, then how many more`() {
        assertEquals(
            listOf(
                TrackLine("Audio", "English · TrueHD 7.1", default = true),
                TrackLine(null, "English · AC3 5.1"),
                TrackLine(null, "English · AAC Stereo · Commentary"),
                TrackLine(null, "+ 1 more"),
                TrackLine("Subtitles", "English · SRT"),
                TrackLine(null, "English · SDH · SRT"),
                TrackLine(null, "English · PGS", burnIn = true),
                TrackLine(null, "+ 1 more"),
            ),
            versionTracks(uhd),
        )
        // Without track lists, the default audio's summary; nothing at all without one (the TV used to say "Unknown").
        assertEquals(listOf(TrackLine("Audio", "TrueHD 7.1 · English", default = true)), versionTracks(uhd.copy(audio = emptyList(), subtitles = emptyList())))
        assertEquals(emptyList<TrackLine>(), versionTracks(uhd.copy(audio = emptyList(), subtitles = emptyList(), audioSummary = null)))
    }
}
