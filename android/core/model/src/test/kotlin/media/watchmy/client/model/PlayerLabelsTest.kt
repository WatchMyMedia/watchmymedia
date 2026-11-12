package media.watchmy.client.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerLabelsTest {
    @Test fun `modes, bitrates and the video`() {
        assertEquals("Direct play", modeLabel(PlaybackMode.Direct))
        assertEquals("Direct stream", modeLabel(PlaybackMode.Remux))
        assertEquals("Audio transcode", modeLabel(PlaybackMode.AudioTranscode))
        assertEquals("Transcode", modeLabel(PlaybackMode.Transcode))
        assertEquals("4.5 Mbps", mbps(4_480_000))
        assertEquals("2.5 Mbps", mbps(2_460_000))
        assertEquals("12 Mbps", mbps(12_000_000))
        assertEquals("48 Mbps", mbps(48_200_000))
        assertNull(mbps(0))
        assertEquals("4K HDR", videoLabel(StreamVideo(3840, 2160, "HDR10")))
        assertEquals("4K HDR", videoLabel(StreamVideo(3840, 2160, "HLG")))
        // The phone used to say "4K DV"; the TV and the Apple apps write it out.
        assertEquals("4K Dolby Vision", videoLabel(StreamVideo(3840, 1600, DOLBY_VISION)))
        assertEquals("1080p", videoLabel(StreamVideo(1920, 1080, null)))
    }

    @Test fun `the next episode card`() {
        val next = UpNext(9102, 1308, 100, "Harbor Lights", 3, 8, "Squall Line", null)
        assertEquals("S03 E08 · Squall Line", next.cardTitle)
        assertEquals("Harbor Lights", next.cardMeta)
        assertEquals("Harbor Lights · season finale", next.copy(seasonFinale = true).cardMeta)
        assertEquals("Next episode in 10", upNextButton(10_000))
        assertEquals("Next episode in 7", upNextButton(6_250))
        assertEquals("Next episode in 1", upNextButton(1))
        assertEquals("Play now", upNextButton(null))
        assertEquals(7, countdownSeconds(6_250))
        assertEquals(0, countdownSeconds(0))
        assertEquals(0, countdownSeconds(-500))
        assertEquals(0f, UpNextPrompt(next, 10_000).countdownProgress, 0f)
        assertEquals(0.3f, UpNextPrompt(next, 7_000).countdownProgress, 1e-6f)
        assertEquals(0.5f, UpNextPrompt(next, 7_500, totalMs = 15_000).countdownProgress, 1e-6f)
        assertEquals(1f, UpNextPrompt(next, 0).countdownProgress, 0f)
        assertEquals(0f, UpNextPrompt(next, null).countdownProgress, 0f)
    }

    @Test fun `track labels split at the first dot`() {
        assertEquals(OptionLabel("English", "EAC3 5.1 (Default)"), splitLabel("English · EAC3 5.1 (Default)"))
        assertEquals(OptionLabel("English", "AAC 2.0 · Commentary"), splitLabel("English · AAC 2.0 · Commentary"))
        assertEquals(OptionLabel("English (SDH)"), splitLabel("English (SDH)"))
        assertEquals(OptionLabel("English"), splitLabel("English · "))
        assertEquals(OptionLabel("English", "EAC3 5.1 (Default)"), audioOptionLabel(AudioOption(1, "English · EAC3 5.1 (Default)", "en", true)))
        assertEquals(OptionLabel("Track 3"), audioOptionLabel(AudioOption(3, "Track 3", null, false)))
    }

    @Test fun `image subtitles always say they restart playback`() {
        val fromServer = SubtitleOption("s7", "English (PGS, burned in, restarts playback)", "en", false, true)
        assertEquals(OptionLabel("English", "PGS, burned in, restarts playback"), subtitleOptionLabel(fromServer))
        // The phone used to capitalise "Burned"; the TV and the Apple apps don't.
        assertEquals(OptionLabel("Español", "burned in, restarts playback"), subtitleOptionLabel(SubtitleOption("s8", "Español", "es", false, true)))
        assertEquals(OptionLabel("English", "PGS · burned in, restarts playback"), subtitleOptionLabel(SubtitleOption("s7", "English", "en", false, true, "PGS")))
        // The TV used to split "Español · PGS" at the dot.
        assertEquals(OptionLabel("Español · PGS", "burned in, restarts playback"), subtitleOptionLabel(SubtitleOption("s6", "Español · PGS", "es", false, true)))
    }

    @Test fun `text subtitles keep their label and flag forced ones`() {
        assertEquals(OptionLabel("English (SDH)"), subtitleOptionLabel(SubtitleOption("s5", "English (SDH)", "en", false, false)))
        assertEquals(OptionLabel("English", "Forced"), subtitleOptionLabel(SubtitleOption("s9", "English", "en", true, false)))
        assertEquals(OptionLabel("English · SDH", "SRT"), subtitleOptionLabel(SubtitleOption("s5", "English · SDH", "en", false, false, "SRT")))
        assertEquals(OptionLabel("Spanish", "ASS · Forced"), subtitleOptionLabel(SubtitleOption("s6", "Spanish", "es", true, false, "ASS")))
        assertEquals(OptionLabel("Spanish", "SRT · HI · Film.2020.WEB"), subtitleOptionLabel(SubtitleOption("d3", "Spanish", "es", false, false, "SRT", SubtitleSource.Online, "Film.2020.WEB", hearingImpaired = true)))
    }

    @Test fun `subtitles list the file's own first, then OpenSubtitles with the credit`() {
        val online = OnlineSubtitles(
            results = listOf(
                OnlineSubtitle("77", "en", "English", "Film.2020.1080p", hashMatch = true, hearingImpaired = false, forced = false, aiTranslated = false, downloads = 9),
                OnlineSubtitle("78", "pt-br", "Portuguese (Brazil)", "Film.2020.720p", hashMatch = false, hearingImpaired = false, forced = false, aiTranslated = true, downloads = 3),
            ),
            remaining = 0,
            resetAtMs = 0,
        )
        val s = PlayerUiState(
            subtitles = listOf(
                SubtitleOption("s2", "English", "en", false, false, "SRT"),
                SubtitleOption("d3", "Spanish", "es", false, false, "SRT", SubtitleSource.Online, "Film.2020.WEB", hearingImpaired = true),
            ),
            selectedSubtitle = "s2",
            online = online,
        )
        val entries = subtitleEntries(s.tracks)
        assertEquals(
            listOf(
                SubtitleEntry.Track(null, OptionLabel("Off"), false),
                SubtitleEntry.Heading("In this file"),
                SubtitleEntry.Track("s2", OptionLabel("English", "SRT"), true),
                SubtitleEntry.Heading("OpenSubtitles"),
                SubtitleEntry.Track("d3", OptionLabel("Spanish", "SRT · HI · Film.2020.WEB"), false),
                SubtitleEntry.Online("77", OptionLabel("English", "Matches this file · Film.2020.1080p"), enabled = false, downloading = false),
                SubtitleEntry.Online("78", OptionLabel("Portuguese (Brazil)", "AI translated · Film.2020.720p"), enabled = false, downloading = false),
            ),
            entries.take(7),
        )
        // Out of downloads: the note says when they resume, then the credit
        // ends the list.
        val notes = entries.drop(7).map { (it as SubtitleEntry.Note).text }
        assertEquals(2, notes.size)
        assertTrue(notes[0].startsWith("No downloads left today; they resume at "))
        assertEquals(OPEN_SUBTITLES_CREDIT, notes[1])

        // Nothing found: the group is its name, the note and the credit.
        assertEquals(
            listOf(SubtitleEntry.Heading("OpenSubtitles"), SubtitleEntry.Note("Nothing found online in these languages."), SubtitleEntry.Note(OPEN_SUBTITLES_CREDIT)),
            subtitleEntries(PlayerTracks(online = OnlineSubtitles())).drop(1),
        )

        // Without a provider the list is as it was.
        assertEquals(
            listOf(SubtitleEntry.Track(null, OptionLabel("Off"), true), SubtitleEntry.Track("s2", OptionLabel("English", "SRT"), false)),
            subtitleEntries(PlayerTracks(subtitles = listOf(SubtitleOption("s2", "English", "en", false, false, "SRT")))),
        )
    }

    private val duration = 52 * 60_000L

    @Test fun `intro and credits become bands, credits without an end run to the end`() {
        val bands = scrubberBands(
            listOf(Segment(SegmentKind.Intro, 60_000, 105_000), Segment(SegmentKind.Credits, 49 * 60_000L + 30_000, null)),
            duration,
        )
        assertEquals(2, bands.size)
        val (intro, credits) = bands
        assertEquals(SegmentKind.Intro, intro.kind)
        assertEquals(60_000f / duration, intro.start, 1e-6f)
        assertEquals(105_000f / duration, intro.end, 1e-6f)
        assertTrue(intro.opening)
        assertEquals((49 * 60_000f + 30_000) / duration, credits.start, 1e-6f)
        assertEquals(1f, credits.end, 0f)
        assertTrue(!credits.opening)
    }

    @Test fun `recaps are drawn like intros, the rest like credits`() {
        val kinds = SegmentKind.entries.associateWith { scrubberBands(listOf(Segment(it, 0, 10_000)), duration).single().opening }
        assertEquals(mapOf(SegmentKind.Intro to true, SegmentKind.Recap to true, SegmentKind.Credits to false, SegmentKind.Preview to false, SegmentKind.Commercial to false), kinds)
    }

    @Test fun `bands are clamped, and empty or outside ones dropped`() {
        val bands = scrubberBands(
            listOf(
                Segment(SegmentKind.Intro, -5_000, 30_000),
                Segment(SegmentKind.Credits, duration - 10_000, duration + 60_000),
                Segment(SegmentKind.Preview, 90_000, 90_000),
                Segment(SegmentKind.Commercial, duration + 1_000, null),
            ),
            duration,
        )
        assertEquals(listOf(0f to 30_000f / duration, (duration - 10_000f) / duration to 1f), bands.map { it.start to it.end })
        assertEquals(emptyList<ScrubberBand>(), scrubberBands(listOf(Segment(SegmentKind.Intro, 0, 10_000)), 0))
    }

    @Test fun `fractions clamp to the track`() {
        assertEquals(0.5f, fractionOf(50, 100), 0f)
        assertEquals(1f, fractionOf(150, 100), 0f)
        assertEquals(0f, fractionOf(-10, 100), 0f)
        assertEquals(0f, fractionOf(5_000, 0), 0f)
    }
}
