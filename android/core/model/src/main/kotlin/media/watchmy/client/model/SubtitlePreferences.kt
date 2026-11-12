package media.watchmy.client.model

import java.util.Locale

const val SUBTITLE_PREFERENCES_API_LEVEL = 18

/**
 * The server level that takes audio languages, [SubtitlePreferences.foreignAudio]
 * and [PlaybackProfile.selectsAudioTrack] (PLAN-LANGUAGES.md §4). Older servers
 * refuse those fields, so they are left out below it.
 */
const val AUDIO_LANGUAGES_API_LEVEL = 21

/** The person's audio languages, in order; empty plays each file's default track. */
data class AudioPreferences(val languages: List<String> = emptyList())

data class SubtitlePreferences(
    val languages: List<String> = emptyList(),
    val mode: SubtitleMode = SubtitleMode.Forced,
    val automaticDownload: Boolean = false,
    /**
     * With [SubtitleMode.Forced]: full subtitles when the audio isn't in the
     * person's languages. A flag beside the mode, not a fourth mode, so
     * released apps read it as Forced only and leave it alone when they save.
     */
    val foreignAudio: Boolean = false,
) {
    /** How subtitles start, as the settings pages offer it. */
    val choice: SubtitleChoice
        get() = when (mode) {
            SubtitleMode.Off -> SubtitleChoice.Off
            SubtitleMode.On -> SubtitleChoice.On
            SubtitleMode.Forced -> if (foreignAudio) SubtitleChoice.ForeignAudio else SubtitleChoice.Forced
        }

    fun withChoice(choice: SubtitleChoice) = copy(mode = choice.mode, foreignAudio = choice == SubtitleChoice.ForeignAudio)

    /** Automatic download needs a choice that can start subtitles, and a language to look for. */
    val automaticAvailable: Boolean
        get() = (choice == SubtitleChoice.On || choice == SubtitleChoice.ForeignAudio) && languages.isNotEmpty()
}

/** How subtitles are saved on the server: off, forced or on. */
enum class SubtitleMode(val id: String) {
    Off("off"),
    Forced("forced"),
    On("on"),
}

/**
 * How subtitles start, in the settings pages' order and words
 * (PLAN-LANGUAGES.md §5.6). [ForeignAudio] is saved as [SubtitleMode.Forced]
 * with [SubtitlePreferences.foreignAudio].
 */
enum class SubtitleChoice(val mode: SubtitleMode, val label: String, val shortLabel: String, val detail: String) {
    Off(SubtitleMode.Off, "Off", "Off", "Start without subtitles."),
    Forced(SubtitleMode.Forced, "Forced only", "Forced only", "Only parts the file marks as forced, such as a line in another language."),
    ForeignAudio(
        SubtitleMode.Forced, "When the audio isn’t in my languages", "For foreign audio",
        "Forced only when you follow the audio. Full subtitles when it isn’t in your audio languages.",
    ),
    On(SubtitleMode.On, "On", "On", "Always start with a subtitle in your first language that has one."),
    ;

    companion object {
        /** The choices a server at [apiLevel] can save: the foreign-audio one from [AUDIO_LANGUAGES_API_LEVEL]. */
        fun offered(apiLevel: Int): List<SubtitleChoice> = if (apiLevel >= AUDIO_LANGUAGES_API_LEVEL) entries else entries - ForeignAudio
    }
}

/** An explicit Off is distinct from asking the server to apply preferences. */
enum class SubtitleRequestMode(val id: String) { Auto("auto"), Off("off") }

/** Media3's text cues do not retain full ASS typesetting or vector drawings. */
val SubtitleOption.requiresBurnIn: Boolean
    get() = burnInOnly || format?.lowercase() in setOf("ass", "ssa")

data class SubtitleLanguageOption(val tag: String, val label: String)

/** Human-readable choices for audio and subtitle languages, with useful script/region variants kept distinct. */
fun subtitleLanguageOptions(display: Locale = Locale.getDefault()): List<SubtitleLanguageOption> =
    (Locale.getISOLanguages().toList() + listOf("pt-BR", "pt-PT", "zh-Hans", "zh-Hant", "en-GB", "en-US", "es-419"))
        .distinct().map { tag -> SubtitleLanguageOption(tag, Locale.forLanguageTag(tag).getDisplayName(display).replaceFirstChar { it.titlecase(display) }) }
        .sortedBy { it.label.lowercase(display) }

fun subtitleLanguageLabel(tag: String, display: Locale = Locale.getDefault()): String =
    Locale.forLanguageTag(tag).getDisplayName(display).ifBlank { tag }.replaceFirstChar { it.titlecase(display) }

/**
 * The Audio & subtitles row's value on a tablet: the first audio language (or
 * "File default") and how subtitles start, "Spanish · For foreign audio".
 * A server without audio languages shows the subtitle choice alone.
 */
fun languagesSummary(preferences: PlaybackPreferences, audioLanguages: Boolean, display: Locale = Locale.getDefault()): String {
    val choice = preferences.subtitles.choice.shortLabel
    if (!audioLanguages) return choice
    val audio = preferences.audio.languages.firstOrNull()?.let { subtitleLanguageLabel(it, display) } ?: "File default"
    return "$audio · $choice"
}
