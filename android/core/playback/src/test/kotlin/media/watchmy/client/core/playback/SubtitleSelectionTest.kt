package media.watchmy.client.core.playback

import media.watchmy.client.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SubtitleSelectionTest {
    private fun track(id: String, language: String, forced: Boolean = false, format: String = "SRT") =
        SubtitleOption(id, language, language, forced, false, format)

    @Test fun `complete preferred tracks beat forced fallbacks and ASS never becomes flat offline text`() {
        val tracks = listOf(track("en-forced", "eng", true), track("es", "spa"), track("ass", "en", format = "ASS"))
        val preferences = SubtitlePreferences(listOf("en-US", "es"), SubtitleMode.On)
        assertEquals("es", preferredSubtitleId(tracks, "en", emptyList(), preferences))
        assertEquals("en-forced", preferredSubtitleId(tracks, "en", emptyList(), preferences.copy(mode = SubtitleMode.Forced)))
        assertNull(preferredSubtitleId(tracks, "en", emptyList(), preferences.copy(mode = SubtitleMode.Off)))
    }

    @Test fun `traditional Chinese never matches a simplified track and bibliographic aliases match`() {
        val prefs = SubtitlePreferences(listOf("zh-Hant"), SubtitleMode.On)
        assertNull(preferredSubtitleId(listOf(track("simplified", "zh-CN")), null, emptyList(), prefs))
        assertEquals("traditional", preferredSubtitleId(listOf(track("traditional", "zh-TW")), null, emptyList(), prefs))
        assertEquals("fr", preferredSubtitleId(listOf(track("fr", "fre")), null, emptyList(), prefs.copy(languages = listOf("fr-CA"))))
    }

    /*
     * TestPreferredAudio in server/internal/playback/audio_preferences_test.go.
     * The device has no dispositions (the server's replies leave them out),
     * so tracks 7 and 9, flagged there, carry the titles that mark them here.
     */
    private fun audio(index: Int, codec: String, channels: Int, language: String, default: Boolean = false, title: String? = null) =
        AudioOption(index, language, language, default, codec, channels, title)

    private val tracks = listOf(
        audio(1, "ac3", 6, "eng", default = true),
        audio(2, "truehd", 8, "eng", title = "TrueHD Atmos"),
        audio(3, "aac", 2, "spa"),
        audio(4, "aac", 2, "spa", title = "Director's Commentary"),
        audio(5, "aac", 2, "por"),
        audio(6, "aac", 2, "pt-BR"),
        audio(7, "aac", 2, "jpn", title = "Audio Description"),
        audio(8, "aac", 2, "chi"),
        audio(9, "aac", 2, "fre", title = "Commentary"),
        audio(10, "aac", 2, "fre", title = "Director's Cut"),
        audio(11, "aac", 2, "und"),
    )

    private fun profile(vararg codecs: String, maxChannels: Int? = null) = PlaybackProfile("android", listOf("mp4"), listOf("h264"), codecs.toList(), maxAudioChannels = maxChannels)

    @Test fun `preferred audio follows the server's table`() {
        val ac3 = profile("aac", "ac3")
        val truehd = profile("aac", "ac3", "truehd", maxChannels = 8)
        data class Case(val name: String, val languages: List<String>, val profile: PlaybackProfile, val want: Int?)
        for (c in listOf(
            Case("no languages", emptyList(), ac3, null),
            Case("first language the file has", listOf("de", "es", "en"), ac3, 3),
            Case("ISO 639-2 against BCP 47", listOf("en"), ac3, 1),
            Case("copyable before more channels", listOf("en"), profile("aac", "truehd"), 2),
            Case("default before more channels when both copy", listOf("en"), truehd, 1),
            Case("commentary never by language", listOf("es"), profile("aac"), 3),
            Case("exact region before the base language", listOf("pt-BR"), ac3, 6),
            Case("base language when the region is missing", listOf("pt-PT"), ac3, 5),
            Case("audio description skipped", listOf("ja"), ac3, null),
            Case("script does not matter for speech", listOf("zh-Hant"), ac3, 8),
            Case("commentary skipped, Director's Cut kept", listOf("fr"), ac3, 10),
            Case("und never matches", listOf("und"), ac3, null),
        )) {
            assertEquals(c.name, c.want, preferredAudioIndex(tracks, c.profile, c.languages))
        }
    }

    @Test fun `audio a person follows`() {
        // TestUnderstoodAudio.
        data class Case(val audio: String, val audioLanguages: List<String>, val subtitles: List<String>, val understood: Boolean)
        for (c in listOf(
            Case("eng", listOf("en"), emptyList(), true),
            Case("kor", listOf("en"), emptyList(), false),
            Case("kor", emptyList(), listOf("en"), false),
            Case("kor", emptyList(), emptyList(), true),
            Case("und", listOf("en"), emptyList(), true),
            Case("", listOf("en"), emptyList(), true),
            Case("spa", listOf("en", "es"), listOf("fr"), true),
            Case("fra", listOf("en"), listOf("fr"), false),
            Case("zho", listOf("zh-Hant"), emptyList(), true),
        )) {
            assertEquals("${c.audio} ${c.audioLanguages} ${c.subtitles}", c.understood, understoodAudio(c.audio, c.audioLanguages, c.subtitles))
        }
    }

    @Test fun `subtitles for foreign audio`() {
        // TestForeignAudioSubtitles.
        val subs = listOf(track("en-forced", "eng", forced = true), track("en-full", "eng"), track("es-full", "spa"))
        val foreign = SubtitlePreferences(mode = SubtitleMode.Forced, foreignAudio = true)
        data class Case(val name: String, val prefs: SubtitlePreferences, val audio: String, val audioLanguages: List<String>, val want: String?)
        for (c in listOf(
            Case("followed audio: forced only", foreign.copy(languages = listOf("es")), "eng", listOf("en"), "en-forced"),
            Case("foreign audio: subtitle languages", foreign.copy(languages = listOf("es", "en")), "kor", listOf("en"), "es-full"),
            Case("foreign audio: audio languages when no subtitle languages", foreign, "kor", listOf("en"), "en-full"),
            Case("foreign by subtitle languages when no audio languages", foreign.copy(languages = listOf("en")), "kor", emptyList(), "en-full"),
            Case("untagged audio counts as followed", foreign.copy(languages = listOf("en")), "und", listOf("en"), null),
            Case("no languages at all: forced only", foreign, "kor", emptyList(), null),
            Case("on ignores the flag", SubtitlePreferences(listOf("en"), SubtitleMode.On, foreignAudio = true), "eng", listOf("en"), "en-full"),
            Case("off ignores the flag", SubtitlePreferences(listOf("en"), SubtitleMode.Off, foreignAudio = true), "kor", listOf("en"), null),
            Case("forced without the flag stays forced", SubtitlePreferences(listOf("en"), SubtitleMode.Forced), "kor", listOf("en"), null),
        )) {
            assertEquals(c.name, c.want, preferredSubtitleId(subs, c.audio, c.audioLanguages, c.prefs))
        }
    }
}
