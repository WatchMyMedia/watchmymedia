package media.watchmy.client.tv.player

import media.watchmy.client.model.AudioOption
import media.watchmy.client.model.PlaybackMode
import media.watchmy.client.model.PlayerStatus
import media.watchmy.client.model.PlayerTracks
import media.watchmy.client.model.PlayerUiState
import media.watchmy.client.model.StreamQuality
import media.watchmy.client.model.StreamVideo
import media.watchmy.client.model.SubtitleOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The player's words for what a viewer meets: tracks in words, Playback info, and why it stopped. */
class PlayerWordsTest {
    private val tracks = PlayerTracks(
        audio = listOf(AudioOption(1, "English · EAC3 5.1 (Default)", "en", true), AudioOption(2, "Spanish · AAC 2.0", "es", false, channels = 2)),
        selectedAudio = 1,
        subtitles = listOf(SubtitleOption("s4", "English", "en", false, false, "SRT")),
        mode = PlaybackMode.Direct,
        video = StreamVideo(3840, 2160, "HDR10"),
        quality = StreamQuality.Auto,
        qualities = StreamQuality.offered(auto = true),
        autoStep = StreamQuality.Original,
        bitrate = 24_600_000,
    )

    @Test fun `a track's channels are said in words`() {
        assertEquals("5.1 surround", audioWords(tracks.audio[0]))
        assertEquals("stereo", audioWords(tracks.audio[1]))
        assertEquals("mono", audioWords(AudioOption(3, "French", "fr", false, channels = 1)))
        assertEquals("7.1 surround", audioWords(AudioOption(4, "English · TrueHD 7.1", "en", false)))
        assertNull(audioWords(AudioOption(5, "English", "en", false)))
    }

    @Test fun `Playback info says what is happening in a sentence, then the details`() {
        assertEquals("Playing the file as it is. Nothing is being converted.", playbackStory(tracks))
        val lowered = tracks.copy(mode = PlaybackMode.Transcode, autoStep = StreamQuality.Hd, video = StreamVideo(1280, 720, null))
        assertEquals("The server is converting the video, because the connection couldn’t keep up with the original.", playbackStory(lowered))
        assertEquals(
            "The server is converting the video to the 1080p you chose.",
            playbackStory(tracks.copy(mode = PlaybackMode.Transcode, quality = StreamQuality.FullHd, autoStep = null)),
        )
        assertTrue(playbackStory(tracks.copy(mode = null)).startsWith("Starting."))

        val lines = playbackInfo(tracks, "Living room server").associate { it.what to it.value }
        assertEquals("4K HDR, as it is", lines["Video"])
        assertEquals("English · EAC3 5.1, as it is", lines["Sound"])
        assertEquals("Off", lines["Subtitles"])
        assertEquals("Automatic, on the original", lines["Quality"])
        assertEquals("About 25 Mbps", lines["Stream"])
        assertEquals("Living room server", lines["Server"])

        val converted = playbackInfo(lowered.copy(reasons = listOf("video codec hevc not supported")), null).associate { it.what to it.value }
        assertEquals("720p, converted for this TV", converted["Video"])
        assertEquals("Automatic, lowered to 720p", converted["Quality"])
        assertEquals("Video codec hevc not supported", converted["Why"])
        assertNull(converted["Server"])
    }

    @Test fun `a problem says why, and where the play is kept`() {
        val base = PlayerUiState(title = "Harbor Lights", subtitle = "S03 E07 · The Long Dark")
        assertNull(problemOf(base, "Living room server", 0))

        val lost = problemOf(base.copy(status = PlayerStatus.Error, error = "Lost the connection to the server."), "Living room server", 1_390_000)!!
        assertEquals(ProblemKind.Lost, lost.kind)
        assertEquals("Lost Living room server", lost.title)
        assertTrue(lost.body!!.contains("carries on from 23:10"))

        val decoder = problemOf(base.copy(status = PlayerStatus.Error, error = "This device could not decode the stream."), null, 2_760_000)!!
        assertEquals(ProblemKind.CantPlay, decoder.kind)
        assertEquals("This TV can’t play this picture", decoder.title)
        assertEquals("Your place is kept at 46:00.", decoder.note)

        val stopped = problemOf(base.copy(stopMessage = "Restarting the server, back in five minutes."), "Living room server", 1_390_000)!!
        assertEquals(ProblemKind.Stopped, stopped.kind)
        assertEquals("Whoever looks after Living room server stopped it and left a note: “Restarting the server, back in five minutes.”", stopped.body)

        val still = problemOf(base.copy(askStillWatching = true), null, 0)!!
        assertEquals(ProblemKind.StillWatching, still.kind)
        assertEquals("Still watching Harbor Lights?", still.title)

        // An admin's stop comes before Still watching, as the overlays do.
        assertEquals(ProblemKind.Stopped, problemOf(base.copy(askStillWatching = true, stopMessage = "Bye"), null, 0)!!.kind)
    }

    @Test fun `a lost server is tried again sooner first, and every minute at most`() {
        assertEquals(listOf(10, 20, 30, 40, 50, 60, 60), (0..6).map(::retryDelaySeconds))
    }

    @Test fun `Skip's label is in sentence case`() {
        assertEquals("Skip intro", sentenceCase("Skip Intro"))
        assertEquals("Skip credits", sentenceCase("Skip Credits"))
    }
}
