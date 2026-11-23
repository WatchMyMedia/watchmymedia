package media.watchmy.client.tv.player

import media.watchmy.client.model.AudioOption
import media.watchmy.client.model.OnlineSubtitle
import media.watchmy.client.model.OnlineSubtitles
import media.watchmy.client.model.PlaybackMode
import media.watchmy.client.model.PlayerTracks
import media.watchmy.client.model.StreamQuality
import media.watchmy.client.model.StreamVideo
import media.watchmy.client.model.SubtitleOption
import media.watchmy.client.model.SubtitleSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerLabelsTest {
    private val state = PlayerTracks(
        audio = listOf(
            AudioOption(1, "English · EAC3 5.1 (Default)", "en", true),
            AudioOption(2, "English · AAC 2.0 · Commentary", "en", false),
        ),
        selectedAudio = 1,
        subtitles = listOf(
            SubtitleOption("s4", "English", "en", false, false),
            SubtitleOption("s6", "Español · PGS", "es", false, true),
            SubtitleOption("s7", "English (PGS, burned in, restarts playback)", "en", false, true),
            SubtitleOption("s8", "English · SRT", "en", true, false),
        ),
        mode = PlaybackMode.Remux,
    )

    @Test
    fun theButtonsSmallText() {
        assertEquals("English", audioSummary(state))
        assertNull(audioSummary(state.copy(audio = emptyList())))
        assertEquals("Off", subtitleSummary(state))
        assertEquals("Español", subtitleSummary(state.copy(selectedSubtitle = "s6")))
        assertEquals("Original", qualitySummary(StreamQuality.Original))
        assertEquals("720p", qualitySummary(StreamQuality.Hd))
    }

    @Test
    fun subtitlesStartWithOffAndSayInWordsWhichRestartPlayback() {
        val rows = subtitleRows(state)
        // The language alone; the format is in Playback info.
        assertEquals(listOf("Off", "English", "Español", "English", "English"), rows.map { it.label })
        assertEquals(listOf(true, false, false, false, false), rows.map { it.selected })
        assertEquals(PickerChoice.Subtitle(null), rows.first().choice)
        assertEquals(null, rows[0].detail)
        assertEquals("In this file", rows[1].detail)
        assertEquals("Drawn into the picture, so the video starts again", rows[2].detail)
        assertEquals("Drawn into the picture, so the video starts again", rows[3].detail)
        assertEquals("Only the parts in another language", rows[4].detail)
        assertEquals(listOf(false, false, true, false, false), subtitleRows(state.copy(selectedSubtitle = "s6")).map { it.selected })
    }

    @Test
    fun audioRowsMarkTheSelection() {
        val rows = audioRows(state.copy(selectedAudio = 2))
        // In words: how it sounds and what it is, not the format.
        assertEquals(listOf("5.1 surround", "Commentary · stereo"), rows.map { it.detail })
        assertEquals(listOf("English", "English"), rows.map { it.label })
        assertEquals(listOf(false, true), rows.map { it.selected })
        assertEquals(PickerChoice.Audio(2), rows[1].choice)
    }

    @Test
    fun qualityRowsAreOriginalAndTheSteps() {
        val rows = qualityRows(state)
        assertEquals(listOf("Original", "4K", "1080p", "720p", "480p", "360p"), rows.map { it.label })
        assertEquals("The file as it is", rows[0].detail)
        assertEquals("20 Mbps", rows[1].detail)
        assertEquals("1 Mbps", rows[5].detail)
        assertEquals(listOf(true, false, false, false, false, false), rows.map { it.selected })
        assertEquals("The file as it is · 4K HDR", qualityRows(state.copy(video = StreamVideo(3840, 2160, "HDR10")))[0].detail)
        assertEquals("The file as it is", qualityRows(state.copy(quality = StreamQuality.Hd, video = StreamVideo(3840, 2160, "HDR10")))[0].detail)
    }

    @Test
    fun automaticComesFirstAndSaysWhatItSendsNow() {
        val auto = state.copy(quality = StreamQuality.Auto, qualities = StreamQuality.offered(auto = true), autoStep = StreamQuality.Hd, bitrate = 4_128_000)
        val rows = qualityRows(auto)
        assertEquals(listOf("Automatic", "Original", "4K", "1080p", "720p", "480p", "360p"), rows.map { it.label })
        assertEquals("Now 720p · 4 Mbps", rows[0].detail)
        assertTrue(rows[0].selected)
        assertEquals("Now the original · 4K HDR", qualityRows(auto.copy(autoStep = StreamQuality.Original, video = StreamVideo(3840, 2160, "HDR10")))[0].detail)
        assertEquals("Now the original", qualityRows(auto.copy(autoStep = StreamQuality.Original))[0].detail)
        assertEquals("The original, stepping down only if it has to", qualityRows(auto.copy(quality = StreamQuality.Hd, autoStep = null))[0].detail)
        assertEquals("Auto · 720p", qualitySummary(auto))
        assertEquals("Automatic", qualitySummary(auto.copy(autoStep = StreamQuality.Original)))
    }

    @Test
    fun pickerSectionsLeaveOutEmptyOnes() {
        assertEquals(listOf(PickerSection.Audio, PickerSection.Subtitles, PickerSection.Quality), pickerSections(state).map { it.first })
        assertEquals(listOf(PickerSection.Quality), pickerSections(PlayerTracks()).map { it.first })
    }

    @Test
    fun `the Subtitles button shows for the file's own tracks or what was found online`() {
        val own = SubtitleOption("s2", "English", "en", false, false)
        val found = OnlineSubtitles(
            results = listOf(OnlineSubtitle("77", "en", "English", "Glass.Tide.2023", hashMatch = false, hearingImpaired = false, forced = false, aiTranslated = false, downloads = 4)),
        )
        // No tracks of its own and no answer yet (or no provider): nothing to pick.
        assertEquals(false, offersSubtitles(PlayerTracks()))
        assertTrue(offersSubtitles(PlayerTracks(subtitles = listOf(own))))
        // None of its own, but the search found some: the button leads to them.
        assertTrue(offersSubtitles(PlayerTracks(online = found)))
        // The search answered with nothing: the button still shows, and the picker says so.
        assertTrue(offersSubtitles(PlayerTracks(online = OnlineSubtitles())))
        // The button and the picker's section agree.
        for (t in listOf(PlayerTracks(), PlayerTracks(subtitles = listOf(own)), PlayerTracks(online = found))) {
            assertEquals(offersSubtitles(t), pickerSections(t).any { it.first == PickerSection.Subtitles })
        }
    }

    @Test
    fun `the file's own subtitles come first, then online ones with the credit`() {
        val online = OnlineSubtitles(
            results = listOf(
                OnlineSubtitle("77", "en", "English", "Film.2020.1080p", hashMatch = true, hearingImpaired = true, forced = false, aiTranslated = false, downloads = 9),
                OnlineSubtitle("78", "es", "Spanish", "Film.2020.720p", hashMatch = false, hearingImpaired = false, forced = false, aiTranslated = false, downloads = 3, trackId = "d3"),
            ),
            downloading = "77",
        )
        val s = PlayerTracks(
            subtitles = listOf(
                SubtitleOption("s2", "English", "en", false, false, "SRT"),
                SubtitleOption("d3", "Spanish", "es", false, false, "SRT", SubtitleSource.Online, "Film.2020.720p"),
            ),
            selectedSubtitle = "d3",
            online = online,
        )
        val rows = subtitleRows(s)
        assertEquals(listOf<PickerChoice>(PickerChoice.Subtitle(null), PickerChoice.Subtitle("s2"), PickerChoice.Subtitle("d3"), PickerChoice.Online("77")), rows.map { it.choice })
        assertEquals(listOf(null, "In this file", "OpenSubtitles", null), rows.map { it.heading })
        assertEquals(listOf(null, null, null, "Subtitles provided by OpenSubtitles.com"), rows.map { it.footer })
        assertEquals("Downloading… · Matches this file · HI · Film.2020.1080p", rows[3].detail)
        assertEquals("Downloaded · Film.2020.720p", rows[2].detail)
        // Under group names, a track of the file's own says nothing more.
        assertEquals(null, rows[1].detail)
        assertEquals(listOf(false, false, true, false), rows.map { it.selected })

        // Only admins may download here: the results show, faint, with the reason.
        val locked = subtitleRows(s.copy(online = online.copy(canDownload = false, downloading = null)))
        assertEquals(false, locked[3].enabled)
        assertEquals("Only admins can download subtitles on this server. Subtitles provided by OpenSubtitles.com", locked[3].footer)

        // Nothing found: one line that picks nothing says so, under the file's own.
        val none = subtitleRows(PlayerTracks(subtitles = listOf(SubtitleOption("s2", "English", "en", false, false)), online = OnlineSubtitles()))
        assertEquals(listOf(null, "In this file", "OpenSubtitles"), none.map { it.heading })
        assertEquals(PickerChoice.Nothing, none[2].choice)
        assertEquals("Nothing found online in these languages.", none[2].label)
        assertEquals(false, none[2].enabled)

        // No provider: the list is as it was, without group names.
        assertEquals(listOf(null, null), subtitleRows(PlayerTracks(subtitles = listOf(SubtitleOption("s2", "English", "en", false, false)))).map { it.heading })
        // Results alone are enough to show the section.
        assertEquals(listOf(PickerSection.Subtitles, PickerSection.Quality), pickerSections(PlayerTracks(online = online)).map { it.first })
    }
}

