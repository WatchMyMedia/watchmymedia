package media.watchmy.client.core.playback

import media.watchmy.client.core.playback.profile.ProfileProvider
import media.watchmy.client.model.AudioOption
import media.watchmy.client.model.AudioPreferences
import media.watchmy.client.model.DecisionSubtitle
import media.watchmy.client.model.PlaybackMode
import media.watchmy.client.model.PlaybackPreferences
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.StreamProtocol
import media.watchmy.client.model.SubtitleMode
import media.watchmy.client.model.SubtitleOption
import media.watchmy.client.model.SubtitlePreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** PLAN-LANGUAGES.md §5.2 and §5.5: the decided track in direct play, and offline plays choosing on the device. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AudioLanguagesTest {
    private val english = AudioOption(1, "English · AAC 2.0 (Default)", "eng", true, "aac", 2)
    private val spanish = AudioOption(2, "Spanish · AAC 2.0", "spa", false, "aac", 2)
    private val commentary = AudioOption(3, "English · AAC 2.0", "eng", false, "aac", 2, title = "Commentary")
    private val selecting = ProfileProvider { TV_PROFILE.copy(selectsAudioTrack = true) }

    private fun sub(id: String, language: String, forced: Boolean = false) =
        DecisionSubtitle(SubtitleOption(id, "Sub $id", language, forced, false, "SRT"), "webvtt", "http://srv/api/files/1/subtitles/$id.vtt")

    private fun prefs(vararg audio: String, subtitles: SubtitlePreferences = SubtitlePreferences()) =
        PlaybackPreferences(subtitles = subtitles, audio = AudioPreferences(audio.toList()))

    @Test fun `a direct play starts on the decision's track, which the profile lets stay direct`() {
        val h = Harness(profileProvider = selecting)
        try {
            h.start(PlayRequest(1, 0, "Copper Valley"), decision(protocol = StreamProtocol.File, audio = listOf(english, spanish, commentary), selectedAudio = 2))
            assertEquals(true, h.gateway.decisions.single().profile.selectsAudioTrack)
            assertEquals(1, h.player.loads.last().audioOrdinal)
            assertEquals(3, h.player.loads.last().audioTracks)
            assertEquals(2, h.state.selectedAudio)
        } finally { h.release() }
    }

    @Test fun `a player that can't select the track asks again for a remux of it, once`() {
        val h = Harness(profileProvider = selecting)
        try {
            h.start(PlayRequest(1, 0, "Copper Valley")) { r ->
                if (r.profile.selectsAudioTrack == true) decision(protocol = StreamProtocol.File, audio = listOf(english, spanish), selectedAudio = 2)
                else decision(audio = listOf(spanish), selectedAudio = r.audioIndex, sessionId = "s2", mode = PlaybackMode.Remux)
            }
            h.advance(5_000)
            h.player.audioUnavailable(); h.run()
            assertEquals(2, h.gateway.decisions.size)
            val again = h.gateway.decisions.last()
            assertEquals(2, again.audioIndex)
            assertNull(again.profile.selectsAudioTrack)
            assertTrue(h.player.loads.last().hls)
            assertEquals(2, h.state.selectedAudio)
            // Later decisions of this play leave the flag out too, and a second report changes nothing.
            h.player.audioUnavailable(); h.run()
            assertEquals(2, h.gateway.decisions.size)
        } finally { h.release() }
    }

    @Test fun `the default track needs no fallback`() {
        val h = Harness(profileProvider = selecting)
        try {
            h.start(PlayRequest(1, 0, "Copper Valley"), decision(protocol = StreamProtocol.File, audio = listOf(english, spanish), selectedAudio = 1))
            h.player.audioUnavailable(); h.run()
            assertEquals(1, h.gateway.decisions.size)
        } finally { h.release() }
    }

    @Test fun `an original file played offline starts in the person's audio language`() {
        val subs = listOf(sub("en", "eng"), sub("es-forced", "spa", forced = true))
        val h = Harness(preferences = prefs("es", "en"), profileProvider = selecting)
        try {
            h.start(PlayRequest(1, 0, "Copper Valley"), decision(protocol = StreamProtocol.File, audio = listOf(english, spanish, commentary), selectedAudio = 1, subtitles = subs).copy(isOffline = true))
            assertEquals(2, h.state.selectedAudio)
            assertEquals(1, h.player.loads.last().audioOrdinal)
            // Forced only follows the chosen audio: the Spanish forced track.
            assertEquals("es-forced", h.state.selectedSubtitle)
            // A pick stays; the player switches the file's own track.
            h.controller.selectAudio(1); h.run()
            assertEquals(1, h.state.selectedAudio)
            assertEquals(1, h.gateway.decisions.size)
        } finally { h.release() }
    }

    @Test fun `offline, audio the person doesn't follow starts subtitles for foreign audio`() {
        val subs = listOf(sub("en", "eng"), sub("es", "spa"))
        val foreign = SubtitlePreferences(listOf("en"), SubtitleMode.Forced, foreignAudio = true)
        val h = Harness(preferences = prefs("en", subtitles = foreign))
        try {
            h.start(PlayRequest(1, 0, "Copper Valley"), decision(protocol = StreamProtocol.File, audio = listOf(spanish.copy(isDefault = true)), selectedAudio = 2, subtitles = subs).copy(isOffline = true))
            assertEquals(2, h.state.selectedAudio)
            assertEquals("en", h.state.selectedSubtitle)
        } finally { h.release() }
    }

    @Test fun `offline, a track the player can't select falls back to the default it plays`() {
        val h = Harness(preferences = prefs("es"))
        try {
            h.start(PlayRequest(1, 0, "Copper Valley"), decision(protocol = StreamProtocol.File, audio = listOf(english, spanish), selectedAudio = 1).copy(isOffline = true))
            assertEquals(2, h.state.selectedAudio)
            h.player.audioUnavailable(); h.run()
            assertEquals(1, h.state.selectedAudio)
            assertEquals(1, h.gateway.decisions.size)
        } finally { h.release() }
    }

    @Test fun `automatic download follows subtitles for foreign audio`() {
        val foreign = SubtitlePreferences(listOf("en"), SubtitleMode.Forced, automaticDownload = true, foreignAudio = true)
        val korean = AudioOption(1, "Korean", "kor", true, "aac", 2)
        val h = Harness(preferences = prefs("en", subtitles = foreign))
        try {
            h.start(d = decision(audio = listOf(korean), selectedAudio = 1))
            assertEquals(listOf("a1"), h.gateway.automaticCalls)
        } finally { h.release() }
        val followed = Harness(preferences = prefs("en", subtitles = foreign))
        try {
            followed.start(d = decision(audio = listOf(english), selectedAudio = 1))
            assertTrue(followed.gateway.automaticCalls.isEmpty())
        } finally { followed.release() }
    }
}
