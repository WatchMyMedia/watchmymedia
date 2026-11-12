package media.watchmy.client.core.playback

import android.icu.util.ULocale
import java.util.Locale
import media.watchmy.client.model.AudioOption
import media.watchmy.client.model.PlaybackProfile
import media.watchmy.client.model.SubtitleMode
import media.watchmy.client.model.SubtitleOption
import media.watchmy.client.model.SubtitlePreferences
import media.watchmy.client.model.requiresBurnIn

/*
 * How an offline play chooses its starting tracks on the device, with no
 * server: the mirror of server/internal/playback/audio_preferences.go and
 * subtitle_preferences.go (PLAN-LANGUAGES.md §5.1 and §5.3), and of
 * SubtitleSelection.swift in the Apple apps. Change them together; the tests
 * copy the Go test tables.
 */

/** Only finished local text tracks are supplied here; no provider or server access. */
internal fun preferredSubtitleId(
    tracks: List<SubtitleOption>,
    audioLanguage: String?,
    audioLanguages: List<String>,
    preferences: SubtitlePreferences,
): String? {
    if (tracks.none { !it.requiresBurnIn }) return null
    fun best(language: String?, forced: Boolean): String? {
        val wanted = subtitleLanguage(language) ?: return null
        return tracks.asSequence().filter { !it.requiresBurnIn && it.forced == forced }
            .mapNotNull { track -> subtitleLanguage(track.language)?.let { track.id to languageMatch(it, wanted) } }
            .filter { it.second > 0 }.maxByOrNull { it.second }?.first
    }
    val (mode, languages) = startingSubtitles(audioLanguage, audioLanguages, preferences)
    return when (mode) {
        SubtitleMode.Off -> null
        SubtitleMode.Forced -> best(audioLanguage, true)
        SubtitleMode.On -> listOf(false, true).firstNotNullOfOrNull { forced -> languages.firstNotNullOfOrNull { best(it, forced) } }
    }
}

/**
 * How subtitles start for audio in [audioLanguage]: foreignAudio is Forced
 * while the person follows the audio, and On (in their subtitle languages,
 * else their audio languages) when they don't. Otherwise the saved mode.
 */
internal fun startingSubtitles(audioLanguage: String?, audioLanguages: List<String>, p: SubtitlePreferences): Pair<SubtitleMode, List<String>> {
    if (p.mode == SubtitleMode.Forced && p.foreignAudio && !understoodAudio(audioLanguage, audioLanguages, p.languages)) {
        return SubtitleMode.On to p.languages.ifEmpty { audioLanguages }
    }
    return p.mode to p.languages
}

/**
 * The audio track to start on for the person's [languages]: the best track
 * in the first language that the file has; null when none matches, and the
 * file's default stays. Within a language: the exact tag over the same base
 * language, a track [profile] plays as it is over one it would convert, the
 * file's default flag, more channels, then the lower index. Commentary and
 * audio description are only ever picked by hand.
 */
internal fun preferredAudioIndex(tracks: List<AudioOption>, profile: PlaybackProfile?, languages: List<String>): Int? {
    for (want in languages) {
        var best: AudioOption? = null
        var bestMatch = 0
        for (a in tracks) {
            if (secondaryAudio(a)) continue
            val match = audioLanguageMatch(a.language, want)
            if (match == 0) continue
            if (best == null || betterAudio(a, match, best, bestMatch, profile)) {
                best = a
                bestMatch = match
            }
        }
        if (best != null) return best.index
    }
    return null
}

private fun betterAudio(a: AudioOption, aMatch: Int, b: AudioOption, bMatch: Int, profile: PlaybackProfile?): Boolean {
    if (aMatch != bMatch) return aMatch > bMatch
    val ac = copyableAudio(a, profile)
    val bc = copyableAudio(b, profile)
    if (ac != bc) return ac
    if (a.isDefault != b.isDefault) return a.isDefault
    if (a.channels != b.channels) return a.channels > b.channels
    return a.index < b.index
}

private fun copyableAudio(a: AudioOption, profile: PlaybackProfile?): Boolean {
    profile ?: return true
    val codec = a.codec?.lowercase(Locale.ROOT)?.let { if (it.startsWith("pcm_")) "pcm" else it } ?: return false
    val max = profile.maxAudioChannels ?: 0
    return profile.audioCodecs.any { it.equals(codec, ignoreCase = true) } && (max == 0 || a.channels <= max)
}

/** "Director's Cut" is a main track, so "director" alone is not one of them. */
private val SECONDARY_AUDIO_WORDS = listOf("commentary", "description", "descriptive", "described")

/**
 * A commentary or audio-description track, by its title. The server also
 * goes by ffprobe's comment and visual_impaired dispositions, which its
 * replies leave out, so the device knows such a track by its title alone.
 */
internal fun secondaryAudio(a: AudioOption): Boolean {
    val title = a.title?.lowercase(Locale.ROOT) ?: return false
    return SECONDARY_AUDIO_WORDS.any { it in title }
}

/**
 * [languageMatch] for speech: the script a language is written in does not
 * matter, so a zh-Hant reader matches Chinese audio. Exact language and
 * region is 2, the same base language 1.
 */
internal fun audioLanguageMatch(got: String?, want: String?): Int {
    val a = subtitleLanguage(got) ?: return 0
    val b = subtitleLanguage(want) ?: return 0
    if (a.toLanguageTag() == b.toLanguageTag()) return 2
    return if (a.language == b.language) 1 else 0
}

/**
 * Whether a person follows audio in [audioLanguage]: it is one of their audio
 * languages or, when they have none, one of their subtitle languages. An
 * untagged track, or a person with no languages at all, counts as followed.
 */
internal fun understoodAudio(audioLanguage: String?, audioLanguages: List<String>, subtitleLanguages: List<String>): Boolean {
    if (subtitleLanguage(audioLanguage) == null) return true
    val known = audioLanguages.ifEmpty { subtitleLanguages }
    if (known.isEmpty()) return true
    return known.any { audioLanguageMatch(audioLanguage, it) > 0 }
}

private fun subtitleLanguage(tag: String?): ULocale? {
    val value = tag?.trim()?.replace('_', '-') ?: return null
    if (value.length !in 1..64 || !value.matches(Regex("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*"))) return null
    val locale = ULocale.createCanonical(value)
    return locale.takeIf { it.language != "und" && it.language in ULocale.getISOLanguages() }
}

private fun languageMatch(found: ULocale, wanted: ULocale): Int {
    if (found.language != wanted.language) return 0
    if (found.toLanguageTag() == wanted.toLanguageTag()) return 2
    if ((wanted.script.isNotEmpty() || wanted.country.isNotEmpty()) &&
        ULocale.addLikelySubtags(found).script != ULocale.addLikelySubtags(wanted).script
    ) return 0
    return 1
}
