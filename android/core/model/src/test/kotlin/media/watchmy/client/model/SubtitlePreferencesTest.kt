package media.watchmy.client.model

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitlePreferencesTest {
    @Test fun `the choice is saved as a mode and the flag`() {
        val prefs = SubtitlePreferences(listOf("en"))
        assertEquals(SubtitleChoice.Forced, prefs.choice)
        val foreign = prefs.withChoice(SubtitleChoice.ForeignAudio)
        assertEquals(SubtitleMode.Forced, foreign.mode)
        assertTrue(foreign.foreignAudio)
        assertEquals(SubtitleChoice.ForeignAudio, foreign.choice)
        assertFalse(foreign.withChoice(SubtitleChoice.On).foreignAudio)
        // On with the flag left over (an older app saved it): On.
        assertEquals(SubtitleChoice.On, foreign.copy(mode = SubtitleMode.On).choice)
        assertTrue(foreign.automaticAvailable)
        assertFalse(foreign.copy(languages = emptyList()).automaticAvailable)
        assertFalse(prefs.automaticAvailable)
    }

    @Test fun `the row value names the first audio language and the choice`() {
        val prefs = PlaybackPreferences(
            subtitles = SubtitlePreferences(mode = SubtitleMode.Forced, foreignAudio = true),
            audio = AudioPreferences(listOf("es", "en")),
        )
        assertEquals("Spanish · For foreign audio", languagesSummary(prefs, audioLanguages = true, display = Locale.ENGLISH))
        assertEquals("File default · Off", languagesSummary(PlaybackPreferences(subtitles = SubtitlePreferences(mode = SubtitleMode.Off)), audioLanguages = true, display = Locale.ENGLISH))
        assertEquals("Forced only", languagesSummary(PlaybackPreferences(), audioLanguages = false, display = Locale.ENGLISH))
    }

    @Test fun `servers before audio languages are not offered the foreign-audio choice`() {
        assertEquals(SubtitleChoice.entries, SubtitleChoice.offered(AUDIO_LANGUAGES_API_LEVEL))
        assertFalse(SubtitleChoice.ForeignAudio in SubtitleChoice.offered(AUDIO_LANGUAGES_API_LEVEL - 1))
    }
}
